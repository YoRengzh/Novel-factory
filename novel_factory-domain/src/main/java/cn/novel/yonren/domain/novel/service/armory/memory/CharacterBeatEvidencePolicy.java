package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity.*;
import cn.novel.yonren.domain.novel.model.valobj.character.CharacterEffect;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;

import java.util.*;

/**
 * 人物 beat 的字段级观察边界。引用定位不等于语义证明；本类绝不生成 CharacterEffect。
 * 非逐字概括可按 INTERPRETATION 留作明确条件观察，而不能升级成获知/完成事实。
 */
public final class CharacterBeatEvidencePolicy {
    public static final int SCHEMA_VERSION = 1;
    public static final String LOCATED = "BEAT_SOURCE_LOCATED_V1";
    private static final int MAX_QUOTE = 400;
    private static final int MAX_CONTEXT = 1200;
    private static final int MAX_VALUE = 400;
    private static final int MAX_REFS = 8;

    private CharacterBeatEvidencePolicy() { }

    public record FieldObservation(BeatField field, String value, String label, BeatFieldSupport support) { }

    /** 只调用于新抽取，不回写旧书；先定位 quote，effect refs 留给 State.bind 后复核。 */
    public static void sanitizeNewObservations(ChapterSummaryEntity summary, ChapterManuscript manuscript) {
        process(summary, manuscript, List.of(), true);
    }

    /** 在 CharacterStateProjector.bind 之后调用；隔离字段永不自动重试/清空。 */
    public static void bind(ChapterSummaryEntity summary, ChapterManuscript manuscript,
                            List<ChapterSummaryEntity> history) {
        process(summary, manuscript, history, false);
    }

    private static void process(ChapterSummaryEntity summary, ChapterManuscript manuscript,
                                List<ChapterSummaryEntity> history, boolean preparing) {
        if (summary == null || summary.getCharacterBeats() == null) return;
        Objects.requireNonNull(manuscript, "manuscript");
        Map<String, CharacterEffect> effects = preparing ? Map.of() : confirmedEffects(summary, history);
        for (CharacterBeat beat : summary.getCharacterBeats()) {
            if (beat == null) continue;
            // bind 不重写旧观察。新生成缺支持必须由 sanitize 显式标成 v1，而非 legacy。
            if (!preparing && legacy(beat)) continue;
            beat.setSchemaVersion(SCHEMA_VERSION);
            beat.setChapterNo(manuscript.chapterNo());
            beat.setManuscriptHash(manuscript.hash());
            Map<BeatField, BeatFieldSupport> bound = new LinkedHashMap<>();
            Map<BeatField, PendingBeatField> pending = beat.getPendingFields() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(beat.getPendingFields());
            for (BeatField field : BeatField.values()) {
                String value = value(beat, field);
                if (blank(value)) continue;
                BeatFieldSupport input = beat.getSupport() == null ? null : beat.getSupport().get(field);
                String reason = shapeReason(input, value);
                BeatFieldSupport located = reason == null ? locate(input, manuscript.content()) : null;
                if (reason == null && located == null) reason = "QUOTE_OR_CONTEXT_NOT_CONTIGUOUS";
                if (reason == null && !preparing && !validRefs(located, effects)) reason = "EFFECT_REF_NOT_CONFIRMED_OR_SOURCE_MISMATCH";
                if (reason == null && !literalOrConditional(field, value, located, effects, preparing)) reason = "CLAIM_NOT_LITERAL_USE_CONDITIONAL_INTERPRETATION";
                if (reason == null && explicitOtherSubject(summary, beat, field, located, history)) reason = "EXPLICIT_OTHER_SUBJECT";
                if (reason == null && !preparing && !ownRefs(beat, field, located, effects, summary, history)) reason = "EFFECT_REF_SUBJECT_MISMATCH";
                if (reason != null) {
                    // 原值和原支持留作诊断，重复绑定不覆盖既有 pending。
                    pending.putIfAbsent(field, new PendingBeatField(value, input, reason));
                    setValue(beat, field, null);
                } else {
                    bound.put(field, located);
                }
            }
            beat.setSupport(bound.isEmpty() ? null : bound);
            beat.setPendingFields(pending.isEmpty() ? null : pending);
        }
    }

