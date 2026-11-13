package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.valobj.character.CharacterEffect;
import cn.novel.yonren.domain.novel.model.valobj.character.CharacterEffect.Attribution;
import cn.novel.yonren.domain.novel.model.valobj.character.CharacterEffect.EvidenceSupport;
import cn.novel.yonren.domain.novel.model.valobj.character.CharacterEffect.Kind;
import cn.novel.yonren.domain.novel.model.valobj.character.CharacterEffect.State;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import com.alibaba.fastjson2.JSON;

import java.util.*;

/** 从已提交摘要按章序重建人物状态，不维护第二份权威人物库。 */
public final class CharacterStateProjector {
    private CharacterStateProjector() {}

    public record Snapshot(Map<String, CharacterEffect> facts, Map<String, String> aliases,
                           Set<String> unresolvedIds, String hash) {
        public String identity(String name) { return aliases.getOrDefault(normalize(name), characterId(name)); }
        public boolean knowsReference(String name, String reference) {
            CharacterEffect fact = facts.get(reference);
            return fact != null && !unresolvedIds.contains(reference) && identity(name).equals(fact.characterId())
                    && (fact.kind() == Kind.KNOWLEDGE || fact.kind() == Kind.BELIEF)
                    && fact.state() != State.FORGOTTEN && fact.state() != State.WITHDRAWN;
        }
    }

    /**
     * 确认事实与诊断分离。unresolved 只用于审计和诊断，绝不能作为“人物确定知道什么”的硬边界。
     * 旧 Snapshot 仍保留完整 facts，供提交校验与全量读取。
     */
    public record Projection(Snapshot snapshot, Map<String, CharacterEffect> confirmedFacts,
                             Set<String> unresolvedIds, List<String> diagnostics,
                             List<String> selectedFactIds, List<String> omittedFactIds, String projectionHash) {
        public boolean hasUnresolvedFor(Collection<String> names) {
            if (names == null || names.isEmpty()) return !unresolvedIds.isEmpty();
            return unresolvedIds.stream().anyMatch(id -> snapshot.facts().values().stream()
                    .anyMatch(fact -> id.equals(fact.factId()) && names.stream().anyMatch(name ->
                            snapshot.identity(name).equals(fact.characterId())))
                    || snapshot.facts().values().stream().noneMatch(fact -> id.equals(fact.factId())));
        }

        public boolean hasConfirmedKnowledge(String name, String reference) {
            CharacterEffect fact = confirmedFacts.get(reference);
            return fact != null && snapshot.identity(name).equals(fact.characterId())
                    && (fact.kind() == Kind.KNOWLEDGE || fact.kind() == Kind.BELIEF)
                    && fact.state() != State.FORGOTTEN && fact.state() != State.WITHDRAWN;
        }
    }

    public static Projection projection(List<ChapterSummaryEntity> summaries, Integer beforeChapter,
                                        Collection<String> cast, int maxFacts, int maxChars) {
        Snapshot snapshot = project(summaries, beforeChapter);
        Map<String, CharacterEffect> confirmed = new LinkedHashMap<>();
        snapshot.facts().forEach((id, fact) -> {
            if (!snapshot.unresolvedIds().contains(id)) confirmed.put(id, fact);
        });
        List<String> diagnostics = snapshot.unresolvedIds().stream().sorted()
                .map(id -> id + "=UNRESOLVED").toList();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (cast != null) cast.stream().filter(name -> !blank(name)).forEach(name -> names.add(snapshot.identity(name)));
        List<CharacterEffect> candidates = confirmed.values().stream()
                .filter(fact -> names.isEmpty() || names.contains(fact.characterId()))
                .filter(fact -> fact.kind() != Kind.ACTION)
                .filter(fact -> fact.kind() == Kind.KNOWLEDGE || fact.kind() == Kind.BELIEF
                        || fact.kind() == Kind.COMMITMENT || fact.kind() == Kind.DECISION
                        || fact.kind() == Kind.GOAL || fact.kind() == Kind.INTENT || fact.kind() == Kind.ALIAS)
                .filter(fact -> fact.state() != State.COMPLETED && fact.state() != State.FAILED
                        || fact.kind() == Kind.KNOWLEDGE || fact.kind() == Kind.BELIEF || fact.kind() == Kind.ALIAS)
                .sorted(Comparator.comparingInt((CharacterEffect fact) -> rank(fact.kind()))
                        .thenComparing(CharacterEffect::chapterNo, Comparator.reverseOrder())
                        .thenComparing(CharacterEffect::factId))
                .toList();
        List<CharacterEffect> selected = new ArrayList<>();
        List<String> omitted = new ArrayList<>();
        int chars = stateHeader(snapshot.hash(), "0".repeat(64)).length() + stateFooter(diagnostics.size(), candidates.size()).length();
        for (CharacterEffect fact : candidates) {
            int estimate = renderFact(fact).length();
            if (selected.size() >= Math.max(0, maxFacts) || maxChars > 0 && chars + estimate > maxChars) {
                omitted.add(fact.factId());
                continue;
            }
            selected.add(fact);
            chars += estimate;
        }
        String projectionHash = ChapterManuscript.digest(snapshot.hash() + "\n" + String.join(",",
                selected.stream().map(CharacterEffect::factId).toList()) + "\n"
                + String.join(",", omitted) + "\n" + String.join(",", diagnostics));
        return new Projection(snapshot, Collections.unmodifiableMap(confirmed), Collections.unmodifiableSet(
                new LinkedHashSet<>(snapshot.unresolvedIds())), diagnostics,
                selected.stream().map(CharacterEffect::factId).toList(), List.copyOf(omitted), projectionHash);
    }

