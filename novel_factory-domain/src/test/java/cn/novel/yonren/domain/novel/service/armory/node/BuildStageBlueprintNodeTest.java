package cn.novel.yonren.domain.novel.service.armory.node;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.entity.VolumeBlueprintEntity;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.domain.novel.service.armory.llm.LlmInvokeService;
import cn.novel.yonren.domain.novel.service.armory.memory.ChapterMemoryService;
import cn.novel.yonren.domain.novel.service.armory.memory.ForeshadowPriorityService;
import cn.novel.yonren.domain.novel.service.armory.memory.ForeshadowSettlementService;
import cn.novel.yonren.domain.novel.service.armory.memory.RollingOutlineService;
import cn.novel.yonren.domain.novel.service.armory.memory.StageExitReviewService;
import cn.novel.yonren.domain.novel.service.armory.quality.SuspenseLadderPolicy;
import cn.novel.yonren.types.enums.PromptScene;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 阶段蓝图节点测试：阶段内复用不调模型、跨边界生成新蓝图、解析失败 fail-soft、prompt 四件套
 */
class BuildStageBlueprintNodeTest {

    /**
     * 伏笔排期表 JSON 片段（第 11-55 章阶段的排期）。
     *
     * <p>必须带全：缺了它会触发**第三次聚焦补采**，于是所有"恰好调用一次"的断言都会失败。
     * 本夹具的 payoff 全部落在阶段内（≤55）——因为测试未设 `maxChapterCount`，
     * hardTotal 退化为阶段末章，"跨阶段"要求在那种情形下被**条件化跳过**（见
     * `BuildStageBlueprintNode#repairForeshadowSchedule` 的 crossStagePossible）。
     */
    private static final String SCHEDULE_FIELD = "\"foreshadowSchedule\":["
            + "{\"intent\":\"角色戊是否收角色丙为徒\",\"plantChapter\":12,\"payoffChapter\":30},"
            + "{\"intent\":\"某科技公司骗局的最终清算\",\"plantChapter\":20,\"payoffChapter\":45}]";

    /**
     * 章级主线推进的 JSON 片段（第 11-25 章，15 条）。
     *
     * <p>条数必须**恰好覆盖排期窗**：{@code RollingOutlineService.MAINLINE_WINDOW = 15}，
     * 而 {@code parseMainLinePatch} 会严格校验"章号连续覆盖"——补采只给前几条会被判不可用
     * （半张表比没有更危险：校验会因"缺章"不断驳回，而模型每次补采都只给前几条）。
     */
    private static final String MAINLINE_FIELD = "\"mainLineByChapter\":["
            + "{\"chapterNo\":11,\"advance\":\"【悬疑】首次察觉体内异样\"},"
            + "{\"chapterNo\":12,\"advance\":\"【学习】向师姐试探口风\"},"
            + "{\"chapterNo\":13,\"advance\":\"【悬疑】确认识海被侵\"},"
            + "{\"chapterNo\":14,\"advance\":\"【学习】暗中翻阅宗门典籍\"},"
            + "{\"chapterNo\":15,\"advance\":\"【悬疑】找到前任受害者的记录\"},"
            + "{\"chapterNo\":16,\"advance\":\"【学习】锁定嫌疑人范围\"},"
            + "{\"chapterNo\":17,\"advance\":\"【悬疑】设局试探长老\"},"
            + "{\"chapterNo\":18,\"advance\":\"【学习】取得长老的把柄\"},"
            + "{\"chapterNo\":19,\"advance\":\"【悬疑】把柄被对方察觉\"},"
            + "{\"chapterNo\":20,\"advance\":\"【学习】遭到伏击身受重伤\"},"
            + "{\"chapterNo\":21,\"advance\":\"【悬疑】疗伤期间想通夺舍手法\"},"
            + "{\"chapterNo\":22,\"advance\":\"【学习】找到破解夺舍的阵法\"},"
            + "{\"chapterNo\":23,\"advance\":\"【悬疑】布阵失败被反制\"},"
            + "{\"chapterNo\":24,\"advance\":\"【学习】以自身神魂为饵诱敌\"},"
            + "{\"chapterNo\":25,\"advance\":\"【学习】当众揭穿长老身份\"}]";

