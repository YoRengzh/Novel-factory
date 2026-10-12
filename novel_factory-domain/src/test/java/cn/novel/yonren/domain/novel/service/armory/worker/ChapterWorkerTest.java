package cn.novel.yonren.domain.novel.service.armory.worker;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
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
import cn.novel.yonren.domain.novel.service.armory.quality.BatchHealthReport;
import cn.novel.yonren.domain.novel.service.armory.quality.BatchHealthService;
import cn.novel.yonren.domain.novel.service.armory.quality.GateResult;
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
    private cn.novel.yonren.domain.novel.service.armory.beats.ChapterBeatsService chapterBeatsService;

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
                    new cn.novel.yonren.domain.novel.model.valobj.properties.PromptBudgetProperties());

    /** 卷链语义：用真实实现（spy）——章号所属卷的选取由 RollingOutlineServiceTest 覆盖 */
    @org.mockito.Spy
    private RollingOutlineService rollingOutlineService = new RollingOutlineService();

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
        when(qualityGate.auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any()))
                .thenReturn(GateResult.of(List.of(), 0, 0));
        when(chapterCandidateService.challenge(any(), any(), any(), anyString(), any(), any(), anyList(), any(), anyInt(), any()))
                .thenAnswer(inv -> inv.getArgument(5));
        when(chapterMemoryService.buildSecrecyGuard(anyList(), any()))
                .thenReturn(ChapterMemoryService.NO_SECRECY);
        when(chapterMemoryService.buildMemoryBlocks(anyList(), any(), anyList(), anyList(), any(), any(), anyList(), any())).thenReturn(List.of());
        when(chapterMemoryService.renderLedgerPrompt(anyList())).thenReturn("");
        when(chapterMemoryService.buildForeshadowContextList(anyList(), anyInt())).thenReturn(List.of());
        when(styleStatService.empty()).thenReturn(StyleStatEntity.builder().build());
        when(styleStatService.renderWarning(any())).thenReturn("");
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), anyMap())).thenReturn(RAW_CHAPTER);
        when(chapterSummaryService.summarize(any(), any(), anyString(), anyInt(), anyString(), anyList()))
                .thenReturn(summary(1));
        when(storyRepository.createStoryDirectory()).thenReturn(STORY_DIR);
        // 批末体检：只报不动作，测试里固定返回"健康"结论（其自身逻辑由 BatchHealthServiceTest 覆盖）。
        // 用 any() 而非 anyList()：ctx.getStageBlueprints() 可能为 null，anyList() 不匹配 null
        // 注意：ChapterWorker 调的是 **5 参重载**（候选统计 + 结算台账），桩必须同为 5 参，
        // 否则静默不匹配返回 null → 调用点 NPE。**改 assess 的元数时必须同步改这里。**
        // 排期打标：默认无排期表 ⇒ 无状态变化（与未启用排期时行为一致）
        when(foreshadowScheduleService.stamp(any(), any()))
                .thenReturn(ForeshadowScheduleService.StampResult.EMPTY);
        when(batchHealthService.assess(any(), any(), any(), any(), any(), any()))
                .thenReturn(new BatchHealthReport(0, List.of(), BatchHealthReport.Level.OK, List.of()));
        // 候选统计：默认无样本（stats.triggered=0 → 体检不产生候选指标）
        when(candidateSampleService.readStats(any()))
                .thenReturn(new cn.novel.yonren.domain.novel.service.armory.candidate.CandidateSampleService.CandidateStats(0, 0));
    }

    @Test
    void buildChapterPrompt_rendersFourLayersInOrder() {
        // 拆分重构的保护测试：正文 prompt 必须按
        // 事实层 → 任务层 → 约束层 → 表现层 的顺序渲染，且各层内容归属正确。
        // 重构只改变组织方式，不得改变内容与顺序。
        ChapterPlanItemEntity item = ChapterPlanItemEntity.builder()
                .chapterNo(9)
                .title("第九章")
                .goal("保护软软时暴露一个只有许意见过的动作")
                .characters(List.of("江燃", "软软"))
                .keyEvents(List.of("事件甲", "事件乙"))
                .endingHook("怀疑增加但身份未确认")
                .build();

        String prompt = chapterWorker.buildChapterPrompt(
                "【全局设定】都市校园", item, "【记忆前缀】账本末态", 9,
                cn.novel.yonren.domain.novel.service.armory.contract.ChapterContract.of(item, null));

        int facts = prompt.indexOf("【全局设定】都市校园");
        int memory = prompt.indexOf("【记忆前缀】账本末态");
        int contract = prompt.indexOf("第 9 章计划：");
        int requirements = prompt.indexOf("\n要求：");
        int continuity = prompt.indexOf("\n1. 严格围绕本章目标和关键事件");
        int guidance = prompt.indexOf("\n4. 内容密度纪律");
        int json = prompt.indexOf("请严格按照以下 JSON 格式输出");

        assertTrue(facts >= 0, "事实层：全局设定在最前");
        assertTrue(memory > facts, "记忆前缀紧随全局设定");
        assertTrue(contract > memory, "任务层在事实层之后");
        assertTrue(requirements > contract, "任务层含计划条目与「要求」总起");
        assertTrue(continuity > requirements, "约束层（要求 1-3）紧随其后");
        assertTrue(guidance > continuity, "表现层（要求 4 起）在约束层之后");
        assertTrue(json > guidance, "输出格式在最后一层");
        // 任务层必须完整渲染计划条目
        assertTrue(prompt.contains("标题：第九章"));
        assertTrue(prompt.contains("目标：保护软软时暴露一个只有许意见过的动作"));
        assertTrue(prompt.contains("角色：江燃、软软"));
        assertTrue(prompt.contains("关键事件：事件甲、事件乙"));
        assertTrue(prompt.contains("结尾悬念：怀疑增加但身份未确认"));
        assertTrue(prompt.contains("\"chapterNo\":9"));
    }

    @Test
    void buildChapterPrompt_registerGuidanceIsGenreAware() {
        ChapterPlanItemEntity item = ChapterPlanItemEntity.builder()
                .chapterNo(1).title("开端").goal("目标")
                .characters(List.of("林童")).keyEvents(List.of("事件")).endingHook("悬念").build();
        var contract = cn.novel.yonren.domain.novel.service.armory.contract.ChapterContract.of(item, null);

        String urban = chapterWorker.buildChapterPrompt("都市重生", item, null, 1, contract,
                StoryContextEntity.builder().theme("都市重生").style("年代成长").build());
        assertTrue(urban.contains("【语言与语域契约】"));
        assertTrue(urban.contains("年龄、身份、教育、职业"));
        assertFalse(urban.contains("灵气、阵纹、符箓"), "非 fantasy 题材不得被仙侠词表污染");

        String fantasy = chapterWorker.buildChapterPrompt("修仙世界", item, null, 1, contract,
                StoryContextEntity.builder().theme("仙侠修真").style("热血").build());
        assertTrue(fantasy.contains("灵气、阵纹、符箓"), "fantasy 题材仍应获得世界内词汇提示");
        assertTrue(fantasy.contains("程序员术语不得充当客观世界名词"));
    }

    @Test
    void buildChapterPrompt_blankMemoryPrefixSkipsOnlyFactsLayer() {
        ChapterPlanItemEntity item = ChapterPlanItemEntity.builder()
                .chapterNo(3).title("第三章").goal("目标")
                .characters(List.of()).keyEvents(List.of()).endingHook("悬念").build();

        String prompt = chapterWorker.buildChapterPrompt("【全局设定】", item, null, 3,
                cn.novel.yonren.domain.novel.service.armory.contract.ChapterContract.of(item, null));

        assertTrue(prompt.startsWith("【全局设定】"), "无记忆前缀时全局设定仍排最前");
        assertTrue(prompt.contains("\n\n请根据以下第 3 章计划"), "事实层与任务层的分隔保持不变");
        assertTrue(prompt.contains("\"chapterNo\":3"));
    }

    @Test
    void buildChapterPrompt_injectsKnowledgeBoundaryFromLedger() {
        // 账本一直在记录"谁还不知道什么"（如"尚未确认无月的真实身份"），但此前从未进入正文 prompt，
        // 模型只能靠猜，于是会把"怀疑"写成"确认"（实测候选评审就抓到过这种认知提前升级）
        ChapterPlanItemEntity item = ChapterPlanItemEntity.builder()
                .chapterNo(5).title("第五章").goal("目标")
                .characters(List.of("许知意")).keyEvents(List.of("事件甲")).endingHook("悬念")
                .build();
        List<ChapterSummaryEntity> summaries = List.of(
                ChapterSummaryEntity.builder().chapterNo(4).title("第四章").summary("……")
                        .characterStates(List.of(new ChapterSummaryEntity.StateEntry(
                                "许知意", "尚未确认无月的真实身份", "证据")))
                        .build());

        String prompt = chapterWorker.buildChapterPrompt("【全局设定】", item, null, 5,
                cn.novel.yonren.domain.novel.service.armory.contract.ChapterContract.of(item, summaries));

        assertTrue(prompt.contains("【认知边界·不得越界】"), "认知边界块必须注入约束层");
        assertTrue(prompt.contains("尚未确认无月的真实身份"), "边界内容来自账本");
        assertTrue(prompt.contains("不得把\"怀疑\"写成\"确认\""), "必须点明越界的典型形态");

        // 账本里没有认知类记录时，不得凭空生成该块
        String bare = chapterWorker.buildChapterPrompt("【全局设定】", item, null, 5,
                cn.novel.yonren.domain.novel.service.armory.contract.ChapterContract.of(item, null));
        assertTrue(!bare.contains("【认知边界·不得越界】"), "没有认知记录时不应出现该块");
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
        when(chapterSummaryService.summarize(any(), any(), anyString(), anyInt(), anyString(), anyList()))
                .thenAnswer(invocation -> {
                    job.requestCancel();
                    return summary(1);
                });
        DefaultArmoryFactory.DynamicContext ctx = context(job);

        chapterWorker.generateAll(command(), ctx);

        assertEquals(1, ctx.getChapterContents().size(), "取消信号置位后应完成当前章即停");
        verify(llmInvokeService, times(1)).invoke(any(), any(), any(), anyString(), anyMap());
        verify(storyRepository, times(1)).writeChapters(any(), any());
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
        when(chapterSummaryService.summarize(any(), any(), anyString(), anyInt(), anyString(), anyList()))
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
        when(planSegmentPlanner.planSegment(any(), any(), anyString(), eq(3), eq(4), any(), any(), any()))
                .thenReturn(new ChapterPlanSegmentPlanner.PlannedSegment("segment-2-raw", segment2Plan));

        chapterWorker.generateAll(command, ctx);

        verify(planSegmentPlanner, times(1)).planSegment(any(), any(), anyString(), eq(3), eq(4), any(), any(), any());

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
                                .chapterType(cn.novel.yonren.types.enums.ChapterTypeVO.FINALE).build())))
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
        when(qualityGate.auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any()))
                .thenReturn(GateResult.of(List.of(ChapterIssueEntity.builder().dimension("length").severity("BLOCKING").build()), 0, 0));
        DefaultArmoryFactory.DynamicContext ctx = context(null);

        chapterWorker.generateAll(command(), ctx);

        verify(storyRepository, times(2)).writeChapters(any(), any());
        verify(chapterSummaryService, times(2)).summarize(any(), any(), anyString(), anyInt(), anyString(), anyList());
    }

    @Test
    void generateAll_recordsDensitySignalsOnSummary() throws Exception {
        // 密度信号机械入账：摘要携带 validChars/keyEventCount，供规划层判断供给是否太稀
        when(qualityGate.auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any()))
                .thenReturn(GateResult.of(List.of(), 0, 0));
        when(chapterSummaryService.summarize(any(), any(), anyString(), anyInt(), anyString(), anyList()))
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
    @SuppressWarnings("unchecked")
    void generateAll_mechanicalStyleMinor_isRecordedAsDebt() throws Exception {
        // 严重度分层的配套要求：降档后的文风 MINOR 不触发修订、不触发候选、不计入 grade，
        // 但必须落质量债——否则「降档」等于把问题直接丢掉，观测层与规划层回灌都拿不到信号
        when(qualityGate.auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any()))
                .thenReturn(GateResult.of(List.of(), 0,
                        List.of(ChapterIssueEntity.builder()
                                .dimension("aesthetic").severity("MINOR")
                                .description("万能副词密度超标").build()), 0));
        DefaultArmoryFactory.DynamicContext ctx = context(null);

        chapterWorker.generateAll(command(), ctx);

        ArgumentCaptor<List<cn.novel.yonren.domain.novel.model.entity.QualityDebtEntity>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(storyRepository, atLeastOnce()).writeQualityDebts(any(), captor.capture());
        List<cn.novel.yonren.domain.novel.model.entity.QualityDebtEntity> last = captor.getValue();
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
                                .chapterType(cn.novel.yonren.types.enums.ChapterTypeVO.FINALE).build())))
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
        String feedback = ChapterWorker.renderReviewFeedback(minors);

        assertNotNull(feedback);
        assertTrue(feedback.contains("审校反馈"));
        assertTrue(feedback.contains("工具人"));
        assertFalse(feedback.contains("设定甲"), "一致性类问题走账本通道，不进反馈块");
        assertNull(ChapterWorker.renderReviewFeedback(List.of()));
    }

    private ChapterIssueEntity issue(String dimension, String description) {
        ChapterIssueEntity issue = new ChapterIssueEntity();
        issue.setDimension(dimension);
        issue.setSeverity("MINOR");
        issue.setDescription(description);
        return issue;
    }
}
