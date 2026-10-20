package cn.novel.yonren.domain.novel.service.job;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.service.armory.quality.BatchHealthReport;
import cn.novel.yonren.types.enums.JobStatus;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次异步生成作业的可变记录：registry / DynamicContext / worker 三方共享同一实例。
 * 状态迁移全部 synchronized，终态不可再迁移；cancelRequested 为协作取消的 volatile 信号，
 * 随状态一起序列化进 job-status.json（崩溃后人工排查 CANCELLING 卡住时可见信号是否送达）
 */
@Getter
@Setter
@NoArgsConstructor
public class GenerationJob {

    private String jobId;

    private volatile JobStatus status;

    /** 故事目录名（resume 时提交即知；首发由 worker 首章进度回填） */
    private String storyDirName;

    /** 本批 run 目录名（run-job-<jobId>，树尾持久化时回填） */
    private String runDirName;

    /** 当前阶段（如 CHAPTER_GENERATION），观测用自由文本 */
    private String currentStage;

    private Integer currentChapter;

    private Integer totalChapters;

    /** epoch 毫秒，fastjson2 序列化形态稳定 */
    private Long startedAtMs;

    private Long finishedAtMs;

    private String errorMessage;

    private Map<String, Long> chapterDurations;

    /** 协作取消信号：worker 每章迭代开头检查，当前章完成后停 */
    private volatile boolean cancelRequested;

    /** 预算熔断信号：累计 token 达硬上限后由 LlmBudgetFuse 置位，worker 当前章完成即停（与协作取消同通道） */
    private volatile boolean budgetExceeded;

    /** 本作业累计 token 用量（网关逐调用累加，观测用，随 job-status.json 落盘） */
    private volatile long tokensUsed;

    /**
     * 本作业内已「思考烧穿输出预算」的模型名黑名单（key = 场景 configKey + "|" + 模型名）。
     * 强制思考模型烧穿是确定性故障：思考失控与采样无关，同一模型在同一场景下重试只会再烧一次
     * （实测 glm-5.3 在 chapter-plan 连烧 3 次、每次 11~15 分钟 32k token，44 分钟零产出）。
     * 网关降级换站前查询本表，命中即跳过该站；作业结束随实例一起被 GC，不跨作业持久化——
     * 下个作业仍给模型一次机会（供应商侧可能已修复/限流态已变）。
     * transient：不进 job-status.json——该文件是观测快照，崩溃恢复不回填执行态（黑名单同理）
     */
    private final transient Set<String> burnedModels = ConcurrentHashMap.newKeySet();

    /**
     * 最近一次批末体检报告（每批结束在 worker 覆盖，随作业状态轮询透出给前端）。
     * transient：不进 job-status.json——体检是批末快照而非进度，落盘会让崩溃恢复读到过期结论
     */
    private volatile transient BatchHealthReport lastHealthReport;

    /**
     * 批序号（1 起）。外部首发 = 1；自动续批时父作业 +1（见 {@code StoryJobService.scheduleNextBatchIfNeeded}）。
     * 用途：run-plan.max-batches 的上限判定，以及让作业状态直接读出"这是第几批"——
     * 此前只能靠人去数 generation-records 里的批次区间（实测 26 次提交 / 20 个唯一区间）。
     */
    private volatile int batchRound = 1;

    /**
     * 章节计划审批门的挂起信息（非挂起态为 null）。
     * awaitingPlanApprovalAtMs：进入挂起的时刻（epoch 毫秒）；
     * planApprovalDeadlineMs：裁决截止时刻，前端据此倒计时，超时由审批服务按配置放行或中止；
     * approvalRound：本作业已完成的人工裁决轮次（超时自动放行不计入；观测用，也用于区分"人改过"与"没人管"）
     */
    private volatile Long awaitingPlanApprovalAtMs;

    private volatile Long planApprovalDeadlineMs;

    private volatile int approvalRound;

    /**
     * 待裁决的章节计划：与挂起态**一次性交接**（2026-09-28），支撑"状态可见 ⇒ 计划可读"不变式——
     * 此前状态由审批门节点置位、计划由服务层登记，两者非原子，中间的微窗口里状态查询会读到 null
     * （机器繁忙时稳定复现）。transient：作业状态文件只承载标量观测字段，计划本体另有 runDir 落盘
     */
    private transient volatile ChapterPlanAggregate awaitingPlan;

    public GenerationJob(String jobId) {
        this.jobId = jobId;
        this.status = JobStatus.CREATED;
        this.startedAtMs = System.currentTimeMillis();
        this.chapterDurations = new HashMap<>();
    }

    /** 仅 CREATED 可进入 RUNNING ; 终态（含排队期已取消）拒绝，防止排队期取消被覆盖 */
    public synchronized boolean markRunning() {
        if (status != JobStatus.CREATED) {
            return false;
        }
        status = JobStatus.RUNNING;
        return true;
    }