    /**
     * 正常蓝图夹具：**带悬念档位表 + 章级主线推进**。
     *
     * <p>⚠️ 这两个字段都不是可选项——缺了任何一个，蓝图节点都会触发对应的"聚焦补采"
     * 再调一次模型（见 {@code prepare_repairsMissingSuspenseLadderByFocusedRecall}），
     * 于是所有"恰好调用一次"的断言都会失败。这里带上它们，代表**正常情形**，
     * 让本类其余用例继续专注于各自要验的东西。
     */
    private static final String VALID_BLUEPRINT_JSON =
            "{\"stageNo\":2,\"startChapter\":11,\"endChapter\":55,\"stageGoal\":\"主角筑基\","
                    + "\"coreSuspense\":\"主角何时发现自己被夺舍\","
                    + "\"suspenseLadder\":[\"完全不知情\",\"察觉异常\",\"找到关键证据\",\"当面对质\"],"
                    + "\"tasks\":[\"突破至筑基期\"],\"carriedTasks\":[],"
                    + MAINLINE_FIELD + ","
                    + SCHEDULE_FIELD + "}";

    @Mock
    private LlmInvokeService llmInvokeService;

    @Mock
    private BuildChapterPlanPromptNode buildChapterPlanPromptNode;

    @Spy
    private RollingOutlineService rollingOutlineService = new RollingOutlineService();

    @Spy
    private ChapterMemoryService chapterMemoryService = new ChapterMemoryService(new ForeshadowPriorityService());

    @Mock
    private StageExitReviewService stageExitReviewService;

    @Mock
    private ForeshadowSettlementService foreshadowSettlementService;

    @Mock
    private IStoryRepository storyRepository;

    @InjectMocks
    private BuildStageBlueprintNode node;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void prepare_reusesCurrentBlueprintWithoutLlmCall() {
        StageBlueprintEntity current = blueprint(2, 11, 40);
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .chapterOffset(15)
                .stageBlueprints(new ArrayList<>(List.of(blueprint(1, 1, 10), current)))
                .build();

        // 本批 5 章（16-20）完全落在第 2 阶段覆盖区间（11-40）内
        node.prepareBlueprint(command(5), ctx);

        verifyNoInteractions(llmInvokeService);
        assertSame(current, ctx.getStageBlueprint());
    }

    @Test
    void prepare_generatesNewBlueprintAtStageBoundary() {
        // 模拟 LlmInvokeService 真实契约：把实际使用的 prompt 写入传入的 map
        when(llmInvokeService.invoke(any(), eq(PromptScene.STAGE_BLUEPRINT), any(), anyString(), any()))
                .thenAnswer(invocation -> {
                    Map<String, String> prompts = invocation.getArgument(4);
                    prompts.put("system", "system-prompt");
                    prompts.put("user", "user-prompt");
                    return VALID_BLUEPRINT_JSON;
                });
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .chapterOffset(10)
                .stageBlueprints(new ArrayList<>(List.of(blueprint(1, 1, 10))))
                .build();

        // 本批 45 章（11-55）：终点 55 恰为钳制区间上界，一次生成即覆盖批次末章
        node.prepareBlueprint(command(45), ctx);

        // 第 11 章跨过第 1 阶段边界 → 生成第 2 阶段蓝图：起点机械给定，终点取模型自定值（55 在 30-80 章钳制区间内）
        StageBlueprintEntity blueprint = ctx.getStageBlueprint();
        assertEquals(2, blueprint.getStageNo());
        assertEquals(11, blueprint.getStartChapter());
        assertEquals(55, blueprint.getEndChapter());
        assertEquals(2, ctx.getStageBlueprints().size());
        // 蓝图 prompt 以 blueprint: 前缀并入 usedPromptMap 供复盘
        assertNotNull(ctx.getUsedPromptMap());
        assertTrue(ctx.getUsedPromptMap().containsKey("blueprint:user"));
    }

