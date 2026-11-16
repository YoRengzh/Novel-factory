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
