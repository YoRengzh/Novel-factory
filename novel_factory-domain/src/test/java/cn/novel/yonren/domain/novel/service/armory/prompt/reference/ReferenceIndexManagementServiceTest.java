package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import cn.novel.yonren.api.dto.ReferenceIndexDTO;
import cn.novel.yonren.domain.novel.adapter.llm.EmbeddingGateway;
import cn.novel.yonren.domain.novel.adapter.repository.VectorStore;
import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.service.armory.memory.StoryMemoryService;
import cn.novel.yonren.domain.novel.config.StoryMemoryProperties;
import cn.novel.yonren.domain.novel.config.ReferenceSelectorProperties;
import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.model.valobj.ScoredVectorPoint;
import cn.novel.yonren.domain.novel.model.valobj.StoredPoint;
import cn.novel.yonren.domain.novel.model.valobj.VectorCollectionInfo;
import cn.novel.yonren.domain.novel.model.valobj.VectorPoint;
import cn.novel.yonren.domain.novel.model.valobj.VectorPointPage;
import cn.novel.yonren.domain.novel.model.valobj.VectorPointView;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.prompt.ReferenceIndexEntry;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import cn.novel.yonren.domain.novel.service.job.GenerationJob;
import cn.novel.yonren.domain.novel.service.job.JobRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 管理操作只用临时策略文件、内存集合和替身 embedding，不删除真实资料。 */
class ReferenceIndexManagementServiceTest {

    @TempDir
    Path temp;
    private Store store;
    private ReferenceIndexLoader loader;
    private ReferenceSourcePolicy policy;
    private ReferenceVectorIndexer legacy;
    private ReferenceBlockIndexer blocks;
    private EmbeddingGateway embeddings;
    private EmbeddingGateway.EmbeddingSession session;
    private JobRegistry registry;
    private ReferenceIndexManagementService service;

    @BeforeEach
    void setUp() {
        store = new Store();
        store.put("novel-references", "one", Map.of("fileName", "user-one", "sectionTitle", "原小节", "text", "原正文"));
        store.put("novel-reference-blocks-v2-old-d2", "old", Map.of("fileName", "user-one", "text", "旧版本"));
        store.put("novel-memory-story", "chapter", Map.of("kind", "chapter", "text", "不可删除的小说正文"));
        policy = new ReferenceSourcePolicy(temp.resolve("policy.json"));
        loader = mock(ReferenceIndexLoader.class);
        ReferenceIndexEntry entry = new ReferenceIndexEntry("user-one", "用户资料", "简介", "references/user-one.md", "genre");
        when(loader.getAllIndex()).thenReturn(List.of(entry));
        when(loader.getIndex()).thenAnswer(call -> policy.isEnabled("user-one") ? List.of(entry) : List.of());
        when(loader.sourcePolicy()).thenReturn(policy);
        when(loader.corpusVersion()).thenAnswer(call -> "corpus-" + policy.revision());
        PromptRuleFileLoader fileLoader = mock(PromptRuleFileLoader.class);
        when(fileLoader.userAssetRoot()).thenReturn(temp);
        blocks = mock(ReferenceBlockIndexer.class);
        legacy = mock(ReferenceVectorIndexer.class);
        embeddings = mock(EmbeddingGateway.class);
        session = mock(EmbeddingGateway.EmbeddingSession.class);
        when(embeddings.session(any())).thenReturn(session);
        when(session.signature()).thenReturn("embedding-space");
        when(session.dimensions()).thenReturn(2);
        registry = new JobRegistry();
        StoryProperties properties = new StoryProperties();
        properties.setModule(new StoryVO.Module());
        service = new ReferenceIndexManagementService(store, loader, fileLoader, blocks, legacy,
                mock(VectorReferenceRetriever.class), mock(DynamicReferenceService.class), embeddings,
                properties, new ReferenceSelectorProperties(), registry);
    }

    @Test
    void catalogAndPointPreviewNeverReturnVectorArraysOrEmbed() {
        ReferenceIndexDTO view = service.view();
        assertEquals(3, view.collections().size());
        assertTrue(view.sources().get(0).enabled());
        var collection = view.collections().stream().filter(item -> item.name().equals("novel-references"))
                .findFirst().orElseThrow();
        var page = service.points(collection.id(), null, 25);
        assertEquals("原正文", page.points().get(0).payload().get("text"));
        assertFalse(page.points().get(0).payload().containsKey("vector"));
        verify(session, never()).embed(any());
        verify(embeddings, never()).embed(any(), any());
    }

    @Test
    void disablingSourcePersistsAcrossRestartAndDropsOnlyReferences() {
        var preview = service.preview("disable", "user-one", null);
        assertEquals(2, preview.collections().size());
        assertTrue(service.execute(preview.token()).completed());
        assertFalse(new ReferenceSourcePolicy(temp.resolve("policy.json")).isEnabled("user-one"));
        assertEquals(1, store.count("novel-memory-story"));
        assertEquals(0, store.count("novel-references"));
        assertFalse(registry.isReferenceBusy());
        verify(session, never()).embed(any());
    }

