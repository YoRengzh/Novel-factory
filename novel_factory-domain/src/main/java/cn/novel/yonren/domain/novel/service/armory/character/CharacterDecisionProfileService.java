package cn.novel.yonren.domain.novel.service.armory.character;

import cn.novel.yonren.domain.novel.model.valobj.character.CharacterDecisionProfile;
import cn.novel.yonren.domain.novel.model.valobj.character.CharacterDecisionProfile.Adoption;
import cn.novel.yonren.domain.novel.model.valobj.character.CharacterDecisionProfile.Source;
import cn.novel.yonren.domain.novel.model.valobj.character.CharacterDecisionProfileSet;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.UUID;

/** 纯人物设计策略：不读写文件、不调用模型，也不将设计描述升级为故事事实。 */
@Service
public class CharacterDecisionProfileService {
    public static final int MAX_PROFILES = 64;
    public static final int MAX_NAME_CHARS = 120;
    public static final int MAX_FIELD_CHARS = 2048;
    public static final int MAX_TOTAL_CHARS = 65536;

    public record FieldEntry(String key, String label, String value) {}

    /** 规范化可编辑内容并重绑名字身份；输入中的来源、采纳状态与身份不可充当权威。 */
    public static List<CharacterDecisionProfile> normalize(List<CharacterDecisionProfile> requested) {
        if (requested == null) return List.of();
        if (requested.size() > MAX_PROFILES) throw invalid("人物数量不能超过 " + MAX_PROFILES);
        Map<String, CharacterDecisionProfile> profiles = new LinkedHashMap<>();
        int total = 0;
        for (CharacterDecisionProfile raw : requested) {
            if (raw == null) throw invalid("人物档案不能含 null 条目");
            String name = text(raw.displayName(), MAX_NAME_CHARS, "displayName");
            CharacterDecisionProfile normalized = new CharacterDecisionProfile(
                    name == null ? null : characterId(name), name, raw.locked(),
                    content(raw.want(), "want"), content(raw.need(), "need"),
                    content(raw.fearAndDefense(), "fearAndDefense"),
                    content(raw.valuePriorities(), "valuePriorities"),
                    content(raw.boundaries(), "boundaries"),
                    content(raw.independentConcerns(), "independentConcerns"),
                    content(raw.decisionPattern(), "decisionPattern"), null, null);
            List<FieldEntry> entries = fieldEntries(normalized);
            if (name == null) {
                if (!entries.isEmpty() || Boolean.TRUE.equals(raw.locked())) throw invalid("有内容的人物档案必须提供 displayName");
                continue;
            }
            if (name.contains("\n") || name.contains("\t")) throw invalid("人物姓名必须为单行，不能含制表符");
            if (profiles.putIfAbsent(name, normalized) != null) throw invalid("同名人物存在歧义：" + name);
            total += name.length() + entries.stream().mapToInt(entry -> entry.value().length()).sum();
            if (total > MAX_TOTAL_CHARS) throw invalid("人物档案总内容不能超过 " + MAX_TOTAL_CHARS + " 字符");
        }
        return List.copyOf(profiles.values());
    }

    /** 模型只能提供设计草稿；其 locked/source/adoption 均不采信。 */
    public static List<CharacterDecisionProfile> draft(List<CharacterDecisionProfile> candidates) {
        return normalize(candidates).stream().map(profile -> bind(profile, null, Source.MODEL_DESIGN, Adoption.DRAFT)).toList();
    }

    /** 作者替换版本；锁定条目省略时保留，修改或删除前必须显式解除 locked。 */
    public static CharacterDecisionProfileSet adoptAuthor(CharacterDecisionProfileSet current,
                                                          List<CharacterDecisionProfile> requested,
                                                          int effectiveFromChapter) {
        return adoptAuthor(current, requested, effectiveFromChapter, profile -> false);
    }

    public static CharacterDecisionProfileSet adoptAuthor(CharacterDecisionProfileSet current,
                                                          List<CharacterDecisionProfile> requested,
                                                          int effectiveFromChapter,
                                                          Predicate<CharacterDecisionProfile> verifiedDraft) {
        if (current != null) validatePersisted(current);
        Map<String, CharacterDecisionProfile> result = byName(normalize(requested));
        if (current != null) {
            for (CharacterDecisionProfile previous : current.profiles()) {
                if (!Boolean.TRUE.equals(previous.locked())) continue;
                CharacterDecisionProfile candidate = result.get(previous.displayName());
                if (candidate == null) {
                    result.put(previous.displayName(), previous);
                } else if (!Boolean.FALSE.equals(candidate.locked())) {
                    if (!sameContent(previous, candidate)) throw invalid("请先解锁人物再修改：" + previous.displayName());
                    result.put(previous.displayName(), previous);
                }
            }
        }
        Map<String, CharacterDecisionProfile> previousByName = byName(current == null ? List.of() : current.profiles());
        List<CharacterDecisionProfile> adopted = result.values().stream().map(profile -> {
            CharacterDecisionProfile previous = previousByName.get(profile.displayName());
            if (previous != null && sameContent(previous, profile)) {
                return bind(profile, profile.locked(), previous.source(), previous.adoption());
            }
            Source source = verifiedDraft.test(profile) ? Source.MODEL_DESIGN : Source.AUTHOR;
            return bind(profile, profile.locked(), source, Adoption.AUTHOR_CONFIRMED);
        }).toList();
        return snapshot(current, adopted, effectiveFromChapter);
    }

