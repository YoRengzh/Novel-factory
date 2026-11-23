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
        when(gateway.complete(any(), any())).thenReturn("```json\n" + response(pass("quiet", "回忆旧信")) + "\n```");
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
    void unverifiedCandidateReferencesStayVisibleAndUnknownRetainsModeSemantics() {
        String raw = "{\"chapters\":[{\"chapterNo\":2,\"schemaVersion\":2,\"title\":\"核对设备\",\"goal\":\"核对\","
                + "\"scenes\":[{\"id\":\"s1\",\"characters\":[\"江燃\"],\"intention\":\"核对设备\",\"function\":\"静场\","
                + "\"outcome\":\"获得远程扫描结果\",\"entryStates\":[\"设备已具备远程能力\"],"
                + "\"causality\":{\"constraints\":[\"通行牌允许进入禁地\"]}}]}]}";
        ChapterPlanAggregate candidate = ChapterPlanChecks.parseLenient(raw);
        assertNotNull(candidate);
        String before = JSON.toJSONString(candidate);
        properties.getCausality().setPlanMode(Mode.OFF);
        assertTrue(service.check(config, candidate, null, List.of(), 2, 2, job, new HashMap<>()).acceptable());
        verifyNoInteractions(gateway);

        when(gateway.complete(any(), any())).thenReturn(response(
                check("s1", "UNKNOWN", "", "", "缺少可核对的设备来源", "")));
        for (Mode mode : List.of(Mode.ENFORCE, Mode.OBSERVE)) {
            properties.getCausality().setPlanMode(mode);
            PlanValidationResult result = service.check(config, candidate, null, List.of(), 2, 2, job, new HashMap<>());
            if (mode == Mode.ENFORCE) {
                blocked(result, "CAUSALITY_PLAN_UNKNOWN");
            } else {
                assertTrue(result.acceptable());
                assertTrue(result.warnings().stream().anyMatch(value -> value.contains("CAUSALITY_PLAN_UNKNOWN")));
            }
        }
        ArgumentCaptor<LlmCall> calls = ArgumentCaptor.forClass(LlmCall.class);
        verify(gateway, times(2)).complete(same(config.getModule()), calls.capture());
        for (LlmCall call : calls.getAllValues()) {
            String prompt = call.getUserPrompt();
            assertTrue(prompt.contains("【候选计划】\n" + before));
            for (String rule : List.of("只是待核验引用，不是已提交事实", "裸字符串兼容读取形成的描述",
                    "不得作为既成事实或已有能力的依据", "六字段齐全也不等于来源核验通过",
                    "已提交事实或合理的场景内行动支撑", "不要仅因存在未核验引用就否定场景或增加证明配额",
                    "同场不同字段的内部矛盾仍可按现有规则判ISSUE")) {
                assertTrue(prompt.contains(rule), prompt);
            }
        }
        assertEquals(before, JSON.toJSONString(candidate));
    }

    @Test
    void quietSceneWithAnUnverifiedReferenceCanStillPass() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        ScenePlan quiet = new ScenePlan("quiet", List.of("江燃"), "整理旧物", null,
                List.of(FactReference.fromText("设备可能仍可使用")), null, "余韵", null, null, null);
        when(gateway.complete(any(), any())).thenReturn(response(pass("quiet", "整理旧物")));
        var result = service.check(config, plan(quiet), null, List.of(), 2, 2, job, new HashMap<>());
        assertTrue(result.acceptable());
        assertTrue(result.warnings().isEmpty());
        verify(gateway).complete(any(), any());
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
        when(gateway.complete(any(), any())).thenReturn(response(planIssue("s1", "scene.outcome", completed,
                "s1", "scene.causality.terms.payment", "尚未支付，明天转账", "支付时点与结果冲突", "对齐实际支付时点")));
        blocked(service.check(config, plan(transaction), null, List.of(), 2, 2, job, new HashMap<>()), "支付时点与结果冲突");
        ScenePlan single = scene("s1", "未支付；已经支付");
        when(gateway.complete(any(), any())).thenReturn(response(planIssue("s1", "scene.outcome", "未支付",
                "s1", "scene.outcome", "已经支付", "相同字段", "核对")));
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
        // v4知识回退也不能把BELIEF当成确知事实，即使旧v3曾接受此类授权。
        when(gateway.complete(any(), any())).thenReturn(response(issue("s1", RESULT, beliefText,
                "人物认知被抹掉", REPAIR, beliefId, 1, belief.getManuscriptHash(), "KNOWLEDGE_REGRESSION")));
        blocked(service.check(config, plan, stage, List.of(belief), 2, 2, job, new HashMap<>()), "CAUSALITY_PLAN_UNKNOWN");
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

    @Test
    void crossChapterConflictRequiresExplicitSceneFieldBindingsAndContinuousQuotes() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        String failed = "寻药未果，未取得救治所需的药材";
        String success = "用刚取得的药材完成救治";
        ChapterPlanAggregate candidate = ChapterPlanAggregate.builder().chapters(List.of(
                ChapterPlanItemEntity.builder().chapterNo(2).scenes(List.of(scene("search", failed))).build(),
                ChapterPlanItemEntity.builder().chapterNo(3).scenes(List.of(scene("heal", success))).build())).build();
        Map<String, String> conflict = planIssue("heal", "scene.outcome", success,
                "search", "scene.outcome", failed, "必要药材缺失而直接完成救治", "对齐取得药材与救治的必要接续");
        String before = JSON.toJSONString(candidate);
        when(gateway.complete(any(), any())).thenReturn(response(pass("search"), conflict));
        PlanValidationResult valid = service.check(config, candidate, null, List.of(), 2, 3, job, new HashMap<>());
        blocked(valid, "跨场 scene=search");
        assertFalse(valid.blocking().get(0).contains("UNKNOWN"));
        for (String field : List.of("planField", "basisSceneId", "basisPlanField")) {
            Map<String, String> missing = new LinkedHashMap<>(conflict);
            missing.remove(field);
            when(gateway.complete(any(), any())).thenReturn(response(pass("search"), missing));
            blocked(service.check(config, candidate, null, List.of(), 2, 3, job, new HashMap<>()), "CAUSALITY_PLAN_UNKNOWN");
        }
        for (Map<String, String> overrides : List.of(Map.of("basisSceneId", "heal"),
                Map.of("planField", "scene.intention"), Map.of("basisPlanField", "scene.intention"),
                Map.of("basisEvidence", "寻药未果救治所需"), Map.of("planEvidence", "用药材救治"),
                Map.of("basisSceneId", "不存在"), Map.of("factId", factId))) {
            Map<String, String> invalid = new LinkedHashMap<>(conflict);
            invalid.putAll(overrides);
            when(gateway.complete(any(), any())).thenReturn(response(pass("search"), invalid));
            blocked(service.check(config, candidate, null, List.of(), 2, 3, job, new HashMap<>()), "CAUSALITY_PLAN_UNKNOWN");
        }
        assertEquals(before, JSON.toJSONString(candidate));
    }

    @Test
    void knowledgeSubjectsCannotBeReassignedEvenWhenBothCharactersArePresent() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        String knows = "江燃亲眼看见旧信被烧毁。";
        ChapterSummaryEntity knowledge = factSummary(1, knows,
                effect("江燃", Kind.KNOWLEDGE, "旧信", State.ESTABLISHED, "已烧毁", knows));
        ScenePlan wrongActor = new ScenePlan("s1", List.of("江燃", "林青"), "核对已有信息", null, null,
                "林青从未听说旧信被烧毁", "消化", null, null, null);
        String id = knowledge.getCharacterEffects().get(0).factId();
        Map<String, String> claim = new LinkedHashMap<>(issue("s1", wrongActor.outcome(), knows,
                "认知回退", "保留人物已有知识", id, 1, knowledge.getManuscriptHash(), "KNOWLEDGE_REGRESSION"));
        for (String subject : List.of("江燃", "林青", "")) {
            claim.put("subject", subject);
            when(gateway.complete(any(), any())).thenReturn(response(claim));
            blocked(service.check(config, plan(wrongActor), null, List.of(knowledge), 2, 2, job, new HashMap<>()),
                    "CAUSALITY_PLAN_UNKNOWN");
        }
        ScenePlan correct = new ScenePlan("s1", List.of("江燃", "林青"), "核对已有信息", null, null,
                "江燃从未听说旧信被烧毁", "消化", null, null, null);
        claim.put("subject", "江燃");
        claim.put("planEvidence", correct.outcome());
        when(gateway.complete(any(), any())).thenReturn(response(claim));
        PlanValidationResult confirmed = service.check(config, plan(correct), null, List.of(knowledge), 2, 2, job, new HashMap<>());
        blocked(confirmed, "认知回退");
        assertFalse(confirmed.blocking().get(0).contains("UNKNOWN"));
    }

    @Test
    void crossSceneStructuredActorMismatchDoesNotAuthorizeAConflict() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        SceneCausality a = new SceneCausality(null, List.of(new SceneCausality.CausalStep("江燃",
                "已经确认旧信被烧毁", null, null, null, null)), null, null);
        SceneCausality b = new SceneCausality(null, List.of(new SceneCausality.CausalStep("林青",
                "从未听说旧信被烧毁", null, null, null, null)), null, null);
        ScenePlan first = new ScenePlan("first", List.of("江燃", "林青"), "核对已有信息", null, null,
                "继续等雨", "静场", null, null, null, a);
        ScenePlan second = new ScenePlan("second", List.of("江燃", "林青"), "核对已有信息", null, null,
                "继续等雨", "静场", null, null, null, b);
        Map<String, String> bad = planIssue("second", "scene.causality.steps[0].action", "从未听说旧信被烧毁",
                "first", "scene.causality.steps[0].action", "已经确认旧信被烧毁", "认知回退", "保留知识");
        // 通用类型也不能绕过两个结构化actor不一致。
        when(gateway.complete(any(), any())).thenReturn(response(pass("first"), bad));
        blocked(service.check(config, plan(first, second), null, List.of(), 2, 2, job, new HashMap<>()),
                "CAUSALITY_PLAN_UNKNOWN");
    }

    @Test
    void passNeedsLocalProcessOrExplicitAllowedSummaryAndNeverUnboundSuccess() {
        ScenePlan quiet = scene("s1", "江燃独自等雨停，维持原有决定");
        for (Mode mode : List.of(Mode.ENFORCE, Mode.OBSERVE)) {
            properties.getCausality().setPlanMode(mode);
            for (Map<String, String> invalid : List.of(
                    Map.of("sceneId", "s1", "verdict", "PASS", "reason", "旧版无证通过"),
                    check("s1", "PASS", quiet.outcome(), "", "只有成功结果", ""),
                    Map.of("sceneId", "s1", "verdict", "PASS", "reason", "误配", "planField", "scene.intention",
                            "planEvidence", quiet.outcome(), "evidenceRole", "PROCESS"),
                    Map.of("sceneId", "s1", "verdict", "PASS", "reason", "标签不是过程", "planField", "scene.function",
                            "planEvidence", "消化", "evidenceRole", "ALLOWED_SUMMARY"))) {
                when(gateway.complete(any(), any())).thenReturn(response(invalid));
                PlanValidationResult result = service.check(config, plan(quiet), null, List.of(), 2, 2, job, new HashMap<>());
                if (mode == Mode.ENFORCE) blocked(result, "CAUSALITY_PLAN_UNKNOWN");
                else assertTrue(result.acceptable() && result.warnings().get(0).contains("CAUSALITY_PLAN_UNKNOWN"));
            }
            Map<String, String> summary = new LinkedHashMap<>(check("s1", "PASS", quiet.outcome(), "",
                    "独处等待维持已有决定，可用概述承接，无额外行动前提", ""));
            summary.put("evidenceRole", "ALLOWED_SUMMARY");
            when(gateway.complete(any(), any())).thenReturn(response(summary));
            assertTrue(service.check(config, plan(quiet), null, List.of(), 2, 2, job, new HashMap<>()).acceptable());
        }
    }

    @Test
    void oldVersionCacheCannotAuthorizeV4OrSilentlyReuseEvidenceFreePass() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        when(gateway.complete(any(), any())).thenReturn(response(pass("s1")));
        assertTrue(review().acceptable());
        String resultKey = resultKeys(record).get(0);
        String key = resultKey.substring(0, resultKey.length() - (1 + 64 + ":result".length()));
        String factText = record.get(key + ":prompt");
        assertNotNull(factText);
        // 重建v3指纹：key中保留旧plan/history原文hash，只有review版本变。
        String identity = FactIdentity.of("CHARACTER", 1, hash, factId,
                CharacterFactReferences.from(history, 2).get(0).status(), LIMIT_EVIDENCE);
        String oldFingerprint = ChapterManuscript.digest("scene-evidence-v3-unverified-references\n" + key + "\n"
                + job.getJobId() + "\n" + JSON.toJSONString(config.getModule()) + "\n" + identity);
        String oldKey = key + ":" + oldFingerprint + ":result";
        String oldRaw = response(Map.of("sceneId", "s1", "verdict", "PASS", "reason", "旧版通过"));
        record.remove(resultKey);
        record.remove(resultKey + ":hash");
        record.put(oldKey, oldRaw);
        record.put(oldKey + ":hash", ChapterManuscript.digest(oldRaw));
        when(gateway.complete(any(), any())).thenReturn(oldRaw);
        blocked(review(), "CAUSALITY_PLAN_UNKNOWN");
        verify(gateway, times(2)).complete(any(), any());
        assertEquals(PlanCausalityReviewService.REVIEW_VERSION, record.get(key + ":reviewVersion"));
        assertTrue(record.get(key + ":diagnostics").contains("CAUSALITY_PLAN_UNKNOWN"));
    }

    @Test
    void verificationObjectAndPredeterminedVictoryRulesRemainEvidenceBasedAndQuotaFree() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        when(gateway.complete(any(), any())).thenReturn(response(pass("s1")));
        assertTrue(review().acceptable());
        ArgumentCaptor<LlmCall> request = ArgumentCaptor.forClass(LlmCall.class);
        verify(gateway).complete(any(), request.capture());
        String prompt = request.getValue().getUserPrompt();
        for (String rule : List.of("verificationObligations", "verifier/subject/item/criterion/resultUse/allowedAlternative",
                "不能把另一个人或物件的核验误当本对象通过", "预设胜利审查", "未来结果",
                "不凭推测发ISSUE", "不强制逐项加戏", "PASS必须用planField/planEvidence")) {
            assertTrue(prompt.contains(rule), prompt);
        }
        assertFalse(prompt.contains("苏尘") || prompt.contains("林昭"));
    }

    @Test
    void firstSegmentStoryDirectorySuppliesVerifiedWorldFactsWithoutChangingRawHistory() throws Exception {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository repository = mock(
                cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "storyRepository", repository);
        java.nio.file.Path storyDir = java.nio.file.Path.of("D:/read-only-history");
        String text = "设备只能读取接触面的字迹。";
        ChapterManuscript source = new ChapterManuscript(1, "设备核验", text);
        var content = cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity.builder()
                .chapterNo(1).title("设备核验").content(text).build();
        ChapterSummaryEntity world = ChapterSummaryEntity.builder().chapterNo(1).summary("核验设备")
                .manuscriptHash(source.hash()).itemStates(List.of(new ChapterSummaryEntity.StateEntry("设备", "仅接触读取", text))).build();
        FactReference fact = new FactReference("ITEM", 1, source.hash(), "设备", "仅接触读取", text);
        String id = FactIdentity.of(fact.ledger(), fact.chapterNo(), fact.manuscriptHash(), fact.subject(), fact.status(), fact.evidence());
        when(repository.readChapter(storyDir, 1)).thenReturn(content);
        when(gateway.complete(any(), any())).thenReturn(response(issue("s1", RESULT, LIMIT, REASON, REPAIR,
                id, 1, source.hash())));
        String before = JSON.toJSONString(world);
        PlanValidationResult result = service.check(config, plan, null, List.of(world), 2, 2, job, new HashMap<>(), storyDir);
        blocked(result, REASON);
        assertFalse(result.blocking().get(0).contains("UNKNOWN"));
        verify(repository).readChapter(storyDir, 1);
        assertEquals(before, JSON.toJSONString(world));
        assertEquals(source.hash(), world.getManuscriptHash());
    }

    @Test
    void nullAndBlankOptionalSourceFieldsAreAbsentButWrongNonemptyBindingsCannotFallback() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        Map<String, String> historyIssue = new LinkedHashMap<>(issue("s1", RESULT, LIMIT, REASON, REPAIR, factId, 1, hash));
        historyIssue.put("basisSceneId", null);
        historyIssue.put("basisPlanField", " ");
        when(gateway.complete(any(), any())).thenReturn(response(historyIssue));
        PlanValidationResult valid = service.check(config, plan, stage, history, 2, 2, job, new HashMap<>());
        blocked(valid, REASON);
        assertFalse(valid.blocking().get(0).contains("UNKNOWN"));
        historyIssue.put("basisSceneId", "不存在");
        when(gateway.complete(any(), any())).thenReturn(response(historyIssue));
        blocked(service.check(config, plan, stage, history, 2, 2, job, new HashMap<>()), "CAUSALITY_PLAN_UNKNOWN");

        ChapterPlanAggregate two = plan(scene("s1", RESULT), scene("s2", LIMIT));
        Map<String, String> planConflict = planIssue("s1", "scene.outcome", RESULT, "s2", "scene.outcome", LIMIT, REASON, REPAIR);
        planConflict.put("factId", null);
        planConflict.put("sourceChapter", null);
        planConflict.put("manuscriptHash", "");
        when(gateway.complete(any(), any())).thenReturn(response(planConflict, pass("s2")));
        valid = service.check(config, two, stage, history, 2, 2, job, new HashMap<>());
        blocked(valid, "跨场 scene=s2");
        assertFalse(valid.blocking().get(0).contains("UNKNOWN"));
        planConflict.put("factId", "伪造事实源");
        when(gateway.complete(any(), any())).thenReturn(response(planConflict, pass("s2")));
        blocked(service.check(config, two, stage, history, 2, 2, job, new HashMap<>()), "CAUSALITY_PLAN_UNKNOWN");
    }

    @Test
    void secrecyReviewUsesEstablishedDecisionOrIntendedIntentWithoutReclassifyingFacts() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        String disclosure = "江燃认定自己从未决定或打算保密，直接向众人说明特殊迹象";
        for (Kind kind : List.of(Kind.DECISION, Kind.INTENT)) {
            State state = kind == Kind.DECISION ? State.ESTABLISHED : State.INTENDED;
            String original = kind == Kind.DECISION
                    ? "江燃决定对外仅称普通迹象，除非生死关头不提特殊迹象。"
                    : "江燃打算对外仅称普通迹象，除非生死关头不提特殊迹象。";
            ChapterSummaryEntity policy = factSummary(1, original,
                    effect("江燃", kind, "保密策略", state, "对外仅普通迹象，除非生死不提", original));
            assertEquals(1, policy.getCharacterEffects().size(), "有效策略原文应正常绑定");
            String before = JSON.toJSONString(policy);
            String id = policy.getCharacterEffects().get(0).factId();
            for (String type : List.of("SECRECY_CONFLICT", "SECRET_LEAK")) {
                Map<String, String> claim = new LinkedHashMap<>(issue("s1", disclosure, original,
                        "把仍有效的保密策略写成从未存在", "保留已有策略并核对本场变更依据", id, 1,
                        policy.getManuscriptHash(), type));
                claim.put("subject", "江燃");
                when(gateway.complete(any(), any())).thenReturn(response(claim));
                PlanValidationResult result = service.check(config, plan(scene("s1", disclosure)), null,
                        List.of(policy), 2, 2, job, new HashMap<>());
                blocked(result, "把仍有效的保密策略写成从未存在");
                assertFalse(result.blocking().get(0).contains("UNKNOWN"));
                claim.put("subject", "林青");
                when(gateway.complete(any(), any())).thenReturn(response(claim));
                blocked(service.check(config, plan(scene("s1", disclosure)), null, List.of(policy),
                        2, 2, job, new HashMap<>()), "CAUSALITY_PLAN_UNKNOWN");
            }
            assertEquals(before, JSON.toJSONString(policy));
            assertEquals(kind, policy.getCharacterEffects().get(0).kind());
            assertEquals(state, policy.getCharacterEffects().get(0).state());
        }
    }

    @Test
    void withdrawnOrWrongStateSecrecyReferencesAndBeliefsCannotAuthorizeIssues() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        String original = "江燃决定对外仅称普通迹象。";
        String disclosure = "江燃向众人说明特殊迹象";
        for (String kindState : List.of("DECISION/WITHDRAWN", "INTENT/WITHDRAWN", "COMMITMENT/WITHDRAWN",
                "KNOWLEDGE/WITHDRAWN", "DECISION/INTENDED", "INTENT/ESTABLISHED", "BELIEF/ESTABLISHED")) {
            // 显式事实入口也不能绕过撤回/类型状态检查；身份仍由原始六字段计算。
            FactReference fact = new FactReference("CHARACTER", 1, hash, "secrecy-reference",
                    kindState + "|江燃|保密策略|对外仅普通迹象", original);
            String id = CharacterFactReferences.identity(fact);
            for (String type : List.of("SECRECY_CONFLICT", "SECRET_LEAK")) {
                Map<String, String> claim = new LinkedHashMap<>(issue("s1", disclosure, original,
                        "保密策略矛盾", "核对策略状态", id, 1, hash, type));
                claim.put("subject", "江燃");
                when(gateway.complete(any(), any())).thenReturn(response(claim));
                blocked(service.check(config, plan(scene("s1", disclosure)), null, List.of(), 2, 2,
                        job, new HashMap<>(), List.of(fact)), "CAUSALITY_PLAN_UNKNOWN");
            }
        }
    }

    @Test
    void secrecyDecisionsAndIntentsAreNotKnowledgeOrCommitments() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        String original = "江燃决定暂不公开特殊迹象。";
        String candidate = "江燃从未知道秘密，也从未答应保密";
        for (String kindState : List.of("DECISION/ESTABLISHED", "INTENT/INTENDED")) {
            FactReference fact = new FactReference("CHARACTER", 1, hash, "secrecy-reference",
                    kindState + "|江燃|保密策略|暂不公开", original);
            for (String type : List.of("KNOWLEDGE_REGRESSION", "COMMITMENT_CONFLICT", "COMMITMENT_REGRESSION")) {
                Map<String, String> claim = new LinkedHashMap<>(issue("s1", candidate, original,
                        "不能偷换策略语义", "按实际事实类型核对", CharacterFactReferences.identity(fact), 1, hash, type));
                claim.put("subject", "江燃");
                when(gateway.complete(any(), any())).thenReturn(response(claim));
                blocked(service.check(config, plan(scene("s1", candidate)), null, List.of(), 2, 2,
                        job, new HashMap<>(), List.of(fact)), "CAUSALITY_PLAN_UNKNOWN");
            }
        }
    }

    @Test
    void justifiedSecrecyPolicyChangeCanPassAndMissingNecessaryChangePremiseStaysUnknown() {
        properties.getCausality().setPlanMode(Mode.ENFORCE);
        String original = "江燃决定对外仅称普通迹象，除非生死关头不提特殊迹象。";
        ChapterSummaryEntity policy = factSummary(1, original,
                effect("江燃", Kind.DECISION, "保密策略", State.ESTABLISHED, "除非生死不提", original));
        String change = "救援者无法辨认危险，江燃判断已是生死关头，决定只向救援者说明特殊迹象以求脱险";
        SceneCausality process = new SceneCausality(null, List.of(new SceneCausality.CausalStep("江燃", change,
                null, null, "生死例外已触发，限于救援用途调整公开范围", null)), null, null);
        ScenePlan candidate = new ScenePlan("s1", List.of("江燃"), "寻求救援", null, null,
                "江燃向救援者说明特殊迹象", "推进", null, null, null, process);
        String beforePlan = JSON.toJSONString(candidate), beforeHistory = JSON.toJSONString(policy);
        Map<String, String> passed = new LinkedHashMap<>(pass("s1", change));
        passed.put("planField", "scene.causality.steps[0].action");
        passed.put("reason", "已有生死例外触发且有本场重新决策，保密策略合理调整，不是永久禁令");
        when(gateway.complete(any(), any())).thenReturn(response(passed));
        assertTrue(service.check(config, plan(candidate), null, List.of(policy), 2, 2, job, new HashMap<>()).acceptable());
        Map<String, String> summary = new LinkedHashMap<>(passed);
        summary.put("planField", "scene.outcome");
        summary.put("planEvidence", candidate.outcome());
        summary.put("evidenceRole", "ALLOWED_SUMMARY");
        when(gateway.complete(any(), any())).thenReturn(response(summary));
        assertTrue(service.check(config, plan(candidate), null, List.of(policy), 2, 2, job, new HashMap<>()).acceptable());
        when(gateway.complete(any(), any())).thenReturn(response(check("s1", "UNKNOWN", "", "",
                "结果依赖策略变更但没有可核对的触发前提", "")));
        blocked(service.check(config, plan(scene("s1", "江燃已取得按新策略公开秘密的授权")), null,
                List.of(policy), 2, 2, job, new HashMap<>()), "CAUSALITY_PLAN_UNKNOWN");
        ArgumentCaptor<LlmCall> calls = ArgumentCaptor.forClass(LlmCall.class);
        verify(gateway, times(3)).complete(any(), calls.capture());
        for (LlmCall call : calls.getAllValues()) {
            for (String rule : List.of("DECISION/ESTABLISHED", "INTENT/INTENDED", "WITHDRAWN",
                    "不得强制改成KNOWLEDGE", "承诺语义仍只由COMMITMENT支撑", "保密决定不是永恒硬约束",
                    "条件例外", "变更依据", "不因策略改变自动判ISSUE", "不强制增加变更对白或事件")) {
                assertTrue(call.getUserPrompt().contains(rule), call.getUserPrompt());
            }
        }
        assertEquals(beforePlan, JSON.toJSONString(candidate));
        assertEquals(beforeHistory, JSON.toJSONString(policy));
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
        return pass(sceneId, "核对已有信息");
    }

    private static Map<String, String> pass(String sceneId, String process) {
        Map<String, String> value = new LinkedHashMap<>(check(sceneId, "PASS", process, "",
                "已核对所需前提；无须额外冲突或对白", ""));
        value.put("planField", "scene.intention");
        value.put("evidenceRole", "PROCESS");
        return value;
    }

    private static Map<String, String> check(String id, String verdict, String left, String right,
                                             String reason, String repair) {
        return Map.of("sceneId", id, "verdict", verdict, "planField", "scene.outcome", "planEvidence", left,
                "basisEvidence", right, "reason", reason, "repair", repair,
                "failureType", "CAPABILITY_CONFLICT");
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
        value.put("planField", "scene.outcome");
        value.put("planEvidence", left);
        value.put("basisEvidence", right);
        value.put("reason", reason);
        value.put("repair", repair);
        if (factId != null) value.put("factId", factId);
        value.put("sourceChapter", String.valueOf(sourceChapter));
        if (manuscriptHash != null) value.put("manuscriptHash", manuscriptHash);
        value.put("failureType", failureType == null ? "CAPABILITY_CONFLICT" : failureType);
        return value;
    }

    private static Map<String, String> planIssue(String sceneId, String planField, String left,
                                                 String basisScene, String basisField, String right,
                                                 String reason, String repair) {
        Map<String, String> value = new LinkedHashMap<>(check(sceneId, "ISSUE", left, right, reason, repair));
        value.put("planField", planField);
        value.put("basisSceneId", basisScene);
        value.put("basisPlanField", basisField);
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
