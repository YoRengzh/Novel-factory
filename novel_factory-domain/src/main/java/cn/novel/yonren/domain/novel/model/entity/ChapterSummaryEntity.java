package cn.novel.yonren.domain.novel.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 章节摘要实体：记忆系统的滚动数据源。
 * 由刚生成完的正文压缩而来，供后续章节组装记忆前缀；
 * 角色/物品/势力账本与伏笔账均可由摘要列表确定性重建，不另设存储。
 * continuityConflicts 为摘要模型对照当前账本发现的正文偏差（一致性软校验）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChapterSummaryEntity {

    // 章节号
    private Integer chapterNo;
    // 章节标题
    private String title;
    // 150-200 字核心剧情摘要
    private String summary;
    // 本章结束时状态发生变化（或首次登场）的角色状态
    private List<StateEntry> characterStates;
    // 本章关键配角的独立行为轨迹；旧摘要缺省为空，兼容既有 summaries.json
    private List<CharacterBeat> characterBeats;
    // 本章状态发生变化（或首次出现）的关键物品（法宝/金手指等）
    private List<StateEntry> itemStates;
    // 本章状态发生变化（或首次出现）的势力/组织
    private List<StateEntry> factionStates;
    // 本章新埋下、尚未兑现的伏笔
    private List<String> foreshadowingNew;
    // 本章新埋伏笔的种子：content 与 foreshadowingNew 对应条目一致，excerpt 为埋设处正文原文引用（≤80 字）。
    // excerpt 经 content.indexOf 子串校验，失败置空（防模型编造引用）；老数据该字段为 null，天然兼容
    private List<SeedEntry> foreshadowSeeds;
    // 本章回收/印证的此前伏笔
    private List<String> foreshadowingResolved;
    // 本章结束时的时间与地点（时间线连续性锚点）
    private String timePoint;
    // 主角当前真实境界（如"炼气一层"）：随摘要滚动更新，记忆前缀置顶锁定，治境界数值瞬移
    private String cultivationRealm;
    // 仅故事设定存在金手指时记录本章是否实际使用或有意义提及
    private Boolean cheatMechanismUsed;
    // 时间线/伤情/术语/关键数字候选；证据不匹配者隔离，不进入一致性索引
    private List<ConsistencyFact> consistencyFacts;
    private List<ConsistencyFact> pendingConsistencyFacts;
    // 与当前账本冲突的正文偏差描述（一致性软校验结果）
    private List<String> continuityConflicts;
    // 证据校验未通过的状态事实（evidence 非当章正文子串，疑似编造/漂移）：不入三账本，待人工确认；老数据缺省 null
    private List<StateEntry> pendingFacts;
    // 残缺记忆标记：三级降级仅抢救到 summary 字段时为 true（账本状态/伏笔账缺失），供复盘与后续决策；老数据缺省 false
    private boolean partial;
    // 本章正文有效字符数（机械统计，非模型输出）：密度信号数据源，老数据为 null
    private Integer validChars;

    /** 对白行占比（机械统计，0~1）：含「」“”引号的非空行 / 全部非空行。
     *  2026-09-16 新增——新书实测对白占比坍缩至旧书的 1/4（11.1% vs 45.6%），
     *  "零角色互动"此前不可观测（地点有轨迹回灌，关系/互动零指标零回灌） */
    private Double dialogueRatio;

    /** 题材（机械写入，GenreTypeVO.code）：体检的"对话驱动题材"阈值据此切换。
     *  落进摘要而非走参数，是为了让批末日志与 /health 端点的口径自动一致（观测层口径必须同一份） */
    private String storyGenre;

    /** 对白轮次（机械统计）：引号包裹的发言次数。与"行级占比"度量的是不同的事——
     *  占比高但轮次少 = 几段长对白；恋爱/智斗题材要的是高频短交锋（拉扯感来自轮次密度） */
    private Integer dialogueUtterances;
    // 本章计划的关键事件数（机械统计）：与 validChars 配合衡量"计划供给 vs 实际承载"
    private Integer keyEventCount;
    // 本章章型（机械取自章节计划 ChapterTypeVO.code）：密度反馈据此豁免过渡章，老数据为 null
    private String chapterType;
    // 本章结束时的**地点**（模型输出，与 timePoint 分开）：地点轨迹校准的机械可统计信号。
    // 此前只有 timePoint（"时间与地点"混在一串），按整串去重会把时间前缀算成不同地点；老数据为 null
    private String placePoint;
    // 本章是否出现"机制名 + 原理解释"的大段描述（机械统计，非模型输出）：供金手指机制
    // 跨章重复描述检查跨批累计——批内 contents 每批从空开始，只有落进摘要才能守住"全篇至多 2 次"；老数据为 null
    private Boolean mechanismDescribed;
    // 本章是否包含"主角能力/早慧展示"场景（摘要模型判定，）：供 UsedPatternPolicy
    // 检测「主角微动作展示 → 旁人注意/评价」的跨章套路——抽象结构重复抓不了文本相似度，
    // 只能靠结构化标注；老数据为 null（视为无标注，不参与统计）
    private Boolean abilityShowcased;
    // 展示形态短语（8-16 字，如"手指蘸水画圈引人注意""旁人评价不像四岁"），abilityShowcased=true 时填写；
    // 用于黑名单里点名"已用过的形态"，逼下章换载体
    private String abilityDisplayForm;

    /**
     * 本章的**生成模式**（FREE / SCAFFOLDED / RECOVERY，2026-09-22）。
     *
     * <p>落进摘要而非只写日志，是为了让体检能统计模式分布——尤其
     * **SCAFFOLDED / RECOVERY 占比**（= 兜底路径被吃掉的频率）。
     * 落盘还有个必要原因：{@code StoryJobService} 的健康判停是**读盘重算**的，
     * 只放内存的话那条路看不到。
     */
    private String generationMode;

    /** 本章正文 prompt 是否注入了认知边界（账本里有认知类状态且成功注入） */
    private Boolean knowledgeBoundaryInjected;

    /**
     * 本章生成时跨章记忆检索是否**降级为空召回**（RECALL_DEGRADED）——基础设施抖动，与正文质量无关。
     *
     * <p>与 {@link #knowledgeBoundaryInjected}、{@link #generationMode} 同属"只写日志看不见、
     * 必须落盘才能被统计"的一类：降级与"确实无命中"返回的命中表完全一样（都是空表），
     * 调用方区分不了，体检也就无从知道"本批有几章是在无记忆前缀下裸跑的"。
     *
     * <p>**刻意不进质量债**：质量债会回灌给写手当作"你上一章犯的错"，把基础设施故障塞进去
     * 等于给模型下错误指令。老批次该字段为 null（落盘前生成的），统计时只计入有记录的章。
     */
    private Boolean recallDegraded;

    /**
     * 本章的修订验证是否**根本没跑成**（AUDIT_VERIFY_DEGRADED）——基础设施抖动，与正文质量无关。
     *
     * <p>为 true 时本章带着"【未验证】的 BLOCKING"结案：内容侧仍按未通过处理（不放行未验证的稿），
     * 但**不记入质量债**——把基础设施故障写成内容缺陷会回灌给写手当作"你上章犯的错"。
     * 落盘是为了让体检把【未验证】与【真债】分开计数（与 {@link #recallDegraded} 同一约定）。
     * 老批次为 null。
     */
    private Boolean auditVerifyDegraded;

    /**
     * 本章计划声明的**悬念档位**（取自章计划的 {@code suspenseBeat}，逐字对应蓝图档位表）。
     *
     * <p>落进摘要而非只留在计划文件里，是为了让体检能统计"主线是否在原地打转"——
     * 健康判停走的是**读盘重算**，只看计划文件那条路看不到。
     */
    private String suspenseBeat;

    /**
     * **可核验细节**（2026-09-22 新增）：本章中被正文明确描写、且**可供机械核验**的标志性动作或物证，
     * 逐字摘录原句片段（如「指尖在桌面上无意识地轻叩了两下」「切成三段：哒、哒哒、哒」）。
     *
     * <p><b>为什么单开一个字段</b>：{@code summary} 按设计只记主干、明确排除情绪与环境渲染，
     * 而阶段退出条件常要求"某动作被文本明确描写"这类**细节证据**——
     * 结果就是"正文明明写了、摘要没记、核验只能判未达成"（实测踩过，见 2026-09-22 复盘）。
     * 放进独立字段既补上了核验所需的粒度，又不会把细节注水进 summary 去自我强化。
     */
    private List<String> verifiableDetails;

    /**
     * 三账本归属：挂起层（pendingFacts）是三本账混合后的扁平列表，
     * 裁决入账时必须知道该条原本属于哪一本，否则救回的事实会写错账。
     */
    public enum AccountKind {
        CHARACTER("character"),
        ITEM("item"),
        FACTION("faction");

        private final String code;

        AccountKind(String code) {
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    /**
     * 通用状态条目：摘要模型输出载体，角色/物品/势力三账本共用
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StateEntry {
        // 名称（角色名/物品名/势力名）
        private String name;
        // 本章结束时的当前状态（伤势/修为/位置/持有物/动向等）
        private String status;
        // 支持该状态的正文原句片段（≤50 字）：经 EvidenceMatch 分档命中校验，表述差异
        // （归一化/省略号截断/短语覆盖/跨章引用/单短语锚定）可正常入账，完全无法在正文定位者
        // 转入 pendingFacts 隔离；缺省（降级路径/模型未遵从）沿用旧规则直接入账
        private String evidence;
        // 证据档位（EvidenceMatch.Tier.code）：①被隔离条目填逐出档（phrase-partial 值得裁决、
        // no-match 优先级最低），供裁决层分流；②放宽留痕档（spread / anchored）入账时也填，
        // 供观测层统计放宽档占账本比例
        private String evidenceTier;
        // 来源账本（AccountKind.code）：仅被隔离条目填充，供裁决层把救回的事实写回原账本
        private String sourceAccount;

        /**
         * 兼容构造器：evidenceTier / sourceAccount 由证据校验与裁决层回填，构造时无需提供。
         * 保留旧三元签名，避免既有调用点随字段新增而失效
         */
        public StateEntry(String name, String status, String evidence) {
            this.name = name;
            this.status = status;
            this.evidence = evidence;
        }
    }

    /** 配角在本章的自主目标与选择，供后续章节恢复人物能动性。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CharacterBeat {
        private String name;
        private String goal;
        private String decision;
        private String consequence;
        private String relationshipChange;
        private String nextIntent;
        private String evidence;
    }

    /**
     * 伏笔种子条目：新埋伏笔 + 埋设处原文引用 + 主线重要度，供伏笔账回灌与动态权重打分
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeedEntry {
        // 伏笔描述（与 foreshadowingNew 中对应条目一致）
        private String content;
        // 埋设处正文原文引用（≤80 字）；经子串校验，编造/漂移则为 null
        private String excerpt;

        /**
         * **伏笔稳定 ID**（2026-10-13 新增）：格式 {@code F{埋设章号}-{本章序号}}，如 {@code F7-2}。
         *
         * <p><b>为什么需要它</b>：全链路此前靠**文本匹配**关联伏笔——账本销账用双向 contains、
         * 排期打标用 LCS≥4、寿命指标用 LCS≥6。这条设计漏损严重且难以计量，最典型的事故是
         * 「2026」那条线第 5 章埋、第 93/95 章其实已兑现，却到第 100 章仍在被逐段下令补收——
         * 根因就是摘要模型的措辞与账本原文对不上，机械层认不出"已经收了"。
         *
         * <p><b>为什么由机械层分配、不由模型填写</b>：ID 是记账凭据，模型的职责是语义判断
         * （这是不是伏笔、算不算已兑现），记账归机械层。模型只需在回收时**回抄 ID**
         * （选择题远比复述题可靠），机械层做集合差集即可，零模糊匹配。
         *
         * <p><b>兼容性</b>：老数据为 null——所有消费端在 ID 缺失时回退到既有文本匹配，
         * 行为与引入前完全一致（与 {@code resolvable}/{@code scheduledPayoffChapter} 同款约定）。
         */
        private String foreshadowId;

        // 埋设时评估的主线重要度（1-5）：1=路人闲聊/氛围点缀，2=装备/支线，3=势力/地图级布局，
        // 4=重大转折/关系变化，5=主线核心/生死攸关；老数据为 null，打分时按默认档处理
        private Integer importance;
        // 谜底/答案关键词（3-8 条，≤20 字）：揭示前任何章节的正文严禁出现这些词——
        // 机械层逐字拦截 + 审校判变相泄露，揭示章按计划关键事件豁免；老数据为 null，天然兼容
        private List<String> payoffHints;

        /**
         * **是否承担"兑现义务"**（2026-10-02 新增）——用于把"要养/要收的伏笔"与
         * "人物状态、氛围点缀、当时的事实陈述"分开。
         *
         * <p><b>为什么需要它</b>：实测 41 条埋设中，大量条目的本质是**状态或氛围**而非伏笔，例如
         * "角色甲评价儿子'邪性'""张阿婆说小孩子眼睛干净""存折四千二加棺材本刚够八千"。
         * 它们永远处于"在途"，把「在途 31 条」这个数字注水，也让伏笔寿命指标的分母塞满永不兑现项
         * ——实测 `foreshadowSpan` 因此从 2.23 章"恶化"到 1.90 章（**指标惩罚了正确的修复**）。
         *
         * <p><b>为什么用 Boolean 而不是复用 importance</b>：`importance` 已经定义了
         * `1=纯氛围/路人闲聊` 这个档位，恰恰就是"无需兑现"那一类——但实测该档位
         * <b>被使用 0 次</b>（模型只输出 2/3/4/5）。**模型会逃避多级刻度的低档位**，
         * 因此这里用没有低档位可逃的 Boolean。
         *
         * <p><b>取值语义</b>：
         * <ul>
         *   <li>{@code TRUE} —— 有人承诺/约定，或出现了未解释的物件/信息，后续**必须**收束（兑现或明确弃置）；</li>
         *   <li>{@code FALSE} —— 人物状态判断、氛围描写、当时的事实陈述，不承担兑现义务；
         *       不计入伏笔寿命指标与在途统计；</li>
         *   <li>{@code null} —— 老数据或模型未判定。指标侧**单独统计"未标注占比"**，
         *       不计入寿命样本（避免旧数据继续污染），用占比观测判断模型是否在漏标。</li>
         * </ul>
         */
        private Boolean resolvable;

        /**
         * **计划回收章**（2026-10-02 新增，P2a 先落字段，P2b 才由排期表回填）。
         *
         * <p>由记忆层在**埋设章**机械回填：把本章新埋 seed 与排期表中 {@code plantChapter} 落在此章
         * （窗口 ±2）的条目匹配，命中则写入。模型不填——兑现时机是长视野决策。
         *
         * <p>下游消费：清账候选过滤（未到期的排期线不进清账）、漏收指标 {@code foreshadowMissed}。
         * {@code null} = 未排期（老数据/自由萌发）——此时所有消费端行为与引入该字段前**完全一致**。
         */
        private Integer scheduledPayoffChapter;

        /** 三参便利构造器（老 schema：无谜底关键词），兼容既有调用点 */
        public SeedEntry(String content, String excerpt, Integer importance) {
            this.content = content;
            this.excerpt = excerpt;
            this.importance = importance;
        }

        /** 是否计入伏笔寿命统计：只有**明确声明承担兑现义务**的条目才计入 */
        public boolean countedForSpan() {
            return Boolean.TRUE.equals(resolvable);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConsistencyFact {
        // TIMELINE / INJURY / TERM / NUMBER
        private String type;
        private String subject;
        private String value;
        private String scope;
        private String evidence;
        // 证据档位（EvidenceMatch.Tier.code）：被隔离条目填逐出档供裁决层分流；放宽留痕档
        // （spread / anchored）入账时也填，供观测层统计
        private String evidenceTier;

        /**
         * 兼容构造器：evidenceTier 由证据校验回填，构造时无需提供。
         * 保留旧五元签名，避免既有调用点随字段新增而失效
         */
        public ConsistencyFact(String type, String subject, String value, String scope, String evidence) {
            this.type = type;
            this.subject = subject;
            this.value = value;
            this.scope = scope;
            this.evidence = evidence;
        }
    }

}