    private static String shapeReason(BeatFieldSupport support, String value) {
        if (value.length() > MAX_VALUE) return "OBSERVATION_TOO_LONG";
        if (support == null || support.attribution() == null) return "MISSING_FIELD_SUPPORT";
        boolean hasQuote = !blank(support.quote());
        boolean hasRefs = support.effectRefs() != null && !support.effectRefs().isEmpty();
        if (!hasQuote && !hasRefs) return "MISSING_QUOTE_OR_EFFECT_REF";
        if (hasQuote && support.quote().length() > MAX_QUOTE || support.context() != null && support.context().length() > MAX_CONTEXT)
            return "SUPPORT_TOO_LONG";
        if (!blank(support.context()) && (!hasQuote || !support.context().contains(support.quote()))) return "CONTEXT_MUST_CONTAIN_QUOTE";
        if (support.attribution() == BeatAttribution.INTERPRETATION && hasQuote && blank(support.context())) return "INTERPRETATION_NEEDS_CONTEXT";
        if (hasRefs && (support.effectRefs().size() > MAX_REFS || support.effectRefs().stream().anyMatch(ref -> ref == null
                || blank(ref.effectId()) || ref.chapterNo() == null || ref.chapterNo() <= 0 || !hash(ref.manuscriptHash()))))
            return "INVALID_EFFECT_REF";
        return null;
    }

    private static BeatFieldSupport locate(BeatFieldSupport support, String content) {
        Integer from = null;
        Integer to = null;
        if (!blank(support.quote())) {
            String anchor = blank(support.context()) ? support.quote() : support.context();
            int contextAt = uniqueIndex(content, anchor);
            int quoteAt = uniqueIndex(anchor, support.quote());
            if (contextAt < 0 || quoteAt < 0) return null;
            from = contextAt + quoteAt;
            to = from + support.quote().length();
        }
        // 忽略模型回填的偏移与 verification；根据实际输入重新定位。
        return new BeatFieldSupport(support.attribution(), support.quote(), support.context(),
                support.effectRefs(), from, to, LOCATED);
    }

    private static boolean literalOrConditional(BeatField field, String value, BeatFieldSupport support,
                                                Map<String, CharacterEffect> effects, boolean preparing) {
        if (conditional(support.attribution())) return true;
        if (support.attribution() == BeatAttribution.DIRECT_STATEMENT && ownField(field)
                && !blank(support.quote()) && quotedSpeech(support.quote())) return true;
        // 词面保留只是无损摘录，不宣称做过语义归因验证；发言内容仍不证明履行。
        if (!blank(support.quote()) && (field == BeatField.consequence || field == BeatField.relationshipChange
                ? trimTerminal(support.quote()).equals(trimTerminal(value)) : support.quote().contains(value))) return true;
        if (support.effectRefs() == null || support.effectRefs().isEmpty()) return false;
        // 摘要阶段 refs 尚未绑定；正式 bind 必须来自 confirmed 集且文本相同，不以动作 ref 推出送达。
        return preparing || support.effectRefs().stream().map(ref -> effects.get(ref.effectId()))
                .filter(Objects::nonNull).anyMatch(effect -> Objects.equals(value, effect.value()));
    }

    private static boolean ownField(BeatField field) {
        return field == BeatField.goal || field == BeatField.decision || field == BeatField.nextIntent;
    }

