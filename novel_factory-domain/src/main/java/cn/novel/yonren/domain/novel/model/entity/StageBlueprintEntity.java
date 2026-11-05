package cn.novel.yonren.domain.novel.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 阶段蓝图实体：滚动大纲的单个阶段，治长线漂移的方向盘。
 * 每跨一个阶段边界生成一版，链式结转（carriedTasks 承接上一版任务并标注处置状态），
 * 全链落盘 rolling-outline.json 供漂移审计；当前版随记忆前缀注入规划 prompt。
 * 章节覆盖区间由机械计算，不信任模型输出：起步首版按默认窗长（1-10 章）写死，
 * 后续阶段起点 = 上一版终点 + 1，终点取模型自定值并钳制在 30-80 章内
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StageBlueprintEntity {

    // 阶段序号（1 起，逐版递增）
    private Integer stageNo;
    // 覆盖区间起点章号（含）
    private Integer startChapter;
    // 覆盖区间终点章号（含）
    private Integer endChapter;
    // 当前故事阶段：NORMAL/PREPARATION/ESCALATION/WAR/RESOLUTION/EPILOGUE
    private String storyPhase;
    // 是否已经声明收官卷；一旦为 true，后续蓝图不得回退
    private Boolean finalVolumeDeclared;
    // 篇幅是估算而非完结条件：用于工作台展示与滚动校准
    private Integer estimatedTotalChapters;
    // 预估剩余章数区间下限（模型滚动校准估计；缺省继承上一版）
    private Integer estimatedRemainingChaptersMin;
    // 预估剩余章数区间上限（小于下限时被钳制为下限）
    private Integer estimatedRemainingChaptersMax;
    // 收官卷未完成的剧情节点及已完成节点
    private List<String> remainingFinaleBeats;
    // 已兑现的终局节点（终局审查对照；非空可防旧数据/模型漏字段误判停机）
    private List<String> completedFinaleBeats;
    // 阶段目标：本阶段结束时故事应到达的状态（1-2 句）
    private String stageGoal;
    // 本阶段末的故事时间（蓝图声明，）：时序锚 v2 的推进目标——蓝图是年龄/时间的唯一变更入口（境界式）
    // 由【进度对齐·大纲路标】的末段预算得出；写手/审计据此对表，摘要 timePoint 回验。旧数据为 null
    private String stageEndYear;
    // 本阶段末主角年龄（蓝图声明，与 stageEndYear 同批落地；旧数据为 null）
    private String stageEndAge;
    // ---- 三级规划归属（卷→弧→章）：弧归属其所在卷，弧在卷内行使阶段蓝图的职责 ----
    // 所属卷序号（机械回填，取当前卷 volumeNo）
    private Integer volumeNo;
    // 所属卷标题（机械回填，取当前卷 title；无卷时为 null 退化两段式）
    private String volumeTitle;
    // 本弧在卷内弧清单中的序号（模型自报，缺省保持 null，fail-soft 不影响规划）
    private Integer arcNo;
    // 本弧一句话目标（arcPlan 中对应 arc 的 oneLineGoal，模型自报）
    private String arcGoal;
    // 本阶段里程碑任务（状态型目标，严禁活动配额）
    private List<String> tasks;
    // 阶段进入护栏：本阶段开始时应已成立的局面事实（事实性陈述），随分段规划 prompt 注入作方向锚
    private List<String> entryConstraints;
    // 阶段退出条件：可核对的达成谓词清单（可观察/可引用/有终止性），阶段末章后逐条核验
    private List<String> exitConditions;
    // 本版 exitConditions 中由上一阶段"未达成条件"机械注入的原文清单（退场规则依据）：
    // 这些条件本阶段会再核验一次；若本阶段仍未达成则强制出账，不再注入——避免永不退场、单调累积
    private List<String> inheritedExitConditions;
    // 退出条件核验结果（与 exitConditions 逐一下标对齐）：机械校验达成证据后写回，随 rolling-outline.json 落盘
    private List<ExitConditionResult> exitResults;
    // 本书核心悬念：全书围绕"什么未知"展开（一句话，蓝图首次生成时确定、此后保持稳定）
    private String coreSuspense;
    // 核心悬念的推进档位表（有序，3-6 档）：从"完全不知"到"彻底摊牌"的中间状态，每档须可观察
    // （谁知道了什么 / 谁做了什么决定 / 什么被公开），不写内心感受。章计划逐章回填 suspenseBeat，
    // 机械校验档位缺失与倒退；经蓝图对表且去标签后非重复的章级细推进可豁免同档门禁，旧数据保留原门禁
    private List<String> suspenseLadder;
    // 阶段收束时的机械节奏报告（字数/伏笔/账本变更统计 + 失衡警示），注入下一版蓝图生成 prompt；阶段末章后写回
    private String stageReport;

    /**
     * **章级主线推进**（2026-10-02 新增）：本阶段**逐章**的主线推进，段计划必须逐字落地。
     *
     * 为什么需要它：{@link #suspenseLadder} 是"粗进度条"——3-6 档要覆盖 5-80 章的阶段
     * （阶段长度被 {@code MIN_STAGE_LENGTH=30 / MAX_STAGE_LENGTH=80} 钳制，批次不足时截断）。
     * 于是必然出现"多章共用同一档"：实测第 11-15 章五章回填同一档位，
     * **不是模型偷懒，是档位表本身没有能力区分它们**。
     *
     * 两者是**两个维度**，不可互相替代：
     *
     *   · {@code suspenseLadder} + 章计划 {@code suspenseBeat}：纵向——"走到第几格了"（序比较，防缺失/倒退）
     *   · {@code mainLineByChapter} + 章计划 {@code mainLineAdvance}：横向——"这一章主线做了什么"（防多章雷同）
     *
     *
     * 为什么只覆盖一个有界窗口：阶段可长达 80 章，一次产出 80 条不现实（易截断）。
     * 故只要求覆盖 {@code startChapter .. min(endChapter, startChapter + MAINLINE_WINDOW - 1)}，
     * 批次跑完若有余量，由下一版蓝图续写——与滚动大纲的既有哲学一致。
     *
     * 老数据为 null，注入与校验一律跳过（**留痕日志**，不静默）。
     */
    private List<MainLineBeat> mainLineByChapter;


    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MainLineBeat {
        // 该条推进对应的章号
        private Integer chapterNo;
        // 该章主线的具体推进（段计划须逐字落进对应章计划的 mainLineAdvance）
        private String advance;
    }

    // 上一版蓝图结转任务及处置状态
    private List<CarriedTaskEntity> carriedTasks;
    // 追进度要求（transient，仅进程内传递）：时序锚年 / 大纲段预算年 / 滞后年数。
    // 由 BuildStageBlueprintNode 计算、ChapterPlanSegmentPlanner 消费（跳接段 fail-closed 校验）；
    // fastjson 跳过 transient 不落 rolling-outline.json，每批蓝图重建时重算，无需持久化
    private transient Integer pacingAnchorYear;
    private transient Integer pacingBudgetStartYear;
    private transient Integer pacingLagYears;
    // 终局审查结果（仅收官阶段蓝图写回）：四维核验 + 返工/强制收官标记，随 rolling-outline.json 落盘
    private FinaleAuditEntity finaleAudit;

    /**
     * 退出条件核验结果：达成须给出可机械校验的证据（章节号 + 该章摘要内的连续原文引用），
     * 证据校验失败按未达成处理（宁严勿松）；核验服务失败时整体为 null（回退模型自评结转）。
     *
     * 2026-09-16 起核验按原子子句粒度进行（{@code ExitConditionPolicy.splitAtoms}）：
     * 复合条件（78% 的实测条件含"且/以及/同时"）只有全部分句达成才算整条达成，
     * {@code metAtoms}/{@code totalAtoms} 让"部分推进"可见，{@code note} 精确指出还差哪个分句。
     */
    @Data
    @NoArgsConstructor
    public static class ExitConditionResult {
        // 对应的退出条件原文（机械回填，与 exitConditions 对齐）
        private String condition;
        // 是否达成（证据校验失败置 false；复合条件需全部分句达成）
        private Boolean met;
        // 达成证据所在章节号（阶段区间内）
        private Integer chapterNo;
        // 达成证据原文引用（该章摘要内的连续子串）
        private String evidence;
        // 未达成缺口说明 / 证据校验失败注记 / 还差哪些分句
        private String note;
        // 已达成原子数 / 原子总数（单句条件为 0/1 或 1/1）；老数据为 null
        private Integer metAtoms;
        // 原子总数（与 metAtoms 配对；单句条件为 1）
        private Integer totalAtoms;

        /** 兼容构造器：metAtoms/totalAtoms 由核验服务回填，构造时无需提供 */
        public ExitConditionResult(String condition, Boolean met, Integer chapterNo,
                                   String evidence, String note) {
            this(condition, met, chapterNo, evidence, note, null, null);
        }

        public ExitConditionResult(String condition, Boolean met, Integer chapterNo,
                                   String evidence, String note, Integer metAtoms, Integer totalAtoms) {
            this.condition = condition;
            this.met = met;
            this.chapterNo = chapterNo;
            this.evidence = evidence;
            this.note = note;
            this.metAtoms = metAtoms;
            this.totalAtoms = totalAtoms;
        }
    }

    /**
     * 结转任务：上一版蓝图任务在本版的处置——完成/进行中（note 记进展）/放弃（note 记理由）
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CarriedTaskEntity {
        // 结转的任务内容
        private String content;
        // 处置状态：完成/进行中/放弃
        private String status;
        // 进展说明或放弃理由，可为空
        private String note;
    }

}
