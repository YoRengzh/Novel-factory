package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.valobj.character.CharacterEffect;
import cn.novel.yonren.domain.novel.model.valobj.character.CharacterEffect.Attribution;
import cn.novel.yonren.domain.novel.model.valobj.character.CharacterEffect.EvidenceSupport;
import cn.novel.yonren.domain.novel.model.valobj.character.CharacterEffect.Kind;
import cn.novel.yonren.domain.novel.model.valobj.character.CharacterEffect.State;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CharacterStateProjectorTest {
    private CharacterEffect fact(String name, Kind kind, String key, State state, String value, String evidence, String ref) {
        return new CharacterEffect(name, kind, key, state, value, evidence, "亲历场景中明确获知", ref);
    }

    private ChapterSummaryEntity summary(int no, String content, List<CharacterEffect> effects, List<ChapterSummaryEntity> history) {
        var manuscript = new ChapterManuscript(no, "测试章" + no, content);
        var summary = ChapterSummaryEntity.builder().chapterNo(no).title(manuscript.title())
                .summary("合成状态").manuscriptHash(manuscript.hash()).characterEffects(effects).build();
        CharacterStateProjector.bind(summary, manuscript, history);
        return summary;
    }

    private static CharacterEffect explicit(String character, Kind kind, String key, State state, String value,
                                            String evidence, String reference) {
        return new CharacterEffect(character, kind, key, state, value, evidence, "正文明确描写", reference,
                new EvidenceSupport(1, Attribution.EXPLICIT_NARRATION, character, evidence,
                        null, null, null, null, null));
    }

    private static ChapterSummaryEntity bindNew(int no, String text, List<CharacterEffect> effects,
                                               List<ChapterSummaryEntity> history) {
        var manuscript = new ChapterManuscript(no, "合成章", text);
        var summary = ChapterSummaryEntity.builder().chapterNo(no).title(manuscript.title())
                .manuscriptHash(manuscript.hash()).characterEffects(effects).build();
        CharacterStateProjector.bind(summary, manuscript, history, true);
        return summary;
    }

    @Test
    void independentKnowledgePersistsBeyondThreeChaptersAndFutureIsExcluded() {
        String hiding = "苏青看见林远把终端放进地砖暗格。";
        var first = summary(1, hiding, List.of(fact("苏青", Kind.KNOWLEDGE, "终端藏处", State.ESTABLISHED,
                "终端在地砖暗格", hiding, null)), List.of());
        String directory = "苏青看过屏幕，认出供水目录。";
        var second = summary(2, directory, List.of(fact("苏青", Kind.KNOWLEDGE, "目录类别", State.ESTABLISHED,
                "终端有供水目录", directory, null)), List.of(first));
        var future = summary(9, "苏青读到坐标。", List.of(fact("苏青", Kind.KNOWLEDGE, "井位坐标", State.ESTABLISHED,
                "坐标已知", "苏青读到坐标。", null)), List.of(first, second));
        var projected = CharacterStateProjector.project(List.of(future, second, first), 8);
        assertEquals(2, projected.facts().size());
        String prompt = CharacterStateProjector.render(List.of(first, second, future), 8, List.of("苏青"));
        assertTrue(prompt.contains("终端藏处"));
        assertTrue(prompt.contains("目录类别"));
        assertFalse(prompt.contains("井位坐标"));
        assertEquals(projected.hash(), CharacterStateProjector.project(List.of(first, second), 8).hash());
    }

    @Test
    void requestsAndIntentionsCannotFulfillUnacceptedCommitments() {
        String text = "林远要求苏青去找手册，苏青没有答应。";
        var request = summary(1, text, List.of(fact("苏青", Kind.COMMITMENT, "寻找手册", State.REQUESTED,
                "寻找手册", text, null)), List.of());
        var ref = request.getCharacterEffects().get(0).factId();
        var fake = summary(2, "苏青找回手册。", List.of(fact("苏青", Kind.COMMITMENT, "寻找手册", State.COMPLETED,
                "寻找手册", "苏青找回手册。", ref)), List.of(request));
        assertTrue(fake.getCharacterEffects().isEmpty());
        assertEquals(1, fake.getPendingCharacterEffects().size());
        var accepted = summary(2, "苏青答应寻找手册。", List.of(fact("苏青", Kind.COMMITMENT, "寻找手册", State.ACCEPTED,
                "寻找手册", "苏青答应寻找手册。", ref)), List.of(request));
        var fulfilled = summary(3, "苏青交出找到的手册。", List.of(fact("苏青", Kind.COMMITMENT, "寻找手册", State.COMPLETED,
                "寻找手册", "苏青交出找到的手册。", ref)), List.of(request, accepted));
        assertEquals(State.COMPLETED, CharacterStateProjector.project(List.of(request, accepted, fulfilled), 4).facts().get(ref).state());
        assertFalse(CharacterStateProjector.project(List.of(request), 2).knowsReference("苏青", ref));
    }

    @Test
    void wrongBeliefsAreNotKnowledgeAndDoNotOverwriteAnotherCharactersKnowledge() {
        String source = "苏青以为井里仍有清水。林远知道水样已污染。";
        var s = summary(1, source, List.of(
                fact("苏青", Kind.BELIEF, "水样", State.SUSPECTED, "可能有清水", "苏青以为井里仍有清水。", null),
                fact("林远", Kind.KNOWLEDGE, "水样", State.ESTABLISHED, "水样已污染", "林远知道水样已污染。", null)), List.of());
        var snapshot = CharacterStateProjector.project(List.of(s), 2);
        assertEquals(2, snapshot.facts().size());
        var lin = s.getCharacterEffects().stream().filter(effect -> effect.character().equals("林远")).findFirst().orElseThrow();
        assertFalse(snapshot.knowsReference("苏青", lin.factId()));
        assertTrue(CharacterStateProjector.render(List.of(s), 2, List.of("苏青")).contains("BELIEF/SUSPECTED"));
    }

    @Test
    void missingEvidenceDuplicateKeysOrInventedHistoryCannotEnterAuthoritativeState() {
        String source = "苏青看见终端藏在地砖里。";
        var one = fact("苏青", Kind.KNOWLEDGE, "藏处", State.ESTABLISHED, "地砖里", source, null);
        var bad = fact("苏青", Kind.KNOWLEDGE, "能力", State.ESTABLISHED, "可以读数据", "苏青读出了数据。", null);
        var duplicate = summary(1, source, List.of(one, one, bad), List.of());
        assertTrue(duplicate.getCharacterEffects().isEmpty());
        assertEquals(3, duplicate.getPendingCharacterEffects().size());
        var noWitness = summary(1, "终端可以展开目录。", List.of(fact("苏青", Kind.KNOWLEDGE, "能力", State.ESTABLISHED,
                "可以展开目录", "终端可以展开目录。", null)), List.of());
        assertTrue(noWitness.getCharacterEffects().isEmpty());
    }

    @Test
    void hashAndEvidenceTamperingIsRejectedAtCommitAndOldDataStaysUnknown() {
        String source = "苏青看见终端藏在地砖里。";
        var s = summary(1, source, List.of(fact("苏青", Kind.KNOWLEDGE, "藏处", State.ESTABLISHED, "地砖里", source, null)), List.of());
        var manuscript = new ChapterManuscript(1, "测试章1", source);
        assertDoesNotThrow(() -> CharacterStateProjector.validateSummary(s, manuscript, List.of()));
        var changed = JSON.parseObject(JSON.toJSONString(s), ChapterSummaryEntity.class);
        changed.setManuscriptHash("wrong");
        assertTrue(CharacterStateProjector.project(List.of(changed), 2).facts().isEmpty());
        assertThrows(IllegalStateException.class, () -> CharacterStateProjector.validateSummary(changed, manuscript, List.of()));
        assertTrue(CharacterStateProjector.project(List.of(ChapterSummaryEntity.builder().chapterNo(1).build()), 2).facts().isEmpty());
    }

    @Test
    void unresolvedFactsAreDiagnosticsOnlyAndNeverBecomeWritingConstraints() {
        String first = "苏青看见终端藏在地砖里。";
        var good = summary(1, first, List.of(fact("苏青", Kind.KNOWLEDGE, "藏处", State.ESTABLISHED,
                "地砖里", first, null)), List.of());
        // 第二章自称“已完成承诺”，但没有先前的 ACCEPTED 事实：状态迁移非法 → 进 unresolved。
        var illegal = summary(2, "苏青交出找到的手册。", List.of(fact("苏青", Kind.COMMITMENT, "寻找手册",
                State.COMPLETED, "寻找手册", "苏青交出找到的手册。", "character-fact-missing")), List.of(good));
        var projection = CharacterStateProjector.projection(List.of(good, illegal), 3, List.of("苏青"), 12, 4000);

        assertEquals(1, projection.confirmedFacts().size(), "非法迁移不得进入确认事实");
        assertFalse(projection.unresolvedIds().isEmpty(), "非法迁移必须留作诊断");
        String rendered = CharacterStateProjector.render(List.of(good, illegal), 3, List.of("苏青"));
        assertTrue(rendered.contains("藏处"), rendered);
        assertTrue(rendered.contains("不得作为确定知识"), "诊断必须显式标注不可当确定知识：" + rendered);
        assertTrue(rendered.contains("UNRESOLVED"), rendered);
        assertFalse(rendered.contains("苏青交出找到的手册"), "未解决事实的旧值不得写进约束：" + rendered);

        // 未解决事实不得出现在可核对引用里，否则模型能拿它当已确认前提。
        var references = cn.novel.yonren.domain.novel.service.armory.plan.CharacterFactReferences.from(
                projection, 3);
        assertTrue(references.stream().noneMatch(ref -> projection.unresolvedIds().contains(ref.subject())));
    }

    @Test
    void projectedReferencesRespectSelectionAndRejectLegacyKnowledgeClaims() {
        List<CharacterEffect> effects = new java.util.ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 14; i++) {
            text.append("苏青看到线索").append(i).append("。");
            effects.add(fact("苏青", Kind.KNOWLEDGE, "线索" + i, State.ESTABLISHED,
                    "线索" + i, "苏青看到线索" + i + "。", null));
        }
        var source = summary(1, text.toString(), effects, List.of());
        var projection = CharacterStateProjector.projection(List.of(source), 2, List.of("苏青"), 3, 4000);
        var references = cn.novel.yonren.domain.novel.service.armory.plan.CharacterFactReferences.from(projection, 2);
        assertEquals(3, references.size());
        assertTrue(references.stream().allMatch(ref -> projection.selectedFactIds().contains(ref.subject())));

        var legacy = new cn.novel.yonren.domain.novel.model.valobj.plan.FactReference(
                "CHARACTER", 1, "old-hash", "苏青", "LEGACY/在医院", "苏青在医院醒来。");
        assertFalse(cn.novel.yonren.domain.novel.service.armory.plan.CharacterFactReferences.usableAsBasis(
                legacy, "KNOWLEDGE_REGRESSION"), "旧角色状态不证明人物知道什么");
        assertTrue(cn.novel.yonren.domain.novel.service.armory.plan.CharacterFactReferences.usableAsBasis(
                legacy, "CAPABILITY_CONFLICT"), "旧状态仍可用于能力或结果前提");
    }

    @Test
    void legacyReferencesKeepOnlyTheLatestStatePerEntity() {
        var old = ChapterSummaryEntity.builder().chapterNo(1).manuscriptHash("old-hash")
                .characterStates(List.of(new ChapterSummaryEntity.StateEntry("苏青", "留在旧馆", "苏青留在旧馆。")))
                .itemStates(List.of(new ChapterSummaryEntity.StateEntry("终端", "尚未破解", "终端尚未破解。"))).build();
        var latest = ChapterSummaryEntity.builder().chapterNo(2).manuscriptHash("new-hash")
                .characterStates(List.of(new ChapterSummaryEntity.StateEntry("苏青", "已经离馆", "苏青已经离馆。")))
                .itemStates(List.of(new ChapterSummaryEntity.StateEntry("终端", "已经破解", "终端已经破解。"))).build();

        var references = cn.novel.yonren.domain.novel.service.armory.plan.CharacterFactReferences.from(
                List.of(old, latest), 3);
        assertEquals(2, references.size());
        assertTrue(references.stream().allMatch(ref -> ref.chapterNo() == 2));
        assertTrue(references.stream().noneMatch(ref -> ref.evidence().contains("旧馆")
                || ref.evidence().contains("尚未破解")));
    }

    @Test
    void projectionIsBoundedAndRecordsOmittedFacts() {
        List<CharacterEffect> effects = new java.util.ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            text.append("苏青看到线索").append(i).append("。");
            effects.add(fact("苏青", Kind.KNOWLEDGE, "线索" + i, State.ESTABLISHED, "线索" + i,
                    "苏青看到线索" + i + "。", null));
        }
        var all = summary(1, text.toString(), effects, List.of());
        var projection = CharacterStateProjector.projection(List.of(all), 2, List.of("苏青"), 5, 4000);
        assertEquals(5, projection.selectedFactIds().size());
        assertEquals(15, projection.omittedFactIds().size());
        String rendered = CharacterStateProjector.render(List.of(all), 2, List.of("苏青"), 5, 4000);
        assertTrue(rendered.contains("省略事实（不代表失效）"), rendered);
        assertFalse(projection.projectionHash().isBlank());
    }

    @Test
    void legacyJsonNullShapeAndGoldenHashesRemainByteCompatible() throws Exception {
        String oldJson = "{\"acquisition\":\"read note\",\"chapterNo\":1,\"character\":\"Ada\","
                + "\"characterId\":\"character-99a563ab2f6e21e96998f9fddd2a2bab82b70ac019579502b8d7fc0032ff62bb\","
                + "\"effectId\":\"character-effect-4e1174118170e21a905fb01513ba006d84a9fee467bf4f3e4bc24ce561a226e6\","
                + "\"evidence\":\"Ada knows the gate code.\","
                + "\"factId\":\"character-fact-cd4c07c7b5c1e4a5ca8534a7eb508b7e4df7d3480ff4639bf9099cf2d9540c83\","
                + "\"key\":\"gate code\",\"kind\":\"KNOWLEDGE\","
                + "\"manuscriptHash\":\"f4e1154b49401116602e99fb2357380b0e1d5c2fe3287f9ce8581c81cedb39e7\","
                + "\"state\":\"ESTABLISHED\",\"value\":\"42\"}";
        CharacterEffect legacy = JSON.parseObject(oldJson, CharacterEffect.class);
        assertNull(legacy.evidenceSupport());
        assertEquals(oldJson, JSON.toJSONString(legacy));
        var summary = ChapterSummaryEntity.builder().chapterNo(1).title("Legacy")
                .manuscriptHash(legacy.manuscriptHash()).characterEffects(List.of(legacy)).build();
        assertEquals("e1cd1b40fa0cb6d9353d71706036e5f438110b80b698989bb89ed267e7cbc383",
                CharacterStateProjector.project(List.of(summary), 2).hash());
        assertDoesNotThrow(() -> CharacterStateProjector.validateSummary(summary,
                new ChapterManuscript(1, "Legacy", "Ada knows the gate code."), List.of()));
        var unbound = new CharacterEffect("Ada", Kind.KNOWLEDGE, "gate code", State.ESTABLISHED,
                "42", "Ada knows the gate code.", "read note", null);
        assertEquals("{\"character\":\"Ada\",\"kind\":\"KNOWLEDGE\",\"key\":\"gate code\","
                        + "\"state\":\"ESTABLISHED\",\"value\":\"42\",\"evidence\":\"Ada knows the gate code.\","
                        + "\"acquisition\":\"read note\",\"referenceId\":null,\"characterId\":null,"
                        + "\"factId\":null,\"effectId\":null,\"chapterNo\":null,\"manuscriptHash\":null}",
                new ObjectMapper().writeValueAsString(unbound));
    }

    @Test
    void freshClaimsWithoutSupportNeverUseLiteralMatchingAsPsychologicalProof() {
        String text = "苏青握紧拳头，推开门。";
        var claims = List.of(
                new CharacterEffect("苏青", Kind.BELIEF, "信任", State.ESTABLISHED, "林远可信", text, "动作暗示", null),
                new CharacterEffect("苏青", Kind.DECISION, "离队", State.ESTABLISHED, "决定离队", text, null, null),
                new CharacterEffect("苏青", Kind.KNOWLEDGE, "井位", State.ESTABLISHED, "知道井位", "", "获知", null));
        var summary = bindNew(1, text, claims, List.of());
        assertTrue(summary.getCharacterEffects().isEmpty());
        assertEquals(claims, summary.getPendingCharacterEffects());
        String before = JSON.toJSONString(summary);
        CharacterStateProjector.bind(summary, new ChapterManuscript(1, "合成章", text), List.of());
        assertEquals(before, JSON.toJSONString(summary), "重复绑定不能清空或翻倍 pending");
    }

    @Test
    void explicitCharacterSuspicionIsAcceptedWithoutTreatingExtractorUncertaintyAsBelief() {
        String text = "苏青明确怀疑林远在隐瞒井位。";
        var effect = explicit("苏青", Kind.BELIEF, "井位", State.SUSPECTED, "林远在隐瞒井位", text, null);
        var summary = bindNew(1, text, List.of(effect), List.of());
        assertNull(summary.getPendingCharacterEffects());
        assertEquals(State.SUSPECTED, summary.getCharacterEffects().get(0).state());
        assertEquals("CONTEXT_BOUND_V1", summary.getCharacterEffects().get(0).evidenceSupport().verification());
        assertTrue(CharacterStateProjector.render(List.of(summary), 2, List.of("苏青")).contains("BELIEF/SUSPECTED"));
    }

    @Test
    void explicitSubjectContextAndKindMustAgreeAndMarkersCannotSelfAuthorize() {
        record Fixture(String text, String context, String subject, Attribution attribution) {}
        List<Fixture> fixtures = List.of(
                new Fixture("林远知道苏青在隐瞒井位。", "林远知道苏青在隐瞒井位。", "苏青", Attribution.EXPLICIT_NARRATION),
                new Fixture("他知道井位是真的。", "他知道井位是真的。", "苏青", Attribution.EXPLICIT_NARRATION),
                new Fixture("林远说：苏青相信井位是真的。", "苏青相信井位是真的。", "苏青", Attribution.EXPLICIT_NARRATION),
                new Fixture("如果苏青相信井位是真的，她就会出发。", "苏青相信井位是真的", "苏青", Attribution.EXPLICIT_NARRATION),
                new Fixture("苏青撒谎说：我相信井位是真的。", "苏青撒谎说：我相信井位是真的。", "苏青", Attribution.DIRECT_STATEMENT),
                new Fixture("苏青握紧拳头。", "苏青握紧拳头。", "苏青", Attribution.EXPLICIT_NARRATION));
        for (Fixture fixture : fixtures) {
            var support = new EvidenceSupport(1, fixture.attribution(), fixture.subject(), fixture.context(),
                    0, null, null, null, "ADJUDICATED_V1");
            var candidate = new CharacterEffect("苏青", Kind.BELIEF, "井位", State.ESTABLISHED,
                    "井位是真的", fixture.context(), "模型推断", null, support);
            var summary = bindNew(1, fixture.text(), List.of(candidate), List.of());
            assertTrue(summary.getCharacterEffects().isEmpty(), fixture.text());
            assertEquals(List.of(candidate), summary.getPendingCharacterEffects());
        }
    }

    @Test
    void sameChapterFulfilmentReplaysVerifiedBodyOrderAndKeepsTheAcceptedEvent() {
        String acceptedText = "苏青答应寻找手册。";
        String completedText = "苏青完成寻找手册。";
        var accepted = explicit("苏青", Kind.COMMITMENT, "寻找手册", State.ACCEPTED,
                "寻找手册", acceptedText, null);
        var completed = explicit("苏青", Kind.COMMITMENT, "寻找手册", State.COMPLETED,
                "寻找手册", completedText, null);
        var summary = bindNew(1, acceptedText + completedText, List.of(completed, accepted), List.of());
        assertNull(summary.getPendingCharacterEffects());
        assertEquals(List.of(State.ACCEPTED, State.COMPLETED), summary.getCharacterEffects().stream()
                .map(CharacterEffect::state).toList());
        var bound = summary.getCharacterEffects();
        assertEquals(bound.get(0).factId(), bound.get(1).referenceId(), "同章前驱由代码绑定，不要求模型猜 hash");
        assertEquals(acceptedText.length(), bound.get(1).evidenceSupport().order());
        assertEquals(State.COMPLETED, CharacterStateProjector.project(List.of(summary), 2)
                .facts().get(bound.get(0).factId()).state());
        var trajectory = CharacterStateProjector.trajectory(List.of(summary), 2, List.of("苏青"), 12, 4000);
        assertEquals(2, trajectory.events().size());
        assertTrue(trajectory.text().contains("ACCEPTED"));
        assertTrue(trajectory.text().contains("COMPLETED"));
        String before = JSON.toJSONString(summary);
        CharacterStateProjector.bind(summary, new ChapterManuscript(1, "合成章", acceptedText + completedText), List.of());
        assertEquals(before, JSON.toJSONString(summary));
        assertDoesNotThrow(() -> CharacterStateProjector.validateSummary(summary,
                new ChapterManuscript(1, "合成章", acceptedText + completedText), List.of()));
    }

    @Test
    void evidenceOrderDoesNotAllowCompletionBeforeAcceptanceAndEqualOffsetsAreAmbiguous() {
        String acceptedText = "苏青答应寻找手册。";
        String completedText = "苏青完成寻找手册。";
        var accepted = explicit("苏青", Kind.COMMITMENT, "寻找手册", State.ACCEPTED, "寻找手册", acceptedText, null);
        var completed = explicit("苏青", Kind.COMMITMENT, "寻找手册", State.COMPLETED, "寻找手册", completedText, null);
        var reversed = bindNew(1, completedText + acceptedText, List.of(accepted, completed), List.of());
        assertEquals(1, reversed.getPendingCharacterEffects().size());
        assertEquals(State.COMPLETED, reversed.getPendingCharacterEffects().get(0).state());
        assertEquals(State.ACCEPTED, reversed.getCharacterEffects().get(0).state());
        var duplicate = bindNew(1, acceptedText, List.of(accepted, accepted), List.of());
        assertTrue(duplicate.getCharacterEffects().isEmpty());
        assertEquals(2, duplicate.getPendingCharacterEffects().size());
    }

    @Test
    void withdrawalNeedsReferencedPriorAndTerminalCommitmentsCannotSilentlyReopen() {
        String acceptedText = "苏青答应寻找手册。";
        var accepted = bindNew(1, acceptedText, List.of(explicit("苏青", Kind.COMMITMENT, "寻找手册",
                State.ACCEPTED, "寻找手册", acceptedText, null)), List.of());
        String ref = accepted.getCharacterEffects().get(0).factId();
        String withdrawnText = "苏青撤回寻找手册的承诺。";
        var noRef = bindNew(2, withdrawnText, List.of(explicit("苏青", Kind.COMMITMENT, "寻找手册",
                State.WITHDRAWN, "寻找手册", withdrawnText, null)), List.of(accepted));
        assertTrue(noRef.getCharacterEffects().isEmpty());
        var withdrawn = bindNew(2, withdrawnText, List.of(explicit("苏青", Kind.COMMITMENT, "寻找手册",
                State.WITHDRAWN, "寻找手册", withdrawnText, ref)), List.of(accepted));
        assertEquals(State.WITHDRAWN, withdrawn.getCharacterEffects().get(0).state());
        var reopen = bindNew(3, acceptedText, List.of(explicit("苏青", Kind.COMMITMENT, "寻找手册",
                State.ACCEPTED, "寻找手册", acceptedText, ref)), List.of(accepted, withdrawn));
        assertTrue(reopen.getCharacterEffects().isEmpty());
        assertNotNull(reopen.getPendingCharacterEffects());
    }

    @Test
    void versionedMetadataParticipatesInEffectHashAndStoredOrderTamperingIsRejected() {
        String text = "苏青明确怀疑林远在隐瞒井位。";
        var first = bindNew(1, text, List.of(explicit("苏青", Kind.BELIEF, "井位", State.SUSPECTED,
                "林远在隐瞒井位", text, null)), List.of());
        var changed = JSON.parseObject(JSON.toJSONString(first), ChapterSummaryEntity.class);
        var effect = changed.getCharacterEffects().get(0);
        var support = effect.evidenceSupport();
        var tampered = new EvidenceSupport(1, support.attribution(), support.subject(), support.context(),
                17, support.supportRefs(), support.scope(), support.target(), support.verification());
        changed.setCharacterEffects(List.of(new CharacterEffect(effect.character(), effect.kind(), effect.key(),
                effect.state(), effect.value(), effect.evidence(), effect.acquisition(), effect.referenceId(),
                effect.characterId(), effect.factId(), effect.effectId(), effect.chapterNo(), effect.manuscriptHash(), tampered)));
        assertTrue(CharacterStateProjector.project(List.of(changed), 2).facts().isEmpty());
        assertThrows(IllegalStateException.class, () -> CharacterStateProjector.validateSummary(changed,
                new ChapterManuscript(1, "合成章", text), List.of()));
        assertEquals(first.getCharacterEffects(), JSON.parseObject(JSON.toJSONString(first),
                ChapterSummaryEntity.class).getCharacterEffects());
    }

    @Test
    void trajectoryKeepsRelevantCompletedActionsAndExcludesFutureAndUnresolvedCandidates() {
        String actionText = "苏青完成交还手册。";
        var action = bindNew(1, actionText, List.of(explicit("苏青", Kind.ACTION, "交还手册", State.COMPLETED,
                "交还手册", actionText, null)), List.of());
        String futureText = "苏青完成抵达月球。";
        var future = bindNew(9, futureText, List.of(explicit("苏青", Kind.ACTION, "抵达月球", State.COMPLETED,
                "抵达月球", futureText, null)), List.of(action));
        var trajectory = CharacterStateProjector.trajectory(List.of(future, action), 2, List.of("苏青"), 8, 2000);
        assertEquals(1, trajectory.events().size());
        assertTrue(trajectory.text().contains("交还手册"));
        assertFalse(trajectory.text().contains("抵达月球"));
        assertFalse(trajectory.text().contains("必须再完成"));
        assertEquals(trajectory.hash(), CharacterStateProjector.trajectory(List.of(action), 2, List.of("苏青"), 8, 2000).hash());
        assertEquals("", CharacterStateProjector.render(List.of(action), 2, List.of("苏青")),
                "旧render保持原筛选，APPLY通过独立轨迹入口启用");
    }

    @Test
    void v1CannotCompleteFromAnUnresolvedPriorCommitment() {
        String acceptedText = "苏青答应寻找手册。";
        var accepted = bindNew(1, acceptedText, List.of(explicit("苏青", Kind.COMMITMENT, "寻找手册",
                State.ACCEPTED, "寻找手册", acceptedText, null)), List.of());
        String ref = accepted.getCharacterEffects().get(0).factId();
        var pending = new CharacterEffect("苏青", Kind.COMMITMENT, "寻找手册", State.WITHDRAWN,
                "寻找手册", "没有此证据", null, ref);
        var unresolved = ChapterSummaryEntity.builder().chapterNo(2).manuscriptHash("fixture-hash")
                .pendingCharacterEffects(List.of(pending)).build();
        String completedText = "苏青完成寻找手册。";
        var completed = bindNew(3, completedText, List.of(explicit("苏青", Kind.COMMITMENT, "寻找手册",
                State.COMPLETED, "寻找手册", completedText, ref)), List.of(accepted, unresolved));
        assertTrue(completed.getCharacterEffects().isEmpty());
        assertEquals(1, completed.getPendingCharacterEffects().size());
    }

    @Test
    void explicitSupportDoesNotBorrowAClaimFromAnotherSentenceOrIgnoreNegation() {
        for (String text : List.of("苏青相信旧地图。林远知道井位是真的。", "苏青相信井位不是真的。", "苏青怀疑自己是否相信井位是真的。")) {
            var candidate = explicit("苏青", Kind.BELIEF, "井位", State.ESTABLISHED, "井位是真的", text, null);
            var summary = bindNew(1, text, List.of(candidate), List.of());
            assertTrue(summary.getCharacterEffects().isEmpty(), text);
            assertEquals(List.of(candidate), summary.getPendingCharacterEffects());
        }
    }

    @Test
    void entireRenderedOutputFitsBudgetIncludingHeaderDiagnosticsAndOmissions() {
        List<CharacterEffect> effects = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            String sentence = "苏青明确怀疑线索" + i + "不可靠。";
            text.append(sentence);
            effects.add(explicit("苏青", Kind.BELIEF, "线索" + i, State.SUSPECTED, "线索" + i + "不可靠", sentence, null));
        }
        var all = bindNew(1, text.toString(), effects, List.of());
        for (int budget : List.of(1, 80, 500, 1200)) {
            String rendered = CharacterStateProjector.render(List.of(all), 2, List.of("苏青"), 30, budget);
            assertTrue(rendered.length() <= budget, "render budget=" + budget + ", actual=" + rendered.length());
            var trajectory = CharacterStateProjector.trajectory(List.of(all), 2, List.of("苏青"), 30, budget);
            assertTrue(trajectory.text().length() <= budget);
            for (CharacterEffect selected : trajectory.events()) {
                assertTrue(trajectory.text().contains(selected.evidence()), "只选能完整输出的整条事件");
            }
        }
    }

    @Test
    void explicitAliasesAreStableWhileAmbiguousAliasesDoNotMergePeople() {
        String source = "苏青告诉林远：我就是阿青。";
        var alias = summary(1, source, List.of(fact("苏青", Kind.ALIAS, "阿青", State.ESTABLISHED, "同一人", source, null)), List.of());
        var second = summary(2, "阿青看过目录。", List.of(fact("阿青", Kind.KNOWLEDGE, "目录", State.ESTABLISHED,
                "已看过目录", "阿青看过目录。", null)), List.of(alias));
        assertEquals(CharacterStateProjector.characterId("苏青"), second.getCharacterEffects().get(0).characterId());
        assertEquals(2, CharacterStateProjector.project(List.of(alias, second), 3).facts().size());
        var distinct = summary(1, "阿青自己走了。", List.of(fact("阿青", Kind.ACTION, "离开", State.COMPLETED,
                "已离开", "阿青自己走了。", null)), List.of());
        var conflict = summary(2, source, List.of(fact("苏青", Kind.ALIAS, "阿青", State.ESTABLISHED, "同一人", source, null)), List.of(distinct));
        assertTrue(conflict.getCharacterEffects().isEmpty());
    }
}
