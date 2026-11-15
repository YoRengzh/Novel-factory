package cn.novel.yonren.domain.novel.service.armory.quality;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ContentDensityPolicy {

    private ContentDensityPolicy() { }

    /** 历史统计参考线，不能作为每章写作或修订配额。 */
    public static final int MIN_DIALOGUE_COUNT = 25;

    /**
     * 匹配对话引号包裹的内容：中文弯引号（U+201C/U+201D，中文小说正文的实际形态）、
     * 直角引号「」『』、以及 ASCII 双引号。
     * 注意：历史上此处只写了 ASCII 双引号（"），而正文用的是弯引号 “ ”，
     * 导致 countDialogue 对全部章节恒定返回 0，产生 185 次假预警 —— 勿再改回 ASCII-only。
     */
    private static final Pattern DIALOGUE_PATTERN = Pattern.compile(
            "[\"\u201C][^\"\u201D]*[\"\u201D]|\u300C[^\u300D]*\u300D|\u300E[^\u300F]*\u300F");

    /**
     * 统计正文中的有效对话句数。
     * 每对中文引号计为一句对话；空内容或纯标点不计入。
     */
    public static int countDialogue(String content) {
        if (content == null || content.isBlank()) return 0;
        Matcher matcher = DIALOGUE_PATTERN.matcher(content);
        int count = 0;
        while (matcher.find()) {
            String dialogue = matcher.group();
            // 剔除引号后为空或纯标点的不计入
            String inner = dialogue.substring(1, dialogue.length() - 1).trim();
            if (!inner.isEmpty() && !inner.matches("[\\p{Punct}\\s]+")) {
                count++;
            }
        }
        return count;
    }

    /** 兼容历史调用，只报告词面计数，不生成补量要求或质量结论。 */
    public static String renderLowDensityHint(int dialogueCount) {
        if (dialogueCount >= MIN_DIALOGUE_COUNT) return null;
        return "【对白数量观测】本章引号片段计数 " + Math.max(0, dialogueCount)
                + "；仅为词面统计，不据此判断事件缺失、要求增加对白或触发修订。";
    }
}