    /** 自动采纳只增补未锁定的模型设计空白，不覆盖作者确认内容，也不删除既有人物。 */
    public static CharacterDecisionProfileSet adoptAuto(CharacterDecisionProfileSet current,
                                                        List<CharacterDecisionProfile> candidates,
                                                        int effectiveFromChapter) {
        if (current != null) validatePersisted(current);
        Map<String, CharacterDecisionProfile> result = byName(current == null ? List.of() : current.profiles());
        for (CharacterDecisionProfile candidate : draft(candidates)) {
            CharacterDecisionProfile previous = result.get(candidate.displayName());
            if (previous != null && (Boolean.TRUE.equals(previous.locked()) || previous.source() == Source.AUTHOR
                    || previous.adoption() == Adoption.AUTHOR_CONFIRMED)) continue;
            CharacterDecisionProfile merged = previous == null ? candidate : new CharacterDecisionProfile(
                    previous.characterId(), previous.displayName(), previous.locked(),
                    first(previous.want(), candidate.want()), first(previous.need(), candidate.need()),
                    first(previous.fearAndDefense(), candidate.fearAndDefense()),
                    first(previous.valuePriorities(), candidate.valuePriorities()),
                    first(previous.boundaries(), candidate.boundaries()),
                    first(previous.independentConcerns(), candidate.independentConcerns()),
                    first(previous.decisionPattern(), candidate.decisionPattern()), Source.MODEL_DESIGN, Adoption.AUTO_APPLIED);
            result.put(candidate.displayName(), bind(merged, previous == null ? null : previous.locked(),
                    Source.MODEL_DESIGN, Adoption.AUTO_APPLIED));
        }
        return snapshot(current, List.copyOf(result.values()), effectiveFromChapter);
    }

    /** 只投影非空设计字段；来源标签、预算与适用模式由调用方的 renderer 决定。 */
    public static List<FieldEntry> fieldEntries(CharacterDecisionProfile profile) {
        if (profile == null) return List.of();
        List<FieldEntry> entries = new ArrayList<>();
        add(entries, "want", "外在诉求", profile.want());
        add(entries, "need", "内在需要", profile.need());
        add(entries, "fearAndDefense", "恐惧与防御", profile.fearAndDefense());
        add(entries, "valuePriorities", "价值优先序", profile.valuePriorities());
        add(entries, "boundaries", "底线与边界", profile.boundaries());
        add(entries, "independentConcerns", "独立关切", profile.independentConcerns());
        add(entries, "decisionPattern", "决策模式", profile.decisionPattern());
        return List.copyOf(entries);
    }

    public static String characterId(String displayName) {
        String name = text(displayName, MAX_NAME_CHARS, "displayName");
        if (name == null) throw invalid("人物身份需要 displayName");
        return "character-" + ChapterManuscript.digest(name);
    }

    /** 稳定的长度前缀编码，排除 revision；换 JSON 库或属性顺序不改变内容 hash。 */
    public static String contentHash(CharacterDecisionProfileSet set) {
        if (set == null) throw invalid("人物设计快照不能为空");
        StringBuilder canonical = new StringBuilder();
        token(canonical, String.valueOf(set.schemaVersion()));
        token(canonical, Objects.toString(set.effectiveFromChapter(), null));
        set.profiles().stream().sorted(Comparator.comparing(CharacterDecisionProfile::displayName,
                Comparator.nullsFirst(Comparator.naturalOrder()))).forEach(profile -> {
            token(canonical, profile.characterId());
            token(canonical, profile.displayName());
            token(canonical, Objects.toString(profile.locked(), null));
            token(canonical, profile.want());
            token(canonical, profile.need());
            token(canonical, profile.fearAndDefense());
            token(canonical, profile.valuePriorities());
            token(canonical, profile.boundaries());
            token(canonical, profile.independentConcerns());
            token(canonical, profile.decisionPattern());
            token(canonical, Objects.toString(profile.source(), null));
            token(canonical, Objects.toString(profile.adoption(), null));
        });
        return ChapterManuscript.digest(canonical.toString());
    }

