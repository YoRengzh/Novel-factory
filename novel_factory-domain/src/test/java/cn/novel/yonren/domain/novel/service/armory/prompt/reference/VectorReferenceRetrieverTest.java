package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import cn.novel.yonren.domain.novel.config.ReferenceSelectorProperties;
import cn.novel.yonren.domain.novel.model.valobj.ScoredVectorPoint;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.domain.novel.model.valobj.prompt.ReferenceIndexEntry;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import cn.novel.yonren.types.enums.ChapterType;
import cn.novel.yonren.types.enums.PromptScene;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VectorReferenceRetrieverTest {

    @TempDir
    Path temp;
    private ReferenceIndexLoader loader;
    private PromptRuleFileLoader files;
    private ReferenceTestFixtures.Embedding embedding;
    private ReferenceTestFixtures.Store store;
    private ReferenceVectorIndexer indexer;
    private VectorReferenceRetriever retriever;
    private ReferenceSelectorProperties properties;
    private ReferenceSourcePolicy policy;

    @BeforeEach
    void setUp() {
        loader = mock(ReferenceIndexLoader.class);
        files = mock(PromptRuleFileLoader.class);
        embedding = new ReferenceTestFixtures.Embedding();
        store = new ReferenceTestFixtures.Store();
        properties = new ReferenceSelectorProperties();
        policy = new ReferenceSourcePolicy(temp.resolve("state.json"));
        when(loader.sourcePolicy()).thenReturn(policy);
        when(loader.getIndex()).thenReturn(List.of(entry("hook"), entry("pacing")));
        when(loader.corpusVersion()).thenAnswer(inv -> "full-corpus-r" + policy.revision());
        when(files.load(anyString())).thenReturn("## 写作\n正文");
        indexer = newIndexer();
        retriever = newRetriever(indexer);
    }

    @Test
    void prepareFreezesVersionAndSpaceWithoutEmbeddingAndRetrieveUsesThatSession() {
        var prepared = retriever.prepare(module());
        assertEquals(0, embedding.calls);
        assertEquals(0, store.writes);
        assertNotNull(prepared.versionKey());
        embedding.signature = "changed-after-prepare";
        embedding.dimensions = 7;
        embedding.actualDimensions = 7;

        assertFalse(retriever.retrieve(prepared, PromptScene.CHAPTER_CONTENT, context()).isEmpty());
        assertEquals(1, embedding.sessionCalls, "retrieve Prepared 不重新解析生效接入");
        assertEquals(3, store.dimensions.get(ReferenceVectorIndexer.COLLECTION));
        assertEquals("fake-space", store.points(ReferenceVectorIndexer.COLLECTION).values().iterator().next()
                .getPayload().get("embeddingSignature"));
    }

    @Test
    void emptyPrepareDoesNotCreateEmbeddingSessionOrMutateStorage() {
        when(loader.getIndex()).thenReturn(List.of());
        var prepared = retriever.prepare(module());
        assertNotNull(prepared.versionKey());
        assertTrue(retriever.retrieve(prepared, PromptScene.CHAPTER_CONTENT, context()).isEmpty());
        assertEquals(0, embedding.sessionCalls);
        assertEquals(0, store.clears);
        assertEquals(0, embedding.calls);
    }

    @Test
    void fullVersionReusedWithinProcessAndAfterRestartWithoutCorpusReembedding() {
        retrieve();
        int embeddedAfterFirst = embedding.calls;
        assertEquals(1, store.writes);
        retrieve();
        assertEquals(embeddedAfterFirst + 1, embedding.calls, "第二次只有查询 embedding");
        assertEquals(1, store.writes);
        newRetriever(newIndexer()).retrieve(module(), PromptScene.CHAPTER_PLAN, context());
        assertEquals(embeddedAfterFirst + 2, embedding.calls, "重启核验已存版本，不重嵌语料");
        assertEquals(1, store.writes);
    }

    @Test
    void sourceDisableAndDeleteChangeVersionAndSearchWhitelistBeforeTopK() {
        retrieve();
        policy.setEnabled("hook", false);
        assertEquals(List.of("pacing"), retrieve());
        assertEquals("fileName", store.lastField);
        assertEquals(Set.of("pacing"), store.lastFilter);
        assertEquals(Set.of(ReferenceVectorIndexer.stableId("pacing", 0)),
                store.points(ReferenceVectorIndexer.COLLECTION).keySet());
        when(loader.getIndex()).thenReturn(List.of());
        int calls = embedding.calls;
        int sessions = embedding.sessionCalls;
        assertTrue(retrieve().isEmpty());
        assertEquals(0, store.count(ReferenceVectorIndexer.COLLECTION));
        assertEquals(calls, embedding.calls);
        assertEquals(sessions, embedding.sessionCalls);
    }

    @Test
    void aggregatesDistinctAllowedFileNamesAndRejectsLowScoreOrDisabledPayloads() {
        policy.setEnabled("pacing", false);
        properties.setMinScore(0.5);
        store.overrideHits = List.of(
                hit("u1", 0.9, "hook"), hit("u2", 0.7, "hook"), hit("u3", 0.3, "hook"),
                hit("u4", 1.0, "pacing"), hit("u5", Double.NaN, "hook"));
        assertEquals(List.of("hook"), retrieve());
        assertEquals(Set.of("hook"), store.lastFilter);
    }

    @Test
    void bodyOnlyChangesTriggerReindexEvenWhenDirectoryMetadataIsIdentical() {
        retrieve();
        int writes = store.writes;
        when(files.load("references/hook.md")).thenReturn("## 写作\n正文已修改，但标题不变");
        retriever.invalidate();
        retrieve();
        assertEquals(writes + 1, store.writes);
        assertEquals("正文已修改，但标题不变", store.points(ReferenceVectorIndexer.COLLECTION)
                .get(ReferenceVectorIndexer.stableId("hook", 0)).getPayload().get("text"));
    }

    @Test
    void actualSessionSpaceChangeFailsBeforeQueryOrCorpusEmbedding() {
        retrieve();
        int calls = embedding.calls;
        embedding.signature = "changed-model-with-same-dim";
        assertThrows(IllegalStateException.class, this::retrieve);
        assertEquals(calls, embedding.calls);
        indexer.rebuild(module(), embedding.session(module()));
        retriever.invalidate();
        assertFalse(retrieve().isEmpty());
    }

    @Test
    void inferredActualDimensionChangeRequiresExplicitRebuild() {
        embedding.dimensions = null;
        retrieve();
        embedding.actualDimensions = 5;
        assertThrows(IllegalStateException.class, this::retrieve);
        assertEquals(3, store.dimensions.get(ReferenceVectorIndexer.COLLECTION));
        indexer.rebuild(module());
        retriever.invalidate();
        assertFalse(retrieve().isEmpty());
        assertEquals(5, store.dimensions.get(ReferenceVectorIndexer.COLLECTION));
    }

    @Test
    void indexFailurePropagatesWithoutSearchingHalfBuiltIndex() {
        embedding.invalidAtCall = 2; // 查询成功、资料首批非法。
        assertThrows(IllegalStateException.class, this::retrieve);
        assertEquals(0, store.writes);
        assertEquals(0, store.searches);
    }

    @Test
    void queryTextCarriesThemeStyleChapterTypeAndBrief() {
        retrieve();
        String query = embedding.texts.get(0).get(0);
        assertTrue(query.contains("悬疑推理"));
        assertTrue(query.contains("刑侦"));
        assertTrue(query.contains("普通推进章"));
        assertTrue(query.contains("翻案立案"));
    }

    private List<String> retrieve() {
        return retriever.retrieve(module(), PromptScene.CHAPTER_CONTENT, context());
    }

    private ReferenceVectorIndexer newIndexer() {
        return new ReferenceVectorIndexer(loader, files, new ReferenceChunker(), embedding, store);
    }

    private VectorReferenceRetriever newRetriever(ReferenceVectorIndexer target) {
        return new VectorReferenceRetriever(embedding, store, target, properties);
    }

    private static ReferenceIndexEntry entry(String file) {
        return new ReferenceIndexEntry(file, file, "", "references/" + file + ".md");
    }

    private static ScoredVectorPoint hit(String id, double score, String file) {
        return ScoredVectorPoint.builder().id(id).score(score).payload(Map.of("fileName", file)).build();
    }

    private static StoryVO.Module module() {
        return new StoryVO.Module();
    }

    private static PromptContext context() {
        return PromptContext.builder().theme("悬疑推理").style("刑侦").chapterNo(3).totalChapters(10)
                .chapterType(ChapterType.NORMAL).chapterBrief("真相初现；关键事件：翻案立案").build();
    }
}
