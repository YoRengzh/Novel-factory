package cn.novel.yonren.domain.novel.model.valobj.plan;

/** 已有账本/正文事实的引用；引用本身不证明任何规划里程碑已完成。 */
public record FactReference(String ledger, Integer chapterNo, String manuscriptHash,
                            String subject, String status, String evidence) {

    /** 裸字符串只保留描述，不补造来源；对象形态仍由 Jackson 默认读取。 */
    public static FactReference fromText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        return new FactReference(null, null, null, trimmed, null, trimmed);
    }
}