    private static int rank(Kind kind) {
        return switch (kind) {
            case KNOWLEDGE -> 0;
            case COMMITMENT -> 1;
            case BELIEF -> 2;
            case DECISION, INTENT, GOAL -> 3;
            case ALIAS -> 4;
            case ACTION -> 5;
        };
    }

    public static Snapshot project(List<ChapterSummaryEntity> summaries, Integer beforeChapter) {
        return replay(summaries, beforeChapter).snapshot();
    }

    private record Replay(Snapshot snapshot, List<CharacterEffect> events) {}

    private static Replay replay(List<ChapterSummaryEntity> summaries, Integer beforeChapter) {
        Map<String, CharacterEffect> facts = new LinkedHashMap<>();
        Map<String, String> aliases = new LinkedHashMap<>();
        Set<String> unresolved = new LinkedHashSet<>();
        List<CharacterEffect> events = new ArrayList<>();
        Map<String, CharacterEffect> acceptedEvents = new LinkedHashMap<>();
        var chapters = history(summaries, beforeChapter);
        Map<Integer, Long> chapterCounts = chapters.stream().collect(java.util.stream.Collectors.groupingBy(
                ChapterSummaryEntity::getChapterNo, java.util.stream.Collectors.counting()));
        for (ChapterSummaryEntity summary : chapters) {
            if (blank(summary.getManuscriptHash())) continue;
            List<CharacterEffect> effects = safe(summary.getCharacterEffects());
            Set<String> ambiguous = ambiguousStoredFacts(effects);
            List<CharacterEffect> ordered = new ArrayList<>(effects);
            // v1 偏移只有绑定时回查正文后才产生；旧事件仍按旧列表序（同 fact 重复仍无效）。
            ordered.sort(Comparator.comparingInt(CharacterStateProjector::storedOrder));
            for (CharacterEffect effect : ordered) {
                if (!validStored(effect, summary) || chapterCounts.get(summary.getChapterNo()) > 1
                        || effect != null && ambiguous.contains(effect.factId())) {
                    markUnresolved(effect, aliases, unresolved);
                    continue;
                }
                String expectedId = aliases.getOrDefault(normalize(effect.character()), characterId(effect.character()));
                if (!expectedId.equals(effect.characterId())) {
                    markUnresolved(effect, aliases, unresolved);
                    continue;
                }
                CharacterEffect previous = facts.get(effect.factId());
                if (effect.evidenceSupport() != null && previous != null && unresolved.contains(effect.factId())
                        || !canApply(effect, previous) || !validSupportRefs(effect, acceptedEvents, unresolved)
                        || aliasConflicts(effect, facts, aliases)) {
                    unresolved.add(effect.factId());
                    continue;
                }
                if (effect.kind() == Kind.ALIAS) aliases.put(normalize(effect.key()), effect.characterId());
                facts.put(effect.factId(), effect);
                acceptedEvents.put(effect.effectId(), effect);
                events.add(effect);
                unresolved.remove(effect.factId());
            }
            for (CharacterEffect effect : safe(summary.getPendingCharacterEffects())) {
                markUnresolved(effect, aliases, unresolved);
            }
        }
        String hash = ChapterManuscript.digest(JSON.toJSONString(new TreeMap<>(facts)) + JSON.toJSONString(new TreeMap<>(aliases))
                + JSON.toJSONString(new TreeSet<>(unresolved)));
        return new Replay(new Snapshot(Collections.unmodifiableMap(facts), Collections.unmodifiableMap(aliases),
                Collections.unmodifiableSet(unresolved), hash), List.copyOf(events));
    }

    private static Set<String> ambiguousStoredFacts(List<CharacterEffect> effects) {
        Map<String, List<CharacterEffect>> groups = new HashMap<>();
        for (CharacterEffect effect : effects) if (effect != null && !blank(effect.factId())) {
            groups.computeIfAbsent(effect.factId(), ignored -> new ArrayList<>()).add(effect);
        }
        Set<String> ambiguous = new HashSet<>();
        groups.forEach((id, group) -> {
            if (group.size() < 2) return;
            Set<Integer> orders = new HashSet<>();
            if (group.stream().anyMatch(e -> e.evidenceSupport() == null || e.evidenceSupport().order() == null
                    || !orders.add(e.evidenceSupport().order()))) ambiguous.add(id);
        });
        return ambiguous;
    }

    private static int storedOrder(CharacterEffect effect) {
        return effect == null || effect.evidenceSupport() == null || effect.evidenceSupport().order() == null
                ? -1 : effect.evidenceSupport().order();
    }

    private static void markUnresolved(CharacterEffect effect, Map<String, String> aliases, Set<String> unresolved) {
        if (effect != null && effect.kind() != null && !blank(effect.character()) && !blank(effect.key())) {
            unresolved.add(factId(aliases.getOrDefault(normalize(effect.character()), characterId(effect.character())),
                    effect.kind(), effect.key()));
        }
    }

