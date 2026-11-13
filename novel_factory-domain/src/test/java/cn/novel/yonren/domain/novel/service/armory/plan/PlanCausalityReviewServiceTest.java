package cn.novel.yonren.domain.novel.service.armory.plan;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.character.CharacterEffect;
import cn.novel.yonren.domain.novel.model.valobj.character.CharacterEffect.Kind;
import cn.novel.yonren.domain.novel.model.valobj.character.CharacterEffect.State;
import cn.novel.yonren.domain.novel.service.job.GenerationJob;
import cn.novel.yonren.domain.novel.model.valobj.plan.FactReference;
import cn.novel.yonren.domain.novel.model.valobj.plan.PlanValidationResult;
import cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality;
import cn.novel.yonren.domain.novel.model.valobj.plan.ScenePlan;
import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.config.StoryProperties.CausalityProperties.Mode;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import cn.novel.yonren.domain.novel.service.armory.llm.LlmLocalRejectionException;
import cn.novel.yonren.domain.novel.service.armory.memory.CharacterStateProjector;
import cn.novel.yonren.types.enums.ModelScene;
import cn.novel.yonren.types.exception.AppException;
import cn.novel.yonren.types.utils.FactIdentity;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

class PlanCausalityReviewServiceTest {
    private static final String RESULT = "设备扫描到整座城的暗室";
    private static final String LIMIT = "设备只能读取接触面的字迹";
    private static final String REASON = "扫描结果超出已有设备能力";
    private static final String REPAIR = "将扫描结果限制在接触面，不新增设备能力";
    private static final String LIMIT_EVIDENCE = "江燃检查设备，确认设备只能读取接触面的字迹。";
    private final LlmGateway gateway = mock(LlmGateway.class);
    private final StoryProperties properties = new StoryProperties();
    private final PlanCausalityReviewService service = new PlanCausalityReviewService(gateway, properties);
    private final StoryVO config = config();
    private final GenerationJob job = new GenerationJob("causality-job-1");
    private final Map<String, String> record = new HashMap<>();
    private final ChapterPlanAggregate plan = plan(scene("s1", RESULT));
    private final StageBlueprintEntity stage = StageBlueprintEntity.builder()
            .startChapter(2).endChapter(6).stageGoal("核对旧信").build();
    /** 结构化人物事实：证据、章号、hash 与 factId 均可核对，才允许支撑 ISSUE。 */
    private final ChapterSummaryEntity bound = factSummary(1, LIMIT_EVIDENCE,
            effect("江燃", Kind.KNOWLEDGE, "设备能力", State.ESTABLISHED, "只能读取接触面", LIMIT_EVIDENCE));
    private final String factId = bound.getCharacterEffects().get(0).factId();
    private final String hash = bound.getManuscriptHash();
    private final List<ChapterSummaryEntity> history = List.of(bound);

    @Test
    void defaultOffAndMissingConfigurationNeverCallGatewayOrTouchInputs() {
        List<PlanCausalityReviewService> services = new ArrayList<>();
        services.add(service);
        services.add(new PlanCausalityReviewService(gateway, null));
        StoryProperties noSection = new StoryProperties();
        noSection.setCausality(null);
        services.add(new PlanCausalityReviewService(gateway, noSection));
        StoryProperties noMode = new StoryProperties();
        noMode.getCausality().setPlanMode(null);
        services.add(new PlanCausalityReviewService(gateway, noMode));
        job.setCancelRequested(true);
        job.setBudgetExceeded(true);
        record.put("untouched", "sentinel");
        for (PlanCausalityReviewService candidate : services) {
            assertEquals(Mode.OFF, candidate.getMode());
            PlanValidationResult result = candidate.check(null, null, null, null, 0, -1, job, record);
            assertEquals(new PlanValidationResult(List.of(), List.of()), result);
        }
        assertEquals(Map.of("untouched", "sentinel"), record);
        verifyNoInteractions(gateway);
    }

