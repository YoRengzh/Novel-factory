package cn.novel.yonren.domain.novel.service.armory.plan;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.valobj.character.CharacterEffect;
import cn.novel.yonren.domain.novel.model.valobj.plan.FactReference;
import cn.novel.yonren.domain.novel.service.armory.memory.CharacterStateProjector;
import cn.novel.yonren.types.utils.FactIdentity;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把已提交人物事实转换为可核对引用，供规划、正文与审校共用。
 * 不重新解释语义；只复制已由 CharacterStateProjector 校验过的身份、章节、hash 与正文引文。
 *
 * <p>旧书没有 characterEffects 时退回旧三账本条目，仍要求 hash 与正文连续引文；
 * 旧人物条目无法区分认知/承诺/动作，状态前缀标成 {@code LEGACY/}，只作世界前提核对，
 * 不得被当成“某人知道/答应了”的证据。
 */
public final class CharacterFactReferences {
    private CharacterFactReferences() {}

    public static List<FactReference> from(List<ChapterSummaryEntity> history, int beforeChapter) {
        if (history == null || history.isEmpty()) return List.of();
        var projection = CharacterStateProjector.projection(history, beforeChapter, null,
                CharacterStateProjector.DEFAULT_MAX_FACTS, CharacterStateProjector.DEFAULT_MAX_CHARS);
        return from(projection, history, beforeChapter);
    }

    public static List<FactReference> from(CharacterStateProjector.Snapshot snapshot, int beforeChapter) {
        if (snapshot == null || snapshot.facts().isEmpty()) return List.of();
        Map<String, FactReference> ordered = new LinkedHashMap<>();
        snapshot.facts().values().stream()
                .filter(fact -> fact != null && fact.chapterNo() != null
                        && fact.chapterNo() > 0 && fact.chapterNo() < beforeChapter)
                .filter(fact -> fact.kind() == CharacterEffect.Kind.KNOWLEDGE
                        || fact.kind() == CharacterEffect.Kind.BELIEF
                        || fact.kind() == CharacterEffect.Kind.COMMITMENT
                        || fact.kind() == CharacterEffect.Kind.GOAL
                        || fact.kind() == CharacterEffect.Kind.INTENT
                        || fact.kind() == CharacterEffect.Kind.DECISION)
                .sorted((a, b) -> {
                    int rank = Integer.compare(rank(a.kind()), rank(b.kind()));
                    if (rank != 0) return rank;
                    int chapter = Integer.compare(b.chapterNo(), a.chapterNo());
                    return chapter != 0 ? chapter : a.factId().compareTo(b.factId());
                })
                .forEach(fact -> ordered.put(fact.factId(), new FactReference(
                        "CHARACTER",
                        fact.chapterNo(),
                        fact.manuscriptHash(),
                        fact.factId(),
                        fact.kind().name() + "/" + fact.state().name() + "|" + fact.character()
                                + "|" + fact.key() + "|" + fact.value(),
                        fact.evidence())));
        return List.copyOf(ordered.values());
    }

    /** 与写作投影一致：只暴露入选的确认事实，不能把已省略事实重新塞回审校 prompt。 */
    public static List<FactReference> from(CharacterStateProjector.Projection projection, int beforeChapter) {
        if (projection == null) return List.of();
        Set<String> selected = new HashSet<>(projection.selectedFactIds());
        return from(projection.snapshot(), beforeChapter).stream()
                .filter(ref -> selected.contains(ref.subject())
                        && !projection.unresolvedIds().contains(ref.subject()))
                .toList();
    }

    /** 有界人物投影 + 旧三账本世界事实：正文审校既看确认认知，也能核对物品/势力前提。 */
    public static List<FactReference> from(CharacterStateProjector.Projection projection,
                                           List<ChapterSummaryEntity> history, int beforeChapter) {
        Map<String, FactReference> ordered = new LinkedHashMap<>();
        from(projection, beforeChapter).forEach(ref -> ordered.put(identity(ref), ref));
        if (history != null) legacy(history, beforeChapter, ordered);
        return List.copyOf(ordered.values());
    }

