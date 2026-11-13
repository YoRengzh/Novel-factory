package cn.novel.yonren.domain.novel.config;

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
        /** 是否执行正文有效字符数硬门禁（1500 参考线**告警**） */
        private boolean enforceMinimumChapterLength = true;

        /**
         * 是否启用**篇幅坍缩硬底线**（800 有效字 = 提纲式废稿，记 BLOCKING 进修订闭环）。
         *
         * 独立于 {@link #enforceMinimumChapterLength}：两者答的是不同问题
         * （见 {@code ChapterLengthPolicy#COLLAPSE_FLOOR_CHARACTERS} 的类注释）——
         * 前者答"计划给的料够不够"（只告警、回灌规划层），后者答"这一稿是不是根本不成篇"（必须返工）。
         * 未配置独立开关时，关闭计划供给告警可能连带关闭成篇硬底线，导致不完整稿件被放行。默认开启。
         */
        private boolean enforceCollapseFloor = true;
    }

    @Data
    public static class ReviseProperties {
        /** 是否开启修订 */
        private boolean enabled = true;
        /** 单章最大修订轮数 */
        private int maxAttempts = 1;

        /**
         * 是否优先尝试「定向补丁」。
         *
         * 开启时，修订先让模型只产出「原文锚点 → 替换文本」的补丁，机械套用后再走同一套采纳闸门；
         * 补丁未给出 / 锚点不合法 / 补丁不过闸门时，自动回退到既有的整章重写路径——
         * 因为回退存在，本开关不会降低行为的下界，只是多一次便宜的尝试（补丁输出仅数十 token）。
         *
         * 动机：整章重写会把**所有未被问题命中的段落**一并重出，文风回退风险与修复范围完全不成比例。
         * 局部补丁避免无关段落重写，也减少因跨章累计风格约束导致整稿被拒绝的风险。
         */
        private boolean patchEnabled = true;
    }

    @Data
    public static class CandidateProperties {
        /** 是否开启候选选优：需先在 scene-models.chapter-judge 配置第二模型族（含 base-url/api-key） */
        private boolean enabled = false;
        /** 每次低置信通过时并发生成的候选数（不含原稿） */
        private int maxCandidates = 1;
        /**
         * MINOR 残留（首轮通过但有 MINOR）是否触发候选，默认关闭。
         *
         * 审计笔记条数不能单独证明章节需要整章重写；候选链路会增加费用和文风回退风险。
         * MINOR 仍照常记债并回灌规划层，只是不因残留数量自动起候选。
         */
        private boolean triggerOnMinorResidue = false;

        /**
         * MINOR 残留触发候选的**条数门槛**。
         *
         * 仅在 {@code triggerOnMinorResidue} 开启时生效；启用前应验证条数信号能否区分值得重写的章节。
         */
        private int minorResidueThreshold = 6;

        /** 修订过 N 轮后才闭环是否触发候选：开源仓默认关闭（触发即自动连花 token，须用户显式打开） */
        private boolean triggerOnRevisedPass = false;

        /**
         * 修订耗尽仍有未修复 BLOCKING（{@code DEBT}）是否触发候选，默认关闭。
         *
         * 候选仍使用同一份计划与约束，结构性问题可能继续存在。
         * 挑战者即使赢下盲评，也必须通过自身复检；未过门禁时保留原稿。
         * 开启前应独立评估采纳收益与额外模型费用。
         */
        private boolean triggerOnDebt = false;
        /** 候选重写调用的单次输出上限（评审场景 yml max-tokens 为小值，重写时按此覆盖） */
        private Long rewriteMaxTokens = 16384L;
    }

    /**
     * 段落信息增量审校，对应防注水的信息增量约束。
     *
     * 正文生成后追加一次**章内**审校：找出"只重复已知状态、原地循环同一种情绪"的段落，
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
     * 默认关闭：开启后一次提交会按计划反复自续批，直到达标或触发停机条件——
     * 这是"真正的挂机"，也是最容易失控地花钱的开关。开启前请确认 budget 熔断已配置。
     *
     * 停机条件（任一命中即停并落终态，状态可 resume）：达标（达到 targetChapters）/
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

}
