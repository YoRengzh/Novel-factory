package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import cn.novel.yonren.domain.novel.model.valobj.prompt.ReferenceIndexEntry;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.types.enums.ChapterType;
import cn.novel.yonren.types.enums.PromptScene;
import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 选择器测试：白名单过滤、编造文件名剔除、解析失败与异常降级
 */
class LlmReferenceSelectorTest {

    private final LlmGateway llmGateway = mock(LlmGateway.class);
    private final LlmReferenceSelector selector = new LlmReferenceSelector(llmGateway);

    private final List<ReferenceIndexEntry> index = List.of(
            new ReferenceIndexEntry("dialogue-writing", "对话写作规范", "好对话是揭示人物、推动情节、制造冲突的有力工具", "references/dialogue-writing.md"),
            new ReferenceIndexEntry("content-expansion", "内容扩充技巧", "当章节内容不足时使用以下技巧自然扩充", "references/content-expansion.md"));

    private final PromptContext ctx = PromptContext.builder()
            .theme("悬疑").style("推理").chapterNo(3).totalChapters(10)
            .chapterType(ChapterType.CLIMAX).build();

    @Test
    void select_filtersToWhitelist() {
        stubModel("{\"references\":[\"dialogue-writing\",\"made-up-name\",\"../../etc/passwd\"]}");

        List<String> selected = selector.select(null, PromptScene.CHAPTER_CONTENT, ctx, index);

        // 编造名与路径穿越名都被白名单剔除，仅保留索引内文件
        assertEquals(List.of("dialogue-writing"), selected);
    }

    @Test
    void select_keepsValidOrderAndDedupes() {
        stubModel("{\"references\":[\"content-expansion\",\"dialogue-writing\",\"content-expansion\"]}");

        List<String> selected = selector.select(null, PromptScene.CHAPTER_CONTENT, ctx, index);

        assertEquals(List.of("content-expansion", "dialogue-writing"), selected);
    }

    @Test
    void select_invalidJsonFallsBackToEmpty() {
        stubModel("抱歉，我无法以 JSON 格式回答");

        assertTrue(selector.select(null, PromptScene.CHAPTER_PLAN, ctx, index).isEmpty());
    }

    @Test
    void select_modelErrorFallsBackToEmpty() {
        when(llmGateway.complete(any(), any(LlmCall.class))).thenThrow(new RuntimeException("timeout"));

        assertTrue(selector.select(null, PromptScene.CHAPTER_PLAN, ctx, index).isEmpty());
    }

    @Test
    void select_emptyIndexSkipsModelCall() {
        List<String> selected = selector.select(null, PromptScene.CHAPTER_PLAN, ctx, List.of());

        assertTrue(selected.isEmpty());
        verify(llmGateway, never()).complete(any(), any(LlmCall.class));
    }

    private void stubModel(String content) {
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(content);
    }

}
