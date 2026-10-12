package cn.novel.yonren.types.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * bible 解析测试：口径宽容（未知标签忽略、缺失字段为 null、CRLF 兼容），
 * 但值本身必须逐字还原——解析偏差不会报错，只会在续写时表现为"设定莫名丢失"。
 */
class StoryBibleParserTest {

    /** 磁盘上真实 story-bible.txt 的同构样本 */
    private static final String SAMPLE = String.join("\n",
            "小说名称: 《道诡：理智尽头是疯狂》",
            "小说类型: 玄幻/仙侠/末法时代",
            "小说格调: 硬核战斗，重微操与力量体系的逻辑闭环",
            "世界观: 九州大陆正值末法时代，灵气衰退、天道法则崩塌。",
            "叙述视角: 第三人称限知视角（主跟随男主）",
            "目标读者: 男频/传统玄幻/剑道无敌流",
            "语气基调: 苍凉悲壮，杀伐果断",
            "主人公: 陈长安，22岁（穿越前），外表慵懒随性，实则心思缜密。",
            "故事概述: 陈长安穿越至九州大陆，成为九渊剑匣唯一活着的守墓人。",
            "章节数量: 180",
            "章节目标: 1-30章 剑启篇：守与异变",
            "世界ID: novel-shared-01",
            "语言: 中文",
            "总字数: 2000",
            "存在金手指: true",
            "金手指名称: 九渊剑匣",
            "金手指使用间隔: 3");

    @Test
    void parse_extractsEverySettingField() {
        StoryBibleParser.Snapshot bible = StoryBibleParser.parse(SAMPLE);

        assertEquals("《道诡：理智尽头是疯狂》", bible.novelTitle());
        assertEquals("玄幻/仙侠/末法时代", bible.theme());
        assertEquals("硬核战斗，重微操与力量体系的逻辑闭环", bible.style());
        assertEquals("九州大陆正值末法时代，灵气衰退、天道法则崩塌。", bible.worldSetting());
        assertEquals("第三人称限知视角（主跟随男主）", bible.perspective());
        assertEquals("男频/传统玄幻/剑道无敌流", bible.targetAudience());
        assertEquals("苍凉悲壮，杀伐果断", bible.tone());
        assertEquals("陈长安，22岁（穿越前），外表慵懒随性，实则心思缜密。", bible.protagonist());
        assertEquals("陈长安穿越至九州大陆，成为九渊剑匣唯一活着的守墓人。", bible.outline());
        assertEquals("1-30章 剑启篇：守与异变", bible.chapterGoal());
        assertEquals("novel-shared-01", bible.worldId());
        assertEquals(Boolean.TRUE, bible.hasCheatMechanism());
        assertEquals("九渊剑匣", bible.cheatMechanismName());
        assertEquals(3, bible.cheatUsageInterval());
    }

    @Test
    void parse_onlySplitsOnFirstAsciiColon_keepingFullWidthColonsInValues() {
        // 书名、章节目标里都有全角冒号；用 split(":") 会把值切成两半
        StoryBibleParser.Snapshot bible = StoryBibleParser.parse(SAMPLE);

        assertEquals("《道诡：理智尽头是疯狂》", bible.novelTitle());
        assertEquals("1-30章 剑启篇：守与异变", bible.chapterGoal());
    }

    @Test
    void parse_toleratesCrlf() {
        StoryBibleParser.Snapshot bible = StoryBibleParser.parse(SAMPLE.replace("\n", "\r\n"));

        // bible 用 System.lineSeparator() 写入，Windows 上是 CRLF，行尾不可残留 \r 混进值里
        assertEquals("《道诡：理智尽头是疯狂》", bible.novelTitle());
        assertEquals("novel-shared-01", bible.worldId());
    }

    @Test
    void parse_missingLabelsBecomeNull_insteadOfThrowing() {
        // 老故事可能由更早版本写入，标签集合未必齐全
        StoryBibleParser.Snapshot bible = StoryBibleParser.parse("小说名称: 只有书名\n");

        assertEquals("只有书名", bible.novelTitle());
        assertNull(bible.theme());
        assertNull(bible.outline());
        assertNull(bible.worldId());
        assertNull(bible.hasCheatMechanism());
        assertNull(bible.cheatUsageInterval());
    }

