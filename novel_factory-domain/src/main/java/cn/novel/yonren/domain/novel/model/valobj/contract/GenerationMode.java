package cn.novel.yonren.domain.novel.model.valobj.contract;


public enum GenerationMode {

    /** 契约完整 + 节拍可用：节拍作建议，表达自由。 */
    FREE("自由写作"),

    /** 契约完整但节拍不可用：用通用骨架兜底。 */
    SCAFFOLDED("骨架兜底"),

    /** 契约不完整：骨架 + 强约束，先写成立。 */
    RECOVERY("强约束恢复");

    private final String label;

    GenerationMode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 选择生成模式。
     *
     * @param contract       本章契约（可为 null → RECOVERY）
     * @param beatsAvailable 是否拿到可用节拍表
     */
    public static GenerationMode decide(ChapterContract contract, boolean beatsAvailable) {
        if (contract == null || !contract.isComplete()) {
            return RECOVERY;
        }
        return beatsAvailable ? FREE : SCAFFOLDED;
    }

    /** 是否要求"逐拍强制扩写"（FREE 下节拍退化为建议顺序） */
    public boolean enforcesBeats() {
        return this == SCAFFOLDED || this == RECOVERY;
    }

    /** 是否附加"禁止计划外人物/地点/冲突"的强约束 */
    public boolean forbidsOutOfPlanElements() {
        return this == RECOVERY;
    }
}