    private static boolean aliasConflicts(CharacterEffect effect, Map<String, CharacterEffect> facts,
                                          Map<String, String> aliases) {
        if (effect.kind() != Kind.ALIAS) return false;
        String alias = normalize(effect.key());
        String existing = aliases.get(alias);
        return existing != null && !existing.equals(effect.characterId()) || facts.values().stream().anyMatch(f ->
                normalize(f.character()).equals(alias) && !f.characterId().equals(effect.characterId()));
    }

    /** 旧入口维持灰度关闭时的原归因策略；所有身份/hash 仍重新由代码绑定。 */
    public static void bind(ChapterSummaryEntity summary, ChapterManuscript manuscript,
                            List<ChapterSummaryEntity> history) {
        bind(summary, manuscript, history, false);
    }

    /** APPLY 才要求 v1 支持；新支持不齐全时只进 pending，不将动作解释为心理。 */
    public static void bind(ChapterSummaryEntity summary, ChapterManuscript manuscript,
                            List<ChapterSummaryEntity> history, boolean strictSupport) {
        if (summary == null || summary.getCharacterEffects() == null) return;
        Objects.requireNonNull(manuscript, "manuscript");
        Replay replay = replay(history, manuscript.chapterNo());
        Snapshot previous = replay.snapshot();
        Map<String, CharacterEffect> working = new LinkedHashMap<>(previous.facts());
        Map<String, String> aliases = new LinkedHashMap<>(previous.aliases());
        Map<String, CharacterEffect> acceptedEvents = new LinkedHashMap<>();
        replay.events().forEach(effect -> acceptedEvents.put(effect.effectId(), effect));
        List<CharacterEffect> kept = new ArrayList<>();
        List<CharacterEffect> originalPending = safe(summary.getPendingCharacterEffects());
        List<CharacterEffect> rejected = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        int index = 0;
        for (CharacterEffect candidate : summary.getCharacterEffects()) {
            int inputIndex = index++;
            if (!validCandidate(candidate, manuscript.content())) {
                if (candidate != null) rejected.add(candidate);
                continue;
            }
            EvidenceSupport support = null;
            if (strictSupport || candidate.evidenceSupport() != null) {
                support = bindSupport(candidate, manuscript.content());
                if (support == null) {
                    rejected.add(candidate);
                    continue;
                }
            }
            candidates.add(new Candidate(candidate, support, inputIndex,
                    manuscript.content().indexOf(candidate.evidence())));
        }
        Map<String, List<Candidate>> groups = new HashMap<>();
        for (Candidate candidate : candidates) {
            String id = factId(previous.identity(candidate.effect().character()), candidate.effect().kind(), candidate.effect().key());
            groups.computeIfAbsent(id, ignored -> new ArrayList<>()).add(candidate);
        }
        Set<Integer> ambiguous = new HashSet<>();
        groups.values().forEach(group -> {
            if (group.size() < 2) return;
            Set<Integer> offsets = new HashSet<>();
            if (group.stream().anyMatch(c -> c.support() == null || !offsets.add(c.offset()))) {
                group.forEach(c -> ambiguous.add(c.inputIndex()));
            }
        });
        // 同章迁移按实际连续原句顺序重放，不依赖模型输出数组或模型 order。
        candidates.sort(Comparator.comparingInt(Candidate::offset).thenComparingInt(Candidate::inputIndex));
        Set<String> updatedThisChapter = new HashSet<>();
        for (Candidate candidate : candidates) {
            CharacterEffect effect = candidate.effect();
            if (ambiguous.contains(candidate.inputIndex())) {
                rejected.add(effect);
                continue;
            }
            String characterId = aliases.getOrDefault(normalize(effect.character()), characterId(effect.character()));
            String factId = factId(characterId, effect.kind(), effect.key());
            CharacterEffect prior = working.get(factId);
            String reference = effect.referenceId();
            if (blank(reference) && candidate.support() != null && updatedThisChapter.contains(factId)) {
                reference = factId;
            }
            if (!blank(reference) && (prior == null || !reference.equals(prior.factId())
                    || !characterId.equals(prior.characterId()) || prior.kind() != effect.kind())) {
                rejected.add(effect);
                continue;
            }
            CharacterEffect bound = new CharacterEffect(effect.character().strip(), effect.kind(), effect.key().strip(),
                    effect.state(), effect.value().strip(), effect.evidence(), effect.acquisition(), reference,
                    characterId, factId, null, manuscript.chapterNo(), manuscript.hash(), candidate.support());
            bound = new CharacterEffect(bound.character(), bound.kind(), bound.key(), bound.state(), bound.value(),
                    bound.evidence(), bound.acquisition(), bound.referenceId(), characterId, factId, effectId(bound),
                    manuscript.chapterNo(), manuscript.hash(), bound.evidenceSupport());
            if (candidate.support() != null && prior != null && previous.unresolvedIds().contains(factId)
                    || !canApply(bound, prior) || !validSupportRefs(bound, acceptedEvents, previous.unresolvedIds())
                    || aliasConflicts(bound, working, aliases)) {
                rejected.add(effect);
                continue;
            }
            kept.add(bound);
            working.put(factId, bound);
            acceptedEvents.put(bound.effectId(), bound);
            updatedThisChapter.add(factId);
            if (bound.kind() == Kind.ALIAS) aliases.put(normalize(bound.key()), bound.characterId());
        }
        // pending 不是候选输入：不自动重试、更不能重绑时静默清空；重复调用保持原列表/原条目。
        List<CharacterEffect> pending = new ArrayList<>(originalPending);
        for (CharacterEffect effect : rejected) if (!originalPending.contains(effect)) pending.add(effect);
        summary.setCharacterEffects(kept);
        summary.setPendingCharacterEffects(pending.isEmpty() ? null : pending);
    }

