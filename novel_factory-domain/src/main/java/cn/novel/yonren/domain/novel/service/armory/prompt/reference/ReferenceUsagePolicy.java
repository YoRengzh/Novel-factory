package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.types.enums.GenreType;
import cn.novel.yonren.types.enums.PromptScene;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 参考资产的用途资格表：只过滤候选与注入，不改变拆块、embedding 或 payload 信任边界。
 *
 * 本地 classpath 配置在构造时读取一次，解析结果不可变；未知资产和未知场景拒绝。
 * 整文回源必须 whole-safe；小节资格供后续块级检索使用。
 */
@Component
public class ReferenceUsagePolicy {

    private static final String RESOURCE = "assets/reference-usage-policy.json";
    private static final ReferenceChunker CHUNKER = new ReferenceChunker();
    private static final Pattern NON_CURRENT_PREFIX = Pattern.compile(
            "(?:不|未|无法|不能|不需|不必|避免|放弃|没有|无需|不需要|暂缓|搁置|(?:下(?:一)?章|后续章节)(?:再|才))\\s*$");
    private static final Pattern DEFERRED_SUFFIX = Pattern.compile(
            "\\s*(?:留到|放到|推迟到|推到|延至|安排在)(?:下(?:一)?章|后续章节)");
    private final Registry registry;

    public ReferenceUsagePolicy() {
        this(loadLocal());
    }

    /** 包内测试入口，不改变生产的本地 classpath 来源。 */
    ReferenceUsagePolicy(String json) {
        registry = parse(json);
    }

    /** usage JSON 原文 UTF-8 SHA-256；只用于资格与选择缓存，不作为 embedding 版本。 */
    public String version() {
        return registry.version();
    }

    /** 整文件安全资格：任一小节在本场景不合格就拒绝整文。 */
    public boolean allowsDocument(String docId, PromptScene scene, PromptContext ctx) {
        Entry entry = docId == null ? null : registry.documents().get(docId);
        return eligibleFile(entry, scene, ctx) && entry.chapterKeywordsByHeading().isEmpty();
    }

    /** 文件级选择器可以选中按章裁剪的小节，但不能据此回传整篇原文。 */
    boolean canSelectDocument(String docId, PromptScene scene, PromptContext ctx) {
        Entry entry = docId == null ? null : registry.documents().get(docId);
        return eligibleFile(entry, scene, ctx) && entry.matchesAnyHeading(ctx);
    }

    String documentContent(String docId, String content, PromptScene scene, PromptContext ctx) {
        return selectContent(registry.documents().get(docId), content, scene, ctx);
    }

    /** 小节资格：块级检索在候选阶段逐条调用，依赖项不能因身份豁免。 */
    public boolean allowsSection(String docId, String headingPath, PromptScene scene, PromptContext ctx) {
        if (docId == null || headingPath == null || headingPath.isBlank()) {
            return false;
        }
        Entry entry = registry.documents().get(docId);
        if (entry == null || !allows(entry.metadata(), scene, ctx)) {
            return false;
        }
        // 混合文档的"全文"不是普通小节：必须按整文安全口径判断。
        if ("全文".equals(headingPath)) {
            return allowsDocument(docId, scene, ctx);
        }
        if (!entry.chapterKeywordsByHeading().isEmpty()) {
            // 导言是正文块的非独立依赖。没有匹配的正文块时也跳过导言。
            String heading = headingPath.split(" › ", 2)[0];
            boolean matches = "导言".equals(headingPath) ? entry.matchesAnyHeading(ctx) : entry.matchesHeading(heading, ctx);
            if (!matches) {
                return false;
            }
        }
        return entry.sections().stream()
                .filter(section -> section.matches(headingPath))
                .allMatch(section -> allows(section.metadata(), scene, ctx));
    }

    /** 固定规则是否有可用内容。按章选段的资料须通过 fixedContent 装配。 */
    public boolean allowsFixed(String resourcePath, PromptScene scene, PromptContext ctx) {
        Entry entry = fixedEntry(resourcePath);
        return eligibleFile(entry, scene, ctx) && entry.matchesAnyHeading(ctx);
    }

    public String fixedContent(String resourcePath, String content, PromptScene scene, PromptContext ctx) {
        return selectContent(fixedEntry(resourcePath), content, scene, ctx);
    }

    private Entry fixedEntry(String path) {
        return path == null ? null : registry.fixed().get(path.startsWith("assets/") ? path.substring(7) : path);
    }

