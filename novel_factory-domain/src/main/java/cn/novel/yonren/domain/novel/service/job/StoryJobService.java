package cn.novel.yonren.domain.novel.service.job;

import cn.novel.yonren.api.dto.JobStatusResponseDTO;
import cn.novel.yonren.api.dto.StoryGenerateRequestDTO;
import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.aggregate.StoryGenerateResultAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.QualityDebtEntity;
import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.StoryGenerateService;
import cn.novel.yonren.domain.novel.service.armory.plan.ChapterPlanChecks;
import cn.novel.yonren.domain.novel.model.valobj.quality.BatchHealthReport;
import cn.novel.yonren.domain.novel.service.armory.quality.BatchHealthService;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import cn.novel.yonren.types.utils.SnowflakeIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import cn.novel.yonren.types.enums.JobStatus;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 作业编排服务：异步提交 → 单线程 worker 执行 → 进度/取消/终态。
 * D9 个人用范围：只做触发/进度/取消/裁决，无列表/详情/导出；job-status.json 仅供崩溃后人工查看。
 * MDC trace-id 与 jobId 同源，生成期间全部日志与 LLM usage 记账按 job 归因
 */
@Service
@Slf4j
public class StoryJobService {

    /** workerId=2：storyId 已占用 workerId=1（PersistChapterPlanNode），双实例同 workerId 同毫秒会碰撞 */
    private static final SnowflakeIdWorker JOB_ID_WORKER = new SnowflakeIdWorker(2);

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
        if (!jobRegistry.tryRegister(job)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "该故事有运行中的作业或维护操作，请稍后再提交：" + command.getResumeStoryDir());
        }
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

    /** 协作取消：CREATED 直接置 CANCELLED；RUNNING 置 CANCELLING（当前章完成后停）；终态幂等。未知返回 null */
    public GenerationJob cancel(String jobId) {
        GenerationJob job = jobRegistry.cancel(jobId);
        if (job != null && job.getStatus() == JobStatus.CANCELLING) {
            // 故事目录已知（续写/首章后）时立即把取消受理态写盘；未知则等 worker 终态落盘
            persistJobStatusIfPossible(null, job);
        }
        return job;
    }


    /**
     * worker 线程执行体（jobExecutor 单线程串行，符合 质量 > token > 速度 的优先级）
     */
    private void runJob(ArmoryCommandEntity command, GenerationJob job) {
        MDC.put("trace-id", job.getJobId());
        cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory.DynamicContext dynamicContext =
                storyGenerateService.newDynamicContext(job);
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

    /**
     * 自动续批：本批正常完成后，若 RunPlan 判定该继续（未达标 / 未超批数上限 / 批末体检未达停线），
     * 克隆命令再投一个子作业接着写。
     *
     * 放在终态之后：当前作业的 COMPLETED 已经落定，续批只是"再开一个作业"，
     * 与当前作业的成败解耦——续批失败不会把一批成功的成果标成失败。
     *
     * fail-soft：读盘/体检/投递的任何异常都只告警。续批是增强件，
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
            // 体检口径取**本批窗口**：判停答的是"这一批写得健不健康"，
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
     * 为什么判停必须窗口化：指标此前在全书历史上计算，于是"这本书写得健不健康"
     * 与"这一批写得健不健康"被混为一谈——早期批次的坏账会把 overall 永久压在停线上
     *（自动续批永久停机，且旧债后来全清也恢复不了）；反向的"这批突然劣化"则会被
     * 大量历史好数据稀释到看不出。全书口径仍可用于观测（{@code batchStart<=0} 即全量）。
     *
     * 窗口只过滤**章节级**数据（摘要/质量债）；阶段蓝图是全书长期锚（数量少、语义为"方向"），
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

    // ------------------------------------------------DTO 门面（trigger 层专用入口）

    /**
     * DTO 门面：trigger 层不触碰实体/聚合，
     * 装配（StoryCommandAssembler）与状态 DTO 化都在本服务内完成。
     * 既有实体签名方法保留——自动续批等 domain 内部路径仍走实体。
     */

    public JobStatusResponseDTO submitDto(StoryGenerateRequestDTO request) {
        GenerationJob job = submit(cn.novel.yonren.domain.novel.service.armory.StoryCommandAssembler
                .toCommand(request, storyProperties));
        return toJobStatusDTO(job);
    }

    /** 作业状态查询；作业不存在返回 null（由 trigger 层映射 404） */
    public JobStatusResponseDTO statusDto(String jobId) {
        GenerationJob job = get(jobId);
        return job == null ? null : toJobStatusDTO(job);
    }

    /** 协作取消；未知作业返回 null（由 trigger 层映射 404） */
    public JobStatusResponseDTO cancelDto(String jobId) {
        GenerationJob job = cancel(jobId);
        return job == null ? null : toJobStatusDTO(job);
    }

    /** 作业状态 → DTO 拆装 */
    private JobStatusResponseDTO toJobStatusDTO(GenerationJob job) {
        JobStatusResponseDTO dto = new JobStatusResponseDTO();
        dto.setJobId(job.getJobId());
        dto.setStatus(job.getStatus() == null ? null : job.getStatus().name());
        dto.setStoryDirName(job.getStoryDirName());
        dto.setRunDirName(job.getRunDirName());
        dto.setCurrentStage(job.getCurrentStage());
        dto.setCurrentChapter(job.getCurrentChapter());
        dto.setTotalChapters(job.getTotalChapters());
        dto.setStartedAtMs(job.getStartedAtMs());
        dto.setFinishedAtMs(job.getFinishedAtMs());
        dto.setErrorMessage(job.getErrorMessage());
        dto.setChapterDurations(job.getChapterDurations());
        dto.setHealthReport(job.getLastHealthReport());
        return dto;
    }
}