    private record Candidate(CharacterEffect effect, EvidenceSupport support, int inputIndex, int offset) {}

    public static void validateSummary(ChapterSummaryEntity summary, ChapterManuscript manuscript,
                                       List<ChapterSummaryEntity> history) {
        if (summary == null || summary.getCharacterEffects() == null || summary.getCharacterEffects().isEmpty()) return;
        if (!Objects.equals(summary.getChapterNo(), manuscript.chapterNo())
                || !Objects.equals(summary.getManuscriptHash(), manuscript.hash())) {
            throw new IllegalStateException("CHARACTER_STATE_SOURCE_MISMATCH");
        }
        var copy = new ChapterSummaryEntity();
        copy.setCharacterEffects(new ArrayList<>(summary.getCharacterEffects()));
        bind(copy, manuscript, history);
        Map<String, CharacterEffect> expected = new LinkedHashMap<>();
        copy.getCharacterEffects().forEach(effect -> expected.put(effect.effectId(), effect));
        boolean allLegacy = summary.getCharacterEffects().stream().allMatch(e -> e != null && e.evidenceSupport() == null);
        boolean same = allLegacy ? summary.getCharacterEffects().size() == expected.size()
                && summary.getCharacterEffects().stream().allMatch(e -> e.equals(expected.get(e.effectId())))
                : summary.getCharacterEffects().equals(copy.getCharacterEffects());
        if (!same || copy.getPendingCharacterEffects() != null) {
            throw new IllegalStateException("CHARACTER_STATE_INVALID_EVIDENCE_OR_TRANSITION");
        }
    }

    public static String render(List<ChapterSummaryEntity> summaries, Integer beforeChapter, Collection<String> cast) {
        return render(summaries, beforeChapter, cast, DEFAULT_MAX_FACTS, DEFAULT_MAX_CHARS);
    }

    /** 有界写作/规划投影：保留旧筛选策略；诊断/表头/省略提示也计入预算，不截半句。 */
    public static String render(List<ChapterSummaryEntity> summaries, Integer beforeChapter,
                                Collection<String> cast, int maxFacts, int maxChars) {
        Projection projection = projection(summaries, beforeChapter, cast, maxFacts, maxChars);
        if (projection.selectedFactIds().isEmpty() && projection.diagnostics().isEmpty()
                && projection.omittedFactIds().isEmpty()) return "";
        StringBuilder text = new StringBuilder(stateHeader(projection.snapshot().hash(), projection.projectionHash()));
        for (String id : projection.selectedFactIds()) text.append(renderFact(projection.confirmedFacts().get(id)));
        String footer = stateFooter(projection.diagnostics().size(), projection.omittedFactIds().size());
        if (maxChars > 0 && text.length() + footer.length() > maxChars) return "";
        return text.append(footer).toString();
    }

    private static String stateHeader(String stateHash, String projectionHash) {
        return "【动态人物状态·截至本章之前】stateHash=" + stateHash + " projectionHash=" + projectionHash
                + "\n缺省是未知，不等于不知道；信念可能错误，意图/请求不等于承诺或完成。\n";
    }

    private static String stateFooter(int unresolved, int omitted) {
        return (unresolved == 0 ? "" : "未解决人物状态（不得作为确定知识，必须核对原正文）：UNRESOLVED " + unresolved + " 项\n")
                + (omitted == 0 ? "" : "省略事实（不代表失效）：" + omitted + " 项；完整ID见投影诊断\n");
    }

    private static String renderFact(CharacterEffect fact) {
        return "- " + fact.factId() + "｜" + fact.character() + "｜" + fact.kind() + '/' + fact.state() + "｜"
                + fact.key() + "：" + fact.value() + "（第" + fact.chapterNo() + "章，证据：" + fact.evidence() + "）\n"
                + (blank(fact.acquisition()) ? "" : "  获知/形成方式：" + fact.acquisition() + '\n');
    }

    /** 只读派生轨迹，不落盘、不构成第二份人物权威账本；events 仅为实际完整渲染的事件。 */
    public record Trajectory(List<CharacterEffect> events, List<String> omittedEffectIds,
                             List<String> diagnostics, String hash, String text) {}