    private static boolean eligibleFile(Entry entry, PromptScene scene, PromptContext ctx) {
        return entry != null && allows(entry.metadata(), scene, ctx)
                && entry.sections().stream().allMatch(section -> allows(section.metadata(), scene, ctx));
    }

    private static String selectContent(Entry entry, String content, PromptScene scene, PromptContext ctx) {
        if (content == null || !eligibleFile(entry, scene, ctx) || !entry.matchesAnyHeading(ctx)) {
            return "";
        }
        if (entry.chapterKeywordsByHeading().isEmpty()) {
            return content;
        }
        String selected = CHUNKER.sections(content).stream()
                .filter(section -> entry.matchesHeading(section.title(), ctx))
                .map(ReferenceChunker.Section::content)
                .collect(Collectors.joining());
        return selected.isEmpty() ? "" : CHUNKER.frontMatter(content) + selected;
    }

    /** 最终装配时对固定题材/风格规则的资格检查；其他业务规则不应送入本方法。 */
    public boolean allowsRule(String ruleName, PromptScene scene, PromptContext ctx) {
        if (ruleName == null) {
            return false;
        }
        if ("genre-exemplar-fallback".equals(ruleName)) {
            return scene == PromptScene.CHAPTER_CONTENT && allowsDocument("exemplar-dialogue", scene, ctx);
        }
        for (String prefix : List.of("genre-exemplar-", "genre-style-", "genre-arc-", "style-")) {
            if (!ruleName.startsWith(prefix)) {
                continue;
            }
            String genre = ruleName.substring(prefix.length());
            String path = switch (prefix) {
                case "genre-exemplar-" -> "references/genres/" + genre + "/exemplars.md";
                case "genre-style-" -> "references/genres/" + genre + "/style-references.md";
                case "genre-arc-" -> "references/genres/" + genre + "/arc-templates.md";
                default -> "styles/" + genre + ".md";
            };
            // 未提供风格文件的题材由题材策略使用登记过的 default 风格兜底。
            if (prefix.equals("style-") && !registry.fixed().containsKey(path)
                    && Set.of(GenreType.values()).stream().anyMatch(g -> g.getCode().equals(genre))) {
                path = "styles/default.md";
            }
            return allowsFixed(path, scene, ctx);
        }
        return false;
    }

    private static boolean allows(Metadata metadata, PromptScene scene, PromptContext ctx) {
        if (scene == null || !metadata.scenes().contains(scene)) {
            return false;
        }
        // 章节任务适配：专用范例必须能对上本章要点，未知任务不强行注入。
        if (!matchesChapterBrief(metadata.chapterKeywords(), ctx)) {
            return false;
        }
        Set<Purpose> purposes = switch (scene) {
            case SETTING_DRAFT -> Set.of(Purpose.SETTING_DESIGN, Purpose.STYLE_GUIDANCE);
            case CHAPTER_PLAN, STAGE_BLUEPRINT, VOLUME_BLUEPRINT ->
                    Set.of(Purpose.STORY_PLANNING, Purpose.STYLE_GUIDANCE);
            case CHAPTER_CONTENT, CHAPTER_REVISE -> Set.of(Purpose.PROSE_EXPRESSION, Purpose.STYLE_GUIDANCE);
            case CHAPTER_AUDIT -> Set.of(Purpose.QUALITY_REVIEW, Purpose.STYLE_GUIDANCE);
        };
        if (metadata.purposes().stream().noneMatch(purposes::contains)) {
            return false;
        }
        Set<MaterialType> forbidden = switch (scene) {
            case SETTING_DRAFT -> Set.of(MaterialType.TEMPLATE, MaterialType.MODEL_SCHEMA,
                    MaterialType.EXEMPLAR, MaterialType.OPERATIONS);
            case CHAPTER_PLAN, STAGE_BLUEPRINT, VOLUME_BLUEPRINT ->
                    Set.of(MaterialType.EXEMPLAR, MaterialType.MODEL_SCHEMA, MaterialType.OPERATIONS);
            case CHAPTER_CONTENT, CHAPTER_REVISE, CHAPTER_AUDIT ->
                    Set.of(MaterialType.TEMPLATE, MaterialType.MODEL_SCHEMA, MaterialType.OPERATIONS);
        };
        if (metadata.types().stream().anyMatch(forbidden::contains)) {
            return false;
        }
        if (scene == PromptScene.SETTING_DRAFT && !metadata.targetFields().isEmpty()
                && ctx != null && ctx.getTargetFields() != null && !ctx.getTargetFields().isEmpty()
                && ctx.getTargetFields().stream().noneMatch(metadata.targetFields()::contains)) {
            return false;
        }
        if (!metadata.genres().isEmpty() && !matchesGenre(metadata, ctx)) {
            return false;
        }
        return matches(metadata.themeKeywords(), ctx == null ? null : ctx.getTheme())
                && matches(metadata.styleKeywords(), ctx == null ? null : ctx.getStyle());
    }

