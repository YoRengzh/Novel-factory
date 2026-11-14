package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.adapter.llm.EmbeddingGateway;
import cn.novel.yonren.domain.novel.adapter.repository.VectorStore;
import cn.novel.yonren.domain.novel.config.StoryMemoryProperties;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.VolumeBlueprintEntity;
import cn.novel.yonren.domain.novel.model.valobj.ScoredVectorPoint;
import cn.novel.yonren.domain.novel.model.valobj.StoredPoint;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.VectorPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 管理重建严格限定单集合，向量与原文均在临时目录或替身中。 */
class StoryCollectionMaintenanceTest {
    @TempDir Path temp;
    private Store store;
    private EmbeddingGateway gateway;
    private ChapterMemoryService memory;
    private StoryMemoryService service;
    private StoryVO.Module module;
    private Path a;
    private Path b;

    @BeforeEach
    void setUp() throws Exception {
        store = new Store();
        gateway = mock(EmbeddingGateway.class);
        memory = mock(ChapterMemoryService.class);
        when(memory.buildCharacterLedger(any())).thenReturn(List.of());
        when(memory.buildLedger(any(), any())).thenReturn(List.of());
        module = new StoryVO.Module();
        StoryVO.Module.EmbeddingApi api = new StoryVO.Module.EmbeddingApi();
        api.setModel("test-space");
        api.setDimensions(2);
        module.setEmbeddingApi(api);
        when(gateway.session(any())).thenReturn(session());
        service = new StoryMemoryService(gateway, store, memory, new StoryMemoryProperties());
        a = Files.createDirectory(temp.resolve("20261113-story-0001"));
        b = Files.createDirectory(temp.resolve("20261113-story-0002"));
        Files.writeString(a.resolve("story-bible.txt"), "小说名称: A\n世界ID: world-one\n世界观: 甲");
        Files.writeString(b.resolve("story-bible.txt"), "小说名称: B\n世界ID: world-one\n世界观: 乙");
        store.points.put("novel-memory-" + b.getFileName(), new ArrayList<>(List.of(VectorPoint.builder()
                .id("untouched").vector(new float[]{1, 0}).payload(Map.of("kind", "chapter", "text", "B既有数据")).build())));
        store.points.put("novel-world-world-one", new ArrayList<>(List.of(VectorPoint.builder()
                .id("world").vector(new float[]{1, 0}).payload(Map.of("text", "共享世界原数据")).build())));
    }

    @Test
    void previewIsFreeAndStoryRebuildIncludesVolumesWithoutTouchingBOrWorld() throws Exception {
        var summaries = List.of(ChapterSummaryEntity.builder().chapterNo(1).title("章一").summary("甲的经历").build());
        var volume = VolumeBlueprintEntity.builder().volumeNo(1).title("卷一").themeShift("跨越门槛").build();
        var plan = service.prepareStoryCollection(module, a, summaries, List.of(volume));
        verify(gateway, never()).embed(any(), any());
        assertEquals(3, plan.preview().pointCount());
        assertTrue(plan.preview().embeddingChars() > 0);
        String original = Files.readString(a.resolve("story-bible.txt"));
        service.rebuildCollection(plan);
        var points = store.points.get("novel-memory-" + a.getFileName());
        assertEquals(1, points.stream().filter(point -> point.getPayload().get("kind").equals("volume")).count());
        assertEquals(List.of("novel-memory-" + a.getFileName()), store.dropped);
        assertEquals("B既有数据", store.points.get("novel-memory-" + b.getFileName()).get(0).getPayload().get("text"));
        assertEquals("共享世界原数据", store.points.get("novel-world-world-one").get(0).getPayload().get("text"));
        assertEquals(original, Files.readString(a.resolve("story-bible.txt")));
        assertTrue(Files.exists(a.resolve("memory/embedding-index-meta.json")));
    }

    @Test
    void worldRebuildUsesBothStoriesButNeverChangesPrivateCollections() {
        var plan = service.prepareWorldCollection(module, "world-one", List.of(a, b), session());
        assertEquals(2, plan.preview().pointCount());
        service.rebuildCollection(plan);
        assertEquals(List.of("novel-world-world-one"), store.dropped);
        assertEquals(2, store.points.get("novel-world-world-one").size());
        assertEquals("B既有数据", store.points.get("novel-memory-" + b.getFileName()).get(0).getPayload().get("text"));
    }