    public static Trajectory trajectory(List<ChapterSummaryEntity> summaries, Integer beforeChapter,
                                        Collection<String> cast, int maxEvents, int maxChars) {
        Replay replay = replay(summaries, beforeChapter);
        Snapshot snapshot = replay.snapshot();
        Set<String> identities = new LinkedHashSet<>();
        if (cast != null) cast.stream().filter(name -> !blank(name)).forEach(name -> identities.add(snapshot.identity(name)));
        List<CharacterEffect> candidates = replay.events().stream()
                .filter(effect -> !snapshot.unresolvedIds().contains(effect.factId()))
                .filter(effect -> identities.isEmpty() || identities.contains(effect.characterId()))
                .filter(effect -> effect.kind() != Kind.ALIAS)
                .toList();
        // 优先最近的选择/行动/承诺结果；旧已完成事件不消失，也不被误标成下一章待办。
        List<CharacterEffect> ranked = new ArrayList<>(candidates);
        ranked.sort(Comparator.comparing(CharacterEffect::chapterNo, Comparator.reverseOrder())
                .thenComparing(Comparator.comparingInt(CharacterStateProjector::storedOrder).reversed())
                .thenComparingInt(effect -> trajectoryRank(effect.kind())).thenComparing(CharacterEffect::effectId));
        List<String> diagnostics = snapshot.unresolvedIds().stream().sorted().map(id -> id + "=UNRESOLVED").toList();
        String header = "【人物选择后果轨迹·截至本章之前】\n已完成/失败/撤回是历史后果，不是未完成待办；信念不等于世界事实。"
                + "只呈现有记录的选择与后果，不据动作补写内心。\n";
        int chars = header.length() + trajectoryFooter(diagnostics.size(), ranked.size()).length();
        Set<String> selectedIds = new LinkedHashSet<>();
        List<String> omitted = new ArrayList<>();
        for (CharacterEffect effect : ranked) {
            String line = renderEvent(effect);
            if (selectedIds.size() >= Math.max(0, maxEvents) || maxChars > 0 && chars + line.length() > maxChars) {
                omitted.add(effect.effectId());
            } else {
                selectedIds.add(effect.effectId());
                chars += line.length();
            }
        }
        List<CharacterEffect> selected = candidates.stream().filter(e -> selectedIds.contains(e.effectId())).toList();
        StringBuilder text = new StringBuilder(header);
        selected.forEach(effect -> text.append(renderEvent(effect)));
        text.append(trajectoryFooter(diagnostics.size(), omitted.size()));
        String rendered = candidates.isEmpty() && diagnostics.isEmpty() || maxChars > 0 && text.length() > maxChars
                ? "" : text.toString();
        String hash = ChapterManuscript.digest(snapshot.hash() + "\ntrajectory-v1\n"
                + JSON.toJSONString(selected.stream().map(CharacterEffect::effectId).toList())
                + JSON.toJSONString(omitted) + JSON.toJSONString(diagnostics));
        return new Trajectory(List.copyOf(selected), List.copyOf(omitted), diagnostics, hash, rendered);
    }

    public static String renderTrajectory(List<ChapterSummaryEntity> summaries, Integer beforeChapter,
                                          Collection<String> cast, int maxEvents, int maxChars) {
        return trajectory(summaries, beforeChapter, cast, maxEvents, maxChars).text();
    }

    public static String renderTrajectory(List<ChapterSummaryEntity> summaries, Integer beforeChapter, Collection<String> cast) {
        return renderTrajectory(summaries, beforeChapter, cast, DEFAULT_MAX_FACTS, DEFAULT_MAX_CHARS);
    }

    private static int trajectoryRank(Kind kind) {
        return switch (kind) {
            case COMMITMENT, ACTION, DECISION -> 0;
            case INTENT, GOAL -> 1;
            default -> 2;
        };
    }

    private static String renderEvent(CharacterEffect effect) {
        EvidenceSupport support = effect.evidenceSupport();
        return "- 第" + effect.chapterNo() + "章 " + effect.effectId() + "｜" + effect.character() + "｜"
                + effect.kind() + '/' + effect.state() + "｜" + effect.key() + "：" + effect.value()
                + "；证据：" + effect.evidence()
                + (support == null ? "；旧记录归因未按v1复核" : "；顺序=" + support.order()
                    + (blank(support.scope()) ? "" : "；范围=" + support.scope())
                    + (blank(support.target()) ? "" : "；对象=" + support.target())
                    + (safe(support.supportRefs()).isEmpty() ? "" : "；支持事件=" + support.supportRefs()))
                + '\n';
    }

    private static String trajectoryFooter(int unresolved, int omitted) {
        return (unresolved == 0 ? "" : "UNRESOLVED " + unresolved + " 项：不得作为确定心理或已兑现结果。\n")
                + (omitted == 0 ? "" : "另有 " + omitted + " 条完整事件因预算省略，不代表失效。\n");
    }

    public static final int DEFAULT_MAX_FACTS = 12;
    public static final int DEFAULT_MAX_CHARS = 4000;

    private static boolean validCandidate(CharacterEffect effect, String content) {
        if (effect == null || blank(effect.character()) || effect.kind() == null || blank(effect.key())
                || effect.state() == null || blank(effect.value()) || blank(effect.evidence())
                || content == null || !content.contains(effect.evidence())) return false;
        if ((effect.kind() == Kind.KNOWLEDGE || effect.kind() == Kind.BELIEF)
                && (blank(effect.acquisition()) || !effect.evidence().contains(effect.character()))) return false;
        if (effect.kind() == Kind.ALIAS && (!effect.evidence().contains(effect.character())
                || !effect.evidence().contains(effect.key()))) return false;
        return allowed(effect.kind(), effect.state());
    }

    private static boolean allowed(Kind kind, State state) {
        return switch (kind) {
            case ALIAS -> state == State.ESTABLISHED;
            case KNOWLEDGE -> state == State.ESTABLISHED || state == State.FORGOTTEN;
            case BELIEF -> state == State.ESTABLISHED || state == State.SUSPECTED || state == State.WITHDRAWN;
            case GOAL -> state == State.ESTABLISHED || state == State.COMPLETED || state == State.FAILED || state == State.WITHDRAWN;
            case DECISION -> state == State.ESTABLISHED || state == State.WITHDRAWN;
            case INTENT -> state == State.INTENDED || state == State.WITHDRAWN;
            case ACTION -> state == State.ATTEMPTED || state == State.COMPLETED || state == State.FAILED;
            case COMMITMENT -> state == State.REQUESTED || state == State.ACCEPTED || state == State.COMPLETED
                    || state == State.FAILED || state == State.WITHDRAWN;
        };
    }

