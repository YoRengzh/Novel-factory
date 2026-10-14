package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.properties.ReferenceSelectorProperties;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import cn.novel.yonren.domain.novel.service.armory.prompt.valobj.PromptContext;
import cn.novel.yonren.domain.novel.service.armory.prompt.valobj.PromptRule;
import cn.novel.yonren.types.enums.ChapterTypeVO;
import cn.novel.yonren.types.enums.PromptScene;
import cn.novel.yonren.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 动态资料编排测试：开关短路、缓存命中、截断上限与缺文件跳过
 */
class DynamicReferenceServiceTest {

    private ReferenceIndexLoader referenceIndexLoader;
    private LlmReferenceSelector selector;
    private VectorReferenceRetriever retriever;
    private PromptRuleFileLoader fileLoader;
    private ReferenceSelectorProperties properties;
    private DynamicReferenceService service;

    @BeforeEach
    void setUp() {
        referenceIndexLoader = mock(ReferenceIndexLoader.class);
        selector = mock(LlmReferenceSelector.class);
        retriever = mock(VectorReferenceRetriever.class);
        fileLoader = mock(PromptRuleFileLoader.class);
        properties = new ReferenceSelectorProperties();
        service = new DynamicReferenceService(referenceIndexLoader, selector, retriever, fileLoader, properties);

        when(referenceIndexLoader.getIndex()).thenReturn(List.of());
        when(fileLoader.load(anyString())).thenReturn("资料内容");
    }

    @Test
    void disabledSwitchShortCircuitsWithoutSelection() {
        properties.setEnabled(false);

        List<PromptRule> rules = service.selectAsRules(null, PromptScene.CHAPTER_PLAN, planCtx());

        assertTrue(rules.isEmpty());
        verify(selector, never()).select(any(), any(), any(), anyList());
    }

