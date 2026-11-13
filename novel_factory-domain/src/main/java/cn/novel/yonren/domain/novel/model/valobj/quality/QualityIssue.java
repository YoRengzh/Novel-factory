package cn.novel.yonren.domain.novel.model.valobj.quality;

/** Read-only projection of a debt plus its independent author-maintenance history. */
public record QualityIssue(String id, int chapterNo, String sourceHash, String dimension,
                           String severity, String description, String evidence, String suggestion,
                           boolean systemResolved, QualityIssueDisposition disposition,
                           QualityIssueRecheck recheck, String currentContentHash,
                           String currentManuscriptHash, String acceptedManuscriptHash, String planHash) { }
