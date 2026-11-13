package cn.novel.yonren.types.utils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * story-bible.txt 的反向解析：把「标签: 值」纯文本读回结构化字段。
 *
 * <p><b>为什么需要它</b>：前端要"接着上次的末尾继续写"，就必须把初次提交时的整套设定重新组装出来
 * ——{@code ValidateUserInputNode} 对 11 个字段做全非空校验，<em>续写同样要走一遍</em>。
 * 而 bible 是这些设定唯一被持久化下来的地方（{@code story-meta.json} 只存总章数），
 * 且写入时"仅首次生效"、后续续写绝不覆盖，因此它是续写时最可信的来源。
 *
 * <p>⚠️ <b>标签常量必须与 {@code StoryRepository#buildBibleContent} 的写入逐字一致</b>——
 * 两边共用本类的常量正是为了消除"改了写入、忘了读取"这类静默失配：值解析不出来不会报错，
 * 只会在续写时表现为"设定莫名其妙丢了"。
 *
 * <p>长文本值仅由完整的已知标签加 ASCII 冒号分隔，段落和段内冒号原样保留。
 * 标题、类型、ID 与数值/布尔字段保持旧版单行规则；这些字段外的未知行仍忽略。
 * 缺失字段为 null，重复标签及其可能截断的长文本字段通过 {@link #parseDiagnostic(String)} 报告，
 * 不自动选择冲突值。旧格式没有转义，单次出现且完全符合标签语法的行只能按字段边界解释。
 * 多行机制标识的首行作为现有检测名称，后续行是补充说明，仍保留在快照中。
 */
public final class StoryBibleParser {

    public static final String LABEL_NOVEL_TITLE = "小说名称";
    public static final String LABEL_THEME = "小说类型";
    public static final String LABEL_STYLE = "小说格调";
    public static final String LABEL_WORLD_SETTING = "世界观";
    public static final String LABEL_PERSPECTIVE = "叙述视角";
    public static final String LABEL_TARGET_AUDIENCE = "目标读者";
    public static final String LABEL_TONE = "语气基调";
    public static final String LABEL_PROTAGONIST = "主人公";
    /**
     * 主要人物档案（代词与既定事实）：如「角色甲（男，用「他」）；角色乙（女，用「她」）」
     * ＋既定事实（班级/年级/亲属/住址）。
     *
     * <p><b>为什么需要单独一条</b>：正文把某女性角色写成「他」并连错数章时，
     * 账本里既没有"该角色是女"的事实、也没有可判定的锚，审计想判"事实矛盾"都无从对照。
     * 性别与志愿这类**跨章不变的设定**必须由作者在 bible 里声明一次，
     * 靠模型从正文推断只会复制已有的错。
     */
    public static final String LABEL_CHARACTERS = "主要人物";
    public static final String LABEL_OUTLINE = "故事概述";
    public static final String LABEL_CHAPTER_COUNT = "章节数量";
    public static final String LABEL_CHAPTER_GOAL = "章节目标";
    public static final String LABEL_WORLD_ID = "世界ID";
    public static final String LABEL_HAS_CHEAT = "存在金手指";
    public static final String LABEL_CHEAT_NAME = "金手指名称";
    public static final String LABEL_CHEAT_INTERVAL = "金手指使用间隔";
    /** 以下两项来自 yml 默认值（非用户输入），仅作写入/读取共用；续写无需求解 */
    public static final String LABEL_LANGUAGE = "语言";
    public static final String LABEL_TOTAL_COUNT = "总字数";

    // 包含 Snapshot 不返回的字段：章节数量/语言/总字数也必须结束前一段，不能混入作者设定。
    private static final Set<String> KNOWN_LABELS = Set.of(
            LABEL_NOVEL_TITLE, LABEL_THEME, LABEL_STYLE, LABEL_WORLD_SETTING, LABEL_PERSPECTIVE,
            LABEL_TARGET_AUDIENCE, LABEL_TONE, LABEL_PROTAGONIST, LABEL_CHARACTERS, LABEL_OUTLINE,
            LABEL_CHAPTER_COUNT, LABEL_CHAPTER_GOAL, LABEL_WORLD_ID, LABEL_LANGUAGE, LABEL_TOTAL_COUNT,
            LABEL_HAS_CHEAT, LABEL_CHEAT_NAME, LABEL_CHEAT_INTERVAL);
    private static final Set<String> MULTILINE_LABELS = Set.of(
            LABEL_STYLE, LABEL_WORLD_SETTING, LABEL_PERSPECTIVE, LABEL_TARGET_AUDIENCE, LABEL_TONE,
            LABEL_PROTAGONIST, LABEL_CHARACTERS, LABEL_OUTLINE, LABEL_CHAPTER_GOAL, LABEL_CHEAT_NAME);
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");

    private StoryBibleParser() {
    }

    /**
     * 解析结果：缺失字段为 {@code null}（不做默认值填充）。
     *
     * <p>{@code hasCheatMechanism} 区分三态：{@code null} = bible 里没写（老故事），
     * {@code false} = 明确声明"没有金手指"——这两者语义不同，不能合并。
     */
    public record Snapshot(String novelTitle, String theme, String style, String worldSetting,
                           String perspective, String targetAudience, String tone, String protagonist,
                           String outline, String chapterGoal, String worldId,
                           Boolean hasCheatMechanism, String cheatMechanismName,
                           Integer cheatUsageInterval,
                           /** 主要人物档案（代词与既定事实）；老 bible 无此行为 null */
                           String characters) {
        /**
         * 现有机制检测使用首行名称；后续行可能是说明文字，不能并入匹配标识。
         * 原文本仍完整保存在 cheatMechanismName 中。
         */
        public String mechanismIdentifier() {
            if (cheatMechanismName == null) {
                return null;
            }
            String identifier = cheatMechanismName.split("\\R", 2)[0].trim();
            return identifier.isEmpty() ? null : identifier;
        }
    }

    public enum DiagnosticCode {
        DUPLICATE_LABEL,
        AMBIGUOUS_FIELD_BOUNDARY
    }

    /** 只携带标签及从 1 起算的行号，不复制用户设定全文到错误消息或日志。 */
    public record Diagnostic(DiagnosticCode code, String label, int firstLineNumber, int lineNumber) {
    }

    /** 冲突字段为 null；调用方可以在恢复/提交前明确拒绝有歧义的旧文本。 */
    public record ParseResult(Snapshot snapshot, List<Diagnostic> diagnostics) {
        public ParseResult {
            diagnostics = List.copyOf(diagnostics);
        }

        public boolean hasAmbiguities() {
            return !diagnostics.isEmpty();
        }
    }

    /**
     * 兼容入口：空输入返回全 null；歧义字段同样返回 null，不静默选择首个或最后一个值。
     * 需要区分缺失和冲突的调用方使用 {@link #parseDiagnostic(String)}。
     */
    public static Snapshot parse(String bibleText) {
        return parseDiagnostic(bibleText).snapshot();
    }

    /**
     * 仅由已知标签的行首边界收集多行值，保留原始 LF/CRLF、段内缩进、空行及 ASCII 冒号。
     * 值最外侧仍按旧规则 trim；完整已知标签不能作为未转义的段内独占行。
     */
    public static ParseResult parseDiagnostic(String bibleText) {
        Map<String, String> values = new HashMap<>();
        Map<String, Field> firstFields = new HashMap<>();
        Set<String> ambiguousLabels = new HashSet<>();
        Set<Diagnostic> diagnostics = new LinkedHashSet<>();
        if (bibleText != null && !bibleText.isBlank()) {
            Matcher lineBreaks = LINE_BREAK.matcher(bibleText);
            Field current = null;
            int start = 0;
            int lineNumber = 1;
            while (start < bibleText.length()) {
                boolean hasBreak = lineBreaks.find();
                int end = hasBreak ? lineBreaks.start() : bibleText.length();
                String line = bibleText.substring(start, end);
                int colon = line.indexOf(':');
                String label = colon > 0 ? line.substring(0, colon).trim() : "";
                if (KNOWN_LABELS.contains(label)) {
                    collectValue(current, bibleText, start, values);
                    Boundary previous = current != null && current.multiline()
                            ? new Boundary(current.label(), current.lineNumber()) : null;
                    // 老 appendBibleLine 的 null 占位不能因后续坏行而复活为人物档案等假字段。
                    boolean multiline = MULTILINE_LABELS.contains(label)
                            && !"null".equalsIgnoreCase(line.substring(colon + 1).trim());
                    current = new Field(label, lineNumber, start + colon + 1, end, multiline, previous);
                    Field first = firstFields.putIfAbsent(label, current);
                    if (first != null) {
                        ambiguousLabels.add(label);
                        diagnostics.add(new Diagnostic(DiagnosticCode.DUPLICATE_LABEL, label,
                                first.lineNumber(), lineNumber));
                        // 任一次重复标签都可能是正文中的小标题，先前被它截断的段落也不能冒充完整值。
                        markAmbiguousBoundary(first, ambiguousLabels, diagnostics);
                        markAmbiguousBoundary(current, ambiguousLabels, diagnostics);
                    }
                }
                if (!hasBreak) {
                    break;
                }
                start = lineBreaks.end();
                lineNumber++;
            }
            collectValue(current, bibleText, bibleText.length(), values);
        }
        ambiguousLabels.forEach(values::remove);
        return new ParseResult(snapshot(values), List.copyOf(diagnostics));
    }

    private static Snapshot snapshot(Map<String, String> values) {
        return new Snapshot(
                values.get(LABEL_NOVEL_TITLE),
                values.get(LABEL_THEME),
                values.get(LABEL_STYLE),
                values.get(LABEL_WORLD_SETTING),
                values.get(LABEL_PERSPECTIVE),
                values.get(LABEL_TARGET_AUDIENCE),
                values.get(LABEL_TONE),
                values.get(LABEL_PROTAGONIST),
                values.get(LABEL_OUTLINE),
                values.get(LABEL_CHAPTER_GOAL),
                values.get(LABEL_WORLD_ID),
                parseBooleanOrNull(values.get(LABEL_HAS_CHEAT)),
                values.get(LABEL_CHEAT_NAME),
                parseLeadingIntOrNull(values.get(LABEL_CHEAT_INTERVAL)),
                values.get(LABEL_CHARACTERS));
    }

    private record Boundary(String label, int lineNumber) {
    }

    private record Field(String label, int lineNumber, int valueStart, int firstLineEnd,
                         boolean multiline, Boundary previous) {
    }

    private static void markAmbiguousBoundary(Field field, Set<String> ambiguousLabels,
                                              Set<Diagnostic> diagnostics) {
        Boundary previous = field.previous();
        if (previous != null) {
            ambiguousLabels.add(previous.label());
            diagnostics.add(new Diagnostic(DiagnosticCode.AMBIGUOUS_FIELD_BOUNDARY, previous.label(),
                    previous.lineNumber(), field.lineNumber()));
        }
    }

    private static void collectValue(Field field, String text, int boundary, Map<String, String> values) {
        if (field == null) {
            return;
        }
        // 用原文本区间而非逐行 trim/join，保留段落内的换行种类与空白。
        int end = field.multiline() ? boundary : field.firstLineEnd();
        String value = text.substring(field.valueStart(), end).trim();
        if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) {
            values.put(field.label(), value);
        }
    }

    private static Boolean parseBooleanOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if ("true".equalsIgnoreCase(trimmed)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(trimmed)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** 取前导数字：老数据里可能有「180（预估范围 175-185）」这类带说明的值 */
    private static Integer parseLeadingIntOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        StringBuilder digits = new StringBuilder();
        for (char c : value.trim().toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            } else {
                break;
            }
        }
        if (digits.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(digits.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