    @Test
    void prepare_freshRunGeneratesStageOne() {
        when(llmInvokeService.invoke(any(), eq(PromptScene.STAGE_BLUEPRINT), any(), anyString(), any()))
                .thenReturn(VALID_BLUEPRINT_JSON);
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .chapterOffset(0)
                .build();

        // 本批 10 章：首版固定窗口 1-10 恰好覆盖批次，无需链式生成
        node.prepareBlueprint(command(10), ctx);

        StageBlueprintEntity blueprint = ctx.getStageBlueprint();
        assertEquals(1, blueprint.getStageNo());
        assertEquals(1, blueprint.getStartChapter());
        assertEquals(10, blueprint.getEndChapter());
        assertEquals(1, ctx.getStageBlueprints().size());
    }

    @Test
    void prepare_midFlightAdoptionGeneratesAdaptiveFirstBlueprint() {
        when(llmInvokeService.invoke(any(), eq(PromptScene.STAGE_BLUEPRINT), any(), anyString(), any()))
                .thenReturn(VALID_BLUEPRINT_JSON);
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .chapterOffset(14)
                .build();

        node.prepareBlueprint(command(), ctx);

        // 老故事第 15 章接入：首版蓝图不再写死 1-10，直接从下一章起自适应（终点取模型自定值）
        StageBlueprintEntity blueprint = ctx.getStageBlueprint();
        assertEquals(1, blueprint.getStageNo());
        assertEquals(15, blueprint.getStartChapter());
        assertEquals(55, blueprint.getEndChapter());
    }

    @Test
    void prepare_failSoftOnUnparseableOutput() {
        when(llmInvokeService.invoke(any(), eq(PromptScene.STAGE_BLUEPRINT), any(), anyString(), any()))
                .thenReturn("模型胡言乱语，不是 JSON");
        StageBlueprintEntity previous = blueprint(1, 1, 10);
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .chapterOffset(10)
                .stageBlueprints(new ArrayList<>(List.of(previous)))
                .build();

        node.prepareBlueprint(command(), ctx);

        // 蓝图是增强件：解析失败回退上一版，不追加、不抛异常、不阻断计划生成
        // 起失败自动重试一次，重试仍失败才降级——两次调用均返回垃圾，行为不变）
        assertSame(previous, ctx.getStageBlueprint());
        assertEquals(1, ctx.getStageBlueprints().size());
        verify(llmInvokeService, times(2)).invoke(any(), eq(PromptScene.STAGE_BLUEPRINT), any(), anyString(), any());
    }

    @Test
    void prepare_retriesOnceThenSucceeds() {
        // 蓝图解析失败自动重试一次——任务/退出条件/排期都挂在蓝图上，
        // 静默降级的代价是整批规划缺位（36-40 章实测排期 71 条原地踏步），重试成本（60s）远低于此
        when(llmInvokeService.invoke(any(), eq(PromptScene.STAGE_BLUEPRINT), any(), anyString(), any()))
                .thenReturn("模型胡言乱语，不是 JSON", VALID_BLUEPRINT_JSON);
        StageBlueprintEntity previous = blueprint(1, 1, 10);
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .chapterOffset(10)
                .stageBlueprints(new ArrayList<>(List.of(previous)))
                .build();

        node.prepareBlueprint(command(), ctx);

        // 重试产物覆盖 nextChapterNo=11；command() 为百章长篇，链条按标准窗口继续补齐至批次末章
        assertTrue(ctx.getStageBlueprints().size() > 1, "重试成功后应已追加新蓝图");
        assertEquals(11, ctx.getStageBlueprint().getStartChapter(),
                "覆盖 nextChapterNo 的蓝图应为重试成功的那次产出");
        assertTrue(ctx.getStageBlueprints().stream()
                        .anyMatch(b -> b.getEndChapter() != null && b.getEndChapter() >= 110),
                "蓝图链应覆盖批次末章 110");
    }

