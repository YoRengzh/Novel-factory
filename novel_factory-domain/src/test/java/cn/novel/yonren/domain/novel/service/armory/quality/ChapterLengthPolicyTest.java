package cn.novel.yonren.domain.novel.service.armory.quality;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 章节长度策略测试：有效字符只统计中文/字母/数字，空白与标点忽略
 */
class ChapterLengthPolicyTest {

    @Test
    void effectiveCount_nullAndEmpty_returnsZero() {
        assertEquals(0, ChapterLengthPolicy.effectiveCharacterCount(null));
        assertEquals(0, ChapterLengthPolicy.effectiveCharacterCount(""));
    }

    @Test
    void effectiveCount_chineseLettersDigits_counted() {
        assertEquals(3, ChapterLengthPolicy.effectiveCharacterCount("abc"));
        assertEquals(3, ChapterLengthPolicy.effectiveCharacterCount("一二三"));
        assertEquals(3, ChapterLengthPolicy.effectiveCharacterCount("123"));
        assertEquals(9, ChapterLengthPolicy.effectiveCharacterCount("a一1b二2c三3"));
    }

    @Test
    void effectiveCount_whitespaceAndPunctuation_ignored() {
        // 空格、换行、制表符
        assertEquals(0, ChapterLengthPolicy.effectiveCharacterCount(" \n\t "));
        // 中文标点不计数，汉字计数（你/好/世/界 = 4）
        assertEquals(4, ChapterLengthPolicy.effectiveCharacterCount("你好，世界！"));
        // 英文标点不计数，字母计数（a/b/c/d = 4）
        assertEquals(4, ChapterLengthPolicy.effectiveCharacterCount("ab,cd."));
        // 混合：字/a/1 = 3 个有效字符，标点与换行忽略
        assertEquals(3, ChapterLengthPolicy.effectiveCharacterCount("字 a，\n1."));
    }

    @Test
    void exceedsReference_boundaryIsInclusiveBelowAndExclusiveAbove() {
        // 上沿 补）：与下沿对偶，用来让"注水"有检测面。
        // 超出参考上沿的长稿应被观测，区间内稿件不报超标。
        assertFalse(ChapterLengthPolicy.exceedsReference("字".repeat(2600)), "等于上沿不算超标");
        assertTrue(ChapterLengthPolicy.exceedsReference("字".repeat(2601)), "略超上沿即算超标");
        assertTrue(ChapterLengthPolicy.exceedsReference("字".repeat(4170)), "实测第 20 章的量级必须能识别");
        assertFalse(ChapterLengthPolicy.exceedsReference("字".repeat(2000)), "区间内不报");
        assertFalse(ChapterLengthPolicy.exceedsReference(null), "空内容无从判定，宁可漏报");
    }

    @Test
    void meetsMinimum_1499_fails() {
        assertFalse(ChapterLengthPolicy.meetsMinimum("正".repeat(1499)));
    }

    @Test
    void meetsMinimum_1500_passes() {
        assertTrue(ChapterLengthPolicy.meetsMinimum("正".repeat(1500)));
    }

    @Test
    void meetsMinimum_1501_passes() {
        assertTrue(ChapterLengthPolicy.meetsMinimum("正".repeat(1501)));
    }
}