    @Test
    void observeKeepsAdmissionButRetainsIssueAndUnknownDiagnostics() {
        properties.getCausality().setPlanMode(Mode.OBSERVE);
        ChapterPlanAggregate two = plan(scene("s1", RESULT), scene("s2", "独自等雨停"));
        when(gateway.complete(any(), any())).thenReturn(response(
                issue("s1", RESULT, LIMIT, REASON, REPAIR, factId, 1, hash),
                check("s2", "UNKNOWN", "", "", "前置知识缺少可核对记录", "")));
        String beforePlan = JSON.toJSONString(two), beforeHistory = JSON.toJSONString(history);
        PlanValidationResult result = service.check(config, two, stage, history, 2, 2, job, record);
        assertTrue(result.acceptable());
        assertEquals(List.of(), result.blocking());
        assertEquals(2, result.warnings().size());
        assertTrue(result.warnings().stream().allMatch(w -> w.startsWith("OBSERVE ")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains(REASON) && w.contains(REPAIR)));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("CAUSALITY_PLAN_UNKNOWN") && w.contains("s2")));
        assertFalse(result.warnings().stream().anyMatch(w -> w.contains("PASS")));
        assertEquals(beforePlan, JSON.toJSONString(two));
        assertEquals(beforeHistory, JSON.toJSONString(history));
        verify(gateway, times(1)).complete(any(), any());
    }

    @Test
    void enforceRejectsUnknownRatherThanInventingAPassOrRetrying() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        when(gateway.complete(any(), any())).thenReturn(response(check("s1", "UNKNOWN", "", "", "缺少设备来源", "")));
        PlanValidationResult result = review();
        blocked(result, "CAUSALITY_PLAN_UNKNOWN");
        assertTrue(result.blocking().get(0).contains("s1"));
        assertTrue(result.blocking().get(0).contains("缺少设备来源"));
        verify(gateway, times(1)).complete(any(), any());
    }

    @Test
    void validEnforcedIssueUsesBothOriginalExcerptsAndKeepsTargetedFeedback() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        when(gateway.complete(any(), any())).thenReturn(issueResponse());
        PlanValidationResult result = review();
        blocked(result, REASON);
        assertTrue(result.blocking().get(0).contains(REPAIR));
        assertTrue(result.blocking().get(0).contains(factId));
        assertFalse(result.blocking().get(0).contains("UNKNOWN"));
        verify(gateway, times(1)).complete(any(), any());
    }

    @Test
    void issueWithoutFactIdentityOrWithBeliefBasisIsDiagnosticOnly() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        ChapterSummaryEntity belief = factSummary(1, "江燃以为设备还能远程读取。",
                effect("江燃", Kind.BELIEF, "设备能力", State.ESTABLISHED, "可以远程读取",
                        "江燃以为设备还能远程读取。"));
        String beliefId = belief.getCharacterEffects().get(0).factId();
        for (String raw : List.of(
                response(check("s1", "ISSUE", RESULT, LIMIT, REASON, REPAIR)),
                response(issue("s1", RESULT, LIMIT, REASON, REPAIR, factId, 1, "wrong-hash")),
                response(issue("s1", RESULT, LIMIT, REASON, REPAIR, factId, 2, hash)))) {
            when(gateway.complete(any(), any())).thenReturn(raw);
            PlanValidationResult result = service.check(config, plan, stage, history, 2, 2, job, new HashMap<>());
            blocked(result, "CAUSALITY_PLAN_UNKNOWN");
            assertFalse(result.blocking().stream().anyMatch(v -> v.startsWith("causality scene=")));
        }
        when(gateway.complete(any(), any())).thenReturn(response(issue("s1", RESULT,
                "可以远程读取", REASON, REPAIR, beliefId, 1, belief.getManuscriptHash())));
        PlanValidationResult beliefBasis = service.check(config, plan, stage, List.of(belief), 2, 2, job,
                new HashMap<>());
        blocked(beliefBasis, "CAUSALITY_PLAN_UNKNOWN");
        verify(gateway, times(4)).complete(any(), any());
    }

    @Test
    void quietAndMistakenBeliefCanPassAndPromptPreservesBeliefFactSeparation() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        SceneCausality causality = new SceneCausality(List.of(new SceneCausality.ActorInput("江燃", null, null,
                List.of("character-fact-1"), null, "旧信仍完好")), null, null, null);
        ScenePlan quiet = new ScenePlan("quiet", List.of("江燃"), "回忆旧信", null, null,
                null, "余韵：独自看雨", null, null, null, causality);
        when(gateway.complete(any(), any())).thenReturn("```json\n" + response(pass("quiet")) + "\n```");
        List<ChapterSummaryEntity> submitted = Arrays.asList(summary(1, "旧信已焚毁"), null,
                ChapterSummaryEntity.builder().summary("无章号未提交记录").build(), summary(2, "未来事实不能使用"));
        PlanValidationResult result = service.check(config, plan(quiet), stage, submitted, 2, 2, job, record);
        assertTrue(result.acceptable());
        assertTrue(result.warnings().isEmpty());
        ArgumentCaptor<LlmCall> request = ArgumentCaptor.forClass(LlmCall.class);
        verify(gateway).complete(same(config.getModule()), request.capture());
        assertEquals(ModelScene.CHAPTER_AUDIT, request.getValue().getScene());
        assertEquals("plan-causality-2-2", request.getValue().getLabel());
        String prompt = request.getValue().getUserPrompt();
        for (String text : List.of("旧信已焚毁", "旧信仍完好", "belief/believes", "信念与真实情况不同本身不构成 ISSUE",
                "要求不等于答应", "打算不等于完成", "静场、独处、等待与闭合余韵均可PASS")) {
            assertTrue(prompt.contains(text), prompt);
        }
        assertFalse(prompt.contains("未来事实不能使用"));
        assertFalse(prompt.contains("无章号未提交记录"));
    }

    @Test
    void nullEmptyPartialOrDuplicateInputScenesFailLocallyWithoutGateway() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        List<ChapterPlanAggregate> invalid = Arrays.asList(null, new ChapterPlanAggregate(),
                ChapterPlanAggregate.builder().chapters(List.of()).build(),
                ChapterPlanAggregate.builder().chapters(Arrays.asList((ChapterPlanItemEntity) null)).build(),
                ChapterPlanAggregate.builder().chapters(List.of(ChapterPlanItemEntity.builder().build())).build(),
                plan(), plan((ScenePlan) null), plan(scene(" ", "静坐")),
                plan(scene("s1", "静坐"), scene(" s1 ", "看雨")));
        for (ChapterPlanAggregate value : invalid) {
            blocked(service.check(config, value, stage, history, 2, 2, job, record), "CAUSALITY_PLAN_INPUT");
        }
        blocked(service.check(null, plan, stage, history, 2, 2, job, record), "CAUSALITY_PLAN_INPUT");
        blocked(service.check(new StoryVO(), plan, stage, history, 2, 2, job, record), "CAUSALITY_PLAN_INPUT");
        blocked(service.check(config, plan, stage, history, 3, 2, job, record), "CAUSALITY_PLAN_INPUT");
        assertTrue(record.isEmpty());
        verifyNoInteractions(gateway);
    }

    @Test
    void duplicateUnknownMissingOrExtraReviewScenesCannotPass() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        ChapterPlanAggregate two = plan(scene("s1", RESULT), scene("s2", "独自看雨"));
        for (String raw : List.of(response(pass("s1"), pass("s1")), response(pass("s1"), pass("tampered")),
                response(pass("s1")), response(pass("s1"), pass("s2"), pass("s3")), response())) {
            when(gateway.complete(any(), any())).thenReturn(raw);
            blocked(service.check(config, two, stage, history, 2, 2, job, new HashMap<>()), "CAUSALITY_PLAN_");
        }
        verify(gateway, times(5)).complete(any(), any());
    }

    @Test
    void malformedTruncatedOrAmbiguousJsonNeverAuthorizesPass() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        String pass = response(pass("s1"));
        List<String> invalid = Arrays.asList(null, "", "not-json", "null", "[]", "{}", "{\"checks\":{}}",
                "{\"checks\":[null]}", "{\"checks\":[{\"sceneId\":\"s1\",\"verdict\":\"PASS\",\"reason\":null}]}",
                "{\"checks\":[{\"sceneId\":\"s1\",\"verdict\":\"PASS\",\"reason\":17}]}",
                "{\"checks\":[{\"sceneId\":\"s1\",\"verdict\":\"ISSUE\",\"verdict\":\"PASS\",\"reason\":\"被覆盖\"}]}",
                "{\"checks\":[],\"checks\":[{\"sceneId\":\"s1\",\"verdict\":\"PASS\",\"reason\":\"被覆盖\"}]}",
                pass.substring(0, pass.length() - 1), pass + "{}", "前缀说明" + pass,
                response(check("s1", "pass", "", "", "无正式结论", "")));
        for (String raw : invalid) {
            when(gateway.complete(any(), any())).thenReturn(raw);
            blocked(service.check(config, plan, stage, history, 2, 2, job, new HashMap<>()), "CAUSALITY_PLAN_");
        }
        verify(gateway, times(invalid.size())).complete(any(), any());
    }

    @Test
    void tamperedOrIncompleteIssueEvidenceIsUnknownNotAnAcceptedConflict() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        for (Map<String, String> issue : List.of(
                check("s1", "ISSUE", "凭空篡改的计划内容", LIMIT, REASON, REPAIR),
                issue("s1", "凭空篡改的计划内容", LIMIT, REASON, REPAIR, factId, 1, hash),
                issue("s1", RESULT, "凭空篡改的历史事实", REASON, REPAIR, factId, 1, hash),
                issue("s1", "", LIMIT, REASON, REPAIR, factId, 1, hash),
                issue("s1", RESULT, "", REASON, REPAIR, factId, 1, hash),
                issue("s1", RESULT, LIMIT, REASON, "", factId, 1, hash),
                issue("s1", "outcome", LIMIT, REASON, REPAIR, factId, 1, hash),
                issue("s1", RESULT, RESULT, REASON, REPAIR, factId, 1, hash))) {
            when(gateway.complete(any(), any())).thenReturn(response(issue));
            PlanValidationResult result = service.check(config, plan, stage, history, 2, 2, job, new HashMap<>());
            blocked(result, "CAUSALITY_PLAN_UNKNOWN");
            assertFalse(result.blocking().stream().anyMatch(v -> v.startsWith("causality scene=")));
        }
        verify(gateway, times(8)).complete(any(), any());
    }

    @Test
    void otherSceneEvidenceCannotBeMisassignedToEitherSideOfAnIssue() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        String otherResult = "另一场已收到扫描报告";
        ChapterPlanAggregate two = plan(scene("s1", RESULT), scene("s2", otherResult));
        for (Map<String, String> issue : List.of(
                issue("s1", otherResult, LIMIT, REASON, REPAIR, factId, 1, hash),
                issue("s1", RESULT, otherResult, REASON, REPAIR, factId, 1, hash))) {
            when(gateway.complete(any(), any())).thenReturn(response(issue, pass("s2")));
            blocked(service.check(config, two, stage, history, 2, 2, job, new HashMap<>()), "CAUSALITY_PLAN_UNKNOWN");
        }
        verify(gateway, times(2)).complete(any(), any());
    }

    @Test
    void internalConflictMustUseTwoDifferentFieldsInThatScene() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        SceneCausality causality = new SceneCausality(null, null,
                new SceneCausality.Terms("江燃", "林青", "旧信副本", "尚未支付，明天转账", null, null, null), null);
        String completed = "当场支付完成，双方离开";
        ScenePlan transaction = new ScenePlan("s1", List.of("江燃", "林青"), "取回副本", null, null,
                completed, "过渡", null, null, null, causality);
        when(gateway.complete(any(), any())).thenReturn(response(check("s1", "ISSUE", completed,
                "尚未支付，明天转账", "支付时点与结果冲突", "对齐实际支付时点")));
        blocked(service.check(config, plan(transaction), null, List.of(), 2, 2, job, new HashMap<>()), "支付时点与结果冲突");
        ScenePlan single = scene("s1", "未支付；已经支付");
        when(gateway.complete(any(), any())).thenReturn(response(check("s1", "ISSUE", "未支付", "已经支付", "相同字段", "核对")));
        blocked(service.check(config, plan(single), null, List.of(), 2, 2, job, new HashMap<>()), "CAUSALITY_PLAN_UNKNOWN");
        verify(gateway, times(2)).complete(any(), any());
    }

    @Test
    void quotesAndNewlinesAreMatchedAgainstOriginalFieldTextNotJsonEscapes() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        String result = "读出\"城外\"档案\n并已完成远程定位";
        String limit = "设备仅接触读取\n无法\"远程\"定位";
        ChapterSummaryEntity quoted = factSummary(1, "江燃检查设备：" + limit + "。",
                effect("江燃", Kind.KNOWLEDGE, "设备能力", State.ESTABLISHED, "仅接触读取",
                        "江燃检查设备：" + limit + "。"));
        String quotedId = quoted.getCharacterEffects().get(0).factId();
        when(gateway.complete(any(), any())).thenReturn(response(issue("s1", result, limit, REASON, REPAIR,
                quotedId, 1, quoted.getManuscriptHash())));
        PlanValidationResult checked = service.check(config, plan(scene("s1", result)), null,
                List.of(quoted), 2, 2, job, record);
        blocked(checked, REASON);
        assertFalse(checked.blocking().get(0).contains("UNKNOWN"));
    }

    @Test
    void beliefIsNotWorldFactEvidenceOnEitherSide() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        String beliefText = "旧信仍完好";
        SceneCausality causality = new SceneCausality(List.of(new SceneCausality.ActorInput("江燃", null, null,
                null, null, beliefText)), null, null, null);
        ScenePlan believing = new ScenePlan("s1", List.of("江燃"), "独自回忆", null, null,
                RESULT, "静场", null, null, null, causality);
        ChapterSummaryEntity belief = factSummary(1, "江燃以为旧信仍完好。",
                effect("江燃", Kind.BELIEF, "旧信状态", State.ESTABLISHED, beliefText, "江燃以为旧信仍完好。"));
        String beliefId = belief.getCharacterEffects().get(0).factId();
        for (Map<String, String> issue : List.of(
                issue("s1", RESULT, beliefText, "拿信念作能力事实", REPAIR, beliefId, 1,
                        belief.getManuscriptHash(), "CAPABILITY_CONFLICT"),
                issue("s1", RESULT, beliefText, "拿信念作结果依据", REPAIR, beliefId, 1,
                        belief.getManuscriptHash()))) {
            when(gateway.complete(any(), any())).thenReturn(response(issue));
            blocked(service.check(config, plan(believing), stage, List.of(belief), 2, 2, job, new HashMap<>()),
                    "CAUSALITY_PLAN_UNKNOWN");
        }
        // 认知回退可以用信念作对照：人物曾这样相信，本章却写成从未听说。
        when(gateway.complete(any(), any())).thenReturn(response(issue("s1", RESULT, beliefText,
                "人物认知被抹掉", REPAIR, beliefId, 1, belief.getManuscriptHash(), "KNOWLEDGE_REGRESSION")));
        blocked(service.check(config, plan, stage, List.of(belief), 2, 2, job, new HashMap<>()), "人物认知被抹掉");
        verify(gateway, times(3)).complete(any(), any());
    }

    @Test
    void legacyCharacterStateCannotSupportKnowledgeRegression() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        var old = summary(1, "江燃在医院醒来");
        var legacy = CharacterFactReferences.from(List.of(old), 2).get(0);
        String legacyId = FactIdentity.of(legacy.ledger(), legacy.chapterNo(), legacy.manuscriptHash(),
                legacy.subject(), legacy.status(), legacy.evidence());
        when(gateway.complete(any(), any())).thenReturn(response(issue("s1", RESULT, "江燃在医院醒来",
                "把旧状态误作人物认知", REPAIR, legacyId, 1, "history-hash", "KNOWLEDGE_REGRESSION")));

        PlanValidationResult result = service.check(config, plan, stage, List.of(old), 2, 2, job, new HashMap<>());
        blocked(result, "CAUSALITY_PLAN_UNKNOWN");
        assertFalse(result.blocking().stream().anyMatch(value -> value.contains("把旧状态误作人物认知")));
    }

    @Test
    void planReviewBoundsLegacySummariesAndStructuredFacts() {
        properties.getCausality().setPlanMode(Mode.OBSERVE);
        List<ChapterSummaryEntity> facts = new ArrayList<>();
        String text = "江燃核对设备读数。";
        for (int i = 0; i < 15; i++) {
            facts.add(factSummary(i + 1, text, effect("江燃", Kind.KNOWLEDGE, "读数" + i,
                    State.ESTABLISHED, "读数" + i, text), List.copyOf(facts)));
        }
        List<ChapterSummaryEntity> input = new ArrayList<>(facts);
        for (int no = 16; no <= 20; no++) input.add(summary(no, "第" + no + "章独立旧事件"));
        when(gateway.complete(any(), any())).thenReturn(response(check("s1", "PASS", "", "", "无冲突", "")));

        service.check(config, plan, stage, input, 21, 21, job, record);
        ArgumentCaptor<LlmCall> call = ArgumentCaptor.forClass(LlmCall.class);
        verify(gateway).complete(any(), call.capture());
        String prompt = call.getValue().getUserPrompt();
        assertTrue(prompt.contains("第18章独立旧事件") && prompt.contains("第19章独立旧事件")
                && prompt.contains("第20章独立旧事件"));
        assertFalse(prompt.contains("第16章独立旧事件") || prompt.contains("第17章独立旧事件"),
                "旧摘要参考不能随全书历史无限增长");
        assertEquals(12, prompt.split("character-fact-", -1).length - 1,
                "人物事实应与有限投影保持同一上限");
    }

    @Test
    void pendingHistoryFutureSummariesAndStageGoalsAreNotSubmittedFacts() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        ChapterSummaryEntity pending = ChapterSummaryEntity.builder().chapterNo(1).pendingFacts(List.of(
                new ChapterSummaryEntity.StateEntry("设备", LIMIT, LIMIT))).build();
        stage.setStageGoal(LIMIT);
        when(gateway.complete(any(), any())).thenReturn(issueResponse());
        for (List<ChapterSummaryEntity> source : List.of(List.of(pending), List.of(summary(2, LIMIT)),
                List.of(ChapterSummaryEntity.builder().summary(LIMIT).build()))) {
            blocked(service.check(config, plan, stage, source, 2, 2, job, new HashMap<>()), "CAUSALITY_PLAN_UNKNOWN");
        }
        verify(gateway, times(3)).complete(any(), any());
    }

    @Test
    void cancellationAndBudgetFuseBeforeReviewCauseZeroGatewayCalls() {
        for (Mode mode : List.of(Mode.OBSERVE, Mode.ENFORCE)) {
            properties.getCausality().setPlanMode(mode);
            for (boolean budget : List.of(false, true)) {
                job.setCancelRequested(!budget);
                job.setBudgetExceeded(budget);
                assertThrows(AppException.class, this::review);
            }
        }
        assertTrue(record.isEmpty());
        verifyNoInteractions(gateway);
    }

    @Test
    void cachedPassDoesNotOverrideLaterCancellationOrBudgetFuse() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        when(gateway.complete(any(), any())).thenReturn(response(pass("s1")));
        assertTrue(review().acceptable());
        job.setCancelRequested(true);
        assertThrows(AppException.class, this::review);
        job.setCancelRequested(false);
        job.setBudgetExceeded(true);
        assertThrows(AppException.class, this::review);
        verify(gateway, times(1)).complete(any(), any());
    }

    @Test
    void cancellationOrBudgetSetDuringGatewayCallIsNeverDowngradedToObserve() {
        properties.getCausality().setPlanMode(Mode.OBSERVE);
        doAnswer(call -> {
            job.setCancelRequested(true);
            return response(pass("s1"));
        }).when(gateway).complete(any(), any());
        assertThrows(AppException.class, this::review);
        job.setCancelRequested(false);
        doAnswer(call -> {
            job.setBudgetExceeded(true);
            throw new IllegalStateException("transport failure after fuse");
        }).when(gateway).complete(any(), any());
        assertThrows(AppException.class, this::review);
        assertEquals(0, resultKeys(record).size());
        verify(gateway, times(2)).complete(any(), any());
    }

    @Test
    void providerFailureIsDiagnosticAndLocalRejectionPropagatesWithoutRetry() {
        when(gateway.complete(any(), any())).thenThrow(new IllegalStateException("provider unavailable"));
        for (Mode mode : List.of(Mode.OBSERVE, Mode.ENFORCE)) {
            properties.getCausality().setPlanMode(mode);
            PlanValidationResult result = service.check(config, plan, stage, history, 2, 2, job, new HashMap<>());
            assertEquals(mode == Mode.OBSERVE, result.acceptable());
            List<String> diagnostics = mode == Mode.OBSERVE ? result.warnings() : result.blocking();
            assertTrue(diagnostics.stream().anyMatch(v -> v.contains("CAUSALITY_PLAN_UNAVAILABLE")));
        }
        LlmLocalRejectionException local = new LlmLocalRejectionException("request input budget exceeded");
        doThrow(new IllegalStateException("wrapped", local)).when(gateway).complete(any(), any());
        assertSame(local, assertThrows(LlmLocalRejectionException.class, this::review));
        verify(gateway, times(3)).complete(any(), any());
    }

    @Test
    void sameJobAndExactInputsReuseCheckedResponseEvenWhenModeBecomesEnforce() {
        properties.getCausality().setPlanMode(Mode.OBSERVE);
        when(gateway.complete(any(), any())).thenReturn(issueResponse());
        PlanValidationResult observed = review();
        assertTrue(observed.acceptable());
        assertEquals(observed, review());
        ChapterPlanAggregate identical = JSON.parseObject(JSON.toJSONString(plan), ChapterPlanAggregate.class);
        assertEquals(observed, service.check(config, identical, stage, history, 2, 2, job, record));
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        blocked(review(), REASON);
        assertEquals(1, resultKeys(record).size());
        assertTrue(record.entrySet().stream().filter(e -> e.getKey().endsWith(":mode"))
                .allMatch(e -> e.getValue().equals("ENFORCE")));
        verify(gateway, times(1)).complete(any(), any());
    }

    @Test
    void planHistoryStageModelAndJobChangesEachRequireNewReview() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        when(gateway.complete(any(), any())).thenReturn(response(pass("s1")));
        assertTrue(review().acceptable());
        plan.getChapters().get(0).setScenes(List.of(scene("s1", "仅读取桌上的字迹")));
        assertTrue(review().acceptable());
        bound.setSummary("设备电量耗尽");
        assertTrue(review().acceptable());
        stage.setStageGoal("回家整理旧物");
        assertTrue(review().acceptable());
        config.getModule().getChatModel().setModel("audit-model-b");
        assertTrue(review().acceptable());
        assertTrue(service.check(config, plan, stage, history, 2, 2,
                new GenerationJob("causality-job-2"), record).acceptable());
        assertEquals(6, resultKeys(record).size());
        verify(gateway, times(6)).complete(any(), any());
    }

    @Test
    void unknownOrNullModelResponseIsNeverPromotedByCacheReuse() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        when(gateway.complete(any(), any())).thenReturn((String) null);
        PlanValidationResult first = review();
        blocked(first, "CAUSALITY_PLAN_");
        assertEquals(first, review());
        assertEquals("", record.get(resultKeys(record).get(0)));
        verify(gateway, times(1)).complete(any(), any());
    }

    @Test
    void observeParseFailureAndCachedUnknownRemainVisibleWithoutExtraCalls() {
        properties.getCausality().setPlanMode(Mode.OBSERVE);
        for (String raw : List.of("not-json", response(check("s1", "UNKNOWN", "", "", "尚无足够证据", "")))) {
            when(gateway.complete(any(), any())).thenReturn(raw);
            Map<String, String> localRecord = new HashMap<>();
            PlanValidationResult result = service.check(config, plan, stage, history, 2, 2, job, localRecord);
            assertTrue(result.acceptable());
            assertFalse(result.warnings().isEmpty());
            assertTrue(result.warnings().stream().allMatch(v -> v.startsWith("OBSERVE CAUSALITY_PLAN_")));
            assertEquals(result, service.check(config, plan, stage, history, 2, 2, job, localRecord));
        }
        verify(gateway, times(2)).complete(any(), any());
    }

    @Test
    void corruptedNullOrUnauthenticatedCacheEntryFailsClosedWithoutExtraCall() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        when(gateway.complete(any(), any())).thenReturn(response(pass("s1")));
        assertTrue(review().acceptable());
        String key = resultKeys(record).get(0);
        Map<String, String> corrupted = new HashMap<>(record);
        corrupted.put(key, issueResponse());
        blocked(service.check(config, plan, stage, history, 2, 2, job, corrupted), "CAUSALITY_PLAN_CACHE_MISMATCH");
        corrupted = new HashMap<>(record);
        corrupted.put(key, null);
        blocked(service.check(config, plan, stage, history, 2, 2, job, corrupted), "CAUSALITY_PLAN_CACHE_MISMATCH");
        corrupted = new HashMap<>(record);
        corrupted.remove(key + ":hash");
        properties.getCausality().setPlanMode(Mode.OBSERVE);
        PlanValidationResult observed = service.check(config, plan, stage, history, 2, 2, job, corrupted);
        assertTrue(observed.acceptable());
        assertEquals(List.of("OBSERVE CAUSALITY_PLAN_CACHE_MISMATCH"), observed.warnings());
        verify(gateway, times(1)).complete(any(), any());
    }

    @Test
    void anonymousJobsOrMissingRecordMapDoNotReuseAResultAcrossCalls() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        when(gateway.complete(any(), any())).thenReturn(response(pass("s1")));
        for (GenerationJob anonymous : Arrays.asList(null, new GenerationJob(), new GenerationJob(" "))) {
            for (int i = 0; i < 2; i++) {
                assertTrue(service.check(config, plan, stage, history, 2, 2, anonymous, record).acceptable());
            }
        }
        assertTrue(service.check(config, plan, stage, history, 2, 2, job, null).acceptable());
        assertTrue(service.check(config, plan, stage, history, 2, 2, job, null).acceptable());
        assertTrue(resultKeys(record).isEmpty());
        verify(gateway, times(8)).complete(any(), any());
    }

    private PlanValidationResult review() {
        return service.check(config, plan, stage, history, 2, 2, job, record);
    }

    private static void blocked(PlanValidationResult result, String code) {
        assertFalse(result.acceptable(), result.toString());
        assertTrue(result.blocking().stream().anyMatch(value -> value.contains(code)), result.toString());
        assertTrue(result.warnings().isEmpty());
    }

    private static List<String> resultKeys(Map<String, String> values) {
        return values.keySet().stream().filter(key -> key.endsWith(":result")).toList();
    }

    private String issueResponse() {
        return response(issue("s1", RESULT, LIMIT, REASON, REPAIR, factId, 1, hash));
    }

    private static Map<String, String> pass(String sceneId) {
        return check(sceneId, "PASS", "", "", "已核对所需前提；无须额外冲突或对白", "");
    }

    private static Map<String, String> check(String id, String verdict, String left, String right,
                                             String reason, String repair) {
        return Map.of("sceneId", id, "verdict", verdict, "planEvidence", left,
                "basisEvidence", right, "reason", reason, "repair", repair);
    }

    private static Map<String, String> issue(String id, String left, String right, String reason, String repair,
                                             String factId, int sourceChapter, String manuscriptHash) {
        return issue(id, left, right, reason, repair, factId, sourceChapter, manuscriptHash, null);
    }

    private static Map<String, String> issue(String id, String left, String right, String reason, String repair,
                                             String factId, int sourceChapter, String manuscriptHash,
                                             String failureType) {
        Map<String, String> value = new LinkedHashMap<>();
        value.put("sceneId", id);
        value.put("verdict", "ISSUE");
        value.put("planEvidence", left);
        value.put("basisEvidence", right);
        value.put("reason", reason);
        value.put("repair", repair);
        if (factId != null) value.put("factId", factId);
        value.put("sourceChapter", String.valueOf(sourceChapter));
        if (manuscriptHash != null) value.put("manuscriptHash", manuscriptHash);
        if (failureType != null) value.put("failureType", failureType);
        return value;
    }

    @SafeVarargs
    private static String response(Map<String, String>... checks) {
        return JSON.toJSONString(Map.of("checks", Arrays.asList(checks)));
    }

    private static ScenePlan scene(String id, String outcome) {
        return new ScenePlan(id, List.of("江燃"), "核对已有信息", null, null,
                outcome, "消化", null, "书房", "当晚");
    }

    private static ChapterPlanAggregate plan(ScenePlan... scenes) {
        return ChapterPlanAggregate.builder().storyId("synthetic-causality")
                .chapters(List.of(ChapterPlanItemEntity.builder().schemaVersion(2).chapterNo(2)
                        .scenes(Arrays.asList(scenes)).build())).build();
    }

    /** 旧摘要兼容：只带文本，没有结构化事实。 */
    private static ChapterSummaryEntity summary(int chapter, String text) {
        return ChapterSummaryEntity.builder().chapterNo(chapter).summary(text).manuscriptHash("history-hash")
                .characterStates(List.of(new ChapterSummaryEntity.StateEntry("江燃", text, text))).build();
    }

    private static CharacterEffect effect(String name, Kind kind, String key, State state,
                                          String value, String evidence) {
        return new CharacterEffect(name, kind, key, state, value, evidence, "亲历场景中明确获知", null);
    }

    /** 与真实提交一致：先绑定再使用，测试不手写 factId/hash。 */
    private static ChapterSummaryEntity factSummary(int chapter, String text, CharacterEffect effect) {
        ChapterManuscript manuscript = new ChapterManuscript(chapter, "测试章" + chapter, text);
        ChapterSummaryEntity summary = ChapterSummaryEntity.builder().chapterNo(chapter)
                .title(manuscript.title()).summary(text).manuscriptHash(manuscript.hash())
                .characterEffects(new ArrayList<>(List.of(effect))).build();
        CharacterStateProjector.bind(summary, manuscript, List.of());
        return summary;
    }

    private static ChapterSummaryEntity factSummary(int chapter, String text, CharacterEffect effect,
                                                    List<ChapterSummaryEntity> history) {
        ChapterSummaryEntity summary = factSummary(chapter, text, effect);
        summary.setCharacterEffects(new ArrayList<>(List.of(effect)));
        CharacterStateProjector.bind(summary, new ChapterManuscript(chapter, "测试章" + chapter, text), history);
        return summary;
    }

    private static StoryVO config() {
        StoryVO config = new StoryVO();
        StoryVO.Module module = new StoryVO.Module();
        StoryVO.Module.ChatModel model = new StoryVO.Module.ChatModel();
        model.setModel("audit-model-a");
        model.setMaxTokens(2048L);
        module.setChatModel(model);
        config.setModule(module);
        return config;
    }
}