    @Test
    void prepare_failSoftOnFreshRunKeepsNoBlueprint() {
        when(llmInvokeService.invoke(any(), eq(PromptScene.STAGE_BLUEPRINT), any(), anyString(), any()))
                .thenReturn("模型胡言乱语，不是 JSON");
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .chapterOffset(0)
                .build();

        node.prepareBlueprint(command(), ctx);

        assertNull(ctx.getStageBlueprint());
        assertNull(ctx.getStageBlueprints());
    }

    @Test
    void prepare_batchSpanningTwoStagesChainsBlueprints() {
        // 首发批次 25 章：首版蓝图固定覆盖 1-10，剩余 11-25 需链式生成第 2 阶段蓝图
        when(llmInvokeService.invoke(any(), eq(PromptScene.STAGE_BLUEPRINT), any(), anyString(), any()))
                .thenReturn(VALID_BLUEPRINT_JSON, VALID_BLUEPRINT_JSON);
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .chapterOffset(0)
                .build();

        node.prepareBlueprint(command(25), ctx);

        assertEquals(2, ctx.getStageBlueprints().size());
        StageBlueprintEntity first = ctx.getStageBlueprints().get(0);
        assertEquals(1, first.getStageNo());
        assertEquals(1, first.getStartChapter());
        assertEquals(10, first.getEndChapter());
        StageBlueprintEntity second = ctx.getStageBlueprints().get(1);
        assertEquals(2, second.getStageNo());
        assertEquals(11, second.getStartChapter());
        // 全书 25 章：第 2 阶段起点 11 距末章不足一个标准窗长，终点强制钳制到全书末章
        assertEquals(25, second.getEndChapter());
        // 默认蓝图 = 覆盖批次首章的蓝图（第 1 阶段）
        assertSame(first, ctx.getStageBlueprint());
        // 2 版蓝图 × (主蓝图 + 伏笔排期补采) = 4：
        // 排期表不是 StageBlueprintEntity 的字段，主 prompt 产不出，**补采即其常规产出路径**
        verify(llmInvokeService, times(4)).invoke(any(), eq(PromptScene.STAGE_BLUEPRINT), any(), anyString(), any());
    }

    @Test
    void prepare_resumeBatchInsideExistingWindowReusesWithoutChain() {
        // 续写批次（第 16-20 章）完全落在第 2 阶段蓝图（11-40）内：不生成新蓝图
        StageBlueprintEntity current = blueprint(2, 11, 40);
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .chapterOffset(15)
                .stageBlueprints(new ArrayList<>(List.of(blueprint(1, 1, 10), current)))
                .build();

        node.prepareBlueprint(command(5), ctx);

        verifyNoInteractions(llmInvokeService);
        assertEquals(2, ctx.getStageBlueprints().size());
        assertSame(current, ctx.getStageBlueprint());
    }

    @Test
    void prepare_promptCarriesFourInputs() {
        when(llmInvokeService.invoke(any(), eq(PromptScene.STAGE_BLUEPRINT), any(), anyString(), any()))
                .thenReturn(VALID_BLUEPRINT_JSON);
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        for (int no = 1; no <= 15; no++) {
            summaries.add(ChapterSummaryEntity.builder()
                    .chapterNo(no).title("第" + no + "章").summary("剧情推进。").build());
        }
        summaries.get(0).setForeshadowingNew(List.of("古物来历"));
        summaries.get(0).setForeshadowSeeds(List.of(
                new ChapterSummaryEntity.SeedEntry("古物来历", null, 5)));
        StageBlueprintEntity previous = blueprint(1, 1, 10);
        previous.setTasks(List.of("上一版任务"));
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .chapterOffset(10)
                .chapterSummaries(summaries)
                .stageBlueprints(new ArrayList<>(List.of(previous)))
                .build();

        // 本批 45 章（11-55）：一次生成即覆盖，prompt 断言基于该窗口
        node.prepareBlueprint(command(45), ctx);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        // 共 2 次调用：第 1 次主蓝图、第 2 次伏笔排期补采 ⇒ 内容断言必须取**第 1 次**
        verify(llmInvokeService, times(2)).invoke(any(), eq(PromptScene.STAGE_BLUEPRINT), any(), promptCaptor.capture(), any());
        String prompt = promptCaptor.getAllValues().get(0);
        // 四件套：原始大纲 + 上一版蓝图（结转） + 近章摘要 + 待回收伏笔
        assertTrue(prompt.contains("主角身世之谜"));
        assertTrue(prompt.contains("- 上一版任务"));
        assertTrue(prompt.contains("第10章"));
        assertTrue(prompt.contains("【硬】古物来历（第1章埋）"));
        // 第 11 章触发第 2 阶段：窗口不再写死，由模型自定终点（30-80 章钳制）
        assertTrue(prompt.contains("起点为第 11 章"));
        assertTrue(prompt.contains("窗长约束 30-80 章"));
    }

