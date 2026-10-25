package cn.novel.yonren.domain.novel.service.armory.prompt;

import cn.novel.yonren.api.dto.UserReferenceDTO;
import cn.novel.yonren.domain.novel.service.armory.prompt.reference.ReferenceIndexLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户自备资产测试：资料增删改查与缓存逐出（PromptRuleFileLoader 立即读到新内容）、
 * 命名约束（合法字符/保留前缀/内置同名拒绝）、文风覆盖读写与恢复内置
 */
class UserAssetStoreTest {

    @TempDir
    Path tmp;

    private PromptRuleFileLoader fileLoader;
    private ReferenceIndexLoader indexLoader;
    private UserAssetStore store;

    @BeforeEach
    void setUp() {
        fileLoader = new PromptRuleFileLoader();
        fileLoader.overrideUserAssetRootForTest(tmp);
        indexLoader = new ReferenceIndexLoader();
        indexLoader.overrideUserReferencesDirForTest(tmp.resolve("references"));
        store = new UserAssetStore(fileLoader, indexLoader);
    }

    // ===== 参考资料 =====

    @Test
    void writeReference_thenListReadDelete_roundTrip() {
        store.writeReference("my-battle-exemplar", "# 我的大战范例\n\n主角利用地形逐个击破。");

        List<UserReferenceDTO> list = store.listReferences();
        assertEquals(1, list.size());
        assertEquals("我的大战范例", list.get(0).getTitle());
        assertEquals("technique", list.get(0).getGroup(), "未命中结构/题材前缀的默认归技法组");

        // 写入后内容缓存必须已逐出：全文加载立即返回新内容
        assertEquals("# 我的大战范例\n\n主角利用地形逐个击破。",
                fileLoader.load("references/my-battle-exemplar.md"));
        assertEquals("my-battle-exemplar", list.get(0).getFileName());
        assertEquals("我的大战范例", store.readReference("my-battle-exemplar").split("\n")[0].substring(2));

        store.deleteReference("my-battle-exemplar");
        assertTrue(store.listReferences().isEmpty());
        assertNull(fileLoader.load("references/my-battle-exemplar.md"), "删除后缓存不得残留旧内容");
    }

    @Test
    void writeReference_structurePrefixGrouped() {
        store.writeReference("plot-structure-mythic", "# 我的神话结构\n\n轮回三段式。");
        assertEquals("structure", store.listReferences().get(0).getGroup());
    }

    @Test
    void writeReference_rejectsBadNames() {
        assertThrows(IllegalArgumentException.class,
                () -> store.writeReference("My-Name", "# x\n内容"));          // 大写
        assertThrows(IllegalArgumentException.class,
                () -> store.writeReference("a/b", "# x\n内容"));               // 路径分隔
        assertThrows(IllegalArgumentException.class,
                () -> store.writeReference("design-my-doc", "# x\n内容"));    // 保留前缀
        assertThrows(IllegalArgumentException.class,
                () -> store.writeReference("ok-name", "   "));                 // 空内容
    }

    @Test
    void writeReference_rejectsBuiltinCollision() {
        // dialogue-writing 是 jar 内置资料（ReferenceIndexLoaderTest 依赖同一份 classpath 资源）
        assertThrows(IllegalArgumentException.class,
                () -> store.writeReference("dialogue-writing", "# 覆盖内置\n内容"),
                "内置资料不开放覆盖（资料层保持加法语义）");
    }

    @Test
    void readReference_missing_throws() {
        assertThrows(IllegalArgumentException.class, () -> store.readReference("no-such-file"));
        assertThrows(IllegalArgumentException.class, () -> store.deleteReference("no-such-file"));
    }

    // ===== 文件路径导入 =====

    @Test
    void importReference_copiesSourceContent() throws Exception {
        Path source = tmp.resolve("我的打斗范例.md");
        java.nio.file.Files.writeString(source, "# 打斗范例\n\n用地形与弱点设计打斗。\n\n正文。", java.nio.charset.StandardCharsets.UTF_8);

        store.importReference("imported-battle", source);

        assertEquals("# 打斗范例\n\n用地形与弱点设计打斗。\n\n正文。",
                store.readReference("imported-battle"), "导入是复制语义：内容与源一致");
        // 源文件删除后资料仍在
        java.nio.file.Files.delete(source);
        assertEquals(1, store.listReferences().size());
    }

    @Test
    void importReference_rejectsMissingAndNonUtf8AndOversize() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> store.importReference("x-1", tmp.resolve("no-such.md")), "源文件不存在");
        // GBK 字节流：UTF-8 解码必须失败并给面向用户的提示
        Path gbk = tmp.resolve("gbk.md");
        java.nio.file.Files.write(gbk, "中文内容".getBytes("GBK"));
        assertThrows(IllegalArgumentException.class, () -> store.importReference("x-2", gbk), "非 UTF-8");
        // 2MB+ 文件拒绝
        Path big = tmp.resolve("big.md");
        byte[] chunk = new byte[1024 * 1024];
        try (var out = java.nio.file.Files.newOutputStream(big)) {
            out.write(chunk);
            out.write(chunk);
            out.write(chunk);
        }
        assertThrows(IllegalArgumentException.class, () -> store.importReference("x-3", big), "超大小上限");
        assertTrue(store.listReferences().isEmpty(), "失败的导入不得留下半成品");
    }

    // ===== 文风 =====

    @Test
    void writeStyle_overrideBeatsBuiltin_andDeleteRestores() {
        // fantasy 有内置文风：覆盖后生效内容=覆盖，内置原文仍可取回
        store.writeStyle("fantasy", "# 我的玄幻文风\n\n冷峻、克制。");
        UserAssetStore.StyleContent overridden = store.readStyle("fantasy");
        assertTrue(overridden.overridden());
        assertEquals("# 我的玄幻文风\n\n冷峻、克制。", overridden.content());
        assertNotNull(overridden.builtin(), "内置原文必须保留供「恢复内置」");

        // 全文加载走覆盖版（缓存已逐出）
        assertEquals("# 我的玄幻文风\n\n冷峻、克制。", fileLoader.load("styles/fantasy.md"));

        store.deleteStyle("fantasy");
        UserAssetStore.StyleContent restored = store.readStyle("fantasy");
        assertFalse(restored.overridden());
        assertEquals(restored.builtin(), restored.content(), "删除覆盖后回到内置原文");
        assertEquals(restored.builtin(), fileLoader.load("styles/fantasy.md"));
    }

    @Test
    void writeStyle_genreWithoutBuiltin_createsFirstStyle() {
        // scifi 无内置文风：覆盖即为该题材第一次拥有专属文风
        store.writeStyle("scifi", "# 硬科幻文风\n\n技术细节压着情绪走。");
        UserAssetStore.StyleContent content = store.readStyle("scifi");
        assertTrue(content.overridden());
        assertNull(content.builtin());
        assertEquals("# 硬科幻文风\n\n技术细节压着情绪走。", content.content());
    }

    @Test
    void styleCrud_rejectsUnknownGenreAndStrayDelete() {
        assertThrows(IllegalArgumentException.class, () -> store.writeStyle("wuxia", "# x\n内容"));
        assertThrows(IllegalArgumentException.class, () -> store.deleteStyle("default"),
                "没有覆盖时删除应 404 而不是误删内置语义");
    }
}
