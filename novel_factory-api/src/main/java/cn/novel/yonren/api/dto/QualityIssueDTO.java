package cn.novel.yonren.api.dto;

/** Source hashes are opaque CAS tokens; acceptedManuscriptHash never implies the current manuscript passed. */
public record QualityIssueDTO(String id, int chapterNo, String sourceHash, String dimension,
                              String severity, String description, String evidence, String suggestion,
                              boolean systemResolved, Disposition disposition, Recheck recheck,
                              String currentContentHash, String currentManuscriptHash,
                              String acceptedManuscriptHash, String planHash) {
    public record Disposition(String state, String note, String revision) { }
    public record Recheck(String status, String contentHash, String manuscriptHash, String planHash,
                          String sourceHash, long checkedAtMs, String message, String evidence) { }
}