    private ArmoryCommandEntity command() {
        // 默认百章长篇：阶段窗口保持标准 30-80 章语义，不触发"收束全书"钳制
        return command(100);
    }

    private ArmoryCommandEntity command(int chapterCount) {
        StoryContextEntity story = StoryContextEntity.builder()
                .novel_title("镜中仙途")
                .theme("仙侠")
                .style("热血")
                .protagonist("小陈")
                .outline("主角身世之谜")
                .chapterCount(chapterCount)
                .build();
        return ArmoryCommandEntity.builder().storyContextEntity(story).build();
    }

    @Test
    void prepare_reviewsExitConditionsAndMechanicallyCarriesUnmet() {
        StageBlueprintEntity previous = blueprint(1, 1, 10);
        previous.setExitConditions(List.of("主角突破至炼气九层"));
        // 外部审计判未达成（证据校验失败）；真实服务会把 prompt 写入 sink，mock 需模拟同一契约
        when(stageExitReviewService.review(any(), eq(previous), any(), any())).thenAnswer(invocation -> {
            Map<String, String> sink = invocation.getArgument(3);
            if (sink != null) {
                sink.put("user", "exit-review-prompt");
            }
            return List.of(new StageBlueprintEntity.ExitConditionResult(
                    "主角突破至炼气九层", false, null, null, "境界仍为炼气八层"));
        });
        when(llmInvokeService.invoke(any(), eq(PromptScene.STAGE_BLUEPRINT), any(), anyString(), any()))
                .thenAnswer(invocation -> {
                    Map<String, String> prompts = invocation.getArgument(4);
                    prompts.put("system", "system-prompt");
                    prompts.put("user", "user-prompt");
                    // VALID_BLUEPRINT_JSON 未结转任何任务：机械结转必须补入未达成退出条件
                    return VALID_BLUEPRINT_JSON;
                });
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .chapterOffset(10)
                .stageBlueprints(new ArrayList<>(List.of(previous)))
                .build();

        node.prepareBlueprint(command(45), ctx);

        // 核验先于蓝图生成，未达成条件进入生成 prompt 的结转指令
        verify(stageExitReviewService).review(any(), eq(previous), any(), any());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        // 共 2 次：主蓝图 + 伏笔排期补采 ⇒ 取第 1 次
        verify(llmInvokeService, times(2)).invoke(any(), eq(PromptScene.STAGE_BLUEPRINT), any(), promptCaptor.capture(), any());
        assertTrue(promptCaptor.getAllValues().get(0).contains("未达成：主角突破至炼气九层"));
        // 机械结转：模型未结转（VALID_BLUEPRINT_JSON carriedTasks 为空），缺失即补
        StageBlueprintEntity blueprint = ctx.getStageBlueprint();
        assertNotNull(blueprint.getCarriedTasks());
        assertTrue(blueprint.getCarriedTasks().stream()
                .anyMatch(t -> t.getContent().contains("【未达成退出条件】主角突破至炼气九层")));
        // 退场规则：未达成条件必须被**原样注入**本阶段退出条件，否则永不被重验；
        // 同时登记继承谱系，供下一轮判定"是否已重验过一次"
        assertTrue(blueprint.getExitConditions().contains("主角突破至炼气九层"),
                "未达成条件须原样注入本阶段 exitConditions 才能进入核验层");
        assertEquals(List.of("主角突破至炼气九层"), blueprint.getInheritedExitConditions());
        // 核验与蓝图 prompt 均入 usedPromptMap 供复盘（合并式不互相覆盖）
        assertTrue(ctx.getUsedPromptMap().containsKey("exit-review:user"));
        assertTrue(ctx.getUsedPromptMap().containsKey("blueprint:user"));
    }