    private static boolean ownRefs(CharacterBeat beat, BeatField field, BeatFieldSupport support,
                                    Map<String, CharacterEffect> effects, ChapterSummaryEntity summary,
                                    List<ChapterSummaryEntity> history) {
        if (!ownField(field) || support.effectRefs() == null || support.effectRefs().isEmpty()) return true;
        List<ChapterSummaryEntity> sources = new ArrayList<>();
        if (history != null) history.stream().filter(Objects::nonNull)
                .filter(item -> item.getChapterNo() != null && item.getChapterNo() < summary.getChapterNo()).forEach(sources::add);
        sources.add(summary);
        String identity = CharacterStateProjector.project(sources, null).identity(beat.getName());
        return support.effectRefs().stream().map(ref -> effects.get(ref.effectId())).allMatch(effect -> effect != null
                && identity.equals(effect.characterId()));
    }

    /** 仅挡已知其他人物的显式句首主体错配；不以缺姓名/心理谓词排除代词、动作或上下文。 */
    private static boolean explicitOtherSubject(ChapterSummaryEntity summary, CharacterBeat beat, BeatField field,
                                                BeatFieldSupport support, List<ChapterSummaryEntity> history) {
        if (!ownField(field) || blank(support.quote())) return false;
        Set<String> names = new LinkedHashSet<>();
        collectNames(summary, names);
        if (history != null) history.stream().filter(Objects::nonNull)
                .filter(item -> summary.getChapterNo() != null && item.getChapterNo() != null
                        && item.getChapterNo() < summary.getChapterNo()).forEach(item -> collectNames(item, names));
        String quote = support.quote().stripLeading();
        if (quote.startsWith(beat.getName())) return false;
        return names.stream().filter(name -> !Objects.equals(name, beat.getName())).anyMatch(quote::startsWith);
    }

    private static void collectNames(ChapterSummaryEntity summary, Set<String> names) {
        if (summary == null) return;
        if (summary.getCharacterBeats() != null) summary.getCharacterBeats().stream().filter(Objects::nonNull)
                .map(CharacterBeat::getName).filter(name -> !blank(name)).forEach(names::add);
        if (summary.getCharacterStates() != null) summary.getCharacterStates().stream().filter(Objects::nonNull)
                .map(StateEntry::getName).filter(name -> !blank(name)).forEach(names::add);
        if (summary.getCharacterEffects() != null) summary.getCharacterEffects().stream().filter(Objects::nonNull)
                .map(CharacterEffect::character).filter(name -> !blank(name)).forEach(names::add);
    }

    private static boolean validRefs(BeatFieldSupport support, Map<String, CharacterEffect> effects) {
        if (support.effectRefs() == null) return true;
        return support.effectRefs().stream().allMatch(ref -> {
            CharacterEffect effect = effects.get(ref.effectId());
            return effect != null && Objects.equals(ref.chapterNo(), effect.chapterNo())
                    && Objects.equals(ref.manuscriptHash(), effect.manuscriptHash());
        });
    }

    /** 已确认事实是唯一 refs 来源；pending/失效稿件/重复章号不进入此表。 */
    private static Map<String, CharacterEffect> confirmedEffects(ChapterSummaryEntity summary,
                                                                List<ChapterSummaryEntity> history) {
        if (summary == null || summary.getChapterNo() == null) return Map.of();
        List<ChapterSummaryEntity> sources = new ArrayList<>();
        if (history != null) history.stream().filter(Objects::nonNull)
                .filter(item -> item.getChapterNo() != null && item.getChapterNo() < summary.getChapterNo()).forEach(sources::add);
        sources.add(summary);
        var snapshot = CharacterStateProjector.project(sources, null);
        Map<String, CharacterEffect> effects = new LinkedHashMap<>();
        snapshot.facts().forEach((id, effect) -> {
            if (!snapshot.unresolvedIds().contains(id)) effects.put(effect.effectId(), effect);
        });
        // 同一事项旧的已确认事件仍可支持观察，不因后续状态替换而丢失历史来源。
        CharacterStateProjector.trajectory(sources, null, null, Integer.MAX_VALUE, 0).events()
                .forEach(effect -> effects.put(effect.effectId(), effect));
        return effects;
    }