    private static boolean matchesGenre(Metadata metadata, PromptContext ctx) {
        if (ctx == null) {
            return false;
        }
        if (metadata.types().contains(MaterialType.EXEMPLAR)) {
            return GenreType.matching(ctx.getTheme(), ctx.getStyle()).stream()
                    .anyMatch(genre -> metadata.genres().contains(genre.getCode()));
        }
        return metadata.genres().contains(GenreType.match(ctx.getTheme(), ctx.getStyle()).getCode());
    }

    /** 章节任务关键词：无约束时放行；有约束时必须能从本章要点中找到对应任务，否定表述不算命中。 */
    private static boolean matchesChapterBrief(Set<String> keywords, PromptContext ctx) {
        if (keywords.isEmpty()) {
            return true;
        }
        String brief = ctx == null ? null : ctx.getChapterBrief();
        if (brief == null || brief.isBlank()) {
            return false;
        }
        String normalized = brief.toLowerCase(Locale.ROOT);
        return keywords.stream().map(s -> s.toLowerCase(Locale.ROOT)).anyMatch(keyword -> {
            int index = normalized.indexOf(keyword);
            while (index >= 0) {
                if (!isNonCurrent(normalized, index, index + keyword.length())) return true;
                index = normalized.indexOf(keyword, index + keyword.length());
            }
            return false;
        });
    }

    /** 章计划也会说明暂缓的戏，例如「暂不讯问」「下章再表白」「告白留到下一章」。 */
    private static boolean isNonCurrent(String text, int start, int end) {
        return NON_CURRENT_PREFIX.matcher(text).region(Math.max(0, start - 12), start).find()
                || DEFERRED_SUFFIX.matcher(text).region(end, text.length()).lookingAt();
    }

    private static boolean matches(Set<String> keywords, String text) {
        if (keywords.isEmpty()) {
            return true;
        }
        if (text == null) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return keywords.stream().map(s -> s.toLowerCase(Locale.ROOT)).anyMatch(normalized::contains);
    }

    private static String loadLocal() {
        try (InputStream stream = new ClassPathResource(RESOURCE).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取本地参考用途资格表 " + RESOURCE, e);
        }
    }

    private static Registry parse(String json) {
        try {
            JSONObject root = JSON.parseObject(json);
            if (root == null || !"1".equals(root.getString("schemaVersion"))) {
                throw new IllegalArgumentException("schemaVersion 必须为 1");
            }
            return new Registry(hash(json), entries(root.getJSONObject("documents"), true),
                    entries(root.getJSONObject("fixed"), false));
        } catch (RuntimeException e) {
            throw new IllegalStateException("参考用途资格表无效", e);
        }
    }

    private static Map<String, Entry> entries(JSONObject object, boolean documents) {
        if (object == null || object.isEmpty()) {
            throw new IllegalArgumentException("缺少资产登记");
        }
        Map<String, Entry> result = new LinkedHashMap<>();
        for (String key : object.keySet()) {
            if (key.isBlank() || key.contains("..") || key.contains("\\")
                    || key.startsWith("/") || (documents && key.contains("/"))) {
                throw new IllegalArgumentException("非法资产标识 " + key);
            }
            JSONObject rule = object.getJSONObject(key);
            Metadata metadata = metadata(rule, null);
            List<Section> sections = new ArrayList<>();
            JSONArray array = rule.getJSONArray("sections");
            if (array != null) {
                for (int i = 0; i < array.size(); i++) {
                    JSONObject section = array.getJSONObject(i);
                    Set<String> selectors = strings(section, "headingContains");
                    if (selectors.isEmpty()) {
                        throw new IllegalArgumentException("小节缺少标题选择器 " + key);
                    }
                    sections.add(new Section(selectors, metadata(section, metadata)));
                }
            }
            Map<String, Set<String>> headingKeywords = new LinkedHashMap<>();
            JSONObject headings = rule.getJSONObject("chapterKeywordsByHeading");
            if (headings != null) {
                if (headings.isEmpty()) {
                    throw new IllegalArgumentException("缺少范例标题 " + key);
                }
                for (String title : headings.keySet()) {
                    Set<String> keywords = strings(headings, title);
                    if (title.isBlank() || keywords.isEmpty()) {
                        throw new IllegalArgumentException("范例标题与章节关键词不能为空 " + key);
                    }
                    headingKeywords.put(title, keywords);
                }
            }
            result.put(key, new Entry(metadata, List.copyOf(sections), Map.copyOf(headingKeywords)));
        }
        return Map.copyOf(result);
    }

