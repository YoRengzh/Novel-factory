package cn.novel.yonren.domain.novel.service.armory.prompt;

import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.types.enums.GenreType;

/** 正文、审校与修订共用的作者契约，不由辅助环节追加创作任务。 */
public final class WritingContractPolicy {

    private WritingContractPolicy() { }

    public static String renderRegister(StoryContextEntity context) {
        GenreType genre = GenreType.match(context == null ? null : context.getTheme(),
                context == null ? null : context.getStyle());
        StringBuilder rule = new StringBuilder("【语言与语域契约】客观叙述、人物对话和内心判断须符合故事时代与世界设定；")
                .append("人物措辞符合身份、已知信息与作者风格；明确的认知超前、特殊训练或世界设定不能被年龄常识抹平。")
                .append("复杂判断可用内心、对白、讲述或行动表达，不统一改成物件与短促口语。");
        if (context != null) {
            append(rule, "作者风格", context.getStyle());
            append(rule, "叙述视角", context.getPerspective());
            append(rule, "语气基调", context.getTone());
        }
        if (genre == GenreType.FANTASY) {
            rule.append("玄幻/仙侠题材优先使用设定内已有词汇（如灵气、阵纹、符箓、灵脉、宗门、长老、法宝）；")
                    .append("握手信号、加密信号、波形数据、服务器、协议、分布式节点、灰度、缓存、日志等程序员术语不得充当客观世界名词，")
                    .append("除非本书设定明确支持；角色的现代类比也须符合已知经历与作者语域。");
        }
        return rule.toString();
    }

    public static String renderEditorialScope() {
        return "【编辑范围】已确认事实与作者明确设定优先，场景计划决定本次交付，通用技法不新增任务。"
                + "独处、心理、讲述、等待、环境与后果消化可以承担阅读功能；不要求固定对白、事件、代价、合作或近暴露次数。"
                + "判断依据是具体内容是否重复、矛盾或妨碍本场阅读功能，不是表达形式本身。"
                + "确认取决于角色已有信息和现场证据，不规定观察次数；结尾可未决、闭合或留有余韵。"
                + "若计划与可信事实不能同时满足，指出冲突及依据，不编造能力、材料或已发生事件来强行完成计划。";
    }

    /** 场景规划交给写手后，按读者已经知道的信息选择交代方式。 */
    public static String renderIntentGuidance() {
        return "【留白与克制】用场景计划组织事件，按视角人物所知交代读者还需要知道的事。"
                + "动作或对白已让读者理解人物的意图时，不必抢先报出完整计策，也不必事后再解释一遍。"
                + "可以直写有新信息的内心判断、推理、取舍、情绪变化或首次需要交代的规则。"
                + "揭晓以本章允许的程度为准，阶段目标与前情摘要不能代替当下的证据。"
                + "结尾交付了行动、结果、感受或新认识之后收住，让读者自己理解它的意义。";
    }

    private static void append(StringBuilder rule, String label, String value) {
        if (value != null && !value.isBlank()) {
            rule.append('\n').append(label).append('：').append(value);
        }
    }
}
