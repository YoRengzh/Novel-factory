package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.VectorPoint;
import cn.novel.yonren.domain.novel.model.valobj.prompt.ReferenceIndexEntry;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReferenceVectorIndexerTest {

    private ReferenceIndexLoader indexLoader;
    private PromptRuleFileLoader fileLoader;
    private ReferenceTestFixtures.Embedding embedding;
    private ReferenceTestFixtures.Store store;
    private ReferenceVectorIndexer indexer;

    @BeforeEach
    void setUp() {
        indexLoader = mock(ReferenceIndexLoader.class);
        fileLoader = mock(PromptRuleFileLoader.class);
        embedding = new ReferenceTestFixtures.Embedding();
        store = new ReferenceTestFixtures.Store();
        indexer = newIndexer();
    }

    @Test
    void indexesChunksWithStableIdsTextAndCompleteVersionPayload() {
        when(indexLoader.getIndex()).thenReturn(List.of(entry("craft-a")));
        when(indexLoader.corpusVersion()).thenReturn("full-corpus-r0");
        when(fileLoader.load("references/craft-a.md")).thenReturn("## 小节一\n内容甲\n\n## 小节二\n内容乙\n");

        assertEquals(2, indexer.index(module()));
        var points = List.copyOf(store.points(ReferenceVectorIndexer.COLLECTION).values());
        assertEquals("craft-a", points.get(0).getPayload().get("fileName"));
        assertEquals("小节一", points.get(0).getPayload().get("sectionTitle"));
        assertEquals("内容甲", points.get(0).getPayload().get("text"));
        assertEquals("fake-space", points.get(0).getPayload().get("embeddingSignature"));
        assertNotNull(points.get(0).getPayload().get("corpusVersion"));
        assertNotNull(points.get(0).getPayload().get("indexVersion"));
        assertTrue(points.get(0).getPayload().get("kind").startsWith("source-index:"));
        assertEquals(ReferenceVectorIndexer.stableId("craft-a", 0), points.get(0).getId());
        assertEquals(3, store.dimensions.get(ReferenceVectorIndexer.COLLECTION));
    }

    @Test
    void fileLocalIdsStayStableWhenEarlierFileIsDeletedAndSectionsShrink() {
        when(indexLoader.getIndex()).thenReturn(List.of(entry("craft-a"), entry("craft-b")));
        when(fileLoader.load("references/craft-a.md")).thenReturn("## A\n甲");
        when(fileLoader.load("references/craft-b.md")).thenReturn("## B一\n乙\n\n## B二\n丙");
        assertEquals(3, indexer.index(module()));
        String retainedId = ReferenceVectorIndexer.stableId("craft-b", 0);
        assertTrue(store.points(ReferenceVectorIndexer.COLLECTION).containsKey(retainedId));

        when(indexLoader.getIndex()).thenReturn(List.of(entry("craft-b")));
        when(fileLoader.load("references/craft-b.md")).thenReturn("## B一\n新乙");
        assertEquals(1, indexer.index(module()));
        assertEquals(Set.of(retainedId), store.points(ReferenceVectorIndexer.COLLECTION).keySet());
        assertEquals("新乙", store.points(ReferenceVectorIndexer.COLLECTION).get(retainedId).getPayload().get("text"));
        assertEquals(2, store.deletions);
    }

    @Test
    void restartReconcilesUnknownLegacyIdsAndDeletedSourceRatherThanOnlyUpserting() {
        when(indexLoader.getIndex()).thenReturn(List.of(entry("craft-a"), entry("craft-b")));
        when(fileLoader.load(anyString())).thenReturn("## 内容\n正文");
        indexer.index(module());
        store.points(ReferenceVectorIndexer.COLLECTION).put("old-global-id", VectorPoint.builder()
                .id("old-global-id").vector(new float[]{1, 1, 1}).payload(Map.of("fileName", "gone-source")).build());
        when(indexLoader.getIndex()).thenReturn(List.of(entry("craft-b")));

        assertEquals(1, newIndexer().index(module()));
        assertEquals(Set.of(ReferenceVectorIndexer.stableId("craft-b", 0)),
                store.points(ReferenceVectorIndexer.COLLECTION).keySet());
    }

    @Test
    void laterInvalidBatchLeavesOldIndexUntouched() {
        when(indexLoader.getIndex()).thenReturn(List.of(entry("craft-a")));
        when(fileLoader.load(anyString())).thenReturn("## 原节\n原文");
        indexer.index(module());
        Map<String, VectorPoint> before = Map.copyOf(store.points(ReferenceVectorIndexer.COLLECTION));
        StringBuilder changed = new StringBuilder();
        for (int i = 0; i < 18; i++) {
            changed.append("## 节").append(i).append("\n内容").append(i).append("\n\n");
        }
        when(fileLoader.load(anyString())).thenReturn(changed.toString());
        embedding.invalidAtCall = embedding.calls + 2;
        int writes = store.writes;

        assertThrows(IllegalStateException.class, () -> indexer.index(module()));
        assertEquals(writes, store.writes);
        assertEquals(before, store.points(ReferenceVectorIndexer.COLLECTION));
        assertEquals(0, store.deletions);
    }

    @Test
    void wrongEmbeddingCountIsRejectedBeforeAnyStoreWrite() {
        when(indexLoader.getIndex()).thenReturn(List.of(entry("craft-a")));
        when(fileLoader.load(anyString())).thenReturn("## 节\n正文");
        embedding.wrongCountAtCall = 1;
        assertThrows(IllegalStateException.class, () -> indexer.index(module()));
        assertEquals(0, store.writes);
        assertEquals(0, store.clears);
    }

    @Test
    void emptyOrAllDisabledCorpusClearsOldPointsWithoutEmbeddingSession() {
        when(indexLoader.getIndex()).thenReturn(List.of(entry("craft-a")));
        when(fileLoader.load(anyString())).thenReturn("## 节\n正文");
        indexer.index(module());
        int calls = embedding.calls;
        int sessions = embedding.sessionCalls;
        when(indexLoader.getIndex()).thenReturn(List.of());
        assertEquals(0, indexer.index(module()));
        assertEquals(0, store.count(ReferenceVectorIndexer.COLLECTION));
        assertEquals(calls, embedding.calls);
        assertEquals(sessions, embedding.sessionCalls);
    }

    @Test
    void blankFilesAreSkippedAndEmptyIndexIsCleaned() {
        when(indexLoader.getIndex()).thenReturn(List.of(entry("blank")));
        when(fileLoader.load(anyString())).thenReturn(null);
        assertEquals(0, indexer.index(module()));
        assertEquals(0, store.writes);
        assertEquals(0, embedding.calls);
    }

    @Test
    void spaceChangeNeedsExplicitRebuildAndInvalidRebuildPreservesOldPoints() {
        when(indexLoader.getIndex()).thenReturn(List.of(entry("craft-a")));
        when(fileLoader.load(anyString())).thenReturn("## 节\n正文");
        indexer.index(module());
        embedding.signature = "new-space";
        embedding.dimensions = 4;
        embedding.actualDimensions = 4;
        int calls = embedding.calls;
        assertThrows(IllegalStateException.class, () -> indexer.index(module()));
        assertEquals(calls, embedding.calls, "空间变化不能暗中收重嵌成本");
        embedding.invalidAtCall = calls + 1;
        assertThrows(IllegalStateException.class, () -> indexer.rebuild(module()));
        assertEquals(0, store.clears);
        assertEquals(3, store.dimensions.get(ReferenceVectorIndexer.COLLECTION));
        embedding.invalidAtCall = -1;
        assertEquals(1, indexer.rebuild(module()));
        assertEquals(4, store.dimensions.get(ReferenceVectorIndexer.COLLECTION));
    }

    @Test
    void frozenSessionOverloadDoesNotResolveConfigurationAgainAndInfersDimensions() {
        when(indexLoader.getIndex()).thenReturn(List.of(entry("craft-a")));
        when(fileLoader.load(anyString())).thenReturn("## 节\n正文");
        embedding.dimensions = null;
        var session = embedding.session(module());
        embedding.signature = "changed-after-freeze";
        embedding.actualDimensions = 8;
        assertEquals(1, indexer.index(module(), session));
        assertEquals(1, embedding.sessionCalls);
        assertEquals(3, store.dimensions.get(ReferenceVectorIndexer.COLLECTION));
        assertEquals("fake-space", store.points(ReferenceVectorIndexer.COLLECTION).values().iterator().next()
                .getPayload().get("embeddingSignature"));
    }

    @Test
    void exactPostWriteReconciliationDetectsFailedDeletion() {
        when(indexLoader.getIndex()).thenReturn(List.of(entry("craft-a"), entry("craft-b")));
        when(fileLoader.load(anyString())).thenReturn("## 节\n正文");
        indexer.index(module());
        when(indexLoader.getIndex()).thenReturn(List.of(entry("craft-b")));
        store.ignoreDeletes = true;
        assertThrows(IllegalStateException.class, () -> indexer.index(module()));
    }

    private ReferenceVectorIndexer newIndexer() {
        return new ReferenceVectorIndexer(indexLoader, fileLoader, new ReferenceChunker(), embedding, store);
    }

    private static ReferenceIndexEntry entry(String file) {
        return new ReferenceIndexEntry(file, file, "", "references/" + file + ".md");
    }

    private static StoryVO.Module module() {
        return new StoryVO.Module();
    }
}
