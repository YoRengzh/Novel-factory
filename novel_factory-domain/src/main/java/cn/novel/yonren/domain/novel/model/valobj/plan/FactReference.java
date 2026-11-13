package cn.novel.yonren.domain.novel.model.valobj.plan;

/** 已有账本/正文事实的引用；引用本身不证明任何规划里程碑已完成。 */
public record FactReference(String ledger, Integer chapterNo, String manuscriptHash,
                            String subject, String status, String evidence) {
}
