package cn.novel.yonren.domain.novel.service.armory.plan;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowSettlementEntity;
import cn.novel.yonren.domain.novel.model.entity.QualityDebtEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowScheduleEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.valobj.properties.PromptBudgetProperties;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.domain.novel.service.armory.memory.ChapterMemoryService;
import cn.novel.yonren.domain.novel.service.armory.memory.RollingOutlineService;
import cn.novel.yonren.domain.novel.service.armory.memory.StoryMemoryService;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptBudgetGuard;
import cn.novel.yonren.types.enums.ChapterTypeVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 章节计划 prompt 装配测试（自 BuildChapterPlanPromptNodeTest 迁移，装配逻辑同址）：
 * 续写注入记忆前缀 + 全局起始章号，首发与现状一致；
 * 跨阶段批次按阶段蓝图分段，逐段限定章号区间与 finale 要求
 */
class ChapterPlanPromptServiceTest {

    @Mock
    private ChapterMemoryService chapterMemoryService;

    @Spy
    private RollingOutlineService rollingOutlineService = new RollingOutlineService();

    @Mock
    private StoryMemoryService storyMemoryService;

    /** 预算属性：默认值下不裁剪（既有用例行为不变），逐用例可调 */
    private final PromptBudgetProperties budgetProperties = new PromptBudgetProperties();

    /** 输入段总量守门：真实实现（spy）——淘汰/截断规则由 PromptBudgetGuardTest 覆盖 */
    @Spy
    private PromptBudgetGuard promptBudgetGuard = new PromptBudgetGuard(budgetProperties);

    @InjectMocks
    private ChapterPlanPromptService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void buildPlanPrompt_resumeContainsMemoryPrefixAndGlobalStartNo() {
        when(chapterMemoryService.buildMemoryBlocks(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new PromptBudgetGuard.Block("近章摘要", 2, true, "【前情记忆标记】")));
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .storyContext("故事上下文")
                .chapterOffset(2)
                .chapterSummaries(List.of(ChapterSummaryEntity.builder().chapterNo(2).build()))
                .prevChapterTail("上一章结尾原文")
                .build();

        String prompt = service.buildPlanPrompt(ctx);

        assertTrue(prompt.startsWith("故事上下文"));
        assertTrue(prompt.contains("【前情记忆标记】"));
        assertTrue(prompt.contains("chapterNo 从 3 开始连续递增"));
        assertTrue(prompt.contains("\"chapterNo\":3"));
        assertTrue(prompt.contains("这是续写批次"));
    }

    @Test
    void buildPlanPrompt_worldLanguageRuleIsGenreAware() {
        DefaultArmoryFactory.DynamicContext urban = DefaultArmoryFactory.DynamicContext.builder()
                .storyContext("故事上下文")
                .storyContextEntity(StoryContextEntity.builder()
                        .theme("都市重生").style("年代成长").build())
                .build();
        String urbanPrompt = service.buildPlanPrompt(urban);
        assertTrue(urbanPrompt.contains("人物身份一致的世界内语言"));
        assertFalse(urbanPrompt.contains("灵气、阵纹、符箓"), "非 fantasy 规划不得注入仙侠词表");

        DefaultArmoryFactory.DynamicContext fantasy = DefaultArmoryFactory.DynamicContext.builder()
                .storyContext("故事上下文")
                .storyContextEntity(StoryContextEntity.builder()
                        .theme("仙侠修真").style("热血").build())
                .build();
        String fantasyPrompt = service.buildPlanPrompt(fantasy);
        assertTrue(fantasyPrompt.contains("灵气、阵纹、符箓"));
        assertTrue(fantasyPrompt.contains("不进入计划事件名词"));
    }

    @Test
    void buildPlanPrompt_firstRunHasNoMemoryPrefix() {
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .storyContext("故事上下文")
                .build();

        String prompt = service.buildPlanPrompt(ctx);

        assertTrue(prompt.contains("chapterNo 从 1 开始连续递增"));
        assertTrue(prompt.contains("\"chapterNo\":1"));
        // 首发不注入记忆前缀（要求文本中可出现"前情记忆"字样的条件指令，此处只校验前缀本体）
        assertFalse(prompt.contains("以下为前情记忆"));
        assertFalse(prompt.contains("这是续写批次"));
    }

    @Test
    void buildPlanPrompt_resumeKeepsCountAndTypeRequirements() {
        when(chapterMemoryService.buildMemoryBlocks(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new PromptBudgetGuard.Block("近章摘要", 2, true, "【前情记忆标记】")));
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .storyContext("故事上下文")
                .chapterOffset(6)
                .chapterSummaries(List.of(ChapterSummaryEntity.builder().chapterNo(6).build()))
                .build();

        String prompt = service.buildPlanPrompt(ctx);

        assertTrue(prompt.contains("章节数量必须等于输入的章节数量"));
        assertTrue(prompt.contains("chapterType"));
    }

    @Test
    void buildPlanPrompt_hardForeshadowsRequireExplicitEvaluation() {
        when(chapterMemoryService.buildMemoryBlocks(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new PromptBudgetGuard.Block("近章摘要", 2, true, "【前情记忆标记】")));
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .storyContext("故事上下文")
                .build();

        String prompt = service.buildPlanPrompt(ctx);

        // 评估制：硬级必须逐条评估（相关则回收、无关需说明理由），但不强排——反注水
        assertTrue(prompt.contains("标【硬】的条目"));
        assertTrue(prompt.contains("必须逐条显式评估"));
        assertTrue(prompt.contains("与本章场景无关：一句理由"));
        assertTrue(prompt.contains("标【软】的条目"));
        assertTrue(prompt.contains("每章合计至多安排 1 条回收"));
    }