    @Test
    void cacheReusesSelectionForSameSceneThemeChapterType() {
        when(selector.select(any(), any(), any(), anyList()))
                .thenReturn(List.of("dialogue-writing"));

        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterTypeVO.NORMAL));
        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterTypeVO.NORMAL));

        // 同 场景+题材+风格+章节类型+模型+索引 只调一次选择器
        verify(selector, times(1)).select(any(), any(), any(), anyList());
    }

    @Test
    void differentChapterTypeSelectsIndependently() {
        when(selector.select(any(), any(), any(), anyList()))
                .thenReturn(List.of("dialogue-writing"));

        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterTypeVO.NORMAL));
        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterTypeVO.CLIMAX));

        verify(selector, times(2)).select(any(), any(), any(), anyList());
    }

    @Test
    void differentThemeSelectsIndependently() {
        when(selector.select(any(), any(), any(), anyList()))
                .thenReturn(List.of("dialogue-writing"));

        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterTypeVO.NORMAL));
        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, PromptContext.builder()
                .theme("都市异能").style("刑侦")
                .chapterNo(3).totalChapters(10).chapterType(ChapterTypeVO.NORMAL).build());

        verify(selector, times(2)).select(any(), any(), any(), anyList());
    }

    @Test
    void differentChapterBriefSelectsIndependently() {
        when(selector.select(any(), any(), any(), anyList()))
                .thenReturn(List.of("dialogue-writing"));

        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterTypeVO.NORMAL, null));
        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterTypeVO.NORMAL, "揭开古物来历"));
        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterTypeVO.NORMAL, "揭开古物来历"));

        // 章节要点参与缓存键：不同本章要点各选一次，同要点命中缓存
        verify(selector, times(2)).select(any(), any(), any(), anyList());
    }

    @Test
    void differentModelSelectsIndependently() {
        when(selector.select(any(), any(), any(), anyList()))
                .thenReturn(List.of("dialogue-writing"));

        service.selectAsRules(module("https://api-a", "model-1"), PromptScene.CHAPTER_CONTENT, contentCtx(ChapterTypeVO.NORMAL));
        service.selectAsRules(module("https://api-a", "model-2"), PromptScene.CHAPTER_CONTENT, contentCtx(ChapterTypeVO.NORMAL));

        verify(selector, times(2)).select(any(), any(), any(), anyList());
    }

    @Test
    void indexChangeInvalidatesCache() {
        when(selector.select(any(), any(), any(), anyList()))
                .thenReturn(List.of("dialogue-writing"));
        when(referenceIndexLoader.getIndex())
                .thenReturn(List.of(new ReferenceIndexEntry("a", "标题A", null, "references/a.md")))
                .thenReturn(List.of(new ReferenceIndexEntry("b", "标题B", null, "references/b.md")));

        service.selectAsRules(null, PromptScene.CHAPTER_PLAN, planCtx());
        service.selectAsRules(null, PromptScene.CHAPTER_PLAN, planCtx());

        // 索引内容变化（资料增删/改写）→ 缓存失效重新选择
        verify(selector, times(2)).select(any(), any(), any(), anyList());
    }

    @Test
    void cacheCapacityEvictsLeastRecentlyUsed() {
        properties.setCacheMaxEntries(1);
        when(selector.select(any(), any(), any(), anyList()))
                .thenReturn(List.of("dialogue-writing"));

        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterTypeVO.NORMAL));
        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterTypeVO.CLIMAX));
        // 容量 1：NORMAL 键已被淘汰，重新请求需再次选择
        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterTypeVO.NORMAL));

        verify(selector, times(3)).select(any(), any(), any(), anyList());
    }

    @Test
    void rulesWrappedAsUserTailWithRefPrefix() {
        when(selector.select(any(), any(), any(), anyList()))
                .thenReturn(List.of("dialogue-writing"));

        List<PromptRule> rules = service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterTypeVO.NORMAL));

        assertEquals(1, rules.size());
        assertEquals("ref-dialogue-writing", rules.get(0).getName());
        assertEquals(PromptRule.InjectPosition.USER_TAIL, rules.get(0).getInjectPosition());
    }

    @Test
    void maxSelectedTruncatesRules() {
        properties.setMaxSelected(2);
        when(selector.select(any(), any(), any(), anyList()))
                .thenReturn(List.of("a", "b", "c"));

        List<PromptRule> rules = service.selectAsRules(null, PromptScene.CHAPTER_PLAN, planCtx());

        assertEquals(2, rules.size());
        verify(fileLoader, never()).load(eq("references/c.md"));
    }

    @Test
    void maxRecallCharsBoundsInjectedContent() {
        properties.setMaxRecallChars(12);
        when(selector.select(any(), any(), any(), anyList()))
                .thenReturn(List.of("dialogue-writing"));
        when(fileLoader.load("references/dialogue-writing.md"))
                .thenReturn("一二三四五六七八九十十一十二十三十四");

        List<PromptRule> rules = service.selectAsRules(null, PromptScene.CHAPTER_PLAN, planCtx());

        assertEquals(1, rules.size());
        assertTrue(rules.get(0).getContent().length() <= 12);
    }

    @Test
    void missingFileSkippedWithoutBreaking() {
        when(selector.select(any(), any(), any(), anyList()))
                .thenReturn(List.of("dialogue-writing", "content-expansion"));
        when(fileLoader.load("references/dialogue-writing.md")).thenReturn(null);

        List<PromptRule> rules = service.selectAsRules(null, PromptScene.CHAPTER_PLAN, planCtx());

        assertEquals(1, rules.size());
        assertEquals("ref-content-expansion", rules.get(0).getName());
    }

    @Test
    void vectorModeRoutesToRetrieverWithoutLlmCall() {
        properties.setMode("vector");
        when(retriever.retrieve(any(), any(), any()))
                .thenReturn(List.of("dialogue-writing"));

        List<PromptRule> rules = service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterTypeVO.NORMAL));

        assertEquals(1, rules.size());
        assertEquals("ref-dialogue-writing", rules.get(0).getName());
        verify(selector, never()).select(any(), any(), any(), anyList());
    }

    @Test
    void vectorFailureTerminatesWithoutLlmFallback() {
        properties.setMode("vector");
        when(retriever.retrieve(any(), any(), any()))
                .thenThrow(new IllegalStateException("qdrant down"));

        // 快速失败：向量链路任何失败（欠费/向量库/网络）都终止作业，绝不降级 LLM 选择
        AppException ex = assertThrows(AppException.class,
                () -> service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterTypeVO.NORMAL)));

        assertTrue(ex.getInfo().contains("不降级"));
        verify(selector, never()).select(any(), any(), any(), anyList());
        verify(retriever, times(1)).retrieve(any(), any(), any());
    }

    private PromptContext planCtx() {
        return PromptContext.builder().theme("悬疑推理").style("刑侦").totalChapters(10).build();
    }

    private PromptContext contentCtx(ChapterTypeVO chapterType) {
        return contentCtx(chapterType, null);
    }

    private PromptContext contentCtx(ChapterTypeVO chapterType, String chapterBrief) {
        return PromptContext.builder().theme("悬疑推理").style("刑侦")
                .chapterNo(3).totalChapters(10).chapterType(chapterType).chapterBrief(chapterBrief).build();
    }

    private StoryVO.Module module(String baseUrl, String model) {
        StoryVO.Module module = new StoryVO.Module();
        StoryVO.Module.AiApi aiApi = new StoryVO.Module.AiApi();
        aiApi.setBaseUrl(baseUrl);
        module.setAiApi(aiApi);
        StoryVO.Module.ChatModel chatModel = new StoryVO.Module.ChatModel();
        chatModel.setModel(model);
        module.setChatModel(chatModel);
        return module;
    }

}