    @Test
    void orphanStoryCanBeDeletedButNotRebuiltAndPathsAreRejected() {
        var story = service.view().collections().stream().filter(item -> item.kind().equals("story"))
                .findFirst().orElseThrow();
        assertTrue(story.canDelete());
        assertFalse(story.canRebuild());
        assertEquals("orphan", story.state());
        assertThrows(IllegalArgumentException.class, () -> service.previewCollections("rebuild", List.of(story.id())));
        assertThrows(IllegalArgumentException.class, () -> service.previewCollections("delete", List.of("../../novel-memory-story")));
        assertThrows(IllegalArgumentException.class, () -> service.points("novel-references", null, 25));
        assertEquals(1, store.count("novel-memory-story"));
    }

    @Test
    void stalePayloadWithSameCountInvalidatesConfirmation() {
        String id = service.view().collections().stream().filter(item -> item.name().equals("novel-references"))
                .findFirst().orElseThrow().id();
        var preview = service.preview("clear", null, id);
        store.put("novel-references", "one", Map.of("fileName", "user-one", "text", "同点数但正文已变"));
        assertThrows(ReferenceIndexManagementService.ConflictException.class, () -> service.execute(preview.token()));
        assertEquals(1, store.count("novel-references"));
        assertFalse(registry.isReferenceBusy());
    }

    @Test
    void previewTokenIsSingleUseAndHistoricalDeletionPreservesCurrent() {
        String id = service.view().collections().stream().filter(item -> item.state().equals("historical"))
                .findFirst().orElseThrow().id();
        var preview = service.preview("delete", null, id);
        assertTrue(service.execute(preview.token()).completed());
        assertThrows(ReferenceIndexManagementService.ConflictException.class, () -> service.execute(preview.token()));
        assertEquals(1, store.count("novel-references"));
        assertEquals(1, store.count("novel-memory-story"));
    }

    @Test
    void activeOrCancellingJobBlocksDestruction() {
        var job = new GenerationJob("active");
        job.markRunning();
        registry.register(job);
        registry.cancel(job.getJobId());
        assertTrue(service.view().busy());
        assertThrows(ReferenceIndexManagementService.ConflictException.class, () -> service.preview("disable", "user-one", null));
        job.markCancelled();
        assertNotNull(service.preview("disable", "user-one", null));
    }

    @Test
    void oldAssetMutationUsesTheSameGuardAndInvalidation() {
        AtomicReference<Boolean> changed = new AtomicReference<>(false);
        assertTrue(registry.tryBeginReferenceUse());
        assertThrows(ReferenceIndexManagementService.ConflictException.class, () ->
                service.mutateReference(() -> changed.set(true)));
        assertFalse(changed.get());
        registry.endReferenceUse();
        service.mutateReference(() -> changed.set(true));
        assertTrue(changed.get());
        assertEquals(0, store.count("novel-references"));
        assertEquals(1, store.count("novel-memory-story"));
        assertFalse(registry.isReferenceBusy());
    }

    @Test
    void collectionOwnersIdentifyPrivateBooksAndAllSharedWorldUsers() throws Exception {
        IStoryRepository repository = mock(IStoryRepository.class);
        String a = "20261113-story-0001", b = "20261113-story-0002";
        Path aPath = temp.resolve(a), bPath = temp.resolve(b);
        when(repository.listStories()).thenReturn(List.of(
                new IStoryRepository.StorySummary(a, "小说 A", 1, 1),
                new IStoryRepository.StorySummary(b, "小说 B", 1, 1)));
        when(repository.resolveStoryDirectoryReadOnly(a)).thenReturn(aPath);
        when(repository.resolveStoryDirectoryReadOnly(b)).thenReturn(bPath);
        when(repository.readStoryBible(aPath)).thenReturn("小说名称: 小说 A\n世界ID: world-one");
        when(repository.readStoryBible(bPath)).thenReturn("小说名称: 小说 B\n世界ID: world-one");
        when(repository.readStoryMaintenanceBasis(any())).thenReturn("source-basis");
        StoryMemoryService memory = mock(StoryMemoryService.class);
        ReflectionTestUtils.setField(service, "storyRepository", repository);
        ReflectionTestUtils.setField(service, "storyMemoryService", memory);
        ReflectionTestUtils.setField(service, "storyMemoryProperties", new StoryMemoryProperties());
        store.put("novel-memory-" + a, "a", Map.of("text", "A"));
        store.put("novel-memory-" + b, "b", Map.of("text", "B"));
        store.put("novel-world-world-one", "world", Map.of("text", "shared"));
        var view = service.view();
        var privateA = view.collections().stream().filter(item -> item.name().equals("novel-memory-" + a)).findFirst().orElseThrow();
        var world = view.collections().stream().filter(item -> item.kind().equals("world")).findFirst().orElseThrow();
        assertEquals(List.of(a), privateA.owners().stream().map(ReferenceIndexDTO.Owner::storyDirName).toList());
        assertEquals(java.util.Set.of(a, b), world.owners().stream().map(ReferenceIndexDTO.Owner::storyDirName).collect(java.util.stream.Collectors.toSet()));
        assertTrue(privateA.canRebuild());
        var preview = service.previewCollections("delete", List.of(privateA.id()));
        assertTrue(service.execute(preview.token()).completed());
        assertEquals(1, store.count("novel-memory-" + b));
        assertEquals(1, store.count("novel-world-world-one"));
        verify(memory).markCollectionDeleted(any(), eq(aPath));
        verify(repository, never()).writeChapterSummaries(any(), any());
    }