    @Test
    void prepare_unmetConditionReverifiedOnce_thenWrittenOff() {
        // 退场规则：上一版已把该条件继承注入（= 已重验过一次），本版仍不达成 ⇒ 强制出账，不再注入
        StageBlueprintEntity previous = blueprint(2, 11, 20);
        previous.setExitConditions(List.of("主角突破至炼气九层"));
        previous.setInheritedExitConditions(List.of("主角突破至炼气九层"));
        when(stageExitReviewService.review(any(), eq(previous), any(), any()))
                .thenReturn(List.of(new StageBlueprintEntity.ExitConditionResult(
                        "主角突破至炼气九层", false, null, null, "境界仍为炼气八层")));
        when(llmInvokeService.invoke(any(), eq(PromptScene.STAGE_BLUEPRINT), any(), anyString(), any()))
                .thenAnswer(invocation -> {
                    Map<String, String> prompts = invocation.getArgument(4);
                    prompts.put("user", "user-prompt");
                    return VALID_BLUEPRINT_JSON;
                });
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .chapterOffset(20)
                .stageBlueprints(new ArrayList<>(List.of(previous)))
                .build();

        node.prepareBlueprint(command(45), ctx);

        StageBlueprintEntity blueprint = ctx.getStageBlueprint();
        assertFalse(blueprint.getExitConditions().contains("主角突破至炼气九层"),
                "重验一次仍不达成 ⇒ 不再注入，避免单调累积");
        assertTrue(blueprint.getCarriedTasks().stream().anyMatch(t ->
                        t.getContent().contains("【未达成退出条件·出账】") && "放弃".equals(t.getStatus())),
                "应记一条『放弃 + 机械出账』结转，交人工复盘裁定");
    }

    /**
     * 悬念档位缺失时的**聚焦补采**：主调用漏了档位表 → 必须再问一次并把结果合并进蓝图。
     *
     * <p>这条守的是实测踩到的坑：把"给出档位表"当作完整蓝图 prompt 里的第 11 条要求，
     * 模型会在长 prompt 里静默省略它；若就此放过，档位表恒为 null，
     * 锚块不注入、校验跳过、指标不出——整条"主线推进"链路**一声不响地空转**。
     */
    @Test
    void prepare_repairsMissingSuspenseLadderByFocusedRecall() {
        // 三次返回：①主蓝图（缺档位表，也缺章级主线推进）②档位表补采 ③章级主线推进补采。
        // 每次补采都是**独立的一次聚焦调用**——章级推进后，缺失字段会各触发一次。
        when(llmInvokeService.invoke(any(), eq(PromptScene.STAGE_BLUEPRINT), any(), anyString(), any()))
                .thenReturn("{\"stageGoal\":\"目标\",\"endChapter\":55,\"tasks\":[\"任务\"],\"carriedTasks\":[]}")
                .thenReturn("{\"coreSuspense\":\"外门何时露出破绽\","
                        + "\"suspenseLadder\":[\"无人察觉\",\"个别弟子起疑\",\"当众揭穿\"]}")
                .thenReturn("{" + MAINLINE_FIELD + "}")
                // 第 4 次：伏笔排期补采（P2b）
                .thenReturn("{" + SCHEDULE_FIELD + "}");
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .chapterOffset(10)
                .stageBlueprints(new ArrayList<>(List.of(blueprint(1, 1, 10))))
                .build();

        node.prepareBlueprint(command(45), ctx);

        StageBlueprintEntity blueprint = ctx.getStageBlueprint();
        assertNotNull(blueprint);
        assertTrue(SuspenseLadderPolicy.usable(blueprint.getSuspenseLadder()),
                "补采必须把档位表补上，否则推进闸门静默失效");
        assertEquals(3, blueprint.getSuspenseLadder().size());
        assertEquals("外门何时露出破绽", blueprint.getCoreSuspense());
        assertNotNull(blueprint.getMainLineByChapter(), "章级主线推进缺失也必须补采，否则横向校验静默失效");
        assertFalse(blueprint.getMainLineByChapter().isEmpty());
        verify(llmInvokeService, times(4))
                .invoke(any(), eq(PromptScene.STAGE_BLUEPRINT), any(), anyString(), any());
    }

