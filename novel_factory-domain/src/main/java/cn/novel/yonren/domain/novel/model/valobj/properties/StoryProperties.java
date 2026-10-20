package cn.novel.yonren.domain.novel.model.valobj.properties;

import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 故事生成配置绑定：模型接入与默认值。
 * 动态资料选择器开关（story.prompt.reference-selector）由 domain 的 ReferenceSelectorProperties 独立绑定，此处不含
 */
@Data
@ConfigurationProperties(prefix = "story", ignoreInvalidFields = true)
public class StoryProperties {

    //默认设置
    private StoryVO.Defaults defaults;
    // 模型
    private StoryVO.Module module;
    //限制
    private StoryVO.Constraints constraints;
    /** 故事级一致性 feature 声明（金手指等），经 toStoryVO() 透传给领域层 */
    private StoryVO.StoryFeatures features;
    // 章节审校开关
    private AuditProperties audit = new AuditProperties();
    // 章节修订开关
    private ReviseProperties revise = new ReviseProperties();
    // 候选选优（低置信通过时第二模型族重写 + 异模型评审二选一）
    private CandidateProperties candidate = new CandidateProperties();

    /** 段落信息增量审校（默认关闭：新链路先观察再启用） */
    private ParagraphAuditProperties paragraphAudit = new ParagraphAuditProperties();
    // 预算熔断（按作业累计 token 超阈值停机）
    private BudgetProperties budget = new BudgetProperties();
    // 章节计划（分支推演版数等）
    private PlanProperties plan = new PlanProperties();
    // 账本挂起事实裁决（L1 兜底：LLM 判定 + 引文回检）
    private LedgerAdjudicateProperties ledgerAdjudicate = new LedgerAdjudicateProperties();
    // 无人值守续写计划（自续批；默认关闭，开启后作业会自动连续花 token）
    private RunPlanProperties runPlan = new RunPlanProperties();
    // 章节计划审批门（human-in-the-loop；默认关闭，开启后每批正文生成前挂起等人裁决）
    private PlanApprovalProperties planApproval = new PlanApprovalProperties();

    public StoryVO toStoryVO() {
        StoryVO storyVO = new StoryVO();
        storyVO.setDefaults(defaults);
        storyVO.setModule(module);
        storyVO.setConstraints(constraints);
        storyVO.setFeatures(features);
        return storyVO;
    }

    @Data
    public static class AuditProperties {
        /** 是否开启审校 */
        private boolean enabled = true;
        /** 是否执行正文有效字符数硬门禁 */
        private boolean enforceMinimumChapterLength = true;
    }

    @Data
    public static class ReviseProperties {
        /** 是否开启修订 */
        private boolean enabled = true;
        /** 单章最大修订轮数 */
        private int maxAttempts = 1;

        /** 是否优先尝试定向补丁修订（模型只产出「锚点→替换文本」的局部改写）；未采用时回退整章重写 */
        private boolean patchEnabled = true;
    }

    @Data
    public static class CandidateProperties {
        /** 是否开启候选选优：需先在 scene-models.chapter-judge 配置第二模型族（含 base-url/api-key） */
        private boolean enabled = false;
        /** 每次低置信通过时并发生成的候选数（不含原稿） */
        private int maxCandidates = 1;

        /** 审校门结论为 MINOR_RESIDUE（残留 MINOR 问题）时是否触发候选挑战；默认关闭——MINOR 条数缺乏判别力 */
        private boolean triggerOnMinorResidue = false;

        /** MINOR_RESIDUE 触发候选所需的 MINOR 问题条数下限，与 triggerOnMinorResidue 联用 */
        private int minorResidueThreshold = 6;

        /**
         * 修订过 N 轮后才闭环是否触发候选（**当前唯一有效的触发信号**，采纳率 66.7%）。
         * 默认收紧为 false：整章重写 + 盲评单次是 20 分钟量级，而采纳率仅约三分之二——
         * 对"想快点看到成稿"的用户这是最贵的一环。要最高质量时在 03 项目模型配置里打开
         * （candidate 开关，运行时覆盖生效）。
         */
        private boolean triggerOnRevisedPass = false;


        /** 审校门结论为 DEBT（修订耗尽仍不收敛）时是否触发候选挑战；默认关闭 */
        private boolean triggerOnDebt = false;
        /** 候选重写调用的单次输出上限（评审场景 yml max-tokens 为小值，重写时按此覆盖） */
        private Long rewriteMaxTokens = 16384L;
    }