    @Test
    void collectionBatchDeletionStopsOnFailureAndReportsEachItem() {
        store.put("novel-world-world-one", "world", Map.of("text", "world"));
        var inventory = service.view();
        String a = inventory.collections().stream().filter(item -> item.name().equals("novel-memory-story")).findFirst().orElseThrow().id();
        String b = inventory.collections().stream().filter(item -> item.name().equals("novel-references")).findFirst().orElseThrow().id();
        String world = inventory.collections().stream().filter(item -> item.kind().equals("world")).findFirst().orElseThrow().id();
        store.failDrop = "novel-references";
        var preview = service.previewCollections("delete", List.of(a, b, world));
        var result = service.execute(preview.token());
        assertFalse(result.completed());
        assertEquals(List.of("completed", "failed", "skipped"), result.results().stream().map(ReferenceIndexDTO.CollectionResult::status).toList());
        assertEquals(0, store.count("novel-memory-story"));
        assertEquals(1, store.count("novel-references"));
        assertEquals(1, store.count("novel-world-world-one"));
        assertFalse(registry.isReferenceBusy());
    }

    @Test
    void historyReferenceRebuildBatchDeduplicatesEmbeddingTarget() throws Exception {
        var references = service.view().collections().stream().filter(item -> item.kind().equals("reference")).map(ReferenceIndexDTO.CollectionView::id).toList();
        var executor = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        ReflectionTestUtils.setField(service, "executor", executor);
        try {
            var preview = service.previewCollections("rebuild", references);
            var result = service.execute(preview.token());
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals("completed", service.task(result.taskId()).status());
            verify(legacy, times(1)).index(any(), same(session));
        } finally { executor.shutdownNow(); }
    }

    @Test
    void asynchronousRebuildFailureIsReportedAndReleasesMaintenance() throws Exception {
        doThrow(new IllegalStateException("test embedding failed")).when(legacy).index(any(), any());
        var executor = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        ReflectionTestUtils.setField(service, "executor", executor);
        try {
            var preview = service.preview("rebuild", null, null);
            var result = service.execute(preview.token());
            assertFalse(result.completed());
            assertNotNull(result.taskId());
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals("failed", service.task(result.taskId()).status());
            assertFalse(registry.isReferenceBusy());
            assertEquals(1, store.count("novel-memory-story"));
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class Store implements VectorStore {
        private final Map<String, Map<String, Map<String, String>>> collections = new LinkedHashMap<>();
        private String failDrop;
        void put(String collection, String id, Map<String, String> payload) {
            collections.computeIfAbsent(collection, ignored -> new LinkedHashMap<>()).put(id, payload);
        }
        @Override public String backendName() { return "test"; }
        @Override public List<VectorCollectionInfo> listCollections() {
            return collections.entrySet().stream().map(entry ->
                    new VectorCollectionInfo(entry.getKey(), 2, entry.getValue().size(), "ready", null)).toList();
        }
        @Override public VectorPointPage pagePoints(String collection, String cursor, int limit) {
            var points = collections.getOrDefault(collection, Map.of()).entrySet().stream()
                    .map(entry -> new VectorPointView(entry.getKey(), entry.getValue())).toList();
            return new VectorPointPage(points, null);
        }
        @Override public void dropCollection(String collection) {
            if (collection.equals(failDrop)) throw new IllegalStateException("drop test failed");
            collections.remove(collection);
        }
        @Override public void ensureCollection(String collection, int size) { collections.computeIfAbsent(collection, ignored -> new LinkedHashMap<>()); }
        @Override public void upsert(String collection, List<VectorPoint> points) { throw new UnsupportedOperationException(); }
        @Override public List<ScoredVectorPoint> search(String collection, float[] query, int limit) { return List.of(); }
        @Override public void clear(String collection) { collections.remove(collection); }
        @Override public List<StoredPoint> listPoints(String collection) { return List.of(); }
        @Override public void delete(String collection, List<String> ids) { ids.forEach(id -> collections.getOrDefault(collection, Map.of()).remove(id)); }
        @Override public long count(String collection) { return collections.getOrDefault(collection, Map.of()).size(); }
    }
}
