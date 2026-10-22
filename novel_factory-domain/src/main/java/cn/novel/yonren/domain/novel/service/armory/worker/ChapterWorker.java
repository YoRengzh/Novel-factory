package cn.novel.yonren.domain.novel.service.armory.worker;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterBeatsEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.ConsistencyIndexEntity;
import cn.novel.yonren.domain.novel.model.entity.FinaleAuditEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowSettlementEntity;
import cn.novel.yonren.domain.novel.model.entity.QualityDebtEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.entity.StyleStatEntity;
import cn.novel.yonren.domain.novel.service.armory.audit.ParagraphDensityAuditService;
import cn.novel.yonren.domain.novel.service.armory.contract.ChapterContract;
import cn.novel.yonren.domain.novel.service.armory.contract.GenerationMode;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.domain.novel.service.armory.candidate.ChapterCandidateService;
import cn.novel.yonren.domain.novel.service.armory.llm.LlmInvokeService;
import cn.novel.yonren.domain.novel.service.armory.memory.ChapterMemoryService;
import cn.novel.yonren.domain.novel.service.armory.memory.FinaleReviewService;
import cn.novel.yonren.domain.novel.service.armory.memory.ChapterSummaryService;
import cn.novel.yonren.domain.novel.service.armory.memory.ForeshadowScheduleService;
import cn.novel.yonren.domain.novel.service.armory.memory.ForeshadowSettlementService;
import cn.novel.yonren.domain.novel.service.armory.memory.LedgerAdjudicateService;
import cn.novel.yonren.domain.novel.service.armory.memory.QualityDebtService;
import cn.novel.yonren.domain.novel.service.armory.memory.QualityReviewService;
import cn.novel.yonren.domain.novel.service.armory.memory.RollingOutlineService;
import cn.novel.yonren.domain.novel.service.armory.memory.StageExitReviewService;
import cn.novel.yonren.domain.novel.service.armory.memory.StageReportService;
import cn.novel.yonren.domain.novel.service.armory.memory.StoryMemoryService;
import cn.novel.yonren.domain.novel.service.armory.memory.ForeshadowPriorityService;
import cn.novel.yonren.domain.novel.service.armory.memory.StyleStatService;
import cn.novel.yonren.domain.novel.service.armory.memory.ConsistencyIndexService;
import cn.novel.yonren.domain.novel.service.armory.plan.ChapterPlanPromptService;
import cn.novel.yonren.domain.novel.service.armory.plan.ChapterPlanSegmentPlanner;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptBudgetGuard;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptBudgetGuard.PrefixBlock;
import cn.novel.yonren.domain.novel.service.armory.prompt.valobj.PromptContext;
import cn.novel.yonren.domain.novel.service.armory.candidate.CandidateSampleService;
import cn.novel.yonren.domain.novel.service.armory.quality.BatchHealthReport;
import cn.novel.yonren.domain.novel.service.armory.quality.BatchHealthService;
import cn.novel.yonren.domain.novel.service.armory.quality.GateResult;
import cn.novel.yonren.domain.novel.service.armory.quality.QualityGate;
import cn.novel.yonren.domain.novel.service.armory.quality.UsedPatternPolicy;
import cn.novel.yonren.domain.novel.service.job.GenerationJob;
import cn.novel.yonren.types.enums.GenreTypeVO;
import cn.novel.yonren.types.enums.PromptScene;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import cn.novel.yonren.types.utils.JsonRepair;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 逐章生成器：从 GenerateChapterContentNode 自然拆出（D8）的逐章循环体。
 * 每章生成后立即压缩为结构化摘要（记忆滚动），下一章的 prompt 携带
 * 三层记忆底层——近章摘要 + 三账本 + 伏笔账 + 上一章结尾 + 上章偏差警示。
 * 惰性分段规划：跨阶段批次只预规划首段，生成推进到段边界时用最新记忆规划后续段
 * （消除"批次开头一次性规划、后段记忆过旧"的陈旧问题）；阶段末章触发
 * 机械节奏报告与退出条件外部审计（结果写回蓝图随 rolling-outline 落盘）。
 * 异步作业通过 DynamicContext.job 做协作取消检查（每章迭代开头）、进度上报与状态落盘；
 * 同步路径 job 为 null，行为与拆分前完全一致
 */
@Service
@Slf4j
public class ChapterWorker {

