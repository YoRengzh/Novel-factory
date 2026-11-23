package cn.novel.yonren.domain.novel.service.armory.worker;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.config.StoryProperties.CausalityProperties.Mode;
import cn.novel.yonren.domain.novel.model.valobj.quality.DeliveryReview;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import cn.novel.yonren.domain.novel.service.armory.quality.DeliveryReviewPolicy;
import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
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
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterCommitState;
import cn.novel.yonren.domain.novel.service.armory.authoring.StoryAuthoringService;
import cn.novel.yonren.domain.novel.service.armory.memory.CharacterStateProjector;
import cn.novel.yonren.domain.novel.service.armory.quality.ChapterLengthPolicy;
import cn.novel.yonren.domain.novel.service.armory.quality.NarrativeStylePolicy;
import cn.novel.yonren.domain.novel.model.entity.StyleStatEntity;
import cn.novel.yonren.domain.novel.service.armory.audit.ParagraphDensityAuditService;
import cn.novel.yonren.domain.novel.model.valobj.contract.ChapterContract;
import cn.novel.yonren.domain.novel.model.valobj.contract.GenerationMode;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.domain.novel.service.armory.candidate.ChapterCandidateService;
import cn.novel.yonren.domain.novel.service.armory.writer.WriterSampleService;
import cn.novel.yonren.domain.novel.service.armory.writer.ChapterWritingPlan;
import cn.novel.yonren.domain.novel.service.armory.writer.ChapterWritingPlanService;
import cn.novel.yonren.domain.novel.service.armory.writer.WriterContextAssembler;
import cn.novel.yonren.domain.novel.model.valobj.revise.RewriteContext;
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
import cn.novel.yonren.domain.novel.service.armory.plan.FactReferenceSource;
import cn.novel.yonren.domain.novel.service.armory.plan.TimeAdvanceChecks;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptBudgetGuard;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptBudgetGuard.PrefixBlock;
import cn.novel.yonren.domain.novel.service.armory.prompt.WritingContractPolicy;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.domain.novel.service.armory.candidate.CandidateSampleService;
import cn.novel.yonren.domain.novel.model.valobj.quality.BatchHealthReport;
import cn.novel.yonren.domain.novel.service.armory.quality.BatchHealthService;
import cn.novel.yonren.domain.novel.model.valobj.quality.GateResult;
import cn.novel.yonren.domain.novel.service.armory.quality.GateFeedbackPolicy;
import cn.novel.yonren.domain.novel.service.armory.quality.QualityGate;
import cn.novel.yonren.domain.novel.service.armory.quality.UsedPatternPolicy;
import cn.novel.yonren.domain.novel.service.job.GenerationJob;
import cn.novel.yonren.types.enums.GenreType;
import cn.novel.yonren.types.enums.PromptScene;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import cn.novel.yonren.types.utils.ChapterTitleNormalizer;
import cn.novel.yonren.types.utils.JsonParseFallback;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.io.IOException;
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
    private static final JsonFactory CHAPTER_JSON =
            JsonFactory.builder().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS).build();

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

    /** 写手训练样本水龙头：每章落一行（最终prompt+采纳稿+门禁），供后训练自有写手模型，观测件不反噬主流程 */
    @Resource
    private WriterSampleService writerSampleService;

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
    private ChapterWritingPlanService chapterWritingPlanService;

    /** 观察式重基（默认 OFF）：只产生候选记录，不改变正文计划。 */
    @Resource
    private cn.novel.yonren.domain.novel.service.armory.rebase.ChapterRebaseRunner chapterRebaseRunner;

    @Resource
    private StoryMemoryService storyMemoryService;

    @Resource
    private ConsistencyIndexService consistencyIndexService;

    /** 机械校验事件留痕（账本失配等）：只写 WARN 无法聚合统计，落盘才能量化管道漏损 */
    @Resource
    private cn.novel.yonren.domain.novel.service.armory.quality.MechanicalEventRecorder mechanicalEventRecorder;

    @Resource
    private IStoryRepository storyRepository;

    @Resource
    private StoryProperties storyProperties;

    @Resource
    private cn.novel.yonren.domain.novel.service.armory.character.CharacterStageProfileService characterStageProfileService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cn.novel.yonren.domain.novel.service.armory.revise.ProseScalpelService proseScalpelService;

    /**
     * 执行整批逐章生成，完成后把正文/摘要/质量债/风格统计回写 DynamicContext，
     * 树尾持久化节点据此统一落盘
     */
    public void generateAll(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        // 在节拍、召回或正文等任何模型调用前拒绝矛盾配置，不能以关闭审校绕过严格交付。
        DeliveryReviewPolicy.assertConfiguration(storyProperties);
        Mode deliveryMode = DeliveryReviewPolicy.mode(storyProperties);
        boolean deliveryEnforced = deliveryMode == Mode.ENFORCE;
        if (deliveryEnforced && (dynamicContext.getStoryDir() == null || dynamicContext.getRunDir() == null)) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(),
                    "严格交付模式缺少生成前故事/run 检查点，正文未生成");
        }
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
        int olderBreathTimings = priorBreathTimings(storyDir, offset - 1);
        int lastBreathTimings = priorBreathTimings(storyDir, offset);
        // 惰性分段规划：分段蓝图与边界由规划节点计算，生成推进到未规划段时才用最新记忆规划该段
        List<RollingOutlineService.StageSegment> segments = dynamicContext.getPlanSegments();
        int batchEnd = offset + (storyContext.getChapterCount() == null ? 0 : storyContext.getChapterCount());
        // null=未分段（整批已规划）：局部变量取 MAX_VALUE 让"是否需要补规划"的判断恒为否
        Integer plannedEndStored = dynamicContext.getPlannedSegmentEnd();
        int plannedSegmentEnd = plannedEndStored != null ? plannedEndStored : Integer.MAX_VALUE;

        int idx = 0;
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
            cn.novel.yonren.domain.novel.service.armory.character.CharacterProfileReferences.validate(storyContext, item);
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
            // DIRECT 当前仍每章检索一次，使用紧凑章级查询：人物 + 场景意图 + 本章目标。
            // 它不把风格算进相似度，也不宣称已经完成逐场检索；真正逐场执行时再按当前段检索。
            String recallQuery = storyProperties.getWriterContextMode() == StoryProperties.WriterContextMode.DIRECT
                    ? storyMemoryService.buildSceneQuery(storyContext.getTheme(), item,
                            item.getCharacters(), directSceneTexts(item))
                    : storyMemoryService.buildContentQuery(storyContext.getTheme(), storyContext.getStyle(), item);
            StoryMemoryService.RecallOutcome recall = summaries.isEmpty()
                    ? new StoryMemoryService.RecallOutcome(List.of(), false)
                    : storyMemoryService.retrieveWithOutcome(
                            module, storyDir, recallQuery,
                            storyProperties.getWriterContextMode() == StoryProperties.WriterContextMode.DIRECT
                                    ? globalNo : recallMinChapterNo(summaries),
                            storyContext.getWorldId());
            List<StoryMemoryService.RecallHit> recallHits = recall.hits();
            String edgeState = chapterMemoryService.renderLedgerEdgeState(summaries);
            // 机械层风格警示：跨章逐字重复句 + 疲劳词超频（纯代码统计）
            String styleWarning = styleStatService.renderWarning(styleStat);
            int recentBreathTimings = olderBreathTimings + lastBreathTimings;
            String proseStyleWarning = recentBreathTimings >= 4 ? "近两章已有 " + recentBreathTimings
                    + " 处用呼吸次数交代时长。普通停顿可直接接下一件事，涉及具体时限时保留计时。" : null;
            var secrecyGuard = chapterMemoryService.buildSecrecyGuard(summaries, item);
            // 观察式重基：默认 OFF；开启时只落候选与差异，返回值不参与本方法后续任何分支。
            if (chapterRebaseRunner != null && chapterRebaseRunner.enabled()) {
                try {
                    chapterRebaseRunner.proposeOnly(requestParameter.getStoryVO(), item, summaries, storyContext,
                            storyDir, dynamicContext.getRunDir());
                } catch (Exception rebaseFailure) {
                    log.warn("第 {} 章重基候选失败，继续按原计划生成：{}", globalNo, rebaseFailure.getMessage());
                }
            }
            ChapterContract contract = buildChapterContract(item, summaries);
            var verifiedReferences = FactReferenceSource.verifyForWriting(item, summaries, globalNo, storyRepository, storyDir);
            String depthMode = cn.novel.yonren.domain.novel.model.valobj.character.CharacterDepthSettings.mode(storyContext);
            List<String> visibleCharacterFields = List.of();
            String planningContext = null;

            if (storyProperties.getWriterContextMode() != StoryProperties.WriterContextMode.DIRECT) {
                // LEGACY 保留完整规划前缀：它由中间整理模型消费，不直接发送给正文模型。
                List<PromptBudgetGuard.Block> memoryBlocks = new ArrayList<>(chapterMemoryService.buildMemoryBlocks(
                        summaries, prevTail, pendingConflicts, qualityDebts,
                        currentStageBlueprint(dynamicContext.getStageBlueprints(), globalNo),
                        rollingOutlineService.volumeAt(dynamicContext.getVolumes(), globalNo), recallHits,
                        item.getTimeAdvance()));
                chapterMemoryService.prependSettingsAnchorIfNoSummaries(memoryBlocks, summaries,
                        storyContext.getWorldSetting(), storyContext.getProtagonist(), storyContext.getOutline());
                memoryBlocks.addAll(chapterMemoryService.buildEntityDossierBlocks(
                        summaries, item, dynamicContext.getConsistencyIndex()));
                List<PromptBudgetGuard.Block> ledgerBlocks = detachLedgerBlocks(memoryBlocks);
                List<PromptBudgetGuard.Block> prefixBlocks = new ArrayList<>();
                prefixBlocks.add(PrefixBlock.EDGE_STATE.toBlock(edgeState));
                prefixBlocks.addAll(memoryBlocks);
                prefixBlocks.add(PrefixBlock.STYLE_WARNING.toBlock(styleWarning));
                prefixBlocks.add(PrefixBlock.FATIGUE_BLACKLIST.toBlock(styleStatService.renderContentFatigueBlacklist(styleStat)));
                prefixBlocks.add(PrefixBlock.CONSISTENCY.toBlock(consistencyIndexService.renderPrompt(dynamicContext.getConsistencyIndex())));
                prefixBlocks.add(PrefixBlock.STYLE_FINGERPRINT.toBlock(styleStatService.renderFingerprints(styleFingerprints)));
                prefixBlocks.add(PrefixBlock.USED_PATTERN_BLACKLIST.toBlock(buildUsedPatternBlacklist(summaries, globalNo)));
                prefixBlocks.add(PrefixBlock.IMAGERY_WARNING.toBlock(styleStatService.renderImageryWarning(styleStat)));
                if (StringUtils.isNotBlank(reviewFeedback)) {
                    prefixBlocks.add(PrefixBlock.REVIEW_FEEDBACK.toBlock(reviewFeedback));
                }
                prefixBlocks.addAll(ledgerBlocks);
                String fullPrefix = promptBudgetGuard.assembleChapterPrefix(globalNo, prefixBlocks);
                planningContext = StringUtils.defaultString(dynamicContext.getStoryContext()) + "\n\n"
                        + fullPrefix + "\n\n" + StringUtils.defaultString(contract.knowledgeBoundary())
                        + StringUtils.defaultString(secrecyGuard.promptBlock())
                        + StoryAuthoringService.renderGuidance(storyContext, globalNo, globalNo);
                if (!verifiedReferences.isEmpty()) {
                    planningContext += "\n【核验过的场景引用】" + com.alibaba.fastjson2.JSON.toJSONString(verifiedReferences);
                }
            }

            if ("APPLY".equals(depthMode)) {
                var assembler = new cn.novel.yonren.domain.novel.service.armory.character.CharacterContextAssembler();
                var characterContext = storyProperties.getWriterContextMode() == StoryProperties.WriterContextMode.DIRECT
                        ? assembler.assembleCompactForWriter(storyContext, item.getCharacters(), globalNo)
                        : assembler.assemble(storyContext, item.getCharacters(), globalNo);
                visibleCharacterFields = characterContext.visibleFieldIds();
                if (storyProperties.getWriterContextMode() != StoryProperties.WriterContextMode.DIRECT) {
                    planningContext += characterContext.text();
                    planningContext += cn.novel.yonren.domain.novel.service.armory.memory.CharacterDepthFeedbackProjector
                            .renderFeedback(storyContext, summaries);
                }
                log.info("第 {} 章人物设计：{} 个字段，诊断{}", globalNo,
                        characterContext.visibleFieldIds().size(), characterContext.diagnostics());
            } else if ("OBSERVE".equals(depthMode)) {
                log.info("第 {} 章人物设计OBSERVE：不改变写作提示词", globalNo);
            }
            if (storyProperties.getWriterContextMode() != StoryProperties.WriterContextMode.DIRECT
                    && StringUtils.isNotBlank(dynamicContext.getPlanResidualViolations())) {
                planningContext += "\n\n【相邻章节安排，仅用于避免串章】" + dynamicContext.getPlanResidualViolations();
            }

            Runnable checkWriting = () -> {
                if (job != null && (job.isCancelRequested() || job.isBudgetExceeded())) {
                    throw new java.util.concurrent.CancellationException("章节写作已取消或触发预算停止");
                }
            };
            ChapterWritingPlan writingPlan = prepareWritingPlan(requestParameter.getStoryVO(), item, globalNo,
                    planningContext, secrecyGuard, summaries, storyContext, verifiedReferences,
                    recallHits, prevTail);
            boolean sectioned = writingPlan.sections().size() > 1;
            GenerationMode mode = GenerationMode.decide(contract, false);
            // 观测字段以 DIRECT 实际材料为准：不能只因 APPLY 生成了人物卡就声称正文已注入。
            if (storyProperties.getWriterContextMode() == StoryProperties.WriterContextMode.DIRECT) {
                visibleCharacterFields = writingPlan.sections().stream()
                        .anyMatch(s -> s.render().contains("【人物决策卡") || s.render().contains("【出场人物底色"))
                        ? visibleCharacterFields : List.of();
            }
            log.info("第 {} 章正文材料已整理，按 {} 次写作交付", globalNo, writingPlan.sections().size());
            ChapterDraft draft = writeDraft(requestParameter, item, storyContext, globalNo, offset + totalChapters,
                    writingPlan, proseStyleWarning, dynamicContext.getUsedPromptMap(), checkWriting);
            ChapterContentEntity chapterContent = draft.chapter();
            if (storyProperties.getWriterContextMode() == StoryProperties.WriterContextMode.DIRECT
                    && proseScalpelService != null && chapterContent != null) {
                String trimmed = proseScalpelService.trim(module, item, chapterContent.getContent(), globalNo);
                if (StringUtils.isNotBlank(trimmed)) {
                    chapterContent.setContent(trimmed);
                }
            }
            ChapterManuscript initialDraft = ChapterManuscript.of(chapterContent);
            if (writerSampleService != null) {
                writerSampleService.recordDraft(storyDir, dynamicContext.getRunDir(), initialDraft);
                var suggestions = paragraphDensityAuditService.suggest(module, initialDraft.content(), globalNo,
                        recentBreathTimings);
                writerSampleService.recordSuggestions(storyDir, dynamicContext.getRunDir(), initialDraft, suggestions);
            }
            RewriteContext writerContext = RewriteContext.scoped(draft.userPrompt(), draft.systemPrompt(),
                    initialDraft, checkWriting, sectioned);
            GateResult gateResult = qualityGate.auditAndReviseIfEnabled(
                    requestParameter, item, chapterContent, summaries, styleStat, globalNo, storyDir, writerContext);
            if (deliveryMode != Mode.OFF) {
                gateResult = bindDeliveryReview(gateResult, item, chapterContent, deliveryEnforced);
            }
            // 候选选优（六期）：低置信通过（MINOR 残留/修订过）时第二模型族重写候选，
            // UNKNOWN 是未完成核验，不是低置信通过；不得追加候选成本或为其生产合格样本。
            if (!sectioned && !gateResult.planAdjustmentRequired()
                    && (deliveryMode == Mode.OFF || !gateResult.deliveryReview().unknown())) {
                gateResult = chapterCandidateService.challenge(requestParameter, item, draft.promptContext(), draft.basePrompt(),
                        chapterContent, gateResult, summaries, styleStat, globalNo, storyDir);
            }
            if (deliveryMode != Mode.OFF) {
                // 候选会原地换稿；必须对最终稿/当前计划重新核对身份，旧报告绝不能授权新正文。
                gateResult = bindDeliveryReview(gateResult, item, chapterContent, deliveryEnforced);
                if (gateResult.deliveryReview().blocksCommit()) {
                    // 必须抛异常而不是 break：break 会路由树尾 PersistChapterPlanNode 写正式正文。
                    // 计划调整同样不得绕过本拦截；此前已逐章提交的历史保持不变。
                    dynamicContext.setChapterContents(new ArrayList<>(chapterContents));
                    throw preserveBlockedDelivery(dynamicContext, item, ChapterManuscript.of(chapterContent),
                            gateResult, offset + chapterPlan.getChapters().size());
                }
                recordDeliveryReview(dynamicContext, item, chapterContent, gateResult);
            }
            boolean deliveryUnknown = deliveryMode != Mode.OFF && gateResult.deliveryReview().unknown();
            boolean deliveryPassed = deliveryMode == Mode.OFF || gateResult.deliveryReview().passed();
            // 只有交付策略已允许的稿件才进入树尾待写集合。
            chapterContents.add(chapterContent);
            olderBreathTimings = lastBreathTimings;
            lastBreathTimings = NarrativeStylePolicy.inspect(chapterContent.getContent()).breathTimings().size();
            // 写手样本（训练数据水龙头）：完整最终 prompt 取自 usedPromptMap 的 chapter-N:user/system
            // （LlmInvokeService 已存含规则尾注的版本），正文取过完审校/修订/盲评的最终采纳稿；
            // OBSERVE 的 ISSUE/UNKNOWN 独立留交付诊断，不得冒充合格范文。
            if (writerSampleService != null && deliveryPassed && !sectioned) {
                Map<String, String> usedPrompts = dynamicContext.getUsedPromptMap();
                writerSampleService.record(storyDir,
                        storyDir == null ? null : storyDir.getFileName().toString(),
                        globalNo,
                        usedPrompts != null ? usedPrompts.get("chapter-" + globalNo + ":user") : null,
                        usedPrompts != null ? usedPrompts.get("chapter-" + globalNo + ":system") : null,
                        chapterContent, gateResult, mode.getLabel());
            }
            List<ChapterIssueEntity> unresolvedIssues = gateResult.unresolvedBlocking();
            // 计划与可信事实冲突：不是写作缺陷，不能当普通质量债继续推进后续章节。
            // 当前章按原稿提交、此前章节均保留，作业停在可调整计划的位置。
            boolean planAdjustmentRequired = gateResult.planAdjustmentRequired();
            if (planAdjustmentRequired) {
                log.warn("第 {} 章暂停：计划与可信事实冲突，保留当前稿等待调整计划：{}",
                        globalNo, gateResult.planAdjustmentReason());
            }
            // 验证式自动核销：以本章审校结论验证旧债，须在记入本章新债之前
            if (qualityGate.auditEnabled() && !deliveryUnknown) {
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
            if (deliveryUnknown) {
                // unresolvedBlocking 目前混有机械与语义来源，不能把未完成核验整体解释成内容缺陷。
                // 保留分账明确的 mechanicalMinor，下方不改普通已确认质量债的既有行为。
                log.warn("第 {} 章交付核验 UNKNOWN：不核销旧债、不把未验证 BLOCKING 记为内容债", globalNo);
            } else if (gateResult.auditVerifyDegraded()) {
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
            reviewFeedback = deliveryUnknown ? null : renderReviewFeedback(GateFeedbackPolicy.feedbackMinorsFor(gateResult,
                    cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript.of(chapterContent)));
            // 文风指纹库：仅首轮全票通过且已确认交付的章节提取高信息密度句入库（滚动上限 50）
            if (deliveryPassed && gateResult.grade() == GateResult.PassGrade.CLEAN_PASS) {
                List<String> additions = styleStatService.extractFingerprints(chapterContent.getContent());
                if (additions != null && !additions.isEmpty()) {
                    styleStatService.mergeFingerprints(styleFingerprints, additions);
                }
            }

            // 机械层风格统计滚动合并（使用最终采纳稿）。
            // 题材随调用传入：重复意象清单按题材分组取用（{@code repetition-watchlist.txt}），
            // 题材取自故事设定（与摘要落盘的 storyGenre 同一来源，口径一致）
            styleStatService.merge(styleStat, chapterContent.getContent(),
                    GenreType.match(storyContext == null ? null : storyContext.getTheme(),
                            storyContext == null ? null : storyContext.getStyle()).getCode());

            // 摘要立即生成，供下一章记忆前缀使用。摘要是质量门：三级降级+抢救均失败抛异常终止本批，
            // 抢救成功返回 partial 标记的残缺记忆（仅剧情摘要，账本状态缺失）
            String ledgerPrompt = chapterMemoryService.renderLedgerPrompt(summaries);
            // 摘要携带待回收伏笔账（新→旧封顶），回收登记才能逐字沿用原文、账本核销匹配才可靠。
            // 带 ID 前缀版：模型可回抄 [F章号-序号] 精确销账，不再依赖措辞吻合
            List<String> pendingForeshadowing = chapterMemoryService.buildForeshadowContextListWithIds(
                    summaries, ChapterMemoryService.FORESHADOW_CONTEXT_LIMIT);
            ChapterSummaryEntity summary = chapterSummaryService.summarize(
                    requestParameter.getStoryVO(), item, chapterContent.getContent(), globalNo,
                    ledgerPrompt, pendingForeshadowing, summaries);
            // 账本失配留痕：本章回收清单与待收账本一条都没匹配上时，
            // 把失配事实结构化落盘——这是"账本按文本核销"这条设计的漏损计量点，
            // 只写 WARN 日志无法聚合（伏笔结构化 ID 改造的效果验证依赖这个数字）。fail-soft
            ChapterSummaryService.LedgerMismatch ledgerMismatch = chapterSummaryService.consumeLedgerMismatch();
            if (ledgerMismatch != null) {
                mechanicalEventRecorder.recordLedgerMismatch(storyDir, globalNo,
                        ledgerMismatch.resolvedCount(), ledgerMismatch.pendingCount(),
                        ledgerMismatch.resolved());
            }
            // 种子补 new 留痕：模型偶尔只填 foreshadowSeeds 漏填 foreshadowingNew，
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
            summary.setStoryGenre(cn.novel.yonren.types.enums.GenreType.match(
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
                    storyProperties.getWriterContextMode() == StoryProperties.WriterContextMode.DIRECT
                            ? writingPlan.knowledgeBoundarySupplied()
                            : contract != null && StringUtils.isNotBlank(contract.knowledgeBoundary()));
            // 悬念档位也落盘：体检要统计"主线是否原地打转"（读盘重算那条路看不到内存里的计划）
            summary.setSuspenseBeat(item.getSuspenseBeat());
            // 人物状态增量必须绑定到具体正文版本：重建时靠哈希确认增量属于这一版稿，
            // 否则编辑或重生成之后会把旧稿的人物状态当成当前事实。
            var manuscript = cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript.of(chapterContent);
            summary.setManuscriptHash(manuscript.hash());
            if (!"OFF".equals(depthMode)) {
                summary.setCharacterDesignMode(depthMode);
                summary.setCharacterDesignRevision(storyContext.getCharacterDecisionProfiles() == null
                        ? null : storyContext.getCharacterDecisionProfiles().revision());
                summary.setCharacterFieldsInjected(visibleCharacterFields.size());
                summary.setCharacterProfilesInjected((int) visibleCharacterFields.stream()
                        .map(field -> field.substring(0, field.lastIndexOf(':'))).distinct().count());
            }
            // 证据与身份由代码重算：模型给的人物、事项、取值、证据保留，characterId/factId/effectId/
            // 章号/哈希一律以本地正文为准。定位不到证据或状态迁移非法的条目转 pending，不进权威状态。
            cn.novel.yonren.domain.novel.service.armory.memory.CharacterStateProjector.bind(
                    summary, manuscript, summaries);
            cn.novel.yonren.domain.novel.service.armory.memory.CharacterBeatEvidencePolicy.bind(
                    summary, manuscript, summaries);
            summary.setCharacterDepthReview(cn.novel.yonren.domain.novel.service.armory.memory.CharacterDepthFeedbackProjector
                    .observe(storyContext, item, manuscript, summary, summaries));
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

            // 时间欠账回灌：把计划声明与正文实际时点的缺口
            // 注入下一章的【偏差警示】块。复用 pendingConflicts 这条现成通道——
            // 它本来就是"上一章的问题，下一章必须修正"的传递载体（语义与账本冲突一致）。
            //
            // 时间欠账可能累积，逐章回灌让下一章及时处理，而不是只留批末告警。
            String timeArrears = timeAdvanceArrears(item, summaries, globalNo);
            if (StringUtils.isNotBlank(timeArrears)) {
                // 前缀区分来源：偏差警示块的导语是"以账本为准修正"，那对时间欠账不适用——
                // 时间的基准是【时序锚 + 本章时间目标】，不是账本。带前缀的条目在渲染层
                // 走独立导语（见 ChapterMemoryService 偏差警示块的组装处），避免"以账本为准"
                // 与"必须推进到 X"两条指令互相打架。
                pendingConflicts = new java.util.ArrayList<>(
                        pendingConflicts == null ? java.util.List.of() : pendingConflicts);
                pendingConflicts.add(TIME_ARREARS_PREFIX + timeArrears);
                log.warn("第{}章时间欠账：{}（已注入下一章偏差警示）", globalNo, timeArrears);
            }

            // 阶段收束钩子（阶段末章）：机械节奏报告写回蓝图 + 退出条件外部审计 + 卷末清账
            //（清账结果：VOID 出账、RECOVER 随结算文件注入下一段规划 prompt）
            StageBlueprintEntity stageBlueprint = currentStageBlueprint(dynamicContext.getStageBlueprints(), globalNo);
            if (stageBlueprint != null && globalNo == stageBlueprint.getEndChapter()) {
                stageBlueprint.setStageReport(stageReportService.buildStageReport(
                        stageBlueprint, summaries, chapterContents));
                reviewStageExitConditions(module, stageBlueprint, summaries, dynamicContext);
                settleStageBreakerForeshadows(requestParameter, module, stageBlueprint, summaries,
                        foreshadowSettlements, dynamicContext);
            }

            // 逐章同步上下文：惰性分段规划依赖最新记忆（下一段规划在章间触发）
            dynamicContext.setChapterSummaries(summaries);
            dynamicContext.setPendingConflicts(pendingConflicts);
            dynamicContext.setPrevChapterTail(chapterMemoryService.tailByParagraph(
                    chapterContent.getContent(), ChapterMemoryService.DEFAULT_PREV_TAIL_LENGTH));

            if (storyDir != null) {
                var state = new ChapterCommitState(summaries, consistencyIndex, qualityDebts, styleStat,
                        styleFingerprints, dynamicContext.getForeshadowSchedules(), foreshadowSettlements,
                        dynamicContext.getStageBlueprints(), dynamicContext.getVolumes());
                try {
                    storyRepository.commitChapter(storyDir, chapterContent, state);
                } catch (Exception e) {
                    log.error("第 {} 章提交失败，本批终止", globalNo, e);
                    throw new AppException(ResponseCode.UN_ERROR.getCode(),
                            "第 " + globalNo + " 章提交失败，请续写以恢复未完成的提交：" + e.getMessage(), e);
                }
                // 向量索引可从已提交记忆补建，不占用章节事务。
                try {
                    storyMemoryService.indexAfterChapter(module, storyDir, summaries, storyContext.getWorldId());
                } catch (Exception e) {
                    log.warn("第 {} 章向量索引更新失败", globalNo, e);
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

            if (planAdjustmentRequired) {
                if (job != null) {
                    job.requirePlanAdjustment(globalNo, offset + totalChapters,
                            storyDir == null ? null : storyDir.getFileName().toString(),
                            gateResult.planAdjustmentReason());
                    persistJobStatus(dynamicContext);
                }
                log.warn("第 {} 章后停止本批：计划需调整，已保留当前稿与检查点（{}）",
                        globalNo, gateResult.planAdjustmentReason());
                break;
            }

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

    private int priorBreathTimings(Path storyDir, int chapterNo) {
        if (storyDir == null || chapterNo < 1) return 0;
        try {
            ChapterContentEntity chapter = storyRepository.readChapter(storyDir, chapterNo);
            return chapter == null ? 0 : NarrativeStylePolicy.inspect(chapter.getContent()).breathTimings().size();
        } catch (Exception e) {
            log.warn("第 {} 章正文无法回读，跳过该章计时统计", chapterNo, e);
            return 0;
        }
    }

    /** 续写从故事目录读取已有的文风指纹。 */
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
                || item.getChapterType() != cn.novel.yonren.types.enums.ChapterType.FINALE
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
                    .chapterType(cn.novel.yonren.types.enums.ChapterType.FINALE)
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
     * 时间欠账条目在偏差警示列表里的来源前缀：渲染层据此把它与账本冲突分开导语。
     * 偏差警示的默认导语是"以账本为准修正"——对时间欠账不适用（时间的基准是时序锚，
     * 不是账本），混在同一导语下会让写手收到两条互相矛盾的指令。
     */
    public static final String TIME_ARREARS_PREFIX = "【时间欠账】";

    /**
     * 本章的时间欠账（计划声明的目标日期 vs 摘要记录的实到日期）。
     *
     * 只针对**刚写完的这一章**做比较，产出可直接注入下一章【偏差警示】的文案；
     * 无欠账或日期无法定位时返回 null（不臆造）。
     *
     * 为什么逐章比对而不是整段扫：欠账会累积，逐章回灌才能让写手在下一章就补；
     * 整段扫只能在段末发现，那时已无法修正。
     */
    private String timeAdvanceArrears(ChapterPlanItemEntity item, List<ChapterSummaryEntity> summaries, int globalNo) {
        if (item == null || StringUtils.isBlank(item.getTimeAdvance()) || summaries == null || summaries.isEmpty()) {
            return null;
        }
        List<TimeAdvanceChecks.Arrears> arrears = TimeAdvanceChecks.findArrears(List.of(item), summaries);
        if (arrears.isEmpty()) {
            return null;
        }
        TimeAdvanceChecks.Arrears a = arrears.get(0);
                // 措辞与时间锚的"护栏"语义对齐：此处只在**计划显式声明了绝对目标日期**、
        // 而正文落后时才触发，所以仍要求追上；但不再指定"过夜/赶路"这类填充手段——
        // 时间推移可以融进场景转换，不必单独写一段过渡。
        return "第" + globalNo + "章时间推进未达标：计划要求到 " + a.target()
                + "，正文实际停在 " + a.actual() + "（欠 " + a.behindDays() + " 天）。"
                + "本章时间应推进到不早于 " + a.target()
                + "：可在章末交代时间推移，也可把它融进场景转换（不必单独写一段过夜/赶路的过渡）。";
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
        if (characterStageProfileService != null) characterStageProfileService.ensure(requestParameter, dynamicContext,
                segment.blueprint(), segment.startChapter());
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
                summaries, dynamicContext.getForeshadowSettlements(), planJob, dynamicContext.getStoryDir());

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
        foreshadowScheduleService.applySettlementOutcomes(
                dynamicContext.getForeshadowSchedules(), decisions,
                stageBlueprint.getEndChapter() == null ? 0 : stageBlueprint.getEndChapter());
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

    record ChapterDraft(ChapterContentEntity chapter, PromptContext promptContext,
                        String basePrompt, String userPrompt, String systemPrompt) {}

    /** 场景级检索文本：本章各场的意图与阻力，去掉人物名（人物已在查询串里单列）。 */
    private static List<String> directSceneTexts(ChapterPlanItemEntity item) {
        if (item == null || item.getScenes() == null || item.getScenes().isEmpty()) {
            return List.of();
        }
        List<String> texts = new java.util.ArrayList<>();
        for (var scene : item.getScenes()) {
            if (scene == null) {
                continue;
            }
            StringBuilder line = new StringBuilder();
            if (StringUtils.isNotBlank(scene.intention())) {
                line.append(scene.intention().trim());
            }
            if (StringUtils.isNotBlank(scene.resistance())) {
                line.append("；阻碍：").append(scene.resistance().trim());
            }
            if (line.length() > 0) {
                texts.add(line.toString());
            }
        }
        return texts;
    }

    /**
     * 正文材料来源：DIRECT 走确定性组装，LEGACY 沿用 LLM 分段整理。
     *
     * <p>DIRECT 的价值不在于"少一次调用"，而在于写手拿到的是计划原文与该段在场人物的真实状态，
     * 不再依赖中间模型判断"哪些细节值得保留"——那个判断的校验只覆盖场景编号与禁泄词。
     * 没有可写场景时明确报错，不在缺少规划背景时静默降级。
     */
    ChapterWritingPlan prepareWritingPlan(cn.novel.yonren.domain.novel.model.valobj.StoryVO storyVO,
                                          ChapterPlanItemEntity item, int globalNo,
                                          String planningContext, ChapterMemoryService.SecrecyGuard secrecyGuard,
                                          List<ChapterSummaryEntity> summaries, StoryContextEntity story,
                                          java.util.Set<cn.novel.yonren.domain.novel.model.valobj.plan.FactReference> verifiedReferences,
                                          List<StoryMemoryService.RecallHit> recallHits, String previousTail) {
        if (storyProperties.getWriterContextMode() == StoryProperties.WriterContextMode.DIRECT) {
            String characterGuidance = "";
            if ("APPLY".equals(cn.novel.yonren.domain.novel.model.valobj.character.CharacterDepthSettings.mode(story))) {
                characterGuidance = new cn.novel.yonren.domain.novel.service.armory.character.CharacterContextAssembler()
                        .assembleCompactForWriter(story, item.getCharacters(), globalNo).text();
            }
            String directTail = StringUtils.isBlank(previousTail)
                    ? "" : chapterMemoryService.tailByParagraph(previousTail, 800);
            var directContext = new cn.novel.yonren.domain.novel.service.armory.writer.WriterChapterContext(
                    verifiedReferences,
                    characterGuidance,
                    cn.novel.yonren.domain.novel.service.armory.writer.WriterCharacterContext.renderFeedback(
                            cn.novel.yonren.domain.novel.service.armory.memory.CharacterDepthFeedbackProjector
                                    .renderFeedback(story, summaries)),
                    cn.novel.yonren.domain.novel.service.armory.writer.WriterContextSupport.edgeState(
                            chapterMemoryService, summaries),
                    cn.novel.yonren.domain.novel.service.armory.writer.WriterContextSupport.castStates(
                            chapterMemoryService, summaries, item)
                            + cn.novel.yonren.domain.novel.service.armory.writer.WriterContextSupport.relevantContinuity(
                            chapterMemoryService, summaries, item),
                    secrecyGuard == null ? "" : secrecyGuard.promptBlock(),
                    StoryAuthoringService.renderGuidance(story, globalNo, globalNo),
                    cn.novel.yonren.domain.novel.service.armory.writer.WriterContextSupport.memories(recallHits, globalNo),
                    directTail,
                    cn.novel.yonren.domain.novel.service.armory.writer.WriterContextSupport.initialFacts(story, summaries),
                    resolveDirectPovCharacter(story, item));
            // 此阶段只验证整章材料直通，不按字数切段冒充知识隔离。
            // 逐场作者权限、知识时序和工作态要由后续场景执行器统一维护。
            if (storyProperties.getWriterContextSectionChars() > 0) {
                log.warn("DIRECT 暂以整章单次写作交付；分段阈值暂不生效，避免后段丢失人物卡和作者约束");
            }
            ChapterWritingPlan direct = WriterContextAssembler.assemble(item, summaries, directContext, 0);
            if (!direct.sections().isEmpty()) {
                log.info("第 {} 章 DIRECT 正文材料：{} 段 / {} 字；未装配全量规划前缀",
                        globalNo, direct.sections().size(),
                        direct.sections().stream().mapToInt(s -> s.render().length()).sum());
                return direct;
            }
            log.warn("第 {} 章计划无可写场景或关键事件，DIRECT 停止生成", globalNo);
            throw new AppException(ResponseCode.UN_ERROR.getCode(),
                    "第 " + globalNo + " 章没有可写场景或关键事件，DIRECT 无法构造正文材料");
        }
        return chapterWritingPlanService.prepare(storyVO, item, globalNo, planningContext,
                secrecyGuard == null ? List.of() : secrecyGuard.keywords());
    }

    /** 限知或第一人称视角下识别叙述聚焦主角，用于区分主角内心显化与配角戏底暗线渲染。 */
    static String resolveDirectPovCharacter(StoryContextEntity story, ChapterPlanItemEntity item) {
        if (story == null || StringUtils.isBlank(story.getPerspective())) {
            return null;
        }
        String perspective = story.getPerspective();
        if (perspective.contains("全知") || (!perspective.contains("限知") && !perspective.contains("第一人称"))) {
            return null;
        }
        String protagonistText = StringUtils.defaultString(story.getProtagonist());
        var profileSet = story.getCharacterDecisionProfiles();
        if (profileSet != null && profileSet.profiles() != null) {
            String fallbackProfileName = null;
            for (var profile : profileSet.profiles()) {
                if (profile == null || StringUtils.isBlank(profile.displayName())) {
                    continue;
                }
                String name = profile.displayName().strip();
                if (fallbackProfileName == null) {
                    fallbackProfileName = name;
                }
                if (protagonistText.contains(name)) {
                    return name;
                }
            }
            if (fallbackProfileName != null) {
                return fallbackProfileName;
            }
        }
        if (item != null && item.getCharacters() != null) {
            for (String ch : item.getCharacters()) {
                if (StringUtils.isNotBlank(ch) && protagonistText.contains(ch.strip())) {
                    return ch.strip();
                }
            }
            String outlineText = StringUtils.defaultString(story.getOutline());
            if (!outlineText.isBlank()) {
                String earliest = null;
                int bestIndex = Integer.MAX_VALUE;
                for (String ch : item.getCharacters()) {
                    if (StringUtils.isBlank(ch)) {
                        continue;
                    }
                    String name = ch.strip();
                    if (outlineText.contains("主角" + name)) {
                        return name;
                    }
                    int idx = outlineText.indexOf(name);
                    if (idx >= 0 && idx < bestIndex) {
                        bestIndex = idx;
                        earliest = name;
                    }
                }
                if (earliest != null) {
                    return earliest;
                }
            }
        }
        return null;
    }

    ChapterDraft writeDraft(ArmoryCommandEntity request, ChapterPlanItemEntity item, StoryContextEntity story,
                            int chapterNo, int totalChapters, ChapterWritingPlan plan, String styleWarning,
                            Map<String, String> usedPromptMap, Runnable checkWriting) {
        var text = new StringBuilder();
        ChapterContentEntity chapter = null;
        PromptContext context = null;
        String basePrompt = null;
        Map<String, String> sectionPrompts = new HashMap<>();
        String promptKey = "chapter-" + chapterNo + ":";
        int sections = plan.sections().size();
        var defaults = request.getStoryVO().getDefaults();
        String language = defaults == null ? null : defaults.getLanguage();
        boolean directMode = storyProperties != null
                && storyProperties.getWriterContextMode() == StoryProperties.WriterContextMode.DIRECT;
        for (int i = 0; i < sections; i++) {
            checkWriting.run();
            var section = plan.sections().get(i);
            context = PromptContext.builder().theme(story.getTheme()).style(story.getStyle())
                    .chapterNo(chapterNo).totalChapters(totalChapters).chapterType(item.getChapterType())
                    .chapterBrief(section.brief()).directWriterMode(directMode).build();
            String tail = chapterMemoryService.tailByParagraph(text.toString(), ChapterMemoryService.DEFAULT_PREV_TAIL_LENGTH);
            basePrompt = buildWritingPrompt(story, language, chapterNo, section, tail, styleWarning, sections > 1,
                    i + 1 == sections, directMode);
            sectionPrompts.clear();
            ChapterContentEntity part = generateChapterWithFallback(request, item, context, basePrompt, chapterNo,
                    sectionPrompts, directMode);
            if (chapter == null) {
                chapter = part;
            }
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            text.append(sections == 1 ? part.getContent() : part.getContent().strip());
            if (usedPromptMap != null) {
                for (var entry : sectionPrompts.entrySet()) {
                    String key = entry.getKey();
                    if (sections > 1 && key.startsWith(promptKey)) {
                        key = promptKey + "section-" + (i + 1) + ":" + key.substring(promptKey.length());
                    }
                    usedPromptMap.put(key, entry.getValue());
                }
            }
        }
        chapter.setContent(text.toString());
        // 分段稿的任何一次写作上下文都不能代表整章的可见范围。
        return sections > 1 ? new ChapterDraft(chapter, null, null, null, null)
                : new ChapterDraft(chapter, context, basePrompt,
                        StringUtils.defaultIfBlank(sectionPrompts.get(promptKey + "user"), basePrompt),
                        sectionPrompts.get(promptKey + "system"));
    }

    String buildWritingPrompt(StoryContextEntity story, String language, int chapterNo, ChapterWritingPlan.Section section,
                              String tail, String styleWarning, boolean sectioned, boolean last) {
        return buildWritingPrompt(story, language, chapterNo, section, tail, styleWarning, sectioned, last, false);
    }

    String buildWritingPrompt(StoryContextEntity story, String language, int chapterNo, ChapterWritingPlan.Section section,
                              String tail, String styleWarning, boolean sectioned, boolean last, boolean directMode) {
        if (directMode) {
            return buildDirectWritingPrompt(story, language, chapterNo, section, tail, styleWarning, sectioned, last);
        }
        return "请按下面的材料写第 " + chapterNo + " 章" + (sectioned ? "的当前一段。\n" : "。\n")
                + "类型：" + StringUtils.defaultString(story.getTheme())
                + "。格调：" + StringUtils.defaultString(story.getStyle())
                + "。语气：" + StringUtils.defaultString(story.getTone()) + "。"
                + (StringUtils.isBlank(language) ? "" : "写作语言：" + language + "。") + "\n\n"
                + section.render()
                + (StringUtils.isBlank(tail) ? "" : "\n\n【本章已写原文结尾，从这里往下接】\n" + tail)
                + "\n\n视角人物凭亲历、所闻或眼下的证据行动。猜测可以出错，确认须有来源。"
                + "限知或第一人称叙述只进入当前视角人物的内心。旁人的打算通过他们的言行或视角人物的猜测呈现。"
                + "按事件先后写出新信息到手的时刻。"
                + "事件完整后停笔。保留当前人物的语气和选择，已经通过动作或对白说清的意思可以留给读者。"
                + "承接给定的入场状态，只在时间变化影响理解或承担剧情作用时交代时间。"
                + (last ? "" : "写到当前一段的事件完成就停，后面会接写，无须写章末伏笔、总结或下场预告。")
                + (StringUtils.isBlank(styleWarning) ? "" : "\n" + styleWarning)
                + "\n\n" + WritingContractPolicy.renderIntentGuidance()
                + renderWritingGuidance(chapterNo, story, sectioned);
    }

    private String buildDirectWritingPrompt(StoryContextEntity story, String language, int chapterNo,
                                            ChapterWritingPlan.Section section, String tail, String styleWarning,
                                            boolean sectioned, boolean last) {
        GenreType genre = GenreType.match(story.getTheme(), story.getStyle());
        StringBuilder sb = new StringBuilder();
        sb.append("请根据以下局面与材料，直接写出第 ").append(chapterNo).append(" 章")
                .append(sectioned ? "的当前一段" : "").append("正文。\n")
                .append("类型：").append(StringUtils.defaultString(story.getTheme()))
                .append("。格调：").append(StringUtils.defaultString(story.getStyle()))
                .append("。语气：").append(StringUtils.defaultString(story.getTone())).append("。");
        if (StringUtils.isNotBlank(language)) {
            sb.append("写作语言：").append(language).append("。");
        }
        sb.append("\n\n").append(section.render());
        if (StringUtils.isNotBlank(tail)) {
            sb.append("\n\n【本章已写原文结尾，紧接往下写】\n").append(tail);
        }
        sb.append("\n\n【写作提示】")
                .append("\n1. 严格保持与上一章/前文结尾的人、物、伤势、位置、修为和信息边界连续；视角人物只知道自己亲历、所见、所闻或当下合理推测的事。")
                .append("\n2. 放开写情绪张力和现场感官，重要冲突与爽点写透，日常过渡利落推进；动作或对白已经演出的意图、胜负和情绪直接推进下一拍，不要在动作后补写旁白复盘、战略演讲或心理账本总结，人物出场用言行带出脾气，不插播静态简历或世界百科。")
                .append("\n3. 对话写出各自脾气、身份与私心，冲突与对手戏放开写出试探、嘲讽、压迫感与反唇相讥，不要连续蹦出两三个字的冰冷电报式短句；非关键道具与场景一笔带过，只给推动剧情的细节特写；用贴合处境的具体反应替代“指节发白”“瞳孔骤缩”等套话；直接写“是什么”，避免在叙述中反复使用“不是A，而是B / 并非A，而是B / 不再是A，而是B”句式；正文纯文本，不用星号、井号等 Markdown 标记。")
                .append("\n4. ");
        if (StringUtils.isNotBlank(story.getPerspective())) {
            sb.append("叙述视角：").append(story.getPerspective().strip()).append("。");
        }
        sb.append(renderDirectRegisterNote(story, genre));
        if (!sectioned) {
            sb.append("整章篇幅自然落在 1500~2600 字为佳（写透核心冲突即自然收笔，略超亦可，严禁为凑字数插入无关跑腿、重复验物或对非关键道具逐项描写外观）。");
        } else if (!last) {
            sb.append("写到本段事件完成即收，无需章末总结或下场预告。");
        }
        if (StringUtils.isNotBlank(styleWarning)) {
            sb.append("\n").append(styleWarning.strip());
        }
        sb.append("\n\n直接输出纯文本，第一行写章节标题（纯标题文字，不要加“第X章”或标点装饰），空一行后直接写正文。不要输出 JSON、代码块或任何解释说明。");
        return sb.toString();
    }

    static String renderDirectRegisterNote(StoryContextEntity story, GenreType genre) {
        String themeAndStyle = (StringUtils.defaultString(story == null ? null : story.getTheme()) + "\n"
                + StringUtils.defaultString(story == null ? null : story.getStyle())).toLowerCase(java.util.Locale.ROOT);
        boolean classicalGenre = genre == GenreType.FANTASY || genre == GenreType.WUXIA || genre == GenreType.HISTORY
                || containsAny(themeAndStyle, "古言", "古风", "古代", "仙侠", "修仙", "修真", "武侠");
        String settingText = (themeAndStyle + "\n"
                + StringUtils.defaultString(story == null ? null : story.getWorldSetting()) + "\n"
                + StringUtils.defaultString(story == null ? null : story.getProtagonist())).toLowerCase(java.util.Locale.ROOT);
        boolean transmigrationOrSystem = containsAny(settingText, "穿越", "穿书", "系统", "现代灵魂", "现代人", "魂穿");
        if (classicalGenre && !transmigrationOrSystem) {
            return "古风/古代语境：叙述比喻与人物口语贴合古人认知，避免“肥皂”“电流”“特么”“我靠”“说拜拜”等现代工业/日化词、网络粗口与程序员术语。";
        }
        if (classicalGenre) {
            return "古风穿越/系统语境：旁白与本土人物保持古风认知，穿越者内心吐槽或系统元素可自然保留符合其出身的现代用语。";
        }
        return "现代/对应时代语境：自然使用符合该时代与行业的生活口语、器物与专业词汇；若属特定年代背景，避免超出当年时间线的晚近网络热梗。";
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 每次调用先严格解析，再尝试修复 JSON；轻装模式允许解析纯文本；失败时重试一次 LLM。
     * 正文是核心交付物、无降级替身——全部失败时抛出异常终止本批
     * （逐章检查点已保住此前章节，可用 resumeStoryDir 续写补齐）
     */
    private ChapterContentEntity generateChapterWithFallback(
            ArmoryCommandEntity requestParameter, ChapterPlanItemEntity item,
            PromptContext ctx, String userPrompt, int globalNo, Map<String, String> usedPromptMap) {
        return generateChapterWithFallback(requestParameter, item, ctx, userPrompt, globalNo, usedPromptMap, false);
    }

    private ChapterContentEntity generateChapterWithFallback(
            ArmoryCommandEntity requestParameter, ChapterPlanItemEntity item,
            PromptContext ctx, String userPrompt, int globalNo, Map<String, String> usedPromptMap,
            boolean allowPlainText) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String raw = llmInvokeService.invoke(
                        requestParameter.getStoryVO(), PromptScene.CHAPTER_CONTENT, ctx, userPrompt, usedPromptMap);
                ChapterContentEntity chapter = JsonParseFallback.parse(raw, json -> readChapterResponse(json, item));
                if (chapter == null && allowPlainText) {
                    chapter = readPlainTextChapterResponse(raw, item);
                }
                if (chapter != null) {
                    // 正文实体以全局章节号为准（落盘文件名/续写衔接均依赖），不信任模型输出的编号
                    chapter.setChapterNo(globalNo);
                    return chapter;
                }
                log.warn("第 {} 章正文第 {}/2 次输出无法解析", globalNo, attempt);
            } catch (Exception e) {
                log.warn("第 {} 章正文第 {}/2 次生成异常", globalNo, attempt, e);
            }
        }

        throw new AppException(ResponseCode.UN_ERROR.getCode(),
                "第 " + globalNo + " 章正文生成与解析彻底失败，本批终止；"
                        + "此前章节已通过逐章检查点落盘，可在请求中携带 resumeStoryDir 续写补齐后续章节");
    }

    static ChapterContentEntity readPlainTextChapterResponse(String raw, ChapterPlanItemEntity item) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        String text = stripFence(raw.strip());
        if (StringUtils.isBlank(text) || text.startsWith("{")) {
            return null;
        }
        String fallbackTitle = item == null ? null : item.getTitle();
        String title = ChapterTitleNormalizer.normalize(fallbackTitle);
        String body = text;
        int newline = text.indexOf('\n');
        if (newline > 0) {
            String firstLine = text.substring(0, newline).strip().replaceFirst("^#+\\s*", "");
            String rest = text.substring(newline + 1).strip();
            if (looksLikePlainTitle(firstLine) && !rest.isEmpty()) {
                String normalized = normalizePlainTitle(firstLine);
                if (StringUtils.isNotBlank(normalized)) {
                    title = normalized;
                }
                body = rest;
            }
        }
        return StringUtils.isBlank(body) ? null : ChapterContentEntity.builder().title(title).content(body).build();
    }

    private static String stripFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int newline = text.indexOf('\n');
        if (newline < 0) {
            return "";
        }
        String inner = text.substring(newline + 1);
        if (inner.endsWith("```")) {
            inner = inner.substring(0, inner.length() - 3);
        }
        return inner.strip();
    }

    private static boolean looksLikePlainTitle(String firstLine) {
        return !firstLine.isEmpty()
                && firstLine.length() <= 40
                && !firstLine.contains("`")
                && !firstLine.matches(".*[。，！？“”\"].*");
    }

    private static String normalizePlainTitle(String firstLine) {
        String normalized = ChapterTitleNormalizer.normalize(firstLine);
        if (normalized == null || normalized.isBlank()) {
            return normalized;
        }
        // 兼容中文数字章号前缀（如"第一章 造化道瞳"）
        String strippedChinese = normalized.replaceFirst("^第[零一二三四五六七八九十百千万两]+章[\\s:：、．.·\\-—]+", "").strip();
        return strippedChinese.isEmpty() ? normalized : strippedChinese;
    }

    private ChapterContentEntity readChapterResponse(String json, ChapterPlanItemEntity item) {
        try (var parser = CHAPTER_JSON.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return null;
            }
            String title = null;
            String body = null;
            String lastBody = null;
            int bodyLength = -1;
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                if (parser.nextToken() != JsonToken.VALUE_STRING) {
                    if ("content".equals(field)) {
                        lastBody = null;
                    }
                    parser.skipChildren();
                    continue;
                }
                if ("title".equals(field)) {
                    title = parser.getText();
                } else if ("content".equals(field)) {
                    // 模型偶尔重复输出 content，保留最长的非空根字段。
                    String candidate = parser.getText();
                    lastBody = candidate;
                    int length = ChapterLengthPolicy.effectiveCharacterCount(candidate);
                    if (length >= bodyLength && StringUtils.isNotBlank(candidate)) {
                        body = candidate;
                        bodyLength = length;
                    }
                }
            }
            // 必须读完外层对象，不能将裸引号之前的前缀作为完整正文。
            if (parser.currentToken() != JsonToken.END_OBJECT || parser.nextToken() != null || body == null) {
                return null;
            }
            // 末尾重复字段选错稿时，使用程序已有的计划标题。
            if (!body.equals(lastBody)) {
                title = item.getTitle();
            }
            return ChapterContentEntity.builder()
                    .title(ChapterTitleNormalizer.normalize(StringUtils.defaultIfBlank(title, item.getTitle())))
                    .content(body)
                    .build();
        } catch (IOException invalidJson) {
            return null;
        }
    }

    ChapterContract buildChapterContract(ChapterPlanItemEntity item, List<ChapterSummaryEntity> summaries) {
        String knowledge = CharacterStateProjector.render(summaries, item.getChapterNo(), item.getCharacters());
        return ChapterContract.of(item, summaries, knowledge);
    }

    private String renderWritingGuidance(int globalNo, StoryContextEntity storyContext, boolean sectioned) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n4. 内容有效性：按计划的重要性展开起因、行动、回应和后果，不要求每段新增情报。")
                .append("物品出现不等于需要外观特写；只有当前人物有理由注意，且细节能改变其判断、动作、情绪，揭示异常或状态，或体现其独特观察方式时才展开。已出现且没有变化的物品再次出现时直接称名，只有状态、人物感受或意义变化才重新展开。")
                .append("心理、环境、等待和后果消化可以承担阅读功能，不为了数量目标删掉必要信息或追加事件。")
                .append("交流服从人物意图和已知信息，让提问、回答、条件和改变决定的依据清楚相接。")
                .append("\n5. 核心能力机制按读者理解与计划需要说明，既有原理无需无效复述，不设全篇解释次数。")
                .append("\n6. 同类操作按实际差异与重要性选择详略，不按次数强制轮换角度、困难或情绪。")
                .append("\n7. 心理叙述、情绪直述和旁白可服务视角与作者风格，不设句数上限；重要事件不能靠空泛总结代替过程。")
                .append("\n8. 正文为纯文本，禁止使用任何 Markdown 标记：不得用星号加粗或强调（包括 * 与 **）、不得用井号标题、横线列表、下划线；人物的心理活动、系统面板/提示、技能名称、注入脑海的旁白一律用自然语言直接叙述，靠上下文呈现层次，不得用任何符号包裹或高亮；严禁在正文中出现章节编号（如\"第30章\"\"第十二章\"）或\"本章/上一章/下一章\"等作者层面的元信息。回忆过往事件时，必须用时间/地点/事件指代，绝对不能用章节编号：错误示例\"第12章他在阵眼核心发现的\"→正确写法\"十二天前在阵眼核心发现的\"；错误示例\"第28章袭击事件后\"→正确写法\"上次遇袭之后\"；错误示例\"第6章那个模糊的感知\"→正确写法\"之前那次模糊的感知\"。角色说出口的对白使用作者约定的合法成对引号，未指定时用中文双引号“”；上述禁令只针对 Markdown 标记与作者层面元信息，绝不适用于对白；严禁为了遵守纯文本要求而丢弃对白引号。不要输出解释。")
                .append(sectioned ? "" : "\n9. 保留本章具体的结尾落点，未决、闭合或余韵均合法；不强制悬念或画面动作。")
                .append(renderRegisterGuidance(storyContext))
                .append("\n11. 检查无意义的情节复用，但因果上必要的同类解法可以再次使用，不要求每章换解法、增加对手或伤害。")
                .append("\n\n请严格按照以下 JSON 格式输出：")
                .append("\n{\"chapterNo\":").append(globalNo)
                .append(",\"title\":\"章节标题\",\"content\":\"").append(sectioned ? "当前段正文" : "章节正文").append("\"}")
                .append("\n只返回这一个对象。content 内对白默认用中文引号，使用英文双引号时按 JSON 规则转义。");
        return sb.toString();
    }

    /** 正文、审校与修订使用相同的语域口径。 */
    private String renderRegisterGuidance(StoryContextEntity context) {
        return "\n10. " + cn.novel.yonren.domain.novel.service.armory.prompt.WritingContractPolicy.renderRegister(context);
    }

    private static GateResult bindDeliveryReview(GateResult gateResult, ChapterPlanItemEntity item,
                                                 ChapterContentEntity chapterContent, boolean enforced) {
        GateResult current = gateResult == null ? GateResult.of(List.of(), 0, 0) : gateResult;
        return current.withDeliveryReview(DeliveryReviewPolicy.ensureBound(
                current.deliveryReview(), item, ChapterManuscript.of(chapterContent), enforced));
    }

    /** 拒绝交付必须保全非正式稿并异常退出；失败信息不能冒称草稿已经写入。 */
    private AppException preserveBlockedDelivery(DefaultArmoryFactory.DynamicContext dynamicContext,
                                                 ChapterPlanItemEntity item, ChapterManuscript manuscript,
                                                 GateResult gateResult, int totalChapters) {
        DeliveryReview review = gateResult.deliveryReview();
        String stage = review.unknown() ? GenerationJob.DELIVERY_REVIEW_UNAVAILABLE
                : GenerationJob.DELIVERY_REVIEW_FAILED;
        String reason = stage + "：第 " + manuscript.chapterNo() + " 章未获交付许可；"
                + deliveryDiagnostics(review);
        GenerationJob job = dynamicContext.getJob();
        Path storyDir = dynamicContext.getStoryDir();
        Path runDir = dynamicContext.getRunDir();
        if (job != null) {
            job.updateProgress(stage, manuscript.chapterNo(), totalChapters,
                    storyDir == null ? null : storyDir.getFileName().toString());
        }
        final Path saved;
        try {
            saved = storyRepository.saveUncommittedChapter(storyDir, runDir, manuscript, item, gateResult, reason);
            if (saved == null) {
                throw new IllegalStateException("未提交草稿仓储没有返回保存地址");
            }
        } catch (Exception e) {
            String cause = e instanceof AppException app ? app.getInfo() : e.getMessage();
            String message = reason + "；非正式草稿保全失败，不能确认已保存；目标 run=" + runDir
                    + "；" + cause + "。本章未正式提交，此前已提交章节保留。";
            if (job != null) job.setErrorMessage(message);
            persistJobStatus(dynamicContext);
            log.error(message, e);
            return new AppException(ResponseCode.UN_ERROR.getCode(), message, e);
        }
        String message = reason + "；非正式草稿已保存：" + saved.toAbsolutePath()
                + "。本章未正式提交，此前已提交章节保留；可从第 " + manuscript.chapterNo() + " 章重新续写。";
        if (job != null) job.setErrorMessage(message);
        persistJobStatus(dynamicContext);
        log.warn(message);
        return new AppException(ResponseCode.UN_ERROR.getCode(), message);
    }

    /** OBSERVE/UNKNOWN 不进入合格样本，单独保留完整交付报告；观测失败不反噬允许继续的模式。 */
    private void recordDeliveryReview(DefaultArmoryFactory.DynamicContext dynamicContext,
                                      ChapterPlanItemEntity item, ChapterContentEntity chapterContent,
                                      GateResult gateResult) {
        DeliveryReview review = gateResult.deliveryReview();
        if (!review.passed()) {
            log.warn("第 {} 章交付核验 {}（enforced={}）：{}", chapterContent.getChapterNo(),
                    review.status(), review.enforced(), deliveryDiagnostics(review));
        }
        try {
            storyRepository.recordDeliveryReview(dynamicContext.getStoryDir(), dynamicContext.getRunDir(),
                    ChapterManuscript.of(chapterContent), item, gateResult);
        } catch (Exception e) {
            log.warn("第 {} 章交付观测记录写入失败，未影响正式提交许可；{}",
                    chapterContent.getChapterNo(), deliveryDiagnostics(review), e);
        }
    }

    private static String deliveryDiagnostics(DeliveryReview review) {
        StringJoiner details = new StringJoiner("；");
        details.add("status=" + review.status());
        details.add("manuscriptHash=" + review.manuscriptHash());
        details.add("planHash=" + review.planHash());
        for (String diagnostic : review.diagnostics()) {
            if (StringUtils.isNotBlank(diagnostic)) details.add(diagnostic);
        }
        for (var check : review.checks()) {
            if (check.verdict() != cn.novel.yonren.domain.novel.model.valobj.plan.CausalVerdict.Status.PASS) {
                details.add("[" + check.targetId() + "] " + check.failureType() + "：" + check.reason());
            }
        }
        return details.toString();
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
     * 此前这里有一份私有实现（"最近 5 章摘要前 80 字 + 首个节拍 goal→结果"），
     * 规划层则完全没有对应块——重复的源头在规划层，正文层再劝也拦不住。
     * 现两处共用同一实现，避免"规划说没重复、正文说重复"的口径分裂
     * （项目里已因"两份判据各自演化"出过几次问题）。窗口沿用 5 章，与改造前一致，便于对照。
     * 返回 null 表示无历史可参考（第 1 章或摘要为空）。
     */
    private String buildUsedPatternBlacklist(List<ChapterSummaryEntity> summaries, int chapterNo) {
        return UsedPatternPolicy.render(summaries, chapterNo, UsedPatternPolicy.BODY_LOOKBACK);
    }

    /** 上章同版且有证据的审校意见：按完整条目回灌，避免截断适用条件后变成强制任务。 */
    static String renderReviewFeedback(List<ChapterIssueEntity> semanticMinors) {
        if (semanticMinors == null || semanticMinors.isEmpty()) {
            return null;
        }
        List<ChapterIssueEntity> readable = semanticMinors.stream()
                .filter(i -> i != null && "MINOR".equalsIgnoreCase(i.getSeverity())
                        && StringUtils.isNotBlank(i.getDescription()) && StringUtils.isNotBlank(i.getEvidence())
                        && ("character".equalsIgnoreCase(i.getDimension())
                        || "aesthetic".equalsIgnoreCase(i.getDimension())
                        || "pacing".equalsIgnoreCase(i.getDimension())))
                .limit(3)
                .toList();
        if (readable.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("【上章审校反馈·参考】以下意见有上章原文依据，不代表本章存在同样问题。"
                + "先核对本章契约、人物信息与场景功能，只在相同问题确实重现时参考；"
                + "不为纠正上章而增加事件、对白、代价或改变作者风格。引文存在不等于审美判断必然成立。");
        int appended = 0;
        for (ChapterIssueEntity issue : readable) {
            String entry = "\n- [" + issue.getDimension() + "] " + issue.getDescription()
                    + "\n  上章原文：" + issue.getEvidence()
                    + (StringUtils.isBlank(issue.getSuggestion()) ? "" : "\n  可选建议：" + issue.getSuggestion());
            if (sb.length() + entry.length() > 2400) {
                continue;
            }
            sb.append(entry);
            appended++;
        }
        return appended == 0 ? null : sb.toString();
    }
}
