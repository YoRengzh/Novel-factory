package cn.novel.yonren.domain.novel.service.armory.candidate;

import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.StyleStatEntity;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.prompt.valobj.PromptContext;
import cn.novel.yonren.domain.novel.service.armory.quality.GateResult;
import cn.novel.yonren.domain.novel.service.armory.quality.GateResult.PassGrade;
import cn.novel.yonren.domain.novel.service.armory.quality.QualityGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 候选选优测试：未启用/高置信通过不触发；挑战者机械违规出局；
 * 评审保留原稿；挑战者胜出复审不过回退原稿；胜出且复审通过采纳新稿
 */
class ChapterCandidateServiceTest {

    private static final String RAW_CHALLENGER = "{\"chapterNo\":1,\"title\":\"挑战稿\",\"content\":\"挑战稿正文，情节完整。\"}";

    @Mock
    private cn.novel.yonren.domain.novel.adapter.llm.LlmGateway llmGateway;

    @Mock
    private cn.novel.yonren.domain.novel.service.armory.llm.LlmInvokeService llmInvokeService;

    @Mock
    private QualityGate qualityGate;

    @Mock
    private CandidateSampleService candidateSampleService;

    @InjectMocks
    private ChapterCandidateService candidateService;

    private StoryProperties storyProperties;
    private ArmoryCommandEntity command;
    private ChapterPlanItemEntity item;
    private ChapterContentEntity chapterContent;
    private GateResult lowConfidenceGate;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        storyProperties = new StoryProperties();
        StoryProperties.CandidateProperties candidate = new StoryProperties.CandidateProperties();
        candidate.setEnabled(true);
        // MINOR_RESIDUE 与 DEBT 的默认开关均已关闭（见 CandidateProperties 注释）。
        // 本类多数用例测的是"候选机制本身"（生成/盲评/回退），故显式打开 MINOR 通道，
        // 让 fixture 的 MINOR_RESIDUE 仍能走到候选；**默认值行为另有专门用例覆盖**（见文件末尾）。
        candidate.setTriggerOnMinorResidue(true);
        storyProperties.setCandidate(candidate);

