package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import cn.novel.yonren.domain.novel.model.valobj.prompt.ReferenceChunk;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 参考资料切块器：按 "## " 小节切块（标题+正文 = 一个可独立检索的语义单元）。
 * 无小节的资料整篇一块；有小节的资料，首个 "## " 之前的卷首独立成块（文档级导言）。
 */
@Component
public class ReferenceChunker {

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

}
