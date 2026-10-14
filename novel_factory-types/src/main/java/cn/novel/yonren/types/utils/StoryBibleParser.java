package cn.novel.yonren.types.utils;

import java.util.HashMap;
import java.util.Map;

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
 * <p>解析保持宽容：未知标签忽略、缺失标签为 null（老故事可能由更早版本写入，标签集合未必齐全），
 * 兜底策略交由调用方决定。
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
     * 主要人物档案（代词与既定事实）：如「角色丙（男，用「他」）；角色庚（女，用「她」）；角色己（女，用「她」）」
     * ＋既定事实（班级/年级/亲属/住址）。
     *
     * <p><b>为什么需要单独一条</b>：正文把某女性角色写成了「他」，连错数章 三章；
     * 而账本里既没有"该角色是女"的事实、也没有可判定的锚，审计想判"事实矛盾"都无从对照。
     * 性别与志愿这类**跨章不变的设定**必须由作者在 bible 里声明一次，靠模型从正文推断只会复制已有的错。
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
    }

    /**
     * 解析 bible 文本为快照；入参为 null/空/全无有效行时，返回字段全 null 的快照（不抛异常）
     */
    public static Snapshot parse(String bibleText) {
        Map<String, String> values = parseToMap(bibleText);
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

    /**
     * 按「标签: 值」切分。
     *
     * <p>只按<em>第一个 ASCII 冒号</em>切分：值本身可能含冒号，用 {@code split(":")} 会把值截断。
     * 中文全角冒号「：」不参与切分（书名如《道诡：理智尽头是疯狂》正是全角，天然安全）。
     */
    private static Map<String, String> parseToMap(String bibleText) {
        Map<String, String> values = new HashMap<>();
        if (bibleText == null || bibleText.isBlank()) {
            return values;
        }
        // \\R 覆盖 \n / \r\n / \r——bible 用 System.lineSeparator() 写入，Windows 上是 CRLF
        for (String line : bibleText.split("\\R")) {
            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String label = line.substring(0, idx).trim();
            String value = line.substring(idx + 1).trim();
            if (!label.isEmpty() && !value.isEmpty()) {
                values.put(label, value);
            }
        }
        return values;
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
