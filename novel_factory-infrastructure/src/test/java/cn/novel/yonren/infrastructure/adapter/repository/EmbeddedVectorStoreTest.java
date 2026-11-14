package cn.novel.yonren.infrastructure.adapter.repository;

import cn.novel.yonren.domain.novel.model.valobj.ScoredVectorPoint;
import cn.novel.yonren.domain.novel.model.valobj.StoredPoint;
import cn.novel.yonren.domain.novel.model.valobj.VectorCollectionInfo;
import cn.novel.yonren.domain.novel.model.valobj.VectorPoint;
import cn.novel.yonren.domain.novel.model.valobj.VectorPointPage;
import cn.novel.yonren.domain.novel.model.valobj.VectorPointView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EmbeddedVectorStore 的行为契约测试——同时也是 VectorStore 端口语义的活文档。
 * 全部基于真实临时目录（@TempDir），无 mock：文件往返是真的，崩溃安全路径至少被走到过。
 */
class EmbeddedVectorStoreTest {

    @TempDir
    Path tempDir;

    private EmbeddedVectorStore store;

    @BeforeEach
    void setUp() {
        // collectionPrefix 用真实默认值 novel-memory，确保路由逻辑（前缀匹配）被真实验证
        store = new EmbeddedVectorStore(tempDir.resolve("root"),
                tempDir.resolve("workspace"), "novel-memory");
    }

    private static float[] vec(float... values) {
        return values;
    }

    private static VectorPoint point(String id, float[] vector, String kind, String text) {
        Map<String, String> payload = new HashMap<>();
        payload.put("kind", kind);
        payload.put("text", text);
        return VectorPoint.builder().id(id).vector(vector).payload(payload).build();
    }

    // ---------- 基本往返 ----------

    @Test
    void upsertThenSearchReturnsDescendingByScore() {
        store.ensureCollection("novel-memory-20261018-story-0001", 3);
        store.upsert("novel-memory-20261018-story-0001", List.of(
                point("a", vec(1, 0, 0), "chapter", "完全同向"),
                point("b", vec(0, 1, 0), "chapter", "正交"),
                point("c", vec(0.7f, 0.7f, 0), "chapter", "斜向")));

        List<ScoredVectorPoint> hits = store.search("novel-memory-20261018-story-0001", vec(1, 0, 0), 3);

        assertEquals(3, hits.size());
        assertEquals("a", hits.get(0).getId());          // 同向最近
        assertTrue(hits.get(0).getScore() > 0.999);        // 余弦=1（容浮点误差）
        assertEquals("b", hits.get(2).getId());           // 正交最远
        // 斜向量夹角 45°，余弦 ≈ 0.707
        assertTrue(Math.abs(hits.get(1).getScore() - 0.7071) < 0.001);
        // 命中带回 payload（检索后回源依赖它）
        assertEquals("完全同向", hits.get(0).getPayload().get("text"));
    }

    @Test
    void searchRespectsLimit() {
        store.ensureCollection("c1", 2);
        store.upsert("c1", List.of(
                point("x", vec(1, 0), "chapter", "t1"),
                point("y", vec(1, 1), "chapter", "t2")));
        assertEquals(1, store.search("c1", vec(1, 0), 1).size());
    }

    @Test
    void upsertSameIdOverwrites() {
        store.ensureCollection("c2", 2);
        store.upsert("c2", List.of(point("same", vec(1, 0), "chapter", "旧文本")));
        store.upsert("c2", List.of(point("same", vec(0, 1), "chapter", "新文本")));

        assertEquals(1, store.count("c2"));
        assertEquals("新文本", store.search("c2", vec(0, 1), 1).get(0).getPayload().get("text"));
    }

    // ---------- 归一化语义：分数 == 余弦，与向量缩放无关 ----------

