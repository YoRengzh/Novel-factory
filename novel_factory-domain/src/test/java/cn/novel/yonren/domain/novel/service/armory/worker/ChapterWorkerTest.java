package cn.novel.yonren.domain.novel.service.armory.worker;

import cn.novel.yonren.domain.novel.service.armory.writer.ChapterWritingPlan;
import cn.novel.yonren.domain.novel.service.armory.writer.ChapterWritingPlanService;
import cn.novel.yonren.types.enums.PromptScene;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.config.StoryProperties.CausalityProperties.Mode;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.valobj.character.CharacterEffect;
import cn.novel.yonren.domain.novel.model.valobj.plan.CausalVerdict;
import cn.novel.yonren.domain.novel.model.valobj.quality.DeliveryReview;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import cn.novel.yonren.domain.novel.service.armory.quality.DeliveryReviewPolicy;
import cn.novel.yonren.domain.novel.service.armory.writer.WriterSampleService;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterCommitState;
import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.entity.StyleStatEntity;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.domain.novel.service.armory.llm.LlmInvokeService;
import cn.novel.yonren.domain.novel.service.armory.memory.ChapterMemoryService;
import cn.novel.yonren.domain.novel.service.armory.memory.ChapterSummaryService;
import cn.novel.yonren.domain.novel.service.armory.memory.CharacterStateProjector;
import cn.novel.yonren.domain.novel.service.armory.memory.ForeshadowScheduleService;
import cn.novel.yonren.domain.novel.service.armory.memory.LedgerAdjudicateService;
import cn.novel.yonren.domain.novel.service.armory.memory.QualityDebtService;
import cn.novel.yonren.domain.novel.service.armory.memory.RollingOutlineService;
import cn.novel.yonren.domain.novel.service.armory.memory.StoryMemoryService;
import cn.novel.yonren.domain.novel.service.armory.memory.ConsistencyIndexService;
import cn.novel.yonren.domain.novel.model.entity.ConsistencyIndexEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.types.exception.AppException;
import cn.novel.yonren.domain.novel.service.armory.memory.StyleStatService;
import cn.novel.yonren.domain.novel.service.armory.plan.ChapterPlanPromptService;
import cn.novel.yonren.domain.novel.service.armory.plan.ChapterPlanSegmentPlanner;
import cn.novel.yonren.domain.novel.service.armory.quality.QualityGate;
import cn.novel.yonren.domain.novel.service.armory.candidate.CandidateSampleService;
import cn.novel.yonren.domain.novel.model.valobj.quality.BatchHealthReport;
import cn.novel.yonren.domain.novel.service.armory.quality.BatchHealthService;
import cn.novel.yonren.domain.novel.model.valobj.quality.GateResult;
import cn.novel.yonren.domain.novel.service.armory.candidate.ChapterCandidateService;
import cn.novel.yonren.domain.novel.service.job.GenerationJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.MockitoAnnotations;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 逐章生成循环测试：job=null 与拆分前行为一致、协作取消（首章前/一章后）、
 * 进度上报与每章状态落盘
 */
class ChapterWorkerTest {

    private static final String RAW_CHAPTER = "{\"chapterNo\":1,\"title\":\"第一章\",\"content\":\"正文内容\"}";
    private static final Path STORY_DIR = Paths.get("docs/workspace/stories/20260903-story-0001");

    @Mock
    private ForeshadowScheduleService foreshadowScheduleService;

    @Mock
    private LlmInvokeService llmInvokeService;

    @Mock
    private ChapterSummaryService chapterSummaryService;

    @Mock
    private LedgerAdjudicateService ledgerAdjudicateService;

    @Mock
    private ChapterMemoryService chapterMemoryService;

    @Mock
    private StyleStatService styleStatService;

    @Mock
    private QualityDebtService qualityDebtService;

    @Mock
    private BatchHealthService batchHealthService;

    // 段落信息增量审校：默认 mock 返回 null = 不改写，既有用例行为不变
    @Mock
    private cn.novel.yonren.domain.novel.service.armory.audit.ParagraphDensityAuditService
            paragraphDensityAuditService;

    @Mock
    private CandidateSampleService candidateSampleService;

    @Mock
    private QualityGate qualityGate;

    @Mock
    private ChapterCandidateService chapterCandidateService;

    @Mock
    private ChapterWritingPlanService chapterWritingPlanService;

    @Mock
    private StoryMemoryService storyMemoryService;

    @Mock
    private ConsistencyIndexService consistencyIndexService;

    @Mock
    private IStoryRepository storyRepository;

    /** 惰性分段规划：prompt 组装与规划执行均下沉到 plan 包服务，worker 只做编排与追加 */
    @Mock
    private ChapterPlanPromptService planPromptService;

    @Mock
    private ChapterPlanSegmentPlanner planSegmentPlanner;

    @Mock
    private cn.novel.yonren.domain.novel.service.armory.memory.StageExitReviewService stageExitReviewService;

    @Mock
    private cn.novel.yonren.domain.novel.service.armory.memory.StageReportService stageReportService;

    @Mock
    private cn.novel.yonren.domain.novel.service.armory.memory.FinaleReviewService finaleReviewService;

    @Mock
    private cn.novel.yonren.domain.novel.service.armory.memory.ForeshadowSettlementService foreshadowSettlementService;

    /** 全书质量评分：每章调一次（触发门禁在服务内），此处仅需替身，不校验其内部行为 */
    @Mock
    private cn.novel.yonren.domain.novel.service.armory.memory.QualityReviewService qualityReviewService;

    /** 前缀总预算守门：无状态纯函数，用真实实现（spy）保持"块拼接语义"不变；
     *  其淘汰/截断规则由 PromptBudgetGuardTest 覆盖，此处不重复校验 */
    @org.mockito.Spy
    private cn.novel.yonren.domain.novel.service.armory.prompt.PromptBudgetGuard promptBudgetGuard =
            new cn.novel.yonren.domain.novel.service.armory.prompt.PromptBudgetGuard(
                    new cn.novel.yonren.domain.novel.config.PromptBudgetProperties());

    /** 卷链语义：用真实实现（spy）——章号所属卷的选取由 RollingOutlineServiceTest 覆盖 */
    @org.mockito.Spy
    private RollingOutlineService rollingOutlineService = new RollingOutlineService();

    @org.mockito.Spy
    private StoryProperties storyProperties = new StoryProperties();

    @Mock
    private WriterSampleService writerSampleService;

