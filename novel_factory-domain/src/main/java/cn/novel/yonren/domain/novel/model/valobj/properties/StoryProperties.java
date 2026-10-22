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
        /** 是否执行正文有效字符数硬门禁（1500 参考线**告警**） */
        private boolean enforceMinimumChapterLength = true;

        /**
         * 是否启用**篇幅坍缩硬底线**（800 有效字 = 提纲式废稿，记 BLOCKING 进修订闭环）。
         *
         * <p>独立于 {@link #enforceMinimumChapterLength}：两者答的是不同问题
         * （见 {@code ChapterLengthPolicy#COLLAPSE_FLOOR_CHARACTERS} 的类注释）——
         * 前者答"计划给的料够不够"（只告警、回灌规划层），后者答"这一稿是不是根本不成篇"（必须返工）。
         * 此前复用同一开关，导致关掉 1500 告警时会**静默连 800 硬底线一起关掉**，
         * 第 136 章 674 字废稿出厂正是缺了这条线。默认开启。
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
         * 是否优先尝试「定向补丁」（2026-09-17 新增）。
         *
         * <p>开启时，修订先让模型只产出「原文锚点 → 替换文本」的补丁，机械套用后再走同一套采纳闸门；
         * 补丁未给出 / 锚点不合法 / 补丁不过闸门时，自动回退到既有的整章重写路径——
         * 因为回退存在，本开关不会降低行为的下界，只是多一次便宜的尝试（补丁输出仅数十 token）。
         *
         * <p>动机：整章重写会把**所有未被问题命中的段落**一并重出，文风回退风险与修复范围完全不成比例。
         * 实测（20260917-story-0001 第 4 章）：为补一句过渡描写，两轮整章重写都因
         * "疲劳词跨章累计超阈值"被采纳闸门拒绝，缺口最终永久留存。
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
         * MINOR 残留（首轮通过但有 MINOR）是否触发候选。<b>2026-10-02 由 true 改为 false。</b>
         *
         * <p><b>为什么关掉</b>：两批实测（各 5 章）MINOR 条数的中位数都是 6——
         * 而 LLM 审计被要求"找问题"，其**输出条数是一个恒定基线，几乎不随章节质量变化**。
         * 于是「条数过门槛就整章重写」这个判据本质上等于常开：
         * 门槛设 3 全命中、设 6 仍全命中（实测计数 6/6/6/8），只是在一根水平曲线上挪切点。
         *
         * <p>而成本是实打实的：候选链路占全批 token 的 <b>36%</b>（是正文生成的 2.1 倍），
         * 采纳率仅 40% ⇒ 六成重写纯浪费。相比之下 {@code REVISED_PASS} 的采纳率是 66.7%——
         * **真正有判别力的信号是"修订过才闭环"，不是"审计报了几条笔记"。**
         *
         * <p>MINOR 残留本身是**任何合格章节的正常状态**（"通过，但有若干可优化项"），
         * 不值得触发第二模型族整章重写。MINOR 仍照常记债并回灌规划层，只是不再起候选。
         */
        private boolean triggerOnMinorResidue = false;

        /**
         * MINOR 残留触发候选的**条数门槛**（2026-10-01 新增，2026-10-02 随开关关闭而失效）。
         *
         * <p>保留该字段是为了在 {@code triggerOnMinorResidue} 重新打开时仍可调节；
         * 但须注意：**实测该门槛没有判别力**（两批中位数均为 6，见上），
         * 重新启用前应先想清楚"用什么信号区分值得重写的章"。
         */
        private int minorResidueThreshold = 6;

        /** 修订过 N 轮后才闭环是否触发候选：开源仓默认关闭（触发即自动连花 token，须用户显式打开） */
        private boolean triggerOnRevisedPass = false;

        /**
         * 修订耗尽仍有未修复 BLOCKING（{@code DEBT}）是否触发候选。<b>2026-10-02 新增，默认 false。</b>
         *
         * <p><b>为什么关掉</b>：2026-10-01 我曾把 DEBT 改成恒触发，理由是"修订两轮仍不收敛 =
         * 最该换个写法"。当时 DEBT 因 switch 漏分支而**一次都没触发**，我是在**没有采纳率数据**的情况下
         * 下的结论。2026-10-02 实测推翻了这个判断：
         *
         * <table border="1">
         *   <tr><th>触发等级</th><th>触发</th><th>采纳</th><th>采纳率</th></tr>
         *   <tr><td>DEBT</td><td>3</td><td>0</td><td><b>0%</b></td></tr>
         *   <tr><td>MINOR_RESIDUE</td><td>14</td><td>6</td><td>42.9%</td></tr>
         *   <tr><td>REVISED_PASS</td><td>3</td><td>2</td><td><b>66.7%</b></td></tr>
         * </table>
         *
         * <p>原因不难理解：DEBT 章的问题多是**结构性**的（如同一份计划下的同一堵墙，例如
         * "物品账本与正文直接冲突"），而挑战者是在**同一份计划、同样的约束**下重写——
         * 它同样会撞上那堵墙。日志中 {@code challenger-debt-reverted} 出现 2 次即是此意：
         * 挑战者赢下盲评后，自身复检仍带 BLOCKING，只得连同候选稿一起回退。
         *
         * <p>⚠️ <b>样本仅 3 次触发</b>。默认关掉是因为"代价确定、收益为零（0/3）"；
         * 若后续批次积累到更多样本且采纳率回升，可用此开关重新打开。
         */
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

    /**
     * 章节计划审批门（human-in-the-loop）：本批章节计划生成并落盘后，正文生成之前
     * 把计划交人工裁决，改动/驳回后再决定是否写正文。
     *
     * <p><b>默认关闭</b>：关闭时为纯黑盒路径，节点链与历史行为完全一致（计划校验后直连正文生成）。
     *
     * <p><b>实现语义是"挂起"而非"阻塞"</b>：jobExecutor 是单线程池，在工作线程里等待会冻住
     * 全部排队作业；因此挂起时直接归还线程（作业转 AWAITING_APPROVAL），裁决/超时后再重新入队，
     * 复用同一个 jobId 与 run 目录继续跑剩余阶段。
     *
     * <p>与 {@code story.run-plan} 自续批存在语义冲突：自续批"只问体检不问人"，每批都会停下来等人；
     * 同时开启时本门默认只在首批生效（见 scope）。
     */
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
