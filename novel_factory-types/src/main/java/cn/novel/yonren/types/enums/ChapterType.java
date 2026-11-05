package cn.novel.yonren.types.enums;

/**
 * 章节类型：由大纲模型标记，用于规则动态装配与剧情节奏控制
 */
public enum ChapterType {

    /** 普通推进章 */
    NORMAL("normal", "普通推进章"),

    TRANSITION("transition", "过渡章（蓄势/整理/关系推进；事件密度可低于常规）"),

    /** 高潮章（重大冲突/转折） */
    CLIMAX("climax", "高潮章（重大冲突/转折）"),

    /** 卷末收束章 */
    FINALE("finale", "卷末收束章");

    /** 模型输出/prompt 模板中使用的小写标识 */
    private final String code;

    /** 中文语义说明 */
    private final String desc;

    ChapterType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 容错解析：大小写不敏感匹配 code；未识别的值（含 null）一律降级为 NORMAL，
     * 保证模型输出的变体不会导致流程中断
     */
    public static ChapterType of(String code) {
        if (code == null) {
            return NORMAL;
        }
        for (ChapterType type : values()) {
            if (type.code.equalsIgnoreCase(code.trim())) {
                return type;
            }
        }
        return NORMAL;
    }

    /**
     * 是否为需要重点营造悬念的章节（高潮/卷末）
     */
    public boolean isHookChapter() {
        return this == CLIMAX || this == FINALE;
    }

    /**
     * 是否为过渡章：事件密度可低于常规（规划层的「每章关键事件 ≥3」对本类型豁免）。
     * 豁免不等于放水——过渡章仍须至少包含一处关系/信息/资源的变化，且受配额与连续性约束。
     */
    public boolean isTransition() {
        return this == TRANSITION;
    }

}