    private StageBlueprintEntity blueprint(int stageNo, int start, Integer end) {
        return StageBlueprintEntity.builder()
                .stageNo(stageNo)
                .startChapter(start)
                .endChapter(end)
                .build();
    }

    @Test
    void prepare_backFillsVolumeOwnershipFromCurrentVolume() {
        // 模型输出自带 arcNo/arcGoal，但卷号/卷标题被机械回填（不信任模型重算）
        // 章级主线推进字段也带上——缺了它会额外触发一次聚焦补采（见类注释的夹具说明）
        when(llmInvokeService.invoke(any(), eq(PromptScene.STAGE_BLUEPRINT), any(), anyString(), any()))
                .thenReturn("{\"stageGoal\":\"目标\",\"endChapter\":55,\"arcNo\":3,\"arcGoal\":\"外门立身\","
                        + "\"coreSuspense\":\"外门何时露出破绽\","
                        + "\"suspenseLadder\":[\"无人察觉\",\"个别弟子起疑\",\"当众揭穿\"],"
                        + "\"mainLineByChapter\":[{\"chapterNo\":11,\"advance\":\"【悬疑】弟子首次察觉异常\"},"
                        + "{\"chapterNo\":12,\"advance\":\"【学习】向执事试探口风\"}],"
                        + SCHEDULE_FIELD + "}");
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .chapterOffset(10)
                .currentVolume(VolumeBlueprintEntity.builder()
                        .volumeNo(1).title("外门篇").startChapter(1).endChapter(300).build())
                .stageBlueprints(new ArrayList<>(List.of(blueprint(1, 1, 10))))
                .build();

        node.prepareBlueprint(command(45), ctx);

        StageBlueprintEntity blueprint = ctx.getStageBlueprint();
        // 卷号/卷标题机械回填自当前卷
        assertEquals(1, blueprint.getVolumeNo());
        assertEquals("外门篇", blueprint.getVolumeTitle());
        // arcNo/arcGoal 保留模型自报
        assertEquals(3, blueprint.getArcNo());
        assertEquals("外门立身", blueprint.getArcGoal());
        // 当前卷方向锚进入生成 prompt
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        // 共 2 次：主蓝图 + 伏笔排期补采 ⇒ 取第 1 次
        verify(llmInvokeService, times(2)).invoke(any(), eq(PromptScene.STAGE_BLUEPRINT), any(), promptCaptor.capture(), any());
        assertTrue(promptCaptor.getAllValues().get(0).contains("【所属卷方向锚】"));
    }

    @Test
    void prepare_withoutVolume_leavesOwnershipNull() {
        when(llmInvokeService.invoke(any(), eq(PromptScene.STAGE_BLUEPRINT), any(), anyString(), any()))
                .thenReturn(VALID_BLUEPRINT_JSON);
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .chapterOffset(10)
                .stageBlueprints(new ArrayList<>(List.of(blueprint(1, 1, 10))))
                .build();

        node.prepareBlueprint(command(45), ctx);

        // 存量/无卷：不注入方向锚，归属字段保持 null（两段式）
        StageBlueprintEntity blueprint = ctx.getStageBlueprint();
        assertNull(blueprint.getVolumeNo());
        assertNull(blueprint.getVolumeTitle());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        // 共 2 次：主蓝图 + 伏笔排期补采 ⇒ 取第 1 次
        verify(llmInvokeService, times(2)).invoke(any(), eq(PromptScene.STAGE_BLUEPRINT), any(), promptCaptor.capture(), any());
        assertTrue(!promptCaptor.getAllValues().get(0).contains("【所属卷方向锚】"));
    }
}
