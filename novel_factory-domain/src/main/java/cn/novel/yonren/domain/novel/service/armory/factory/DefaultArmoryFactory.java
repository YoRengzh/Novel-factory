package cn.novel.yonren.domain.novel.service.armory.factory;

import cn.novel.yonren.domain.framework.StrategyHandler;
import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.model.aggregate.StoryGenerateResultAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.ConsistencyIndexEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowScheduleEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowSettlementEntity;
import cn.novel.yonren.domain.novel.model.entity.QualityDebtEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.entity.StyleStatEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.entity.VolumeBlueprintEntity;
import cn.novel.yonren.domain.novel.service.armory.memory.RollingOutlineService;
import cn.novel.yonren.domain.novel.service.armory.node.RootNode;
import cn.novel.yonren.domain.novel.service.job.GenerationJob;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 规则树工厂。
 *
 * <p><b>树的合法入口只有两个</b>（2026-09-29 登记为显式契约，新增入口必须先在此登记）：
 * <ol>
 *   <li><b>完整树入口</b>：{@code armoryStrategyHandler().apply(command, dynamicContext)}——
 *       从 RootNode 顺序走完全部节点；</li>
 *   <li><b>树腰入口</b>：{@code StoryGenerateService.resumeAfterPlanApproval}——
 *       审批挂起后从正文生成节点续跑剩余阶段。前提：DynamicContext 来自挂起现场
 *       <em>原样保留</em>（含已批准计划/蓝图链/分段边界/故事上下文），规划段不会重跑。
 *       上下文残缺时该方法快速失败。</li>
 * </ol>
 * 在规划段节点内新增的任何逻辑，树腰入口不会执行——依赖这一点做设计决策时务必想清楚。
 */
@Service
public class DefaultArmoryFactory {

    @Resource
    private RootNode rootNode;

    public StrategyHandler<ArmoryCommandEntity, DynamicContext, StoryGenerateResultAggregate> armoryStrategyHandler() {
        return rootNode;
    }

