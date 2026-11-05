package cn.novel.yonren.types.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 题材路由测试：关键词包含匹配、theme/style 任一命中、未命中落 DEFAULT。
 * 扩容：新增 ability/scifi/apocalypse/infinite-flow/urban 五码（含既有行为变更：
 * "都市异能"从 FANTASY 改归 ABILITY，"科幻太空"从 DEFAULT 改归 SCIFI）。
 */
class GenreTypeTest {

    @Test
    void match_themeHitsFantasy() {
        // 修仙/玄幻仍归 FANTASY；"都市异能"自 起归 ABILITY（修仙向与异能向资料分岗）
        assertEquals(GenreType.FANTASY, GenreType.match("修仙", ""));
        assertEquals(GenreType.FANTASY, GenreType.match("玄幻仙侠", null));
        assertEquals(GenreType.ABILITY, GenreType.match("都市异能", null));
        assertEquals(GenreType.ABILITY, GenreType.match(null, "超能力觉醒"));
    }

    @Test
    void match_styleHitsSuspense() {
        assertEquals(GenreType.SUSPENSE, GenreType.match(null, "悬疑推理"));
    }

    @Test
    void match_eitherThemeOrStyleHitsRomance() {
        assertEquals(GenreType.ROMANCE, GenreType.match("豪门婚恋", null));
        assertEquals(GenreType.ROMANCE, GenreType.match(null, "甜宠校园"));
    }

    @Test
    void match_themeCheckedFirstOnFantasyGroup() {
        // FANTASY 组先于 ROMANCE 组判定：style 命中玄幻词即落 FANTASY
        assertEquals(GenreType.FANTASY, GenreType.match("青春校园", "修仙"));
    }

    @Test
    void match_newGenreCodes() {
        assertEquals(GenreType.SCIFI, GenreType.match("黑科技", null));
        assertEquals(GenreType.SCIFI, GenreType.match("星际机甲", ""));
        assertEquals(GenreType.APOCALYPSE, GenreType.match("末世囤货", null));
        assertEquals(GenreType.APOCALYPSE, GenreType.match(null, "废土求生"));
        assertEquals(GenreType.INFINITE_FLOW, GenreType.match("无限流副本", null));
        assertEquals(GenreType.URBAN, GenreType.match("都市重生", null));
        assertEquals(GenreType.URBAN, GenreType.match(null, "年代商战"));
    }

    @Test
    void match_routingOrderPriority() {
        // 玄幻在 ABILITY 之前：玄幻异能混合归修仙资料
        assertEquals(GenreType.FANTASY, GenreType.match("修仙异能", null));
        // 异能在 URBAN 之前：都市异能归异能资料而非都市资料
        assertEquals(GenreType.ABILITY, GenreType.match("都市异能", null));
        // 末世在无限流之前：末世无限流归末世资料
        assertEquals(GenreType.APOCALYPSE, GenreType.match("末世无限流", null));
        // 悬疑在 URBAN 之前：悬疑推理的都市背景不抢路由
        assertEquals(GenreType.SUSPENSE, GenreType.match("都市刑侦", null));
    }

    @Test
    void match_noHitFallsBackToDefault() {
        assertEquals(GenreType.DEFAULT, GenreType.match(null, null));
        assertEquals(GenreType.DEFAULT, GenreType.match("", ""));
        assertEquals(GenreType.DEFAULT, GenreType.match("美食探店", "轻松"));
    }

    @Test
    void code_matchesDirectoryNames() {
        assertEquals("fantasy", GenreType.FANTASY.getCode());
        assertEquals("ability", GenreType.ABILITY.getCode());
        assertEquals("suspense", GenreType.SUSPENSE.getCode());
        assertEquals("romance", GenreType.ROMANCE.getCode());
        assertEquals("scifi", GenreType.SCIFI.getCode());
        assertEquals("apocalypse", GenreType.APOCALYPSE.getCode());
        assertEquals("infinite-flow", GenreType.INFINITE_FLOW.getCode());
        assertEquals("urban", GenreType.URBAN.getCode());
        assertEquals("default", GenreType.DEFAULT.getCode());
    }

}