    /**
     * 段落信息增量审校（2026-09-22，对应"防注水改成信息增量约束"）。
     *
     * <p>正文生成后追加一次**章内**审校：找出"只重复已知状态、原地循环同一种情绪"的段落，
     * 产出**局部改写补丁**（anchor + replacement），而不是整章重写——删除是不可逆的，
     * 改写才可以回退。它不需要跨章记忆，所以是低成本的一条链路。
     */
    @Data
    public static class ParagraphAuditProperties {
        /** 是否开启：默认 false，新链路先观察再启用 */
        private boolean enabled = false;
        /** 单章最多接受几条补丁（防止一处判错就把整章拆了） */
        private int maxPatches = 3;
    }

    @Data
    public static class BudgetProperties {
        /** 单作业 token 预警线（累计 prompt+completion，来自 usage 记账；null=不预警） */
        private Long warnTotalTokens;
        /** 单作业 token 硬上限：达到后当前章完成即停（逐章检查点已落盘，可 resume 续写）；null=不熔断 */
        private Long hardTotalTokens;
    }

    @Data
    public static class PlanProperties {
        /** 批次计划分支推演版数：1=单稿（默认），2=稳健/进取两版自评风险后由 chapter-judge 评审择优 */
        private int branches = 1;
    }

    /**
     * 账本挂起裁决（L1）开关。机械分档（EvidenceMatch）放行后仍剩「仅部分短语命中」的残余项，
     * 由 LLM 回读当章正文逐条判定，并要求给出可逐字核对的引文（引文须再经 EvidenceMatch 才入账）。
     * 关掉即零 LLM 成本：残余项保持挂起，账本完整度停在机械档水平，交由观测层统计存量
     */
    @Data
    public static class LedgerAdjudicateProperties {
        /** 是否开启账本挂起裁决 */
        private boolean enabled = true;
        /** 单章单次裁决的挂起项上限：超出部分本章不裁（防 prompt/输出去膨胀），仍保留在挂起层 */
        private int maxItems = 12;
    }

    /**
     * 无人值守续写计划（RunPlan，阶段六）。
     *
     * <p><b>默认关闭</b>：开启后一次提交会按计划反复自续批，直到达标或触发停机条件——
     * 这是"真正的挂机"，也是最容易失控地花钱的开关。开启前请确认 budget 熔断已配置。
     *
     * <p>停机条件（任一命中即停并落终态，状态可 resume）：达标（达到 targetChapters）/
     * 批末体检分级达到 stopOnHealthLevel / 续批轮数达 maxBatches / 预算熔断 / 人工取消。
     */
    @Data
    public static class RunPlanProperties {
        /** 是否开启自动续批 */
        private boolean enabled = false;
        /** 目标总章数；null 时用 story.constraints.max-chapter-count，再空则用请求里的 chapterCount */
        private Integer targetChapters;
        /** 单批章数（每个子作业生成多少章）；null 时沿用请求里的 chapterCount */
        private Integer batchSize;
        /** 最多自动续批轮数；0 = 不限制（仍有预算熔断与总章数上限兜底） */
        private int maxBatches = 0;
        /** 批末体检分级达到该档即停止续批，交人工过问：WATCH / DEGRADED / CRITICAL，默认 CRITICAL */
        private String stopOnHealthLevel = "CRITICAL";
    }

    @Data
    public static class PlanApprovalProperties {
        /** 是否开启审批门（关闭时计划校验后直连正文生成，行为与历史一致） */
        private boolean enabled = false;
        /**
         * 挂起等待人工裁决的时长（秒）。因挂起不占用线程，可以放宽到小时级，
         * 用户不必守在屏幕前；超时后按 timeoutAction 处置
         */
        private long timeoutSeconds = 7200L;
        /**
         * 超时处置：AUTO_APPROVE = 按 AI 原版计划继续（等同于退化成黑盒模式，默认推荐）；
         * ABORT = 置 CANCELLED 中止本批。
         * 刻意只提供这两种：超时必须是"已有状态之一"，否则会引入第三种终局语义
         */
        private String timeoutAction = "AUTO_APPROVE";
        /**
         * 门的作用范围：FIRST_BATCH_ONLY = 仅首个批次需要裁决（首批定下全书调性，后续是延续，默认）；
         * EVERY_BATCH = 每批都要裁决。
         * FIRST_BATCH_ONLY 兼作与自续批并存时的隔离手段
         */
        private String scope = "FIRST_BATCH_ONLY";

        /**
         * 本批是否在门的作用范围内。口径的唯一实现——审批门节点（是否挂起）与
         * 规划节点（是否整批提前规划以便全量裁决）共用，避免两处判定各自演化。
         * 与历史判定逐字一致：EVERY_BATCH 恒真；其余值（含未识别）按 FIRST_BATCH_ONLY 处理
         */
        public boolean coversBatch(int batchRound) {
            String normalized = scope == null ? "" : scope.trim().toUpperCase();
            if ("EVERY_BATCH".equals(normalized)) {
                return true;
            }
            return batchRound <= 1;
        }
    }

}
