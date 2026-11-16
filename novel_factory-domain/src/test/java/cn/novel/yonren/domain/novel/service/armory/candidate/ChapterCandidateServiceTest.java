package cn.novel.yonren.domain.novel.service.armory.candidate;

import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.StyleStatEntity;
import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.config.StoryProperties.CausalityProperties.Mode;
import cn.novel.yonren.domain.novel.model.valobj.plan.CausalVerdict;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.domain.novel.model.valobj.quality.DeliveryReview;
import cn.novel.yonren.domain.novel.model.valobj.quality.GateResult;
import cn.novel.yonren.domain.novel.model.valobj.quality.GateResult.PassGrade;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import cn.novel.yonren.domain.novel.service.armory.llm.LlmLocalRejectionException;
import cn.novel.yonren.domain.novel.service.armory.quality.DeliveryReviewPolicy;
import cn.novel.yonren.domain.novel.service.armory.quality.QualityGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        // 旧候选行为显式在 OFF 下验证；交付用例各自切换 OBSERVE/ENFORCE。
        storyProperties.getCausality().setAuditMode(Mode.OFF);
        storyProperties.getCausality().setPlanMode(Mode.OFF);
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
        // 字数下限：挑战者有效字 < 原稿 60% 直接机械出局，不进盲评。
        // 盲评不评价长短，篇幅保护必须在盲评前完成；夹具中原稿约 1600 字、挑战者约 270 字。
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
        // 评审输出 winner=B（挑战稿被随机定在 A/B 之一）：无论盲排方向如何，用不指向 challenge 的桩不可行——// 这里验证评审"不可用"路径（输出 winner=C 非法 → 保留原稿）与原稿不被替换
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
        when(qualityGate.auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any(), any()))
                .thenReturn(GateResult.of(List.of(), 0, 0));

        GateResult result = serviceWithProperties().challenge(command, item, PromptContext.builder().build(),
                "prompt", chapterContent, lowConfidenceGate, List.of(), StyleStatEntity.builder().build(), 1, null);

        assertEquals(GateResult.PassGrade.CLEAN_PASS, result.grade());
        assertEquals("挑战稿", chapterContent.getTitle());
        verify(qualityGate).auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any(), any());
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
        when(qualityGate.auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any(), any()))
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
        when(qualityGate.auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any(), any()))
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
        // 盲评原先只拿到【章节计划 + 两稿正文】，既没有记忆也没有账本 ——// 结构上不可能发现"身份映射被改写/认知状态提前升级/已确认事实被否定"，
        // 只能比较文风，于是"更漂亮但事实错误"的稿子会被选中。
        // 本测试锁定：最近章节的摘要与三账本状态必须进入评审 prompt，且事实维度排在文风之前。
        when(llmInvokeService.invokeWithScene(any(), any(), any(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(RAW_CHALLENGER);
        when(llmGateway.complete(any(), any())).thenReturn("{\"winner\":\"B\",\"reason\":\"原稿事实更稳\"}");

        List<ChapterSummaryEntity> summaries = List.of(
                ChapterSummaryEntity.builder()
                        .chapterNo(1).title("第1章")
                        .summary("角色癸上线遇到软软，两人配合残血反杀。")
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
        assertTrue(prompt.contains("角色癸上线遇到软软"), "基线须含最近章节摘要");
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

    /** MINOR 条数不足门槛时不得触发候选 */
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

    // ==================== 交付验收边界（全部本地 Mock，不验证模型能力） ====================

    @ParameterizedTest
    @EnumSource(Mode.class)
    void unverifiedDegradedOrPlanAdjustment_neverInvokesCandidateModels(Mode mode) {
        configureDelivery(mode);
        storyProperties.getCandidate().setTriggerOnRevisedPass(true);
        storyProperties.getCandidate().setTriggerOnDebt(true);
        GateResult degraded = GateResult.of(List.of(), 6, List.of(), 0, List.of(), true)
                .withDeliveryReview(lowConfidenceGate.deliveryReview());
        GateResult unverified = new GateResult(List.of(), PassGrade.UNVERIFIED, 6, List.of(), 0,
                List.of(), false, null);
        GateResult unknown = lowConfidenceGate.withDeliveryReview(
                reviewFor(chapterContent, mode, CausalVerdict.Status.UNKNOWN));

        for (GateResult blocked : List.of(degraded, unverified, unknown,
                lowConfidenceGate.withPlanAdjustmentRequired("计划与已确认事实冲突"))) {
            assertIncumbentRetained(blocked);
        }
        verifyNoInteractions(llmInvokeService, llmGateway, qualityGate, candidateSampleService);
    }

    @ParameterizedTest
    @EnumSource(value = Mode.class, names = {"OBSERVE", "ENFORCE"})
    void incumbentMissingOrStaleDelivery_neverInvokesCandidateModels(Mode mode) {
        configureDelivery(mode);
        DeliveryReview valid = lowConfidenceGate.deliveryReview();
        List<DeliveryReview> invalid = List.of(
                DeliveryReview.notChecked(),
                new DeliveryReview(DeliveryReview.Status.PASS, valid.enforced(), valid.scope(),
                        ChapterManuscript.digest("旧原稿"), valid.planHash(), valid.checks(), List.of()),
                new DeliveryReview(DeliveryReview.Status.PASS, valid.enforced(), valid.scope(),
                        valid.manuscriptHash(), ChapterManuscript.digest("旧计划"), valid.checks(), List.of()),
                new DeliveryReview(DeliveryReview.Status.PASS, valid.enforced(), valid.scope(),
                        valid.manuscriptHash(), valid.planHash(), List.of(), List.of()));

        for (DeliveryReview review : invalid) {
            assertIncumbentRetained(lowConfidenceGate.withDeliveryReview(review));
        }
        verifyNoInteractions(llmInvokeService, llmGateway, qualityGate, candidateSampleService);
    }

    @Test
    void enforceConfirmedIssue_doesNotAutomaticallyEnableDebtCandidates() {
        configureDelivery(Mode.ENFORCE);
        chapterContent.setContent("原稿正文。药签已经交付，她转身离开。");
        GateResult issue = lowConfidenceGate.withDeliveryReview(
                reviewFor(chapterContent, Mode.ENFORCE, CausalVerdict.Status.ISSUE));
        assertEquals(PassGrade.DEBT, issue.grade());
        assertTrue(issue.unresolvedBlocking().isEmpty(), "交付 ISSUE 本身也能阻断，不能只看 BLOCKING 列表");

        assertIncumbentRetained(issue);

        assertFalse(storyProperties.getCandidate().isTriggerOnDebt());
        verifyNoInteractions(llmInvokeService, llmGateway, qualityGate, candidateSampleService);
    }

    @Test
    void enforceConfirmedIssue_explicitDebtCandidateCanRescue() {
        configureDelivery(Mode.ENFORCE);
        storyProperties.getCandidate().setTriggerOnDebt(true);
        chapterContent.setContent("原稿正文。药签已经交付，她转身离开。");
        GateResult issue = lowConfidenceGate.withDeliveryReview(
                reviewFor(chapterContent, Mode.ENFORCE, CausalVerdict.Status.ISSUE));
        stubDeliveryChallengerWins();
        when(qualityGate.auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any(), any()))
                .thenAnswer(inv -> GateResult.of(List.of(), 0, 0).withDeliveryReview(
                        reviewFor(inv.getArgument(2), Mode.ENFORCE, CausalVerdict.Status.PASS)));

        GateResult result = challenge(issue);

        assertEquals(PassGrade.CLEAN_PASS, result.grade());
        assertTrue(result.deliveryReview().passed());
        assertTrue(result.deliveryReview().enforced());
        assertEquals(ChapterManuscript.of(chapterContent).hash(), result.deliveryReview().manuscriptHash());
        assertEquals("挑战稿", chapterContent.getTitle());
        verify(qualityGate).auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void emptyBlockingButNotVerified_revertsOriginalManuscriptAndAllGateMetadata(Mode mode) {
        configureDelivery(mode);
        stubDeliveryChallengerWins();
        DeliveryReview review = mode == Mode.OFF ? DeliveryReview.notChecked()
                : reviewFor(deliveryChallenger(), mode, CausalVerdict.Status.PASS);
        GateResult clean = GateResult.of(List.of(), 0, 0).withDeliveryReview(review);
        GateResult degraded = GateResult.of(List.of(), 0, List.of(), 0, List.of(), true)
                .withDeliveryReview(review);
        GateResult debtWithoutBlocking = new GateResult(List.of(), PassGrade.DEBT, 0, List.of(), 0,
                List.of(), false, null, null, review);
        GateResult unverified = new GateResult(List.of(), PassGrade.UNVERIFIED, 0, List.of(), 0,
                List.of(), false, null);
        GateResult unknown = clean.withDeliveryReview(
                reviewFor(deliveryChallenger(), mode, CausalVerdict.Status.UNKNOWN));

        for (GateResult rejected : List.of(degraded, debtWithoutBlocking, unverified, unknown,
                clean.withPlanAdjustmentRequired("计划缺少必要前提"))) {
            assertTrue(rejected.unresolvedBlocking().isEmpty());
            when(qualityGate.auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any(), any()))
                    .thenReturn(rejected);
            assertIncumbentRetained(lowConfidenceGate);
        }
        verify(candidateSampleService, never()).record(any(), anyInt(), anyString(), eq("challenger-adopted"),
                any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = Mode.class, names = {"OBSERVE", "ENFORCE"})
    void challengerMissingStaleOrIncompleteReview_revertsOriginalGate(Mode mode) {
        configureDelivery(mode);
        stubDeliveryChallengerWins();
        DeliveryReview valid = reviewFor(deliveryChallenger(), mode, CausalVerdict.Status.PASS);
        List<DeliveryReview> invalid = List.of(
                DeliveryReview.notChecked(),
                new DeliveryReview(DeliveryReview.Status.PASS, false, valid.scope(),
                        lowConfidenceGate.deliveryReview().manuscriptHash(), valid.planHash(), valid.checks(), List.of()),
                new DeliveryReview(DeliveryReview.Status.PASS, false, valid.scope(),
                        valid.manuscriptHash(), ChapterManuscript.digest("另一计划"), valid.checks(), List.of()),
                new DeliveryReview(DeliveryReview.Status.PASS, false, DeliveryReview.Scope.SCENES,
                        valid.manuscriptHash(), valid.planHash(), valid.checks(), List.of()),
                new DeliveryReview(DeliveryReview.Status.PASS, false, valid.scope(),
                        valid.manuscriptHash(), valid.planHash(), List.of(), List.of()));

        for (DeliveryReview review : invalid) {
            when(qualityGate.auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any(), any()))
                    .thenReturn(GateResult.of(List.of(), 0, 0).withDeliveryReview(review));
            assertIncumbentRetained(lowConfidenceGate);
        }
        verify(candidateSampleService, never()).record(any(), anyInt(), anyString(), eq("challenger-adopted"),
                any(), any(), any(), any(), any());
    }

    @Test
    void enforceAppliesToChallengerEvenWhenReturnedIssueWasObserveOnly() {
        configureDelivery(Mode.ENFORCE);
        stubDeliveryChallengerWins();
        GateResult observeIssue = GateResult.of(List.of(), 0, 0).withDeliveryReview(
                reviewFor(deliveryChallenger(), Mode.OBSERVE, CausalVerdict.Status.ISSUE));
        assertEquals(PassGrade.CLEAN_PASS, observeIssue.grade());
        assertFalse(observeIssue.deliveryReview().blocksCommit());
        when(qualityGate.auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any(), any()))
                .thenReturn(observeIssue);

        assertIncumbentRetained(lowConfidenceGate);
    }

    @Test
    void observeConfirmedIssue_isRecordedWithoutDeliveryCommitBlock() {
        configureDelivery(Mode.OBSERVE);
        stubDeliveryChallengerWins();
        when(qualityGate.auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any(), any()))
                .thenReturn(GateResult.of(List.of(), 0, 0).withDeliveryReview(
                        reviewFor(deliveryChallenger(), Mode.OBSERVE, CausalVerdict.Status.ISSUE)));

        GateResult result = challenge(lowConfidenceGate);

        assertEquals("挑战稿", chapterContent.getTitle());
        assertEquals(DeliveryReview.Status.ISSUE, result.deliveryReview().status());
        assertFalse(result.deliveryReview().blocksCommit(), "OBSERVE 只记录，不伪装成 ENFORCE");
    }

    @Test
    void reviewForGeneratedChallenger_cannotBeReusedAfterRevision() {
        configureDelivery(Mode.ENFORCE);
        stubDeliveryChallengerWins();
        when(qualityGate.auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any(), any()))
                .thenAnswer(inv -> {
                    ChapterContentEntity candidate = inv.getArgument(2);
                    DeliveryReview beforeRevision = reviewFor(candidate, Mode.ENFORCE, CausalVerdict.Status.PASS);
                    candidate.setTitle("已改标题");
                    candidate.setContent("复审改过的正文，与已核验版本不同。");
                    return GateResult.of(List.of(), 0, 1).withDeliveryReview(beforeRevision);
                });

        assertIncumbentRetained(lowConfidenceGate);
    }

    @Test
    void enforceAcceptsOnlyReportBoundToFinalRevisedChallenger() {
        configureDelivery(Mode.ENFORCE);
        stubDeliveryChallengerWins();
        when(qualityGate.auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any(), any()))
                .thenAnswer(inv -> {
                    ChapterContentEntity candidate = inv.getArgument(2);
                    candidate.setTitle("复审挑战稿");
                    candidate.setContent("她从药匣取出药签递给掌柜，掌柜逐行核对后收入柜中。");
                    return GateResult.of(List.of(), 0, 1).withSemanticFeedbackHash("挑战者反馈指纹")
                            .withDeliveryReview(reviewFor(candidate, Mode.ENFORCE, CausalVerdict.Status.PASS));
                });

        GateResult result = challenge(lowConfidenceGate);

        assertEquals(PassGrade.REVISED_PASS, result.grade());
        assertEquals("复审挑战稿", chapterContent.getTitle());
        assertEquals("挑战者反馈指纹", result.semanticFeedbackHash());
        assertTrue(result.deliveryReview().passed());
        assertEquals(ChapterManuscript.of(chapterContent).hash(), result.deliveryReview().manuscriptHash());
        assertEquals(DeliveryReviewPolicy.contract(item).planHash(), result.deliveryReview().planHash());
        ArgumentCaptor<ChapterContentEntity> snapshot = ArgumentCaptor.forClass(ChapterContentEntity.class);
        verify(candidateSampleService).record(any(), eq(1), anyString(), eq("challenger-adopted"),
                snapshot.capture(), any(), eq("challenger"), anyString(), any());
        assertEquals("原稿", snapshot.getValue().getTitle(), "采纳样本仍须保留败者原稿，而非被原地改写的对象");
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void reAuditThrowsAfterMutation_finallyRestoresOriginalAndPreservesGate(Mode mode) {
        configureDelivery(mode);
        stubDeliveryChallengerWins();
        when(qualityGate.auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any(), any()))
                .thenAnswer(inv -> {
                    ChapterContentEntity candidate = inv.getArgument(2);
                    candidate.setChapterNo(99);
                    candidate.setTitle("异常路径标题");
                    candidate.setContent("复审在写入半成品后抛异常。");
                    throw new IllegalStateException("Mock 复审不可用");
                });

        assertIncumbentRetained(lowConfidenceGate);

        ArgumentCaptor<ChapterContentEntity> snapshot = ArgumentCaptor.forClass(ChapterContentEntity.class);
        verify(candidateSampleService).record(any(), eq(1), anyString(), eq("error"),
                snapshot.capture(), any(), any(), anyString(), any());
        assertEquals(chapterContent, snapshot.getValue(), "异常样本应记录原稿，而不是未采纳的半成品");
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void reAuditCancellation_restoresOriginalAndPropagatesDirectOrWrappedSignal(Mode mode) {
        configureDelivery(mode);
        stubDeliveryChallengerWins();
        ChapterContentEntity snapshot = new ChapterContentEntity(chapterContent.getChapterNo(),
                chapterContent.getTitle(), chapterContent.getContent());
        CancellationException cancelled = new CancellationException("Mock 交付核验已取消");

        for (RuntimeException failure : List.of(cancelled, new CompletionException(cancelled))) {
            doAnswer(inv -> {
                ChapterContentEntity candidate = inv.getArgument(2);
                candidate.setTitle("取消前的半成品");
                candidate.setContent("本稿尚未完成交付核验。");
                throw failure;
            }).when(qualityGate).auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any(), any());

            assertSame(cancelled, assertThrows(CancellationException.class, () -> challenge(lowConfidenceGate)));
            assertEquals(snapshot, chapterContent, "控制异常向上传递时 finally 仍须回滚标题与原文");
        }
        verifyNoInteractions(candidateSampleService);
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void reAuditLocalRejection_restoresOriginalAndPropagatesDirectOrWrappedSignal(Mode mode) {
        configureDelivery(mode);
        stubDeliveryChallengerWins();
        ChapterContentEntity snapshot = new ChapterContentEntity(chapterContent.getChapterNo(),
                chapterContent.getTitle(), chapterContent.getContent());
        LlmLocalRejectionException rejection = new LlmLocalRejectionException("Mock 本地预算拒绝");

        for (RuntimeException failure : List.of(rejection, new CompletionException(rejection))) {
            doAnswer(inv -> {
                ChapterContentEntity candidate = inv.getArgument(2);
                candidate.setChapterNo(99);
                candidate.setTitle("拒绝前的半成品");
                candidate.setContent("本地确定性拒绝不是模型失败。");
                throw failure;
            }).when(qualityGate).auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any(), any());

            assertSame(rejection, assertThrows(LlmLocalRejectionException.class, () -> challenge(lowConfidenceGate)));
            assertEquals(snapshot, chapterContent, "本地拒绝不能被 fail-soft 吞掉或留下未采纳稿");
        }
        verifyNoInteractions(candidateSampleService);
    }

    @Test
    void interruptedReAudit_restoresOriginalAndPropagatesCancellation() {
        configureDelivery(Mode.ENFORCE);
        stubDeliveryChallengerWins();
        ChapterContentEntity snapshot = new ChapterContentEntity(chapterContent.getChapterNo(),
                chapterContent.getTitle(), chapterContent.getContent());
        InterruptedException interrupted = new InterruptedException("Mock 核验中断");
        when(qualityGate.auditAndReviseIfEnabled(any(), any(), any(), anyList(), any(), anyInt(), any(), any()))
                .thenAnswer(inv -> {
                    ChapterContentEntity candidate = inv.getArgument(2);
                    candidate.setContent("被中断的复审稿。");
                    throw new CompletionException(interrupted);
                });

        try {
            CancellationException thrown = assertThrows(CancellationException.class, () -> challenge(lowConfidenceGate));
            assertSame(interrupted, thrown.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(snapshot, chapterContent);
            verifyNoInteractions(candidateSampleService);
        } finally {
            Thread.interrupted();
        }
    }

    private static final String DELIVERY_CHALLENGER_CONTENT = "挑战稿正文，情节完整。她递出药签，掌柜核对后收下。";

    private void configureDelivery(Mode mode) {
        storyProperties.getCausality().setAuditMode(mode);
        storyProperties.getCausality().setPlanMode(Mode.OFF);
        item.setKeyEvents(List.of("交付药签"));
        chapterContent.setContent("原稿正文。她递出药签，掌柜收下。");
        lowConfidenceGate = GateResult.of(List.of(), 6,
                        List.of(ChapterIssueEntity.builder().severity("MINOR").description("原稿机械提示").build()), 0,
                        List.of(ChapterIssueEntity.builder().severity("MINOR").description("原稿语义反馈").build()))
                .withSemanticFeedbackHash("原稿反馈指纹")
                .withDeliveryReview(mode == Mode.OFF ? DeliveryReview.notChecked()
                        : reviewFor(chapterContent, mode, CausalVerdict.Status.PASS));
    }

    /** 真 contract + 真引文定位，只把模型的语义裁决桩住，不伪造 PASS + 空 checks。 */
    private DeliveryReview reviewFor(ChapterContentEntity content, Mode mode, CausalVerdict.Status status) {
        var request = new DeliveryReviewPolicy.Request(DeliveryReviewPolicy.contract(item),
                ChapterManuscript.of(content), mode == Mode.ENFORCE);
        if (status == CausalVerdict.Status.UNKNOWN) {
            return DeliveryReviewPolicy.unavailable(request, "Mock 交付核验不可用");
        }
        boolean issue = status == CausalVerdict.Status.ISSUE;
        CausalVerdict check = new CausalVerdict(null, status, content.getContent(), null,
                issue ? "Mock 裁决：未交付必要过程" : "Mock 裁决：正文已完成药签交接",
                issue ? "补足计划承诺的必要交付过程" : null,
                issue ? CausalVerdict.FailureType.RESULT_WITHOUT_CAUSE : null,
                null, null, null, null, "event:1", "keyEvents[0]", "交付药签");
        DeliveryReview review = DeliveryReviewPolicy.consume(request, List.of(check));
        assertEquals(status.name(), review.status().name(), "测试夹具须通过真实的协议与引文校验");
        return review;
    }

    private ChapterContentEntity deliveryChallenger() {
        return ChapterContentEntity.builder().chapterNo(1).title("挑战稿")
                .content(DELIVERY_CHALLENGER_CONTENT).build();
    }

    private void stubDeliveryChallengerWins() {
        when(llmInvokeService.invokeWithScene(any(), any(), any(), anyString(), any(), any(), anyString(), any()))
                .thenReturn("{\"chapterNo\":1,\"title\":\"挑战稿\",\"content\":\"" + DELIVERY_CHALLENGER_CONTENT + "\"}");
        when(llmGateway.complete(any(), any())).thenAnswer(inv -> {
            cn.novel.yonren.domain.novel.model.valobj.LlmCall call = inv.getArgument(1);
            String prompt = call.getUserPrompt();
            boolean challengerIsA = prompt.indexOf(DELIVERY_CHALLENGER_CONTENT) < prompt.indexOf("【候选 B】");
            return "{\"winner\":\"" + (challengerIsA ? "A" : "B") + "\",\"reason\":\"Mock 盲评胜出\"}";
        });
    }

    private GateResult challenge(GateResult incumbent) {
        return serviceWithProperties().challenge(command, item, PromptContext.builder().build(),
                "prompt", chapterContent, incumbent, List.of(), StyleStatEntity.builder().build(), 1, null);
    }

    private void assertIncumbentRetained(GateResult incumbent) {
        ChapterContentEntity snapshot = new ChapterContentEntity(chapterContent.getChapterNo(),
                chapterContent.getTitle(), chapterContent.getContent());
        assertSame(incumbent, challenge(incumbent), "必须返回原 GateResult 实例，保留全部字段");
        assertEquals(snapshot, chapterContent, "所有未采纳路径均恢复章号、标题与原文");
    }

    // ==================== 默认配置行为 ====================

    /** 默认分级开关全部关闭；交付验收不得暗中开启任何一档候选。 */
    @Test
    void defaultConfig_allGradeTriggersRemainDisabled() {
        // 用默认值重建一套属性（不复用 setUp 里为测机制而打开的 MINOR 通道）
        StoryProperties defaults = new StoryProperties();
        defaults.getCausality().setAuditMode(Mode.OFF);
        defaults.getCausality().setPlanMode(Mode.OFF);
        StoryProperties.CandidateProperties defaultsCandidate = new StoryProperties.CandidateProperties();
        assertFalse(defaultsCandidate.isEnabled());
        assertFalse(defaultsCandidate.isTriggerOnMinorResidue());
        assertFalse(defaultsCandidate.isTriggerOnRevisedPass());
        assertFalse(defaultsCandidate.isTriggerOnDebt());
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

        GateResult revised = GateResult.of(List.of(), 0, 1);
        assertSame(revised, candidateService.challenge(command, item, PromptContext.builder().build(),
                        "prompt", chapterContent, revised, List.of(),
                        StyleStatEntity.builder().build(), 1, null),
                "REVISED_PASS 也须用户显式开启，不改变源码默认值");
        verifyNoInteractions(llmInvokeService, llmGateway, qualityGate, candidateSampleService);
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