    /** 无正文的读盘投影仅检查绑定来源/形状/refs，绝不声称已复核正文语义。 */
    public static List<FieldObservation> observations(ChapterSummaryEntity summary, CharacterBeat beat,
                                                       List<ChapterSummaryEntity> history) {
        if (beat == null || blank(beat.getName())) return List.of();
        List<FieldObservation> observations = new ArrayList<>();
        if (legacy(beat)) {
            for (BeatField field : BeatField.values()) {
                if (!blank(value(beat, field))) observations.add(new FieldObservation(field, value(beat, field),
                        "旧观察·未逐项复核，非确认事实", null));
            }
            return observations;
        }
        if (!Integer.valueOf(SCHEMA_VERSION).equals(beat.getSchemaVersion()) || summary == null
                || !Objects.equals(beat.getChapterNo(), summary.getChapterNo())
                || !hash(beat.getManuscriptHash()) || !Objects.equals(beat.getManuscriptHash(), summary.getManuscriptHash())) return List.of();
        Map<String, CharacterEffect> effects = confirmedEffects(summary, history);
        for (BeatField field : BeatField.values()) {
            String value = value(beat, field);
            BeatFieldSupport support = beat.getSupport() == null ? null : beat.getSupport().get(field);
            if (blank(value) || shapeReason(support, value) != null || !LOCATED.equals(support.verification())
                    || !storedPosition(support) || !validRefs(support, effects)
                    || !literalOrConditional(field, value, support, effects, false)
                    || explicitOtherSubject(summary, beat, field, support, history)
                    || !ownRefs(beat, field, support, effects, summary, history)) continue;
            String label = switch (support.attribution()) {
                case OBSERVABLE -> "原文定位观察·非语义证明";
                case DIRECT_STATEMENT -> "直接发言内容·不证实兑现或真伪";
                case INTERPRETATION -> "可能（上下文解释，未证实）";
                case REPORTED -> "转述观察·未证实";
                case HYPOTHETICAL -> "假设观察·未发生";
                case AMBIGUOUS -> "归因不明观察·未证实";
            };
            observations.add(new FieldObservation(field, value, label, support));
        }
        return observations;
    }

    private static boolean storedPosition(BeatFieldSupport support) {
        if (blank(support.quote())) return support.quoteStart() == null && support.quoteEnd() == null;
        return support.quoteStart() != null && support.quoteStart() >= 0 && support.quoteEnd() != null
                && support.quoteEnd() - support.quoteStart() == support.quote().length();
    }

    /** 有正文时逐字段重查位置，模型/旧盘上的 verification 标记本身没有授权效力。 */
    public static List<FieldObservation> observations(ChapterSummaryEntity summary, CharacterBeat beat,
                                                       ChapterManuscript manuscript, List<ChapterSummaryEntity> history) {
        if (manuscript == null || summary == null || !Objects.equals(summary.getChapterNo(), manuscript.chapterNo())
                || !Objects.equals(summary.getManuscriptHash(), manuscript.hash())) return List.of();
        return observations(summary, beat, history).stream().filter(observation -> {
            if (observation.support() == null) return false;
            BeatFieldSupport located = locate(observation.support(), manuscript.content());
            return located != null && located.equals(observation.support());
        }).toList();
    }

    public static List<cn.novel.yonren.domain.novel.model.valobj.character.CharacterDepthReview.SourceRef> sourceRefs(
            ChapterSummaryEntity summary, FieldObservation observation, List<ChapterSummaryEntity> history) {
        var support = observation.support();
        if (support == null) return List.of();
        List<cn.novel.yonren.domain.novel.model.valobj.character.CharacterDepthReview.SourceRef> sources = new ArrayList<>();
        if (!blank(support.quote())) sources.add(new cn.novel.yonren.domain.novel.model.valobj.character.CharacterDepthReview.SourceRef(
                summary.getChapterNo(), summary.getManuscriptHash(), support.quote()));
        Map<String, CharacterEffect> effects = confirmedEffects(summary, history);
        if (support.effectRefs() != null) for (BeatEffectRef ref : support.effectRefs()) {
            var effect = effects.get(ref.effectId());
            if (effect != null && Objects.equals(ref.chapterNo(), effect.chapterNo()) && Objects.equals(ref.manuscriptHash(), effect.manuscriptHash()))
                sources.add(new cn.novel.yonren.domain.novel.model.valobj.character.CharacterDepthReview.SourceRef(
                        effect.chapterNo(), effect.manuscriptHash(), effect.evidence()));
        }
        return sources.stream().distinct().toList();
    }