    private static Metadata metadata(JSONObject object, Metadata parent) {
        if (object == null) {
            throw new IllegalArgumentException("缺少元数据");
        }
        String group = object.getString("group");
        if (group == null && parent != null) {
            group = parent.group();
        }
        if (!Set.of("technique", "structure", "genre", "style").contains(group == null ? "" : group)) {
            throw new IllegalArgumentException("缺少显式合法 group");
        }
        Set<PromptScene> scenes = enums(object, "scenes", PromptScene.class);
        Set<Purpose> purposes = enums(object, "purposes", Purpose.class);
        Set<MaterialType> types = enums(object, "types", MaterialType.class);
        if (scenes.isEmpty() || purposes.isEmpty() || types.isEmpty()) {
            throw new IllegalArgumentException("scenes/purposes/types 必须显式且非空");
        }
        return new Metadata(group, scenes, purposes, types,
                inherited(object, "genres", parent == null ? Set.of() : parent.genres()),
                inherited(object, "themeKeywords", parent == null ? Set.of() : parent.themeKeywords()),
                inherited(object, "styleKeywords", parent == null ? Set.of() : parent.styleKeywords()),
                inherited(object, "targetFields", parent == null ? Set.of() : parent.targetFields()),
                inherited(object, "chapterKeywords", parent == null ? Set.of() : parent.chapterKeywords()));
    }

    private static Set<String> inherited(JSONObject object, String key, Set<String> parent) {
        return object.containsKey(key) ? strings(object, key) : parent;
    }

    private static Set<String> strings(JSONObject object, String key) {
        JSONArray array = object.getJSONArray(key);
        if (array == null) {
            return Set.of();
        }
        return array.toJavaList(String.class).stream().map(String::trim)
                .peek(value -> {
                    if (value.isEmpty()) {
                        throw new IllegalArgumentException("空元数据 " + key);
                    }
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    private static <E extends Enum<E>> Set<E> enums(JSONObject object, String key, Class<E> type) {
        return strings(object, key).stream().map(value -> Enum.valueOf(type, value))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String hash(String json) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private enum Purpose {
        SETTING_DESIGN, STORY_PLANNING, PROSE_EXPRESSION, STYLE_GUIDANCE, QUALITY_REVIEW, ASSET_MAINTENANCE
    }

    private enum MaterialType {
        PRINCIPLE, METHOD, TEMPLATE, EXEMPLAR, STYLE, MODEL_SCHEMA, OPERATIONS
    }

    private record Registry(String version, Map<String, Entry> documents, Map<String, Entry> fixed) {
    }

    private record Entry(Metadata metadata, List<Section> sections, Map<String, Set<String>> chapterKeywordsByHeading) {
        boolean matchesAnyHeading(PromptContext ctx) {
            return chapterKeywordsByHeading.isEmpty()
                    || chapterKeywordsByHeading.values().stream().anyMatch(words -> matchesChapterBrief(words, ctx));
        }

        boolean matchesHeading(String title, PromptContext ctx) {
            Set<String> words = chapterKeywordsByHeading.get(title);
            return words != null && matchesChapterBrief(words, ctx);
        }
    }

    private record Metadata(String group, Set<PromptScene> scenes, Set<Purpose> purposes, Set<MaterialType> types,
                            Set<String> genres, Set<String> themeKeywords, Set<String> styleKeywords,
                            Set<String> targetFields, Set<String> chapterKeywords) {
    }

    private record Section(Set<String> headingContains, Metadata metadata) {
        boolean matches(String headingPath) {
            return headingContains.stream().anyMatch(headingPath::contains);
        }
    }
}