    @Test
    void buildPlanPrompt_conflictAsymmetryInstruction() {
        when(chapterMemoryService.buildMemoryBlocks(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new PromptBudgetGuard.Block("近章摘要", 2, true, "【前情记忆标记】")));
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .storyContext("故事上下文")
                .build();

        String prompt = service.buildPlanPrompt(ctx);

        // 破除阵营套路：多方交锋禁止对称出场，必须信息差/时间差
        assertTrue(prompt.contains("【禁止对称出场】"));
        assertTrue(prompt.contains("信息差和时间差"));
        assertTrue(prompt.contains("非对称性和意外感"));
    }

    @Test
    void buildPlanPrompt_stageBlueprintMilestoneFraming() {
        when(chapterMemoryService.buildMemoryBlocks(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new PromptBudgetGuard.Block("近章摘要", 2, true, "【前情记忆标记】")));
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .storyContext("故事上下文")
                .build();

        String prompt = service.buildPlanPrompt(ctx);

        // 蓝图任务只作方向语境：自然推进可写入计划，严禁配额化注水或提前透支高潮
        assertTrue(prompt.contains("【阶段蓝图】"));
        assertTrue(prompt.contains("方向语境"));
        assertTrue(prompt.contains("无需每章都推进任务"));
        assertTrue(prompt.contains("严禁为凑任务强行编造事件"));
    }

    @Test
    void buildPlanPrompt_segmentScopesChapterRangeAndStartNo() {
        when(chapterMemoryService.buildMemoryBlocks(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new PromptBudgetGuard.Block("近章摘要", 2, true, "【前情记忆标记】")));
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .storyContext("故事上下文")
                .chapterOffset(10)
                .chapterSummaries(List.of(ChapterSummaryEntity.builder().chapterNo(10).build()))
                .build();
        // 收官卷蓝图（finalVolumeDeclared + EPILOGUE）：末段承担全书收束要求
        StageBlueprintEntity finalVolume = StageBlueprintEntity.builder()
                .stageNo(2).startChapter(11).endChapter(55)
                .finalVolumeDeclared(true).storyPhase("EPILOGUE")
                .build();

        String prompt = service.buildPlanPrompt(ctx, 11, 17, finalVolume, true, null, null);

        assertTrue(prompt.contains("本段只规划第 11 章至第 17 章（共 7 章）"));
        assertTrue(prompt.contains("chapterNo 从 11 开始连续递增"));
        assertTrue(prompt.contains("\"chapterNo\":11"));
        // 末段承担全书收束要求
        assertTrue(prompt.contains("只有收官卷的最后一章才标 finale"));
        assertTrue(prompt.contains("remainingFinaleBeats 必须在正文中全部完成"));
    }

    @Test
    void buildPlanPrompt_middleSegmentRelaxesFinaleRequirement() {
        when(chapterMemoryService.buildMemoryBlocks(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new PromptBudgetGuard.Block("近章摘要", 2, true, "【前情记忆标记】")));
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .storyContext("故事上下文")
                .chapterOffset(10)
                .build();

        String prompt = service.buildPlanPrompt(ctx, 11, 17, blueprint(2, 11, 55), false, null, null);

        // 非末段：不要求段尾标 finale，避免卷末收束章提前出现
        assertFalse(prompt.contains("只有收官卷的最后一章才标 finale"));
        assertTrue(prompt.contains("本段末章不得因批次结束而标 finale"));
    }

    @Test
    void buildPlanPrompt_segmentUsesItsOwnBlueprintInMemoryPrefix() {
        StageBlueprintEntity stage2 = blueprint(2, 11, 55);
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .storyContext("故事上下文")
                .chapterSummaries(List.of(ChapterSummaryEntity.builder().chapterNo(10).build()))
                .stageBlueprint(blueprint(1, 1, 10))
                .build();

        service.buildPlanPrompt(ctx, 11, 17, stage2, true, null, null);

        // 记忆子块必须按段归属蓝图构造（第 5 个入参），而非上下文默认蓝图
        ArgumentCaptor<StageBlueprintEntity> captor = ArgumentCaptor.forClass(StageBlueprintEntity.class);
        verify(chapterMemoryService).buildMemoryBlocks(any(), any(), any(), any(), captor.capture(), any(), any(), any());
        assertSame(stage2, captor.getValue());
    }

    @Test
    void buildPlanPrompt_passesQualityDebtsIntoMemoryPrefix() {
        // 规划层必须看到审校确认的质量债（与正文层同源）：此前传 null，规划会把已标记的
        // 劣质模式（重复套路/低密度编排）再安排一遍，写作层只能照办
        List<QualityDebtEntity> debts = List.of(QualityDebtEntity.builder().chapterNo(5).resolved(false).build());
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .storyContext("故事上下文")
                .chapterOffset(5)
                .chapterSummaries(List.of(ChapterSummaryEntity.builder().chapterNo(5).build()))
                .qualityDebts(debts)
                .build();

        service.buildPlanPrompt(ctx);

        ArgumentCaptor<List<QualityDebtEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(chapterMemoryService).buildMemoryBlocks(any(), any(), any(), captor.capture(), any(), any(), any(), any());
        assertSame(debts, captor.getValue(), "质量债必须按上下文实例原样传入，缺省不得回落为 null");
    }

