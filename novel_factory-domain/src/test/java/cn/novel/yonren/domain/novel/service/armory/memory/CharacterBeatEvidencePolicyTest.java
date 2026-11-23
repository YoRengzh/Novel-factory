package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity.*;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.valobj.character.CharacterDecisionProfile;
import cn.novel.yonren.domain.novel.model.valobj.character.CharacterDepthSettings;
import cn.novel.yonren.domain.novel.model.valobj.character.CharacterEffect;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import cn.novel.yonren.domain.novel.service.armory.character.CharacterDecisionProfileService;
import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CharacterBeatEvidencePolicyTest {
    private static CharacterBeat beat(String name, String decision, String consequence) {
        return new CharacterBeat(name, null, decision, consequence, null, null, "旧共享引文");
    }

    private static BeatFieldSupport observable(String quote) {
        return new BeatFieldSupport(BeatAttribution.OBSERVABLE, quote, null, null);
    }

    private static ChapterSummaryEntity summary(ChapterManuscript manuscript, CharacterBeat... beats) {
        return ChapterSummaryEntity.builder().chapterNo(manuscript.chapterNo()).title(manuscript.title())
                .manuscriptHash(manuscript.hash()).characterBeats(List.of(beats)).build();
    }

    private static void prepareAndBind(ChapterSummaryEntity summary, ChapterManuscript manuscript) {
        CharacterBeatEvidencePolicy.sanitizeNewObservations(summary, manuscript);
        CharacterBeatEvidencePolicy.bind(summary, manuscript, List.of());
    }

    @Test
    void crushingJadeSlipDoesNotProveNamedRecipientReceivedMessage() {
        String text = "苏青抬手捏碎玉简。";
        var manuscript = new ChapterManuscript(1, "传讯", text);
        var beat = beat("苏青", "捏碎玉简", "赵衡已收到消息");
        beat.setSupport(Map.of(BeatField.decision, observable(text), BeatField.consequence, observable(text)));
        var summary = summary(manuscript, beat);
        prepareAndBind(summary, manuscript);
        assertEquals("捏碎玉简", beat.getDecision());
        assertNull(beat.getConsequence());
        assertTrue(beat.getPendingFields().containsKey(BeatField.consequence));
        assertEquals(0, beat.getSupport().get(BeatField.decision).quoteStart());
        String fields = CharacterBeatEvidencePolicy.renderFields(summary, beat, List.of());
        assertTrue(fields.contains("捏碎玉简"));
        assertFalse(fields.contains("赵衡已收到消息"));
        assertTrue(CharacterStateProjector.project(List.of(summary), null).facts().isEmpty());
    }

    @Test
    void missingSupportInNewBeatNeverFallsBackToLegacyEvidence() {
        var manuscript = new ChapterManuscript(1, "动作", "苏青捏碎玉简。");
        var beat = beat("苏青", "捏碎玉简", "消息已送达");
        var summary = summary(manuscript, beat);
        prepareAndBind(summary, manuscript);
        assertEquals(1, beat.getSchemaVersion());
        assertNull(beat.getDecision());
        assertNull(beat.getConsequence());
        assertEquals(2, beat.getPendingFields().size());
        assertTrue(CharacterBeatEvidencePolicy.observations(summary, beat, List.of()).isEmpty());
    }

    @Test
    void contextualIntentRemainsPossibleAndDoesNotBecomeHardState() {
        String text = "苏青收起地图，朝北门望了一眼。";
        var manuscript = new ChapterManuscript(2, "北门", text);
        var beat = beat("苏青", "收起地图", null);
        beat.setNextIntent("可能准备从北门出城");
        beat.setSupport(Map.of(BeatField.decision, observable(text), BeatField.nextIntent,
                new BeatFieldSupport(BeatAttribution.INTERPRETATION, "朝北门望了一眼", text, null)));
        var summary = summary(manuscript, beat);
        prepareAndBind(summary, manuscript);
        String fields = CharacterBeatEvidencePolicy.renderFields(summary, beat, List.of());
        assertTrue(fields.contains("可能（上下文解释，未证实）"));
        assertTrue(fields.contains("可能准备从北门出城"));
        assertNull(summary.getCharacterEffects());
        assertTrue(CharacterStateProjector.project(List.of(summary), null).facts().isEmpty());
    }

    @Test
    void reliableDirectStatementCanSummarizeIntentButDoesNotProveFulfillment() {
        String text = "苏青说：“我明早去北门，替你送信。”";
        var manuscript = new ChapterManuscript(3, "约定", text);
        var beat = beat("苏青", null, "信已送达赵衡");
        beat.setNextIntent("次日从北门出发替对方送信");
        var support = new BeatFieldSupport(BeatAttribution.DIRECT_STATEMENT, text, text, null);
        beat.setSupport(Map.of(BeatField.nextIntent, support, BeatField.consequence, support));
        var summary = summary(manuscript, beat);
        prepareAndBind(summary, manuscript);
        assertNotNull(beat.getNextIntent());
        assertNull(beat.getConsequence());
        assertTrue(CharacterBeatEvidencePolicy.renderFields(summary, beat, List.of()).contains("直接发言内容·不证实兑现或真伪"));
    }

    @Test
    void lexicalSubstringCannotStripPossibilityFromConsequence() {
        String text = "也许赵衡已收到消息。";
        var manuscript = new ChapterManuscript(4, "可能", text);
        var beat = beat("苏青", null, "赵衡已收到消息");
        beat.setSupport(Map.of(BeatField.consequence, observable(text)));
        var summary = summary(manuscript, beat);
        prepareAndBind(summary, manuscript);
        assertNull(beat.getConsequence());
        assertNotNull(beat.getPendingFields().get(BeatField.consequence));
    }

    @Test
    void actualReceiveHasItsOwnPositiveConsequenceQuote() {
        String action = "苏青捏碎玉简。";
        String receive = "赵衡收到了苏青传来的消息。";
        var manuscript = new ChapterManuscript(4, "收到", action + receive);
        var beat = beat("苏青", "捏碎玉简", "赵衡收到了苏青传来的消息");
        beat.setSupport(Map.of(BeatField.decision, observable(action), BeatField.consequence, observable(receive)));
        var summary = summary(manuscript, beat);
        prepareAndBind(summary, manuscript);
        assertNotNull(beat.getConsequence());
        assertEquals(action.length(), beat.getSupport().get(BeatField.consequence).quoteStart());
        assertEquals(2, CharacterBeatEvidencePolicy.observations(summary, beat, manuscript, List.of()).size());
    }

    @Test
    void otherKnownPersonsQuoteCannotProveThisPersonsChoice() {
        String text = "赵衡说：“我明早去北门。”";
        var manuscript = new ChapterManuscript(5, "主体", text);
        var su = beat("苏青", "决定明早去北门", null);
        su.setSupport(Map.of(BeatField.decision, new BeatFieldSupport(BeatAttribution.DIRECT_STATEMENT, text, text, null)));
        var zhao = beat("赵衡", null, null);
        var summary = summary(manuscript, su, zhao);
        prepareAndBind(summary, manuscript);
        assertNull(su.getDecision());
        assertEquals("EXPLICIT_OTHER_SUBJECT", su.getPendingFields().get(BeatField.decision).reason());
    }

    @Test
    void pronounActionNeedsNoPsychologicalPredicateOrRepeatedName() {
        String text = "苏青站在门边。他把旧信塞进袖口。";
        var manuscript = new ChapterManuscript(6, "旧信", text);
        var beat = beat("苏青", "把旧信塞进袖口", null);
        beat.setSupport(Map.of(BeatField.decision, new BeatFieldSupport(BeatAttribution.OBSERVABLE,
                "他把旧信塞进袖口。", text, null)));
        var summary = summary(manuscript, beat);
        prepareAndBind(summary, manuscript);
        assertNotNull(beat.getDecision());
        assertNull(beat.getGoal());
        assertNull(beat.getNextIntent());
    }

    @Test
    void refsMustBeConfirmedAndMatchOriginalChapterVersion() {
        String text = "赵衡知道北门封闭。";
        var manuscript = new ChapterManuscript(7, "确认", text);
        var effect = new CharacterEffect("赵衡", CharacterEffect.Kind.KNOWLEDGE, "北门", CharacterEffect.State.ESTABLISHED,
                "北门封闭", text, "读到告示", null);
        var summary = summary(manuscript);
        summary.setCharacterEffects(List.of(effect));
        CharacterStateProjector.bind(summary, manuscript, List.of());
        assertEquals(1, summary.getCharacterEffects().size());
        var bound = summary.getCharacterEffects().get(0);
        var refs = List.of(new BeatEffectRef(bound.effectId(), 7, manuscript.hash()));
        var beat = beat("苏青", null, "北门封闭");
        beat.setSupport(Map.of(BeatField.consequence, new BeatFieldSupport(BeatAttribution.OBSERVABLE, null, null, refs)));
        summary.setCharacterBeats(List.of(beat));
        prepareAndBind(summary, manuscript);
        assertNotNull(beat.getConsequence());

        var wrong = beat("苏青", null, "北门封闭");
        wrong.setSupport(Map.of(BeatField.consequence, new BeatFieldSupport(BeatAttribution.OBSERVABLE, null, null,
                List.of(new BeatEffectRef(bound.effectId(), 7, "0".repeat(64))))));
        summary.setCharacterBeats(List.of(wrong));
        prepareAndBind(summary, manuscript);
        assertNull(wrong.getConsequence());
        assertEquals("EFFECT_REF_NOT_CONFIRMED_OR_SOURCE_MISMATCH", wrong.getPendingFields().get(BeatField.consequence).reason());

        var pending = beat("苏青", null, "北门封闭");
        pending.setSupport(Map.of(BeatField.consequence, new BeatFieldSupport(BeatAttribution.OBSERVABLE, null, null, refs)));
        summary.setCharacterBeats(List.of(pending));
        summary.setPendingCharacterEffects(List.of(effect));
        prepareAndBind(summary, manuscript);
        assertNull(pending.getConsequence());
    }

    @Test
    void refCannotExpandCrushingActionIntoReceiptOrBorrowAnotherPersonsIntent() {
        String text = "苏青完成捏碎玉简。";
        var manuscript = new ChapterManuscript(8, "ref", text);
        var summary = summary(manuscript);
        summary.setCharacterEffects(List.of(new CharacterEffect("苏青", CharacterEffect.Kind.ACTION, "玉简", CharacterEffect.State.COMPLETED,
                "捏碎玉简", text, null, null)));
        CharacterStateProjector.bind(summary, manuscript, List.of());
        var effect = summary.getCharacterEffects().get(0);
        var refs = List.of(new BeatEffectRef(effect.effectId(), 8, manuscript.hash()));
        var beat = beat("赵衡", "捏碎玉简", "已收到消息");
        beat.setSupport(Map.of(BeatField.decision, new BeatFieldSupport(BeatAttribution.OBSERVABLE, null, null, refs),
                BeatField.consequence, new BeatFieldSupport(BeatAttribution.OBSERVABLE, null, null, refs)));
        summary.setCharacterBeats(List.of(beat));
        prepareAndBind(summary, manuscript);
        assertNull(beat.getDecision());
        assertNull(beat.getConsequence());
    }

    @Test
    void rebindPreservesPendingAndIgnoresModelVerificationAndOffsets() {
        String text = "苏青捏碎玉简。";
        var manuscript = new ChapterManuscript(9, "重绑", text);
        var beat = beat("苏青", "捏碎玉简", "已送达");
        beat.setSupport(Map.of(BeatField.decision, new BeatFieldSupport(BeatAttribution.OBSERVABLE,
                text, null, null, 900, 901, "CONFIRMED")));
        var summary = summary(manuscript, beat);
        prepareAndBind(summary, manuscript);
        var pending = beat.getPendingFields().get(BeatField.consequence);
        String first = JSON.toJSONString(summary);
        CharacterBeatEvidencePolicy.bind(summary, manuscript, List.of());
        assertEquals(first, JSON.toJSONString(summary));
        assertSame(pending, beat.getPendingFields().get(BeatField.consequence));
        assertEquals(CharacterBeatEvidencePolicy.LOCATED, beat.getSupport().get(BeatField.decision).verification());
        assertEquals(0, beat.getSupport().get(BeatField.decision).quoteStart());
    }

    @Test
    void legacyGoldenAndExplicitNullRemainReadableAndUnmodified() throws Exception {
        String golden = "{\"name\":\"苏青\",\"goal\":\"查旧账\",\"decision\":\"捏碎玉简\",\"consequence\":\"已收到消息\",\"relationshipChange\":null,\"nextIntent\":null,\"evidence\":\"苏青捏碎玉简。\"}";
        ObjectMapper mapper = new ObjectMapper();
        var beat = mapper.readValue(golden, CharacterBeat.class);
        assertNull(beat.getSchemaVersion());
        assertNull(beat.getSupport());
        assertEquals(mapper.readTree(golden), mapper.readTree(mapper.writeValueAsString(beat)));
        assertFalse(JSON.toJSONString(beat).contains("schemaVersion"));
        assertFalse(JSON.toJSONString(beat).contains("support"));
        String before = ChapterManuscript.digest(JSON.toJSONString(beat));
        var manuscript = new ChapterManuscript(10, "旧书", "苏青捏碎玉简。");
        var summary = summary(manuscript, beat);
        CharacterBeatEvidencePolicy.bind(summary, manuscript, List.of());
        assertEquals(before, ChapterManuscript.digest(JSON.toJSONString(beat)));
        assertTrue(CharacterBeatEvidencePolicy.renderFields(summary, beat, List.of()).contains("未逐项复核"));
        var nullSupport = mapper.readValue(golden.substring(0, golden.length() - 1) + ",\"support\":null,\"schemaVersion\":null}", CharacterBeat.class);
        assertEquals(beat, nullSupport);
        assertEquals(beat, new CharacterBeat("苏青", "查旧账", "捏碎玉简", "已收到消息", null, null, "苏青捏碎玉简。"));
    }

    @Test
    void downstreamMemoryAndDepthUsePerFieldPolicyAndRejectSharedQuotePollution() {
        String text = "苏青捏碎玉简。";
        var manuscript = new ChapterManuscript(11, "观察", text);
        var beat = beat("苏青", "捏碎玉简", "赵衡已收到消息");
        beat.setSupport(Map.of(BeatField.decision, observable(text), BeatField.consequence, observable(text)));
        var summary = summary(manuscript, beat);
        prepareAndBind(summary, manuscript);
        String memory = new ChapterMemoryService(new ForeshadowPriorityService()).buildMemoryPrefix(List.of(summary), null, null);
        assertTrue(memory.contains("捏碎玉简"));
        assertFalse(memory.contains("赵衡已收到消息"));
        var story = StoryContextEntity.builder().characterDepthSettings(CharacterDepthSettings.defaults("APPLY"))
                .characterDecisionProfiles(CharacterDecisionProfileService.adoptAuthor(null, List.of(
                        new CharacterDecisionProfile("苏青", false, "查旧账", null, null, null, null, null, null)), 1)).build();
        var review = CharacterDepthFeedbackProjector.observe(story, new ChapterPlanItemEntity(), manuscript, summary, List.of());
        assertEquals(1, review.findings().size());
        assertTrue(review.findings().get(0).dimension().endsWith("decision"));
        assertFalse(review.findings().get(0).observation().contains("赵衡已收到消息"));
        summary.setCharacterDepthReview(review);
        assertFalse(CharacterDepthFeedbackProjector.renderFeedback(story, List.of(summary)).contains("赵衡已收到消息"));
        assertTrue(CharacterDepthFeedbackProjector.renderFeedback(story, List.of(summary)).contains("捏碎玉简"));
        // 污染落盘字段，即使旧verification还在，下游也不得复用先前点评。
        beat.setDecision("赵衡已收到消息");
        assertEquals("", CharacterDepthFeedbackProjector.renderFeedback(story, List.of(summary)));
    }

    @Test
    void detachedModelMarkerAndWrongManuscriptNeverAuthorizeObservations() {
        var manuscript = new ChapterManuscript(12, "来源", "苏青捏碎玉简。");
        var beat = beat("苏青", "捏碎玉简", null);
        beat.setSupport(Map.of(BeatField.decision, new BeatFieldSupport(BeatAttribution.OBSERVABLE,
                "不存在的捏碎玉简", null, null, 0, 8, CharacterBeatEvidencePolicy.LOCATED)));
        var summary = summary(manuscript, beat);
        prepareAndBind(summary, manuscript);
        assertNull(beat.getDecision());
        assertTrue(CharacterBeatEvidencePolicy.observations(summary, beat, manuscript, List.of()).isEmpty());
    }
}