    @Test
    void scoreIsCosineRegardlessOfVectorScale() {
        store.ensureCollection("c3", 2);
        // 同方向、不同长度：缩放不改变相似度（余弦的定义），归一化后应得分一致
        store.upsert("c3", List.of(
                point("s1", vec(0.5f, 0.5f), "chapter", "t"),
                point("s2", vec(10, 10), "chapter", "t")));

        List<ScoredVectorPoint> hits = store.search("c3", vec(2, 2), 2);
        assertEquals(2, hits.size());
        assertTrue(Math.abs(hits.get(0).getScore() - 1.0) < 0.001); // 与查询同向 → 余弦 1
        assertTrue(Math.abs(hits.get(1).getScore() - 1.0) < 0.001);
    }

    // ---------- 删除/清空/计数 ----------

    @Test
    void deleteRemovesOnlyTargetIds() {
        store.ensureCollection("c4", 2);
        store.upsert("c4", List.of(
                point("keep", vec(1, 0), "chapter", "留"),
                point("drop", vec(0, 1), "chapter", "删")));
        store.delete("c4", List.of("drop", "not-exist")); // 幂等：不存在的 id 静默忽略

        assertEquals(1, store.count("c4"));
        assertEquals("keep", store.search("c4", vec(1, 0), 5).get(0).getId());
    }

    @Test
    void clearWipesEverythingAndCountDropsToZero() {
        store.ensureCollection("c5", 2);
        store.upsert("c5", List.of(point("p", vec(1, 0), "chapter", "t")));
        store.clear("c5");

        assertEquals(0, store.count("c5"));
        assertTrue(store.search("c5", vec(1, 0), 5).isEmpty());
    }

    @Test
    void countOfMissingCollectionIsZero() {
        assertEquals(0, store.count("never-created"));
    }

    // ---------- listPoints 对账契约 ----------

    @Test
    void listPointsReturnsIdKindTextWithoutVector() {
        store.ensureCollection("c6", 2);
        store.upsert("c6", List.of(point("p1", vec(1, 0), "volume", "卷方向点")));

        List<StoredPoint> points = store.listPoints("c6");
        assertEquals(1, points.size());
        assertEquals("p1", points.get(0).getId());
        assertEquals("volume", points.get(0).getKind());
        assertEquals("卷方向点", points.get(0).getText());
    }

    @Test
    void listPointsOfMissingCollectionIsEmpty() {
        assertTrue(store.listPoints("no-such").isEmpty());
    }

    // ---------- 重启恢复：新实例读同一目录 ----------

    @Test
    void newInstanceReadsPersistedData() {
        String collection = "c7";
        store.ensureCollection(collection, 2);
        store.upsert(collection, List.of(point("p", vec(1, 0), "chapter", "跨重启")));

        // 模拟重启：全新实例指向同一根目录
        EmbeddedVectorStore restarted = new EmbeddedVectorStore(tempDir.resolve("root"),
                tempDir.resolve("workspace"), "novel-memory");

        assertEquals(1, restarted.count(collection));
        assertEquals("跨重启", restarted.search(collection, vec(1, 0), 1).get(0).getPayload().get("text"));
    }

    // ---------- 维度守卫 ----------