    /**
     * 旧摘要账本兼容：每个实体只保留最新一条带正文 hash 与连续引文的记录。
     * 已由结构化事实覆盖的角色不再回灌旧条目，避免同一认知出现两个版本。
     */
    private static void legacy(List<ChapterSummaryEntity> history, int beforeChapter,
                               Map<String, FactReference> ordered) {
        if (history == null) return;
        Set<String> structuredNames = new HashSet<>();
        for (FactReference ref : ordered.values()) {
            String[] parts = ref.status() == null ? new String[0] : ref.status().split("\\|", -1);
            if (parts.length > 1 && !parts[1].isBlank()) structuredNames.add(parts[1].strip());
        }
        Set<String> legacyCharacters = new HashSet<>(), legacyItems = new HashSet<>(), legacyFactions = new HashSet<>();
        history.stream()
                .filter(summary -> summary != null && summary.getChapterNo() != null
                        && summary.getChapterNo() > 0 && summary.getChapterNo() < beforeChapter)
                .sorted((a, b) -> Integer.compare(b.getChapterNo(), a.getChapterNo()))
                .forEach(summary -> {
                    addLegacy(ordered, summary, "CHARACTER", summary.getCharacterStates(), structuredNames, legacyCharacters);
                    addLegacy(ordered, summary, "ITEM", summary.getItemStates(), Set.of(), legacyItems);
                    addLegacy(ordered, summary, "FACTION", summary.getFactionStates(), Set.of(), legacyFactions);
                });
    }

    private static void addLegacy(Map<String, FactReference> ordered, ChapterSummaryEntity summary,
                                  String ledger, List<ChapterSummaryEntity.StateEntry> states,
                                  Set<String> skipNames, Set<String> seenNames) {
        if (states == null || summary.getManuscriptHash() == null || summary.getManuscriptHash().isBlank()) return;
        for (var state : states) {
            if (state == null || state.getName() == null || state.getName().isBlank()
                    || state.getStatus() == null || state.getStatus().isBlank()
                    || state.getEvidence() == null || state.getEvidence().isBlank()) continue;
            String name = state.getName().strip();
            if (!seenNames.add(name) || skipNames.contains(name)) continue;
            FactReference reference = new FactReference(ledger, summary.getChapterNo(),
                    summary.getManuscriptHash(), name,
                    "LEGACY/" + state.getStatus().strip(), state.getEvidence().strip());
            ordered.putIfAbsent(identity(reference), reference);
        }
    }

    public static String identity(FactReference reference) {
        return reference == null ? null : FactIdentity.of(reference.ledger(), reference.chapterNo(),
                reference.manuscriptHash(), reference.subject(), reference.status(), reference.evidence());
    }

    /**
     * 该事实能否支撑某类冲突结论。
     * 人物信念只能用于“认知回退/误信被写成事实”类核对；能力与结果缺口必须由世界事实或已确认认知支撑，
     * 否则模型只要挑一条信念就能把自己的推断包装成已核验冲突。
     */
    public static boolean usableAsBasis(FactReference fact, String failureType) {
        if (fact == null) return false;
        boolean character = "CHARACTER".equalsIgnoreCase(fact.ledger());
        String status = fact.status() == null ? "" : fact.status();
        if (status.startsWith("LEGACY/")) {
            return "CAPABILITY_CONFLICT".equals(failureType)
                    || "RESULT_WITHOUT_CAUSE".equals(failureType);
        }
        if (character && status.startsWith("BELIEF/")) return "KNOWLEDGE_REGRESSION".equals(failureType);
        return character || "ITEM".equalsIgnoreCase(fact.ledger()) || "FACTION".equalsIgnoreCase(fact.ledger());
    }

    private static int rank(CharacterEffect.Kind kind) {
        return switch (kind) {
            case KNOWLEDGE -> 0;
            case COMMITMENT -> 1;
            case BELIEF -> 2;
            case DECISION, INTENT, GOAL -> 3;
            case ALIAS -> 4;
            case ACTION -> 5;
        };
    }
}