    /** 磁盘/冻结上下文读回必须自洽，坏版本不得当作缺省空档案继续。 */
    public static void validatePersisted(CharacterDecisionProfileSet set) {
        if (set == null || set.schemaVersion() != CharacterDecisionProfileSet.SCHEMA_VERSION
                || set.revision() == null || !set.revision().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || set.hash() == null || !set.hash().matches("[0-9a-f]{64}")
                || set.effectiveFromChapter() == null || set.effectiveFromChapter() < 1) {
            throw invalid("人物设计快照的 schema/revision/hash/生效章节非法");
        }
        List<CharacterDecisionProfile> normalized = normalize(set.profiles());
        if (normalized.size() != set.profiles().size()) throw invalid("人物设计快照包含空姓名条目");
        for (int i = 0; i < normalized.size(); i++) {
            CharacterDecisionProfile stored = set.profiles().get(i);
            if (stored.source() == null || stored.adoption() == null
                    || stored.source() == Source.AUTHOR && stored.adoption() != Adoption.AUTHOR_CONFIRMED
                    || !bind(normalized.get(i), normalized.get(i).locked(), stored.source(), stored.adoption()).equals(stored)) {
                throw invalid("人物设计快照身份、来源或规范化内容不一致");
            }
        }
        if (!set.hash().equals(contentHash(set))) throw invalid("人物设计快照内容 hash 不匹配");
    }

    public static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) return null;
        String normalized = mode.strip();
        if (!List.of("OFF", "OBSERVE", "APPLY").contains(normalized)) throw invalid("characterDepthMode 只允许 OFF/OBSERVE/APPLY");
        return normalized;
    }

    private static CharacterDecisionProfileSet snapshot(CharacterDecisionProfileSet current,
                                                         List<CharacterDecisionProfile> profiles, int chapter) {
        if (chapter < 1) throw invalid("人物设计生效章节必须为正数");
        normalize(profiles);
        if (current != null && current.profiles().equals(profiles)) return current;
        CharacterDecisionProfileSet unsigned = new CharacterDecisionProfileSet(CharacterDecisionProfileSet.SCHEMA_VERSION,
                null, null, chapter, profiles);
        String hash = contentHash(unsigned);
        String seed = "character-candidate-v1\n" + Objects.toString(current == null ? null : current.revision(), "ABSENT") + "\n" + hash;
        String revision = UUID.nameUUIDFromBytes(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        CharacterDecisionProfileSet result = new CharacterDecisionProfileSet(unsigned.schemaVersion(), revision,
                hash, chapter, profiles);
        validatePersisted(result);
        return result;
    }

    private static CharacterDecisionProfile bind(CharacterDecisionProfile profile, Boolean locked, Source source, Adoption adoption) {
        return new CharacterDecisionProfile(characterId(profile.displayName()), profile.displayName(), locked,
                profile.want(), profile.need(), profile.fearAndDefense(), profile.valuePriorities(), profile.boundaries(),
                profile.independentConcerns(), profile.decisionPattern(), source, adoption);
    }

    private static Map<String, CharacterDecisionProfile> byName(List<CharacterDecisionProfile> profiles) {
        Map<String, CharacterDecisionProfile> result = new LinkedHashMap<>();
        for (CharacterDecisionProfile profile : profiles) result.put(profile.displayName(), profile);
        return result;
    }

    private static boolean sameContent(CharacterDecisionProfile left, CharacterDecisionProfile right) {
        return Objects.equals(left.displayName(), right.displayName()) && Objects.equals(left.want(), right.want())
                && Objects.equals(left.need(), right.need()) && Objects.equals(left.fearAndDefense(), right.fearAndDefense())
                && Objects.equals(left.valuePriorities(), right.valuePriorities()) && Objects.equals(left.boundaries(), right.boundaries())
                && Objects.equals(left.independentConcerns(), right.independentConcerns())
                && Objects.equals(left.decisionPattern(), right.decisionPattern());
    }

    private static String first(String previous, String candidate) { return previous == null ? candidate : previous; }

    private static void add(List<FieldEntry> target, String key, String label, String value) {
        if (value != null && !value.isBlank()) target.add(new FieldEntry(key, label, value));
    }

    private static String content(String raw, String field) {
        if (raw == null) return null;
        String value = text(raw, MAX_FIELD_CHARS, field);
        return value == null ? "" : value;
    }

    private static String text(String raw, int limit, String field) {
        if (raw == null) return null;
        if (raw.length() > limit) throw invalid(field + " 超过 " + limit + " 字符");
        String value = raw.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (value.isEmpty()) return null;
        if (value.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')) {
            throw invalid(field + " 含不支持的控制字符");
        }
        return value;
    }

    private static void token(StringBuilder target, String value) {
        target.append(value == null ? -1 : value.length()).append(':');
        if (value != null) target.append(value);
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
