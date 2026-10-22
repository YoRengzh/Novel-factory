package cn.novel.yonren.domain.novel.service.job;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.model.aggregate.StoryGenerateResultAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.QualityDebtEntity;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.StoryGenerateService;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.domain.novel.service.armory.plan.ChapterPlanChecks;
import cn.novel.yonren.domain.novel.service.armory.quality.BatchHealthReport;
import cn.novel.yonren.domain.novel.service.armory.quality.BatchHealthService;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import cn.novel.yonren.types.exception.PlanApprovalSuspendedException;
import cn.novel.yonren.types.utils.SnowflakeIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import cn.novel.yonren.types.enums.JobStatus;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 作业编排服务：异步提交 → 单线程 worker 执行 → 进度/取消/章节计划裁决/终态。
 * D9 个人用范围：只做触发/进度/取消/裁决，无列表/详情/导出；job-status.json 仅供崩溃后人工查看。
 * MDC trace-id 与 jobId 同源，生成期间全部日志与 LLM usage 记账按 job 归因
 */
@Service
@Slf4j
public class StoryJobService {

    /** workerId=2：storyId 已占用 workerId=1（PersistChapterPlanNode），双实例同 workerId 同毫秒会碰撞 */
    private static final SnowflakeIdWorker JOB_ID_WORKER = new SnowflakeIdWorker(2);

    /** 审批门挂起的作业（内存态，与 JobRegistry 同生命周期：进程重启即失效，job-status.json 仅供人工查看） */
    private final Map<String, PendingApproval> pendingApprovals = new ConcurrentHashMap<>();

