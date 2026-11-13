package cn.novel.yonren.domain.novel.service.armory.quality;

/**
 * 章节正文长度策略。有效字符仅统计中文、字母和数字，排除空白及标点。
 *
 * 上下参考沿分别观测供给不足与篇幅膨胀，不能合成一个质量布尔量。
 *
 * 上沿取 {@code assets/rules/plan-targets.md} 声明的目标区间上界（1400–2600）。
 * ⚠️ 与下沿一样只告警不阻塞：长度是创作取舍，机械层负责把现状摆出来并回灌规划层，
 * 不负责拒收章节（拒收只会逼模型注水——这条在下沿的设计里已经论证过）。
 */
public final class ChapterLengthPolicy {
    public static final int MINIMUM_EFFECTIVE_CHARACTERS = 1500;

    /**
     * 篇幅坍缩硬底线：有效字低于此值即判成篇不足，记 BLOCKING 进修订闭环。
     *
     * 参考下沿用于计划供给反馈，硬底线用于拦截严重坍缩的生成稿。
     * 首审与修订后的终稿均需检测，不能只用重写前的篇幅结论。
     */
    public static final int COLLAPSE_FLOOR_CHARACTERS = 800;

    /**
     * 参考区间上沿：超出即为"注水"信号。
     * 注意它不是下沿的镜像：下沿查的是"计划给的料太少"，上沿查的是"关键事件没增加、
     * 描写与对白被拉长"。两者要分别看，不能合成一个"长度是否合格"的布尔量。
     */
    public static final int MAXIMUM_REFERENCE_CHARACTERS = 2600;

    private ChapterLengthPolicy() { }
    public static int effectiveCharacterCount(String content) {
        if (content == null) return 0;
        int count = 0;
        for (int i = 0; i < content.length();) {
            int cp = content.codePointAt(i);
            if (Character.isLetterOrDigit(cp) || Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) count++;
            i += Character.charCount(cp);
        }
        return count;
    }
    public static boolean meetsMinimum(String content) {
        return effectiveCharacterCount(content) >= MINIMUM_EFFECTIVE_CHARACTERS;
    }

    /**
     * 人工编辑保存的最低要求：非空即可，不套 1500 字的生成质量线。
     *
     * 人工编辑是用户的显式意图，生成门禁不应阻止已提交短章的非空保存。
     *
     * 生成路径仍用 {@link #meetsMinimum}，两套门禁各管各的场景。
     */
    public static boolean meetsEditMinimum(String content) {
        return content != null && !content.isBlank();
    }
    /** 篇幅是否坍缩到"不成篇"（提纲式废稿）；空内容返回 false——空稿是另一条链路的问题，此处不重复记账 */
    public static boolean isCollapsed(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        return effectiveCharacterCount(content) < COLLAPSE_FLOOR_CHARACTERS;
    }
    /** 是否超出参考上沿（注水信号）；空内容返回 false（无从判定，宁可漏报） */
    public static boolean exceedsReference(String content) {
        return effectiveCharacterCount(content) > MAXIMUM_REFERENCE_CHARACTERS;
    }
}
