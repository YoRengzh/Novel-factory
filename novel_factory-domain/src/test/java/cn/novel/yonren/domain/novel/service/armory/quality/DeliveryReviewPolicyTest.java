package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.config.StoryProperties.CausalityProperties.Mode;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.valobj.plan.CausalVerdict;
import cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality;
import cn.novel.yonren.domain.novel.model.valobj.plan.ScenePlan;
import cn.novel.yonren.domain.novel.model.valobj.quality.DeliveryReview;
import cn.novel.yonren.domain.novel.model.valobj.quality.GateResult;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryReviewPolicyTest {
    private static final String EVENT = "交付药签";

    @Test
    void lexicalCoverageDoesNotProveProcessWasDelivered() {
        var item = legacy(EVENT);
        var request = request(item, "交付药签已经完成。", true);
        assertEquals(1.0, PlanAdherencePolicy.coverage(item.getKeyEvents(), request.manuscript().content()));
        var result = DeliveryReviewPolicy.consume(request, List.of(check("event:1", null,
                CausalVerdict.Status.ISSUE, "交付药签已经完成。", "keyEvents[0]", EVENT,
                CausalVerdict.FailureType.RESULT_WITHOUT_CAUSE)));

        assertEquals(DeliveryReview.Status.ISSUE, result.status());
        assertTrue(result.blocksCommit());
        assertEquals(1, DeliveryReviewPolicy.blockingIssues(result).size());
        assertEquals("plan-delivery", DeliveryReviewPolicy.blockingIssues(result).get(0).getDimension());
    }

    @Test
    void equivalentProcessCanPassWithoutLexicalMatch() {
        var item = legacy(EVENT);
        var request = request(item, "林岚将凭条递过柜台，药师逐字核对后收入匣中。", true);
        assertEquals(0.0, PlanAdherencePolicy.coverage(item.getKeyEvents(), request.manuscript().content()));
        var result = DeliveryReviewPolicy.consume(request, List.of(check("event:1", null,
                CausalVerdict.Status.PASS, request.manuscript().content(), "keyEvents[0]", EVENT, null)));

        assertTrue(result.passed());
        assertFalse(result.blocksCommit());
    }

    @Test
    void declaredStepsAreSeparateMandatoryTargetsEvenWithoutDerivedKeyEvents() {
        var item = scenePlan();
        var request = request(item, "药师收下凭条，核过印鉴，将回执放在柜台。", true);
        assertNull(item.getKeyEvents());
        var contract = request.contract();
        assertEquals(DeliveryReview.Scope.SCENES, contract.scope());
        assertEquals(List.of("scene:counter", "scene:counter/step:1"), new ArrayList<>(contract.targets().keySet()));
        var scenePass = check("scene:counter", "counter", CausalVerdict.Status.PASS,
                request.manuscript().content(), "outcome", "拿到回执", null);
        assertTrue(DeliveryReviewPolicy.consume(request, List.of(scenePass)).unknown(), "不能只证明结果而漏验关键步骤");
        var stepPass = check("scene:counter/step:1", "counter", CausalVerdict.Status.PASS,
                "核过印鉴", "causality.steps[0].action", "核对凭条印鉴", null);
        assertTrue(DeliveryReviewPolicy.consume(request, List.of(scenePass, stepPass)).passed());
        var intentOnly = check("scene:counter", "counter", CausalVerdict.Status.PASS,
                request.manuscript().content(), "intention", "交付凭条", null);
        assertTrue(DeliveryReviewPolicy.consume(request, List.of(intentOnly, stepPass)).unknown(),
                "意图本身不是本场交付义务的完成证据");
    }

    @Test
    void duplicateUnknownAndMissingIdentitiesAreUnknown() {
        var request = request(legacy(EVENT), "交付药签", true);
        var pass = check("event:1", null, CausalVerdict.Status.PASS, EVENT, "keyEvents[0]", EVENT, null);
        assertTrue(DeliveryReviewPolicy.consume(request, List.of(pass, pass)).unknown());
        assertTrue(DeliveryReviewPolicy.consume(request, List.of()).unknown());
        assertTrue(DeliveryReviewPolicy.consume(request, null).unknown());
        assertTrue(DeliveryReviewPolicy.consume(request, List.of(check("event:2", null,
                CausalVerdict.Status.PASS, EVENT, "keyEvents[0]", EVENT, null))).unknown());
        List<CausalVerdict> nullItem = new ArrayList<>();
        nullItem.add(null);
        assertTrue(DeliveryReviewPolicy.consume(request, nullItem).unknown());
    }

    @Test
    void falseEvidenceOrCrossFieldPlanQuoteNeverBecomesPass() {
        var request = request(legacy(EVENT), "交付药签", true);
        assertTrue(DeliveryReviewPolicy.consume(request, List.of(check("event:1", null,
                CausalVerdict.Status.PASS, "药师签收药签", "keyEvents[0]", EVENT, null))).unknown());
        assertTrue(DeliveryReviewPolicy.consume(request, List.of(check("event:1", null,
                CausalVerdict.Status.PASS, EVENT, "keyEvents[1]", EVENT, null))).unknown());
        assertTrue(DeliveryReviewPolicy.consume(request, List.of(check("event:1", null,
                CausalVerdict.Status.PASS, EVENT, "keyEvents[0]", "计划中不存在的义务", null))).unknown());
        assertTrue(DeliveryReviewPolicy.consume(request, List.of(check("event:1", null,
                CausalVerdict.Status.PASS, "。", "keyEvents[0]", EVENT, null))).unknown());
    }

    @Test
    void completeAbsenceRequiresPlanBasisNotFabricatedContentEvidence() {
        var request = request(legacy(EVENT), "雨落在院墙上。", true);
        var result = DeliveryReviewPolicy.consume(request, List.of(check("event:1", null,
                CausalVerdict.Status.ISSUE, null, "keyEvents[0]", EVENT,
                CausalVerdict.FailureType.MISSING_DELIVERY)));
        assertEquals(DeliveryReview.Status.ISSUE, result.status());
        assertNull(DeliveryReviewPolicy.blockingIssues(result).get(0).getEvidence());
        assertEquals("BLOCKING", DeliveryReviewPolicy.blockingIssues(result).get(0).getSeverity());
        assertTrue(DeliveryReviewPolicy.consume(request, List.of(check("event:1", null,
                CausalVerdict.Status.ISSUE, null, "keyEvents[0]", EVENT,
                CausalVerdict.FailureType.RESULT_WITHOUT_CAUSE))).unknown(), "宣布式缺口须定位宣布句");
    }

    @Test
    void observeRetainsIssueWithoutBlockingOrDrivingRevision() {
        var request = request(legacy(EVENT), "交付药签已经完成。", false);
        var result = DeliveryReviewPolicy.consume(request, List.of(check("event:1", null,
                CausalVerdict.Status.ISSUE, request.manuscript().content(), "keyEvents[0]", EVENT,
                CausalVerdict.FailureType.RESULT_WITHOUT_CAUSE)));
        assertEquals(DeliveryReview.Status.ISSUE, result.status());
        assertFalse(result.blocksCommit());
        assertTrue(DeliveryReviewPolicy.blockingIssues(result).isEmpty());
    }

    @Test
    void versionBindingRejectsChangedTitleContentAndPlan() {
        var item = legacy(EVENT);
        var request = request(item, "交付药签", true);
        var report = DeliveryReviewPolicy.consume(request, List.of(check("event:1", null,
                CausalVerdict.Status.PASS, EVENT, "keyEvents[0]", EVENT, null)));
        assertTrue(DeliveryReviewPolicy.ensureBound(report, item, request.manuscript(), true).passed());
        assertTrue(DeliveryReviewPolicy.ensureBound(report, item,
                new ChapterManuscript(1, "另一个标题", EVENT), true).unknown());
        assertTrue(DeliveryReviewPolicy.ensureBound(report, item,
                new ChapterManuscript(1, "柜台", "宣告完成"), true).unknown());
        item.setEndingHook("柜外有人等待");
        assertTrue(DeliveryReviewPolicy.ensureBound(report, item, request.manuscript(), true).unknown());
    }

    @Test
    void localBindingDiscardsModelSuppliedFactIdentity() {
        var request = request(legacy(EVENT), "交付药签", true);
        var check = new CausalVerdict(null, CausalVerdict.Status.PASS, EVENT, "伪造依据", "凭条已交付", null,
                null, "forged-id", 99, "forged-hash", "world-fact", "event:1", "keyEvents[0]", EVENT);
        var report = DeliveryReviewPolicy.consume(request, List.of(check));
        assertTrue(report.passed());
        assertEquals(request.manuscript().hash(), report.manuscriptHash());
        assertEquals(request.manuscript().hash(), report.checks().get(0).manuscriptHash());
        assertEquals(1, report.checks().get(0).sourceChapter());
        assertNull(report.checks().get(0).factId());
        assertNull(report.checks().get(0).basisEvidence());
    }

    @Test
    void unknownNeverMasqueradesAsCleanOrContentDebt() {
        var report = DeliveryReviewPolicy.unavailable(request(legacy(EVENT), "稿件", true), "模型不可用");
        var gate = GateResult.of(List.of(), 0, 0).withDeliveryReview(report).withSemanticFeedbackHash("origin");
        assertEquals(GateResult.PassGrade.UNVERIFIED, gate.grade());
        assertFalse(gate.isLowConfidencePass());
        assertTrue(gate.unresolvedBlocking().isEmpty());
        assertTrue(DeliveryReviewPolicy.blockingIssues(report).isEmpty());
        assertEquals(report, gate.withPlanAdjustmentRequired("计划含糊").deliveryReview());
    }

    @Test
    void disabledAuditCannotSilentlySatisfyEnforcement() {
        var properties = new StoryProperties();
        properties.getCausality().setAuditMode(Mode.ENFORCE);
        properties.getAudit().setEnabled(false);
        assertThrows(IllegalArgumentException.class, () -> DeliveryReviewPolicy.assertConfiguration(properties));
        properties.getCausality().setAuditMode(Mode.OBSERVE);
        assertDoesNotThrow(() -> DeliveryReviewPolicy.assertConfiguration(properties));
    }

    @Test
    void legacyIndicesAreStableAndEmptyPlanIsNotVacuousPass() {
        var item = legacy("", EVENT);
        var contract = DeliveryReviewPolicy.contract(item);
        assertEquals(List.of("event:2"), new ArrayList<>(contract.targets().keySet()));
        assertEquals(EVENT, contract.targets().get("event:2").fields().get("keyEvents[1]"));
        var empty = request(legacy(), "正文", true);
        assertTrue(DeliveryReviewPolicy.consume(empty, List.of()).unknown());
    }

    @Test
    void quietSceneAndBriefOutcomeHaveNoInventedStepQuota() {
        var item = new ChapterPlanItemEntity();
        item.setSchemaVersion(2);
        item.setScenes(List.of(new ScenePlan("quiet", List.of("林岚"), "等待雨停", null,
                List.of(), "继续等待", "静场·消化昨日的失落", List.of(), "屋檐下", "午后")));
        var request = request(item, "雨还没有停，林岚抱着膝盖坐回门槛。", true);
        assertEquals(1, request.contract().targets().size());
        var report = DeliveryReviewPolicy.consume(request, List.of(check("scene:quiet", "quiet",
                CausalVerdict.Status.PASS, request.manuscript().content(), "function", "静场·消化昨日的失落", null)));
        assertTrue(report.passed());
        String prompt = DeliveryReviewPolicy.prompt(request);
        assertTrue(prompt.contains("不新增对白"));
        assertTrue(prompt.contains("心理、讲述与余韵"));
        assertTrue(prompt.contains("意图不等于成功"));
    }

    @Test
    void duplicateScenesOrMissingStepActionCannotAuthorizePass() {
        var item = scenePlan();
        item.setScenes(List.of(item.getScenes().get(0), item.getScenes().get(0)));
        assertFalse(DeliveryReviewPolicy.contract(item).diagnostics().isEmpty());
        item.setScenes(List.of(new ScenePlan("invalid", List.of("林岚"), "交付", "等候", List.of(), "完成", null,
                List.of(), null, null, new SceneCausality(null,
                List.of(new SceneCausality.CausalStep("林岚", "", null, null, null, null)), null, null))));
        assertFalse(DeliveryReviewPolicy.contract(item).diagnostics().isEmpty());
    }

    @Test
    void verificationAndMeaningfulTermsAreFixedTargetsWithoutStepsAndCannotBeSkipped() {
        var item = verificationPlan(null, true);
        var request = request(item, "执事验过林岚的药材，封好通行凭条。", true);
        assertEquals(List.of("scene:exam", "scene:exam/terms", "scene:exam/verification:1"),
                new ArrayList<>(request.contract().targets().keySet()));
        assertEquals(List.of(), request.contract().diagnostics());
        var fields = request.contract().targets().get("scene:exam/verification:1").fields();
        assertEquals("执事", fields.get("causality.verificationObligations[0].verifier"));
        assertEquals("林岚", fields.get("causality.verificationObligations[0].subject"));
        assertEquals("药材成色", fields.get("causality.verificationObligations[0].item"));
        assertEquals("与样品同等成色", fields.get("causality.verificationObligations[0].criterion"));
        assertFalse(fields.containsKey("causality.verificationObligations[0].allowedAlternative"));
        var scenePass = examScenePass(request);
        var verificationPass = verificationCheck(request, CausalVerdict.Status.PASS, null);
        assertTrue(DeliveryReviewPolicy.consume(request, List.of(scenePass, verificationPass)).unknown(), "不能漏验已声明条款");
        var termsPass = check("scene:exam/terms", "exam", CausalVerdict.Status.PASS, request.manuscript().content(),
                "causality.terms.consideration", "通行凭条", null);
        assertTrue(DeliveryReviewPolicy.consume(request, List.of(scenePass, termsPass)).unknown(), "不能漏验被验项目");
        assertTrue(DeliveryReviewPolicy.consume(request, List.of(scenePass, termsPass, verificationPass)).passed());
        assertTrue(DeliveryReviewPolicy.consume(request, List.of(scenePass, termsPass, verificationPass, verificationPass)).unknown());
        var identityOnly = check("scene:exam/verification:1", "exam", CausalVerdict.Status.PASS,
                request.manuscript().content(), "causality.verificationObligations[0].subject", "林岚", null);
        assertTrue(DeliveryReviewPolicy.consume(request, List.of(scenePass, termsPass, identityOnly)).unknown());
        var crossTarget = check("scene:exam/verification:1", "exam", CausalVerdict.Status.PASS,
                request.manuscript().content(), "causality.terms.consideration", "通行凭条", null);
        assertTrue(DeliveryReviewPolicy.consume(request, List.of(scenePass, termsPass, crossTarget)).unknown());
    }

    @Test
    void examiningOpponentCannotDischargeOriginalSubjectAndNewAlternativeRuleIsReportedAsIssue() {
        var request = request(verificationPlan(null, false), "林岚指出对手的药材掺假。执事让对手退下，宣布林岚已合格。", true);
        var failure = new CausalVerdict("exam", CausalVerdict.Status.ISSUE, "宣布林岚已合格", null,
                "核对全文，仅验出对手掺假，未按与样品同等成色核验林岚的药材；计划没有免验授权",
                "保留拆穿对手情节，补原主体林岚的药材核验，或保留待验结果；不得新增拆穿即可免验的规则",
                CausalVerdict.FailureType.RESULT_WITHOUT_CAUSE, null, null, null, null,
                "scene:exam/verification:1", "causality.verificationObligations[0].criterion", "与样品同等成色");
        var result = DeliveryReviewPolicy.consume(request, List.of(examScenePass(request), failure));
        assertEquals(DeliveryReview.Status.ISSUE, result.status());
        assertTrue(result.blocksCommit());
        assertTrue(DeliveryReviewPolicy.blockingIssues(result).get(0).getDescription().contains("verification:1"));
        assertEquals(failure.planField(), result.checks().get(1).planField());
        String prompt = DeliveryReviewPolicy.prompt(request);
        for (String required : List.of("查出对手的问题不能代替证明主角达标", "保持原主体、项目和标准",
                "allowedAlternative 缺席不授权新增替代规则", "授权含糊用 UNKNOWN", "全文核对结论")) {
            assertTrue(prompt.contains(required), required);
        }
    }

    @Test
    void originalVerificationMayBeMissingEvenWhenADifferentExamOccurs() {
        var request = request(verificationPlan(null, false), "执事只检查对手的药材后散场，林岚仍在门外等待。", true);
        var missing = check("scene:exam/verification:1", "exam", CausalVerdict.Status.ISSUE, null,
                "causality.verificationObligations[0].item", "药材成色", CausalVerdict.FailureType.MISSING_DELIVERY);
        var report = DeliveryReviewPolicy.consume(request, List.of(examScenePass(request), missing));
        assertEquals(DeliveryReview.Status.ISSUE, report.status());
        assertNull(report.checks().get(1).evidence());
        assertEquals("BLOCKING", DeliveryReviewPolicy.blockingIssues(report).get(0).getSeverity());
    }

    @Test
    void authorizedAlternativePassesOnlyAgainstItsOwnPlanFieldAndVersion() {
        String alternative = "作者已授权以林岚本人上次的验药记录代验，须执事确认记录完整且仍适用于同批药材";
        var item = verificationPlan(alternative, false);
        var request = request(item, "执事对照林岚上次的验药记录，确认记录完整且仍是同批药材，按旧约放行。", true);
        var alternativePass = check("scene:exam/verification:1", "exam", CausalVerdict.Status.PASS,
                request.manuscript().content(), "causality.verificationObligations[0].allowedAlternative", alternative, null);
        var report = DeliveryReviewPolicy.consume(request, List.of(examScenePass(request), alternativePass));
        assertTrue(report.passed());
        assertTrue(DeliveryReviewPolicy.ensureBound(report, item, request.manuscript(), true).passed());
        assertTrue(DeliveryReviewPolicy.ensureBound(report, verificationPlan(null, false), request.manuscript(), true).unknown());
        assertTrue(DeliveryReviewPolicy.consume(request(verificationPlan(null, false), request.manuscript().content(), true),
                List.of(examScenePass(request), alternativePass)).unknown(), "不能引用原计划没有的替代字段");
        var oldSceneOnly = DeliveryReviewPolicy.consume(request(legacy("取得通行凭条"), request.manuscript().content(), true),
                List.of(check("event:1", null, CausalVerdict.Status.PASS, request.manuscript().content(),
                        "keyEvents[0]", "取得通行凭条", null)));
        assertTrue(DeliveryReviewPolicy.ensureBound(oldSceneOnly, item, request.manuscript(), true).unknown());
        assertTrue(DeliveryReviewPolicy.prompt(request).contains("已有明确授权且条件在正文成立时可按该替代判 PASS"));
    }

    @Test
    void eachVerificationIdentityItemCriterionUseAndAlternativeChangeInvalidatesPriorReport() {
        var item = verificationPlan(null, false);
        var request = request(item, "执事验过林岚药材后签下通行凭条。", true);
        var report = DeliveryReviewPolicy.consume(request, List.of(examScenePass(request),
                verificationCheck(request, CausalVerdict.Status.PASS, null)));
        assertTrue(report.passed());
        var original = item.getScenes().get(0);
        for (var changed : List.of(
                new SceneCausality.VerificationObligation("对手", "林岚", "药材成色", "与样品同等成色", "按结果决定是否给通行凭条", null),
                new SceneCausality.VerificationObligation("执事", "对手", "药材成色", "与样品同等成色", "按结果决定是否给通行凭条", null),
                new SceneCausality.VerificationObligation("执事", "林岚", "药材重量", "与样品同等成色", "按结果决定是否给通行凭条", null),
                new SceneCausality.VerificationObligation("执事", "林岚", "药材成色", "验出不是假药", "按结果决定是否给通行凭条", null),
                new SceneCausality.VerificationObligation("执事", "林岚", "药材成色", "与样品同等成色", "只决定登记次序", null),
                new SceneCausality.VerificationObligation("执事", "林岚", "药材成色", "与样品同等成色", "按结果决定是否给通行凭条", "作者新增合法替代"))) {
            var newPlan = verificationPlan(null, false);
            newPlan.setScenes(List.of(new ScenePlan(original.id(), original.characters(), original.intention(),
                    original.resistance(), original.entryStates(), original.outcome(), original.function(), original.milestoneRefs(),
                    original.location(), original.time(), new SceneCausality(null, null, null, null, List.of(changed)))));
            assertNotEquals(request.contract().planHash(), DeliveryReviewPolicy.contract(newPlan).planHash());
            assertTrue(DeliveryReviewPolicy.ensureBound(report, newPlan, request.manuscript(), true).unknown());
        }
    }

    @Test
    void allBlankTermsAndAbsentOrEmptyVerificationDoNotInventTargetsButInvalidObligationsCannotPass() {
        var item = verificationPlan(null, false);
        var scene = item.getScenes().get(0);
        item.setScenes(List.of(new ScenePlan(scene.id(), scene.characters(), scene.intention(), scene.resistance(), null,
                scene.outcome(), scene.function(), null, null, null,
                new SceneCausality(null, null, new SceneCausality.Terms(null, " ", "", null, null, null, null), null, List.of()))));
        assertEquals(List.of("scene:exam"), new ArrayList<>(DeliveryReviewPolicy.contract(item).targets().keySet()));
        var incomplete = new SceneCausality.VerificationObligation("执事", "外人", "药材成色", null, "决定放行", null);
        item.setScenes(List.of(new ScenePlan(scene.id(), scene.characters(), scene.intention(), scene.resistance(), null,
                scene.outcome(), scene.function(), null, null, null,
                new SceneCausality(null, null, null, null, java.util.Arrays.asList(null, incomplete)))));
        var contract = DeliveryReviewPolicy.contract(item);
        assertEquals(List.of("scene:exam", "scene:exam/verification:1", "scene:exam/verification:2"),
                new ArrayList<>(contract.targets().keySet()));
        assertTrue(contract.diagnostics().stream().anyMatch(value -> value.contains("verificationObligations[1].subject")));
        assertTrue(contract.diagnostics().stream().anyMatch(value -> value.contains("verificationObligations[1].criterion")));
        assertTrue(DeliveryReviewPolicy.consume(request(item, "正文", true), List.of()).unknown());
    }

    @Test
    void legacyTextStillReceivesOriginalVerificationAndAlternativeBoundaryReview() {
        var request = request(legacy("执事按与样品同等成色核验林岚药材，并按结果决定通行资格"), "对手被拆穿后林岚获准通行。", true);
        String prompt = DeliveryReviewPolicy.prompt(request);
        assertEquals(DeliveryReview.Scope.EVENTS, request.contract().scope());
        assertEquals(1, request.contract().targets().size());
        assertTrue(prompt.contains("即使旧计划只在 keyEvents、意图、结果或自由条款里写明核验"));
        assertTrue(prompt.contains("不能由正文临时发明"));
        var report = DeliveryReviewPolicy.consume(request, List.of(check("event:1", null, CausalVerdict.Status.ISSUE,
                request.manuscript().content(), "keyEvents[0]", request.contract().targets().get("event:1").fields().get("keyEvents[0]"),
                CausalVerdict.FailureType.RESULT_WITHOUT_CAUSE)));
        assertTrue(report.blocksCommit());
    }

    private static ChapterPlanItemEntity verificationPlan(String alternative, boolean terms) {
        var item = new ChapterPlanItemEntity();
        item.setChapterNo(1);
        item.setSchemaVersion(2);
        var obligation = new SceneCausality.VerificationObligation("执事", "林岚", "药材成色", "与样品同等成色",
                "按结果决定是否给通行凭条", alternative);
        var exchange = terms ? new SceneCausality.Terms("执事", "林岚", "通行凭条", "无偿",
                "执事核验林岚药材", "当场", "未通过则暂不发放") : null;
        item.setScenes(List.of(new ScenePlan("exam", List.of("执事", "林岚", "对手"), "验药取得通行资格", "成色待查", null,
                "按核验结果决定通行资格", "核验", null, null, null,
                new SceneCausality(null, null, exchange, null, List.of(obligation)))));
        return item;
    }

    private static CausalVerdict examScenePass(DeliveryReviewPolicy.Request request) {
        return check("scene:exam", "exam", CausalVerdict.Status.PASS, request.manuscript().content(),
                "outcome", "按核验结果决定通行资格", null);
    }

    private static CausalVerdict verificationCheck(DeliveryReviewPolicy.Request request, CausalVerdict.Status status,
                                                 CausalVerdict.FailureType failureType) {
        return check("scene:exam/verification:1", "exam", status, request.manuscript().content(),
                "causality.verificationObligations[0].criterion", "与样品同等成色", failureType);
    }

    private static ChapterPlanItemEntity legacy(String... events) {
        var item = new ChapterPlanItemEntity();
        item.setChapterNo(1);
        item.setKeyEvents(List.of(events));
        return item;
    }

    private static ChapterPlanItemEntity scenePlan() {
        var item = new ChapterPlanItemEntity();
        item.setChapterNo(1);
        item.setSchemaVersion(2);
        var step = new SceneCausality.CausalStep("药师", "核对凭条印鉴", "林岚", "等到签收", "确认来处", null);
        item.setScenes(List.of(new ScenePlan("counter", List.of("药师", "林岚"), "交付凭条", "需核验印鉴",
                List.of(), "拿到回执", "交接", List.of(), "柜台", "午后",
                new SceneCausality(null, List.of(step), null, null))));
        return item;
    }

    private static DeliveryReviewPolicy.Request request(ChapterPlanItemEntity item, String content, boolean enforced) {
        return new DeliveryReviewPolicy.Request(DeliveryReviewPolicy.contract(item),
                new ChapterManuscript(1, "柜台", content), enforced);
    }

    private static CausalVerdict check(String id, String sceneId, CausalVerdict.Status status,
                                        String evidence, String field, String planEvidence,
                                        CausalVerdict.FailureType failureType) {
        return new CausalVerdict(sceneId, status, evidence, null,
                status == CausalVerdict.Status.ISSUE ? "核对全文，仅见结果宣告或未见该义务兑现" : "正文交代了该义务要求的行为与落点",
                status == CausalVerdict.Status.ISSUE ? "按计划只补齐交接所必需的过程" : null,
                failureType, null, null, null, null, id, field, planEvidence);
    }
}
