package cn.novel.yonren.domain.novel.service.armory.quality;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重复意象盯防清单的加载与分组。
 *
 * <p>本机制的核心设计是**意象是题材知识、不是流水线逻辑**——故测试重点在分组语义：
 * 通用组对所有题材生效、题材组只对对应题材生效、缺失组不是错误。
 * 夹具词条一律用中性占位意象，不绑定任何具体作品。
 */
class RepetitionWatchlistCatalogTest {

    @Test
    void parse_groupsByGenreAndSkipsComments() {
        String text = """
                # 注释行应被忽略
                [common]
                通用意象
                [urban]
                意象一
                意象二

                # 空行与注释不影响解析
                [fantasy]
                意象三
                """;

        RepetitionWatchlistCatalog.Watchlist list = RepetitionWatchlistCatalog.parse(text);

        assertEquals(3, list.groups().size());
        assertEquals(List.of("通用意象"), list.groups().get("common"));
        assertEquals(List.of("意象一", "意象二"), list.groups().get("urban"));
        assertEquals(List.of("意象三"), list.groups().get("fantasy"));
    }

    @Test
    void forGenre_mergesCommonWithGenreGroup() {
        String text = """
                [common]
                通用意象
                [urban]
                意象一
                """;
        RepetitionWatchlistCatalog.Watchlist list = RepetitionWatchlistCatalog.parse(text);

        // 通用组始终参与 + 题材组追加
        List<String> urban = list.forGenre("urban");
        assertTrue(urban.contains("通用意象"), "通用组必须对所有题材生效：" + urban);
        assertTrue(urban.contains("意象一"), "urban 组必须生效：" + urban);

        // 未配置的题材只拿通用组——组缺失不是错误（大部分题材不会有专属词条）
        List<String> fantasy = list.forGenre("fantasy");
        assertEquals(List.of("通用意象"), fantasy);

        // 题材为 null 同样只拿通用组
        assertEquals(List.of("通用意象"), list.forGenre(null));
    }

    @Test
    void forGenre_isCaseInsensitive() {
        RepetitionWatchlistCatalog.Watchlist list = RepetitionWatchlistCatalog.parse("[URBAN]\n意象一\n");
        assertTrue(list.forGenre("urban").contains("意象一"), "组名大小写不敏感");
        assertTrue(list.forGenre("URBAN").contains("意象一"), "题材 code 大小写不敏感");
    }

    @Test
    void emptyFile_disablesFeatureWithoutError() {
        // 空清单 = 未启用意象盯防，行为与引入本机制前完全一致（对既有题材零影响）
        RepetitionWatchlistCatalog.Watchlist list = RepetitionWatchlistCatalog.parse("# 只有注释\n");
        assertTrue(list.isEmpty());
        assertTrue(list.forGenre("urban").isEmpty());
    }

    @Test
    void emptyGroupsAreDropped() {
        // 空组应被丢弃：让"组缺失"与"组为空"语义一致，避免 forGenre 返回重复项
        RepetitionWatchlistCatalog.Watchlist list = RepetitionWatchlistCatalog.parse("[common]\n[urban]\n意象一\n");
        assertFalse(list.groups().containsKey("common"), "空组不该保留");
        assertEquals(List.of("意象一"), list.forGenre("urban"));
    }

    @Test
    void bundledWatchlistDefaultsToDisabled() {
        // 打包清单必须可解析；且按设计**默认全空**——意象是"这本书反复用了什么"的判断，
        // 不能替任何具体作品预置词条。空清单 = 不启用意象盯防，对新书零影响。
        RepetitionWatchlistCatalog.Watchlist list = RepetitionWatchlistCatalog.get();
        assertTrue(list.isEmpty(),
                "打包清单默认应全空（不预置任何具体作品的意象）：" + list.groups());
        assertTrue(list.forGenre("urban").isEmpty(), "urban 组默认应无词条");
    }
}