    /**
     * 规则树执行过程中的动态上下文，节点间传递中间数据。
     *
     * <p><b>字段不变式表</b>（2026-09-29 规约：新增字段必须先在此登记，写明写入者/时机、
     * 读取者与关键约定；本类是三方共享黑板，时序耦合靠这张表而非人脑记忆维护）：
     * <pre>
     * | 字段                     | 写入者/时机                                      | 读取者               | 关键约定 |
     * |--------------------------|--------------------------------------------------|----------------------|----------|
     * | storyContextEntity       | BuildStoryContextNode；enforceChapterLimit 树前可截断 chapterCount | 树内全部 | 截断是**故意的契约**（完结保护），非脏写 |
     * | storyContext             | BuildStoryContextNode                            | 规划/正文 prompt     | 只读 |
     * | chapterPlanAggregate     | 规划链产出；审批续跑由 StoryJobService 覆写为裁决稿 | 校验/审批/worker/Persist | worker 循环中可被惰性分段/终局返工**追加**；收缩非法 |
     * | chapterContents          | worker 循环收集（循环尾才回写）                   | Persist/阶段报告     | 崩溃时可能不完整，以磁盘检查点为准 |
     * | chapterSummaries         | worker 逐章追加并逐章同步回写                     | 下一章记忆/规划/Persist | 摘要失败即终止本批（质量门） |
     * | consistencyIndex         | worker 每章 rebuild 后写回                        | Persist/下一章 prompt | |
     * | styleStat/styleFingerprints | worker 滚动合并                                | 下一章 prompt/Persist | |
     * | storyDir/runDir          | 服务层预载(续写)/计划检查点创建                   | worker/审批门/Persist | 创建非幂等，禁止重复创建 |
     * | chapterOffset            | StoryGenerateService 预载（首发 0）              | 全树                 | 进树后只读 |
     * | prevChapterTail          | 服务层预载 → worker 每章覆写                      | 下一章 prompt/规划   | 语义恒为"上一章结尾" |
     * | pendingConflicts         | 服务层预载 → worker 每章覆写                      | 下一章记忆           | 语义恒为"最近一章偏差警示" |
     * | qualityDebts/foreshadowSettlements | 服务层预载 → worker 滚动追加            | 规划/审校/Persist    | |
     * | stageBlueprints/volumes  | 服务层预载 → 蓝图节点链式追加                     | 全树                 | |
     * | stageBlueprint/currentVolume/volumeBlueprint | 蓝图节点                      | 规划/记忆 prompt     | |
     * | planSegments/planSegmentPrompts | BuildChapterPlanPromptNode                 | 规划调用/worker      | 跨段批次非空；prompts 只含已装配段 |
     * | plannedSegmentEnd        | BuildChapterPlanPromptNode 初值 → worker 逐段推进 → handleFinale 返工扩写 | worker/惰性规划 | **null=未分段**；Integer 非 int，禁止 0 哨兵 |
     * | plannedChapterCount      | BuildChapterPlanPromptNode                       | 校验/审批校验        | null=回退批次章数 |
     * | prompt/rawResult         | 规划链                                           | LLM 调用/复盘落盘    | rawResult 为最终采纳稿 |
     * | usedPromptMap            | 各节点/服务合并式写入（key 前缀区分）             | Persist              | 只增不覆盖 |
     * | job                      | StoryGenerateService 注入                        | worker/审批门        | 同步调试路径为 null |
     * | maxChapterCount          | StoryGenerateService（sticky cap）               | 蓝图预算/Persist     | 落盘后不可抬高 |
     * | planApproved             | 审批通过/续跑时置位                              | 审批门               | 置位后门直通 |
     * | creativeNotes            | StoryGenerateService 从 command 复制             | 三个规划 prompt      | 作者指令，进 prompt 顶部不裁剪 |
     * </pre>
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext {

        // 结构化故事上下文
        private StoryContextEntity storyContextEntity;
        // 构建好的故事上下文文本
        private String storyContext;
        // 章节计划聚合根
        private ChapterPlanAggregate chapterPlanAggregate;
        // 逐章生成的正文
        private List<ChapterContentEntity> chapterContents;
        // 逐章摘要（记忆系统滚动数据源，供下一章组装记忆前缀与落盘）
        private List<ChapterSummaryEntity> chapterSummaries;
        private ConsistencyIndexEntity consistencyIndex;
        // 风格统计（跨章重复句 + 疲劳词，逐章滚动合并）
        private StyleStatEntity styleStat;
        // 逐章落盘的故事目录（续写时由服务层预解析为原目录；计划检查点提前创建，树尾持久化节点复用）
        private java.nio.file.Path storyDir;
        // 本批 run 目录（chapter-plan 与 generation-record 所在）：计划检查点提前创建并落盘计划，
        // 树尾持久化复用（run 目录创建非幂等，禁止重复创建）；兜底路径下由树尾创建
        private java.nio.file.Path runDir;
        // 续写偏移：历史最大章号，计划章节号已是全局编号时仅用于渲染总量（首发为 0）
        private int chapterOffset;
        // 续写时原目录最后一章结尾原文，供规划 prompt 与跨批首章正文衔接；首发为 null
        private String prevChapterTail;
        // 续写时历史末章的一致性偏差警示；首发为 null
        private List<String> pendingConflicts;
        // 质量债（审校确认未修复的 BLOCKING 问题），续写时预载，批内滚动追加并落盘
        private List<QualityDebtEntity> qualityDebts;
        // 阶段蓝图链（滚动大纲，rolling-outline.json 落盘）；续写时预载，批内跨阶段边界时追加
        private List<StageBlueprintEntity> stageBlueprints;
        // 卷蓝图链（volume-blueprint.json 落盘，全书长期锚）；续写时预载，批内跨卷边界时追加
        private List<VolumeBlueprintEntity> volumes;
        // 当前生效的卷蓝图（最新一卷，无则为 null），注入弧/卷生成 prompt 作长期锚
        private VolumeBlueprintEntity currentVolume;
        // 本批新建的卷蓝图（供节点/检查点引用）
        private VolumeBlueprintEntity volumeBlueprint;
        // 卷末清账结算（foreshadow-settlements.json 落盘）：阶段出口未填伏笔裁决台账——续写时预载，
        // 阶段出口清账时追加；VOID 条目已出账，RECOVER 条目由规划 prompt 按段起点注入限期回收
        private List<ForeshadowSettlementEntity> foreshadowSettlements;
        // 伏笔兑现排期表（foreshadow-schedule.json 落盘）：阶段蓝图为伏笔预排的埋设章/回收章。
        // 续写时预载（种子上 scheduledPayoffChapter 的打标依据），阶段蓝图生成时追加新一版。
        // ⚠️ 必须随检查点快照落盘——丢了会让种子的打标变成悬空引用
        private List<ForeshadowScheduleEntity> foreshadowSchedules;
        // 当前生效的阶段蓝图（阶段未跨过时复用上一版），随记忆前缀注入规划 prompt；无则为 null
        private StageBlueprintEntity stageBlueprint;
        // 跨阶段批次的计划分段：按阶段蓝图把批次切成若干段逐段规划后合并；单段批次为 null
        private List<RollingOutlineService.StageSegment> planSegments;
        // 惰性分段规划：已规划覆盖的末章号（首发单段=批次末章；跨段批次随生成推进逐段更新）。
        // null=未分段（整批已规划或无分段）——刻意用 Integer 而非 int，禁止拿 0 当"未设置"哨兵
        private Integer plannedSegmentEnd;
        // 惰性分段规划：已规划段落的章节总数（校验预期章数；null 时校验回退批次章数）
        private Integer plannedChapterCount;
        /**
         * 本段规划二次重规划后仍遗留的闸门违规（2026-10-13）：计划层放行 ≠ 问题消失，
         * 正文 prompt 据此注入硬约束（如"本章推进必须独有、不得复用相邻章描述"）。
         * 段规划时写入，逐章消费；为空表示本段计划干净。
         */
        private String planResidualViolations;
        // 与 planSegments 逐一下标对齐的逐段 prompt；仅分段批次非空
        private List<String> planSegmentPrompts;
        // 装配好的 prompt
        private String prompt;
        // 模型返回的原始结果
        private String rawResult;
        // 使用到的 prompt 模板
        private Map<String, String> usedPromptMap;
        // 异步作业（可空）：同步调试路径为 null；取消信号/进度上报/runId 合并均由该字段承载
        private GenerationJob job;
        // 全书总章数硬上限（完结保护/预算收敛）：由服务层依据 sticky cap 计算，>0 时蓝图规划与截断据此执行
        private Integer maxChapterCount;
        // 文风指纹库（审校全票通过章节的高信息密度句，滚动上限 50）：续写时预载，批内滚动追加并落盘
        private java.util.List<String> styleFingerprints;
        // 本批创作要点（导演通道）：作者自由文本，注入卷/阶段/计划 prompt 顶部
        private String creativeNotes;
        // 章节计划审批门：本批计划已经过人工裁决（通过或超时放行）。
        // 置位后审批门直接放行到正文生成，不再重复挂起；恢复执行走"从正文阶段续跑"路径时也置位以便观测归因
        private boolean planApproved;
    }

}