    private static boolean canApply(CharacterEffect effect, CharacterEffect prior) {
        if (effect.evidenceSupport() == null) return canApplyLegacy(effect, prior);
        if (prior != null && (!Objects.equals(effect.characterId(), prior.characterId())
                || effect.kind() != prior.kind() || !normalize(effect.key()).equals(normalize(prior.key())))) return false;
        if (prior == null) return switch (effect.kind()) {
            case ALIAS, KNOWLEDGE, BELIEF, GOAL, DECISION -> effect.state() == State.ESTABLISHED
                    || effect.kind() == Kind.BELIEF && effect.state() == State.SUSPECTED;
            case INTENT -> effect.state() == State.INTENDED;
            case ACTION -> effect.state() == State.ATTEMPTED || effect.state() == State.COMPLETED || effect.state() == State.FAILED;
            case COMMITMENT -> effect.state() == State.REQUESTED || effect.state() == State.ACCEPTED;
        };
        if (prior.state() == effect.state() && Objects.equals(prior.value(), effect.value())) return true;
        if (!Objects.equals(effect.referenceId(), prior.factId())) return false;
        return transitions(effect.kind(), prior.state()).contains(effect.state());
    }

    /**
     * v1 迁移表。终态不自动重开；重复同态同值是幂等重述，不是一次新承诺。
     * 重新接受/重试的语义暂不推断，应建立有独立 key 的新任务；旧无 metadata 按旧规则回放。
     */
    private static Set<State> transitions(Kind kind, State from) {
        return switch (kind) {
            case ALIAS -> Set.of();
            case KNOWLEDGE -> from == State.ESTABLISHED ? Set.of(State.ESTABLISHED, State.FORGOTTEN) : Set.of();
            case BELIEF -> from == State.ESTABLISHED || from == State.SUSPECTED
                    ? Set.of(State.ESTABLISHED, State.SUSPECTED, State.WITHDRAWN) : Set.of();
            case GOAL -> from == State.ESTABLISHED
                    ? Set.of(State.ESTABLISHED, State.COMPLETED, State.FAILED, State.WITHDRAWN) : Set.of();
            case DECISION -> from == State.ESTABLISHED ? Set.of(State.ESTABLISHED, State.WITHDRAWN) : Set.of();
            case INTENT -> from == State.INTENDED ? Set.of(State.INTENDED, State.WITHDRAWN) : Set.of();
            case ACTION -> from == State.ATTEMPTED ? Set.of(State.ATTEMPTED, State.COMPLETED, State.FAILED) : Set.of();
            case COMMITMENT -> from == State.REQUESTED ? Set.of(State.REQUESTED, State.ACCEPTED, State.WITHDRAWN)
                    : from == State.ACCEPTED ? Set.of(State.ACCEPTED, State.COMPLETED, State.FAILED, State.WITHDRAWN) : Set.of();
        };
    }

    private static boolean canApplyLegacy(CharacterEffect effect, CharacterEffect prior) {
        if (prior != null && (!Objects.equals(effect.characterId(), prior.characterId())
                || effect.kind() != prior.kind() || !normalize(effect.key()).equals(normalize(prior.key())))) return false;
        if (prior != null && prior.state() == effect.state() && Objects.equals(prior.value(), effect.value())) return true;
        if (effect.kind() == Kind.KNOWLEDGE && effect.state() == State.FORGOTTEN
                || effect.state() == State.WITHDRAWN
                || effect.kind() == Kind.COMMITMENT && (effect.state() == State.COMPLETED || effect.state() == State.FAILED)) {
            if (prior == null || !Objects.equals(effect.referenceId(), prior.factId())) return false;
            if (effect.kind() == Kind.COMMITMENT && prior.state() != State.ACCEPTED) return false;
        }
        return prior == null || Objects.equals(prior.value(), effect.value()) && prior.state() == effect.state()
                || Objects.equals(prior.factId(), effect.referenceId());
    }

    private static boolean validStored(CharacterEffect effect, ChapterSummaryEntity summary) {
        return effect != null && !blank(effect.character()) && effect.kind() != null && !blank(effect.key())
                && effect.state() != null && allowed(effect.kind(), effect.state()) && !blank(effect.value())
                && !blank(effect.evidence()) && validCandidate(effect, effect.evidence()) && !blank(effect.characterId())
                && Objects.equals(effect.chapterNo(), summary.getChapterNo())
                && Objects.equals(effect.manuscriptHash(), summary.getManuscriptHash())
                && Objects.equals(effect.factId(), factId(effect.characterId(), effect.kind(), effect.key()))
                && (effect.evidenceSupport() == null || validStoredSupport(effect))
                && Objects.equals(effect.effectId(), effectId(effect));
    }

    public static final String CONTEXT_BOUND_V1 = "CONTEXT_BOUND_V1";
    private static final int MAX_CONTEXT_CHARS = 600;
    private static final int MAX_SUPPORT_REFS = 8;

