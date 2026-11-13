package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import cn.novel.yonren.domain.novel.model.valobj.prompt.ReferenceChunk;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 参考资料切块器：按 "## " 小节切块（标题+正文 = 一个可独立检索的语义单元）。
 * 无小节的资料整篇一块；有小节的资料，首个 "## " 之前的卷首独立成块（文档级导言）。
 *
 * 旧检索块与新注入原子使用两个独立入口：{@link #chunk(String, String)} 保持既有
 * 文件级集合的内容与顺序；原子入口使用围栏感知的结构解析，不把表格/围栏里的伪标题当小节。
 */
@Component
public class ReferenceChunker {

    private static final Pattern ATX_HEADING = Pattern.compile("^ {0,3}(#{1,6})(?:[ \\t]+(.*?)|[ \\t]*)$");
    private static final Pattern FENCE_LINE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");

    public List<ReferenceChunk> chunk(String fileName, String content) {
        List<ReferenceChunk> chunks = new ArrayList<>();
        if (StringUtils.isBlank(content)) {
            return chunks;
        }

        StringBuilder front = new StringBuilder();
        String currentTitle = null;
        StringBuilder currentBody = new StringBuilder();

        for (String line : content.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("## ")) {
                if (currentTitle != null) {
                    addIfNotEmpty(chunks, fileName, currentTitle, currentBody.toString());
                }
                currentTitle = trimmed.substring(3).trim();
                currentBody.setLength(0);
            } else if (currentTitle != null) {
                currentBody.append(line).append('\n');
            } else {
                front.append(line).append('\n');
            }
        }

        if (currentTitle == null) {
            // 全文无小节：整篇一块
            addIfNotEmpty(chunks, fileName, firstHeadingTitle(front.toString(), fileName), front.toString());
            return chunks;
        }
        addIfNotEmpty(chunks, fileName, currentTitle, currentBody.toString());
        addIfNotEmpty(chunks, fileName, firstHeadingTitle(front.toString(), fileName), front.toString());
        return chunks;
    }

    private void addIfNotEmpty(List<ReferenceChunk> chunks, String fileName, String title, String body) {
        if (StringUtils.isBlank(body)) {
            return;
        }
        chunks.add(new ReferenceChunk(fileName, title, body.trim()));
    }

    private String firstHeadingTitle(String text, String fallback) {
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ") && !trimmed.startsWith("## ")) {
                return trimmed.substring(2).trim();
            }
        }
        return fallback;
    }

    /** 原子入口：原文顺序的 H2；不含卷首，不识别代码围栏或表格内的伪标题。 */
    List<Section> sections(String content) {
        return structure(content, 2).stream().filter(section -> section.title() != null).toList();
    }

    /** 首个真实 H2 之前的全部原文（包括一级标题）。 */
    String frontMatter(String content) {
        List<Section> sections = structure(content, 2);
        return !sections.isEmpty() && sections.get(0).title() == null ? sections.get(0).body() : "";
    }

    /** H3 前文作为 title=null 的首段返回，遇到首个 H3 时不能丢弃。 */
    List<Section> h3Sections(String content) {
        return structure(content, 3);
    }

    private List<Section> structure(String content, int level) {
        List<Section> out = new ArrayList<>();
        if (StringUtils.isBlank(content)) {
            return out;
        }
        String title = null;
        String heading = "";
        StringBuilder body = new StringBuilder();
        Fence fence = new Fence();
        boolean inTable = false;
        // 保留原始换行、标题行与末尾；结构解析不重写 Markdown 内容。
        String[] lines = content.split("(?<=\n)", -1);
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String line = withoutLineEnding(raw);
            boolean protectedLine = fence.consume(line);
            if (!protectedLine) {
                if (inTable && (!hasPipe(line) || line.isBlank())) {
                    inTable = false;
                }
                if (!inTable && hasPipe(line) && i + 1 < lines.length
                        && tableDelimiter(withoutLineEnding(lines[i + 1]))) {
                    inTable = true;
                }
                Matcher matcher = ATX_HEADING.matcher(line);
                if (!inTable && matcher.matches() && matcher.group(1).length() == level) {
                    if (title != null || body.length() > 0) {
                        out.add(new Section(level, title, body.toString(), heading));
                    }
                    title = matcher.group(2) == null ? ""
                            : matcher.group(2).replaceFirst("[ \\t]+#+[ \\t]*$", "").trim();
                    heading = raw;
                    body.setLength(0);
                    continue;
                }
            }
            body.append(raw);
        }
        if (title != null || body.length() > 0) {
            out.add(new Section(level, title, body.toString(), heading));
        }
        return out;
    }

    boolean hasUnclosedFence(String content) {
        Fence fence = new Fence();
        for (String line : content.split("\n", -1)) {
            fence.consume(withoutLineEnding(line));
        }
        return fence.open();
    }

    private static String withoutLineEnding(String line) {
        int end = line.length();
        if (end > 0 && line.charAt(end - 1) == '\n') {
            end--;
        }
        if (end > 0 && line.charAt(end - 1) == '\r') {
            end--;
        }
        return line.substring(0, end);
    }

    private static boolean hasPipe(String line) {
        return line.replace("\\|", "").contains("|");
    }

    private static boolean tableDelimiter(String line) {
        String row = line.trim();
        if (row.startsWith("|")) {
            row = row.substring(1);
        }
        if (row.endsWith("|")) {
            row = row.substring(0, row.length() - 1);
        }
        if (row.isBlank()) {
            return false;
        }
        for (String cell : row.split("\\|", -1)) {
            if (!cell.trim().matches(":?-+:?")) {
                return false;
            }
        }
        return true;
    }

    /** 同字符、长度不少于开栏、后面只有空白的围栏才闭合；另种字符/短围栏均是内容。 */
    private static final class Fence {
        private char marker;
        private int length;

        boolean open() {
            return length > 0;
        }

        boolean consume(String line) {
            Matcher match = FENCE_LINE.matcher(line);
            if (open()) {
                if (match.matches() && match.group(1).charAt(0) == marker
                        && match.group(1).length() >= length && match.group(2).isBlank()) {
                    length = 0;
                }
                return true;
            }
            if (!match.matches()) {
                return false;
            }
            String run = match.group(1);
            // CommonMark：反引号开栏的 info string 不得再含反引号。
            if (run.charAt(0) == '`' && match.group(2).contains("`")) {
                return false;
            }
            marker = run.charAt(0);
            length = run.length();
            return true;
        }
    }

    /** heading 含原始标题行及换行；body 不含标题。无标题前文的 heading 为空。 */
    record Section(int level, String title, String body, String heading) {
        String content() {
            return heading + body;
        }
    }

}
