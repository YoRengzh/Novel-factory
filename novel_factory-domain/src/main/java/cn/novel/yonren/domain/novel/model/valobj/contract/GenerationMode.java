package cn.novel.yonren.domain.novel.model.valobj.contract;


public enum GenerationMode {

    /** 契约完整：表达自由。 */
    FREE("自由写作"),

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
     * @param contract 本章契约（可为 null → RECOVERY）
     */
    public static GenerationMode decide(ChapterContract contract) {
        if (contract == null || !contract.isComplete()) {
            return RECOVERY;
        }
        return FREE;
    }

    /** 是否附加"禁止计划外人物/地点/冲突"的强约束 */
    public boolean forbidsOutOfPlanElements() {
        return this == RECOVERY;
    }
}