    @InjectMocks
    private ChapterWorker chapterWorker;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        when(consistencyIndexService.rebuild(anyList(), any())).thenReturn(ConsistencyIndexEntity.builder().build());
        // retrieveWithOutcome 返回 record，Mockito 默认给 null（只有 List 才默认给空表），
        // 故必须显式桩成"未降级的无命中"——否则 worker 在取 hits() 时 NPE。
        // 全参用 any()：buildContentQuery 也是 mock，返回 null，用 anyString() 会匹配不上
        when(storyMemoryService.retrieveWithOutcome(any(), any(), any(), any(), any()))
                .thenReturn(StoryMemoryService.RecallOutcome.of(List.of()));
        when(qualityGate.auditEnabled()).thenReturn(false);
        when(qualityGate.auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any(), any()))
                .thenReturn(GateResult.of(List.of(), 0, 0));
        when(chapterCandidateService.challenge(any(), any(), any(), anyString(), any(), any(), anyList(), any(), anyInt(), any()))
                .thenAnswer(inv -> inv.getArgument(5));
        when(chapterMemoryService.buildSecrecyGuard(anyList(), any()))
                .thenReturn(ChapterMemoryService.NO_SECRECY);
        when(chapterMemoryService.buildMemoryBlocks(anyList(), any(), anyList(), anyList(), any(), any(), anyList(), any())).thenReturn(List.of());
        when(chapterMemoryService.renderLedgerPrompt(anyList())).thenReturn("");
        when(chapterMemoryService.buildForeshadowContextList(anyList(), anyInt())).thenReturn(List.of());
        when(chapterWritingPlanService.prepare(any(), any(), anyInt(), anyString(), anyList()))
                .thenReturn(new ChapterWritingPlan(List.of(writingSection(1, "交付药签"))));
        when(styleStatService.empty()).thenReturn(StyleStatEntity.builder().build());
        when(styleStatService.renderWarning(any())).thenReturn("");
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), anyMap())).thenReturn(RAW_CHAPTER);
        when(chapterSummaryService.summarize(any(), any(), anyString(), anyInt(), anyString(), anyList(), any()))
                .thenReturn(summary(1));
        when(storyRepository.createStoryDirectory()).thenReturn(STORY_DIR);
        // 批末体检：只报不动作，测试里固定返回"健康"结论（其自身逻辑由 BatchHealthServiceTest 覆盖）。
        // 用 any() 而非 anyList()：ctx.getStageBlueprints() 可能为 null，anyList() 不匹配 null
        // 注意：ChapterWorker 调的是 **7 参重载**（候选统计 + 结算台账 + 章目标 + 质量趋势），桩必须同元数，
        // 否则静默不匹配返回 null → 调用点 NPE。**改 assess 的元数时必须同步改这里。**
        // 排期打标：默认无排期表 ⇒ 无状态变化（与未启用排期时行为一致）
        when(foreshadowScheduleService.stamp(any(), any()))
                .thenReturn(ForeshadowScheduleService.StampResult.EMPTY);
        when(batchHealthService.assess(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new BatchHealthReport(0, List.of(), BatchHealthReport.Level.OK, List.of()));
        // 候选统计：默认无样本（stats.triggered=0 → 体检不产生候选指标）
        when(candidateSampleService.readStats(any()))
                .thenReturn(new cn.novel.yonren.domain.novel.service.armory.candidate.CandidateSampleService.CandidateStats(0, 0));
    }

    private static ChapterWritingPlan.Section writingSection(int number, String events) {
        return new ChapterWritingPlan.Section("跟随沈砚", "他在药铺门口，手中没有药签。",
                List.of(new ChapterWritingPlan.Scene(number, events)));
    }

    @Test
    void generateAllSeparatesPlanningContextFromWriterAndReferenceQuery() throws Exception {
        var ctx = context(null);
        ctx.setStoryContext("下卷隐藏答案红色石匣");
        ctx.setPlanResidualViolations("相邻章将有人自认藏匣");
        when(chapterMemoryService.buildSecrecyGuard(anyList(), any()))
                .thenReturn(new ChapterMemoryService.SecrecyGuard(List.of("红色石匣"), "禁泄：红色石匣"));

        chapterWorker.generateAll(command(), ctx);

        var planning = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(chapterWritingPlanService).prepare(any(), any(), eq(1), planning.capture(), eq(List.of("红色石匣")));
        assertTrue(planning.getValue().contains("下卷隐藏答案红色石匣"));
        assertTrue(planning.getValue().contains("相邻章将有人自认藏匣"));

        var writer = org.mockito.ArgumentCaptor.forClass(String.class);
        var reference = org.mockito.ArgumentCaptor.forClass(cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext.class);
        verify(llmInvokeService, org.mockito.Mockito.atLeastOnce())
                .invoke(any(), eq(PromptScene.CHAPTER_CONTENT), reference.capture(), writer.capture(), anyMap());
        for (String prompt : writer.getAllValues()) {
            assertTrue(prompt.contains("交付药签"));
            assertFalse(prompt.contains("红色石匣"));
            assertFalse(prompt.contains("相邻章将有人自认藏匣"));
        }
        assertTrue(reference.getAllValues().stream().allMatch(r -> r.getChapterBrief().equals("交付药签")));
    }

    @Test
    void legacyMemoryLedgerBlocksAreAppendedOnlyOnce() throws Exception {
        when(chapterMemoryService.buildMemoryBlocks(anyList(), any(), any(), any(), any(), any(), anyList(), any()))
                .thenReturn(List.of(
                        new cn.novel.yonren.domain.novel.service.armory.prompt.PromptBudgetGuard.Block(
                                "近章摘要", 3, true, "最近一次正文已经确认的事实"),
                        new cn.novel.yonren.domain.novel.service.armory.prompt.PromptBudgetGuard.Block(
                                ChapterMemoryService.LEDGER_BLOCK_LABEL, 4, true, "药签仍在柜中"),
                        new cn.novel.yonren.domain.novel.service.armory.prompt.PromptBudgetGuard.Block(
                                ChapterMemoryService.FORESHADOW_BLOCK_LABEL, 4, true, "旧约尚未兑现")));

        chapterWorker.generateAll(command(), context(null));

        var blocks = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(promptBudgetGuard, atLeastOnce()).assembleChapterPrefix(anyInt(), blocks.capture());
        for (Object captured : blocks.getAllValues()) {
            var supplied = (List<cn.novel.yonren.domain.novel.service.armory.prompt.PromptBudgetGuard.Block>) captured;
            assertEquals(1, supplied.stream().filter(b -> ChapterMemoryService.LEDGER_BLOCK_LABEL.equals(b.label())).count());
            assertEquals(1, supplied.stream().filter(b -> ChapterMemoryService.FORESHADOW_BLOCK_LABEL.equals(b.label())).count());
            assertEquals(ChapterMemoryService.FORESHADOW_BLOCK_LABEL, supplied.get(supplied.size() - 1).label());
        }
    }

    @Test
    void directWriterContextSkipsMiddleModelAndKeepsSceneProse() throws Exception {
        storyProperties.setWriterContextMode(StoryProperties.WriterContextMode.DIRECT);
        var ctx = context(null);
        ctx.setChapterPlanAggregate(ChapterPlanAggregate.builder().storyId("story-1")
                .chapters(List.of(sceneItem(1))).build());

        chapterWorker.generateAll(command(), ctx);

        verify(chapterWritingPlanService, never()).prepare(any(), any(), anyInt(), anyString(), anyList());
        var writer = org.mockito.ArgumentCaptor.forClass(String.class);
        var reference = ArgumentCaptor.forClass(cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext.class);
        verify(llmInvokeService, atLeastOnce())
                .invoke(any(), eq(PromptScene.CHAPTER_CONTENT), reference.capture(), writer.capture(), anyMap());
        assertTrue(writer.getAllValues().stream().anyMatch(p -> p.contains("沈砚要借钥匙")));
        assertTrue(reference.getAllValues().stream().allMatch(p -> p.getChapterBrief().contains("沈砚要借钥匙")));
    }

    private static ChapterPlanItemEntity sceneItem(int no) {
        return ChapterPlanItemEntity.builder()
                .chapterNo(no)
                .title("第" + no + "章")
                .goal("本章目标")
                .schemaVersion(2)
                .characters(List.of("沈砚"))
                .scenes(List.of(new cn.novel.yonren.domain.novel.model.valobj.plan.ScenePlan(
                        "s-" + no, List.of("沈砚"), "沈砚要借钥匙", "管事说没带钥匙", List.of(),
                        "管事拒绝", "推进", List.of(), "药铺", null)))
                .build();
    }

    @Test
    void writeDraftSendsEachSectionAloneAndContinuesFromActualProse() throws Exception {
        var earlier = writingSection(1, "沈砚借钥匙，管事说没带。");
        var later = writingSection(2, "沈砚发现红色石匣，第一次知道管事偷了账本。");
        var story = StoryContextEntity.builder().theme("悬疑").outline("下一卷进盐仓").build();
        when(chapterMemoryService.tailByParagraph(eq("管事朝门口喊了一声。"), anyInt()))
                .thenReturn("管事朝门口喊了一声。");
        when(llmInvokeService.invoke(any(), eq(PromptScene.CHAPTER_CONTENT), any(), anyString(), anyMap()))
                .thenReturn("{\"title\":\"借钥匙\",\"content\":\"管事朝门口喊了一声。\"}",
                            "{\"title\":\"找到匣子\",\"content\":\"沈砚拉开抽屉。\"}");
        var prompts = new java.util.HashMap<String, String>();

        var draft = chapterWorker.writeDraft(command(), item(1), story, 1, 2,
                new ChapterWritingPlan(List.of(earlier, later)), null, prompts, () -> {});

        var sent = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(llmInvokeService, org.mockito.Mockito.times(2))
                .invoke(any(), eq(PromptScene.CHAPTER_CONTENT), any(), sent.capture(), anyMap());
        assertFalse(sent.getAllValues().get(0).contains("红色石匣"));
        assertFalse(sent.getAllValues().get(1).contains("下一卷进盐仓"));
        assertTrue(sent.getAllValues().get(1).contains("管事朝门口喊了一声。"));
        assertTrue(sent.getAllValues().get(1).contains("红色石匣"));
        assertEquals("管事朝门口喊了一声。\n\n沈砚拉开抽屉。", draft.chapter().getContent());
        assertEquals("借钥匙", draft.chapter().getTitle());
        assertNull(draft.userPrompt());
    }

    @Test
    void writingPromptRetainsObjectFocusAndGenreLanguage() {
        var section = writingSection(1, "取药");
        String urban = chapterWorker.buildWritingPrompt(StoryContextEntity.builder()
                .theme("都市重生").style("年代成长").build(), null, 2, section, null, null, false, true);
        String fantasy = chapterWorker.buildWritingPrompt(StoryContextEntity.builder()
                .theme("仙侠修真").style("热血").build(), null, 2, section, null, null, false, true);

        assertTrue(urban.contains("【语言与语域契约】"));
        assertTrue(urban.contains("物品出现不等于需要外观特写"));
        assertTrue(urban.contains("当前人物有理由注意"));
        assertFalse(urban.contains("灵气、阵纹、符箓"));
        assertTrue(fantasy.contains("灵气、阵纹、符箓"));
    }

    @Test
    void planningContractUsesRelevantStructuredKnowledge() {
        String suspicion = "陈九看见了黑气，怀疑林长渊藏着东西。";
        String unrelated = "周管事得知粮价涨了。";
        var manuscript = new ChapterManuscript(1, "柴房", suspicion + unrelated);
        var summary = ChapterSummaryEntity.builder().chapterNo(1).manuscriptHash(manuscript.hash())
                .characterStates(List.of(new ChapterSummaryEntity.StateEntry("陈九", "陈九确认铁片的来历", suspicion)))
                .characterEffects(List.of(
                        new CharacterEffect("陈九", CharacterEffect.Kind.BELIEF,
                                "林长渊藏了东西", CharacterEffect.State.SUSPECTED,
                                "林长渊藏着东西", suspicion, "亲眼看见黑气", null),
                        new CharacterEffect("周管事", CharacterEffect.Kind.KNOWLEDGE, "粮价",
                                CharacterEffect.State.ESTABLISHED, "粮价涨了", unrelated, "听商人说", null)))
                .pendingCharacterEffects(List.of(new CharacterEffect("陈九", CharacterEffect.Kind.KNOWLEDGE,
                        "铁片来历", CharacterEffect.State.ESTABLISHED, "铁片来自矿洞", "未能定位", "推断", null)))
                .build();
        CharacterStateProjector.bind(summary, manuscript, List.of());
        var item = ChapterPlanItemEntity.builder().chapterNo(2).title("矿柴").characters(List.of("陈九"))
                .suspenseBeat("陈九仍只怀疑林长渊藏着东西，没有见到铁片。").build();
        String prompt = chapterWorker.buildChapterContract(item, List.of(summary)).knowledgeBoundary();

        assertTrue(prompt.contains("BELIEF/SUSPECTED"));
        assertTrue(prompt.contains("亲眼看见黑气"));
        assertTrue(prompt.contains("UNRESOLVED 1 项"));
        assertFalse(prompt.contains("陈九确认铁片的来历"));
        assertFalse(prompt.contains("铁片来自矿洞"));
        assertFalse(prompt.contains("粮价涨了"));
    }

    @Test
    void enforceDeliveryFailurePreservesDraftBeforeAnyOfficialSideEffect() throws Exception {
        var ctx = deliveryContext(Mode.ENFORCE, new GenerationJob("job-delivery-fail"));
        stubDeliveryGate(DeliveryReview.Status.ISSUE);
        Path draft = ctx.getRunDir().resolve("uncommitted/chapter-0001-test.json");
        when(storyRepository.saveUncommittedChapter(any(), any(), any(), any(), any(), anyString())).thenReturn(draft);

        AppException failure = assertThrows(AppException.class, () -> chapterWorker.generateAll(command(), ctx));

        assertTrue(failure.getInfo().contains("非正式草稿已保存"));
        assertTrue(failure.getInfo().contains("第 1 章重新续写"));
        assertEquals(GenerationJob.DELIVERY_REVIEW_FAILED, ctx.getJob().getCurrentStage());
        assertTrue(ctx.getChapterContents().isEmpty());
        verify(storyRepository).saveUncommittedChapter(eq(STORY_DIR), eq(ctx.getRunDir()), any(), any(), any(), anyString());
        verify(storyRepository, never()).commitChapter(any(), any(), any());
        verify(chapterSummaryService, never()).summarize(any(), any(), anyString(), anyInt(), anyString(), anyList(), any());
        verify(qualityDebtService, never()).settle(any(), any());
        verify(writerSampleService, never()).record(any(), any(), anyInt(), any(), any(), any(), any(), any());
        verify(styleStatService, never()).merge(any(), anyString(), anyString());
        verify(llmInvokeService, times(1)).invoke(any(), any(), any(), anyString(), anyMap());
    }

    @Test
    void enforceUnknownDoesNotTriggerCandidateOrSettleOldDebts() throws Exception {
        var ctx = deliveryContext(Mode.ENFORCE, new GenerationJob("job-delivery-unknown"));
        stubDeliveryGate(DeliveryReview.Status.UNKNOWN);
        when(storyRepository.saveUncommittedChapter(any(), any(), any(), any(), any(), anyString()))
                .thenReturn(ctx.getRunDir().resolve("uncommitted/unknown.json"));
        when(qualityGate.auditEnabled()).thenReturn(true);

        assertThrows(AppException.class, () -> chapterWorker.generateAll(command(), ctx));

        assertEquals(GenerationJob.DELIVERY_REVIEW_UNAVAILABLE, ctx.getJob().getCurrentStage());
        verify(chapterCandidateService, never()).challenge(any(), any(), any(), anyString(), any(), any(), anyList(), any(), anyInt(), any());
        verify(qualityDebtService, never()).settle(any(), any());
        verify(storyRepository, never()).commitChapter(any(), any(), any());
        verify(chapterSummaryService, never()).summarize(any(), any(), anyString(), anyInt(), anyString(), anyList(), any());
    }

    @Test
    void draftSaveFailureIsReportedWithoutPretendingTheDraftWasSaved() throws Exception {
        var ctx = deliveryContext(Mode.ENFORCE, new GenerationJob("job-draft-disk"));
        stubDeliveryGate(DeliveryReview.Status.ISSUE);
        when(storyRepository.saveUncommittedChapter(any(), any(), any(), any(), any(), anyString()))
                .thenThrow(new java.io.IOException("disk full"));

        AppException failure = assertThrows(AppException.class, () -> chapterWorker.generateAll(command(), ctx));

        assertTrue(failure.getInfo().contains("保全失败"));
        assertTrue(failure.getInfo().contains("disk full"));
        assertFalse(failure.getInfo().contains("非正式草稿已保存"));
        verify(storyRepository, never()).commitChapter(any(), any(), any());
    }

    @Test
    void priorCommittedChapterSurvivesTheNextUnverifiedChapter() throws Exception {
        var ctx = deliveryContext(Mode.ENFORCE, new GenerationJob("job-second-fails"));
        when(qualityGate.auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any(), any()))
                .thenAnswer(inv -> deliveryGate(inv.getArgument(1), inv.getArgument(2),
                        (int) inv.getArgument(5) == 1 ? DeliveryReview.Status.PASS : DeliveryReview.Status.ISSUE, true));
        when(chapterSummaryService.summarize(any(), any(), anyString(), anyInt(), anyString(), anyList(), any()))
                .thenAnswer(inv -> summary(inv.getArgument(3)));
        when(storyRepository.saveUncommittedChapter(any(), any(), any(), any(), any(), anyString()))
                .thenReturn(ctx.getRunDir().resolve("uncommitted/chapter-0002.json"));

        assertThrows(AppException.class, () -> chapterWorker.generateAll(command(), ctx));

        assertEquals(1, ctx.getChapterContents().size());
        assertEquals(1, ctx.getChapterContents().get(0).getChapterNo());
        assertEquals(1, ctx.getChapterSummaries().size());
        verify(storyRepository, times(1)).commitChapter(eq(STORY_DIR), any(), any());
        verify(chapterSummaryService, times(1)).summarize(any(), any(), anyString(), anyInt(), anyString(), anyList(), any());
        assertEquals(2, ctx.getJob().getCurrentChapter(), "当前尝试章不是已提交章数");
        ArgumentCaptor<ChapterManuscript> saved = ArgumentCaptor.forClass(ChapterManuscript.class);
        verify(storyRepository).saveUncommittedChapter(any(), any(), saved.capture(), any(), any(), anyString());
        assertEquals(2, saved.getValue().chapterNo());
    }

    @Test
    void finalCandidateSwapCannotReuseOriginalDeliveryPass() throws Exception {
        var ctx = deliveryContext(Mode.ENFORCE, null);
        stubDeliveryGate(DeliveryReview.Status.PASS);
        when(chapterCandidateService.challenge(any(), any(), any(), anyString(), any(), any(), anyList(), any(), anyInt(), any()))
                .thenAnswer(inv -> {
                    ChapterContentEntity chapter = inv.getArgument(4);
                    chapter.setContent("候选只有宣布成功。");
                    return inv.getArgument(5);
                });
        when(storyRepository.saveUncommittedChapter(any(), any(), any(), any(), any(), anyString()))
                .thenReturn(ctx.getRunDir().resolve("uncommitted/stale.json"));

        AppException failure = assertThrows(AppException.class, () -> chapterWorker.generateAll(command(), ctx));

        assertTrue(failure.getInfo().contains("DELIVERY_VERSION_MISMATCH"));
        verify(storyRepository, never()).commitChapter(any(), any(), any());
    }

    @Test
    void observeUnknownIsVisibleButNotAContentDebtOrQualifiedSample() throws Exception {
        var ctx = deliveryContext(Mode.OBSERVE, null);
        stubDeliveryGate(DeliveryReview.Status.UNKNOWN);
        when(qualityGate.auditEnabled()).thenReturn(true);
        chapterWorker.generateAll(command(), ctx);

        assertEquals(2, ctx.getChapterContents().size());
        verify(storyRepository, times(2)).recordDeliveryReview(any(), any(), any(), any(), any());
        verify(storyRepository, times(2)).commitChapter(any(), any(), any());
        verify(qualityDebtService, never()).settle(any(), any());
        verify(writerSampleService, never()).record(any(), any(), anyInt(), any(), any(), any(), any(), any());
        verify(styleStatService, never()).extractFingerprints(anyString());
        verify(chapterCandidateService, never()).challenge(any(), any(), any(), anyString(), any(), any(), anyList(), any(), anyInt(), any());
    }

    @Test
    void conflictingAuditConfigurationFailsBeforeAnyGeneration() {
        storyProperties.getCausality().setAuditMode(Mode.ENFORCE);
        storyProperties.getAudit().setEnabled(false);
        assertThrows(IllegalArgumentException.class, () -> chapterWorker.generateAll(command(), context(null)));
        verify(llmInvokeService, never()).invoke(any(), any(), any(), anyString(), anyMap());
    }

    private DefaultArmoryFactory.DynamicContext deliveryContext(Mode mode, GenerationJob job) {
        storyProperties.getCausality().setAuditMode(mode);
        var ctx = context(job);
        ctx.setStoryDir(STORY_DIR);
        ctx.setRunDir(STORY_DIR.resolve("generation-records/run-0001"));
        for (var plan : ctx.getChapterPlanAggregate().getChapters()) plan.setKeyEvents(List.of("交付药签"));
        return ctx;
    }

    private void stubDeliveryGate(DeliveryReview.Status status) {
        when(qualityGate.auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any(), any()))
                .thenAnswer(inv -> deliveryGate(inv.getArgument(1), inv.getArgument(2), status,
                        storyProperties.getCausality().getAuditMode() == Mode.ENFORCE));
    }

    private static GateResult deliveryGate(ChapterPlanItemEntity plan, ChapterContentEntity chapter,
                                           DeliveryReview.Status status, boolean enforce) {
        var request = new DeliveryReviewPolicy.Request(DeliveryReviewPolicy.contract(plan), ChapterManuscript.of(chapter), enforce);
        DeliveryReview report;
        if (status == DeliveryReview.Status.UNKNOWN) {
            report = DeliveryReviewPolicy.unavailable(request, "缺少可靠的核验结果");
        } else {
            var issue = status == DeliveryReview.Status.ISSUE;
            var check = new CausalVerdict(null, issue ? CausalVerdict.Status.ISSUE : CausalVerdict.Status.PASS,
                    chapter.getContent(), null, issue ? "正文仅有结果，没有计划承诺的交接过程" : "正文已交代交接过程",
                    issue ? "只补齐交接和确认的必要过程" : null,
                    issue ? CausalVerdict.FailureType.RESULT_WITHOUT_CAUSE : null,
                    null, null, null, null, "event:1", "keyEvents[0]", "交付药签");
            report = DeliveryReviewPolicy.consume(request, List.of(check));
        }
        return GateResult.of(DeliveryReviewPolicy.blockingIssues(report), 0, 0).withDeliveryReview(report);
    }

    @Test
    void continuationCountsOnlyTwoPrecedingFinalChaptersForParagraphEditing() throws Exception {
        var ctx = context(null);
        ctx.setChapterOffset(2);
        ctx.setStoryDir(STORY_DIR);
        ctx.getChapterPlanAggregate().setChapters(List.of(item(3), item(4)));
        when(storyRepository.readChapter(STORY_DIR, 1)).thenReturn(
                new cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity(1, "甲", "两息过去。三息过去。又等了十来息。"));
        when(storyRepository.readChapter(STORY_DIR, 2)).thenReturn(
                new cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity(2, "乙", "过了几息。"));

        chapterWorker.generateAll(command(), ctx);

        verify(paragraphDensityAuditService).suggest(any(), anyString(), eq(3), eq(4));
        verify(paragraphDensityAuditService).suggest(any(), anyString(), eq(4), eq(1));
    }

    @Test
    void generateAll_jobNull_generatesAllChapters_noStatusPersistence() throws Exception {
        DefaultArmoryFactory.DynamicContext ctx = context(null);

        chapterWorker.generateAll(command(), ctx);

        assertEquals(2, ctx.getChapterContents().size());
        assertEquals(2, ctx.getChapterSummaries().size());
        verify(llmInvokeService, times(2)).invoke(any(), any(), any(), anyString(), anyMap());
        verify(storyRepository, never()).writeJobStatus(any(), any());
        // 接线校验：每章完成后必须叫一次全书质量评分（"每 N 章评一次"的门禁在服务内部，
        // worker 侧只管逐章调用）。此处只验调用次数与参数形状，评分内容由 QualityReviewServiceTest 覆盖。
        verify(qualityReviewService, times(2)).reviewWindow(any(), any(), any(), anyInt());
    }

    @Test
    void generateAll_planAdjustmentRequired_stopsAfterCurrentChapterAndKeepsCommit() throws Exception {
        // 计划与事实冲突不是写作缺陷：当前章照常提交，但不继续消耗后续章节。
        when(qualityGate.auditEnabled()).thenReturn(true);
        when(qualityGate.auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any(), any()))
                .thenAnswer(inv -> GateResult.of(List.of(), 0, 0)
                        .withPlanAdjustmentRequired("冻结事实中没有药签"));
        GenerationJob job = new GenerationJob("job-plan-conflict");
        DefaultArmoryFactory.DynamicContext ctx = context(job);

        chapterWorker.generateAll(command(), ctx);

        assertEquals(1, ctx.getChapterContents().size(), "计划需调整时应停在当前章，不再生成下一章");
        assertEquals(GenerationJob.PLAN_ADJUSTMENT_REQUIRED, job.getCurrentStage());
        assertEquals(1, job.getCurrentChapter());
        assertEquals(2, job.getTotalChapters());
        assertTrue(job.getErrorMessage().contains("冻结事实中没有药签"));
        verify(chapterCandidateService, never()).challenge(
                any(), any(), any(), anyString(), any(), any(), anyList(), any(), anyInt(), any());
        verify(llmInvokeService, times(1)).invoke(any(), any(), any(), anyString(), anyMap());
        verify(storyRepository, times(1)).commitChapter(eq(STORY_DIR), any(), any(ChapterCommitState.class));
    }

    @Test
    void generateAll_cancelBeforeFirstChapter_generatesZeroChapters() throws Exception {
        GenerationJob job = new GenerationJob("job-1");
        job.requestCancel();
        DefaultArmoryFactory.DynamicContext ctx = context(job);

        chapterWorker.generateAll(command(), ctx);

        assertTrue(ctx.getChapterContents().isEmpty(), "取消已受理时不应生成任何章节");
        verify(llmInvokeService, never()).invoke(any(), any(), any(), anyString(), anyMap());
        verify(storyRepository, never()).writeJobStatus(any(), any());
    }

    @Test
    void generateAll_cancelAfterFirstChapter_generatesExactlyOneChapter() throws Exception {
        GenerationJob job = new GenerationJob("job-2");
        when(chapterSummaryService.summarize(any(), any(), anyString(), anyInt(), anyString(), anyList(), any()))
                .thenAnswer(invocation -> {
                    job.requestCancel();
                    return summary(1);
                });
        DefaultArmoryFactory.DynamicContext ctx = context(job);

        chapterWorker.generateAll(command(), ctx);

        assertEquals(1, ctx.getChapterContents().size(), "取消信号置位后应完成当前章即停");
        verify(llmInvokeService, times(1)).invoke(any(), any(), any(), anyString(), anyMap());
        verify(storyRepository, times(1)).commitChapter(eq(STORY_DIR), any(), any(ChapterCommitState.class));
        verify(storyRepository, times(1)).writeJobStatus(eq(STORY_DIR), same(job));
        assertEquals(Integer.valueOf(1), job.getCurrentChapter());
    }

    @Test
    void generateAll_jobPresent_reportsProgressAndPersistsStatusPerChapter() throws Exception {
        GenerationJob job = new GenerationJob("job-3");
        DefaultArmoryFactory.DynamicContext ctx = context(job);

        chapterWorker.generateAll(command(), ctx);

        assertEquals("CHAPTER_GENERATION", job.getCurrentStage());
        assertEquals(Integer.valueOf(2), job.getCurrentChapter());
        assertEquals(Integer.valueOf(2), job.getTotalChapters());
        assertEquals("20260903-story-0001", job.getStoryDirName());
        assertEquals(2, job.getChapterDurations().size());
        verify(storyRepository, times(2)).writeJobStatus(eq(STORY_DIR), same(job));
    }

    @Test
    void generateAll_lazySegmentPlanning_plansNextSegmentAtBoundary() throws Exception {
        // 首段 1-2 章已预规划，批次 4 章：生成推进到第 3 章时用最新记忆惰性规划 3-4 段
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), anyMap())).thenReturn(RAW_CHAPTER);
        when(chapterSummaryService.summarize(any(), any(), anyString(), anyInt(), anyString(), anyList(), any()))
                .thenAnswer(invocation -> summary(invocation.getArgument(3, Integer.class)));
        when(planPromptService.buildPlanPrompt(any(), eq(3), eq(4), any(), eq(true), any(), any()))
                .thenReturn("segment-2-plan-prompt");
        ChapterPlanAggregate segment2Plan = ChapterPlanAggregate.builder()
                .chapters(new java.util.ArrayList<>(List.of(
                        plannedItem(3), plannedItem(4))))
                .build();

        ArmoryCommandEntity command = ArmoryCommandEntity.builder()
                .storyVO(new StoryVO())
                .storyContextEntity(StoryContextEntity.builder()
                        .theme("都市异能").style("逆袭打脸爽文").chapterCount(4).build())
                .build();
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .chapterPlanAggregate(ChapterPlanAggregate.builder()
                        .storyId("story-1")
                        .chapters(new java.util.ArrayList<>(List.of(item(1), item(2))))
                        .build())
                .planSegments(new java.util.ArrayList<>(List.of(
                        new RollingOutlineService.StageSegment(1, 2, StageBlueprintEntity.builder()
                                .stageNo(1).startChapter(1).endChapter(2).build()),
                        new RollingOutlineService.StageSegment(3, 4, StageBlueprintEntity.builder()
                                .stageNo(2).startChapter(3).endChapter(4).build()))))
                .plannedSegmentEnd(2)
                .plannedChapterCount(2)
                .usedPromptMap(new HashMap<>())
                .build();

        // 段规划经规划执行器（与链式路径同一实现）：打桩并验证调用契约
        when(planSegmentPlanner.planSegment(any(), any(), anyString(), eq(3), eq(4), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ChapterPlanSegmentPlanner.PlannedSegment("segment-2-raw", segment2Plan, null));

        chapterWorker.generateAll(command, ctx);

        verify(planSegmentPlanner, times(1)).planSegment(any(), any(), anyString(), eq(3), eq(4), any(), any(), any(), any(), any(), any(), any());

        // 4 章全部生成（首段 2 章 + 惰性规划段 2 章），段计划已合并进整批计划
        assertEquals(4, ctx.getChapterContents().size());
        assertEquals(4, ctx.getChapterSummaries().size());
        assertEquals(4, ctx.getChapterPlanAggregate().getChapters().size());
        assertEquals(4, ctx.getPlannedSegmentEnd());
        // 惰性规划恰好一次：prompt 组装（含段边界与末段 finale 标记参数）
        verify(planPromptService, times(1)).buildPlanPrompt(any(), eq(3), eq(4), any(), eq(true), any(), any());
        // 内容生成 4 次（4 章正文；段规划走 planner 桩，不占内容调用次数）
        verify(llmInvokeService, times(4)).invoke(any(), any(), any(), anyString(), anyMap());
    }

    @Test
    void generateAll_finaleAuditFailed_appendReworkWindow_andNotDeclareComplete() throws Exception {
        // 收官蓝图：EPILOGUE + finalVolumeDeclared + beats 已清 + 退出条件全 MET → isStoryComplete=true
        StageBlueprintEntity blueprint = StageBlueprintEntity.builder()
                .stageNo(5)
                .startChapter(1)
                .endChapter(1)
                .storyPhase("EPILOGUE")
                .finalVolumeDeclared(true)
                .completedFinaleBeats(List.of("平定兽潮"))
                .remainingFinaleBeats(new java.util.ArrayList<>())
                .exitConditions(List.of("兽潮平息"))
                .exitResults(List.of(new StageBlueprintEntity.ExitConditionResult(
                        "兽潮平息", true, 1, "兽潮退去", null)))
                .build();
        ChapterPlanAggregate plan = ChapterPlanAggregate.builder()
                .storyId("story-1")
                .chapters(new java.util.ArrayList<>(List.of(
                        ChapterPlanItemEntity.builder().chapterNo(1)
                                .chapterType(cn.novel.yonren.types.enums.ChapterType.FINALE).build())))
                .build();
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .chapterPlanAggregate(plan)
                .stageBlueprints(new java.util.ArrayList<>(List.of(blueprint)))
                .maxChapterCount(100)
                .usedPromptMap(new HashMap<>())
                .build();

        // 终局审查：不通过、0 轮返工 → 触发写回末卷返工
        when(finaleReviewService.review(any(), any(), any(), any(), any())).thenReturn(
                cn.novel.yonren.domain.novel.model.entity.FinaleAuditEntity.builder()
                        .overallPass(false)
                        .forcedClose(false)
                        .reworkTasks(List.of("收束灾后世界状态"))
                        .dimensions(List.of(cn.novel.yonren.domain.novel.model.entity.FinaleAuditEntity.DimensionResult.builder()
                                .dimension("WORLD_STATE").met(false).note("灾后未收束").build()))
                        .build());

        chapterWorker.generateAll(command(), ctx);

        // 拒绝宣告完结：未把返工头寸写尽前不停止，追加 REPENT_BATCH(3) 个返工章
        assertEquals(1 + 3, ctx.getChapterPlanAggregate().getChapters().size());
        // 返工轮数 +1、缺口注入新窗口的 remainingFinaleBeats
        assertEquals(1, blueprint.getFinaleAudit().getReworkCount());
        StageBlueprintEntity rework = ctx.getStageBlueprints().get(1);
        assertEquals(Integer.valueOf(2), rework.getStartChapter());
        assertEquals(Integer.valueOf(4), rework.getEndChapter());
        assertEquals(List.of("收束灾后世界状态"), rework.getRemainingFinaleBeats());
        assertTrue(rework.getRemainingFinaleBeats().isEmpty() == Boolean.FALSE);
        verify(finaleReviewService, times(1)).review(any(), any(), any(), any(), any());
    }

    @Test
    void generateAll_unresolvedLengthIssueNoLongerRejects_persistsAndRecordsDebt() throws Exception {
        // 字数不足不再拒收（拒收只会逼模型注水）：章节正常落盘，未闭环问题记为质量债
        when(qualityGate.auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any(), any()))
                .thenReturn(GateResult.of(List.of(ChapterIssueEntity.builder().dimension("length").severity("BLOCKING").build()), 0, 0));
        DefaultArmoryFactory.DynamicContext ctx = context(null);

        chapterWorker.generateAll(command(), ctx);

        verify(storyRepository, times(2)).commitChapter(eq(STORY_DIR), any(), any(ChapterCommitState.class));
        verify(chapterSummaryService, times(2)).summarize(any(), any(), anyString(), anyInt(), anyString(), anyList(), any());
    }

    @Test
    void repeatedContentKeyCannotReplaceCompleteBodyWithAShortFragment() {
        String complete = "苏尘核对药签，确认根须完好后把青纹兰送到丹房。".repeat(55);
        String raw = "{\"chapterNo\":1,\"title\":\"原题\",\"content\":"
                + com.alibaba.fastjson2.JSON.toJSONString(complete) + ",\"content\":\"短片段\"}";
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), anyMap())).thenReturn(raw);
        ChapterPlanItemEntity item = ChapterPlanItemEntity.builder().chapterNo(1).title("计划标题").build();
        var chapter = org.springframework.test.util.ReflectionTestUtils.<cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity>invokeMethod(
                chapterWorker, "generateChapterWithFallback", command(), item,
                cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext.builder().build(), "计划", 1, new HashMap<String, String>());
        assertEquals(complete, chapter.getContent());
        assertEquals("计划标题", chapter.getTitle());
    }

    @Test
    void generateAll_recordsDensitySignalsOnSummary() throws Exception {
        // 密度信号机械入账：摘要携带 validChars/keyEventCount，供规划层判断供给是否太稀
        when(qualityGate.auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any(), any()))
                .thenReturn(GateResult.of(List.of(), 0, 0));
        when(chapterSummaryService.summarize(any(), any(), anyString(), anyInt(), anyString(), anyList(), any()))
                .thenAnswer(inv -> ChapterSummaryEntity.builder()
                        .chapterNo(inv.getArgument(3)).summary("摘要").build());
        DefaultArmoryFactory.DynamicContext ctx = context(null);

        chapterWorker.generateAll(command(), ctx);

        List<ChapterSummaryEntity> summaries = ctx.getChapterSummaries();
        assertEquals(2, summaries.size());
        for (ChapterSummaryEntity s : summaries) {
            assertEquals(4, s.getValidChars().intValue(), "有效字数应机械统计自正文");
            assertEquals(0, s.getKeyEventCount().intValue(), "计划未给关键事件时应记 0");
        }
        // 账本挂起裁决必须逐章调用（在摘要落盘前），否则本章记忆前缀拿不到救回的事实
        verify(ledgerAdjudicateService, times(2)).adjudicate(any(), any(), anyString());
    }

    @Test
    void generateAll_bindsManuscriptHashAndRejectsUnlocatableCharacterEvidence() throws Exception {
        // 人物状态增量必须绑定到具体正文版本；定位不到证据的条目转 pending，不进权威状态。
        when(qualityGate.auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any(), any()))
                .thenReturn(GateResult.of(List.of(), 0, 0));
        when(chapterSummaryService.summarize(any(), any(), anyString(), anyInt(), anyString(), anyList(), any()))
                .thenAnswer(inv -> ChapterSummaryEntity.builder()
                        .chapterNo(inv.getArgument(3)).summary("摘要")
                        .characterEffects(new java.util.ArrayList<>(List.of(
                                new cn.novel.yonren.domain.novel.model.valobj.character.CharacterEffect(
                                        "角色甲",
                                        cn.novel.yonren.domain.novel.model.valobj.character.CharacterEffect.Kind.GOAL,
                                        "复仇", cn.novel.yonren.domain.novel.model.valobj.character.CharacterEffect.State.ESTABLISHED,
                                        "向宗门复仇", "正文里根本不存在的句子", null, null))))
                        .build());
        DefaultArmoryFactory.DynamicContext ctx = context(null);

        chapterWorker.generateAll(command(), ctx);

        for (ChapterSummaryEntity s : ctx.getChapterSummaries()) {
            assertNotNull(s.getManuscriptHash(), "摘要必须携带本章正文哈希，否则人物增量无法确认来源");
            assertFalse(s.getManuscriptHash().isBlank());
            assertTrue(s.getCharacterEffects() == null || s.getCharacterEffects().isEmpty(),
                    "证据无法在正文定位的条目不得进入权威人物状态");
            assertNotNull(s.getPendingCharacterEffects(), "被拒条目应隔离到 pending 供核对");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void generateAll_mechanicalStyleMinor_isRecordedAsDebt() throws Exception {
        // 严重度分层的配套要求：降档后的文风 MINOR 不触发修订、不触发候选、不计入 grade，
        // 但必须落质量债——否则「降档」等于把问题直接丢掉，观测层与规划层回灌都拿不到信号
        when(qualityGate.auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any(), any()))
                .thenReturn(GateResult.of(List.of(), 0,
                        List.of(ChapterIssueEntity.builder()
                                .dimension("aesthetic").severity("MINOR")
                                .description("万能副词密度超标").build()), 0));
        DefaultArmoryFactory.DynamicContext ctx = context(null);

        chapterWorker.generateAll(command(), ctx);

        ArgumentCaptor<ChapterCommitState> captor = ArgumentCaptor.forClass(ChapterCommitState.class);
        verify(storyRepository, atLeastOnce()).commitChapter(any(), any(), captor.capture());
        var last = captor.getValue().debts();
        boolean recorded = last.stream().flatMap(d -> d.getIssues().stream())
                .anyMatch(i -> "aesthetic".equals(i.getDimension())
                        && "MINOR".equals(i.getSeverity()));
        assertTrue(recorded, "降档的文风 MINOR 必须落质量债，否则信号被丢弃");
    }

    @Test
    void generateAll_finaleAuditPasses_declaresComplete() throws Exception {
        StageBlueprintEntity blueprint = StageBlueprintEntity.builder()
                .stageNo(5)
                .startChapter(1)
                .endChapter(1)
                .storyPhase("EPILOGUE")
                .finalVolumeDeclared(true)
                .completedFinaleBeats(List.of("平定兽潮"))
                .remainingFinaleBeats(new java.util.ArrayList<>())
                .exitConditions(List.of("兽潮平息"))
                .exitResults(List.of(new StageBlueprintEntity.ExitConditionResult(
                        "兽潮平息", true, 1, "兽潮退去", null)))
                .build();
        ChapterPlanAggregate plan = ChapterPlanAggregate.builder()
                .storyId("story-1")
                .chapters(new java.util.ArrayList<>(List.of(
                        ChapterPlanItemEntity.builder().chapterNo(1)
                                .chapterType(cn.novel.yonren.types.enums.ChapterType.FINALE).build())))
                .build();
        GenerationJob job = new GenerationJob("finale-pass");
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .chapterPlanAggregate(plan)
                .stageBlueprints(new java.util.ArrayList<>(List.of(blueprint)))
                .maxChapterCount(100)
                .usedPromptMap(new HashMap<>())
                .job(job)
                .build();

        when(finaleReviewService.review(any(), any(), any(), any(), any())).thenReturn(
                cn.novel.yonren.domain.novel.model.entity.FinaleAuditEntity.builder()
                        .overallPass(true)
                        .forcedClose(false)
                        .dimensions(java.util.List.of())
                        .build());

        chapterWorker.generateAll(command(), ctx);

        // 通过 → 宣告完结，不再追加返工章
        assertEquals(1, ctx.getChapterPlanAggregate().getChapters().size());
        assertEquals(true, job.getCurrentStage().equals("STORY_COMPLETED"));
    }

    private static ChapterPlanItemEntity plannedItem(int no) {
        return ChapterPlanItemEntity.builder()
                .chapterNo(no)
                .title("第" + no + "章")
                .goal("本章目标")
                .keyEvents(List.of("关键事件"))
                .build();
    }

    private ArmoryCommandEntity command() {
        StoryVO storyVO = new StoryVO();
        StoryVO.Module module = new StoryVO.Module();
        module.setEmbeddingApi(new StoryVO.Module.EmbeddingApi());
        storyVO.setModule(module);
        return ArmoryCommandEntity.builder()
                .storyVO(storyVO)
                .storyContextEntity(StoryContextEntity.builder()
                        .theme("都市异能")
                        .style("逆袭打脸爽文")
                        .build())
                .build();
    }

    private DefaultArmoryFactory.DynamicContext context(GenerationJob job) {
        return DefaultArmoryFactory.DynamicContext.builder()
                .chapterPlanAggregate(ChapterPlanAggregate.builder()
                        .storyId("story-1")
                        .chapters(List.of(item(1), item(2)))
                        .build())
                .usedPromptMap(new HashMap<>())
                .job(job)
                .build();
    }

    private static ChapterPlanItemEntity item(int no) {
        return ChapterPlanItemEntity.builder()
                .chapterNo(no)
                .title("第" + no + "章")
                .goal("本章目标")
                .build();
    }

    private static ChapterSummaryEntity summary(int no) {
        return ChapterSummaryEntity.builder()
                .chapterNo(no)
                .title("摘要")
                .continuityConflicts(List.of())
                .build();
    }

    @Test
    void renderReviewFeedback_filtersDimensionsAndCapsThree() {
        List<ChapterIssueEntity> minors = List.of(
                issue("consistency", "设定甲与前文冲突"),
                issue("character", "配角沦为工具人"),
                issue("aesthetic", "定义旁白连用"),
                issue("pacing", "节奏偏快"));
        minors.forEach(item -> item.setEvidence("他把药签交给执事。"));
        String feedback = ChapterWorker.renderReviewFeedback(minors);

        assertNotNull(feedback);
        assertTrue(feedback.contains("审校反馈"));
        assertTrue(feedback.contains("工具人"));
        assertTrue(feedback.contains("他把药签交给执事。"));
        assertFalse(feedback.contains("相反的写法"));
        assertFalse(feedback.contains("设定甲"), "一致性类问题走账本通道，不进反馈块");
        assertNull(ChapterWorker.renderReviewFeedback(List.of()));
        assertNull(ChapterWorker.renderReviewFeedback(List.of(issue("pacing", "无证据判断"))));
    }

    @Test
    void reviewFeedbackKeepsConditionsAndSuggestionIntact() {
        String description = "此处连续确认同一个条件。".repeat(12)
                + "但如果本章是在向第三人核实，新一轮询问可以保留。";
        ChapterIssueEntity advice = issue("pacing", description);
        advice.setEvidence("三日之内交回药签。");
        advice.setSuggestion("只在没有新增阅读功能时压缩；不要修改已有事实。");
        String feedback = ChapterWorker.renderReviewFeedback(List.of(advice));
        assertTrue(feedback.contains(description), "不能截断后半段适用条件");
        assertTrue(feedback.contains(advice.getSuggestion()));
        assertTrue(feedback.contains("本章契约"));
        assertFalse(feedback.contains("必须避免同类问题"));
    }

    private ChapterIssueEntity issue(String dimension, String description) {
        ChapterIssueEntity issue = new ChapterIssueEntity();
        issue.setDimension(dimension);
        issue.setSeverity("MINOR");
        issue.setDescription(description);
        return issue;
    }


    // ==================== 移植自旧系统的闸门用例 ====================

    /**
     * 回归：模型输出**重复 content 键**（先完整正文、后补空串）时不得判死。
     * JSON 库对重复键取后一个值（空串），旧实现认为"解析成功"直接返回空稿；
     * 现在空正文视为失败，并由抢救逻辑取首个非空 content 拿回真正文。
     */
    @Test
    void generateAll_duplicateContentKey_salvagesFirstNonBlankContent() throws Exception {
        String duplicated = "{\"chapterNo\":1,\"title\":\"第一章\","
                + "\"content\":\"这是真正文内容，长度足够。\",\"content\":\"\",\"note\":\"\"}";
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), anyMap()))
                .thenReturn(duplicated);

        GenerationJob job = new GenerationJob("job-dupkey");
        DefaultArmoryFactory.DynamicContext ctx = context(job);

        chapterWorker.generateAll(command(), ctx);

        // 落盘正文必须是抢救回来的那段（不是空串）
        org.mockito.ArgumentCaptor<cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity> captor =
                org.mockito.ArgumentCaptor.forClass(cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity.class);
        verify(storyRepository, org.mockito.Mockito.atLeastOnce())
                .commitChapter(any(), captor.capture(), any(ChapterCommitState.class));
        assertTrue(captor.getAllValues().stream()
                        .anyMatch(c -> c.getContent() != null && c.getContent().contains("这是真正文内容")),
                "重复键时必须抢救首个非空 content，而不是落盘空稿");
    }

    @Test
    void generateAll_keepsDialogueAndEndingAfterRepairingBareQuotesAndNewlines() throws Exception {
        String body = "他推开门。\n\n\"你也在？\"陈九转过来。\n\n他们听见了第三声锣。";
        String response = "{\"chapterNo\":1,\"title\":\"柴房\",\"content\":\"" + body + "\"}";
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), anyMap())).thenReturn(response);

        var ctx = context(new GenerationJob("job-quotes"));
        ctx.getChapterPlanAggregate().setChapters(List.of(item(1)));
        chapterWorker.generateAll(command(), ctx);

        var captor = org.mockito.ArgumentCaptor.forClass(
                cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity.class);
        verify(storyRepository).commitChapter(any(), captor.capture(), any(ChapterCommitState.class));
        assertEquals(body, captor.getValue().getContent());
        verify(llmInvokeService).invoke(any(), any(), any(), anyString(), anyMap());
    }

    @Test
    void generateAll_retriesAnUnclosedBodyInsteadOfSubmittingItsPrefix() throws Exception {
        String truncated = "{\"chapterNo\":1,\"title\":\"柴房\",\"content\":\"他推开门。\"你也在？\"陈九";
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), anyMap()))
                .thenReturn(truncated, "{\"chapterNo\":\"第一章\",\"title\":\"柴房\","
                        + "\"content\":\"他放下斧头，走出柴房。\",\"note\":{\"content\":\"这段嵌套内容更长但不能覆盖正文。\"}}");

        var ctx = context(new GenerationJob("job-truncated"));
        ctx.getChapterPlanAggregate().setChapters(List.of(item(1)));
        chapterWorker.generateAll(command(), ctx);

        var captor = org.mockito.ArgumentCaptor.forClass(
                cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity.class);
        verify(storyRepository).commitChapter(any(), captor.capture(), any(ChapterCommitState.class));
        assertEquals(1, captor.getValue().getChapterNo());
        assertEquals("他放下斧头，走出柴房。", captor.getValue().getContent());
        verify(llmInvokeService, org.mockito.Mockito.times(2)).invoke(any(), any(), any(), anyString(), anyMap());
    }

    @Test
    void directWriterModeSendsSlimPromptAcceptsPlainTextAndRejectsBrokenJson() throws Exception {
        storyProperties.setWriterContextMode(StoryProperties.WriterContextMode.DIRECT);
        var ctx = context(null);
        ctx.setChapterPlanAggregate(ChapterPlanAggregate.builder().storyId("story-1")
                .chapters(List.of(sceneItem(1))).build());
        when(llmInvokeService.invoke(any(), eq(PromptScene.CHAPTER_CONTENT), any(), anyString(), anyMap()))
                .thenReturn("{\"chapterNo\":1,\"title\":\"残缺",
                        "# 第1章 崖缝惊变\n\n他抓紧石缝，冷汗滑落。\n\n“是谁？”声音从身后传来。");

        chapterWorker.generateAll(command(), ctx);

        var promptCaptor = ArgumentCaptor.forClass(String.class);
        var pcCaptor = ArgumentCaptor.forClass(cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext.class);
        verify(llmInvokeService, org.mockito.Mockito.times(2))
                .invoke(any(), eq(PromptScene.CHAPTER_CONTENT), pcCaptor.capture(), promptCaptor.capture(), anyMap());

        assertTrue(pcCaptor.getAllValues().stream().allMatch(cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext::isDirectWriterMode));
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("直接输出纯文本"));
        assertFalse(prompt.contains("严格按照以下 JSON 格式输出"));
        assertFalse(prompt.contains("让读者自己理解它的意义"));

        var savedCaptor = ArgumentCaptor.forClass(cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity.class);
        verify(storyRepository).commitChapter(any(), savedCaptor.capture(), any(ChapterCommitState.class));
        assertEquals("崖缝惊变", savedCaptor.getValue().getTitle());
        assertTrue(savedCaptor.getValue().getContent().startsWith("他抓紧石缝"));
    }

    @Test
    void directWriterModeStillAcceptsValidJsonResponseAndNormalizesPlainText() throws Exception {
        storyProperties.setWriterContextMode(StoryProperties.WriterContextMode.DIRECT);
        var ctx = context(null);
        ctx.setChapterPlanAggregate(ChapterPlanAggregate.builder().storyId("story-1")
                .chapters(List.of(sceneItem(1))).build());
        when(llmInvokeService.invoke(any(), eq(PromptScene.CHAPTER_CONTENT), any(), anyString(), anyMap()))
                .thenReturn("{\"chapterNo\":1,\"title\":\"兼容标题\",\"content\":\"这是JSON正文。\"}");

        chapterWorker.generateAll(command(), ctx);

        var savedCaptor = ArgumentCaptor.forClass(cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity.class);
        verify(storyRepository).commitChapter(any(), savedCaptor.capture(), any(ChapterCommitState.class));
        assertEquals("兼容标题", savedCaptor.getValue().getTitle());
        assertEquals("这是JSON正文。", savedCaptor.getValue().getContent());

        var parsed = ChapterWorker.readPlainTextChapterResponse("第一章 夜探\n\n风吹过药园。", sceneItem(1));
        assertEquals("夜探", parsed.getTitle());
        assertEquals("风吹过药园。", parsed.getContent());

        var noTitle = ChapterWorker.readPlainTextChapterResponse("第一句就已经有句号，这是正文。\n\n第二段。", sceneItem(1));
        assertEquals("第1章", noTitle.getTitle());
        assertTrue(noTitle.getContent().startsWith("第一句就已经有句号"));

        org.junit.jupiter.api.Assertions.assertNull(
                ChapterWorker.readPlainTextChapterResponse("```json\n{\"chapterNo\":1,\"content\":\"残缺", sceneItem(1)));
    }

    @Test
    void directWriterRoutesRegisterByGenreAndTransmigrationWithoutOvergeneralizing() {
        var section = writingSection(1, "交锋");

        String nativeFantasy = chapterWorker.buildWritingPrompt(StoryContextEntity.builder()
                .theme("玄幻修真").style("传统升级爽文").worldSetting("各大修仙宗门林立").build(),
                "中文", 1, section, null, null, false, true, true);
        assertTrue(nativeFantasy.contains("古风/古代语境"));
        assertTrue(nativeFantasy.contains("肥皂"));
        assertTrue(nativeFantasy.contains("1500~2600 字"));

        String transmigrationFantasy = chapterWorker.buildWritingPrompt(StoryContextEntity.builder()
                .theme("玄幻修真").style("轻松").protagonist("现代程序员魂穿成外门杂役，觉醒签到系统").build(),
                "中文", 1, section, null, null, false, true, true);
        assertTrue(transmigrationFantasy.contains("古风穿越/系统语境"));
        assertTrue(transmigrationFantasy.contains("穿越者内心吐槽或系统元素可自然保留"));
        assertFalse(transmigrationFantasy.contains("避免“肥皂”"));

        String modernUrban = chapterWorker.buildWritingPrompt(StoryContextEntity.builder()
                .theme("都市职场").style("现实烟火气").build(),
                "中文", 1, section, null, null, false, true, true);
        assertTrue(modernUrban.contains("现代/对应时代语境"));
        assertFalse(modernUrban.contains("古风/古代语境"));
        assertFalse(modernUrban.contains("肥皂"));
        assertTrue(modernUrban.contains("1500~2600 字"));

        String sectioned = chapterWorker.buildWritingPrompt(StoryContextEntity.builder()
                .theme("都市职场").build(),
                "中文", 1, section, null, null, true, false, true);
        assertFalse(sectioned.contains("1500~2600 字"), "分段非整章输出不应硬塞整章字数上限");
        assertTrue(sectioned.contains("写到本段事件完成即收"));
    }

    @Test
    void directWriterModeInvokesProseScalpelBeforeCommit() throws Exception {
        storyProperties.setWriterContextMode(StoryProperties.WriterContextMode.DIRECT);
        var scalpel = org.mockito.Mockito.mock(cn.novel.yonren.domain.novel.service.armory.revise.ProseScalpelService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(chapterWorker, "proseScalpelService", scalpel);
        when(llmInvokeService.invoke(any(), eq(PromptScene.CHAPTER_CONTENT), any(), anyString(), anyMap()))
                .thenReturn("初稿标题\n\n苏尘不是犹豫，而是小心探出头。");
        when(scalpel.trim(any(), any(), eq("苏尘不是犹豫，而是小心探出头。"), eq(1)))
                .thenReturn("苏尘小心探出头。");

        var ctx = context(null);
        ctx.setChapterPlanAggregate(ChapterPlanAggregate.builder().storyId("story-1")
                .chapters(List.of(sceneItem(1))).build());
        chapterWorker.generateAll(command(), ctx);

        var savedCaptor = ArgumentCaptor.forClass(cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity.class);
        verify(storyRepository).commitChapter(any(), savedCaptor.capture(), any(ChapterCommitState.class));
        assertEquals("苏尘小心探出头。", savedCaptor.getValue().getContent());
    }

    @Test
    void resolveDirectPovCharacterFallsBackToOutlineWhenProtagonistOmitsName() {
        var chapterItem = ChapterPlanItemEntity.builder().chapterNo(1)
                .characters(List.of("周贵", "苏尘")).build();

        var explicitProtagonist = StoryContextEntity.builder()
                .perspective("第三人称限知视角")
                .protagonist("身具下品杂灵根的杂役弟子")
                .outline("周贵负责看守灵药园，主角苏尘在后山得到残瞳。")
                .build();
        assertEquals("苏尘", ChapterWorker.resolveDirectPovCharacter(explicitProtagonist, chapterItem));

        var earliestInOutline = StoryContextEntity.builder()
                .perspective("第三人称限知视角")
                .protagonist("杂役弟子")
                .outline("苏尘夜入后山采药，次日向周贵交差。")
                .build();
        assertEquals("苏尘", ChapterWorker.resolveDirectPovCharacter(earliestInOutline, chapterItem));

        var omniscient = StoryContextEntity.builder()
                .perspective("全知视角")
                .protagonist("苏尘")
                .outline("苏尘与周贵")
                .build();
        org.junit.jupiter.api.Assertions.assertNull(ChapterWorker.resolveDirectPovCharacter(omniscient, chapterItem));
    }
}