    public static String renderFields(ChapterSummaryEntity summary, CharacterBeat beat, List<ChapterSummaryEntity> history) {
        StringBuilder text = new StringBuilder();
        for (FieldObservation observation : observations(summary, beat, history)) {
            text.append("｜").append(fieldLabel(observation.field())).append("[").append(observation.label()).append("]：")
                    .append(observation.value().replace('\n', '；').replace('\r', '；'));
            // 不丢掉原句中的限定/否定/发言归属；截取词面不能把整句的可能性抹成确定结果。
            if (observation.support() != null && !blank(observation.support().quote())) {
                String source = blank(observation.support().context()) ? observation.support().quote() : observation.support().context();
                text.append("（连续来源：").append(source.replace('\n', '；').replace('\r', '；')).append("）");
            }
        }
        return text.toString();
    }

    public static String fieldLabel(BeatField field) {
        return switch (field) {
            case goal -> "目标";
            case decision -> "决定/动作";
            case consequence -> "后果";
            case relationshipChange -> "关系变化";
            case nextIntent -> "后续意图";
        };
    }

    private static boolean legacy(CharacterBeat beat) {
        return beat.getSchemaVersion() == null && beat.getSupport() == null && beat.getPendingFields() == null
                && beat.getChapterNo() == null && beat.getManuscriptHash() == null;
    }

    private static boolean conditional(BeatAttribution attribution) {
        return attribution == BeatAttribution.INTERPRETATION || attribution == BeatAttribution.REPORTED
                || attribution == BeatAttribution.HYPOTHETICAL || attribution == BeatAttribution.AMBIGUOUS;
    }

    private static int uniqueIndex(String text, String part) {
        if (text == null || blank(part)) return -1;
        int at = text.indexOf(part);
        return at >= 0 && text.indexOf(part, at + 1) < 0 ? at : -1;
    }

    /** 仅核对直接引语的呈现形状，不据引号判定发言可靠、承诺被接受或事情已发生。 */
    private static boolean quotedSpeech(String quote) {
        return paired(quote, '“', '”') || paired(quote, '「', '」') || paired(quote, '『', '』')
                || paired(quote, '"', '"') || paired(quote, '‘', '’');
    }

    private static boolean paired(String text, char opening, char closing) {
        int from = text.indexOf(opening);
        return from >= 0 && text.indexOf(closing, from + 1) > from + 1;
    }

    private static String trimTerminal(String value) {
        String text = value.strip();
        while (!text.isEmpty() && "。！？.!?".indexOf(text.charAt(text.length() - 1)) >= 0)
            text = text.substring(0, text.length() - 1).stripTrailing();
        return text;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean hash(String value) { return value != null && value.matches("[a-fA-F0-9]{64}"); }

    private static String value(CharacterBeat beat, BeatField field) {
        return switch (field) {
            case goal -> beat.getGoal();
            case decision -> beat.getDecision();
            case consequence -> beat.getConsequence();
            case relationshipChange -> beat.getRelationshipChange();
            case nextIntent -> beat.getNextIntent();
        };
    }

    private static void setValue(CharacterBeat beat, BeatField field, String value) {
        switch (field) {
            case goal -> beat.setGoal(value);
            case decision -> beat.setDecision(value);
            case consequence -> beat.setConsequence(value);
            case relationshipChange -> beat.setRelationshipChange(value);
            case nextIntent -> beat.setNextIntent(value);
        }
    }
}