    /**
     * 进入章节计划审批挂起态（仅 RUNNING 可进入）。
     * 置位后 worker 会归还线程，作业在内存注册表中等待裁决，不占用 jobExecutor
     */
    public synchronized boolean markAwaitingApproval(long deadlineMs) {
        return markAwaitingApproval(deadlineMs, null);
    }

    /**
     * 进入审批挂起态并交接待裁决计划（仅 RUNNING 可进入）。
     * **先写计划字段再置状态**：volatile 写序保证"读到 AWAITING_APPROVAL 的读者必然也能读到计划"，
     * 使 {@code pendingChapterPlan} 不再受"服务层登记尚未完成"的时序影响
     */
    public synchronized boolean markAwaitingApproval(long deadlineMs, ChapterPlanAggregate pendingPlan) {
        if (status != JobStatus.RUNNING) {
            return false;
        }
        this.awaitingPlan = pendingPlan;
        status = JobStatus.AWAITING_APPROVAL;
        this.awaitingPlanApprovalAtMs = System.currentTimeMillis();
        this.planApprovalDeadlineMs = deadlineMs;
        return true;
    }

    public synchronized boolean exitApprovalWait() {
        if (status != JobStatus.AWAITING_APPROVAL) {
            return false;
        }
        status = JobStatus.CREATED;
        this.awaitingPlanApprovalAtMs = null;
        this.planApprovalDeadlineMs = null;
        this.awaitingPlan = null;
        return true;
    }

    /**
     * 记一次人工裁决（仅人工 approve 路径调用）。
     * 用途：区分"人看过并放行/改过"与"没人管、被超时自动放行"——
     * 前者说明计划质量被人工认可，后者说明审批门可能纯属流程负担，观测层据此判断门是否值得开
     */
    public synchronized void recordHumanApproval() {
        this.approvalRound++;
    }

    /** 是否处于等待人工裁决（审批门挂起中） */
    public boolean isAwaitingApproval() {
        return status == JobStatus.AWAITING_APPROVAL;
    }

    public synchronized void updateProgress(String stage, int currentChapter, int totalChapters, String storyDirName) {
        this.currentStage = stage;
        this.currentChapter = currentChapter;
        this.totalChapters = totalChapters;
        if (storyDirName != null) {
            this.storyDirName = storyDirName;
        }
    }

    public synchronized void recordChapterDuration(int chapterNo, long durationMs) {
        // 键转字符串：保证 job-status.json 是合法 JSON（见字段注释）
        chapterDurations.put(String.valueOf(chapterNo), durationMs);
    }

    /** 置位取消信号；终态返回 false（取消不再受理） */
    public synchronized boolean requestCancel() {
        if (status == JobStatus.FAILED || status == JobStatus.COMPLETED || status == JobStatus.CANCELLED) {
            return false;
        }
        cancelRequested = true;
        return true;
    }

    /** 置位预算熔断信号；终态返回 false */
    public synchronized boolean requestBudgetExceeded() {
        if (status == JobStatus.FAILED || status == JobStatus.COMPLETED || status == JobStatus.CANCELLED) {
            return false;
        }
        budgetExceeded = true;
        return true;
    }

    /** 仅 RUNNING 可进入 CANCELLING */
    public synchronized boolean markCancelling() {
        if (status != JobStatus.RUNNING) {
            return false;
        }
        status = JobStatus.CANCELLING;
        return true;
    }

    public synchronized void markCancelled() {
        finishedAtMs = System.currentTimeMillis();
        status = JobStatus.CANCELLED;
    }

    public synchronized void markCompleted(String storyDirName) {
        if (storyDirName != null) {
            this.storyDirName = storyDirName;
        }
        finishedAtMs = System.currentTimeMillis();
        status = JobStatus.COMPLETED;
    }

    public synchronized void markFailed(String errorMessage) {
        this.errorMessage = errorMessage;
        finishedAtMs = System.currentTimeMillis();
        status = JobStatus.FAILED;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public boolean isBudgetExceeded() {
        return budgetExceeded;
    }

    /** 记一次模型烧穿（网关在换站降级前调用）；sceneKey/model 任一为空不记 */
    public void recordBurnedModel(String sceneKey, String model) {
        if (StringUtils.isNotBlank(sceneKey) && StringUtils.isNotBlank(model)) {
            burnedModels.add(sceneKey + "|" + model);
        }
    }

    /** 该模型在该场景下本作业内是否已烧穿过（网关选站时查询） */
    public boolean isModelBurned(String sceneKey, String model) {
        return StringUtils.isNotBlank(sceneKey) && StringUtils.isNotBlank(model)
                && burnedModels.contains(sceneKey + "|" + model);
    }
}
