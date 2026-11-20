package cn.novel.yonren.domain.novel.service.armory.worker;

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

    @Test
    void buildChapterPrompt_rendersFourLayersInOrder() {
        // 拆分重构的保护测试：正文 prompt 必须按
        // 事实层 → 任务层 → 约束层 → 表现层 的顺序渲染，且各层内容归属正确。
        // 重构只改变组织方式，不得改变内容与顺序。
        ChapterPlanItemEntity item = ChapterPlanItemEntity.builder()
                .chapterNo(9)
                .title("第九章")
                .goal("保护软软时暴露一个只有许意见过的动作")
                .characters(List.of("角色癸", "软软"))
                .keyEvents(List.of("事件甲", "事件乙"))
                .endingHook("怀疑增加但身份未确认")
                .build();

        String prompt = chapterWorker.buildChapterPrompt(
                "【全局设定】都市校园", item, "【记忆前缀】账本末态", 9,
                cn.novel.yonren.domain.novel.model.valobj.contract.ChapterContract.of(item, null));

        int facts = prompt.indexOf("【全局设定】都市校园");
        int memory = prompt.indexOf("【记忆前缀】账本末态");
        int contract = prompt.indexOf("第 9 章计划：");
        int requirements = prompt.indexOf("\n要求：");
        int continuity = prompt.indexOf("\n1. 严格围绕本章目标和关键事件");
        int guidance = prompt.indexOf("\n4. 内容有效性");
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
        assertTrue(prompt.contains("角色：角色癸、软软"));
        assertTrue(prompt.contains("关键事件：事件甲、事件乙"));
        assertTrue(prompt.contains("结尾落点：怀疑增加但身份未确认"));
        assertTrue(prompt.contains("\"chapterNo\":9"));
    }

    @Test
    void buildChapterPrompt_prefersStructuredScenesOverDerivedEvents() {
        var action = new cn.novel.yonren.domain.novel.model.valobj.plan.ScenePlan(
                "scene-1", List.of("店员"), "悄悄退回钥匙", "有人盯着门", List.of(),
                "他把钥匙放在台上", "回收策划标记", List.of("后续目标"), "后门", "");
        var quiet = new cn.novel.yonren.domain.novel.model.valobj.plan.ScenePlan(
                "scene-2", List.of("店员"), "等门铃安静", "", List.of(),
                "", "余韵：回过神来", List.of(), "柜台", "");
        var item = ChapterPlanItemEntity.builder().chapterNo(2).schemaVersion(2).title("闭店").goal("归还钥匙")
                .scenes(List.of(action, quiet)).keyEvents(List.of("派生事件不重复给写手")).build();

        String prompt = chapterWorker.buildChapterPrompt("设定", item, null, 2,
                cn.novel.yonren.domain.novel.model.valobj.contract.ChapterContract.of(item, List.of()));

        assertTrue(prompt.contains("悄悄退回钥匙"));
        assertTrue(prompt.contains("他把钥匙放在台上"));
        assertTrue(prompt.contains("余韵：回过神来"));
        assertTrue(prompt.contains("【留白与克制】"));
        assertFalse(prompt.contains("派生事件不重复给写手"));
        assertFalse(prompt.contains("回收策划标记"));
        assertFalse(prompt.contains("后续目标"));
    }

    @Test
    void buildChapterPrompt_trimsOnlyAnExactRedundantEndingSuffixAndPreservesThePlan() {
        String action = "孙执事叫她明日去矿洞外围搬新料";
        String commentary = "她的追查终于踏上了新征程。";
        var item = ChapterPlanItemEntity.builder().chapterNo(3).title("新差事").goal("接下新差事")
                .endingHook(action + "，" + commentary).build();
        var contract = cn.novel.yonren.domain.novel.model.valobj.contract.ChapterContract.of(item, List.of());

        String prompt = chapterWorker.buildChapterPrompt("设定", item, null, 3, contract, null, "", commentary);
        assertTrue(prompt.contains("结尾落点：" + action + "。"));
        assertFalse(prompt.contains(commentary));
        assertEquals(action + "，" + commentary, item.getEndingHook());

        for (String invalid : List.of(item.getEndingHook(), "追查终于踏上了新征程。", action, "不同于原文的解释")) {
            String fallback = chapterWorker.buildChapterPrompt("设定", item, null, 3, contract, null, "", invalid);
            assertTrue(fallback.contains(item.getEndingHook()));
        }

        String discovery = "她认出回执上的亲笔署名，第一次确认失踪的弟弟还活着。";
        item.setEndingHook("驿卒把回执交给她。" + discovery);
        var discoveryContract = cn.novel.yonren.domain.novel.model.valobj.contract.ChapterContract.of(item, List.of());
        for (String invalid : List.of(discovery, "第一次确认失踪的弟弟还活着。")) {
            String fallback = chapterWorker.buildChapterPrompt("设定", item, null, 3, discoveryContract, null, "", invalid);
            assertTrue(fallback.contains(item.getEndingHook()));
        }
    }

    @Test
    void buildChapterPrompt_doesNotTurnContinuityMetadataIntoMandatoryOpeningTime() {
        ChapterPlanItemEntity item = ChapterPlanItemEntity.builder()
                .chapterNo(3).title("第三章").goal("赴约")
                .characters(List.of("沈砚")).keyEvents(List.of("到后山门")).endingHook("见面").build();
        var contract = cn.novel.yonren.domain.novel.model.valobj.contract.ChapterContract.of(item, null);

        String prompt = chapterWorker.buildChapterPrompt("【全局设定】", item, null, 3, contract);

        assertTrue(prompt.contains("允许仍在同一时间点、地点和场景，不必专门交代时间"));
        assertTrue(prompt.contains("时间用于核对连续性，不是章首报时要求"));
        assertTrue(prompt.contains("时间跳跃影响理解或具体时间承担赴约、倒计时等剧情作用时明确写出"));
        assertTrue(prompt.contains("不要求复用其章首格式或物品描写方式"));
        assertFalse(prompt.contains("本章第一段必须从上一章结束的时间点之后继续推进"));
    }

    @Test
    void buildChapterPrompt_selectsObjectDetailsByObservationAndChange() {
        ChapterPlanItemEntity item = ChapterPlanItemEntity.builder()
                .chapterNo(2).title("第二章").goal("领取物资")
                .characters(List.of("沈砚")).keyEvents(List.of("领取丹药")).endingHook("回屋").build();
        var contract = cn.novel.yonren.domain.novel.model.valobj.contract.ChapterContract.of(item, null);

        String prompt = chapterWorker.buildChapterPrompt("【全局设定】", item, null, 2, contract);

        assertTrue(prompt.contains("物品出现不等于需要外观特写"));
        assertTrue(prompt.contains("当前人物有理由注意"));
        assertTrue(prompt.contains("已出现且没有变化的物品再次出现时直接称名"));
        assertTrue(prompt.contains("状态、人物感受或意义变化才重新展开"));
    }

    @Test
    void buildChapterPrompt_registerGuidanceIsGenreAware() {
        ChapterPlanItemEntity item = ChapterPlanItemEntity.builder()
                .chapterNo(1).title("开端").goal("目标")
                .characters(List.of("林童")).keyEvents(List.of("事件")).endingHook("悬念").build();
        var contract = cn.novel.yonren.domain.novel.model.valobj.contract.ChapterContract.of(item, null);

        String urban = chapterWorker.buildChapterPrompt("都市重生", item, null, 1, contract,
                StoryContextEntity.builder().theme("都市重生").style("年代成长").build());
        assertTrue(urban.contains("【语言与语域契约】"));
        assertTrue(urban.contains("明确的认知超前、特殊训练或世界设定不能被年龄常识抹平"));
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
                cn.novel.yonren.domain.novel.model.valobj.contract.ChapterContract.of(item, null));

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
                .characters(List.of("角色壬")).keyEvents(List.of("事件甲")).endingHook("悬念")
                .build();
        List<ChapterSummaryEntity> summaries = List.of(
                ChapterSummaryEntity.builder().chapterNo(4).title("第四章").summary("……")
                        .characterStates(List.of(new ChapterSummaryEntity.StateEntry(
                                "角色壬", "尚未确认无月的真实身份", "证据")))
                        .build());

        String prompt = chapterWorker.buildChapterPrompt("【全局设定】", item, null, 5,
                cn.novel.yonren.domain.novel.model.valobj.contract.ChapterContract.of(item, summaries));

        assertTrue(prompt.contains("【认知边界·不得越界】"), "认知边界块必须注入约束层");
        assertTrue(prompt.contains("尚未确认无月的真实身份"), "边界内容来自账本");
        assertTrue(prompt.contains("不得把\"怀疑\"写成\"确认\""), "必须点明越界的典型形态");

        // 账本里没有认知类记录时，不得凭空生成该块
        String bare = chapterWorker.buildChapterPrompt("【全局设定】", item, null, 5,
                cn.novel.yonren.domain.novel.model.valobj.contract.ChapterContract.of(item, null));
        assertTrue(!bare.contains("【认知边界·不得越界】"), "没有认知记录时不应出现该块");
    }

    @Test
    void buildChapterPrompt_usesRelevantStructuredKnowledgeAndCurrentProgress() {
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
        String prompt = chapterWorker.buildChapterPrompt("设定", item, null, 2,
                chapterWorker.buildChapterContract(item, List.of(summary)));

        assertTrue(prompt.contains("BELIEF/SUSPECTED"));
        assertTrue(prompt.contains("亲眼看见黑气"));
        assertTrue(prompt.contains("【本章悬念进度】" + item.getSuspenseBeat()));
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
        when(planSegmentPlanner.planSegment(any(), any(), anyString(), eq(3), eq(4), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ChapterPlanSegmentPlanner.PlannedSegment("segment-2-raw", segment2Plan, null));

        chapterWorker.generateAll(command, ctx);

        verify(planSegmentPlanner, times(1)).planSegment(any(), any(), anyString(), eq(3), eq(4), any(), any(), any(), any(), any(), any());

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
}
