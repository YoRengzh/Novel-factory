package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import cn.novel.yonren.domain.novel.model.valobj.prompt.ReferenceChunk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切块器单测：按 ## 切块、无小节整篇、卷首独立成块、空内容
 */
class ReferenceChunkerTest {

    private final ReferenceChunker chunker = new ReferenceChunker();

    @Test
    void splitsBySecondLevelHeadingsWithFrontChunk() {
        String md = "# 去AI味判据\n\n导言一行\n\n## 一、结构\n内容甲\n\n## 二、用词\n内容乙\n";

        List<ReferenceChunk> chunks = chunker.chunk("anti-ai-tone", md);

        assertEquals(3, chunks.size());
        assertEquals("一、结构", chunks.get(0).sectionTitle());
        assertEquals("内容甲", chunks.get(0).content());
        assertEquals("二、用词", chunks.get(1).sectionTitle());
        // 卷首标题取一级标题
        assertEquals("去AI味判据", chunks.get(2).sectionTitle());
        assertTrue(chunks.get(0).embedText().contains("一、结构"));
    }

    @Test
    void noHeadingFileBecomesSingleChunk() {
        String md = "# 对话技巧\n\n整篇无小节的内容。\n第二行。";

        List<ReferenceChunk> chunks = chunker.chunk("dialogue", md);

        assertEquals(1, chunks.size());
        assertEquals("对话技巧", chunks.get(0).sectionTitle());
        assertEquals("# 对话技巧\n\n整篇无小节的内容。\n第二行。", chunks.get(0).content());
    }

    @Test
    void noHeadingAndNoTitleFallsBackToFileName() {
        List<ReferenceChunk> chunks = chunker.chunk("plain", "只有内容没有标题");

        assertEquals(1, chunks.size());
        assertEquals("plain", chunks.get(0).sectionTitle());
    }

    @Test
    void fileStartingDirectlyWithHeadingSkipsFrontChunk() {
        String md = "## 第一节\n内容一\n\n## 第二节\n内容二\n";

        List<ReferenceChunk> chunks = chunker.chunk("x", md);

        assertEquals(2, chunks.size());
        assertEquals("第一节", chunks.get(0).sectionTitle());
        assertEquals("第二节", chunks.get(1).sectionTitle());
    }

    @Test
    void blankContentYieldsNoChunks() {
        assertTrue(chunker.chunk("empty", "   \n  ").isEmpty());
    }

}