    @Test
    void ensureCollectionRejectsDimensionMismatch() {
        store.ensureCollection("c8", 3);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> store.ensureCollection("c8", 2048));
        assertTrue(ex.getMessage().contains("2048")); // 错误信息必须可行动
    }

    @Test
    void upsertRejectsWrongDimensionPoints() {
        store.ensureCollection("c9", 3);
        assertThrows(IllegalStateException.class,
                () -> store.upsert("c9", List.of(point("bad", vec(1, 0), "chapter", "维度不符"))));
    }

    @Test
    void arbitraryDimensionsWork() {
        // 维度是运行时数据不是结构绑定：2048 / 3072 与 1024 同一代码路径
        store.ensureCollection("c2048", 2048);
        float[] big1 = new float[2048];
        float[] big2 = new float[2048];
        big1[0] = 1;
        big2[0] = 0.5f;
        big2[1] = 0.5f;
        store.upsert("c2048", List.of(point("big", big1, "chapter", "2048维点")));
        assertEquals(1, store.count("c2048"));
        assertTrue(store.search("c2048", big2, 1).get(0).getScore() > 0.7); // 与 big1 夹角 45°

        store.ensureCollection("c3072", 3072);
        float[] huge = new float[3072];
        huge[9] = 1;
        store.upsert("c3072", List.of(point("h", huge, "chapter", "3072维点")));
        assertEquals(1, store.count("c3072"));
    }

    // ---------- 落盘路由 ----------

    @Test
    void storyCollectionPersistsUnderStoryMemoryDir() throws IOException {
        store.ensureCollection("novel-memory-20261018-story-0099", 2);
        store.upsert("novel-memory-20261018-story-0099", List.of(point("p", vec(1, 0), "chapter", "t")));

        // 故事集合落在真实故事目录布局的 memory/vectors/ 下 → 删故事目录即向量随葬
        Path expected = tempDir.resolve("workspace")
                .resolve("20261018-story-0099")
                .resolve(StorageKeys.MEMORY_DIR).resolve("vectors");
        assertTrue(Files.exists(expected), "故事向量应在 " + expected + " 下有文件");
        try (var files = Files.list(expected)) {
            assertTrue(files.anyMatch(f -> f.getFileName().toString().endsWith(".nvbin")));
        }
    }

    @Test
    void sharedCollectionPersistsUnderRootVectorStoreDir() {
        store.ensureCollection("novel-references", 2);
        store.upsert("novel-references", List.of(point("p", vec(1, 0), "ref", "参考资料")));
        assertTrue(Files.exists(tempDir.resolve("root").resolve("vector-store")
                .resolve("novel-references.nvbin")));
    }

    // ---------- 用户删目录后的缓存失效 ----------

    @Test
    void deletingStoryDirectoryInvalidatesMemoryCache() throws IOException {
        String collection = "novel-memory-20261018-story-0002";
        store.ensureCollection(collection, 2);
        store.upsert(collection, List.of(point("p", vec(1, 0), "chapter", "待删除")));
        assertEquals(1, store.count(collection));

        // 模拟用户在文件管理器里删除整个故事目录（正文+账本+向量一起消失）
        Path storyDir = tempDir.resolve("workspace").resolve("20261018-story-0002");
        try (var walk = Files.walk(storyDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(f -> { try { Files.delete(f); } catch (IOException ignored) { } });
        }

        // 不重启：内存缓存必须自动失效——count 归零、检索不再命中已删数据
        assertEquals(0, store.count(collection));
        assertTrue(store.search(collection, vec(1, 0), 5).isEmpty());
    }

    // ---------- 崩溃安全 ----------

    @Test
    void noTempFilesLeftBehind() throws IOException {
        store.ensureCollection("c10", 2);
        store.upsert("c10", List.of(point("p", vec(1, 0), "chapter", "t")));
        store.clear("c10");
        store.ensureCollection("c10", 2); // 重建
        // 原子替换的中间产物不应残留（.tmp-* 文件）
        try (var walk = Files.walk(tempDir)) {
            assertTrue(walk.noneMatch(p -> p.getFileName().toString().contains(".tmp-")),
                    "不应残留临时文件");
        }
    }

    @Test
    void corruptedFileFailsLoudly() throws IOException {
        store.ensureCollection("c11", 2);
        Path file;
        try (var walk = Files.walk(tempDir)) {
            file = walk.filter(p -> p.getFileName().toString().equals("c11.nvbin")).findFirst().orElse(null);
        }
        assertNotNull(file);
        // 覆写垃圾内容模拟损坏
        Files.write(file, new byte[]{1, 2, 3, 4});

        EmbeddedVectorStore restarted = new EmbeddedVectorStore(tempDir.resolve("root"),
                tempDir.resolve("workspace"), "novel-memory");
        assertThrows(IllegalStateException.class, () -> restarted.count("c11"));
    }

    // ---------- 管理列表与分页：只读、受管名称、无向量 ----------

    @Test
    void listingAndMissingPageDoNotCreateDirectories() {
        assertEquals("embedded", store.backendName());
        assertTrue(store.listCollections().isEmpty());
        assertEquals(new VectorPointPage(List.of(), null), store.pagePoints("novel-references", null, 100));
        assertFalse(Files.exists(tempDir.resolve("root")));
        assertFalse(Files.exists(tempDir.resolve("workspace")));
    }

    @Test
    void listCollectionsOnlyIncludesManagedFamiliesAndKeepsChineseStoryNames() throws IOException {
        String block = "novel-reference-blocks-v2-abcd-d2";
        String story = "novel-memory-诡秘之主_卷一";
        List<String> expected = List.of("novel-references", block, story, "novel-world-shared");
        for (String name : expected) {
            store.ensureCollection(name, 2);
        }
        store.upsert("novel-references", List.of(point("p", vec(1, 0), "ref", "原始资料")));
        for (String name : List.of("c1", "other-app", "novel-worldish", "novel-referencesOther")) {
            store.ensureCollection(name, 2);
        }
        Path shared = tempDir.resolve("root/vector-store");
        Files.write(shared.resolve("notes.nvbin"), new byte[]{1, 2, 3});
        Files.write(shared.resolve("novel-world-temp.nvbin.tmp-ignored"), new byte[]{1, 2, 3});
        Files.copy(shared.resolve("novel-references.nvbin"), shared.resolve("novel-memory-misplaced.nvbin"));
        Path unrelated = tempDir.resolve("workspace/another-story/chapters");
        Files.createDirectories(unrelated);
        Files.write(unrelated.resolve("novel-world-hidden.nvbin"), new byte[]{1, 2, 3});

        List<VectorCollectionInfo> infos = store.listCollections();
        assertEquals(expected.stream().sorted().toList(), infos.stream().map(VectorCollectionInfo::name).toList());
        for (VectorCollectionInfo info : infos) {
            assertEquals(2, info.dimensions());
            assertEquals("ready", info.status());
            assertNull(info.error());
        }
        assertEquals(1, infos.stream().filter(info -> info.name().equals("novel-references")).findFirst().orElseThrow().pointCount());
        assertTrue(Files.exists(tempDir.resolve("workspace/诡秘之主_卷一/memory/vectors")
                .resolve(story + ".nvbin")), "安全中文名应直接使用原文件名，不迁移/替换");
    }

    @Test
    void listCollectionsUsesConfiguredStoryPrefix() {
        EmbeddedVectorStore custom = new EmbeddedVectorStore(tempDir.resolve("root"),
                tempDir.resolve("workspace"), "custom-memory");
        custom.ensureCollection("custom-memory-中文故事", 2);
        custom.ensureCollection("novel-memory-not-managed", 2);
        assertEquals(List.of("custom-memory-中文故事"), custom.listCollections().stream()
                .map(VectorCollectionInfo::name).toList());
    }

    @Test
    void pagePointsIsSortedBoundedAndReturnsAllPayloadWithoutVector() {
        String collection = "novel-references";
        store.ensureCollection(collection, 2);
        Map<String, String> payload = new HashMap<>(Map.of("kind", "ref", "text", "全文",
                "fileName", "资料.txt", "group", "用户资料", "docId", "doc-1", "contentHash", "abc"));
        store.upsert(collection, List.of(point("c", vec(1, 0), "ref", "c"),
                VectorPoint.builder().id("a").vector(vec(1, 0)).payload(payload).build(),
                point("b", vec(1, 0), "ref", "b")));
        payload.put("text", "调用者修改不得穿透快照");

        VectorPointPage first = store.pagePoints(collection, null, 2);
        assertEquals(List.of("a", "b"), first.points().stream().map(VectorPointView::id).toList());
        assertEquals("全文", first.points().get(0).payload().get("text"));
        assertEquals("doc-1", first.points().get(0).payload().get("docId"));
        assertEquals(6, first.points().get(0).payload().size());
        assertNotNull(first.nextCursor());
        assertThrows(UnsupportedOperationException.class, () -> first.points().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.points().get(0).payload().put("text", "改"));
        assertEquals(List.of("id", "payload"), Arrays.stream(VectorPointView.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList());
        assertEquals(List.of("points", "nextCursor"), Arrays.stream(VectorPointPage.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList());

        VectorPointPage last = store.pagePoints(collection, first.nextCursor(), 2);
        assertEquals(List.of("c"), last.points().stream().map(VectorPointView::id).toList());
        assertNull(last.nextCursor());
        assertEquals(3, store.pagePoints(collection, null, 100).points().size());
    }

    @Test
    void stableCursorSurvivesRestartAndDeletionOfBoundaryPoint() {
        String collection = "novel-references";
        store.ensureCollection(collection, 2);
        store.upsert(collection, List.of(point("c", vec(1, 0), "ref", "c"),
                point("a", vec(1, 0), "ref", "a"), point("b", vec(1, 0), "ref", "b")));
        String cursor = store.pagePoints(collection, null, 1).nextCursor();
        store.delete(collection, List.of("a", "b"));
        store.upsert(collection, List.of(point("d", vec(1, 0), "ref", "d")));
        EmbeddedVectorStore restarted = new EmbeddedVectorStore(tempDir.resolve("root"),
                tempDir.resolve("workspace"), "novel-memory");
        assertEquals(List.of("c", "d"), restarted.pagePoints(collection, cursor, 100).points().stream()
                .map(VectorPointView::id).toList());
    }

    @Test
    void pointIdsThatLookLikePathsRemainOpaqueDataNotFilePaths() {
        String collection = "novel-references";
        store.ensureCollection(collection, 2);
        store.upsert(collection, List.of(point("../../outside", vec(1, 0), "ref", "path-like id"),
                point("z", vec(1, 0), "ref", "z")));
        VectorPointPage first = store.pagePoints(collection, null, 1);
        assertEquals("../../outside", first.points().get(0).id());
        assertFalse(first.nextCursor().contains(".."));
        assertEquals("z", store.pagePoints(collection, first.nextCursor(), 1).points().get(0).id());
        assertFalse(Files.exists(tempDir.resolve("outside")));
    }

    @Test
    void pagePointsRejectsInvalidLimitsMalformedAndCrossCollectionCursors() {
        for (int limit : new int[]{-1, 0, 101, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> store.pagePoints("novel-references", null, limit));
        }
        for (String cursor : List.of("", "../outside", "C:\\outside", "embedded-v1-@@", "embedded-v1-AA")) {
            assertThrows(IllegalArgumentException.class, () -> store.pagePoints("novel-references", cursor, 1));
        }
        store.ensureCollection("novel-references", 2);
        store.upsert("novel-references", List.of(point("a", vec(1, 0), "ref", "a"), point("b", vec(1, 0), "ref", "b")));
        String cursor = store.pagePoints("novel-references", null, 1).nextCursor();
        assertThrows(IllegalArgumentException.class, () -> store.pagePoints("novel-world-shared", cursor, 1));
    }

    @Test
    void corruptManagedCollectionIsVisibleWithoutBreakingOtherCollections() throws IOException {
        store.ensureCollection("novel-references", 2);
        store.upsert("novel-references", List.of(point("p", vec(1, 0), "ref", "healthy")));
        store.ensureCollection("novel-reference-blocks-v2-broken-d2", 2);
        Path broken = tempDir.resolve("root/vector-store/novel-reference-blocks-v2-broken-d2.nvbin");
        Files.write(broken, new byte[]{1, 2, 3, 4});

        List<VectorCollectionInfo> infos = store.listCollections();
        VectorCollectionInfo bad = infos.stream().filter(info -> info.name().contains("broken")).findFirst().orElseThrow();
        assertEquals("error", bad.status());
        assertEquals(-1, bad.pointCount(), "损坏集合点数未知，不能冒充空集合");
        assertNotNull(bad.error());
        assertNull(bad.dimensions());
        assertEquals("ready", infos.stream().filter(info -> info.name().equals("novel-references")).findFirst().orElseThrow().status());
        assertThrows(IllegalStateException.class, () -> store.pagePoints(bad.name(), null, 1));
        assertDoesNotThrow(() -> store.dropCollection(bad.name()));
        assertFalse(Files.exists(broken));
        assertEquals(1, store.listCollections().size());
    }

    @Test
    void corruptedHeaderCannotBecomeAnEmptyReadyCollection() throws IOException {
        Path shared = tempDir.resolve("root/vector-store");
        Files.createDirectories(shared);
        for (int[] header : List.of(new int[]{-1, 1}, new int[]{2, -1}, new int[]{2, Integer.MAX_VALUE})) {
            String name = "novel-reference-blocks-bad-" + Math.abs((long) header[0]) + "-" + Math.abs((long) header[1]);
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(shared.resolve(name + ".nvbin")))) {
                out.writeInt(0x4E565442);
                out.writeInt(1);
                out.writeInt(header[0]);
                out.writeInt(header[1]);
            }
        }
        assertEquals(3, store.listCollections().size());
        assertTrue(store.listCollections().stream().allMatch(info -> info.status().equals("error")));
    }

    @Test
    void dropIsIdempotentActuallyDeletesFileAndLeavesOtherStoryAssets() throws IOException {
        String collection = "novel-memory-中文故事";
        assertDoesNotThrow(() -> store.dropCollection(collection));
        assertFalse(Files.exists(tempDir.resolve("workspace")));
        store.ensureCollection(collection, 2);
        store.upsert(collection, List.of(point("p", vec(1, 0), "chapter", "t")));
        Path memory = tempDir.resolve("workspace/中文故事/memory");
        Path unrelated = memory.resolve("summaries.json");
        Files.writeString(unrelated, "keep");
        Path file = memory.resolve("vectors").resolve(collection + ".nvbin");
        store.dropCollection(collection);
        assertFalse(Files.exists(file));
        assertEquals("keep", Files.readString(unrelated));
        assertEquals(0, store.count(collection));
        assertTrue(store.listPoints(collection).isEmpty());
        assertTrue(store.listCollections().isEmpty());
        assertEquals(new VectorPointPage(List.of(), null), store.pagePoints(collection, null, 1));
        assertDoesNotThrow(() -> store.dropCollection(collection));
        store.ensureCollection(collection, 3);
        assertEquals(3, store.listCollections().get(0).dimensions());
    }

    // ---------- 安全：拒绝越界/别名，不以替换后的安全名访问其它集合 ----------

    @Test
    void everyCollectionOperationRejectsUnsafeNamesEvenWhenInputIsEmpty() {
        for (String name : List.of("../outside", "novel-memory-../outside", "novel-memory-..",
                "novel-memory-.", "novel-memory-", "C:\\outside", "C:outside", "\\\\server\\share",
                "/absolute", "novel-memory-CON", "NUL.txt", "novel-references-a?b", "ends.", "a\u0000b")) {
            assertThrows(IllegalArgumentException.class, () -> store.ensureCollection(name, 2), name);
            assertThrows(IllegalArgumentException.class, () -> store.upsert(name, List.of()), name);
            assertThrows(IllegalArgumentException.class, () -> store.search(name, vec(1, 0), 1), name);
            assertThrows(IllegalArgumentException.class, () -> store.listPoints(name), name);
            assertThrows(IllegalArgumentException.class, () -> store.pagePoints(name, null, 1), name);
            assertThrows(IllegalArgumentException.class, () -> store.count(name), name);
            assertThrows(IllegalArgumentException.class, () -> store.delete(name, List.of()), name);
            assertThrows(IllegalArgumentException.class, () -> store.clear(name), name);
            assertThrows(IllegalArgumentException.class, () -> store.dropCollection(name), name);
        }
        assertFalse(Files.exists(tempDir.resolve("root")));
        assertFalse(Files.exists(tempDir.resolve("workspace")));
    }

    @Test
    void rejectedReplacementNamesCannotAliasAnExistingCollection() {
        store.ensureCollection("novel-references-a_b", 2);
        store.upsert("novel-references-a_b", List.of(point("p", vec(1, 0), "ref", "保留")));
        for (String name : List.of("novel-references-a/b", "novel-references-a?b", "novel-references-a b")) {
            assertThrows(IllegalArgumentException.class, () -> store.dropCollection(name));
            assertThrows(IllegalArgumentException.class, () -> store.pagePoints(name, null, 1));
        }
        assertEquals(1, store.count("novel-references-a_b"));
        assertEquals("保留", store.pagePoints("novel-references-a_b", null, 1).points().get(0).payload().get("text"));
    }

    @Test
    void caseInsensitiveFileAliasIsRejectedWhenPlatformHasOne() throws IOException {
        String collection = "novel-references-Case";
        store.ensureCollection(collection, 2);
        Path file = tempDir.resolve("root/vector-store").resolve(collection + ".nvbin");
        Path alias = file.resolveSibling("novel-references-case.nvbin");
        assumeTrue(Files.exists(alias) && Files.isSameFile(file, alias), "平台文件名区分大小写");
        assertThrows(IllegalArgumentException.class, () -> store.dropCollection("novel-references-case"));
        assertTrue(Files.exists(file));
    }

    @Test
    void symbolicCollectionFileIsErrorAndCannotBeReadWrittenOrDeleted() throws IOException {
        store.ensureCollection("novel-references", 2);
        Path outside = tempDir.resolve("outside.nvbin");
        Files.writeString(outside, "outside must remain untouched");
        String name = "novel-reference-blocks-linked";
        Path link = tempDir.resolve("root/vector-store").resolve(name + ".nvbin");
        createSymbolicLinkOrSkip(link, outside);
        assertThrows(IllegalArgumentException.class, () -> store.count(name));
        assertThrows(IllegalArgumentException.class, () -> store.ensureCollection(name, 2));
        assertThrows(IllegalArgumentException.class, () -> store.pagePoints(name, null, 1));
        assertThrows(IllegalArgumentException.class, () -> store.dropCollection(name));
        VectorCollectionInfo info = store.listCollections().stream().filter(it -> it.name().equals(name)).findFirst().orElseThrow();
        assertEquals("error", info.status());
        assertEquals("outside must remain untouched", Files.readString(outside));
        assertTrue(Files.isSymbolicLink(link));
    }

    @Test
    void symbolicStoryDirectoryCannotEscapeWorkspaceAndIsNotEnumerated() throws IOException {
        Path outside = tempDir.resolve("outside-story");
        Files.createDirectories(outside);
        Files.createDirectories(tempDir.resolve("workspace"));
        createSymbolicLinkOrSkip(tempDir.resolve("workspace/linked"), outside);
        assertThrows(IllegalArgumentException.class, () -> store.ensureCollection("novel-memory-linked", 2));
        assertThrows(IllegalArgumentException.class, () -> store.dropCollection("novel-memory-linked"));
        assertTrue(store.listCollections().isEmpty());
        try (var files = Files.list(outside)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void symbolicRootAndMemoryDirectoriesCannotEscapeManagedRoots() throws IOException {
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(outside);
        Path rootLink = tempDir.resolve("linked-root");
        createSymbolicLinkOrSkip(rootLink, outside);
        EmbeddedVectorStore linked = new EmbeddedVectorStore(rootLink, tempDir.resolve("workspace"), "novel-memory");
        assertThrows(IllegalArgumentException.class, () -> linked.listCollections());
        assertThrows(IllegalArgumentException.class, () -> linked.ensureCollection("novel-references", 2));
        Path story = tempDir.resolve("workspace/linked-memory");
        Files.createDirectories(story);
        createSymbolicLinkOrSkip(story.resolve("memory"), outside);
        assertThrows(IllegalArgumentException.class, () -> store.ensureCollection("novel-memory-linked-memory", 2));
        assertEquals("error", store.listCollections().get(0).status());
        try (var files = Files.list(outside)) {
            assertEquals(0, files.count());
        }
    }

    // ---------- 并发：读改写与删除必须共用同一锁，不能复活旧快照 ----------

    @Test
    void clearAndDropCannotBeOverwrittenByAnInFlightUpsertSnapshot() throws Exception {
        assertMutationWaitsForUpsert(() -> store.dropCollection("novel-references-atomic"), List.of());
        assertMutationWaitsForUpsert(() -> store.clear("novel-references-atomic"), List.of());
    }

    @Test
    void pointDeletionCannotBeOverwrittenByAnInFlightUpsertSnapshot() throws Exception {
        assertMutationWaitsForUpsert(() -> store.delete("novel-references-atomic", List.of("old")), List.of("new"));
    }

    @Test
    void concurrentUpsertsDoNotLoseIndependentPoints() throws Exception {
        String collection = "novel-references-concurrent";
        store.ensureCollection(collection, 2);
        var executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<?>> writes = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                String id = "point-" + i;
                writes.add(executor.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    store.upsert(collection, List.of(point(id, vec(1, 0), "ref", id)));
                }));
            }
            start.countDown();
            for (var write : writes) {
                write.get(10, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(16, store.count(collection));
        EmbeddedVectorStore restarted = new EmbeddedVectorStore(tempDir.resolve("root"),
                tempDir.resolve("workspace"), "novel-memory");
        assertEquals(16, restarted.count(collection));
    }

    private void assertMutationWaitsForUpsert(Runnable mutation, List<String> expectedIds) throws Exception {
        String collection = "novel-references-atomic";
        store.ensureCollection(collection, 2);
        store.upsert(collection, List.of(point("old", vec(1, 0), "ref", "old")));
        CountDownLatch snapshotTaken = new CountDownLatch(1);
        CountDownLatch releaseUpsert = new CountDownLatch(1);
        CountDownLatch mutationStarted = new CountDownLatch(1);
        CountDownLatch mutationDone = new CountDownLatch(1);
        List<VectorPoint> blocking = new java.util.AbstractList<>() {
            @Override
            public VectorPoint get(int index) {
                snapshotTaken.countDown();
                try {
                    if (!releaseUpsert.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("测试未释放写入");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return point("new", vec(1, 0), "ref", "new");
            }

            @Override
            public int size() {
                return 1;
            }
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            var write = executor.submit(() -> store.upsert(collection, blocking));
            assertTrue(snapshotTaken.await(5, TimeUnit.SECONDS));
            var removal = executor.submit(() -> {
                mutationStarted.countDown();
                try {
                    mutation.run();
                } finally {
                    mutationDone.countDown();
                }
            });
            assertTrue(mutationStarted.await(5, TimeUnit.SECONDS));
            assertFalse(mutationDone.await(150, TimeUnit.MILLISECONDS), "删除必须等待持有旧快照的写入释放锁");
            releaseUpsert.countDown();
            write.get(5, TimeUnit.SECONDS);
            removal.get(5, TimeUnit.SECONDS);
        } finally {
            releaseUpsert.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(expectedIds, store.pagePoints(collection, null, 100).points().stream().map(VectorPointView::id).toList());
        EmbeddedVectorStore restarted = new EmbeddedVectorStore(tempDir.resolve("root"),
                tempDir.resolve("workspace"), "novel-memory");
        assertEquals(expectedIds.size(), restarted.count(collection));
        if (expectedIds.isEmpty()) {
            assertFalse(Files.exists(tempDir.resolve("root/vector-store").resolve(collection + ".nvbin")));
        }
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "平台不支持/不允许符号链接：" + e.getMessage());
        }
    }
}