    @Test
    void buildPlanPrompt_gatesInputSegmentButKeepsPlanContract() {
        // 输入段总量守门：故事设定 + 记忆前缀会随连载增长；超限时截断输入段，
        // 但"要求"与 schema 段属于规划契约，必须原样保留
        budgetProperties.setPlanPrefixChars(300);
        // 记忆子块 priority=1（高于设定段的 5）：超限时应保住记忆、丢设定
        when(chapterMemoryService.buildMemoryBlocks(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new PromptBudgetGuard.Block("近章摘要", 1, true, "记忆".repeat(500))));
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .storyContext("设定".repeat(500))
                .build();

        String prompt = service.buildPlanPrompt(ctx);

        assertTrue(prompt.contains("[本块已按前缀总预算截断]"), "超限输入段应被截断");
        assertTrue(prompt.startsWith("记忆"), "存储更久的前情记忆优先保留（priority 更高）并排在最前");
        assertFalse(prompt.contains("设定".repeat(50)), "预算耗尽后设定段应被丢弃");
        assertTrue(prompt.contains("请先规划完整章节计划"), "规划契约段不得被裁");
        assertTrue(prompt.contains("\"chapterNo\":1"), "输出 schema 不得被裁");
    }

    @Test
    void buildPlanPrompt_injectsEntryConstraintsGuardrail() {
        // 分段蓝图带进入护栏：护栏清单必须出现在规划 prompt 中，并要求先补齐过渡
        StageBlueprintEntity stage2 = blueprint(2, 11, 55);
        stage2.setEntryConstraints(List.of("主角已突破至炼气九层", "幽冥谷与外门已公开敌对"));
        when(chapterMemoryService.buildMemoryBlocks(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .storyContext("故事上下文")
                .chapterSummaries(List.of(ChapterSummaryEntity.builder().chapterNo(10).build()))
                .build();

        String prompt = service.buildPlanPrompt(ctx, 11, 20, stage2, true, null, null);

        assertTrue(prompt.contains("【阶段进入护栏】"));
        assertTrue(prompt.contains("主角已突破至炼气九层"));
        assertTrue(prompt.contains("幽冥谷与外门已公开敌对"));
        assertTrue(prompt.contains("过渡补齐"));
    }

    @Test
    void buildPlanPrompt_noEntryConstraintsOmitsGuardrail() {
        when(chapterMemoryService.buildMemoryBlocks(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .storyContext("故事上下文")
                .build();

        String prompt = service.buildPlanPrompt(ctx, 1, 10, blueprint(1, 1, 10), true, null, null);

        assertFalse(prompt.contains("【阶段进入护栏】"));
    }

    @Test
    void settlementRecovery_capsInjectionAndPrioritizesMostStagnant() {
        // 15 条限期回收（埋设章号 1~15），注入上限 12：取滞留最久（章号最小）的 12 条，余 3 条留池
        List<ForeshadowSettlementEntity.SettlementDecision> decisions = new ArrayList<>();
        for (int ch = 1; ch <= 15; ch++) {
            decisions.add(ForeshadowSettlementEntity.SettlementDecision.builder()
                    .content("未填伏笔" + ch).chapterNo(ch)
                    .decision(ForeshadowSettlementEntity.DECISION_RECOVER).reason("连接点" + ch)
                    .build());
        }
        List<ForeshadowSettlementEntity> settlements = List.of(ForeshadowSettlementEntity.builder()
                .stageNo(4).stageEndChapter(60).decisions(decisions).build());

        StringBuilder sb = new StringBuilder();
        ChapterPlanPromptService.appendSettlementRecovery(sb, settlements, 61);

        String prompt = sb.toString();
        assertTrue(prompt.contains("【卷末清账·限期回收】"));
        assertTrue(prompt.contains("未填伏笔1（第1章埋设；连接点：连接点1）"));
        assertTrue(prompt.contains("未填伏笔12（第12章埋设；连接点：连接点12）"));
        assertFalse(prompt.contains("未填伏笔13（第13章埋设"));
        assertTrue(prompt.contains("另有 3 条经裁决可自然兑现的未填伏笔本段暂不安排"));
    }

    @Test
    void settlementRecovery_otherSegmentStartGetsNothing() {
        // 结算阶段末章 60 只对起点 61 的段生效；起点 62（段中/无对应结算）不注入
        List<ForeshadowSettlementEntity> settlements = List.of(ForeshadowSettlementEntity.builder()
                .stageNo(4).stageEndChapter(60)
                .decisions(List.of(ForeshadowSettlementEntity.SettlementDecision.builder()
                        .content("未填伏笔").chapterNo(4)
                        .decision(ForeshadowSettlementEntity.DECISION_RECOVER).reason("连接点").build()))
                .build());

        StringBuilder sb = new StringBuilder();
        ChapterPlanPromptService.appendSettlementRecovery(sb, settlements, 62);

        assertTrue(sb.isEmpty());
    }

    @Test
    void densityFeedback_lowDensityChapterInjectsInstruction() {
        // 最近窗口内有有效字 < 1500 的章节 → 注入密度反馈
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        for (int ch = 1; ch <= 5; ch++) {
            summaries.add(ChapterSummaryEntity.builder()
                    .chapterNo(ch).validChars(2000).keyEventCount(3).build());
        }
        summaries.get(3).setValidChars(1100);
        summaries.get(3).setKeyEventCount(2);

        StringBuilder sb = new StringBuilder();
        ChapterPlanPromptService.appendDensityFeedback(sb, summaries);

        String text = sb.toString();
        assertTrue(text.contains("【密度反馈】"));
        assertTrue(text.contains("第4章（实际 1100 有效字 / 2 个关键事件）"));
        assertTrue(text.contains("关键事件 ≥ 3"));
        assertTrue(text.contains("≥ 4 个"), "climax/finale 事件下沿必须高于常规章（高潮章写薄了等于白给）");
        // 与节奏指令对齐：留白须标 transition，但不得借它逃避推进
        assertTrue(text.contains("chapterType=transition"));
        assertTrue(text.contains("transition 章仍须至少有一处实质变化"));
    }

    @Test
    void densityFeedback_transitionChapterIsExempt() {
        // 过渡章本就是有意留白：不得计入"供给不足"，否则规划层会给过渡章也塞满事件（每章换场的成因之一）
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        for (int ch = 1; ch <= 5; ch++) {
            summaries.add(ChapterSummaryEntity.builder()
                    .chapterNo(ch).validChars(2000).keyEventCount(3).build());
        }
        summaries.get(3).setValidChars(900);
        summaries.get(3).setKeyEventCount(1);
        summaries.get(3).setChapterType(ChapterTypeVO.TRANSITION.getCode());

        StringBuilder sb = new StringBuilder();
        ChapterPlanPromptService.appendDensityFeedback(sb, summaries);

        assertFalse(sb.toString().contains("【密度反馈】"), "过渡章不应触发密度反馈");
    }

    @Test
    void placeTrajectory_isInjectedWithBudgetAndChurnWarning() {
        // 地点轨迹回灌：规划层此前完全看不到地点信息，导致每章换场
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        for (int ch = 1; ch <= 6; ch++) {
            summaries.add(ChapterSummaryEntity.builder()
                    .chapterNo(ch)
                    .placePoint("地点" + ch)   // 6 章 6 个地点 = 每章一个新地点
                    .build());
        }

        String block = cn.novel.yonren.domain.novel.service.armory.quality.PlaceTrajectoryPolicy
                .renderTrajectory(summaries);

        assertNotNull(block);
        assertTrue(block.contains("【地点轨迹】"));
        assertTrue(block.contains("第1章：地点1"));
        assertTrue(block.contains("新增地点 ≤ 3"));
        assertTrue(block.contains("严禁每章换新场景"));
        assertTrue(block.contains("⚠ 警示"), "每章一个新地点必须触发警示");
    }

    @Test
    void placeTrajectory_reusedPlaceHasNoChurnWarning() {
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        for (int ch = 1; ch <= 6; ch++) {
            summaries.add(ChapterSummaryEntity.builder()
                    .chapterNo(ch).placePoint(ch <= 4 ? "阵法堂" : "后山禁地")
                    .build());
        }

        String block = cn.novel.yonren.domain.novel.service.armory.quality.PlaceTrajectoryPolicy
                .renderTrajectory(summaries);

        assertNotNull(block);
        assertTrue(block.contains("阵法堂×4"), "复用地点应被列出供规划层继续复用");
        assertFalse(block.contains("⚠ 警示"), "有复用即不算每章换场");
    }

    @Test
    void densityFeedback_allDenseEnough_injectsNothing() {
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        for (int ch = 1; ch <= 3; ch++) {
            summaries.add(ChapterSummaryEntity.builder()
                    .chapterNo(ch).validChars(2200).keyEventCount(3).build());
        }

        StringBuilder sb = new StringBuilder();
        ChapterPlanPromptService.appendDensityFeedback(sb, summaries);

        assertTrue(sb.isEmpty());
    }

    @Test
    void densityFeedback_legacyNullValidChars_skipped() {
        // 老数据 validChars 为 null 的章节不参与判定（宁可漏报不误伤）
        List<ChapterSummaryEntity> summaries = List.of(
                ChapterSummaryEntity.builder().chapterNo(1).build(),
                ChapterSummaryEntity.builder().chapterNo(2).validChars(2000).keyEventCount(3).build());

        StringBuilder sb = new StringBuilder();
        ChapterPlanPromptService.appendDensityFeedback(sb, summaries);

        assertTrue(sb.isEmpty());
    }

    @Test
    void densityFeedback_onlyLooksBackFiveChapters() {
        // 第 1 章低密度但超出回看窗口（最近 5 章为 3-7），不注入
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        summaries.add(ChapterSummaryEntity.builder().chapterNo(1).validChars(800).keyEventCount(1).build());
        for (int ch = 2; ch <= 7; ch++) {
            summaries.add(ChapterSummaryEntity.builder()
                    .chapterNo(ch).validChars(2500).keyEventCount(3).build());
        }

        StringBuilder sb = new StringBuilder();
        ChapterPlanPromptService.appendDensityFeedback(sb, summaries);

        assertTrue(sb.isEmpty(), "窗口外的早期章节不回溯");
    }

    // ---- 悬念推进锚：主线原地转圈的结构性防线 ----

    @Test
    void buildPlanPrompt_injectsSuspenseLadderAnchorAndSchema() {
        List<String> ladder = List.of("双方都不知道对方身份", "一方起疑并留下物证", "双方各持证据", "身份被公开摊牌");
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .storyContext("故事上下文")
                .stageBlueprint(StageBlueprintEntity.builder()
                        .stageNo(1).stageGoal("目标")
                        .coreSuspense("双方的真实身份何时暴露")
                        .suspenseLadder(ladder)
                        .build())
                .build();

        String prompt = service.buildPlanPrompt(ctx);

        assertTrue(prompt.contains("【悬念推进锚】"), "档位表必须摆到规划那一刻");
        assertTrue(prompt.contains("双方的真实身份何时暴露"), "核心悬念要一并给出");
        assertTrue(prompt.contains("1. 双方都不知道对方身份"), "档位要带序呈现，模型才能按序回填");
        assertTrue(prompt.contains("不得连续 3 章"), "规则要写明，否则模型不知道会被机械驳回");
        assertTrue(prompt.contains("suspenseBeat"), "要求段与 schema 都要有该字段");
        assertTrue(prompt.contains("\"suspenseBeat\":\"档位表原文之一\""), "JSON schema 必须回填字段");
    }

    @Test
    void buildPlanPrompt_omitsAnchorWithoutLadder() {
        // 无蓝图模式 / 老故事：不注入锚块（校验侧同样会跳过，不得凭空判定）
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .storyContext("故事上下文")
                .stageBlueprint(blueprint(1, 1, 6))
                .build();

        String prompt = service.buildPlanPrompt(ctx);

        assertFalse(prompt.contains("【悬念推进锚】"));
    }

    // ---- 导演通道：作者创作要点注入规划 prompt 顶部 ----

    @Test
    void buildPlanPrompt_creativeNotesInjectedAtTop() {
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .storyContext("故事上下文")
                .creativeNotes("金手指是完整记忆，禁止发明记忆迷雾；第1章必须展示信息差的第一笔价值")
                .build();

        String prompt = service.buildPlanPrompt(ctx);

        assertTrue(prompt.contains("【本批创作要点·作者指令】"), "作者指令块必须存在");
        assertTrue(prompt.contains("禁止发明记忆迷雾"));
        assertTrue(prompt.contains("与既有约束冲突时以本清单为准"), "冲突优先级声明缺失");
        // 在最顶部：先于故事设定出现
        assertTrue(prompt.indexOf("本批创作要点") < prompt.indexOf("故事上下文"),
                "作者指令必须先于故事设定（prompt 最顶部）");
    }

    @Test
    void buildPlanPrompt_withoutNotes_hasNoDirectorBlock() {
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .storyContext("故事上下文")
                .build();

        String prompt = service.buildPlanPrompt(ctx);

        assertFalse(prompt.contains("本批创作要点"), "未传要点时不得出现导演块");
    }

    private StageBlueprintEntity blueprint(int stageNo, int start, Integer end) {
        return StageBlueprintEntity.builder()
                .stageNo(stageNo)
                .startChapter(start)
                .endChapter(end)
                .build();
    }

    // ==================== 章级主线推进注入块 ====================

    /**
     * 注入块只渲染**本段用得到的章**：蓝图按 15 章窗产出，而一段通常只有 5 章——
     * 全量渲染纯占前缀预算（而前缀预算实测长期 99-100%）。
     */
    @Test
    void appendMainLineBlock_rendersOnlyChaptersInSegment() {
        StageBlueprintEntity blueprint = StageBlueprintEntity.builder()
                .startChapter(11).endChapter(25)
                .mainLineByChapter(List.of(
                        new StageBlueprintEntity.MainLineBeat(11, "首次察觉异样"),
                        new StageBlueprintEntity.MainLineBeat(12, "向师姐试探"),
                        new StageBlueprintEntity.MainLineBeat(13, "确认识海被侵"),
                        new StageBlueprintEntity.MainLineBeat(14, "本段之外，不该渲染")))
                .build();
        StringBuilder sb = new StringBuilder();

        ChapterPlanPromptService.appendMainLineBlock(sb, blueprint, 11, 13);
        String text = sb.toString();

        assertTrue(text.contains("【章级主线推进】"), text);
        assertTrue(text.contains("第11章：首次察觉异样"), text);
        assertTrue(text.contains("第13章：确认识海被侵"), text);
        assertFalse(text.contains("第14章"), "段外章节不得渲染：" + text);
        assertTrue(text.contains("逐字取自"), "必须写明是照抄而非自由发挥：" + text);
    }

    /** 蓝图无该字段（老数据/补采失败）→ 整块不出现；不能提一个不存在的块让模型去猜 */
    @Test
    void appendMainLineBlock_absentWhenBlueprintHasNoField() {
        StringBuilder sb = new StringBuilder();

        ChapterPlanPromptService.appendMainLineBlock(sb, blueprint(1, 1, 10), 1, 5);

        assertEquals("", sb.toString());
    }

    /** 排期窗与段无交集（越界）→ 不渲染，避免出现空块 */
    @Test
    void appendMainLineBlock_absentWhenNoOverlap() {
        StageBlueprintEntity blueprint = StageBlueprintEntity.builder()
                .startChapter(11).endChapter(25)
                .mainLineByChapter(List.of(
                        new StageBlueprintEntity.MainLineBeat(11, "首次察觉异样")))
                .build();
        StringBuilder sb = new StringBuilder();

        ChapterPlanPromptService.appendMainLineBlock(sb, blueprint, 30, 34);

        assertEquals("", sb.toString());
    }

    // ==================== 输出 JSON 示例（= 模型的有效 schema） ====================

    /**
     * **回归：`mainLineAdvance` 必须出现在输出 JSON 示例里**。
     *
     * <p>2026-10-02 实测教训：只写要求（8.2）与注入块、**漏了示例里的字段**，
     * 结果 qwen3.7-max 五章全部不回填 `mainLineAdvance`，闸门打回重规划后依然为空，
     * 只能二次放行。**要求文本 ≠ 契约，字段不进示例就等于不存在。**
     */
    @Test
    void segmentPrompt_declaresMainLineAdvanceInJsonExample() {
        StageBlueprintEntity blueprint = StageBlueprintEntity.builder()
                .stageNo(3).startChapter(16).endChapter(20)
                .mainLineByChapter(List.of(
                        new StageBlueprintEntity.MainLineBeat(16, "首次登门受挫")))
                .build();
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .stageBlueprint(blueprint)
                .chapterSummaries(new ArrayList<>())
                .build();

        String prompt = service.buildPlanPrompt(ctx, 16, 20, blueprint, false, null, null);

        assertTrue(prompt.contains("\"mainLineAdvance\""),
                "输出示例必须包含 mainLineAdvance，否则模型不会回填该字段");
    }

    /** 蓝图没有章级推进时，示例里也不该出现该字段（避免要求一个不存在的块） */
    @Test
    void segmentPrompt_omitsMainLineAdvanceWithoutBlueprintField() {
        StageBlueprintEntity blueprint = StageBlueprintEntity.builder()
                .stageNo(3).startChapter(16).endChapter(20).build();
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .stageBlueprint(blueprint)
                .chapterSummaries(new ArrayList<>())
                .build();

        String prompt = service.buildPlanPrompt(ctx, 16, 20, blueprint, false, null, null);

        assertFalse(prompt.contains("\"mainLineAdvance\""),
                "无章级推进时不应在示例里声明该字段");
    }

    // ==================== 伏笔排期注入块（P2b） ====================

    private static ForeshadowScheduleEntity.ScheduleItem sched(String intent, int plant, int payoff, String status) {
        return ForeshadowScheduleEntity.ScheduleItem.builder()
                .intent(intent).plantChapter(plant).payoffChapter(payoff)
                .status(status).actualPlantChapter(status.equals(ForeshadowScheduleEntity.STATUS_PLANTED) ? plant : null)
                .build();
    }

    private static List<ForeshadowScheduleEntity> schedulesOf(ForeshadowScheduleEntity.ScheduleItem... items) {
        return List.of(ForeshadowScheduleEntity.builder().stageNo(1).items(new ArrayList<>(List.of(items))).build());
    }

    /**
     * 三块各自的触发条件：本段必埋（且**本段不得兑现**）、本段必兑、**逾期补收**。
     *
     * <p>「本段不得兑现」这半句是把 D3 的抽象要求落成可执行约束的关键——
     * 否则段计划看到"本段要埋 X"的第一反应就是顺手收掉。
     */
    @Test
    void scheduleBlock_rendersThreeSections() {
        StringBuilder sb = new StringBuilder();
        ChapterPlanPromptService.appendForeshadowScheduleBlock(sb, schedulesOf(
                sched("顾老头是否收徒", 22, 35, ForeshadowScheduleEntity.STATUS_PLANNED),
                sched("鸿运科技骗局清算", 18, 23, ForeshadowScheduleEntity.STATUS_PLANTED),
                sched("陆周氏银锁承诺", 12, 19, ForeshadowScheduleEntity.STATUS_PLANTED)), 21, 25);
        String text = sb.toString();

        assertTrue(text.contains("必须埋设"), text);
        assertTrue(text.contains("本段不得兑现"), "必须明确禁止本段兑现：" + text);
        assertTrue(text.contains("必须兑现"), text);
        assertTrue(text.contains("逾期补收"), text);
        assertTrue(text.contains("顾老头是否收徒"), text);
    }

    /**
     * **逾期线必须每段持续注入**（B2）：payoff 落在本段之后的 PLANTED 线若不注入，
     * 它下一段就掉出视野、只能等阶段出口——而阶段可长达 30-80 章。
     */
    @Test
    void scheduleBlock_persistsOverdueInjection() {
        StringBuilder sb = new StringBuilder();
        // 段区间 21-25；该线计划第 19 章收，已逾期
        ChapterPlanPromptService.appendForeshadowScheduleBlock(sb, schedulesOf(
                sched("一条早已该收的老线", 12, 19, ForeshadowScheduleEntity.STATUS_PLANTED)), 21, 25);

        assertTrue(sb.toString().contains("已逾期"), "逾期线必须每段持续注入：" + sb);
    }

    /** 无排期表/本段无相关条目 → 整块不出现（老故事行为不变） */
    @Test
    void scheduleBlock_absentWhenNothingRelevant() {
        StringBuilder sb1 = new StringBuilder();
        ChapterPlanPromptService.appendForeshadowScheduleBlock(sb1, null, 21, 25);
        assertEquals("", sb1.toString());

        StringBuilder sb2 = new StringBuilder();
        ChapterPlanPromptService.appendForeshadowScheduleBlock(sb2, schedulesOf(
                sched("与本段无关的线", 40, 45, ForeshadowScheduleEntity.STATUS_PLANNED)), 21, 25);
        assertEquals("", sb2.toString());
    }

    /**
     * 同一条线在多个阶段块里各存一份时必须去重，且**保留最后一条**。
     *
     * <p>实测（第 96-100 章那批）：8 个逾期注入名额里 5 条逐字是同一条、2 条是同一条弹珠，
     * 整份清单只覆盖 3 件事——规划层被反复命令去收同三样东西，这就是"重复拖沓"的来源。
     */
    @Test
    void scheduleBlock_dedupesSameIntentAcrossBlocks() {
        List<ForeshadowScheduleEntity> twoBlocks = List.of(
                ForeshadowScheduleEntity.builder().stageNo(1).items(new ArrayList<>(List.of(
                        sched("主角无意识写出2026年的日期", 5, 19,
                                ForeshadowScheduleEntity.STATUS_PLANTED)))).build(),
                ForeshadowScheduleEntity.builder().stageNo(2).items(new ArrayList<>(List.of(
                        sched("主角无意识写出2026年的日期", 5, 17,
                                ForeshadowScheduleEntity.STATUS_PLANTED)))).build());
        StringBuilder sb = new StringBuilder();

        ChapterPlanPromptService.appendForeshadowScheduleBlock(sb, twoBlocks, 21, 25);

        String text = sb.toString();
        int at = text.indexOf("主角无意识写出2026年的日期");
        assertTrue(at >= 0, "逾期条目必须注入：" + text);
        assertEquals(-1, text.indexOf("主角无意识写出2026年的日期", at + 1),
                "同一 intent 跨块出现只应注入一次：" + text);
        assertTrue(text.contains("第17章"), "同键时应保留最后一条（payoff 17 在块 2）：" + text);
        assertFalse(text.contains("第19章"), "被覆盖的那条不应出现：" + text);
    }

    /**
     * 去重必须发生在**条数上限之前**：否则上限仍会被重复项吃满，其它线进不了注入。
     * 样本复刻实测形态（5 条重复 + 6 条独立 = 11 条，上限 8）。
     */
    @Test
    void scheduleBlock_overdueLimitNotConsumedByDuplicates() {
        List<ForeshadowScheduleEntity.ScheduleItem> items = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            items.add(sched("主角无意识写出2026年的日期", 5, 19,
                    ForeshadowScheduleEntity.STATUS_PLANTED));
        }
        for (int i = 1; i <= 6; i++) {
            items.add(sched("第" + i + "条独立的线", 5, 19,
                    ForeshadowScheduleEntity.STATUS_PLANTED));
        }
        StringBuilder sb = new StringBuilder();

        ChapterPlanPromptService.appendForeshadowScheduleBlock(sb,
                schedulesOf(items.toArray(new ForeshadowScheduleEntity.ScheduleItem[0])), 21, 25);

        String text = sb.toString();
        assertTrue(text.contains("第1条独立的线"), text);
        assertTrue(text.contains("第6条独立的线"),
                "7 条不同条目应全部装进 8 条上限——去重前的实现只能装下前 3 条：" + text);
    }

    /**
     * 与【卷末清账·限期回收】块去重：同一条线被两块各说一遍时，保留结算块那条
     * （它带连接点信息，对规划更有用），排期块让位。
     */
    @Test
    void scheduleBlock_skipsIntentsAlreadyListedBySettlement() {
        String content = "主角无意识写出2026年的日期";
        List<ForeshadowSettlementEntity> settlements = List.of(ForeshadowSettlementEntity.builder()
                .stageNo(1)
                .stageEndChapter(20)
                .decisions(new ArrayList<>(List.of(ForeshadowSettlementEntity.SettlementDecision.builder()
                        .content(content).chapterNo(5)
                        .decision(ForeshadowSettlementEntity.DECISION_RECOVER)
                        .reason("第95章已由周秀兰点出").build())))
                .build());
        StringBuilder sb = new StringBuilder();

        ChapterPlanPromptService.appendForeshadowScheduleBlock(sb,
                schedulesOf(sched(content, 5, 19, ForeshadowScheduleEntity.STATUS_PLANTED)), 21, 25,
                ChapterPlanPromptService.settlementListedIntents(settlements, 21));

        assertEquals("", sb.toString(), "结算块已列出的线不应在排期块重复下令：" + sb);
    }

    /**
     * 逾期挂起：逾期 ≥ 30 章的线**停止自动补收**，改入「逾期挂起·待人工裁定」块。
     *
     * <p>标定依据（第 96-100 章实测逾期分布，正好两簇）：仍在推进的条目逾期 11-22 章，
     * 卡死的三条逾期 48-74 章。继续每段下令的实测后果是规划层每段都把它安排一遍——
     * 同一件事被反复重写（"藏信/格子纸"重复拖沓的机制）。
     */
    @Test
    void scheduleBlock_suspendsChronicallyOverdueInsteadOfReordering() {
        StringBuilder sb = new StringBuilder();

        ChapterPlanPromptService.appendForeshadowScheduleBlock(sb, schedulesOf(
                sched("主角无意识写出2026年的日期", 5, 45, ForeshadowScheduleEntity.STATUS_PLANTED),
                sched("蓝玻璃弹珠信物", 9, 26, ForeshadowScheduleEntity.STATUS_PLANTED)), 96, 100);

        String text = sb.toString();
        assertTrue(text.contains("逾期挂起·待人工裁定"), text);
        assertTrue(text.contains("已逾期 51 章"), "必须写明逾期了多少章：" + text);
        assertTrue(text.contains("本段不再自动安排"), text);
        assertFalse(text.contains("本段必须补收"), "挂起项不得再带「必须补收」的命令：" + text);
    }

    /** 未达挂起阈值的逾期线仍走「必须补收」——挂起只针对慢性欠账，不放过普通逾期 */
    @Test
    void scheduleBlock_stillOrdersRecoveryBelowSuspendThreshold() {
        StringBuilder sb = new StringBuilder();

        ChapterPlanPromptService.appendForeshadowScheduleBlock(sb, schedulesOf(
                sched("一条刚过期的线", 5, 49, ForeshadowScheduleEntity.STATUS_PLANTED)), 51, 55);

        String text = sb.toString();
        assertTrue(text.contains("逾期补收"), text);
        assertTrue(text.contains("本段必须补收"), text);
        assertFalse(text.contains("逾期挂起·待人工裁定"),
                "只逾期 2 章的线不该被挂起（尾句的豁免说明不算）：" + text);
    }

    /**
     * 长线在养：阶段出口判 PARKED 的线不再被要求回收，但必须**明说这是有意为之**——
     * 否则规划层会把它当"被忘了的线"自发安排回收（实测 2026 那条就是这样被反复重写的）。
     */
    @Test
    void scheduleBlock_showsParkedLongLinesWithoutOrderingRecovery() {
        ForeshadowScheduleEntity.ScheduleItem parked = sched("主角无意识写出2026年的日期", 5, 45,
                ForeshadowScheduleEntity.STATUS_PLANTED);
        parked.setParkedAtChapter(100);
        StringBuilder sb = new StringBuilder();

        ChapterPlanPromptService.appendForeshadowScheduleBlock(sb, schedulesOf(parked,
                sched("另一条刚过期的线", 5, 49, ForeshadowScheduleEntity.STATUS_PLANTED)), 51, 55);

        String text = sb.toString();
        assertTrue(text.contains("长线在养·本段不主动安排"), text);
        assertTrue(text.contains("主角无意识写出2026年的日期"), "在养项仍要点名（否则会被当忘了）：" + text);
        assertFalse(text.contains("《主角无意识写出2026年的日期》（原计划"),
                "在养项不得再进逾期桶被要求补收：" + text);
        assertTrue(text.contains("另一条刚过期的线"), "其它线照常催收：" + text);
    }

    /** 在养必须压过「逾期挂起」：前者是有意为之、后者是待人工裁定，两者不该同时出现 */
    @Test
    void scheduleBlock_parkedLineNeverFallsIntoOverdueOrSuspended() {
        ForeshadowScheduleEntity.ScheduleItem parked = sched("主角无意识写出2026年的日期", 5, 45,
                ForeshadowScheduleEntity.STATUS_PLANTED);
        parked.setParkedAtChapter(95);
        StringBuilder sb = new StringBuilder();

        // 逾期 51 章：若不看在养标记，这本该落进「逾期挂起」
        ChapterPlanPromptService.appendForeshadowScheduleBlock(sb, schedulesOf(parked), 96, 100);

        String text = sb.toString();
        assertTrue(text.contains("长线在养"), text);
        assertFalse(text.contains("■ **逾期挂起·待人工裁定**"),
                "在养项不该再进挂起块（尾句的豁免说明不算）：" + text);
        assertFalse(text.contains("本段必须补收"), "在养项不该再被催收：" + text);
    }

    // ==================== 对白坍缩反馈块（2026-10-09） ====================

    /**
     * 最近 5 章里有轮次密度低于劣化线的章 → 注入【对白坍缩反馈】，责令本段把冲突写进台词。
     *
     * <p>实测第 96 章 4.4 次/千字（劣化线 6.0，同批其它章 12-27）：计划的 4 条关键事件里
     * 有 2 条是对话承载，**数量合规**，但篇幅最大的那条是纯独白推导——规则只管条数、
     * 不管篇幅占比，写手于是交付了动作分解。责任在规划层。
     */
    @Test
    void dialogueCollapseFeedback_injectedWhenRecentChapterCollapsed() {
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        for (int ch = 91; ch <= 95; ch++) {
            ChapterSummaryEntity s = ChapterSummaryEntity.builder()
                    .chapterNo(ch)
                    .validChars(ch == 95 ? 2066 : 2000)
                    .dialogueUtterances(ch == 95 ? 9 : 25)
                    .build();
            summaries.add(s);
        }
        StringBuilder sb = new StringBuilder();

        ChapterPlanPromptService.appendDialogueCollapseFeedback(sb, summaries);

        String text = sb.toString();
        assertTrue(text.contains("【对白坍缩反馈】"), text);
        assertTrue(text.contains("第95章"), "必须点名崩掉的章号：" + text);
        assertTrue(text.contains("4.4"), "必须给出实测密度供规划层定位：" + text);
        assertTrue(text.contains("对话承载事件提到 ≥2 条"), text);
    }

    /** 反向：对白密度健康时不注入（欠采样与健康态都不唠叨，否则模型会当噪声忽略） */
    @Test
    void dialogueCollapseFeedback_absentWhenHealthy() {
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        for (int ch = 91; ch <= 95; ch++) {
            summaries.add(ChapterSummaryEntity.builder()
                    .chapterNo(ch).validChars(2000).dialogueUtterances(25).build());
        }
        StringBuilder sb = new StringBuilder();

        ChapterPlanPromptService.appendDialogueCollapseFeedback(sb, summaries);

        assertEquals("", sb.toString(), "健康态不应注入反馈块：" + sb);
    }

    /**
     * 计划层必须把"对白承载"与"场面级事件"写成硬要求（2026-10-10）。
     *
     * <p>实测：同一段章节、同一份计划重跑一次，对白句数从 139 掉到 97（平均 28→19/章，
     * 而历史 45 章稳在 31-57）。当时的规定是"每章关键事件至少 **1** 个由对话承载"——
     * 一章只有一个对话事件时，其余三个关键事件都写成静态交代，全章就对白塌方，而规则字面上仍然满足。
     * 计划的写法直接决定正文的写法：一句话的事件在正文里只会被展开成一句过渡叙述。
     */
    @Test
    void segmentPrompt_requiresTwoDialogueEventsAndSceneLevelKeyEvents() {
        StageBlueprintEntity blueprint = StageBlueprintEntity.builder()
                .stageNo(3).startChapter(16).endChapter(20).build();
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .stageBlueprint(blueprint)
                .chapterSummaries(new ArrayList<>())
                .build();

        String prompt = service.buildPlanPrompt(ctx, 16, 20, blueprint, false, null, null);

        assertTrue(prompt.contains("至少 2 个**由人物当面的对话/冲突/协作承载"), prompt);
        assertTrue(prompt.contains("谁对谁、围绕什么分歧"), prompt);
        assertTrue(prompt.contains("必须写到「场面」级"), prompt);
        assertTrue(prompt.contains("在场人物 + 起因或分歧 + 落点或结果"), prompt);
        // 对话承载的执法通道：字段必须进输出示例——"字段不进示例就等于不存在"（mainLineAdvance 事故同款）
        assertTrue(prompt.contains("\"dialogueScenes\":["), "输出示例必须含 dialogueScenes 字段：" + prompt);
        assertTrue(prompt.contains("逐字复制**进该章 dialogueScenes"), prompt);
        assertTrue(prompt.contains("transition 章至少 1 个"), prompt);
    }
}
