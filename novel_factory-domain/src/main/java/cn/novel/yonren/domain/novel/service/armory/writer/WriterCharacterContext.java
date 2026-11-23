package cn.novel.yonren.domain.novel.service.armory.writer;

/**
 * 人物决策卡与后果观察的正文视图。设计卡帮助人物作出符合自身价值的选择，但不授权新事实。
 */
public final class WriterCharacterContext {

    private WriterCharacterContext() {
    }

    /** 后果观察只保留人物选择及其结果，不带章节号和来源哈希。 */
    public static String renderFeedback(String observation) {
        if (observation == null || observation.isBlank()) {
            return "";
        }
        return "【人物此前选择及后果】只作为当前行动的参照，不要求重复相同选择。\n"
                + observation.strip().replaceAll("第\\d+章人物选择观察：", "") + "\n";
    }
}