    /**
     * Jackson 默认严格模式会拒绝字符串内未转义的控制字符（如真实换行），
     * 模型长文本输出偶发该问题，这里放宽解析。
     */
    private static final BeanOutputConverter<ChapterContentEntity> CHAPTER_CONTENT_CONVERTER =
            new BeanOutputConverter<>(ChapterContentEntity.class,
                    JsonMapper.builder().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS).build());

    /** 终局审查返工重试预算：审查不通过最多重写几轮，防递归失控（配合顶帽头寸双护栏） */
    private static final int MAX_FINALE_REWORK = 2;
    /** 每轮返工的章节批次长度（终局返工窗口） */
    private static final int REPENT_BATCH = 3;

    /** 终局门禁判定结果：通过=完结 / 拒绝=继续在本批写返工章 / 强制=预算或头寸耗尽被迫收官 */
    private enum FinaleVerdict { CONTINUE, COMPLETE, FORCE_CLOSE }

    @Resource
    private LlmInvokeService llmInvokeService;

    @Resource
    private ChapterSummaryService chapterSummaryService;

    /** 账本挂起裁决（L1 兜底通道）：机械分档后残余的挂起项交 LLM 判定并回填可逐字核对的引文 */
    @Resource
    private LedgerAdjudicateService ledgerAdjudicateService;

    /** 惰性分段规划的 prompt 组装：与链式路径共用同一实现（ChapterPlanPromptService） */
    @Resource
    private ChapterPlanPromptService planPromptService;

    /** 惰性分段规划执行器：分支推演/三级降级/结构校验/主线闸门与链式路径共用同一实现 */
    @Resource
    private ChapterPlanSegmentPlanner planSegmentPlanner;

    @Resource
    private StageExitReviewService stageExitReviewService;

    @Resource
    private BatchHealthService batchHealthService;

    @Resource
    private ParagraphDensityAuditService paragraphDensityAuditService;

    @Resource
    private CandidateSampleService candidateSampleService;

    /** 全书质量评分（每 N 章一次，fail-soft）：为"这本书在变好还是变差"提供唯一对照基线 */
    @Resource
    private QualityReviewService qualityReviewService;

    @Resource
    private FinaleReviewService finaleReviewService;

    @Resource
    private ForeshadowSettlementService foreshadowSettlementService;

    @Resource
    private ForeshadowScheduleService foreshadowScheduleService;

    @Resource
    private StageReportService stageReportService;

    @Resource
    private ChapterMemoryService chapterMemoryService;

    /** 前缀总预算守门：各块独立封顶之和仍可能击穿模型输入窗口，逐章装配时统一裁剪 */
    @Resource
    private PromptBudgetGuard promptBudgetGuard;

    /** 卷链语义（章号所属卷）；正文前缀的卷方向锚据此选取，不取"最新一卷" */
    @Resource
    private RollingOutlineService rollingOutlineService;

    @Resource
    private StyleStatService styleStatService;

    @Resource
    private QualityDebtService qualityDebtService;

    @Resource
    private QualityGate qualityGate;

    @Resource
    private ChapterCandidateService chapterCandidateService;

    @Resource
    private cn.novel.yonren.domain.novel.service.armory.beats.ChapterBeatsService chapterBeatsService;

    @Resource
    private StoryMemoryService storyMemoryService;

    @Resource
    private ConsistencyIndexService consistencyIndexService;

    /** 机械校验事件留痕（账本失配等）：只写 WARN 无法聚合统计，落盘才能量化管道漏损 */
    @Resource
    private cn.novel.yonren.domain.novel.service.armory.quality.MechanicalEventRecorder mechanicalEventRecorder;

    @Resource
    private IStoryRepository storyRepository;

    /**
     * 执行整批逐章生成，完成后把正文/摘要/质量债/风格统计回写 DynamicContext，
     * 树尾持久化节点据此统一落盘
     */
    public void generateAll(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        ChapterPlanAggregate chapterPlan = dynamicContext.getChapterPlanAggregate();
        StoryContextEntity storyContext = requestParameter.getStoryContextEntity();
        int totalChapters = chapterPlan.getChapters().size();
        cn.novel.yonren.domain.novel.model.valobj.StoryVO.Module module = requestParameter.getStoryVO().getModule();

        // 续写：编号偏移/上章结尾/偏差警示由服务层预载，计划章节号已是全局编号；首发均为空值
        List<ChapterSummaryEntity> summaries = dynamicContext.getChapterSummaries() != null
                ? dynamicContext.getChapterSummaries() : new ArrayList<>();
        int offset = dynamicContext.getChapterOffset();
        if (offset > 0) {
            log.info("续写偏移：新章章节号从 {} 开始", offset + 1);
        }

        List<ChapterContentEntity> chapterContents = new ArrayList<>();
        List<String> pendingConflicts = dynamicContext.getPendingConflicts();
        // 上章审校反馈（P2）：语义 MINOR 的人类可读摘要，本章审校结论产出、下一章 prompt 消费
        //（批内滚动；跨批的同类问题走质量债通道）
        String reviewFeedback = null;
        // 质量债：续写时预载历史债务，批内滚动追加（审校确认未修复的 BLOCKING 问题），逐章验证自动核销
        List<QualityDebtEntity> qualityDebts = ( dynamicContext.getQualityDebts() != null
                ? dynamicContext.getQualityDebts() : new ArrayList<>() );
        // 卷末清账结算：续写时预载历史裁决，阶段出口清账时追加（VOID 已出账，RECOVER 供规划注入）
        List<ForeshadowSettlementEntity> foreshadowSettlements = ( dynamicContext.getForeshadowSettlements() != null
                ? dynamicContext.getForeshadowSettlements() : new ArrayList<>() );
        // 机械层风格账本：跨章重复句 + 疲劳词（续写时从历史预载）
        StyleStatEntity styleStat = dynamicContext.getStyleStat() != null
                ? dynamicContext.getStyleStat() : styleStatService.empty();

        // 检查点落盘：续写时沿用服务层解析的原目录（原目录追加）；首发新建。每章完成即写盘
        Path storyDir = dynamicContext.getStoryDir();
        if (storyDir == null) {
            try {
                storyDir = storyRepository.createStoryDirectory();
            } catch (Exception e) {
                // 目录创建失败不阻塞生成，树尾持久化节点会再尝试
                log.warn("故事目录创建失败，本轮退化为树尾统一落盘", e);
                storyDir = null;
            }
            dynamicContext.setStoryDir(storyDir);
        }

        GenerationJob job = dynamicContext.getJob();
        // 文风指纹库：续写时预载历史指纹（首发为空表），全票通过章节逐章追加
        List<String> styleFingerprints = preloadStyleFingerprints(dynamicContext, storyDir);
        // 惰性分段规划：分段蓝图与边界由规划节点计算，生成推进到未规划段时才用最新记忆规划该段
        List<RollingOutlineService.StageSegment> segments = dynamicContext.getPlanSegments();
        int batchEnd = offset + (storyContext.getChapterCount() == null ? 0 : storyContext.getChapterCount());
        // null=未分段（整批已规划）：局部变量取 MAX_VALUE 让"是否需要补规划"的判断恒为否
        Integer plannedEndStored = dynamicContext.getPlannedSegmentEnd();
        int plannedSegmentEnd = plannedEndStored != null ? plannedEndStored : Integer.MAX_VALUE;

        int idx = 0;
        // 蓝图/卷落盘降频用的变化检测：两者一个批次内至多变一次（阶段/卷边界），
        // 而此前每章全量重写（写放大随书线性增长）。**判据是列表大小 + 阶段报告是否被回写**：
        //   · 链式生成只做 append，大小变化即结构变化；
        //   · 阶段末章会 setStageReport 回写蓝图**内容**（大小不变）——只比大小会让阶段报告漏盘；
        //   · 首章 counts=-1 强制写一次建立基线（resume 场景该文件已在盘上，重写是幂等的）。
        // 树尾 PersistChapterPlanNode 仍会无条件全量兜底，故任何判断失误都不会永久丢数据。
        int lastBlueprintCount = -1;
        int lastVolumeCount = -1;
        boolean blueprintDirty = true;
        while (true) {
            // 已规划章节用尽：跨段批次且有未规划段 → 用最新记忆惰性规划下一段（消除陈旧）；
            // 全部规划完成（或无分段）→ 批次结束
            if (idx >= chapterPlan.getChapters().size()) {
                if (segments != null && plannedSegmentEnd < batchEnd) {
                    plannedSegmentEnd = planNextSegment(requestParameter, dynamicContext, segments,
                            plannedSegmentEnd, batchEnd, chapterContents, summaries);
                    continue;
                }
                break;
            }
            ChapterPlanItemEntity item = chapterPlan.getChapters().get(idx);
            int globalNo = item.getChapterNo();
            // 下一章超出已规划覆盖区间（蓝图边界与分段错位的防御）→ 先补规划再取章
            if (segments != null && globalNo > plannedSegmentEnd) {
                plannedSegmentEnd = planNextSegment(requestParameter, dynamicContext, segments,
                        plannedSegmentEnd, batchEnd, chapterContents, summaries);
                continue;
            }
            long chapterStart = System.currentTimeMillis();

            // 协作取消/预算熔断检查（迭代开头）：上一章检查点已落盘，停在此处零损失
            // break 后照常回写上下文并走树尾持久化完成部分落盘
            if (job != null && (job.isCancelRequested() || job.isBudgetExceeded())) {
                log.info("作业 {} {}，第 {} 章开始前停止（已完成章节均已落盘）", job.getJobId(),
                        job.isCancelRequested() ? "取消已受理" : "触发预算熔断", globalNo);
                break;
            }
            if (job != null) {
                // 分母取现值：惰性分段/终局返工会向计划追加章节，开局算死的 totalChapters 会失真
                job.updateProgress("CHAPTER_GENERATION", globalNo, offset + chapterPlan.getChapters().size(),
                        storyDir == null ? null : storyDir.getFileName().toString());
            }

            PromptContext ctx = PromptContext.builder()
                    .theme(storyContext.getTheme())
                    .style(storyContext.getStyle())
                    .chapterNo(globalNo)
                    .totalChapters(offset + totalChapters)
                    .chapterType(item.getChapterType())
                    .chapterBrief(buildChapterBrief(item))
                    .build();

            // 底层记忆：阶段蓝图（方向锚定）+ 近章摘要 + 三账本 + 伏笔账 + 上章偏差警示 + 上一章结尾
            // 跨批首章取服务层预载的原目录最后一章结尾，批内取上一章结尾
            String prevTail = chapterContents.isEmpty()
                    ? dynamicContext.getPrevChapterTail()
                    : chapterMemoryService.tailByParagraph(chapterContents.get(chapterContents.size() - 1).getContent(),
                            ChapterMemoryService.DEFAULT_PREV_TAIL_LENGTH);
            // 二期/三期：相关性检索唤醒久远记忆（窗口外章节摘要 + 账本/设定）。
            // 失败语义：瞬时失败在服务内已有界重试，重试耗尽/确定性失败降级为空召回并 WARN 留痕
            //（RECALL_DEGRADED），不终止作业；首章无历史记忆可唤醒，短路跳过（避免对不存在集合的无效检索）
            // 用带状态的入口拿降级标记：降级与"真无命中"返回的都是空表，不取状态就无法汇总到体检
            StoryMemoryService.RecallOutcome recall = summaries.isEmpty()
                    ? new StoryMemoryService.RecallOutcome(List.of(), false)
                    : storyMemoryService.retrieveWithOutcome(
                            module, storyDir,
                            storyMemoryService.buildContentQuery(ctx.getTheme(), ctx.getStyle(), item),
                            recallMinChapterNo(summaries),
                            storyContext.getWorldId());
            List<StoryMemoryService.RecallHit> recallHits = recall.hits();
            // 卷方向锚随记忆前缀注入：按章号取所属卷——批次跨卷边界时
            // 最新卷可能尚未开始，取它会把下一卷主旨/卷级伏笔提前泄给旧卷章节
            // 防御性拷贝：档案块要追加进列表，不依赖 buildMemoryBlocks 返回可变集合
            List<PromptBudgetGuard.Block> memoryBlocks = new ArrayList<>(chapterMemoryService.buildMemoryBlocks(
                    summaries, prevTail, pendingConflicts, qualityDebts,
                    currentStageBlueprint(dynamicContext.getStageBlueprints(), globalNo),
                    rollingOutlineService.volumeAt(dynamicContext.getVolumes(), globalNo), recallHits,
                    item.getTimeAdvance()));
            // 新书首段（无摘要）：追加设定兜底年龄锚——否则第一章在零年龄约束下生成 实测）
            chapterMemoryService.prependSettingsAnchorIfNoSummaries(memoryBlocks, summaries,
                    storyContext.getWorldSetting(), storyContext.getProtagonist(), storyContext.getOutline());
            // 实体档案（E1）：本章涉及的休眠实体（老角色/旧物品/势力）的档案注入——
            // 近章窗口与语义检索对"老实体按名词回归"都不可靠，见 buildEntityDossierBlocks
            memoryBlocks.addAll(chapterMemoryService.buildEntityDossierBlocks(
                    summaries, item, dynamicContext.getConsistencyIndex()));
            // 账本末态红线：位置/持有物/修为的对齐锚点（衔接类 consistency 矛盾的前置防线），置于前缀最顶部
            String edgeState = chapterMemoryService.renderLedgerEdgeState(summaries);
            // 机械层风格警示：跨章逐字重复句 + 疲劳词超频（纯代码统计）
            String styleWarning = styleStatService.renderWarning(styleStat);
            // 疲劳词前置预警：把机械门禁词表与红线亮给初稿，降低初稿超标率、省一轮修订
            String fatigueBlacklist = styleStatService.renderContentFatigueBlacklist(styleStat);
            // 重复意象警示：全书累计超频的具体意象点名 + 替换方向（词表外置、按题材取用）
            String imageryWarning = styleStatService.renderImageryWarning(styleStat);
            String consistencyPrompt = consistencyIndexService.renderPrompt(dynamicContext.getConsistencyIndex());
            // 伏笔保密边界：本章禁泄清单（已埋未揭伏笔的谜底关键词，揭示章按计划豁免）前置亮给写手
            String secrecyGuardPrompt = chapterMemoryService.buildSecrecyGuard(summaries, item).promptBlock();
            // 文风指纹库：本书已建立的风格基准（正向激励，补"只堵不疏"）
            String fingerprintPrompt = styleStatService.renderFingerprints(styleFingerprints);
            // 已用情节模式黑名单：防止LLM重复使用相同情节套路（如"演武场演示→长老震惊→获授权"被用了两次）
            String usedPatternBlacklist = buildUsedPatternBlacklist(summaries, globalNo);
            // 前缀总预算守门：各块独立封顶之和仍可能击穿模型输入窗口，统一按优先级装配——
            // 渲染顺序：末态红线 → 记忆各节（buildMemoryBlocks） → 门禁类块 → 三账本/伏笔账；
            // 第 4 档内按入参顺序贪心填装，三账本/伏笔账（常合计 5k+ 字，第 4 档主要消耗方）抽到末尾——
            // 预算吃紧时由它们承担截断，保住情节模式黑名单/审校反馈两个小纠正通道
            //（实测第 111/112/115 章：截断[一致性索引]清零预算后两通道整批出局。伏笔账按动态权重
            //  分级渲染、尾部为最低分背景条目，是最抗截的块，见 ChapterMemoryService.renderForeshadowing）
            List<PromptBudgetGuard.Block> ledgerBlocks = detachLedgerBlocks(memoryBlocks);
            List<PromptBudgetGuard.Block> prefixBlocks = new ArrayList<>();
            prefixBlocks.add(PrefixBlock.EDGE_STATE.toBlock(edgeState));
            prefixBlocks.addAll(memoryBlocks);
            prefixBlocks.add(PrefixBlock.STYLE_WARNING.toBlock(styleWarning));
            prefixBlocks.add(PrefixBlock.FATIGUE_BLACKLIST.toBlock(fatigueBlacklist));
            prefixBlocks.add(PrefixBlock.CONSISTENCY.toBlock(consistencyPrompt));
            prefixBlocks.add(PrefixBlock.SECRECY_GUARD.toBlock(secrecyGuardPrompt));
            prefixBlocks.add(PrefixBlock.STYLE_FINGERPRINT.toBlock(fingerprintPrompt));
            prefixBlocks.add(PrefixBlock.USED_PATTERN_BLACKLIST.toBlock(usedPatternBlacklist));
            prefixBlocks.add(PrefixBlock.IMAGERY_WARNING.toBlock(imageryWarning));
            // 上章审校反馈（P2）：批内滚动——第 1 章无反馈（null 块由装配器过滤）
            if (StringUtils.isNotBlank(reviewFeedback)) {
                prefixBlocks.add(PrefixBlock.REVIEW_FEEDBACK.toBlock(reviewFeedback));
            }
            prefixBlocks.addAll(ledgerBlocks);
            String fullPrefix = promptBudgetGuard.assembleChapterPrefix(globalNo, prefixBlocks);

            // 章节契约 + 生成模式：契约把"任务层"从一段文本提升为可判定对象；
            // 模式由「契约完整度 + 节拍可用性」决定，三档都有真实触发场景，且落日志以便归因
            ChapterContract contract = ChapterContract.of(item, summaries);
            ChapterBeatsEntity beats = chapterBeatsService.buildBeats(
                    requestParameter.getStoryVO(), item, globalNo,
                    buildBeatsContext(summaries, globalNo));
            GenerationMode mode = GenerationMode.decide(contract, beats != null);
            log.info("第 {} 章生成模式：{}（契约{}；节拍{}）", globalNo, mode.getLabel(),
                    contract != null && contract.isComplete() ? "完整"
                            : "缺" + String.join("/", contract == null
                                    ? java.util.List.of("计划") : contract.missingParts()),
                    beats == null ? "不可用" : beats.getBeats().size() + " 拍");

            String userPrompt = buildChapterPrompt(dynamicContext.getStoryContext(), item, fullPrefix, globalNo,
                    contract, storyContext);
            // 段计划遗留违规（2026-10-13）：计划层二次重规划仍未通过的推进问题，在此转成对写手的硬约束。
            // 实测第 136 章：计划闸门报了"136/137 事件重合、档位倒退"仍放行，写手拿到的是一份残缺契约，
            // 首稿 87 字（把重叠事件写完就无料可写）——把违规原文交给写手，至少能止损到"本章写别的"。
            if (StringUtils.isNotBlank(dynamicContext.getPlanResidualViolations())) {
                userPrompt += "\n\n【本段计划的遗留问题（规划校验两次未通过仍放行，写作时必须规避）】"
                        + dynamicContext.getPlanResidualViolations()
                        + "\n⚠️ 以上问题意味着本段计划存在相邻章推进雷同或档位安排不当。"
                        + "你只负责本章：**严格按本章计划的关键事件写作，不得把相邻章的推进内容写进本章**；"
                        + "若本章计划与相邻章确有重叠，只写属于本章的那一部分（不同的场景、不同的对手、不同的结果），"
                        + "并保证本章有一个明确的、可与相邻章区分开的推进落点。";
            }
            // 场景节拍层：计划 → 正文之间插入逐拍供给（防注水的治本改动）；
            // 节拍不可用时用通用骨架兜底——保住因果链，而不是放任模型自由发挥。
            // FREE（enforcesBeats=false）时节拍降为"建议顺序"，把场景划分与对白比例的自由还给模型。
            // ⚠️ 这句话必须由**节拍块自己**说：此前它由 FREE_BEATS_NOTE 单独追加在节拍块**之前**，
            // 紧随其后的节拍块又写着"请逐拍扩写，不要跳过或合并"——后者更靠后、更具体，实际生效，
            // 于是 enforcesBeats() 写好了、有单测、生产代码却从未调用，FREE 名不副实。
            String beatsPrompt = beats == null ? ""
                    : chapterBeatsService.renderBeatsPrompt(beats, mode.enforcesBeats());
            userPrompt += StringUtils.isNotBlank(beatsPrompt) ? beatsPrompt : FALLBACK_SKELETON;
            if (mode.forbidsOutOfPlanElements()) {
                userPrompt += RECOVERY_CONSTRAINTS;
            }

            ChapterContentEntity chapterContent = generateChapterWithFallback(
                    requestParameter, item, ctx, userPrompt, globalNo, dynamicContext.getUsedPromptMap());
            // 段落信息增量审校（第七期）：挤掉"只重复已知状态 / 原地循环情绪"的段落，
            // 产出**局部改写补丁**（不是整章重写）。默认关闭；套用失败或改写使文风变差则保留原稿
            String condensed = paragraphDensityAuditService.auditAndRewrite(
                    requestParameter.getStoryVO() == null ? null : requestParameter.getStoryVO().getModule(),
                    item, chapterContent.getContent(), globalNo);
            if (StringUtils.isNotBlank(condensed)) {
                chapterContent.setContent(condensed);
            }
            chapterContents.add(chapterContent);

            // ===== 审校 → 修订（若启用）→ 复审闭环（四期）；未修复的 BLOCKING 问题记为质量债，随下一章记忆回灌 =====
            GateResult gateResult = qualityGate.auditAndReviseIfEnabled(
                    requestParameter, item, chapterContent, summaries, styleStat, globalNo, storyDir);
            // 候选选优（六期）：低置信通过（MINOR 残留/修订过）时第二模型族重写候选，
            // 机械排序 + 异模型评审二选一；挑战者复审不过自动回退原稿，未启用时原样返回
            gateResult = chapterCandidateService.challenge(requestParameter, item, ctx, userPrompt,
                    chapterContent, gateResult, summaries, styleStat, globalNo, storyDir);
            List<ChapterIssueEntity> unresolvedIssues = gateResult.unresolvedBlocking();
            // 验证式自动核销：以本章审校结论验证旧债，须在记入本章新债之前
            if (qualityGate.auditEnabled()) {
                qualityDebtService.settle(qualityDebts, unresolvedIssues);
            }
            // 记债：未修复 BLOCKING + 降档的机械文风 MINOR。
            // 后者不参与修订与候选触发（见 GateResult 分账说明），但必须落债——
            // 否则「降档」等于把问题直接丢掉，观测层与规划层回灌都拿不到信号。
            //
            // 例外：修订验证"没跑成"时，unresolvedBlocking 的语义是【未验证】而非
            // 【确认未修复】——把它记成债等于把基础设施抖动写成内容缺陷，再回灌给写手当
            // "你上一章犯的错"。故这类不落债，只留 WARN + 落盘标记供体检单独立账（与 RECALL_DEGRADED 同则）
            List<ChapterIssueEntity> debtIssues = new java.util.ArrayList<>();
            if (gateResult.auditVerifyDegraded()) {
                log.warn("第 {} 章修订验证未跑成（AUDIT_VERIFY_DEGRADED）：{} 条 BLOCKING 标记为【未验证】，"
                                + "不记入质量债——避免把基础设施故障写成内容缺陷回灌下一章",
                        globalNo, unresolvedIssues.size());
            } else {
                debtIssues.addAll(unresolvedIssues);
            }
            debtIssues.addAll(gateResult.mechanicalMinor());
            if (!debtIssues.isEmpty()) {
                qualityDebts.add(QualityDebtEntity.builder()
                        .chapterNo(globalNo)
                        .issues(debtIssues)
                        .resolved(false)
                        .build());
            }
            // 上章审校反馈（P2）：语义 MINOR（人设/审美等）本章起产生行动通道——压缩要点，
            // 下一章 prompt 的「审校反馈」块消费。此前清单被丢弃：审校每章报 7-9 条却无人行动
            reviewFeedback = renderReviewFeedback(gateResult.semanticMinors());
            // 文风指纹库：仅首轮全票通过的章节提取高信息密度句入库（滚动上限 50）
            if (gateResult.grade() == GateResult.PassGrade.CLEAN_PASS) {
                List<String> additions = styleStatService.extractFingerprints(chapterContent.getContent());
                if (additions != null && !additions.isEmpty()) {
                    styleStatService.mergeFingerprints(styleFingerprints, additions);
                }
            }

            // 机械层风格统计滚动合并（使用最终采纳稿）。
            // 题材随调用传入：重复意象清单按题材分组取用（{@code repetition-watchlist.txt}），
            // 题材取自故事设定（与摘要落盘的 storyGenre 同一来源，口径一致）
            styleStatService.merge(styleStat, chapterContent.getContent(),
                    GenreTypeVO.match(storyContext == null ? null : storyContext.getTheme(),
                            storyContext == null ? null : storyContext.getStyle()).getCode());

            // 摘要立即生成，供下一章记忆前缀使用。摘要是质量门：三级降级+抢救均失败抛异常终止本批，
            // 抢救成功返回 partial 标记的残缺记忆（仅剧情摘要，账本状态缺失）
            String ledgerPrompt = chapterMemoryService.renderLedgerPrompt(summaries);
            // 摘要携带待回收伏笔账（新→旧封顶），回收登记才能逐字沿用原文、账本核销匹配才可靠。
            // 带 ID 前缀版（2026-10-13）：模型可回抄 [F章号-序号] 精确销账，不再依赖措辞吻合
            List<String> pendingForeshadowing = chapterMemoryService.buildForeshadowContextListWithIds(
                    summaries, ChapterMemoryService.FORESHADOW_CONTEXT_LIMIT);
            ChapterSummaryEntity summary = chapterSummaryService.summarize(
                    requestParameter.getStoryVO(), item, chapterContent.getContent(), globalNo,
                    ledgerPrompt, pendingForeshadowing, summaries);
            // 账本失配留痕（2026-10-13）：本章回收清单与待收账本一条都没匹配上时，
            // 把失配事实结构化落盘——这是"账本按文本核销"这条设计的漏损计量点，
            // 只写 WARN 日志无法聚合（伏笔结构化 ID 改造的效果验证依赖这个数字）。fail-soft
            ChapterSummaryService.LedgerMismatch ledgerMismatch = chapterSummaryService.consumeLedgerMismatch();
            if (ledgerMismatch != null) {
                mechanicalEventRecorder.recordLedgerMismatch(storyDir, globalNo,
                        ledgerMismatch.resolvedCount(), ledgerMismatch.pendingCount(),
                        ledgerMismatch.resolved());
            }
            // 种子补 new 留痕（2026-10-14）：模型偶尔只填 foreshadowSeeds 漏填 foreshadowingNew，
            // 机械层已无条件补齐（ChapterSummaryService.syncSeedsIntoNew）——补齐事实落结构化流水，
            // 用于回答"漏填率多高、补进来的条目后来有没有被回收"。fail-soft 同上一块
            ChapterSummaryService.SeedNewSync seedNewSync = chapterSummaryService.consumeSeedNewSync();
            if (seedNewSync != null) {
                mechanicalEventRecorder.recordSeedNewSync(storyDir, globalNo,
                        seedNewSync.addedCount(), seedNewSync.added());
            }
            // 账本挂起裁决（L1 兜底）：机械分档仍挂起的「部分短语命中」条目，回读本章正文由 LLM 判定，
            // 补出可逐字核对的引文——引文须再经 EvidenceMatch 才入账（拟稿→机械校验，裁决层不是后门）。
            // 必须在 summaries.add 之前完成：本章记忆前缀与后续章节账本才拿得到救回的事实。
            // fail-soft：未开启/调用异常/解析失败一律保持挂起层原样，不反噬生成
            ledgerAdjudicateService.adjudicate(requestParameter.getStoryVO(), summary,
                    chapterContent.getContent());
            // 密度信号机械入账（非模型输出）：规划层据此判断"计划供给是否太稀"，短章不回炉、下章加料
            summary.setValidChars(cn.novel.yonren.domain.novel.service.armory.quality.ChapterLengthPolicy
                    .effectiveCharacterCount(chapterContent.getContent()));
            // 检索降级标记落进摘要：只在日志里 WARN 的话，体检读不到"本批几章是裸跑的"；
            // 落盘后健康判停那条读盘重算的路径也能看到（与 generationMode 同一理由）
            summary.setRecallDegraded(recall.degraded());
            // 修订验证未跑成的标记同样落盘：体检据此把【未验证】与【真债】分开计数，
            // 否则"验证没跑成"除了翻日志无从发现
            summary.setAuditVerifyDegraded(gateResult.auditVerifyDegraded());
            summary.setKeyEventCount(item.getKeyEvents() == null ? 0 : item.getKeyEvents().size());
            // 对白行占比机械入账（非模型输出）："零角色互动"的可观测代理——
            // 新书实测坍缩至旧书的 1/4（11.1% vs 45.6%）而此前不可见；不落摘要则体检跨批无从统计
            summary.setDialogueRatio(cn.novel.yonren.domain.novel.service.armory.quality.DialogueRatioPolicy
                    .ratioOf(chapterContent.getContent()));
            // 对白轮次（机械入账）：与行级占比互补——占比合格但轮次稀疏是恋爱/智斗题材的典型失手
            summary.setDialogueUtterances(cn.novel.yonren.domain.novel.service.armory.quality.DialogueRatioPolicy
                    .utteranceCount(chapterContent.getContent()));
            // 题材机械入账：体检据此切换"对话驱动题材"的阈值（批末日志与 /health 共用同一份数据）
            summary.setStoryGenre(cn.novel.yonren.types.enums.GenreTypeVO.match(
                    storyContext == null ? null : storyContext.getTheme(),
                    storyContext == null ? null : storyContext.getStyle()).getCode());
            // 章型机械入账（非模型输出）：规划层密度反馈据此豁免过渡章——
            // 不落摘要的话，反馈只能看到"字少事件少"，把有意安排的过渡章误判成供给不足
            summary.setChapterType(item.getChapterType() == null ? null : item.getChapterType().getCode());
            // 机制原理描述机械入账（非模型输出）：跨章重复描述检查据此累计。
            // 计数必须落进摘要才能跨批——批内 chapterContents 每批从空开始，靠它累计会让
            // "全篇至多 2 次"的上限每批重置（原实现的缺陷）
            summary.setMechanismDescribed(consistencyIndexService.describesMechanism(
                    chapterContent.getContent(), requestParameter.getStoryVO()));
            // 把本章的两个运行时事实落进摘要：体检要统计"生成模式分布"与
            // "认知边界注入率"，而健康判停是**读盘重算**的，只放内存里那条路看不到。
            summary.setGenerationMode(mode == null ? null : mode.name());
            summary.setKnowledgeBoundaryInjected(
                    contract != null && StringUtils.isNotBlank(contract.knowledgeBoundary()));
            // 悬念档位也落盘：体检要统计"主线是否原地打转"（读盘重算那条路看不到内存里的计划）
            summary.setSuspenseBeat(item.getSuspenseBeat());
            summaries.add(summary);

            // 伏笔排期打标P2b）：本章新埋的种子与排期表配对，回填 scheduledPayoffChapter，
            // 并推进 PLANNED→PLANTED→PAID / 到期未埋→MISSED。
            // 放在"摘要进 summaries"之后、"落盘"之前——与 stripVoidedForeshadows 同款就地修改契约。
            // 无排期表时整体跳过（老故事/未启用），行为与引入前完全一致。
            ForeshadowScheduleService.StampResult stamp =
                    foreshadowScheduleService.stamp(summary, dynamicContext.getForeshadowSchedules());
            if (stamp.changed()) {
                log.info("第{}章伏笔排期打标：新埋 {} 条 / 兑现 {} 条 / 到期未埋 {} 条（排期共 {} 条）",
                        globalNo, stamp.planted(), stamp.paid(), stamp.missed(), stamp.total());
            }

            ConsistencyIndexEntity consistencyIndex = consistencyIndexService.rebuild(summaries, requestParameter.getStoryVO());
            dynamicContext.setConsistencyIndex(consistencyIndex);
            // 金手指机制门禁已上移到 QualityGate（真 BLOCKING 进修订闭环，见其调用处说明），
            // 此处不再单独记债——否则同一问题会既被修订又被记债，重复计数
            pendingConflicts = summary.getContinuityConflicts();

            // 阶段收束钩子（阶段末章）：机械节奏报告写回蓝图 + 退出条件外部审计 + 卷末清账
            //（清账结果：VOID 出账、RECOVER 随结算文件注入下一段规划 prompt）
            StageBlueprintEntity stageBlueprint = currentStageBlueprint(dynamicContext.getStageBlueprints(), globalNo);
            if (stageBlueprint != null && globalNo == stageBlueprint.getEndChapter()) {
                stageBlueprint.setStageReport(stageReportService.buildStageReport(
                        stageBlueprint, summaries, chapterContents));
                reviewStageExitConditions(module, stageBlueprint, summaries, dynamicContext);
                settleStageBreakerForeshadows(requestParameter, module, stageBlueprint, summaries,
                        foreshadowSettlements, globalNo, storyDir, dynamicContext);
                // 上述三步都会**就地回写蓝图内容**（阶段报告/退出核验/清账标注）而列表大小不变——
                // 必须显式置脏，否则降频逻辑会把这批回写判成"无变化"而漏盘
                blueprintDirty = true;
            }

            // 逐章同步上下文：惰性分段规划依赖最新记忆（下一段规划在章间触发）
            dynamicContext.setChapterSummaries(summaries);
            dynamicContext.setPendingConflicts(pendingConflicts);
            dynamicContext.setPrevChapterTail(chapterMemoryService.tailByParagraph(
                    chapterContent.getContent(), ChapterMemoryService.DEFAULT_PREV_TAIL_LENGTH));

            // 逐章检查点：正文/记忆/质量债/风格统计/阶段蓝图即写即存。
            // 分级容错：**正文与摘要**是核心交付物——写失败必须立即终止本批，否则磁盘上正文编号
            // 出现缺口，而 resume 的锁步校验对缺口硬失败（validateResumeLockStep），用户从此无法续写，
            // 只能手工修目录；"树尾持久化会重写"的兜底只在活到树尾时成立。
            // 其余为增强件（风格/蓝图/排期/向量索引），保持 fail-soft：写失败不影响正文与记忆的完整性。
            if (storyDir != null) {
                try {
                    storyRepository.writeChapters(storyDir, List.of(chapterContent));
                    storyRepository.writeChapterSummaries(storyDir, summaries);
                } catch (Exception e) {
                    log.error("第 {} 章核心交付物（正文/摘要）落盘失败，终止本批——"
                            + "继续生成会在正文编号上留缺口，resume 锁步校验将拒绝续写", globalNo, e);
                    throw new AppException(ResponseCode.UN_ERROR.getCode(),
                            "第 " + globalNo + " 章正文/摘要落盘失败，本批终止（已完成章节均已落盘，可 resume 续写）："
                                    + e.getMessage());
                }
                try {
                    storyRepository.writeQualityDebts(storyDir, qualityDebts);
                    storyRepository.writeStyleStat(storyDir, styleStat);
                    storyRepository.writeStyleFingerprints(storyDir, styleFingerprints);
                    storyRepository.writeConsistencyIndex(storyDir, consistencyIndex);
                    // 蓝图/卷**只在变化时写**（2026-10-13）：两者一个批次内至多变一次
                    //（阶段边界/卷边界），而此前每章全量重写——写放大随书线性增长。
                    // 蓝图有两个变化源：链式 append（大小变）+ 阶段末章的就地回写（大小不变，
                    // 故须靠 blueprintDirty 显式置脏）；卷在章循环内只读，比大小即可。
                    // 树尾 PersistChapterPlanNode 仍会无条件全量兜底，故不漏盘。
                    int blueprintCount = sizeOf(dynamicContext.getStageBlueprints());
                    int volumeCount = sizeOf(dynamicContext.getVolumes());
                    if (blueprintDirty || blueprintCount != lastBlueprintCount) {
                        storyRepository.writeStageBlueprints(storyDir, dynamicContext.getStageBlueprints());
                        lastBlueprintCount = blueprintCount;
                        blueprintDirty = false;
                    }
                    if (volumeCount != lastVolumeCount) {
                        storyRepository.writeVolumes(storyDir, dynamicContext.getVolumes());
                        lastVolumeCount = volumeCount;
                    }
                    // 伏笔排期表**每章都写**：stamp() 逐章推进 PLANNED→PLANTED→PAID/MISSED，
                    // 状态随时在变，不能降频（漏写会让状态重启即丢，实测两批 MISSED 从未落盘）
                    storyRepository.writeForeshadowSchedule(storyDir, dynamicContext.getForeshadowSchedules());
                    // 二期/三期/五期：本章摘要 + 最新账本 + 设定幂等写入故事记忆集合；worldId 合法时 bible 点另写世界集合
                    storyMemoryService.indexAfterChapter(module, storyDir, summaries, storyContext.getWorldId());
                } catch (Exception e) {
                    log.error("第 {} 章增强件落盘失败，已跳过（树尾持久化会重写）", globalNo, e);
                }
            }

            // 自动检查点（版本快照）：段/批边界或取消/熔断点打点，供事后回滚。fail-soft，失败仅告警不影响生成
            if (storyDir != null && (globalNo == plannedSegmentEnd
                    || idx == chapterPlan.getChapters().size() - 1
                    || (job != null && (job.isCancelRequested() || job.isBudgetExceeded()))
                    || globalNo == offset + totalChapters)) {
                try {
                    storyRepository.snapshotCheckpoint(
                            storyDir, cn.novel.yonren.types.enums.CheckpointType.AUTO, null);
                } catch (Exception e) {
                    log.warn("自动检查点创建失败，已跳过（章节仍逐章落盘）：{}", e.getMessage());
                }
            }

            if (job != null) {
                job.recordChapterDuration(globalNo, System.currentTimeMillis() - chapterStart);
                // 观测性状态落盘：失败仅告警，不反噬生成流程
                persistJobStatus(dynamicContext);
            }

            // 全书质量评分（每 N 章一次，触发规则在服务内）：放在 handleFinale 之前——
            // 终局审查可能 break 出循环，若放在其后，收官那一章的评分会静默丢失。
            // 服务内部全程 fail-soft：评分失败不影响本批任何状态。
            qualityReviewService.reviewWindow(storyDir, requestParameter.getStoryVO(), summaries, globalNo);

            log.info("第 {} 章正文生成完成，chapterType: {}, 累计记忆 {} 章",
                    globalNo,
                    item.getChapterType() == null ? "normal" : item.getChapterType().getCode(),
                    summaries.size());
            idx++;

            // 完结门禁 → 终局审查三态：通过=正常收官；不通过且有头寸=拒绝完结、写回末卷返工；
            // 不通过且预算/头寸耗尽=强制收官（overallPass 仍 false，不伪称通过）。
            FinaleVerdict verdict = handleFinale(requestParameter, module, item, globalNo, dynamicContext, summaries);
            if (verdict == FinaleVerdict.COMPLETE || verdict == FinaleVerdict.FORCE_CLOSE) {
                if (job != null) {
                    job.updateProgress(verdict == FinaleVerdict.COMPLETE ? "STORY_COMPLETED" : "STORY_FORCE_CLOSED",
                            globalNo, globalNo,
                            storyDir == null ? null : storyDir.getFileName().toString());
                    persistJobStatus(dynamicContext);
                }
                log.info(verdict == FinaleVerdict.COMPLETE
                        ? "故事在第 {} 章通过终局审查，宣告完结（提前结束本批剩余 {} 章）"
                        : "故事在第 {} 章终局审查未通过且返工预算/头寸耗尽，强制收官（保留缺口清单）",
                        globalNo, Math.max(0, chapterPlan.getChapters().size() - idx));
                break;
            }
        }
        dynamicContext.setChapterContents(chapterContents);
        dynamicContext.setChapterSummaries(summaries);
        dynamicContext.setConsistencyIndex(consistencyIndexService.rebuild(summaries, requestParameter.getStoryVO()));
        dynamicContext.setQualityDebts(qualityDebts);
        dynamicContext.setForeshadowSettlements(foreshadowSettlements);
        dynamicContext.setStyleStat(styleStat);
        dynamicContext.setStyleFingerprints(styleFingerprints);
        // 批末体检（纯机械、零 LLM 成本）：把账本完整度 / 地点密度 / 过渡章占比 / 债积压 / 出口条件达成率
        // 横着看一遍，让"这批写下来系统是变健康还是变糟了"变成一条可读结论。
        // 只报不动作——停机/降级属执行器层（阶段六），观测层擅自动作会让"为什么停"不可归因
        BatchHealthReport health = batchHealthService.assess(
                summaries, qualityDebts, dynamicContext.getStageBlueprints(),
                candidateSampleService == null ? null : candidateSampleService.readStats(storyDir),
                dynamicContext.getForeshadowSettlements(),
                requestParameter.getStoryContextEntity() == null
                        ? null : requestParameter.getStoryContextEntity().getChapterGoal(),
                // 质量评分读数（唯一的 LLM 主观打分通道）：此前只落盘无人读，读完接进体检。
                // 读取失败不该影响体检——读不到就当没有这行
                readQualityTrendSafely(storyDir));
        // 体检结果挂到作业对象：前端轮询作业状态即可看到本批 24 项指标与建议（依旧只报不动作）
        if (job != null) {
            job.setLastHealthReport(health);
        }
        if (health.needsAttention()) {
            log.warn("第 {}-{} 章批次结束，{}", offset + 1, offset + chapterContents.size(), health.render());
        } else {
            log.info("第 {}-{} 章批次结束，{}", offset + 1, offset + chapterContents.size(), health.render());
        }
    }

    /**
     * 文风指纹库预载：续写批次从故事目录读历史指纹（批内滚动追加），首发为空表；
     * 读取失败退化为空表（指纹库是正向激励，缺失不阻塞生成）
     */
    private List<String> preloadStyleFingerprints(DefaultArmoryFactory.DynamicContext dynamicContext, Path storyDir) {
        if (dynamicContext.getStyleFingerprints() != null) {
            return dynamicContext.getStyleFingerprints();
        }
        if (storyDir == null) {
            return new ArrayList<>();
        }
        try {
            List<String> loaded = storyRepository.readStyleFingerprints(storyDir);
            return loaded != null ? new ArrayList<>(loaded) : new ArrayList<>();
        } catch (Exception e) {
            log.warn("文风指纹库预载失败，本轮从空表开始：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 仅在明确进入收官卷且所有可核验条件完成时返回 true。
     * remainingFinaleBeats 为空代表终局节点已收清，completedFinaleBeats 非空用于防止旧数据/模型漏字段误停。
     */
    private boolean isStoryComplete(List<StageBlueprintEntity> blueprints, int chapterNo,
                                    ChapterPlanItemEntity item) {
        if (item == null || item.getChapterType() == null
                || item.getChapterType() != cn.novel.yonren.types.enums.ChapterTypeVO.FINALE
                || blueprints == null || blueprints.isEmpty()) {
            return false;
        }
        StageBlueprintEntity blueprint = currentStageBlueprint(blueprints, chapterNo);
        if (blueprint == null || !Boolean.TRUE.equals(blueprint.getFinalVolumeDeclared())
                || !("RESOLUTION".equalsIgnoreCase(blueprint.getStoryPhase())
                || "EPILOGUE".equalsIgnoreCase(blueprint.getStoryPhase()))) {
            return false;
        }
        if (blueprint.getCompletedFinaleBeats() == null || blueprint.getCompletedFinaleBeats().isEmpty()
                || (blueprint.getRemainingFinaleBeats() != null && !blueprint.getRemainingFinaleBeats().isEmpty())) {
            return false;
        }
        List<StageBlueprintEntity.ExitConditionResult> results = blueprint.getExitResults();
        List<String> conditions = blueprint.getExitConditions();
        if (conditions == null || conditions.isEmpty() || results == null || results.size() < conditions.size()) {
            return false;
        }
        return results.stream().allMatch(result -> result != null && Boolean.TRUE.equals(result.getMet()));
    }

    /**
     * 终局门禁三态：收官阶段每章判定是否审计，审计通过/强制收官返回终结态，否则由调用方续写返工章。
     * 决定性规则：
     *  - 非收官门禁（未满足完结契约且非返工窗口末章）→ CONTINUE；
     *  - 审计整体失败（null，基础设施故障）→ 按 legacy 判定通过 COMPLETE（fail-soft，不锁死合法完结）；
     *  - 四维全部通过 → COMPLETE；
     *  - 不通过且返工预算未耗尽且顶帽尚有余量 → 合成返工窗口、追加返工章，CONTINUE（拒绝宣告完结）；
     *  - 否则 → 强制收官 FORCE_CLOSE（overallPass 保留 false + 缺口清单，不伪称通过）。
     */
    private FinaleVerdict handleFinale(ArmoryCommandEntity requestParameter,
                                       cn.novel.yonren.domain.novel.model.valobj.StoryVO.Module module,
                                       ChapterPlanItemEntity item, int globalNo,
                                       DefaultArmoryFactory.DynamicContext dynamicContext,
                                       List<ChapterSummaryEntity> summaries) {
        List<StageBlueprintEntity> blueprints = dynamicContext.getStageBlueprints();
        if (blueprints == null || blueprints.isEmpty()) {
            return FinaleVerdict.CONTINUE;
        }
        StageBlueprintEntity active = currentStageBlueprint(blueprints, globalNo);
        if (active == null) {
            return FinaleVerdict.CONTINUE;
        }
        boolean normalComplete = isStoryComplete(blueprints, globalNo, item);
        FinaleAuditEntity previous = active.getFinaleAudit();
        boolean reworkWindowEnd = previous != null && previous.getReworkCount() > 0
                && globalNo == safeEnd(active);
        if (!normalComplete && !reworkWindowEnd) {
            return FinaleVerdict.CONTINUE;
        }

        FinaleAuditEntity audit = runFinaleAudit(requestParameter, module, active, summaries, dynamicContext);
        active.setFinaleAudit(audit);

        if (audit == null) {
            log.warn("终局审查失败，按 legacy 完结判定放行（fail-soft），第 {} 章", globalNo);
            return FinaleVerdict.COMPLETE;
        }
        if (audit.isOverallPass()) {
            log.info("终局审查通过，宣告完结局：第 {} 章收束（四维全通过）", globalNo);
            return FinaleVerdict.COMPLETE;
        }

        // 拒绝完结 → 返工：预算未耗尽、且有顶帽头寸（sticky cap 双护栏）才可续；否则强制收官
        Integer hardTotal = dynamicContext.getMaxChapterCount();
        boolean hasHeadroom = hardTotal == null || globalNo + REPENT_BATCH <= hardTotal;
        if (audit.getReworkCount() < MAX_FINALE_REWORK && hasHeadroom) {
            audit.setReworkCount(audit.getReworkCount() + 1);
            StageBlueprintEntity rework = buildReworkBlueprint(active, audit, globalNo, hardTotal);
            if (rework != null) {
                blueprints.add(rework);
                appendReworkChapters(dynamicContext, rework, audit);
                int reworkEnd = rework.getEndChapter() == null ? globalNo : rework.getEndChapter();
                Integer plannedEnd = dynamicContext.getPlannedSegmentEnd();
                if (plannedEnd == null || plannedEnd < reworkEnd) {
                    dynamicContext.setPlannedSegmentEnd(reworkEnd);
                }
                log.info("终局审查未通过，写回末卷返工（第 {} 轮，拟章 {}-{}），缺口 {} 项",
                        audit.getReworkCount(), rework.getStartChapter(), reworkEnd,
                        audit.getReworkTasks() == null ? 0 : audit.getReworkTasks().size());
                return FinaleVerdict.CONTINUE;
            }
        }

        audit.setForcedClose(true);
        if (StringUtils.isBlank(audit.getNote())) {
            audit.setNote("终局审查未通过且返工预算/章数头寸耗尽，强制收官");
        }
        log.warn("终局审查未通过且无法返工，强制收官：overallPass=false，缺口 {} 项",
                audit.getReworkTasks() == null ? 0 : audit.getReworkTasks().size());
        return FinaleVerdict.FORCE_CLOSE;
    }

    /** 运行终局审查：开放伏笔来自收官区间未回收清单；prompt 回写 usedPromptMap 供复盘 */
    private FinaleAuditEntity runFinaleAudit(ArmoryCommandEntity requestParameter,
                                             cn.novel.yonren.domain.novel.model.valobj.StoryVO.Module module,
                                             StageBlueprintEntity active,
                                             List<ChapterSummaryEntity> summaries,
                                             DefaultArmoryFactory.DynamicContext dynamicContext) {
        List<ForeshadowPriorityService.ScoredForeshadow> openForeshadows =
                chapterMemoryService.buildBreakerForeshadows(summaries, safeEnd(active));
        Map<String, String> reviewPrompts = new HashMap<>();
        FinaleAuditEntity audit = finaleReviewService.review(module, active, openForeshadows, summaries, reviewPrompts);
        if (!reviewPrompts.isEmpty()) {
            Map<String, String> used = dynamicContext.getUsedPromptMap() != null
                    ? new HashMap<>(dynamicContext.getUsedPromptMap()) : new HashMap<>();
            reviewPrompts.forEach((key, value) -> used.put("finale-audit:" + key, value));
            dynamicContext.setUsedPromptMap(used);
        }
        return audit;
    }

    /** 合成收官返工蓝图：在末阶段之上再开一轮 EPILOGUE 窗口，缺口注入 remainingFinaleBeats/exitConditions */
    private StageBlueprintEntity buildReworkBlueprint(StageBlueprintEntity active, FinaleAuditEntity audit,
                                                      int globalNo, Integer hardTotal) {
        if (active.getEndChapter() == null || globalNo > active.getEndChapter()) {
            return null;
        }
        int start = active.getEndChapter() + 1;
        int end = start + REPENT_BATCH - 1;
        if (hardTotal != null) {
            end = Math.min(end, hardTotal);
        }
        if (end < start) {
            return null;
        }
        List<String> conditions = reworkConditions(audit);
        return StageBlueprintEntity.builder()
                .stageNo((active.getStageNo() == null ? 0 : active.getStageNo()) + 1)
                .startChapter(start)
                .endChapter(end)
                .storyPhase(active.getStoryPhase())
                .finalVolumeDeclared(true)
                .stageGoal("收官返工：处置终局审查缺口")
                .remainingFinaleBeats(audit.getReworkTasks())
                .completedFinaleBeats(new ArrayList<>())
                .exitConditions(conditions)
                .entryConstraints(audit.getReworkTasks())
                .carriedTasks(reworkCarriedTasks(active, audit))
                .build();
    }

    /** 返工蓝图退出条件：把未通过的四维映射为可核验的收官条件，供阶段出口审查与再审计 */
    private static List<String> reworkConditions(FinaleAuditEntity audit) {
        List<String> conditions = new ArrayList<>();
        if (audit.getDimensions() == null) {
            return conditions;
        }
        for (FinaleAuditEntity.DimensionResult r : audit.getDimensions()) {
            if (r != null && !Boolean.TRUE.equals(r.getMet())) {
                conditions.add("完成" + label(r.getDimension()) + "缺口：" + StringUtils.defaultString(r.getNote(), "未通过"));
            }
        }
        if (conditions.isEmpty() && audit.getReworkTasks() != null) {
            conditions.addAll(audit.getReworkTasks());
        }
        return conditions;
    }

    /** 返工蓝图结转任务：上一版任务的处置（缺口结转重挂） */
    private static List<StageBlueprintEntity.CarriedTaskEntity> reworkCarriedTasks(
            StageBlueprintEntity active, FinaleAuditEntity audit) {
        List<StageBlueprintEntity.CarriedTaskEntity> tasks = new ArrayList<>();
        if (active.getCarriedTasks() != null) {
            for (StageBlueprintEntity.CarriedTaskEntity t : active.getCarriedTasks()) {
                if (t != null && StringUtils.isNotBlank(t.getContent())) {
                    tasks.add(new StageBlueprintEntity.CarriedTaskEntity(t.getContent(), "进行中", "终局审查返工"));
                }
            }
        }
        if (audit.getReworkTasks() != null) {
            for (String task : audit.getReworkTasks()) {
                if (StringUtils.isNotBlank(task)) {
                    tasks.add(new StageBlueprintEntity.CarriedTaskEntity(task, "进行中", "终局审查返工缺口"));
                }
            }
        }
        return tasks;
    }

    /** 追加返工章节到整批计划（紧接已有计划末尾续号，避免与已规划章节重号） */
    private static void appendReworkChapters(DefaultArmoryFactory.DynamicContext dynamicContext,
                                             StageBlueprintEntity rework, FinaleAuditEntity audit) {
        ChapterPlanAggregate aggregate = dynamicContext.getChapterPlanAggregate();
        if (aggregate.getChapters() == null) {
            aggregate.setChapters(new ArrayList<>());
        }
        int maxNo = aggregate.getChapters().stream()
                .mapToInt(c -> c.getChapterNo() == null ? 0 : c.getChapterNo())
                .max().orElse(rework.getStartChapter() - 1);
        int from = Math.max(rework.getStartChapter(), maxNo + 1);
        int end = rework.getEndChapter() == null ? from : rework.getEndChapter();
        String goal = audit.getReworkTasks() == null || audit.getReworkTasks().isEmpty()
                ? "收官返工：处置终局审查缺口" : "收官返工：" + String.join("、", audit.getReworkTasks());
        for (int no = from; no <= end; no++) {
            aggregate.getChapters().add(cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity.builder()
                    .chapterNo(no)
                    .chapterType(cn.novel.yonren.types.enums.ChapterTypeVO.FINALE)
                    .goal(goal)
                    .build());
        }
    }

    private static String label(String dimension) {
        if (FinaleReviewService.DIM_COMMITMENTS.equals(dimension)) return "终局承诺";
        if (FinaleReviewService.DIM_FORESHADOW.equals(dimension)) return "未回收伏笔";
        if (FinaleReviewService.DIM_CHARACTER_FATE.equals(dimension)) return "主要角色命运";
        if (FinaleReviewService.DIM_WORLD_STATE.equals(dimension)) return "灾后世界状态";
        return "收官";
    }

    private static int safeEnd(StageBlueprintEntity blueprint) {
        return blueprint.getEndChapter() == null ? Integer.MAX_VALUE : blueprint.getEndChapter();
    }

    /** 列表大小（null 安全）：蓝图/卷落盘降频的变化判据 */
    private static int sizeOf(List<?> list) {
        return list == null ? 0 : list.size();
    }

    /** 故事记忆检索的下界：近 N 章窗口内章节已在摘要节直给，检索仅唤醒更早章节（账本/设定点不受限） */
    private static Integer recallMinChapterNo(List<ChapterSummaryEntity> summaries) {        if (summaries == null || summaries.isEmpty()) {
            return null;
        }
        int latest = summaries.get(summaries.size() - 1).getChapterNo();
        return latest - ChapterMemoryService.RECENT_SUMMARY_COUNT + 1;
    }

    /** 当前章号命中的阶段蓝图（覆盖区间匹配，链上无覆盖时回退最新一版），供记忆前缀做方向锚定 */
    private static StageBlueprintEntity currentStageBlueprint(List<StageBlueprintEntity> blueprints, int chapterNo) {
        if (blueprints == null || blueprints.isEmpty()) {
            return null;
        }
        return blueprints.stream()
                .filter(b -> b.getStartChapter() != null && b.getEndChapter() != null
                        && chapterNo >= b.getStartChapter() && chapterNo <= b.getEndChapter())
                .findFirst()
                .orElse(blueprints.get(blueprints.size() - 1));
    }

    /**
     * 惰性分段规划：规划下一未规划段——prompt 用当前最新记忆组装（含进入护栏与上一阶段
     * 未达成退出条件），调用规划执行器完成三级降级 + 段级结构校验 + 编号校正 + 主线推进闸门
     * （与链式路径同一实现），段计划追加进整批计划。
     * 全败抛异常（计划是核心交付物；已完成章节已通过逐章检查点落盘可续写）
     *
     * @return 该段覆盖的末章号（plannedSegmentEnd 新值）
     */
    private int planNextSegment(ArmoryCommandEntity requestParameter,
                                DefaultArmoryFactory.DynamicContext dynamicContext,
                                List<RollingOutlineService.StageSegment> segments, int plannedSegmentEnd,
                                int batchEnd, List<ChapterContentEntity> chapterContents,
                                List<ChapterSummaryEntity> summaries) throws Exception {
        // 规划期协作取消/熔断检查（段边界）：与 CallChapterPlanLlmNode 入口检查同款，
        // 让取消在惰性规划的每次触发点都生效
        GenerationJob planJob = dynamicContext.getJob();
        if (planJob != null && (planJob.isCancelRequested() || planJob.isBudgetExceeded())) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(),
                    "作业在惰性规划段边界已请求取消/触发熔断，停止规划（已完成章节不受影响，可 resume 续写）");
        }
        RollingOutlineService.StageSegment segment = segments.stream()
                .filter(s -> s.startChapter() > plannedSegmentEnd)
                .findFirst()
                .orElseThrow(() -> new AppException(ResponseCode.UN_ERROR.getCode(),
                        "惰性分段规划失败：第 " + (plannedSegmentEnd + 1) + " 章起无对应阶段分段"));
        cn.novel.yonren.domain.novel.model.valobj.StoryVO.Module module =
                requestParameter.getStoryVO() == null ? null : requestParameter.getStoryVO().getModule();
        String prompt = planPromptService.buildPlanPrompt(dynamicContext,
                segment.startChapter(), segment.endChapter(), segment.blueprint(),
                segment.endChapter() >= batchEnd, module,
                requestParameter.getStoryContextEntity().getWorldId());
        String unmetBlock = renderUnmetExitConditions(dynamicContext.getStageBlueprints(), segment.startChapter());
        if (unmetBlock != null) {
            prompt = prompt + unmetBlock;
        }

        PromptContext planCtx = PromptContext.builder()
                .theme(requestParameter.getStoryContextEntity().getTheme())
                .style(requestParameter.getStoryContextEntity().getStyle())
                .totalChapters(requestParameter.getStoryContextEntity().getChapterCount())
                .build();
        // 对话承载闸门的人名来源：按当前最新记忆构建角色名册（空摘要安全降级）
        List<String> rosterNames = chapterMemoryService.characterRoster(summaries);
        ChapterPlanSegmentPlanner.PlannedSegment planned = planSegmentPlanner.planSegment(
                requestParameter.getStoryVO(), planCtx, prompt,
                segment.startChapter(), segment.endChapter(), segment.blueprint(), rosterNames,
                dynamicContext.getUsedPromptMap(),
                summaries, dynamicContext.getForeshadowSettlements(), planJob);

        ChapterPlanAggregate aggregate = dynamicContext.getChapterPlanAggregate();
        if (aggregate.getChapters() == null) {
            aggregate.setChapters(new ArrayList<>());
        }
        aggregate.getChapters().addAll(planned.plan().getChapters());
        dynamicContext.setPlannedSegmentEnd(segment.endChapter());
        // 遗留违规随段规划落进上下文，逐章注入正文 prompt（见 buildChapterPrompt 的调用方）
        dynamicContext.setPlanResidualViolations(planned.residualGateIssues());
        log.info("惰性分段规划：第 {}-{} 章段已规划（{} 章），规划记忆更新至第 {} 章",
                segment.startChapter(), segment.endChapter(), planned.plan().getChapters().size(),
                summaries.isEmpty() ? "-" : summaries.get(summaries.size() - 1).getChapterNo());
        return segment.endChapter();
    }

    /**
     * 阶段退出条件外部审计：阶段末章完成后核验（此时该阶段全部章节摘要已就绪），
     * 结果写回蓝图 exitResults（随 rolling-outline.json 落盘），下一段惰性规划注入未达成清单。
     * 核验失败仅告警跳过（下一段规划回退模型自评），不阻塞生成
     */
    private void reviewStageExitConditions(cn.novel.yonren.domain.novel.model.valobj.StoryVO.Module module,
                                           StageBlueprintEntity stageBlueprint,
                                           List<ChapterSummaryEntity> summaries,
                                           DefaultArmoryFactory.DynamicContext dynamicContext) {
        if (stageBlueprint.getExitConditions() == null || stageBlueprint.getExitConditions().isEmpty()
                || stageBlueprint.getExitResults() != null) {
            return;
        }
        Map<String, String> reviewPrompts = new HashMap<>();
        List<StageBlueprintEntity.ExitConditionResult> results =
                stageExitReviewService.review(module, stageBlueprint, summaries, reviewPrompts);
        // 二阶段：对未达成的条件用**正文**复核。
        // 一阶段的核验文本是「摘要 + 三账本」，而摘要只记剧情主干、不记动作细节，
        // 会出现"正文写了、摘要没记、核验判未达成"的假阴性——用正文兜住，门槛不变（仍要求逐字原文）
        if (results != null) {
            // 正文来源：优先取内存里本批已生成的正文；**取不到时回读磁盘**。
            // ⚠️ 实测踩过：惰性分段规划下内存可能不保留（或只保留当前段），
            // 导致这一层静默跳过（连日志都没有）——所以必须有磁盘兜底 + 空集合告警。
            Map<Integer, String> chapterTextByNo = new java.util.LinkedHashMap<>();
            List<ChapterContentEntity> contents = dynamicContext.getChapterContents();
            if (contents != null) {
                for (ChapterContentEntity content : contents) {
                    if (content != null && content.getChapterNo() != null
                            && StringUtils.isNotBlank(content.getContent())) {
                        chapterTextByNo.put(content.getChapterNo(), content.getContent());
                    }
                }
            }
            Integer stageStart = stageBlueprint.getStartChapter();
            for (ChapterSummaryEntity summary : summaries) {
                if (summary == null || summary.getChapterNo() == null || stageStart == null
                        || summary.getChapterNo() < stageStart
                        || (stageBlueprint.getEndChapter() != null
                                && summary.getChapterNo() > stageBlueprint.getEndChapter())
                        || chapterTextByNo.containsKey(summary.getChapterNo())) {
                    continue;
                }
                try {
                    ChapterContentEntity fromDisk = storyRepository.readChapter(
                            dynamicContext.getStoryDir(), summary.getChapterNo());
                    if (fromDisk != null && StringUtils.isNotBlank(fromDisk.getContent())) {
                        chapterTextByNo.put(summary.getChapterNo(), fromDisk.getContent());
                    }
                } catch (Exception e) {
                    log.debug("第 {} 章正文回读失败，跳过：{}", summary.getChapterNo(), e.getMessage());
                }
            }
            if (chapterTextByNo.isEmpty()) {
                log.warn("阶段{}正文复核跳过：未取得任何正文（内存为空且磁盘回读也未命中），"
                        + "未达成条件将维持一阶段（摘要）结论", stageBlueprint.getStageNo());
            }
            results = stageExitReviewService.recheckAgainstChapterText(
                    module, stageBlueprint, results, chapterTextByNo, reviewPrompts);
        }
        if (results != null) {
            stageBlueprint.setExitResults(results);
            long metCount = results.stream().filter(r -> Boolean.TRUE.equals(r.getMet())).count();
            log.info("阶段退出条件核验完成：第{}阶段，条件达成 {}/{}（含正文复核改判）",
                    stageBlueprint.getStageNo(), metCount, results.size());
        } else {
            log.warn("阶段退出条件核验失败，跳过（下一段规划回退模型自评）");
        }
        if (!reviewPrompts.isEmpty()) {
            Map<String, String> used = dynamicContext.getUsedPromptMap() != null
                    ? new HashMap<>(dynamicContext.getUsedPromptMap()) : new HashMap<>();
            reviewPrompts.forEach((key, value) -> used.put("exit-review:" + key, value));
            dynamicContext.setUsedPromptMap(used);
        }
    }

    /**
     * 卷末清账（阶段末章完成后）：对本阶段出口长期未填的伏笔逐条 LLM 裁决——
     * VOID 弃置出账（从伏笔账剔除，随检查点落盘；结算文件留审计痕），RECOVER 结转限期回收
     * （结算清单随 DynamicContext 流转，下一段规划 prompt 注入回收指令）。
     * 裁决失败整体跳过（fail-soft），未填保持冻结，不阻塞生成
     */
    private void settleStageBreakerForeshadows(ArmoryCommandEntity requestParameter,
                                               cn.novel.yonren.domain.novel.model.valobj.StoryVO.Module module,
                                               StageBlueprintEntity stageBlueprint,
                                               List<ChapterSummaryEntity> summaries,
                                               List<ForeshadowSettlementEntity> settlements,
                                               int stageEndNo, Path storyDir,
                                               DefaultArmoryFactory.DynamicContext dynamicContext) {
        Map<String, String> settlementPrompts = new HashMap<>();
        List<ForeshadowSettlementEntity.SettlementDecision> decisions = foreshadowSettlementService.settleStageBreakers(
                module, stageBlueprint, requestParameter.getStoryContextEntity().getChapterGoal(),
                summaries, settlements, settlementPrompts,
                dynamicContext.getForeshadowSchedules());
        if (!settlementPrompts.isEmpty()) {
            Map<String, String> used = dynamicContext.getUsedPromptMap() != null
                    ? new HashMap<>(dynamicContext.getUsedPromptMap()) : new HashMap<>();
            settlementPrompts.forEach((key, value) -> used.put("settlement:" + key, value));
            dynamicContext.setUsedPromptMap(used);
        }
        if (decisions == null) {
            log.warn("卷末清账失败，未填伏笔保持冻结（fail-soft），stage: {}", stageBlueprint.getStageNo());
            return;
        }
        if (decisions.isEmpty()) {
            log.info("卷末清账：第{}阶段出口无长期未填伏笔，跳过", stageBlueprint.getStageNo());
            return;
        }
        dynamicContext.setForeshadowSettlements(settlements);
        // 回写排期表：判 DONE 的线必须真的关账（置 PAID）、判 PARKED 的线要记长线在养——
        // 否则下一段仍会以「逾期补收」再下一次令，那正是"同一条伏笔被反复安排"的机制本身
        int paid = foreshadowScheduleService.applySettlementOutcomes(
                dynamicContext.getForeshadowSchedules(), decisions,
                stageBlueprint.getEndChapter() == null ? 0 : stageBlueprint.getEndChapter());
        if (storyDir != null) {
            try {
                storyRepository.writeForeshadowSettlements(storyDir, settlements);
                if (paid > 0) {
                    storyRepository.writeForeshadowSchedule(storyDir, dynamicContext.getForeshadowSchedules());
                }
            } catch (Exception e) {
                log.error("卷末清账结算落盘失败，本轮仅内存生效（崩溃恢复时弃置条目可能回账，下次出口重新裁决）", e);
            }
        }
        long recoverCount = decisions.stream()
                .filter(d -> ForeshadowSettlementEntity.DECISION_RECOVER.equals(d.getDecision())).count();
        long doneCount = decisions.stream()
                .filter(d -> ForeshadowSettlementEntity.DECISION_DONE.equals(d.getDecision())).count();
        long parkedCount = decisions.stream()
                .filter(d -> ForeshadowSettlementEntity.DECISION_PARKED.equals(d.getDecision())).count();
        log.info("卷末清账完成：第{}阶段，未填 {} 条 = 已完成 {} + 长线在养 {} + 弃置 {} + 限期回收 {}"
                        + "（已完成项已置 PAID；在养项不再催收；回收项将注入下一段规划）",
                stageBlueprint.getStageNo(), decisions.size(), doneCount, parkedCount,
                decisions.size() - recoverCount - doneCount - parkedCount, recoverCount);
    }

    /** 质量趋势读取（fail-soft）：读不到/解析不出都返回空表，由体检层跳过该行指标 */
    private List<String> readQualityTrendSafely(Path storyDir) {
        try {
            List<String> lines = storyRepository.readQualityTrend(storyDir);
            return lines == null ? List.of() : lines;
        } catch (Exception e) {
            log.warn("质量趋势读取失败，体检跳过质量评分行：{}", e.getMessage());
            return List.of();
        }
    }

    /** 上一阶段（结束于 beforeChapter-1）未达成的退出条件块；无则返回 null */
    private String renderUnmetExitConditions(List<StageBlueprintEntity> blueprints, int beforeChapter) {
        if (blueprints == null) {
            return null;
        }
        StageBlueprintEntity prevStage = blueprints.stream()
                .filter(b -> b.getEndChapter() != null && b.getEndChapter() == beforeChapter - 1
                        && b.getExitResults() != null)
                .findFirst()
                .orElse(null);
        if (prevStage == null) {
            return null;
        }
        List<StageBlueprintEntity.ExitConditionResult> unmet = prevStage.getExitResults().stream()
                .filter(r -> !Boolean.TRUE.equals(r.getMet()))
                .toList();
        if (unmet.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("\n\n【上一阶段未达成的退出条件】本段规划必须安排其达成路径：");
        for (StageBlueprintEntity.ExitConditionResult result : unmet) {
            sb.append("\n- ").append(result.getCondition())
                    .append("（缺口：").append(StringUtils.defaultString(result.getNote(), "未达成")).append("）");
        }
        return sb.toString();
    }

    /** 章节要点：goal + keyEvents 拼接，供动态资料按本章语义细分检索/选择；两者皆空返回 null */
    private static String buildChapterBrief(ChapterPlanItemEntity item) {
        StringJoiner brief = new StringJoiner("；");
        if (StringUtils.isNotBlank(item.getGoal())) {
            brief.add(item.getGoal());
        }
        if (item.getKeyEvents() != null && !item.getKeyEvents().isEmpty()) {
            brief.add("关键事件：" + String.join("、", item.getKeyEvents()));
        }
        return brief.length() == 0 ? null : brief.toString();
    }

    /**
     * 正文生成与解析的四级降级（复用摘要链路思路）：
     * ①原文解析 ②修复后重解 ③重试一次 LLM ④抢救 content 字段。
     * 正文是核心交付物、无降级替身——全部失败时抛出异常终止本批
     * （逐章检查点已保住此前章节，可用 resumeStoryDir 续写补齐）
     */
    private ChapterContentEntity generateChapterWithFallback(
            ArmoryCommandEntity requestParameter, ChapterPlanItemEntity item,
            PromptContext ctx, String userPrompt, int globalNo, Map<String, String> usedPromptMap) {
        String lastRaw = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String raw = llmInvokeService.invoke(
                        requestParameter.getStoryVO(), PromptScene.CHAPTER_CONTENT, ctx, userPrompt, usedPromptMap);
                lastRaw = raw;

                ChapterContentEntity chapter = tryConvertChapter(raw);
                if (chapter == null) {
                    chapter = tryConvertChapter(JsonRepair.repair(raw));
                    if (chapter != null) {
                        log.info("第 {} 章正文经文本修复后解析成功", globalNo);
                    }
                }
                // 解析"成功"但正文为空 = 实际失败（重复 content 键取到后一个空串的典型形态）：
                // 不能直接返回，否则后续校验/落盘会拿到空稿；交由下一级降级接住
                if (chapter != null && StringUtils.isNotBlank(chapter.getContent())) {
                    // 正文实体以全局章节号为准（落盘文件名/续写衔接均依赖），不信任模型输出的编号
                    chapter.setChapterNo(globalNo);
                    return chapter;
                }
                if (chapter != null) {
                    log.warn("第 {} 章正文第 {}/2 次输出解析为空正文（疑似重复 content 键），继续降级",
                            globalNo, attempt);
                }
                log.warn("第 {} 章正文第 {}/2 次输出无法解析", globalNo, attempt);
            } catch (Exception e) {
                log.warn("第 {} 章正文第 {}/2 次生成异常", globalNo, attempt, e);
            }
        }

        // 抢救：取**首个非空** content——重复键时 JSON 库会取到后一个空串，
        // 但按出现顺序扫能拿回前面那段真正文（否则一条本可救回的稿子会被判死）
        String content = JsonRepair.extractStringField(lastRaw, "content", true);
        if (StringUtils.isNotBlank(content)) {
            log.warn("第 {} 章正文解析失败，已抢救正文主体（标题取自章节计划）", globalNo);
            return ChapterContentEntity.builder()
                    .chapterNo(globalNo)
                    .title(cn.novel.yonren.types.utils.ChapterTitleNormalizer.normalize(item.getTitle()))
                    .content(content)
                    .build();
        }
        throw new AppException(ResponseCode.UN_ERROR.getCode(),
                "第 " + globalNo + " 章正文生成与解析彻底失败，本批终止；"
                        + "此前章节已通过逐章检查点落盘，可在请求中携带 resumeStoryDir 续写补齐后续章节");
    }

    private ChapterContentEntity tryConvertChapter(String raw) {
        try {
            ChapterContentEntity chapter = CHAPTER_CONTENT_CONVERTER.convert(raw);
            if (chapter != null) {
                // 模型输出标题常自带"第N章"前缀，落盘/展示会与章节号重复，入口归一
                chapter.setTitle(cn.novel.yonren.types.utils.ChapterTitleNormalizer.normalize(chapter.getTitle()));
            }
            return chapter;
        } catch (Exception e) {
            return null;
        }
    }

    /** 节拍前情上下文取最近几章（只取摘要，避免把整段记忆前缀塞进每章一次的节拍调用） */
    private static final int BEATS_CONTEXT_CHAPTERS = 2;

    /** 节拍不可用时的兜底骨架：只保证因果链成立，不锁死场景数与篇幅 */
    private static final String FALLBACK_SKELETON = """

            【本章兜底骨架】节拍表不可用，请按以下因果链组织本章，不要写成流水账：
            开场：从一个正在发生的动作进入；
            对抗：角色的目标受到具体阻碍；
            转折：获得新信息，或付出代价；
            选择：视角人物必须做一个有成本的决定；
            结尾：该决定带来新的未决问题，并落在「结尾悬念」上。""";

    /** 契约不完整时的强约束：先把这一章写成立，不允许自行扩张 */
    private static final String RECOVERY_CONSTRAINTS = """

            【强约束】本章计划不完整，请严格依据上述内容写作：
            不得添加计划外的主要人物、地点或冲突线索；不得引入新的悬念钩子；
            把已有内容写完整，优先于向外扩张。""";

    /**
     * 节拍生成用的**前情上下文**：最近 2 章的剧情摘要（2026-09-22）。
     *
     * <p><b>为什么必须给</b>：节拍 prompt 的要求 3 早就写着"第一拍必须无缝承接上一章结尾"，
     * 但此前它只拿到本章计划——**指令承诺了它做不到的事**，模型只能凭空想象上一章。
     *
     * <p>⚠️ **只取最近 2 章、只取摘要**：节拍需要的是"上一章结尾在哪、当前状态如何"，
     * 不是全量设定。把整段记忆前缀（实测可达 9000+ 字）塞进去会让每章的节拍调用成本翻几倍。
     *
     * @return 上下文文本；无历史章时返回 null（prompt 会跳过该块）
     */
    private String buildBeatsContext(List<ChapterSummaryEntity> summaries, int beforeChapterNo) {
        if (summaries == null || summaries.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int taken = 0;
        for (int i = summaries.size() - 1; i >= 0 && taken < BEATS_CONTEXT_CHAPTERS; i--) {
            ChapterSummaryEntity summary = summaries.get(i);
            if (summary == null || summary.getChapterNo() == null
                    || summary.getChapterNo() >= beforeChapterNo) {
                continue;
            }
            sb.insert(0, "- 第" + summary.getChapterNo() + "章："
                    + StringUtils.defaultString(summary.getSummary()) + "\n");
            taken++;
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * 组装正文 prompt —— **四层结构**（2026-09-22 拆分重构，输出与拆分前逐字节一致）：
     * <ol>
     *   <li>{@link #renderFacts} 事实层：全局设定 + 记忆前缀（含账本末态红线）——先知道世界与前情；</li>
     *   <li>{@link #renderContract} 任务层：本章计划条目，即本章唯一的核心戏剧任务；</li>
     *   <li>{@link #renderContinuityConstraints} 约束层：承接上一章与账本一致性——不可违背的事实纪律；</li>
     *   <li>{@link #renderWritingGuidance} 表现层：写法建议与输出格式——可让步性最高的一层。</li>
     * </ol>
     * 分层只改变组织方式，不改变内容与顺序；包级可见以便测试锁定输出。
     */
    String buildChapterPrompt(String storyContext, ChapterPlanItemEntity item, String memoryPrefix,
                              int globalNo, ChapterContract contract) {
        return buildChapterPrompt(storyContext, item, memoryPrefix, globalNo, contract, null);
    }

    String buildChapterPrompt(String storyContext, ChapterPlanItemEntity item, String memoryPrefix,
                              int globalNo, ChapterContract contract, StoryContextEntity storyContextEntity) {
        return renderFacts(storyContext, memoryPrefix)
                + renderContract(contract, globalNo)
                + renderContinuityConstraints(contract)
                + renderWritingGuidance(globalNo, storyContextEntity);
    }

    /**
     * 事实层：全局设定 + 记忆前缀（记忆前缀插在全局设定之后、本章计划之前——
     * 先知道世界与前情，再领本章任务）。
     */
    private String renderFacts(String storyContext, String memoryPrefix) {
        StringBuilder sb = new StringBuilder();
        sb.append(storyContext);
        // 记忆前缀插在全局设定之后、本章计划之前：先知道世界与前情，再领本章任务
        if (StringUtils.isNotBlank(memoryPrefix)) {
            sb.append("\n\n").append(memoryPrefix);
        }
        return sb.toString();
    }

    /**
     * 任务层：本章计划条目（标题/目标/角色/关键事件/结尾悬念）+「要求」总起。
     */
    private String renderContract(ChapterContract contract, int globalNo) {
        if (contract == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n请根据以下第 ").append(globalNo).append(" 章计划，创作本章正文。")
                .append("\n第 ").append(globalNo).append(" 章计划：")
                .append("\n  标题：").append(contract.chapterTitle())
                .append("\n  目标：").append(contract.dramaticTask())
                .append("\n  角色：").append(contract.characters() == null
                        ? "" : String.join("、", contract.characters()))
                .append("\n  关键事件：").append(contract.mustHappen() == null
                        ? "" : String.join("、", contract.mustHappen()))
                .append("\n  结尾悬念：").append(contract.endingImage())
                .append("\n【本章完成条件】以下条件全部自然完成后，本章立即结束、不再扩写：")
                .append("\n  1) 关键事件全部落地并写出结果（见上）；")
                // 2) 是"形态"要求而非字数要求——2026-10-10 实测：同一段章节、同一份计划，
                // 两次生成的字数差 21%、对白差 30%、情感词差 75%，而计划供给逐字相同。
                // 根因是本条缺失：写手被要求"完成即收笔"，却没人要求"每个事件得演出来"，
                // 于是把 4 个关键事件各用一段话交代过去，章就成了 977 字的流程说明。
                .append("\n  2) **每个关键事件都必须演成一场戏**：有在场的对手或同伴、有一来一回的对话或具体动作、"
                        + "有可看见的结果。**禁止**把关键事件压成一句过渡叙述（如「他把材料交了上去」"
                        + "「两人谈完了」）——那是摘要，不是本章要交付的东西；")
                // 3) 情感要正面要求，且明确它不算注水：本项目的反注水机制（挤水判据把"重复情绪"
                // 列为首要目标、契约禁止"心理总结"）叠在一起，把**具体的情感呈现**也一起砍了——
                // 实测一章情感词从 5 处掉到 1 处。而这本书的格调本来就是"温情细腻…有血有肉的生活细节"。
                .append("\n  3) 至少一处**具体的情感时刻**：用动作、停顿、沉默、器物或对白把人物的情绪与关系变化"
                        + "呈现出来（比如话说到一半把碗放下、伸手又收回去、把糖纸压平了才开口），"
                        + "而不是用「他很担心」这类抽象心理总结；")
                .append("\n  4) 结尾停在「结尾悬念」所指的画面或动作上。")
                .append("\n  完成即收笔：**不要**为了凑字数追加新的冲突、解释、环境描写或**抽象的心理总结**；")
                .append("宁可短而完整，不可长而注水。但上面的第 2、3 条是**内容本身**，不是凑数——")
                .append("若发现写完还不足一场戏的量，说明事件被写薄了，该把它写细而不是收笔。")
                .append("\n  建议骨架（可按剧情调整顺序，但因果链必须成立）：")
                .append(contract.suggestedSkeleton())
                .append("\n要求：");
        return sb.toString();
    }

    /**
     * 约束层：承接上一章结尾、关键事件括号注释的处理纪律——事实一致性与衔接要求，
     * 属于不可违背的一层（排在写作建议之前）。
     */
    private String renderContinuityConstraints(ChapterContract contract) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n1. 严格围绕本章目标和关键事件展开。")
                .append("\n2. 无缝承接上一章结尾【上一章结尾原文·禁止重复】：上一章结尾原文仅用于衔接参考，严禁在本章正文中原样复述或大段重复（与上一章结尾文字重复不得超过30字）。本章第一段必须从上一章结束的时间点之后继续推进——如果上一章结尾是动作定格，本章开头写这个动作的后续结果而非重复动作本身；如果是悬念定格，本章开头写角色对悬念的反应而非重复悬念描述；如果是对话未答，本章开头写下一句对话而非重复上一句。禁止另起炉灶、禁止重复叙述上一章已写内容；人物状态、伤势、位置、持有物必须与角色账本及上一章结尾一致；人物所处位置、持有物与修为必须与【账本末态红线】（如有）一致，位置/持有物变化必须有过渡描写，严禁无过渡跳变；若上一章结尾是悬念/高潮定格，本章开头必须先接住这个定格再推进，但只能写后续不能复述定格本身。")
                .append("\n3. 关键事件里的括号注释（如\"回收第N章埋设的XX\"）是给你看的执行提示——")
                .append("必须把它转化为真实剧情来兑现该伏笔，严禁把方括号注释或其原文写进正文。");
        // 认知边界：账本一直在记录"谁还不知道什么"，但从未进过正文 prompt。
        // 不编号——避免与表现层的 4-11 撞号
        if (contract != null && StringUtils.isNotBlank(contract.knowledgeBoundary())) {
            sb.append("\n【认知边界·不得越界】").append(contract.knowledgeBoundary())
                    .append("。本章任何角色不得表现出超出上述范围的认知——")
                    .append("不得把\"怀疑\"写成\"确认\"，不得让角色知道他尚未获知的信息。");
        }
        // 能力—阶段一致性通用化）：此前的失控批次先出现"婴儿写数论证明、列乘法竖式"，
        // 收紧后重跑又绕道为"涂鸦=答案/摆物=警告/大人附会解读"。规则按通用原则表述（不限题材与年龄），
        // 具体阶段以记忆前缀中的锚（【时序锚】）为准；时序锚可能因预算被裁，写作层红线不会。
        sb.append("\n【角色能力边界】若上方记忆含【时序锚】：主角本章的动作、语言、书写、专注时长与精细操作")
                .append("必须落在该阶段（年龄/伤情/状态）的极限内——认知可以超前（若故事设定允许），媒介不能超前；")
                .append("超出的能力展示必须改写为阶段内的合法表达（偏好、注视、趋避、选择、单音节词、")
                .append("被协助完成——以阶段常识为准）。")
                .append("\n【特别禁令】两类能力展示变体同禁：")
                .append("① 用任何**可被他人在事后解码出具体含义**的载体传递信息——涂鸦、摆物、敲击、手势、")
                .append("节奏、器物位置都一样，只要他人能读出「某题答案/某警告/认出了某物」，就是能力展示，")
                .append("不是阶段内表达；")
                .append("② 让观察者在**单次**观察后就从主角行为中**确认**其掌握了具体知识或意图")
                .append("——至多「觉得不寻常」，坐实为具体结论须经多次重复，且本章保留不确定口吻。");
        return sb.toString();
    }

    /**
     * 表现层：内容密度、机制描述次数、句式与用词禁令、结尾要求、术语口径、情节去重，
     * 以及输出 JSON 格式——可让步性最高的一层。
     */
    private String renderWritingGuidance(int globalNo, StoryContextEntity storyContext) {
        StringBuilder sb = new StringBuilder();
                sb                .append("\n4. 内容密度纪律（定性要求；**数量目标已在计划阶段落成每章安排，此处不再重复下达**）：")
                .append("\n   - 每一段都必须带来信息增量：新信息进入读者视野 / 角色目标变化 / 关系或信任变化 / 资源或位置变化 / 风险升降 / 做出不可撤销的选择——只重复已知状态、或原地循环同一种情绪的段落必须删掉或合并；")
                .append("\n   - 具体动作优先于抽象心理：用可感知的动作呈现（\"他攥紧刀柄指节泛白\"），禁止用\"感到愤怒\"等抽象心理替代动作；")
                .append("\n   - 对话必须推进情节/揭示人物/制造冲突，\"你好\"\"吃了吗\"等无意义寒暄不计入；对话过密像剧本、过疏太干，按场景自然配比；")
                .append("\n   - 严禁通过重复解释已建立的设定、增加环境描写、拉长心理独白来凑篇幅。")
                .append("\n5. 金手指/核心能力运作机制全篇至多描述2次（首次建立时让读者理解运作方式、关键升级时展示能力变化），后续章节直接用行动展示操作过程和结果，不得每章用大段旁白重新解释原理；如需提醒读者机制存在，用角色的一个习惯性动作或一句话带过（如\"他又闭上眼，阵纹在视网膜上铺展开来\"——一句话带过不展开描述）")
                .append("\n6. 同章内出现3次及以上同类操作（如连续修补3个节点、连续击败3个敌人、连续检查3个房间）时，每次必须有不同的观察角度、困难类型、操作方式或情绪状态，不得用同一种句式重复；第一次详细描写建立操作模式和难度，第二次简化描写但增加新困难或意外，第三次用结果或他人反应带出（不再重复操作过程）")
                .append("\n7. 禁止\"这不是X，这是Y\"式定义旁白及其排比连用；禁止大段连续心理独白（超过3句）；禁止用旁白直接命名情绪（\"他感到愤怒/悲伤/震惊\"→改为用行为呈现，如\"他攥紧拳头\"）；关键转折用动作或对话呈现，不靠旁白总结；禁止已建立的设定/金手指机制在后续章节重复解释（\"这就是XX的原理\"\"XX的本质是\"）")
                .append("\n8. 正文为纯文本，禁止使用任何 Markdown 标记：不得用星号加粗或强调（包括 * 与 **）、不得用井号标题、横线列表、下划线；人物的心理活动、系统面板/提示、技能名称、注入脑海的旁白一律用自然语言直接叙述，靠上下文呈现层次，不得用任何符号包裹或高亮；严禁在正文中出现章节编号（如\"第30章\"\"第十二章\"）或\"本章/上一章/下一章\"等作者层面的元信息。回忆过往事件时，必须用时间/地点/事件指代，绝对不能用章节编号：错误示例\"第12章他在阵眼核心发现的\"→正确写法\"十二天前在阵眼核心发现的\"；错误示例\"第28章袭击事件后\"→正确写法\"上次遇袭之后\"；错误示例\"第6章那个模糊的感知\"→正确写法\"之前那次模糊的感知\"。唯一例外——角色说出口的对白必须用「」或中文双引号包裹：上述禁令只针对 Markdown 标记与作者层面元信息，绝不适用于对白；严禁为了遵守纯文本要求而丢弃对白引号。不要输出解释。")
                .append("\n9. 结尾必须落在真实的悬念/未决/情绪拉扯点上（呼应 endingHook），把读者拽进下一章；严禁用\"本章就这样结束了\"式的总结性收尾，严禁章末感悟升华（\"从此\"\"这就是\"\"终于\"等人生感悟/主题总结/金句点题），结尾必须停在画面或动作上；严禁在事件平息后空转两段才落笔。")
                .append(renderRegisterGuidance(storyContext))
                .append("\n11. 禁止重复使用已出现过的情节模式（情节复用检测）：如果之前已经写过\"公开演示能力→众人震惊→获得授权\"\"被权威质疑→当场证明→获得支持\"\"独自进入禁地→发现惊天秘密→被未知存在盯上\"等套路，本章不得再用相同套路；同类情节必须有不同的场景、对手、困难、结果或情绪走向。每章的核心冲突解决方式必须与前5章不同。如果【已用情节模式黑名单】（如有）中列出了近期使用过的情节模式，本章必须避开。")
                .append("\n\n请严格按照以下 JSON 格式输出：")
                .append("\n{\"chapterNo\":").append(globalNo)
                .append(",\"title\":\"章节标题\",\"content\":\"章节正文\"}");
        return sb.toString();
    }

    /** 题材/时代/人物语域契约：中性规则常驻，仙侠词表只对 fantasy 生效。 */
    private String renderRegisterGuidance(StoryContextEntity context) {
        GenreTypeVO genre = GenreTypeVO.match(context == null ? null : context.getTheme(),
                context == null ? null : context.getStyle());
        StringBuilder rule = new StringBuilder("\n10. 【语言与语域契约】客观叙述、人物对话和内心判断必须符合故事时代与世界设定；")
                .append("人物措辞必须符合其年龄、身份、教育、职业与已知信息。严禁让儿童、古代人物或低认知角色无来源地使用成熟学术腔、管理报告腔、互联网黑话或时代错位词；")
                .append("必须表达复杂判断时，改写为该人物能观察到的动作、物件、经验和口语，不得借叙述者替角色发表论文式总结。");
        if (genre == GenreTypeVO.FANTASY) {
            rule.append("玄幻/仙侠题材描述世界、动作、对话与环境时，优先使用设定内已有词汇（如灵气、阵纹、符箓、灵脉、宗门、长老、法宝）；")
                    .append("握手信号、加密信号、波形数据、服务器、协议、分布式节点、灰度、缓存、日志等程序员术语不得充当客观世界名词，")
                    .append("除非角色设定明确支持，且只作短促心理类比、点到即止。");
        }
        return rule.toString();
    }

    private void persistJobStatus(DefaultArmoryFactory.DynamicContext dynamicContext) {
        GenerationJob job = dynamicContext.getJob();
        Path storyDir = dynamicContext.getStoryDir();
        if (job == null || storyDir == null) {
            return;
        }
        try {
            storyRepository.writeJobStatus(storyDir, job);
        } catch (Exception e) {
            // 观测性文件：写失败不反噬生成流程（树尾持久化节点还会再落终态）
            log.warn("作业 {} 状态落盘失败，忽略", job.getJobId(), e);
        }
    }

    /**
     * 把三账本/伏笔账从记忆块列表中抽出（原地移除、保持相对顺序），调用方将其追加到正文前缀末尾。
     * Block 是 record：label 在场景内唯一（装配器不变式），按 label 匹配再 removeAll 不会误伤其他块
     */
    private static List<PromptBudgetGuard.Block> detachLedgerBlocks(List<PromptBudgetGuard.Block> memoryBlocks) {
        List<PromptBudgetGuard.Block> ledgers = new ArrayList<>();
        for (PromptBudgetGuard.Block block : memoryBlocks) {
            if (ChapterMemoryService.LEDGER_BLOCK_LABEL.equals(block.label())
                    || ChapterMemoryService.FORESHADOW_BLOCK_LABEL.equals(block.label())) {
                ledgers.add(block);
            }
        }
        memoryBlocks.removeAll(ledgers);
        return ledgers;
    }

    /**
     * 已用情节模式块（正文层）：委托 {@link UsedPatternPolicy}，与规划层共用同一实现。
     *
     * <p>此前这里有一份私有实现（"最近 5 章摘要前 80 字 + 首个节拍 goal→结果"），
     * 规划层则完全没有对应块——重复的源头在规划层，正文层再劝也拦不住。
     * 现两处共用同一实现，避免"规划说没重复、正文说重复"的口径分裂
     * （项目里已因"两份判据各自演化"出过几次问题）。窗口沿用 5 章，与改造前一致，便于对照。
     * 返回 null 表示无历史可参考（第 1 章或摘要为空）。
     */
    private String buildUsedPatternBlacklist(List<ChapterSummaryEntity> summaries, int chapterNo) {
        return UsedPatternPolicy.render(summaries, chapterNo, UsedPatternPolicy.BODY_LOOKBACK);
    }

    /**
     * 上章审校反馈渲染（P2）：语义 MINOR 压缩为 ≤3 条要点供下一章 prompt 回灌；空返回 null
     * （装配器过滤空白块）。只挑人设/审美类读者可感问题——一致性/伏笔类问题有各自的账本通道
     */
    static String renderReviewFeedback(List<ChapterIssueEntity> semanticMinors) {
        if (semanticMinors == null || semanticMinors.isEmpty()) {
            return null;
        }
        List<ChapterIssueEntity> readable = semanticMinors.stream()
                .filter(i -> i != null && StringUtils.isNotBlank(i.getDescription())
                        && ("character".equalsIgnoreCase(i.getDimension())
                        || "aesthetic".equalsIgnoreCase(i.getDimension())
                        || "pacing".equalsIgnoreCase(i.getDimension())))
                .limit(3)
                .toList();
        if (readable.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("【上章审校反馈】以下问题在上一章已被审校指出（读者已可见），"
                + "本章必须避免同类问题、并在同类场景中给出相反的写法：");
        for (ChapterIssueEntity issue : readable) {
            sb.append("\n- [").append(StringUtils.defaultString(issue.getDimension())).append("] ")
                    .append(StringUtils.abbreviate(issue.getDescription(), 80));
        }
        return sb.toString();
    }
}