    @Test
    void badLaterBatchNeverDropsOldCollection() {
        AtomicInteger calls = new AtomicInteger();
        var session = new EmbeddingGateway.EmbeddingSession() {
            public String signature() { return "frozen"; }
            public Integer dimensions() { return 2; }
            public List<float[]> embed(List<String> texts) {
                int call = calls.incrementAndGet();
                return texts.stream().map(text -> call == 2 ? new float[]{Float.NaN, 0} : new float[]{1, 0}).toList();
            }
        };
        List<ChapterSummaryEntity> chapters = new ArrayList<>();
        for (int number = 1; number <= 18; number++) chapters.add(ChapterSummaryEntity.builder().chapterNo(number).summary("摘要").build());
        var plan = service.prepareStoryCollection(module, a, chapters, List.of(), session);
        assertThrows(IllegalStateException.class, () -> service.rebuildCollection(plan));
        assertTrue(store.dropped.isEmpty());
    }

    @Test
    void duplicateSourcesAndDropFailureAreNotReportedAsSuccessful() {
        var chapter = ChapterSummaryEntity.builder().chapterNo(1).summary("摘要").build();
        assertThrows(IllegalStateException.class, () -> service.prepareStoryCollection(module, a, List.of(chapter, chapter), List.of()));
        var plan = service.prepareStoryCollection(module, a, List.of(chapter), List.of());
        store.dropFailure = true;
        assertThrows(IllegalStateException.class, () -> service.rebuildCollection(plan));
        assertFalse(Files.exists(a.resolve("memory/embedding-index-meta.json")));
    }

    @Test
    void deletingCollectionMarksHistoryForLaterRepairWithoutChangingBible() throws Exception {
        String original = Files.readString(a.resolve("story-bible.txt"));
        service.markCollectionDeleted(module, a);
        var meta = com.alibaba.fastjson2.JSON.parseObject(Files.readString(a.resolve("memory/embedding-index-meta.json")));
        assertTrue(meta.getBooleanValue("dirty"));
        assertEquals("deleted", meta.getString("sourceVersion"));
        assertEquals(original, Files.readString(a.resolve("story-bible.txt")));
        verify(gateway, never()).embed(any(), any());
        assertTrue(store.dropped.isEmpty());
    }

    @Test
    void emptyPlanDoesNotNeedOrCallEmbedding() throws Exception {
        Path empty = Files.createDirectory(temp.resolve("20261113-story-0003"));
        var plan = service.prepareStoryCollection(module, empty, List.of(), List.of());
        verify(gateway, never()).session(any());
        assertEquals(0, plan.preview().embeddingBatches());
        service.rebuildCollection(plan);
        assertEquals(0, store.count(plan.preview().collection()));
    }

    private EmbeddingGateway.EmbeddingSession session() {
        return new EmbeddingGateway.EmbeddingSession() {
            public List<float[]> embed(List<String> texts) { return texts.stream().map(text -> new float[]{1, 0}).toList(); }
            public String signature() { return "frozen-test"; }
            public Integer dimensions() { return 2; }
        };
    }

    private static final class Store implements VectorStore {
        private final Map<String, List<VectorPoint>> points = new LinkedHashMap<>();
        private final List<String> dropped = new ArrayList<>();
        private boolean dropFailure;
        public void dropCollection(String collection) {
            if (dropFailure) throw new IllegalStateException("drop test failed");
            points.remove(collection); dropped.add(collection);
        }
        public void ensureCollection(String collection, int size) { points.computeIfAbsent(collection, ignored -> new ArrayList<>()); }
        public void upsert(String collection, List<VectorPoint> incoming) { points.put(collection, List.copyOf(incoming)); }
        public List<ScoredVectorPoint> search(String collection, float[] query, int limit) { return List.of(); }
        public void clear(String collection) { dropCollection(collection); }
        public List<StoredPoint> listPoints(String collection) { return points.getOrDefault(collection, List.of()).stream().map(point ->
                StoredPoint.builder().id(point.getId()).kind(point.getPayload().get("kind")).text(point.getPayload().get("text")).build()).toList(); }
        public void delete(String collection, List<String> ids) { throw new UnsupportedOperationException(); }
        public long count(String collection) { return points.getOrDefault(collection, List.of()).size(); }
    }
}
