package cn.novel.yonren.domain.novel.service.armory.authoring;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.valobj.authoring.*;
import cn.novel.yonren.domain.novel.model.valobj.authoring.StoryAuthoringSnapshot.*;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import cn.novel.yonren.types.utils.StoryBibleParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** 纯本地作者维护策略：不读写磁盘，不调用模型，不改正文或摘要，不把未来意图变成事实。 */
public final class StoryAuthoringService {
    public static final List<String> SETTING_KEYS = List.of("novel_title", "theme", "style", "worldSetting",
            "perspective", "targetAudience", "tone", "protagonist", "outline", "chapterGoal", "characterProfiles");
    public static final int MAX_ENTRIES = 128;
    public static final int MAX_SETTING_CHARS = 32768;
    public static final int MAX_ENTRY_CHARS = 4096;
    public static final int MAX_TOTAL_CHARS = 262144;
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String HASH_PATTERN = "[0-9a-f]{64}";

    private StoryAuthoringService() { }

    /** 只解析白名单；未知 bible 段落及原始文本完全不参与写回。 */
    public static Map<String, String> mergedSettings(String bible, StoryAuthoringSnapshot snapshot) {
        var parsed = StoryBibleParser.parseDiagnostic(bible);
        if (parsed.hasAmbiguities()) throw new IllegalStateException("原始故事设定存在重复标签或歧义，请先核对原文");
        var base = parsed.snapshot();
        Map<String, String> result = new LinkedHashMap<>();
        result.put("novel_title", base.novelTitle());
        result.put("theme", base.theme());
        result.put("style", base.style());
        result.put("worldSetting", base.worldSetting());
        result.put("perspective", base.perspective());
        result.put("targetAudience", base.targetAudience());
        result.put("tone", base.tone());
        result.put("protagonist", base.protagonist());
        result.put("outline", base.outline());
        result.put("chapterGoal", base.chapterGoal());
        result.put("characterProfiles", base.characters());
        if (snapshot != null) {
            validatePersisted(snapshot);
            result.putAll(snapshot.settingsOverrides());
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    /** 执行侧仅传激活引用，绝不传作者工作副本；章节数量、模型配置及原 bible 不受影响。 */
    public static void applySettings(StoryContextEntity target, StoryAuthoringSnapshot active) {
        if (target == null || active == null) return;
        validatePersisted(active);
        active.settingsOverrides().forEach((key, value) -> {
            switch (key) {
                case "novel_title" -> target.setNovel_title(value);
                case "theme" -> target.setTheme(value);
                case "style" -> target.setStyle(value);
                case "worldSetting" -> target.setWorldSetting(value);
                case "perspective" -> target.setPerspective(value);
                case "targetAudience" -> target.setTargetAudience(value);
                case "tone" -> target.setTone(value);
                case "protagonist" -> target.setProtagonist(value);
                case "outline" -> target.setOutline(value);
                case "chapterGoal" -> target.setChapterGoal(value);
                case "characterProfiles" -> target.setCharacterProfiles(value);
                default -> throw new IllegalArgumentException("不允许的作者设定字段");
            }
        });
    }

    /** 将当前章节范围内的已激活作者条目带入生成提示词。 */
    public static String renderGuidance(StoryContextEntity context, int startChapter, int endChapter) {
        var active = context == null ? null : context.getStoryAuthoring();
        if (active == null || endChapter < active.effectiveFromChapter()) {
            return "";
        }
        StringBuilder guidance = new StringBuilder();
        for (Entry entry : active.entries()) {
            int fromChapter = Math.max(active.effectiveFromChapter(), entry.effectiveFromChapter());
            if (fromChapter > endChapter) {
                continue;
            }
            if (guidance.isEmpty()) {
                guidance.append("\n\n【作者维护】\n")
                        .append("有据纠错优先于冲突的自动提取。未来意图只约束生效章及后续计划，须由正文演出才成为已发生事实。\n");
            }
            guidance.append("- 第 ").append(Math.max(startChapter, fromChapter)).append(" 章起 ")
                    .append(entry.kind()).append(' ').append(entry.entityName());
            if (entry.operation() == Operation.EXTRACTION_CORRECTION) {
                guidance.append(" · 提取纠错（来源第 ").append(entry.sourceChapter()).append(" 章）：");
            } else {
                guidance.append(" · 未来意图：");
            }
            guidance.append(entry.after()).append('\n');
        }
        return guidance.toString();
    }

    public static String characterDepthMode(StoryContextEntity context) {
        var active = context == null ? null : context.getStoryAuthoring();
        return active == null ? null : active.characterDepthMode();
    }

    public static String renderCharacterDecisions(String trajectory) {
        return "\n\n【人物决策设计】\n让参与本章的关键人物基于已有认知、目标和承诺做出可观察的选择，"
                + "并写出该选择的代价或后果。保留人物之间的信息差，不凭空补造既有动机或承诺。\n"
                + trajectory;
    }

    /** 输入为锁内同一批只读状态；preview 的 revision 可重复计算，PUT 不另选一份候选。 */
    public static StoryAuthoringPreview preview(StoryAuthoringView current, StoryAuthoringPatch patch,
                                                String bible, Map<Integer, ChapterManuscript> chapters,
                                                String freezeHash) {
        if (patch == null) throw new IllegalArgumentException("必须提供作者维护请求");
        if (patch.expectedBasis() == null || !patch.expectedBasis().matches(HASH_PATTERN)) {
            throw new IllegalArgumentException("必须提供有效 expectedBasis");
        }
        if (!Objects.equals(current.revision(), patch.expectedRevision())
                || !Objects.equals(current.basis(), patch.expectedBasis())) {
            throw new IllegalStateException("作者版本或故事依据已变化，请重新读取和预览");
        }
        Map<String, String> settings = new LinkedHashMap<>(current.snapshot() == null
                ? Map.of() : current.snapshot().settingsOverrides());
        if (patch.settings() != null) {
            for (var field : patch.settings().entrySet()) {
                requireSettingKey(field.getKey());
                if (field.getValue() != null) settings.put(field.getKey(), normalizeSetting(field.getKey(), field.getValue()));
            }
        }
        List<Entry> requested = patch.entries() == null
                ? current.snapshot() == null ? List.of() : current.snapshot().entries() : patch.entries();
        if (requested.size() > MAX_ENTRIES) throw new IllegalArgumentException("作者条目不能超过 " + MAX_ENTRIES);
        List<Entry> entries = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (Entry raw : requested) {
            Entry bound;
            if (patch.entries() == null) {
                // null 不改条目本身；纠正仍须复查来源，不能顺带重基已经过期的旧纠正。
                if (raw.operation() == Operation.EXTRACTION_CORRECTION) bindEntry(raw, current, chapters);
                bound = raw;
            } else {
                bound = bindEntry(raw, current, chapters);
            }
            if (!identities.add(bound.id())) throw new IllegalStateException("同一来源/对象的作者条目重复，请合并后重新预览");
            entries.add(bound);
        }
        String mode = patch.characterDepthMode() == null
                ? current.snapshot() == null ? null : current.snapshot().characterDepthMode()
                : normalizeDepthMode(patch.characterDepthMode());
        var unsigned = new StoryAuthoringSnapshot(StoryAuthoringSnapshot.SCHEMA_VERSION, null, null,
                current.effectiveFromChapter(), settings, entries, mode);
        String hash = contentHash(unsigned);
        String seed = canonical("authoring-preview-v1", current.revision(), current.basis(), freezeHash, hash);
        String revision = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
        var candidate = new StoryAuthoringSnapshot(unsigned.schemaVersion(), revision, hash,
                unsigned.effectiveFromChapter(), settings, entries, mode);
        validatePersisted(candidate);
        // 同时验证未被覆写的 bible 字段可无歧义合并，不序列化或覆盖原始 bible。
        mergedSettings(bible, candidate);
        return new StoryAuthoringPreview(current, candidate, ChapterManuscript.digest(seed));
    }

    private static Entry bindEntry(Entry raw, StoryAuthoringView view, Map<Integer, ChapterManuscript> chapters) {
        if (raw == null || raw.kind() == null || raw.operation() == null) {
            throw new IllegalArgumentException("作者条目必须提供 kind/operation");
        }
        String name = required(raw.entityName(), 200, "entityName");
        if (name.contains("\n") || name.contains("\t")) throw new IllegalArgumentException("entityName 必须为单行");
        String after = required(raw.after(), MAX_ENTRY_CHARS, "after");
        String reason = required(raw.reason(), MAX_ENTRY_CHARS, "reason");
        if (raw.kind() == Kind.FORESHADOW && isPayoffClaim(after)) {
            throw new IllegalArgumentException("作者维护不能直接宣布伏笔 PAID/已兑现；只能写未来安排或有据摘录");
        }
        String before;
        Integer chapter;
        String hash;
        String evidence;
        Status status;
        int effective = view.effectiveFromChapter();
        if (raw.operation() == Operation.FUTURE_INTENT) {
            Entry unchanged = view.snapshot() == null ? null : view.snapshot().entries().stream()
                    .filter(previous -> previous.operation() == Operation.FUTURE_INTENT && previous.kind() == raw.kind()
                            && previous.entityName().equals(name) && previous.after().equals(after)
                            && previous.reason().equals(reason) && previous.effectiveFromChapter() == raw.effectiveFromChapter())
                    .findFirst().orElse(null);
            if (raw.effectiveFromChapter() < 0 || raw.effectiveFromChapter() > 0
                    && raw.effectiveFromChapter() < view.effectiveFromChapter() && unchanged == null) {
                throw new IllegalArgumentException("新建或修改的未来意图生效章不得早于下一个未提交章");
            }
            if (raw.effectiveFromChapter() > 0) effective = raw.effectiveFromChapter();
            // 请求里的伪来源、伪 before 和状态全部丢弃，未来设想不是当前事实。
            before = null;
            chapter = null;
            hash = null;
            evidence = null;
            status = Status.INTENT;
        } else {
            StoryAuthoringSource source = view.sources().stream()
                    .filter(item -> item.kind() == raw.kind() && item.entityName().equals(name)).findFirst().orElse(null);
            if (source == null || source.sourceHash() == null || raw.sourceChapter() == null
                    || source.sourceChapter() != raw.sourceChapter()
                    || !Objects.equals(source.sourceHash(), raw.sourceHash())
                    || !Objects.equals(source.currentState(), raw.before())) {
                throw new IllegalStateException("纠正来源的最新状态、正文 hash 或版本已变化，请重新读取后预览");
            }
            ChapterManuscript manuscript = chapters.get(source.sourceChapter());
            if (manuscript == null || !manuscript.hash().equals(source.sourceHash())) {
                throw new IllegalStateException("纠正来源正文已经变化或缺失");
            }
            evidence = required(raw.evidence(), MAX_ENTRY_CHARS, "evidence");
            // 引文存在不证明任意 after；after 自身也须为含对象名的连续原句片段。
            if (!literalObjectQuote(manuscript.content(), name, evidence)
                    || !literalObjectQuote(evidence, name, after)) {
                throw new IllegalArgumentException("纠正后的内容必须是 evidence 内含对象名的正文连续原句片段，不能据引文推断新事实");
            }
            before = source.currentState();
            chapter = source.sourceChapter();
            hash = manuscript.hash();
            status = Status.AUTHOR_CORRECTED;
        }
        String id = entryIdentity(raw.kind(), name, raw.operation(), chapter, hash);
        return new Entry(id, raw.kind(), name, raw.operation(), before, after, reason, chapter, hash, evidence,
                effective, status);
    }

    /** 可供 Projection 在后续章节排除被新摘要取代的纠正；仅比对原提取状态，不读取作者意图。 */
    public static boolean isCurrentCorrection(Entry entry, List<ChapterSummaryEntity> summaries) {
        if (entry == null || entry.operation() != Operation.EXTRACTION_CORRECTION
                || entry.status() != Status.AUTHOR_CORRECTED || entry.sourceHash() == null) return false;
        RawSource source = rawSources(summaries).get(sourceKey(entry.kind(), entry.entityName()));
        return source != null && !source.ambiguous() && Objects.equals(source.chapter(), entry.sourceChapter())
                && Objects.equals(source.summaryHash(), entry.sourceHash())
                && Objects.equals(source.state(), entry.before());
    }

    /** latest-by-kind/name。旧摘要缺正文 hash 或 hash 不符时不可纠正；仍保留可审阅的原提取文本。 */
    public static List<StoryAuthoringSource> sources(List<ChapterSummaryEntity> summaries,
                                                    Map<Integer, ChapterManuscript> chapters) {
        List<StoryAuthoringSource> result = new ArrayList<>();
        for (RawSource raw : rawSources(summaries).values()) {
            ChapterManuscript chapter = chapters.get(raw.chapter());
            String hash = chapter != null && !raw.ambiguous() && chapter.hash().equals(raw.summaryHash())
                    ? chapter.hash() : null;
            String evidence = chapter != null && raw.evidence() != null && chapter.content().contains(raw.evidence())
                    ? raw.evidence() : null;
            boolean verified = hash != null && literalObjectQuote(chapter.content(), raw.name(), raw.state());
            String id = "source-" + ChapterManuscript.digest(canonical(raw.kind().name(), raw.name(),
                    String.valueOf(raw.chapter()), hash, raw.state()));
            result.add(new StoryAuthoringSource(id, raw.kind(), raw.name(), raw.state(), raw.chapter(), hash, evidence, verified));
        }
        return List.copyOf(result);
    }

    private record RawSource(Kind kind, String name, String state, int chapter, String summaryHash,
                             String evidence, boolean ambiguous) { }

    private static Map<String, RawSource> rawSources(List<ChapterSummaryEntity> summaries) {
        Map<String, RawSource> latest = new TreeMap<>();
        if (summaries == null) return latest;
        Set<Integer> numbers = new HashSet<>();
        for (ChapterSummaryEntity summary : summaries.stream().sorted(Comparator.comparing(
                item -> item == null || item.getChapterNo() == null ? 0 : item.getChapterNo())).toList()) {
            if (summary == null || summary.getChapterNo() == null || summary.getChapterNo() < 1
                    || !numbers.add(summary.getChapterNo())) throw new IllegalStateException("摘要章号缺失、重复或无效");
            addStates(latest, Kind.CHARACTER, summary.getCharacterStates(), summary);
            addStates(latest, Kind.ITEM, summary.getItemStates(), summary);
            addStates(latest, Kind.FACTION, summary.getFactionStates(), summary);
            Set<String> seeds = new HashSet<>();
            if (summary.getForeshadowSeeds() != null) {
                for (var seed : summary.getForeshadowSeeds()) {
                    if (seed == null || blank(seed.getContent())) continue;
                    seeds.add(seed.getContent());
                    addSource(latest, Kind.FORESHADOW, seed.getContent(), seed.getContent(), seed.getExcerpt(), summary);
                }
            }
            if (summary.getForeshadowingNew() != null) {
                for (String text : summary.getForeshadowingNew()) if (!blank(text) && !seeds.contains(text)) {
                    addSource(latest, Kind.FORESHADOW, text, text, null, summary);
                }
            }
        }
        return latest;
    }

    private static void addStates(Map<String, RawSource> latest, Kind kind, List<ChapterSummaryEntity.StateEntry> entries,
                                  ChapterSummaryEntity summary) {
        if (entries == null) return;
        for (var entry : entries) if (entry != null && !blank(entry.getName()) && !blank(entry.getStatus())) {
            addSource(latest, kind, entry.getName(), entry.getStatus(), entry.getEvidence(), summary);
        }
    }

    private static void addSource(Map<String, RawSource> latest, Kind kind, String name, String state, String evidence,
                                  ChapterSummaryEntity summary) {
        String key = sourceKey(kind, name);
        RawSource old = latest.get(key);
        latest.put(key, new RawSource(kind, name, state, summary.getChapterNo(), summary.getManuscriptHash(), evidence,
                old != null && old.chapter() == summary.getChapterNo()));
    }

    private static String sourceKey(Kind kind, String name) { return canonical(kind == null ? null : kind.name(), name); }

    /** hash 不含 revision，内容使用长度前缀 canonical 编码，避免 JSON 属性顺序影响。 */
    public static String contentHash(StoryAuthoringSnapshot snapshot) {
        StringBuilder text = new StringBuilder(canonical("authoring-snapshot-v1", String.valueOf(snapshot.schemaVersion()),
                String.valueOf(snapshot.effectiveFromChapter()), snapshot.characterDepthMode()));
        new TreeMap<>(snapshot.settingsOverrides()).forEach((key, value) -> text.append(canonical(key, value)));
        text.append(canonical("entries", String.valueOf(snapshot.entries().size())));
        for (Entry e : snapshot.entries()) text.append(canonical(e.id(), Objects.toString(e.kind(), null), e.entityName(),
                Objects.toString(e.operation(), null), e.before(), e.after(), e.reason(),
                Objects.toString(e.sourceChapter(), null), e.sourceHash(), e.evidence(),
                String.valueOf(e.effectiveFromChapter()), Objects.toString(e.status(), null)));
        return ChapterManuscript.digest(text.toString());
    }

    public static void validatePersisted(StoryAuthoringSnapshot snapshot) {
        if (snapshot == null || snapshot.schemaVersion() != StoryAuthoringSnapshot.SCHEMA_VERSION
                || snapshot.revision() == null || !snapshot.revision().matches(UUID_PATTERN)
                || snapshot.hash() == null || !snapshot.hash().matches(HASH_PATTERN)
                || snapshot.effectiveFromChapter() < 1 || snapshot.entries().size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("作者维护 schema/revision/hash/生效章非法");
        }
        if (!Objects.equals(snapshot.characterDepthMode(), normalizeDepthMode(snapshot.characterDepthMode()))) {
            throw new IllegalArgumentException("作者维护 characterDepthMode 未规范化");
        }
        int total = 0;
        for (var field : snapshot.settingsOverrides().entrySet()) {
            if (field.getValue() == null || !field.getValue().equals(normalizeSetting(field.getKey(), field.getValue()))) {
                throw new IllegalArgumentException("作者设定覆盖不能为空或含未规范化内容");
            }
            total += field.getValue().length();
        }
        Set<String> ids = new HashSet<>();
        for (Entry e : snapshot.entries()) {
            if (e == null || e.kind() == null || e.operation() == null || e.effectiveFromChapter() < 1
                    || e.operation() == Operation.EXTRACTION_CORRECTION && e.effectiveFromChapter() > snapshot.effectiveFromChapter()
                    || !Objects.equals(e.entityName(), required(e.entityName(), 200, "entityName"))
                    || e.entityName().contains("\n") || e.entityName().contains("\t")
                    || !Objects.equals(e.after(), required(e.after(), MAX_ENTRY_CHARS, "after"))
                    || !Objects.equals(e.reason(), required(e.reason(), MAX_ENTRY_CHARS, "reason"))
                    || !Objects.equals(e.id(), entryIdentity(e.kind(), e.entityName(), e.operation(), e.sourceChapter(), e.sourceHash()))
                    || !ids.add(e.id())) throw new IllegalArgumentException("作者维护条目身份、内容或生效章非法");
            if (e.kind() == Kind.FORESHADOW && isPayoffClaim(e.after())) throw new IllegalArgumentException("不能从作者条目设置伏笔已兑现");
            if (e.operation() == Operation.FUTURE_INTENT) {
                if (e.status() != Status.INTENT || e.sourceChapter() != null || e.sourceHash() != null
                        || e.evidence() != null || e.before() != null) throw new IllegalArgumentException("未来意图不得携带伪事实来源");
            } else if (e.status() != Status.AUTHOR_CORRECTED || e.sourceChapter() == null || e.sourceChapter() < 1
                    || e.sourceChapter() >= e.effectiveFromChapter() || blank(e.before()) || e.before().length() > MAX_ENTRY_CHARS
                    || e.sourceHash() == null || !e.sourceHash().matches(HASH_PATTERN)
                    || !Objects.equals(e.evidence(), required(e.evidence(), MAX_ENTRY_CHARS, "evidence"))
                    || !literalObjectQuote(e.evidence(), e.entityName(), e.after())) {
                throw new IllegalArgumentException("作者纠正缺少有界正文摘录及来源版本");
            }
            total += e.entityName().length() + e.after().length() + e.reason().length()
                    + Objects.toString(e.before(), "").length() + Objects.toString(e.evidence(), "").length();
        }
        if (total > MAX_TOTAL_CHARS) throw new IllegalArgumentException("作者维护内容总量超过 " + MAX_TOTAL_CHARS);
        if (!snapshot.hash().equals(contentHash(snapshot))) throw new IllegalArgumentException("作者维护内容 hash 不匹配");
    }

    public static String entryIdentity(Kind kind, String name, Operation operation, Integer sourceChapter, String sourceHash) {
        return "authoring-" + ChapterManuscript.digest(canonical(Objects.toString(kind, null), name,
                Objects.toString(operation, null), Objects.toString(sourceChapter, null), sourceHash));
    }

    private static boolean literalObjectQuote(String text, String name, String quote) {
        return text != null && name != null && quote != null && !quote.isBlank()
                && quote.length() > name.length() && quote.contains(name) && text.contains(quote);
    }

    private static boolean isPayoffClaim(String text) {
        return text.toUpperCase(java.util.Locale.ROOT).matches("(?s).*\\b(PAID|RESOLVED|DONE)\\b.*")
                || text.contains("已兑现") || text.contains("已经兑现") || text.contains("已回收") || text.contains("已经回收");
    }

    private static String normalizeSetting(String key, String value) {
        requireSettingKey(key);
        String normalized = text(value, MAX_SETTING_CHARS, key);
        if (!"characterProfiles".equals(key) && normalized.isBlank()) throw new IllegalArgumentException(key + " 为必填设定，不能清空");
        if (("novel_title".equals(key) || "theme".equals(key)) && (normalized.contains("\n") || normalized.contains("\t"))) {
            throw new IllegalArgumentException(key + " 必须为单行");
        }
        return normalized;
    }

    private static void requireSettingKey(String key) {
        if (!SETTING_KEYS.contains(key)) throw new IllegalArgumentException("不允许修改设定字段：" + key);
    }

    private static String required(String value, int limit, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
        return text(value, limit, field);
    }

    private static String text(String value, int limit, String field) {
        if (value == null || value.length() > limit) throw new IllegalArgumentException(field + " 超出长度限制");
        if (value.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
            throw new IllegalArgumentException(field + " 含非法控制字符");
        }
        return value.strip();
    }

    private static boolean blank(String text) { return text == null || text.isBlank(); }

    private static String canonical(String... values) {
        StringBuilder text = new StringBuilder();
        for (String value : values) {
            text.append(value == null ? -1 : value.length()).append(':');
            if (value != null) text.append(value);
        }
        return text.toString();
    }

    /**
     * 人物深度模式归一：只接受 OFF/OBSERVE/APPLY，空值表示未指定。
     *
     * <p>本方法原属人物决策卡服务。决策卡尚未移植，这里保留同一口径的最小实现，
     * 避免创作维护为一个字符串校验依赖整套人物设计能力。
     */
    private static String normalizeDepthMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return null;
        }
        String normalized = mode.strip();
        if (!List.of("OFF", "OBSERVE", "APPLY").contains(normalized)) {
            throw new IllegalArgumentException("characterDepthMode 只允许 OFF/OBSERVE/APPLY");
        }
        return normalized;
    }
}
