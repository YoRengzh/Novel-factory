package cn.novel.yonren.types.utils;

import java.util.regex.Pattern;

/**
 * 模型 JSON 输出修复工具：仅用于已解析失败的文本（最坏结果仍是失败，不会破坏成功路径）。
 * 正文与摘要两级结构化输出共用
 */
public final class JsonRepair {

    /**
     * 键名吞逗号病症："title,"身世" → "title":"身世"（键闭合引号丢失、逗号被吞进键内）。
     * 仅当引号前是 { 或 ,（键位置）且闭合引号后跟非边界字符（值内容）时触发；
     * 键内容排除 : 防止贪婪吸收前一个 "field":value, 前缀。
     */
    private static final Pattern COMMA_IN_KEY = Pattern.compile("(?<=[{,])(\\s*)\"([^\":]*?),\"(?=[^,\\]}\\s:])");

    private JsonRepair() {
    }

    /**
     * 修复模型 JSON 输出的常见病症：
     * ① markdown 代码块包裹；② 悬空逗号（",}" / ",]"）；
     * ③ 前后缀杂文剥离——模型偶尔先解说后输出 JSON（实测"我先对齐上一章的桌面状态…{"chapterNo":…}"），
     * 截取首个 {/[ 到最后一个 }/] 再试。截错（杂文里恰有花括号）只会照旧解析失败走下一级降级，无副作用
     */
    public static String repair(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceAll("^```(json)?\\s*", "").replaceAll("```\\s*$", "").trim();
        }
        text = text.replaceAll(",\\s*}", "}").replaceAll(",\\s*]", "]");
        if (!text.startsWith("{") && !text.startsWith("[")) {
            int obj = text.indexOf('{');
            int arr = text.indexOf('[');
            int first = obj < 0 ? arr : (arr < 0 ? obj : Math.min(obj, arr));
            if (first > 0) {
                char close = text.charAt(first) == '{' ? '}' : ']';
                int last = text.lastIndexOf(close);
                if (last > first) {
                    text = text.substring(first, last + 1);
                }
            }
        }
        return balanceUnclosed(text);
    }

    /**
     * 补齐模型 JSON 输出的未闭合括号/引号——正文最常见的截断病症：写完最后一个字符串值后，
     * 缺了闭合的 } 甚至 "。状态机统计未闭合的 { [ 与字符串状态，按 JSON 语法顺序在末尾补齐。
     * 括号已平衡、字符串已闭合则原样返回（不破坏成功路径）；补错最坏仍是解析失败走下一级降级。
     */
    private static String balanceUnclosed(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        int brace = 0;
        int bracket = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                brace++;
            } else if (c == '}') {
                if (brace > 0) {
                    brace--;
                }
            } else if (c == '[') {
                bracket++;
            } else if (c == ']') {
                if (bracket > 0) {
                    bracket--;
                }
            }
        }
        // 字符串未闭合（内容可能不完整，如"写到一半"）→ 不修复，交给调用方决定（宁可不抢救半截垃圾）
        if (inString) {
            return text;
        }
        // 括号平衡 → 无需修复，原样返回
        if (brace == 0 && bracket == 0) {
            return text;
        }
        // 仅补外层括号：真实截断故障是"最后一个字符串已闭合、只缺外层 } 或 ]"
        StringBuilder sb = new StringBuilder(text);
        for (int i = 0; i < bracket; i++) {
            sb.append(']');
        }
        for (int i = 0; i < brace; i++) {
            sb.append('}');
        }
        return sb.toString();
    }

    /**
     * 转义 JSON 字符串值内的裸双引号（模型常见病症：在值里直接写 " 而不转义）。
     * 状态机逐字符扫描：字符串外 " 视为结构引号，字符串内 " 若后跟 JSON 边界（,]}:）视为关闭，
     * 否则视为内嵌引号并补 \ 转义。已转义的 \" 不动。
     */
    public static String escapeInteriorQuotes(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        boolean inString = false;

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);

            if (inString) {
                if (c == '\\') {
                    sb.append(c);
                    if (i + 1 < raw.length()) {
                        sb.append(raw.charAt(++i));
                    }
                    continue;
                }
                if (c == '"') {
                    int j = i + 1;
                    while (j < raw.length() && isJsonWhitespace(raw.charAt(j))) {
                        j++;
                    }
                    if (j < raw.length() && isJsonBoundary(raw.charAt(j))) {
                        inString = false;
                        sb.append(c);
                    } else {
                        sb.append('\\');
                        sb.append(c);
                    }
                    continue;
                }
                sb.append(c);
            } else {
                if (c == '"') {
                    inString = true;
                }
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isJsonWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static boolean isJsonBoundary(char c) {
        return c == ',' || c == ']' || c == '}' || c == ':';
    }

    /**
     * 修复键名吞逗号病症："title,"身世" → "title":"身世"。
     * 必须在 escapeInteriorQuotes 之前调用（后者会把 "title,"身世" 转义成无法再修复的形态）。
     */
    public static String fixCommaInKey(String raw) {
        if (raw == null) {
            return null;
        }
        return COMMA_IN_KEY.matcher(raw).replaceAll("$1\"$2\":\"");
    }

    /**
     * 从（可能损坏的）JSON 文本中抢救指定字符串字段的值，字段不存在返回 null。
     * 抠出的值已做转义还原（换行 / 制表 / 回车 / 引号 / 反斜杠 / 斜杠 / unicode 四位转义）。
     * 实现为 indexOf 键定位 + 状态机逐字符扫值（非正则）：字符串值可达数千字，
     * 交替量词正则 ((?:[^"\\]|\\.)*) 按字符递归匹配，正文级长度会耗尽线程栈——
     * 实测第 62 章 content ~8 千字触发 StackOverflowError 致整批作业暴毙
     */
    public static String extractStringField(String raw, String fieldName) {
        return extractStringField(raw, fieldName, false);
    }

    /**
     * 同 {@link #extractStringField}，但可取**首个非空白值**而非首个出现的值。
     *
     * <p><b>为什么需要它</b>：模型偶尔输出**重复键**——先写完整正文，再补一个
     * {@code "content":""}。JSON 库对重复键取后一个值，于是"解析成功但正文为空"；
     * 而这里按出现顺序扫，能取到前面那个真正的正文。没有这个变体时，
     * 一条本可救回的正文会因为"空值也算取到了"而丢掉。
     *
     * @param skipBlank true = 跳过空白值继续找下一个同名键；false = 首个出现即返回（旧行为）
     */
    public static String extractStringField(String raw, String fieldName, boolean skipBlank) {
        String text = repair(raw);
        if (text == null || fieldName == null || fieldName.isBlank()) {
            return null;
        }
        String key = "\"" + fieldName.trim() + "\"";
        int keyIdx = text.indexOf(key);
        while (keyIdx >= 0) {
            int colon = nextNonWhitespace(text, keyIdx + key.length());
            if (colon >= 0 && text.charAt(colon) == ':') {
                int valueStart = nextNonWhitespace(text, colon + 1);
                if (valueStart >= 0 && text.charAt(valueStart) == '"') {
                    String value = scanStringBody(text, valueStart + 1);
                    if (value != null && (!skipBlank || !value.isBlank())) {
                        return value;
                    }
                }
            }
            keyIdx = text.indexOf(key, keyIdx + key.length());
        }
        return null;
    }

    /** 自 from 起首个非空白字符下标；无则 -1 */
    private static int nextNonWhitespace(String text, int from) {
        for (int i = Math.max(0, from); i < text.length(); i++) {
            if (!isJsonWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 扫描 JSON 字符串体（自开引号后一位起），处理常见转义，遇未转义闭引号结束；
     * 未闭合（截断输出）返回 null——与旧正则语义一致，宁可不抢救也不返回半截垃圾
     */
    private static String scanStringBody(String text, int from) {
        StringBuilder sb = new StringBuilder();
        int i = from;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'u' -> {
                        if (i + 5 < text.length()) {
                            try {
                                sb.append((char) Integer.parseInt(text, i + 2, i + 6, 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                sb.append(next);
                            }
                        } else {
                            sb.append(next);
                        }
                    }
                    default -> sb.append(next); // \" \\ \/ 等：去转义保留字面
                }
                i += 2;
                continue;
            }
            if (c == '"') {
                return sb.toString();
            }
            sb.append(c);
            i++;
        }
        return null;
    }

}
