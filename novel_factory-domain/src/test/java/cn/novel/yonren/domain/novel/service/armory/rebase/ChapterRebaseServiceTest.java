package cn.novel.yonren.domain.novel.service.armory.rebase;

import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality;
import cn.novel.yonren.domain.novel.model.valobj.plan.ScenePlan;
import cn.novel.yonren.domain.novel.model.valobj.rebase.RebaseRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChapterRebaseServiceTest {

    private static ScenePlan scene(String id, String intention, String outcome, String... cast) {
        return new ScenePlan(id, List.of(cast), intention, "阻力", List.of(), outcome,
                "推进", List.of(), "报名廊", null);
    }

    private static ChapterPlanItemEntity item() {
        return ChapterPlanItemEntity.builder().chapterNo(3).title("资格牌前").schemaVersion(2)
                .goal("取得大考资格").endingHook("拿到资格牌")
                .mainLineAdvance("【关系】宿敌当众刁难，主角取得资格并结怨")
                .characters(List.of("苏尘", "林昭", "许衡"))
                .scenes(List.of(scene("s1", "去领资格", "林昭拦住", "苏尘", "林昭", "许衡"),
                        scene("s2", "接受考校", "诊断对手", "苏尘", "林昭", "许衡")))
                .build();
    }

    private static StoryProperties props(boolean enabled) {
        StoryProperties properties = new StoryProperties();
        properties.getRebase().setMode(enabled ? StoryProperties.RebaseProperties.Mode.PROPOSE_ONLY
                : StoryProperties.RebaseProperties.Mode.OFF);
        return properties;
    }

    private static StoryVO story() {
        StoryVO vo = new StoryVO();
        vo.setModule(new StoryVO.Module());
        return vo;
    }

    private static String patch(String sceneId, String intention, String outcome) {
        return "{\"status\":\"PROPOSED\",\"diagnosis\":[{\"problem\":\"原计划只验对手\",\"sourceRef\":\"S2\","
                + "\"sourceQuote\":\"接受考校\"}],\"patches\":[{\"sceneId\":\"" + sceneId + "\","
                + "\"intention\":\"" + intention + "\",\"resistance\":\"阻力\",\"outcome\":\"" + outcome + "\","
                + "\"function\":\"推进\",\"causality\":{},\"reason\":\"主角须本人受验\"}],"
                + "\"remainingRisks\":[\"新任务需核对\"]}";
    }

    @Test
    void disabledByDefaultIssuesNoModelCall() {
        List<String> calls = new ArrayList<>();
        var service = new ChapterRebaseService((module, call) -> {
            calls.add(call.getUserPrompt());
            return patch("s2", "本人施法", "本人及格");
        }, props(false));

        assertFalse(service.enabled());
        assertNull(service.propose(story(), item(), List.of(), StoryContextEntity.builder().build(), "上章结尾"));
        assertTrue(calls.isEmpty());
    }

    @Test
    void validPatchProducesProposalWithoutChangingOriginal() {
        var service = new ChapterRebaseService((module, call) -> patch("s2", "本人施法", "本人及格"), props(true));
        var original = item();
        RebaseRecord record = service.propose(story(), original, List.of(),
                StoryContextEntity.builder().theme("玄幻").build(), "上章结尾");

        assertNotNull(record);
        assertEquals("PROPOSED", record.status());
        assertFalse(record.adopted(), "本阶段候选绝不自动采纳");
        assertEquals("接受考校", original.getScenes().get(1).intention(), "原计划不得被就地改写");
        assertTrue(record.changes().stream().anyMatch(c -> c.field().equals("intention")));
        assertNotNull(record.candidatePlan());
    }

    @Test
    void unchangedKeepsOriginalAndReportsNoChange() {
        var service = new ChapterRebaseService((module, call) ->
                "{\"status\":\"UNCHANGED\",\"diagnosis\":[],\"patches\":[],\"remainingRisks\":[]}", props(true));
        RebaseRecord record = service.propose(story(), item(), List.of(), null, null);

        assertEquals("UNCHANGED", record.status());
        assertTrue(record.changes().isEmpty());
        assertFalse(record.adopted());
    }

    @Test
    void blockedIsRecordedWithoutCandidate() {
        var service = new ChapterRebaseService((module, call) ->
                "{\"status\":\"BLOCKED\",\"diagnosis\":[],\"patches\":[],\"remainingRisks\":[\"主线与事实不可兼容\"]}",
                props(true));
        RebaseRecord record = service.propose(story(), item(), List.of(), null, null);

        assertEquals("BLOCKED", record.status());
        assertNull(record.candidatePlan());
        assertTrue(record.diagnostics().contains("主线与事实不可兼容"));
    }

    @Test
    void unknownSceneOrNewCharacterIsInvalid() {
        var unknown = new ChapterRebaseService((module, call) -> patch("nope", "改", "改"), props(true));
        assertEquals("INVALID", unknown.propose(story(), item(), List.of(), null, null).status());

        var newCast = new ChapterRebaseService((module, call) ->
                "{\"status\":\"PROPOSED\",\"patches\":[{\"sceneId\":\"s2\",\"intention\":\"本人施法\","
                        + "\"resistance\":\"阻力\",\"outcome\":\"本人及格\",\"function\":\"推进\","
                        + "\"causality\":{\"actors\":[{\"character\":\"陌生人\",\"want\":\"介入\"}]}}]}", props(true));
        RebaseRecord record = newCast.propose(story(), item(), List.of(), null, null);
        assertEquals("INVALID", record.status());
        assertTrue(record.diagnostics().stream().anyMatch(d -> d.contains("REBASE_NEW_CHARACTER")));
    }

    @Test
    void incompletePatchOrAnchorChangeIsRejected() {
        var incomplete = new ChapterRebaseService((module, call) ->
                "{\"status\":\"PROPOSED\",\"patches\":[{\"sceneId\":\"s2\",\"intention\":\"只给意图\"}]}", props(true));
        assertEquals("INVALID", incomplete.propose(story(), item(), List.of(), null, null).status());
    }

    @Test
    void malformedResponseNeverBecomesProposal() {
        var service = new ChapterRebaseService((module, call) -> "not json at all", props(true));
        RebaseRecord record = service.propose(story(), item(), List.of(), null, null);
        assertEquals("INVALID", record.status());
        assertNull(record.candidatePlan());
    }

    @Test
    void modelFailureKeepsOriginalAndIsRecorded() {
        var service = new ChapterRebaseService((module, call) -> {
            throw new IllegalStateException("timeout");
        }, props(true));
        RebaseRecord record = service.propose(story(), item(), List.of(), null, null);
        assertEquals("INVALID", record.status());
        assertTrue(record.diagnostics().stream().anyMatch(d -> d.contains("REBASE_UNAVAILABLE")));
    }

    @Test
    void incompleteVerificationObligationIsRejected() {
        var service = new ChapterRebaseService((module, call) ->
                "{\"status\":\"PROPOSED\",\"patches\":[{\"sceneId\":\"s2\",\"intention\":\"本人施法\","
                        + "\"resistance\":\"阻力\",\"outcome\":\"本人及格\",\"function\":\"推进\","
                        + "\"causality\":{\"verificationObligations\":[{\"verifier\":\"许衡\",\"subject\":\"苏尘\","
                        + "\"item\":\"基础法术\"}]}}]}", props(true));
        RebaseRecord record = service.propose(story(), item(), List.of(), null, null);
        assertEquals("INVALID", record.status());
        assertTrue(record.diagnostics().stream().anyMatch(d -> d.contains("REBASE_INCOMPLETE_OBLIGATION")));
    }

    @Test
    void causalityOnlyPatchPreservesOriginalSceneCommitment() {
        var service = new ChapterRebaseService((module, call) ->
                "{\"status\":\"PROPOSED\",\"patches\":[{\"sceneId\":\"s2\",\"causality\":{\"actors\":[]}}]}",
                props(true));
        var original = item();
        RebaseRecord record = service.propose(story(), original, List.of(), null, null);

        assertEquals("PROPOSED", record.status());
        // 只给 causality 的补丁不得替换场景承诺：意图与落点都沿用原计划。
        assertTrue(record.changes().stream().noneMatch(c -> c.field().equals("intention")
                || c.field().equals("outcome") || c.field().equals("function")));
        var candidate = (Map<String, Object>) ((List<Map<String, Object>>) record.candidatePlan().get("scenes")).get(1);
        assertEquals(original.getScenes().get(1).intention(), candidate.get("intention"));
        assertEquals(original.getScenes().get(1).outcome(), candidate.get("outcome"));
        assertTrue(record.changes().stream().anyMatch(c -> c.field().equals("causality")));
    }

    @Test
    void fabricatedDiagnosisReferenceIsRejected() {
        var service = new ChapterRebaseService((module, call) ->
                "{\"status\":\"PROPOSED\",\"diagnosis\":[{\"problem\":\"伪造依据\",\"sourceRef\":\"P999\"}],"
                        + "\"patches\":[{\"sceneId\":\"s2\",\"intention\":\"本人施法\",\"resistance\":\"阻力\","
                        + "\"outcome\":\"本人及格\",\"function\":\"推进\",\"causality\":{}}]}", props(true));
        RebaseRecord record = service.propose(story(), item(), List.of(), null, null);

        assertEquals("INVALID", record.status());
        assertTrue(record.diagnostics().contains("REBASE_UNKNOWN_REFERENCE: P999"));
    }

    @Test
    void opponentVerificationCannotProveProtagonistPassed() {
        var service = new ChapterRebaseService((module, call) ->
                "{\"status\":\"PROPOSED\",\"patches\":[{\"sceneId\":\"s2\",\"causality\":{"
                        + "\"verificationObligations\":[{\"verifier\":\"许衡\",\"subject\":\"林昭\","
                        + "\"item\":\"青阳诀运转\",\"criterion\":\"第三息无气逆\",\"resultUse\":\"苏尘通过考校\"}]}}]}",
                props(true));
        RebaseRecord record = service.propose(story(), item(), List.of(), null, null);

        assertEquals("INVALID", record.status());
        assertTrue(record.diagnostics().contains("REBASE_CROSS_SUBJECT_VERDICT: s2 / 林昭"));
    }

    @Test
    void legacyPlanIsSkipped() {
        var legacy = ChapterPlanItemEntity.builder().chapterNo(1).title("旧").keyEvents(List.of("事件")).build();
        var service = new ChapterRebaseService((module, call) -> patch("s2", "改", "改"), props(true));
        assertNull(service.propose(story(), legacy, List.of(), null, null));
    }
}