    /** 只有很窄的显式主语陈述可直绑；这不是通用心理归因/反讽/谎言识别器。 */
    private static EvidenceSupport bindSupport(CharacterEffect effect, String content) {
        EvidenceSupport support = effect.evidenceSupport();
        if (!validSupportShape(effect) || content == null) return null;
        int contextAt = uniqueIndex(content, support.context());
        int evidenceAt = uniqueIndex(support.context(), effect.evidence());
        if (contextAt < 0 || evidenceAt < 0 || !wholeContext(content, contextAt, support.context().length())) return null;
        // order、verification 不读取模型值：每次都由正文重新计算，使重复绑定字节稳定。
        return new EvidenceSupport(1, support.attribution(), normalize(effect.character()), support.context(),
                contextAt + evidenceAt, support.supportRefs(), support.scope(), support.target(), CONTEXT_BOUND_V1);
    }

    private static boolean validStoredSupport(CharacterEffect effect) {
        EvidenceSupport support = effect.evidenceSupport();
        return validSupportShape(effect) && support.order() != null && support.order() >= 0
                && CONTEXT_BOUND_V1.equals(support.verification());
    }

    private static boolean validSupportShape(CharacterEffect effect) {
        EvidenceSupport support = effect.evidenceSupport();
        if (support == null || !Integer.valueOf(1).equals(support.version())
                || support.attribution() != Attribution.EXPLICIT_NARRATION
                || !normalize(effect.character()).equals(normalize(support.subject()))
                || blank(support.context()) || support.context().length() > MAX_CONTEXT_CHARS
                || blank(effect.evidence()) || !support.context().contains(effect.evidence())
                || support.supportRefs() != null && (support.supportRefs().size() > MAX_SUPPORT_REFS
                    || support.supportRefs().stream().anyMatch(CharacterStateProjector::blank))
                || !blank(support.scope()) && !support.context().contains(support.scope())
                || !blank(support.target()) && !support.context().contains(support.target())) return false;
        return explicitStatement(effect, support.context());
    }

    private static boolean explicitStatement(CharacterEffect effect, String context) {
        String text = context.strip();
        String subject = normalize(effect.character());
        if (!text.startsWith(subject) || containsAny(text, "“", "”", "「", "」", "『", "』", "\"", "‘", "’", "'",
                "如果", "假如", "假设", "要是", "倘若", "据说", "听说", "转述", "声称", "撒谎", "谎称", "说谎",
                "佯装", "假装", "假话", "谣言", "声言", "传言", "开玩笑", "：", ":", "？", "?")) return false;
        String predicate = text.substring(subject.length()).stripLeading();
        // 当前快速通道只认一个完整显式陈述；不能在后续另一个主体的句子中拼出命题。
        int terminal = predicate.length();
        while (terminal > 0 && (Character.isWhitespace(predicate.charAt(terminal - 1))
                || sentenceBoundary(predicate.charAt(terminal - 1)))) terminal--;
        for (int i = 0; i < terminal; i++) if (sentenceBoundary(predicate.charAt(i))) return false;
        boolean knownForm = switch (effect.kind()) {
            case KNOWLEDGE -> effect.state() == State.FORGOTTEN
                    ? startsAny(predicate, "忘记", "已经忘记", "已忘记")
                    : startsAny(predicate, "知道", "已经知道", "已知道", "得知", "获知", "确认", "看见", "看到", "读到", "认出");
            case BELIEF -> effect.state() == State.WITHDRAWN ? startsAny(predicate, "不再相信", "不再怀疑", "放弃了原先的判断")
                    : effect.state() == State.SUSPECTED ? startsAny(predicate, "怀疑", "明确怀疑", "开始怀疑")
                    : startsAny(predicate, "相信", "确信", "以为", "认为", "明确相信", "明确认为");
            case GOAL -> effect.state() == State.WITHDRAWN ? startsAny(predicate, "放弃", "撤回")
                    : effect.state() == State.COMPLETED ? startsAny(predicate, "完成", "实现", "达成")
                    : effect.state() == State.FAILED ? startsAny(predicate, "未能完成", "未能实现", "没能完成")
                    : startsAny(predicate, "的目标是", "立志", "目标是");
            case DECISION -> effect.state() == State.WITHDRAWN ? startsAny(predicate, "撤回", "放弃", "取消")
                    : startsAny(predicate, "决定", "下定决心", "决意");
            case INTENT -> effect.state() == State.WITHDRAWN ? startsAny(predicate, "不再打算", "放弃", "取消")
                    : startsAny(predicate, "打算", "准备", "计划", "想要");
            case ACTION -> effect.state() == State.ATTEMPTED ? startsAny(predicate, "尝试", "试着", "开始尝试")
                    : effect.state() == State.COMPLETED ? startsAny(predicate, "完成", "已经完成", "已完成")
                    : startsAny(predicate, "未能完成", "没能完成", "尝试失败");
            case COMMITMENT -> switch (effect.state()) {
                case REQUESTED -> startsAny(predicate, "被要求", "收到请求", "接到请求");
                case ACCEPTED -> startsAny(predicate, "答应", "承诺", "同意", "接受了");
                case COMPLETED -> startsAny(predicate, "完成", "已经完成", "履行", "兑现");
                case FAILED -> startsAny(predicate, "未能完成", "没能完成", "未能履行");
                case WITHDRAWN -> startsAny(predicate, "撤回", "取消", "放弃", "拒绝");
                default -> false;
            };
            case ALIAS -> startsAny(predicate, "就是", "又名", "原名", "的别名是");
        };
        // 直接绑定还要求完整命题（value）就在显式谓词后，不做转义/蕴含/心理补全。
        return knownForm && directValueFollows(predicate, effect.value(), effect.state());
    }

