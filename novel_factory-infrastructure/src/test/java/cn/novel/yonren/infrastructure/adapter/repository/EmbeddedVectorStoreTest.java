package cn.novel.yonren.infrastructure.adapter.repository;

import cn.novel.yonren.domain.novel.model.valobj.ScoredVectorPoint;
import cn.novel.yonren.domain.novel.model.valobj.StoredPoint;
import cn.novel.yonren.domain.novel.model.valobj.VectorPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}
