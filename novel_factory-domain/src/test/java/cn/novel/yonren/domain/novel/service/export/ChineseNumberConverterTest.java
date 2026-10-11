package cn.novel.yonren.domain.novel.service.export;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中文卷号转换测试：卷号渲染（"第 12 卷" → "第十二卷"）的口径锁定。
 * 重点盯两处易错规则：10-19 记「十」不记「一十」；零位插「零」
 */
class ChineseNumberConverterTest {

    @Test
    void convertsCommonVolumeNumbers() {
        assertEquals("一", ChineseNumberConverter.toChinese(1));
        assertEquals("九", ChineseNumberConverter.toChinese(9));
        assertEquals("十", ChineseNumberConverter.toChinese(10));
        assertEquals("十一", ChineseNumberConverter.toChinese(11));
        assertEquals("十九", ChineseNumberConverter.toChinese(19));
        assertEquals("二十", ChineseNumberConverter.toChinese(20));
        assertEquals("二十一", ChineseNumberConverter.toChinese(21));
        assertEquals("三十", ChineseNumberConverter.toChinese(30));
        assertEquals("九十九", ChineseNumberConverter.toChinese(99));
        assertEquals("一百", ChineseNumberConverter.toChinese(100));
        assertEquals("一百零一", ChineseNumberConverter.toChinese(101));
        assertEquals("一百一十", ChineseNumberConverter.toChinese(110));
        assertEquals("一百二十", ChineseNumberConverter.toChinese(120));
        assertEquals("二百零五", ChineseNumberConverter.toChinese(205));
        assertEquals("九百九十九", ChineseNumberConverter.toChinese(999));
        assertEquals("一千", ChineseNumberConverter.toChinese(1000));
        assertEquals("一千零五", ChineseNumberConverter.toChinese(1005));
        assertEquals("一千零一十", ChineseNumberConverter.toChinese(1010));
        assertEquals("九千九百九十九", ChineseNumberConverter.toChinese(9999));
    }

    /** 1-30（现实卷数范围）逐个过一遍：不得出现「一十」前缀，不得为空 */
    @Test
    void firstThirtyVolumesNeverUseLeadingOneBeforeTen() {
        for (int n = 1; n <= 30; n++) {
            String text = ChineseNumberConverter.toChinese(n);
            assertTrue(!text.isEmpty() && !text.contains("一十"), "第 " + n + " 卷不应出现「一十」：" + text);
        }
    }

    /** 越界兜底：不抛异常，返回阿拉伯数字（卷数不可能越界，兜底只为健壮） */
    @Test
    void outOfRangeFallsBackToArabic() {
        assertEquals("0", ChineseNumberConverter.toChinese(0));
        assertEquals("-1", ChineseNumberConverter.toChinese(-1));
        assertEquals("10000", ChineseNumberConverter.toChinese(10000));
    }
}
