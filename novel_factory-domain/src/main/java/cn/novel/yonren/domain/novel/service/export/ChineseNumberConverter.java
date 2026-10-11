package cn.novel.yonren.domain.novel.service.export;

/**
 * 阿拉伯数字 → 中文数字（1-9999）：卷号渲染用（"第 12 卷" → "第十二卷"）。
 *
 * <p>全项目已有的是<b>中文 → 阿拉伯</b>的解析器（OutlineSegmentParser / StoryPacing），
 * 没有反向转换，故自建。规则：10-19 记「十」不记「一十」（十/十一/十九）；
 * 中间的零位插「零」（一百零五 / 一千零五）；120 记「一百二十」。
 * 超出范围返回阿拉伯数字兜底——卷数不可能越界，兜底只为不抛异常。
 */
public final class ChineseNumberConverter {

    private static final String[] DIGITS = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
    private static final String[] UNITS = {"", "十", "百", "千"};

    private ChineseNumberConverter() {
    }

    public static String toChinese(int number) {
        if (number <= 0 || number > 9999) {
            return String.valueOf(number);
        }
        StringBuilder sb = new StringBuilder();
        int unitIndex = String.valueOf(number).length() - 1;
        int remaining = number;
        boolean pendingZero = false;
        while (remaining > 0) {
            int scale = (int) Math.pow(10, unitIndex);
            int digit = remaining / scale;
            remaining %= scale;
            if (digit == 0) {
                pendingZero = true;
            } else {
                if (pendingZero && sb.length() > 0) {
                    sb.append("零");
                }
                pendingZero = false;
                // 10-19 记「十」不记「一十」：仅当"十"是全文首个字（sb 仍为空）时省略"一"
                if (!(unitIndex == 1 && digit == 1 && sb.length() == 0)) {
                    sb.append(DIGITS[digit]);
                }
                sb.append(UNITS[unitIndex]);
            }
            unitIndex--;
        }
        return sb.length() == 0 ? DIGITS[0] : sb.toString();
    }
}
