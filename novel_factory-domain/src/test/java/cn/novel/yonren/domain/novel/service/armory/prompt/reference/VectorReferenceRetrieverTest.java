package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import cn.novel.yonren.domain.novel.adapter.llm.EmbeddingGateway;
import cn.novel.yonren.domain.novel.adapter.repository.VectorStore;
import cn.novel.yonren.domain.novel.model.valobj.ScoredVectorPoint;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.config.ReferenceSelectorProperties;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.types.enums.ChapterType;
import cn.novel.yonren.types.enums.PromptScene;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 向量检索器单测：懒加载索引一次、命中聚合去重、minScore 过滤、查询构造、失败上抛
 */
class VectorReferenceRetrieverTest {

    private EmbeddingGateway embeddingGateway;
    private VectorStore vectorStore;
    private ReferenceVectorIndexer indexer;
    private ReferenceSelectorProperties properties;
    private VectorReferenceRetriever retriever;

    @BeforeEach
    void setUp() {
        embeddingGateway = mock(EmbeddingGateway.class);
        vectorStore = mock(VectorStore.class);
        indexer = mock(ReferenceVectorIndexer.class);
        properties = new ReferenceSelectorProperties();
        retriever = new VectorReferenceRetriever(embeddingGateway, vectorStore, indexer, properties);
    }

    @Test
    void lazilyIndexesOnlyOnce() {
        when(embeddingGateway.embed(any(), anyList())).thenReturn(List.of(new float[]{1f}));
        when(vectorStore.search(anyString(), any(), anyInt())).thenReturn(List.of());

        retriever.retrieve(module(), PromptScene.CHAPTER_PLAN, planCtx());
        retriever.retrieve(module(), PromptScene.CHAPTER_PLAN, planCtx());

        verify(indexer, times(1)).index(any());
    }

    @Test
    void aggregatesHitsToDistinctFileNamesFilteringLowScores() {
        properties.setMinScore(0.5);
        when(embeddingGateway.embed(any(), anyList())).thenReturn(List.of(new float[]{1f}));
        when(vectorStore.search(anyString(), any(), anyInt())).thenReturn(List.of(
                ScoredVectorPoint.builder().id("u1").score(0.9).payload(Map.of("fileName", "hook")).build(),
                ScoredVectorPoint.builder().id("u2").score(0.7).payload(Map.of("fileName", "hook")).build(),
                ScoredVectorPoint.builder().id("u3").score(0.3).payload(Map.of("fileName", "pacing")).build()));

        List<String> names = retriever.retrieve(module(), PromptScene.CHAPTER_CONTENT, contentCtx());

        assertEquals(List.of("hook"), names);
    }

    @Test
    void indexFailurePropagatesForOrchestratorFallback() {
        when(indexer.index(any())).thenThrow(new IllegalStateException("qdrant down"));

        assertThrows(IllegalStateException.class,
                () -> retriever.retrieve(module(), PromptScene.CHAPTER_PLAN, planCtx()));
        verify(embeddingGateway, never()).embed(any(), anyList());
    }

    @Test
    void queryTextCarriesThemeStyleChapterType() {
        when(embeddingGateway.embed(any(), anyList())).thenReturn(List.of(new float[]{1f}));
        when(vectorStore.search(anyString(), any(), anyInt())).thenReturn(List.of());

        retriever.retrieve(module(), PromptScene.CHAPTER_CONTENT, contentCtx());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(embeddingGateway).embed(any(), captor.capture());
        String query = captor.getValue().get(0);
        assertTrue(query.contains("悬疑推理"));
        assertTrue(query.contains("刑侦"));
        assertTrue(query.contains("普通推进章"));
    }

    @Test
    void queryTextCarriesChapterBriefWhenPresent() {
        when(embeddingGateway.embed(any(), anyList())).thenReturn(List.of(new float[]{1f}));
        when(vectorStore.search(anyString(), any(), anyInt())).thenReturn(List.of());

        PromptContext ctx = PromptContext.builder().theme("悬疑推理").style("刑侦")
                .chapterNo(3).totalChapters(10).chapterType(ChapterType.NORMAL)
                .chapterBrief("真相初现；关键事件：翻案立案").build();
        retriever.retrieve(module(), PromptScene.CHAPTER_CONTENT, ctx);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(embeddingGateway).embed(any(), captor.capture());
        String query = captor.getValue().get(0);
        assertTrue(query.contains("本章要点"));
        assertTrue(query.contains("翻案立案"));
    }

    private PromptContext planCtx() {
        return PromptContext.builder().theme("悬疑推理").style("刑侦").totalChapters(10).build();
    }

    private PromptContext contentCtx() {
        return PromptContext.builder().theme("悬疑推理").style("刑侦")
                .chapterNo(3).totalChapters(10).chapterType(ChapterType.NORMAL).build();
    }

    private StoryVO.Module module() {
        StoryVO.Module module = new StoryVO.Module();
        StoryVO.Module.EmbeddingApi api = new StoryVO.Module.EmbeddingApi();
        api.setBaseUrl("https://example.com");
        api.setModel("embedding-3");
        module.setEmbeddingApi(api);
        return module;
    }

}