        command = ArmoryCommandEntity.builder()
                .storyVO(new cn.novel.yonren.domain.novel.model.valobj.StoryVO())
                .storyContextEntity(cn.novel.yonren.domain.novel.model.entity.StoryContextEntity.builder()
                        .theme("都市重生").style("年代成长")
                        .worldSetting("二十世纪九十年代的巷子生活").build())
                .build();
        item = ChapterPlanItemEntity.builder().chapterNo(1).title("第1章").goal("目标").build();
        chapterContent = ChapterContentEntity.builder().chapterNo(1).title("原稿").content("原稿正文。").build();
        // MINOR 条数须达到 minor-residue-threshold（默认 6）才触发，故用 6 条
        lowConfidenceGate = GateResult.of(List.of(), 6, 0);
    }

    private ChapterCandidateService serviceWithProperties() {
        // @InjectMocks 不注入普通字段，这里用反射塞入 storyProperties（构造注入的替代，测试专用）
        org.springframework.test.util.ReflectionTestUtils.setField(candidateService, "storyProperties", storyProperties);
        return candidateService;
    }

    @Test
    void disabledOrHighConfidence_noChallenge() {
        storyProperties.getCandidate().setEnabled(false);
        GateResult clean = GateResult.of(List.of(), 0, 0);

        GateResult result = serviceWithProperties().challenge(command, item, PromptContext.builder().build(),
                "prompt", chapterContent, clean, List.of(), StyleStatEntity.builder().build(), 1, null);

        assertSame(clean, result);
        verify(qualityGate, never()).auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any());
    }

    @Test
    void cleanPass_neverTriggers() {
        GateResult clean = GateResult.of(List.of(), 0, 0);

        GateResult result = serviceWithProperties().challenge(command, item, PromptContext.builder().build(),
                "prompt", chapterContent, clean, List.of(), StyleStatEntity.builder().build(), 1, null);

        assertSame(clean, result);
    }

    @Test
    void challengerGenerated_allMechanicalViolations_keepsIncumbent() {
        // 挑战稿命中机械门禁（眼神套话×2）→ 出局
        when(llmInvokeService.invokeWithScene(any(), any(), any(), anyString(), any(), any(), anyString(), any()))
                .thenReturn("{\"chapterNo\":1,\"title\":\"挑战稿\","
                        + "\"content\":\"他眼神复杂地看着她。她眼神复杂地看着他。\"");
        GateResult result = serviceWithProperties().challenge(command, item, PromptContext.builder().build(),
                "prompt", chapterContent, lowConfidenceGate, List.of(), StyleStatEntity.builder().build(), 1, null);

        assertSame(lowConfidenceGate, result);
        assertEquals("原稿", chapterContent.getTitle());
        verify(qualityGate, never()).auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any());
    }

    @Test
    void challengerBelowLengthFloor_keepsIncumbent() {
        // 字数下限：挑战者有效字 < 原稿 60% 直接机械出局，不进盲评——
        // 实测：5 次采纳的挑战者全部比原稿短（ch15 砍半、ch19 -42%），盲评明令不评长短，
        // 短稿结构性占优，必须在盲评前拦下。原稿约 1600 有效字，挑战者约 270 有效字。
        String longIncumbent = "原稿正文，情节推进，细节扎实，对白自然。".repeat(100);
        chapterContent.setContent(longIncumbent);
        String shortChallenger = "{\"chapterNo\":1,\"title\":\"挑战稿\",\"content\":\""
                + "挑战稿正文，情节完整。".repeat(30) + "\"}";
        when(llmInvokeService.invokeWithScene(any(), any(), any(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(shortChallenger);

        GateResult result = serviceWithProperties().challenge(command, item, PromptContext.builder().build(),
                "prompt", chapterContent, lowConfidenceGate, List.of(), StyleStatEntity.builder().build(), 1, null);

        assertSame(lowConfidenceGate, result);
        assertEquals(longIncumbent, chapterContent.getContent(), "缩水挑战者不得替换原稿");
        verify(candidateSampleService).record(any(), eq(1), anyString(), eq("challenger-mechanical-reject"),
                any(), any(), any(), any(), any());
        verify(qualityGate, never()).auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any());
    }

    @Test
    void invalidVerdict_keepsIncumbent() {
        when(llmInvokeService.invokeWithScene(any(), any(), any(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(RAW_CHALLENGER);
        // 评审输出 winner=B（挑战稿被随机定在 A/B 之一）：无论盲排方向如何，用不指向 challenge 的桩不可行——
        // 这里验证评审"不可用"路径（输出 winner=C 非法 → 保留原稿）与原稿不被替换
        when(llmGateway.complete(any(), any())).thenReturn("{\"winner\":\"C\",\"reason\":\"无法判定\"}");

        GateResult result = serviceWithProperties().challenge(command, item, PromptContext.builder().build(),
                "prompt", chapterContent, lowConfidenceGate, List.of(), StyleStatEntity.builder().build(), 1, null);

        assertSame(lowConfidenceGate, result);
        assertEquals("原稿", chapterContent.getTitle());
        verify(candidateSampleService).record(any(), eq(1), anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void judgeUnavailable_keepsIncumbent() {
        when(llmInvokeService.invokeWithScene(any(), any(), any(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(RAW_CHALLENGER);
        when(llmGateway.complete(any(), any())).thenThrow(new RuntimeException("第二模型族未配置"));

        GateResult result = serviceWithProperties().challenge(command, item, PromptContext.builder().build(),
                "prompt", chapterContent, lowConfidenceGate, List.of(), StyleStatEntity.builder().build(), 1, null);

        assertSame(lowConfidenceGate, result);
        assertEquals("原稿正文。", chapterContent.getContent(), "评审不可用保留原稿");
        verify(candidateSampleService).record(any(), eq(1), anyString(), eq("judge-unavailable"),
                any(), any(), any(), any(), any());
    }

    @Test
    void judgeEmptyResponse_retried_secondAttemptDecides() {
        // 空响应（HTTP 200 但正文为空）算失败并重试：第二次正常出裁决
        when(llmInvokeService.invokeWithScene(any(), any(), any(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(RAW_CHALLENGER);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(llmGateway.complete(any(), any())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                return "";  // 第一次空响应
            }
            cn.novel.yonren.domain.novel.model.valobj.LlmCall call = inv.getArgument(1);
            String prompt = call.getUserPrompt();
            int posA = prompt.indexOf("【候选 A】");
            int posB = prompt.indexOf("【候选 B】");
            int posC = prompt.indexOf("挑战稿正文，情节完整。");
            boolean challengerIsA = posC > posA && posC < posB;
            return "{\"winner\":\"" + (challengerIsA ? "A" : "B") + "\",\"reason\":\"重试后判定\"}";
        });
        when(qualityGate.auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any()))
                .thenReturn(GateResult.of(List.of(), 0, 0));

        GateResult result = serviceWithProperties().challenge(command, item, PromptContext.builder().build(),
                "prompt", chapterContent, lowConfidenceGate, List.of(), StyleStatEntity.builder().build(), 1, null);

        assertEquals(GateResult.PassGrade.CLEAN_PASS, result.grade());
        assertEquals("挑战稿", chapterContent.getTitle());
        verify(qualityGate).auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any());
    }

    @Test
    void challengerWins_reAuditClean_adoptsChallenger() {
        when(llmInvokeService.invokeWithScene(any(), any(), any(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(RAW_CHALLENGER);
        // 让评审恒判 challenger：评审调用以 chapter-judge label 发起，此处两次调用（原稿视角/挑战稿视角随机）
        when(llmGateway.complete(any(), any())).thenAnswer(inv -> {
            cn.novel.yonren.domain.novel.model.valobj.LlmCall call = inv.getArgument(1);
            String prompt = call.getUserPrompt();
            // 挑战稿被随机定为 A/B：按其在 prompt 中的位置判定，评审恒判挑战稿胜
            int posA = prompt.indexOf("【候选 A】");
            int posB = prompt.indexOf("【候选 B】");
            int posC = prompt.indexOf("挑战稿正文，情节完整。");
            boolean challengerIsA = posC > posA && posC < posB;
            return "{\"winner\":\"" + (challengerIsA ? "A" : "B") + "\",\"reason\":\"文风更干净\"}";
        });
        when(qualityGate.auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any()))
                .thenReturn(GateResult.of(List.of(), 0, 1));

        GateResult result = serviceWithProperties().challenge(command, item, PromptContext.builder().build(),
                "prompt", chapterContent, lowConfidenceGate, List.of(), StyleStatEntity.builder().build(), 1, null);

        assertEquals(GateResult.PassGrade.REVISED_PASS, result.grade());
        assertEquals("挑战稿", chapterContent.getTitle());
        verify(candidateSampleService).record(any(), eq(1), anyString(), eq("challenger-adopted"),
                any(), any(), eq("challenger"), anyString(), any());
    }

    @Test
    void challengerWins_reAuditDebt_revertsToIncumbent() {
        when(llmInvokeService.invokeWithScene(any(), any(), any(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(RAW_CHALLENGER);
        when(llmGateway.complete(any(), any())).thenAnswer(inv -> {
            cn.novel.yonren.domain.novel.model.valobj.LlmCall call = inv.getArgument(1);
            String prompt = call.getUserPrompt();
            int posA = prompt.indexOf("【候选 A】");
            int posB = prompt.indexOf("【候选 B】");
            int posC = prompt.indexOf("挑战稿正文，情节完整。");
            boolean challengerIsA = posC > posA && posC < posB;
            return "{\"winner\":\"" + (challengerIsA ? "A" : "B") + "\",\"reason\":\"更紧凑\"}";
        });
        when(qualityGate.auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any()))
                .thenReturn(GateResult.of(List.of(
                        cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity.builder()
                                .severity("BLOCKING").description("复审爆雷").build()), 0, 1));

        GateResult result = serviceWithProperties().challenge(command, item, PromptContext.builder().build(),
                "prompt", chapterContent, lowConfidenceGate, List.of(), StyleStatEntity.builder().build(), 1, null);

        // 复审不过 → 回退原稿（原稿的低置信结论仍然有效）
        assertSame(lowConfidenceGate, result);
        assertEquals("原稿", chapterContent.getTitle());
        assertEquals("原稿正文。", chapterContent.getContent());
    }

    @Test
    void judgePrompt_carriesFactBaselineFromSummaries() {
        // 盲评原先只拿到【章节计划 + 两稿正文】，既没有记忆也没有账本 ——
        // 结构上不可能发现"身份映射被改写/认知状态提前升级/已确认事实被否定"，
        // 只能比较文风，于是"更漂亮但事实错误"的稿子会被选中。
        // 本测试锁定：最近章节的摘要与三账本状态必须进入评审 prompt，且事实维度排在文风之前。
        when(llmInvokeService.invokeWithScene(any(), any(), any(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(RAW_CHALLENGER);
        when(llmGateway.complete(any(), any())).thenReturn("{\"winner\":\"B\",\"reason\":\"原稿事实更稳\"}");

        List<ChapterSummaryEntity> summaries = List.of(
                ChapterSummaryEntity.builder()
                        .chapterNo(1).title("第1章")
                        .summary("江燃上线遇到软软，两人配合残血反杀。")
                        .characterStates(List.of(new ChapterSummaryEntity.StateEntry(
                                "软软", "尚未确认无月的真实身份", "线上角色'软软'")))
                        .build());

        serviceWithProperties().challenge(command, item, PromptContext.builder().build(),
                "prompt", chapterContent, lowConfidenceGate, summaries,
                StyleStatEntity.builder().build(), 2, null);

        ArgumentCaptor<cn.novel.yonren.domain.novel.model.valobj.LlmCall> captor =
                ArgumentCaptor.forClass(cn.novel.yonren.domain.novel.model.valobj.LlmCall.class);
        verify(llmGateway, atLeastOnce()).complete(any(), captor.capture());
        String prompt = captor.getValue().getUserPrompt();

        assertTrue(prompt.contains("【本章开始时的既知事实与角色状态】"), "评审 prompt 必须带事实基线块");
        assertTrue(prompt.contains("江燃上线遇到软软"), "基线须含最近章节摘要");
        assertTrue(prompt.contains("尚未确认无月的真实身份"), "基线须含账本状态（认知边界）");
        assertTrue(prompt.contains("事实与认知一致性"), "评审维度须含事实一致性");
        assertTrue(prompt.contains("【题材与人物语域基准】"));
        assertTrue(prompt.contains("都市重生"));
        assertTrue(prompt.contains("二十世纪九十年代的巷子生活"));
        assertTrue(prompt.contains("时代错位词"), "候选盲评必须能淘汰时代错位或人物失语稿");
        assertTrue(prompt.indexOf("事实与认知一致性") < prompt.indexOf("4) 文风与语域"),
                "事实核对必须排在文风之前（原先文风是第一维度）");
    }

    @Test
    void judgePrompt_withoutHistoryRendersOpeningNote() {
        when(llmInvokeService.invokeWithScene(any(), any(), any(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(RAW_CHALLENGER);
        when(llmGateway.complete(any(), any())).thenReturn("{\"winner\":\"B\",\"reason\":\"原稿更稳\"}");

        serviceWithProperties().challenge(command, item, PromptContext.builder().build(),
                "prompt", chapterContent, lowConfidenceGate, List.of(),
                StyleStatEntity.builder().build(), 1, null);

        ArgumentCaptor<cn.novel.yonren.domain.novel.model.valobj.LlmCall> captor =
                ArgumentCaptor.forClass(cn.novel.yonren.domain.novel.model.valobj.LlmCall.class);
        verify(llmGateway, atLeastOnce()).complete(any(), captor.capture());

        assertTrue(captor.getValue().getUserPrompt().contains("无历史记忆，本章为开篇"),
                "开篇没有基线时 prompt 要显式说明「无既知事实约束」，而不是留空让模型自行假设");
    }

    /** 2026-10-01：MINOR 条数不足门槛时不得触发——正是本批触发率爆到 100% 的根因 */
    @Test
    void minorResidueBelowThreshold_doesNotTrigger() {
        GateResult fewMinors = GateResult.of(List.of(), 5, 0); // 门槛默认 6
        assertEquals(PassGrade.MINOR_RESIDUE, fewMinors.grade());

        GateResult result = serviceWithProperties().challenge(command, item, PromptContext.builder().build(),
                "prompt", chapterContent, fewMinors, List.of(), StyleStatEntity.builder().build(), 1, null);

        assertSame(fewMinors, result, "MINOR 只有 5 条（<门槛 6）时不应起候选");
        verify(llmGateway, never()).complete(any(), any());
    }

    /** 门槛可配置：调到 1 时"有 MINOR 就触发"的老行为应恢复 */
    @Test
    void minorResidueThreshold_isConfigurable() {
        storyProperties.getCandidate().setMinorResidueThreshold(1);
        GateResult oneMinor = GateResult.of(List.of(), 1, 0);

        when(llmInvokeService.invokeWithScene(any(), any(), any(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(RAW_CHALLENGER);
        when(llmGateway.complete(any(), any())).thenReturn("{\"winner\":\"A\",\"reason\":\"原稿更稳\"}");

        serviceWithProperties().challenge(command, item, PromptContext.builder().build(),
                "prompt", chapterContent, oneMinor, List.of(), StyleStatEntity.builder().build(), 1, null);

        verify(llmGateway, atLeastOnce()).complete(any(), any());
    }

    /** DEBT 与 REVISED_PASS 不受 MINOR 条数门槛约束——它们本身就是更重的信号 */
    @Test
    void debtAndRevisedPass_ignoreMinorThreshold() {
        GateResult revisedPass = GateResult.of(List.of(), 0, 1); // 0 条 MINOR，但修订过 1 轮
        assertEquals(PassGrade.REVISED_PASS, revisedPass.grade());
        assertTrue(revisedPass.isLowConfidencePass(), "REVISED_PASS 不设条数门槛");

        GateResult debt = GateResult.of(
                List.of(cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity.builder()
                        .severity("BLOCKING").description("x").build()), 0, 2);
        assertEquals(PassGrade.DEBT, debt.grade());
        assertTrue(debt.isLowConfidencePass(), "DEBT 不设条数门槛");
    }

    // ==================== 默认配置行为 ====================

    /**
     * 默认配置下**只有 REVISED_PASS 触发候选**。
     *
     * <p>依据：两批实测的采纳率分布为 REVISED_PASS 66.7% &gt; MINOR_RESIDUE 42.9% &gt; DEBT 0%，
     * 且 MINOR 条数无判别力（两批中位数均 6）、DEBT 三次触发零采纳。
     * 三条通道里只有 REVISED_PASS 是有效信号，所以其余两条默认关闭。
     */
    @Test
    void defaultConfig_onlyRevisedPassTriggers() {
        // 用默认值重建一套属性（不复用 setUp 里为测机制而打开的 MINOR 通道）
        StoryProperties defaults = new StoryProperties();
        StoryProperties.CandidateProperties defaultsCandidate = new StoryProperties.CandidateProperties();
        defaultsCandidate.setEnabled(true);
        defaults.setCandidate(defaultsCandidate);
        org.springframework.test.util.ReflectionTestUtils.setField(candidateService, "storyProperties", defaults);

        GateResult minorResidue = GateResult.of(List.of(), 6, 0);
        assertSame(minorResidue, candidateService.challenge(command, item, PromptContext.builder().build(),
                        "prompt", chapterContent, minorResidue, List.of(),
                        StyleStatEntity.builder().build(), 1, null),
                "MINOR 残留默认不触发（条数无判别力，成本却是确定的）");

        GateResult debt = GateResult.of(
                List.of(cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity.builder()
                        .severity("BLOCKING").description("x").build()), 0, 2);
        assertSame(debt, candidateService.challenge(command, item, PromptContext.builder().build(),
                        "prompt", chapterContent, debt, List.of(),
                        StyleStatEntity.builder().build(), 1, null),
                "DEBT 默认不触发（实测 3 次触发 0 次采纳）");
    }

    /** DEBT 通道可通过开关重新打开（样本积累后可复用） */
    @Test
    void debtTrigger_isOptIn() {
        storyProperties.getCandidate().setTriggerOnDebt(true);
        GateResult debt = GateResult.of(
                List.of(cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity.builder()
                        .severity("BLOCKING").description("x").build()), 0, 2);

        when(llmInvokeService.invokeWithScene(any(), any(), any(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(RAW_CHALLENGER);
        when(llmGateway.complete(any(), any())).thenReturn("{\"winner\":\"A\",\"reason\":\"原稿更稳\"}");

        serviceWithProperties().challenge(command, item, PromptContext.builder().build(),
                "prompt", chapterContent, debt, List.of(), StyleStatEntity.builder().build(), 1, null);

        verify(llmGateway, atLeastOnce()).complete(any(), any());
    }
}