    /**
     * 审批超时计时器：独立单线程守护调度器。
     * **不复用 jobExecutor**——用唯一的生成线程去 sleep 等超时，等于把整个作业队列冻住。
     * 守护线程 + 关闭时显式收尾：应用退出不因它挂起
     */
    private final ScheduledExecutorService approvalTimer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "plan-approval-timer");
        t.setDaemon(true);
        return t;
    });

    @jakarta.annotation.PreDestroy
    void shutdownApprovalTimer() {
        approvalTimer.shutdownNow();
    }

    private final StoryGenerateService storyGenerateService;
    private final JobRegistry jobRegistry;
    private final IStoryRepository storyRepository;
    private final ThreadPoolExecutor jobExecutor;
    private final RunPlanService runPlanService;
    private final BatchHealthService batchHealthService;
    private final StoryProperties storyProperties;

    public StoryJobService(StoryGenerateService storyGenerateService,
                           JobRegistry jobRegistry,
                           IStoryRepository storyRepository,
                           @Qualifier("jobExecutor") ThreadPoolExecutor jobExecutor,
                           RunPlanService runPlanService,
                           BatchHealthService batchHealthService,
                           StoryProperties storyProperties) {
        this.storyGenerateService = storyGenerateService;
        this.jobRegistry = jobRegistry;
        this.storyRepository = storyRepository;
        this.jobExecutor = jobExecutor;
        this.runPlanService = runPlanService;
        this.batchHealthService = batchHealthService;
        this.storyProperties = storyProperties;
    }

    /**
     * 异步提交：注册作业并投递到 jobExecutor 后立即返回。
     * 队列满（AbortPolicy 拒绝）时作业置 FAILED 并抛 AppException，由 trigger 层转 503
     */
    public GenerationJob submit(ArmoryCommandEntity command) {
        return submit(command, 1);
    }

    /**
     * 异步提交（指定批序号）。自动续批走这里：批序号决定 run-plan.max-batches 是否已达上限，
     * 也让作业状态能读出"这是第几批"，日志与归因不必再去数批次区间。
     */
    public GenerationJob submit(ArmoryCommandEntity command, int batchRound) {
        GenerationJob job = new GenerationJob("job-" + JOB_ID_WORKER.nextId());
        job.setBatchRound(Math.max(1, batchRound));
        // 续写作业提交即知故事目录，先落内存保证崩溃可见性最好
        if (StringUtils.isNotBlank(command.getResumeStoryDir())) {
            job.setStoryDirName(command.getResumeStoryDir());
        }
        jobRegistry.register(job);
        try {
            jobExecutor.execute(() -> runJob(command, job));
        } catch (RejectedExecutionException e) {
            job.markFailed("作业队列已满，请稍后重试");
            log.warn("作业 {} 投递被拒：队列已满", job.getJobId());
            throw new AppException(ResponseCode.UN_ERROR.getCode(), "作业队列已满，请稍后重试");
        }
        return job;
    }

    public GenerationJob get(String jobId) {
        return jobRegistry.get(jobId);
    }

    /**
     * 当前待裁决的章节计划（AI 原版，供状态查询透出给前端展示与编辑）；非挂起态返回 null。
     * 计划同时已落盘到 runDir/chapter-plan.yml，前端也可直接从故事目录读取
     */
    public ChapterPlanAggregate pendingChapterPlan(String jobId) {
        PendingApproval pending = pendingApprovals.get(jobId);
        if (pending != null) {
            return pending.plan;
        }
        // 不变式：状态置位（审批门节点）与待裁决记录登记（本类）非原子，中间有微窗口；
        // 作业在置位时已携带计划，故"状态可见 ⇒ 计划可读"成立。非挂起态一律 null——
        // 裁决退出走 exitApprovalWait，作业侧计划已随之清空，不会读到过期内容
        GenerationJob job = jobRegistry.get(jobId);
        return job != null && job.isAwaitingApproval() ? job.getAwaitingPlan() : null;
    }

    /** 协作取消：CREATED 直接置 CANCELLED；RUNNING 置 CANCELLING（当前章完成后停）；终态幂等。未知返回 null */
    public GenerationJob cancel(String jobId) {
        GenerationJob job = jobRegistry.cancel(jobId);
        if (job != null && job.getStatus() == JobStatus.CANCELLING) {
            // 故事目录已知（续写/首章后）时立即把取消受理态写盘；未知则等 worker 终态落盘
            persistJobStatusIfPossible(null, job);
        }
        // 审批门挂起中取消：清掉待裁决记录并撤销超时计时器，避免计时器到点后又去续跑
        clearPending(jobId);
        return job;
    }


    public GenerationJob approveChapterPlan(String jobId, ChapterPlanAggregate editedPlan) {
        PendingApproval pending = pendingApprovals.get(jobId);
        if (pending == null || !pending.job.isAwaitingApproval()) {
            return null;
        }
        ChapterPlanAggregate plan = normalizeApprovedPlan(pending, editedPlan);
        validateApprovedPlan(pending, plan);

        if (!claimPending(jobId, pending)) {
            return null;
        }
        boolean edited = editedPlan != null && editedPlan.getChapters() != null;
        // 人工裁决才计数：超时自动放行不计入，观测层据此区分"人认可过计划"与"没人管"
        pending.job.recordHumanApproval();
        if (edited) {
            log.info("作业 {} 章节计划经人工裁决通过（携带修订稿，{} 章）",
                    jobId, plan.getChapters() == null ? 0 : plan.getChapters().size());
        } else {
            log.info("作业 {} 章节计划经人工裁决通过（采纳 AI 原版，未修订）", jobId);
        }
        enqueueResume(pending, plan);
        return pending.job;
    }

    /** 人工裁决：驳回 → 本批中止（计划已落盘，可另起命令复用）。返回 null 表示不在挂起态 */
    public GenerationJob rejectChapterPlan(String jobId) {
        PendingApproval pending = pendingApprovals.get(jobId);
        if (pending == null || !pending.job.isAwaitingApproval()) {
            return null;
        }
        if (!claimPending(jobId, pending)) {
            return null;
        }
        pending.job.exitApprovalWait();
        pending.job.markCancelled();
        // 驳回不是失败：终态复用 CANCELLED（已有的状态之一），语义细节记在观测字段
        pending.job.setCurrentStage("PLAN_REJECTED");
        log.info("作业 {} 章节计划被人工驳回，本批中止（已生成 {} 章，计划保留在 run 目录）",
                jobId, countChaptersQuietly(pending.job.getStoryDirName()));
        persistJobStatusIfPossible(pending.command, pending.job);
        return pending.job;
    }

    /**
     * 取走待裁决记录（原子：只有取到的一方可以推进作业，避免"审批与超时同时到点"的双重推进）。
     * 取到后撤销超时计时器
     */
    private boolean claimPending(String jobId, PendingApproval pending) {
        if (!pendingApprovals.remove(jobId, pending)) {
            return false;
        }
        ScheduledFuture<?> task = pending.timeoutTask;
        if (task != null) {
            task.cancel(false);
        }
        return true;
    }

    /** 清理待裁决记录（取消作业时调用；不改动作业状态，状态迁移归 cancel 路径） */
    private void clearPending(String jobId) {
        PendingApproval pending = pendingApprovals.remove(jobId);
        if (pending != null && pending.timeoutTask != null) {
            pending.timeoutTask.cancel(false);
        }
    }

    /**
     * 规整待批准的计划：未携带修订稿 → 采纳 AI 原版；
     * storyId 沿用原版（人工只需改剧情字段，不该被要求维护系统生成的 ID）
     */
    private ChapterPlanAggregate normalizeApprovedPlan(PendingApproval pending, ChapterPlanAggregate editedPlan) {
        if (editedPlan == null || editedPlan.getChapters() == null) {
            return pending.plan;
        }
        if (StringUtils.isBlank(editedPlan.getStoryId()) && pending.plan != null) {
            editedPlan.setStoryId(pending.plan.getStoryId());
        }
        return editedPlan;
    }

    /**
     * 修订稿机械校验：预期章数与起点取挂起时上下文里的实际规划口径——
     * 跨阶段批次的计划是惰性分段生成的，此刻只规划到首段，因此预期章数必须用
     * plannedChapterCount（首段章数）而非批次总章数，否则合法修订会被误判为"章数不符"
     */
    private void validateApprovedPlan(PendingApproval pending, ChapterPlanAggregate plan) {
        DefaultArmoryFactory.DynamicContext ctx = pending.dynamicContext;
        Integer plannedChapterCount = ctx.getPlannedChapterCount();
        int expectedCount = plannedChapterCount != null
                ? plannedChapterCount
                : pending.command.getStoryContextEntity().getChapterCount();
        int expectedStart = ctx.getChapterOffset() + 1;

        if (plan == null || plan.getChapters() == null || plan.getChapters().isEmpty()) {
            throw new AppException(ResponseCode.NULL_EXCEPTION.getCode(),
                    "章节计划不允许置空：请传原计划或修正后的完整计划（预期 " + expectedCount
                            + " 章，自第 " + expectedStart + " 章起）");
        }
        ChapterPlanChecks.validateSegmentStructure(plan.getChapters(), expectedCount, expectedStart);
    }

    /**
     * 裁决落地后重新入队：同一作业（同 jobId、同 run 目录）继续跑剩余阶段。
     * 投递失败按作业失败处理——此时已无法回到挂起态（线程已释放、上下文还在内存里但无人推进）
     */
    private void enqueueResume(PendingApproval pending, ChapterPlanAggregate approvedPlan) {
        if (!pending.job.exitApprovalWait()) {
            log.warn("作业 {} 裁决后无法回到待执行态（当前 {}），放弃续跑",
                    pending.job.getJobId(), pending.job.getStatus());
            return;
        }
        try {
            jobExecutor.execute(() -> runResumeJob(pending, approvedPlan));
        } catch (RejectedExecutionException e) {
            pending.job.markFailed("审批通过但作业队列已满，请携带 resumeStoryDir 重新提交本批");
            log.warn("作业 {} 审批后投递被拒：队列已满", pending.job.getJobId());
        }
    }

    /**
     * 审批超时处置。刻意只提供两种出路，且都是"已有状态之一"：
     * AUTO_APPROVE（默认）= 按 AI 原版计划继续，等同于退化成黑盒模式；
     * ABORT = 中止本批。不引入第三种终局语义，避免状态机组合膨胀
     */
    private void onApprovalTimeout(String jobId) {
        PendingApproval pending = pendingApprovals.get(jobId);
        if (pending == null || !pending.job.isAwaitingApproval()) {
            return;
        }
        String action = StringUtils.trimToEmpty(
                storyProperties.getPlanApproval() == null ? null
                        : storyProperties.getPlanApproval().getTimeoutAction()).toUpperCase();

        if ("ABORT".equals(action)) {
            if (!claimPending(jobId, pending)) {
                return;
            }
            pending.job.exitApprovalWait();
            pending.job.markCancelled();
            pending.job.setCurrentStage("PLAN_APPROVAL_TIMEOUT_ABORTED");
            log.info("作业 {} 章节计划审批超时，按 ABORT 中止本批（计划保留在 run 目录）", jobId);
            persistJobStatusIfPossible(pending.command, pending.job);
            return;
        }

        if (!claimPending(jobId, pending)) {
            return;
        }
        log.info("作业 {} 章节计划审批超时，按 AUTO_APPROVE 放行（退化为黑盒模式，采纳 AI 原版计划）", jobId);
        enqueueResume(pending, pending.plan);
    }

    /**
     * worker 线程执行体（jobExecutor 单线程串行，符合 质量 > token > 速度 的优先级）
     */
    private void runJob(ArmoryCommandEntity command, GenerationJob job) {
        MDC.put("trace-id", job.getJobId());
        // 上下文提到方法作用域：审批门挂起后要用同一个上下文续跑剩余阶段
        DefaultArmoryFactory.DynamicContext dynamicContext = storyGenerateService.newDynamicContext(job);
        try {
            // 排队期已取消：registry 已置 CANCELLED（markRunning 对终态拒绝）或取消信号已置位——
            // 跳过整棵规则树，不烧 token
            if (!job.markRunning() || job.isCancelRequested()) {
                job.markCancelled();
                return;
            }
            persistJobStatusIfPossible(command, job);
            StoryGenerateResultAggregate result = storyGenerateService.generate(command, job, dynamicContext);
            finishJob(command, job, result);
        } catch (PlanApprovalSuspendedException suspend) {
            // 审批挂起：**不是失败**。计划已落盘、作业已转 AWAITING_APPROVAL，
            // 此处直接归还线程，等裁决/超时后再用同一作业续跑剩余阶段
            registerPendingApproval(command, job, dynamicContext, suspend);
        } catch (Throwable e) {
            if (job.isCancelRequested()) {
                // 取消信号已置位时发生的异常（含规划期取消检查抛出）：按 CANCELLED 终态处理，
                // 不伪称失败——用户视角"我取消了"和"它坏了"是两回事
                job.markCancelled();
                job.setCurrentStage("CANCELLED_DURING_RUN");
                log.info("作业 {} 在取消请求后终止：{}", job.getJobId(), extractMessage(e));
            } else {
                job.markFailed(extractMessage(e));
                log.error("作业 {} 执行失败：{}", job.getJobId(), job.getErrorMessage(), e);
            }
        } finally {
            // 终态落盘（fail-soft）+ 线程复用前清理 MDC
            persistJobStatusIfPossible(command, job);
            MDC.remove("trace-id");
        }
    }

    /**
     * 审批通过/超时放行后的续跑执行体：从"正文生成"阶段继续，不重跑规划段。
     * 与 {@link #runJob} 共用收尾逻辑，状态机与自动续批行为保持一致
     */
    private void runResumeJob(PendingApproval pending, ChapterPlanAggregate approvedPlan) {
        GenerationJob job = pending.job;
        MDC.put("trace-id", job.getJobId());
        try {
            // markApprovedAndRequeue 已把状态置回 CREATED，此处按既有排队语义推进
            if (!job.markRunning() || job.isCancelRequested()) {
                job.markCancelled();
                return;
            }
            pending.dynamicContext.setChapterPlanAggregate(approvedPlan);
            persistJobStatusIfPossible(pending.command, job);
            StoryGenerateResultAggregate result = storyGenerateService.resumeAfterPlanApproval(
                    pending.command, job, pending.dynamicContext);
            finishJob(pending.command, job, result);
        } catch (Throwable e) {
            if (job.isCancelRequested()) {
                // 与 runJob 同款收敛：取消信号已置位时抛出的异常（含续跑期惰性规划的取消检查）
                // 按 CANCELLED 终态处理——此前这里一律 markFailed，用户取消会被误报成失败
                job.markCancelled();
                job.setCurrentStage("CANCELLED_DURING_RUN");
                log.info("作业 {} 在取消请求后终止：{}", job.getJobId(), extractMessage(e));
            } else {
                job.markFailed(extractMessage(e));
                log.error("作业 {} 审批后续跑失败：{}", job.getJobId(), job.getErrorMessage(), e);
            }
        } finally {
            persistJobStatusIfPossible(pending.command, job);
            MDC.remove("trace-id");
        }
    }

    /**
     * 正常路径收尾（终态 + 自动续批）。
     * 自动续批只在"正常完成"分支尝试：失败/取消/熔断都不续——失败续批只会把同一个错误再犯一遍，且更贵
     */
    private void finishJob(ArmoryCommandEntity command, GenerationJob job, StoryGenerateResultAggregate result) {
        if (job.isCancelRequested()) {
            job.markCancelled();
        } else if (job.isBudgetExceeded()) {
            // 预算熔断：正常停止而非失败——已完成章节均已落盘，终态按完结处理（缺口可 resume 补齐）
            job.markCompleted(result == null ? null : result.getStoryDirName());
            log.warn("作业 {} 因预算熔断停止：累计 token {}，已完成章节均已落盘，可携带 resumeStoryDir 续写补齐",
                    job.getJobId(), job.getTokensUsed());
        } else {
            job.markCompleted(result == null ? null : result.getStoryDirName());
            scheduleNextBatchIfNeeded(command, job, result);
        }
    }

    /** 登记待裁决记录并启动超时计时器（计时器线程独立，不占 jobExecutor） */
    private void registerPendingApproval(ArmoryCommandEntity command, GenerationJob job,
                                        DefaultArmoryFactory.DynamicContext dynamicContext,
                                        PlanApprovalSuspendedException suspend) {
        if (!job.isAwaitingApproval()) {
            // 挂起态的置位归审批门节点；这里兜底告警，避免"登记了待裁决但作业状态不对"
            // 表现为 approve 静默返回 null 这种极难排查的失效
            log.warn("作业 {} 抛出审批挂起信号但状态并非 AWAITING_APPROVAL（当前 {}），审批门可能未正确置位",
                    job.getJobId(), job.getStatus());
        }
        StoryProperties.PlanApprovalProperties cfg = storyProperties.getPlanApproval();
        long timeoutSeconds = cfg != null && cfg.getTimeoutSeconds() > 0 ? cfg.getTimeoutSeconds() : 7200L;
        PendingApproval pending = new PendingApproval(job, command, dynamicContext,
                dynamicContext.getChapterPlanAggregate());
        pending.timeoutTask = approvalTimer.schedule(() -> safeRunTimeout(job.getJobId()),
                timeoutSeconds, TimeUnit.SECONDS);
        pendingApprovals.put(job.getJobId(), pending);
        log.info("作业 {} 已挂起等待章节计划裁决：{}；超时 {} 秒后按 {} 处置",
                job.getJobId(), suspend.getMessage(), timeoutSeconds,
                cfg == null ? "AUTO_APPROVE" : cfg.getTimeoutAction());
    }

    /** 超时回调兜底：调度线程里的任何异常都不许逃逸（否则后续超时任务静默失效） */
    private void safeRunTimeout(String jobId) {
        try {
            onApprovalTimeout(jobId);
        } catch (Exception e) {
            log.warn("作业 {} 审批超时处置异常，已忽略", jobId, e);
        }
    }

    /**
     * 自动续批：本批正常完成后，若 RunPlan 判定该继续（未达标 / 未超批数上限 / 批末体检未达停线），
     * 克隆命令再投一个子作业接着写。
     *
     * <p><b>放在终态之后</b>：当前作业的 COMPLETED 已经落定，续批只是"再开一个作业"，
     * 与当前作业的成败解耦——续批失败不会把一批成功的成果标成失败。
     *
     * <p><b>fail-soft</b>：读盘/体检/投递的任何异常都只告警。续批是增强件，
     * 绝不能反噬已完成批次的终态（与预算熔断、检查点落盘同一原则）。
     */
    private void scheduleNextBatchIfNeeded(ArmoryCommandEntity command, GenerationJob job,
                                           StoryGenerateResultAggregate result) {
        if (runPlanService == null || !runPlanService.enabled()) {
            return;
        }
        try {
            String storyDirName = result == null ? null : result.getStoryDirName();
            if (StringUtils.isBlank(storyDirName)) {
                storyDirName = job.getStoryDirName();
            }
            if (StringUtils.isBlank(storyDirName)) {
                log.warn("作业 {} 自动续批跳过：故事目录未知，无法定位进度", job.getJobId());
                return;
            }
            int chaptersDone = countChapters(storyDirName);
            // 体检口径取**本批窗口**（2026-10-13）：判停答的是"这一批写得健不健康"，
            // 此前吃全书历史——早期几批的坏账会把 overall 永久压在 CRITICAL（续批永久停机），
            // 而旧债后来全清也无法恢复；反过来"这批突然劣化"会被历史好数据稀释而漏判。
            // 窗口 = 本批区间 [batchStart, batchEnd]（含已完成章）；全书口径仍保留给 /health 观测端点。
            int batchEnd = chaptersDone;
            int batchSize = command.getStoryContextEntity() == null
                    || command.getStoryContextEntity().getChapterCount() == null
                    ? 0 : command.getStoryContextEntity().getChapterCount();
            int batchStart = Math.max(1, batchEnd - Math.max(0, batchSize) + 1);
            BatchHealthReport health = assessHealth(storyDirName, batchStart, batchEnd);
            RunPlanService.Decision decision = runPlanService.decide(chaptersDone, job.getBatchRound(), health);
            if (!decision.continueNext()) {
                log.info("作业 {} 停止自动续批：{}", job.getJobId(), decision.reason());
                return;
            }
            ArmoryCommandEntity next = runPlanService.nextBatchCommand(command, storyDirName, chaptersDone);
            if (next == null) {
                log.warn("作业 {} 自动续批跳过：无法构造下一批命令", job.getJobId());
                return;
            }
            GenerationJob child = submit(next, job.getBatchRound() + 1);
            log.info("作业 {} 自动续批：{}；已提交子作业 {}（第 {} 批，本批 {} 章）",
                    job.getJobId(), decision.reason(), child.getJobId(), child.getBatchRound(),
                    next.getStoryContextEntity() == null ? null : next.getStoryContextEntity().getChapterCount());
        } catch (Exception e) {
            log.warn("作业 {} 自动续批异常，已跳过（fail-soft，不影响本批已完成的结果）", job.getJobId(), e);
        }
    }

    /** 已完成章数（读盘）：续批的"达标"判定以实际落盘为准，不信内存计数 */
    private int countChapters(String storyDirName) throws Exception {
        Path storyDir = storyRepository.resolveStoryDirectory(storyDirName);
        List<Integer> numbers = storyRepository.readChapterNumbers(storyDir);
        return numbers == null ? 0 : numbers.size();
    }

    /** 已完成章数（观测/日志用，不抛异常） */
    private int countChaptersQuietly(String storyDirName) {
        if (StringUtils.isBlank(storyDirName)) {
            return 0;
        }
        try {
            return countChapters(storyDirName);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 批末体检结论（读盘重算，纯机械零 LLM 成本）；读取失败返回 null（不做健康判停） */
    private BatchHealthReport assessHealth(String storyDirName) {
        return assessHealth(storyDirName, 0, 0);
    }

    /**
     * 批末体检结论（带**批次窗口**）：{@code [batchStart, batchEnd]} 内的章节才参与判停。
     *
     * <p><b>为什么判停必须窗口化</b>：指标此前在全书历史上计算，于是"这本书写得健不健康"
     * 与"这一批写得健不健康"被混为一谈——早期批次的坏账会把 overall 永久压在停线上
     *（自动续批永久停机，且旧债后来全清也恢复不了）；反向的"这批突然劣化"则会被
     * 大量历史好数据稀释到看不出。全书口径仍可用于观测（{@code batchStart<=0} 即全量）。
     *
     * <p>窗口只过滤**章节级**数据（摘要/质量债）；阶段蓝图是全书长期锚（数量少、语义为"方向"），
     * 不受窗口约束——过滤掉它会让"阶段规划覆盖"类指标失真。
     */
    private BatchHealthReport assessHealth(String storyDirName, int batchStart, int batchEnd) {
        try {
            Path storyDir = storyRepository.resolveStoryDirectory(storyDirName);
            List<ChapterSummaryEntity> summaries = storyRepository.readChapterSummaries(storyDir);
            List<QualityDebtEntity> debts = storyRepository.readQualityDebts(storyDir);
            if (batchStart > 0 && batchEnd >= batchStart) {
                summaries = filterByChapterWindow(summaries,
                        ChapterSummaryEntity::getChapterNo, batchStart, batchEnd);
                debts = filterByChapterWindow(debts, QualityDebtEntity::getChapterNo, batchStart, batchEnd);
                log.info("批末体检窗口：第 {}-{} 章（摘要 {} 条 / 质量债 {} 条）",
                        batchStart, batchEnd, summaries.size(), debts.size());
            }
            return batchHealthService.assess(
                    summaries,
                    debts,
                    storyRepository.readStageBlueprints(storyDir),
                    null,
                    // 结算台账：伏笔类指标须据此排除已弃置条目的污染，并产出静默兑现数
                    storyRepository.readForeshadowSettlements(storyDir));
        } catch (Exception e) {
            log.warn("作业批末体检读取失败，跳过健康判停：{}", storyDirName, e);
            return null;
        }
    }

    /** 按章号窗口过滤（null/空输入原样返回；章号为 null 的条目保留——无从判定归属，宁可多算） */
    private static <T> List<T> filterByChapterWindow(List<T> items,
                                                     java.util.function.Function<T, Integer> chapterNoOf,
                                                     int start, int end) {
        if (items == null || items.isEmpty()) {
            return items;
        }
        return items.stream()
                .filter(item -> {
                    Integer no = chapterNoOf.apply(item);
                    return no == null || (no >= start && no <= end);
                })
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
    }

    /**
     * 状态快照落盘：观测性文件，任何失败仅告警，绝不反噬生成流程。
     * 首发作业首章前故事目录未知（为空）时跳过
     */
    private void persistJobStatusIfPossible(ArmoryCommandEntity command, GenerationJob job) {
        Path storyDir = null;
        try {
            if (command != null && StringUtils.isNotBlank(command.getResumeStoryDir())) {
                storyDir = storyRepository.resolveStoryDirectory(command.getResumeStoryDir());
            } else if (StringUtils.isNotBlank(job.getStoryDirName())) {
                storyDir = storyRepository.resolveStoryDirectory(job.getStoryDirName());
            }
        } catch (Exception e) {
            log.warn("作业 {} 故事目录解析失败，跳过状态落盘", job.getJobId(), e);
            return;
        }
        if (storyDir == null) {
            return;
        }
        try {
            storyRepository.writeJobStatus(storyDir, job);
        } catch (Exception e) {
            log.warn("作业 {} 状态落盘失败，忽略", job.getJobId(), e);
        }
    }

    private String extractMessage(Throwable e) {
        String msg = e instanceof AppException ? ((AppException) e).getInfo() : null;
        if (StringUtils.isBlank(msg)) {
            msg = e.getMessage();
        }
        if (StringUtils.isBlank(msg)) {
            msg = e.getClass().getName();
        }
        return msg.length() > 1000 ? msg.substring(0, 1000) : msg;
    }

    /**
     * 待裁决的章节计划（内存态）。
     * 持有 DynamicContext 引用是本设计的关键：挂起期间上下文的 storyDir/runDir/蓝图链/
     * 计划分段原样保留，恢复时无需从磁盘重建，也不会重复烧规划 token。
     * 与 JobRegistry 同样是"单进程个人使用，重启即失效"的取舍
     */
    private static final class PendingApproval {
        private final GenerationJob job;
        private final ArmoryCommandEntity command;
        private final DefaultArmoryFactory.DynamicContext dynamicContext;
        /** AI 原版计划：超时 AUTO_APPROVE 与"未修订直接通过"都采纳它 */
        private final ChapterPlanAggregate plan;
        private volatile ScheduledFuture<?> timeoutTask;

        private PendingApproval(GenerationJob job, ArmoryCommandEntity command,
                               DefaultArmoryFactory.DynamicContext dynamicContext, ChapterPlanAggregate plan) {
            this.job = job;
            this.command = command;
            this.dynamicContext = dynamicContext;
            this.plan = plan;
        }
    }
}
