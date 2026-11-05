package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import cn.novel.yonren.domain.novel.model.valobj.prompt.ReferenceIndexEntry;

import cn.novel.yonren.domain.novel.adapter.llm.EmbeddingGateway;
import cn.novel.yonren.domain.novel.adapter.repository.VectorStore;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.VectorPoint;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * 索引器单测：切块→批量向量化→幂等 upsert（稳定 id、payload、维度）
 */
class ReferenceVectorIndexerTest {

    private ReferenceIndexLoader indexLoader;
    private PromptRuleFileLoader fileLoader;
    private EmbeddingGateway embeddingGateway;
    private VectorStore vectorStore;
    private ReferenceVectorIndexer indexer;

    @BeforeEach
    void setUp() {
        indexLoader = mock(ReferenceIndexLoader.class);
        fileLoader = mock(PromptRuleFileLoader.class);
        embeddingGateway = mock(EmbeddingGateway.class);
        vectorStore = mock(VectorStore.class);
        indexer = new ReferenceVectorIndexer(indexLoader, fileLoader, new ReferenceChunker(),
                embeddingGateway, vectorStore);
    }

    @Test
    void indexesChunksWithStableIdsAndPayload() {
        when(indexLoader.getIndex()).thenReturn(List.of(
                new ReferenceIndexEntry("craft-a", "标题A", null, "references/craft-a.md")));
        when(fileLoader.load("references/craft-a.md"))
                .thenReturn("## 小节一\n内容甲\n\n## 小节二\n内容乙\n");
        when(embeddingGateway.embed(any(), anyList()))
                .thenReturn(List.of(new float[]{1f, 2f}, new float[]{3f, 4f}));

        int count = indexer.index(module());

        assertEquals(2, count);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VectorPoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).upsert(eq(ReferenceVectorIndexer.COLLECTION), captor.capture());
        List<VectorPoint> points = captor.getValue();
        assertEquals(2, points.size());
        assertEquals("craft-a", points.get(0).getPayload().get("fileName"));
        assertEquals("小节一", points.get(0).getPayload().get("sectionTitle"));
        assertEquals(1f, points.get(0).getVector()[0]);
        verify(vectorStore).ensureCollection(ReferenceVectorIndexer.COLLECTION, 2);
    }

    @Test
    void stableIdsAcrossRuns() {
        when(indexLoader.getIndex()).thenReturn(List.of(
                new ReferenceIndexEntry("craft-a", "标题A", null, "references/craft-a.md")));
        when(fileLoader.load(anyString())).thenReturn("## 小节一\n内容甲\n");
        when(embeddingGateway.embed(any(), anyList()))
                .thenReturn(List.of(new float[]{1f}));

        indexer.index(module());
        indexer.index(module());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VectorPoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(2)).upsert(eq(ReferenceVectorIndexer.COLLECTION), captor.capture());
        String firstRunId = captor.getAllValues().get(0).get(0).getId();
        String secondRunId = captor.getAllValues().get(1).get(0).getId();
        assertEquals(firstRunId, secondRunId);
    }

    @Test
    void blankFilesSkipped() {
        when(indexLoader.getIndex()).thenReturn(List.of(
                new ReferenceIndexEntry("blank", "标题", null, "references/blank.md")));
        when(fileLoader.load(anyString())).thenReturn(null);

        int count = indexer.index(module());

        assertEquals(0, count);
        verify(vectorStore, never()).upsert(anyString(), anyList());
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