    /** 有界显式前缀白名单；名单只决定哪些表达值得直绑，不做词向量或心理推断。 */
    private static final List<String> EXPLICIT_PREFIXES = List.of(
            "放弃了原先的判断", "开始尝试", "已经忘记", "已经知道", "已经完成", "明确怀疑", "开始怀疑",
            "明确相信", "明确认为", "不再相信", "不再怀疑", "未能完成", "未能实现", "没能完成", "未能履行",
            "不再打算", "尝试失败", "收到请求", "接到请求", "的别名是", "的目标是", "下定决心", "接受了",
            "已忘记", "已知道", "已完成", "目标是", "被要求", "忘记", "知道", "得知", "获知", "确认",
            "看见", "看到", "读到", "认出", "怀疑", "相信", "确信", "以为", "认为", "放弃", "撤回",
            "完成", "实现", "达成", "立志", "取消", "决定", "决意", "打算", "准备", "计划", "想要",
            "尝试", "试着", "答应", "承诺", "同意", "履行", "兑现", "拒绝", "就是", "又名", "原名");

    private static boolean directValueFollows(String predicate, String value, State state) {
        String prefix = EXPLICIT_PREFIXES.stream().filter(predicate::startsWith)
                .max(Comparator.comparingInt(String::length)).orElse(null);
        if (prefix == null) return false;
        String remainder = predicate.substring(prefix.length()).strip();
        String claim = value.strip();
        if (!remainder.startsWith(claim)) return false;
        String tail = remainder.substring(claim.length()).strip();
        while (!tail.isEmpty() && sentenceBoundary(tail.charAt(tail.length() - 1))) tail = tail.substring(0, tail.length() - 1).stripTrailing();
        return tail.isEmpty() || state == State.WITHDRAWN && (tail.equals("的承诺") || tail.equals("的决定") || tail.equals("的计划"));
    }

    private static boolean startsAny(String text, String... prefixes) {
        for (String prefix : prefixes) if (text.startsWith(prefix)) return true;
        return false;
    }

    private static boolean containsAny(String text, String... fragments) {
        for (String fragment : fragments) if (text.contains(fragment)) return true;
        return false;
    }

    private static int uniqueIndex(String text, String part) {
        if (blank(part)) return -1;
        int at = text.indexOf(part);
        return at >= 0 && text.indexOf(part, at + 1) < 0 ? at : -1;
    }

    private static boolean wholeContext(String content, int start, int length) {
        int before = start - 1;
        while (before >= 0 && Character.isWhitespace(content.charAt(before))) before--;
        if (before >= 0 && !sentenceBoundary(content.charAt(before))) return false;
        int end = start + length;
        if (end >= content.length()) return true;
        char last = content.charAt(end - 1);
        return sentenceBoundary(last);
    }

    private static boolean sentenceBoundary(char value) {
        return value == '。' || value == '！' || value == '？' || value == '!' || value == '?' || value == '.'
                || value == '\n' || value == '\r';
    }

    private static boolean validSupportRefs(CharacterEffect effect, Map<String, CharacterEffect> events,
                                             Set<String> unresolved) {
        if (effect.evidenceSupport() == null) return true;
        for (String id : safe(effect.evidenceSupport().supportRefs())) {
            CharacterEffect prior = events.get(id);
            if (prior == null || unresolved.contains(prior.factId()) || prior.chapterNo() > effect.chapterNo()
                    || prior.chapterNo().equals(effect.chapterNo()) && (prior.evidenceSupport() == null
                        || prior.evidenceSupport().order() >= effect.evidenceSupport().order())) return false;
        }
        return true;
    }

    private static List<ChapterSummaryEntity> history(List<ChapterSummaryEntity> summaries, Integer beforeChapter) {
        if (summaries == null) return List.of();
        return summaries.stream().filter(Objects::nonNull).filter(summary -> summary.getChapterNo() != null
                && summary.getChapterNo() > 0 && (beforeChapter == null || summary.getChapterNo() < beforeChapter))
                .sorted(Comparator.comparing(ChapterSummaryEntity::getChapterNo)).toList();
    }

    public static String characterId(String name) { return "character-" + ChapterManuscript.digest(normalize(name)); }
    private static String factId(String characterId, Kind kind, String key) {
        return "character-fact-" + ChapterManuscript.digest(characterId + "\n" + kind + "\n" + normalize(key));
    }
    private static String effectId(CharacterEffect effect) {
        String legacy = JSON.toJSONString(Arrays.asList(effect.factId(), effect.chapterNo(), effect.manuscriptHash(),
                effect.state(), effect.value(), effect.evidence(), effect.acquisition(), effect.referenceId(), effect.character()));
        if (effect.evidenceSupport() == null) return "character-effect-" + ChapterManuscript.digest(legacy);
        // null 旧算法字节不变；v1 对支持信息做摘要，偏移/主体/上下文/refs 任一被改都会失配。
        return "character-effect-" + ChapterManuscript.digest(legacy + "\nevidence-support-v1\n"
                + ChapterManuscript.digest(JSON.toJSONString(effect.evidenceSupport())));
    }
    private static <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }
    private static String normalize(String text) { return text == null ? "" : text.strip(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