    @Test
    void parse_blankOrNullInput_yieldsEmptySnapshot() {
        StoryBibleParser.Snapshot fromNull = StoryBibleParser.parse(null);
        StoryBibleParser.Snapshot fromBlank = StoryBibleParser.parse("   \n\n  ");

        assertNull(fromNull.novelTitle());
        assertNull(fromBlank.novelTitle());
        assertNull(fromBlank.hasCheatMechanism());
    }

    @Test
    void parse_ignoresUnknownLabelsAndMalformedLines() {
        StoryBibleParser.Snapshot bible = StoryBibleParser.parse(String.join("\n",
                "小说名称: 甲",
                "一个没有冒号的行",
                ": 只有冒号没有标签",
                "未来新增的字段: 值",
                "空值字段:    ",
                "小说类型: 乙"));

        assertEquals("甲", bible.novelTitle());
        assertEquals("乙", bible.theme());
    }

    @Test
    void parse_cheatMechanismDistinguishesAbsentFromExplicitFalse() {
        // null = bible 没写（老故事）；false = 明确声明"没有金手指"。两者语义不同，不能合并
        assertNull(StoryBibleParser.parse("小说名称: 甲").hasCheatMechanism());
        assertEquals(Boolean.FALSE,
                StoryBibleParser.parse("小说名称: 甲\n存在金手指: false").hasCheatMechanism());
        assertEquals(Boolean.TRUE,
                StoryBibleParser.parse("小说名称: 甲\n存在金手指: TRUE").hasCheatMechanism());
    }

    @Test
    void parse_cheatIntervalTakesLeadingDigits() {
        assertEquals(5, StoryBibleParser.parse("金手指使用间隔: 5（每五章一次）").cheatUsageInterval());
        assertEquals(3, StoryBibleParser.parse("金手指使用间隔: 3").cheatUsageInterval());
        // 完全取不到数字时宁可返回 null，也不要瞎猜一个间隔（3/5 章规则会因此错位）
        assertNull(StoryBibleParser.parse("金手指使用间隔: 视剧情而定").cheatUsageInterval());
    }

    @Test
    void parse_leadingTrailingWhitespaceIsTrimmed() {
        StoryBibleParser.Snapshot bible = StoryBibleParser.parse("小说名称:    甲   \n小说类型:乙\n");

        assertEquals("甲", bible.novelTitle());
        assertEquals("乙", bible.theme());
    }

    /**
     * 主要人物档案（代词与既定事实）必须能读回来（2026-10-10）。
     *
     * <p>它是防"代词写错、志愿写错"的事实锚：正文曾把女主宋知渔写成「他」连写三章，
     * 而账本里没有任何可对照的事实。读取侧与写入侧共用 {@code LABEL_CHARACTERS} 常量，
     * 本用例守住"写进去能读出来"——值解析不出来不报错，只会表现为设定悄悄丢失。
     */
    @Test
    void parse_characterProfilesRoundTrip() {
        String roster = "陆瑾瑜（男，用「他」）；宋知渔（女，用「她」）。既定事实（不得改写）：宋知渔报名志愿二班";

        StoryBibleParser.Snapshot bible = StoryBibleParser.parse(
                "小说名称: 甲\n主人公: 陆瑾瑜\n主要人物: " + roster + "\n故事概述: 乙\n");

        assertEquals(roster, bible.characters());
        assertEquals("陆瑾瑜", bible.protagonist());
        assertEquals("乙", bible.outline(), "人物档案是单行值，不能把后面的行吃掉");
    }

    /** 老 bible 没有这一行：返回 null（调用方据此不渲染，而不是渲染一个空栏） */
    @Test
    void parse_characterProfilesNullForLegacyBible() {
        assertNull(StoryBibleParser.parse("小说名称: 甲\n").characters());
    }
}
