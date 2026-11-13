package cn.novel.yonren.domain.novel.model.valobj.quality;

import java.util.Objects;

/** A model outcome is tied to an exact current manuscript and plan, never to manual disposition. */
public record QualityIssueRecheck(Status status, String contentHash, String manuscriptHash,
                                  String planHash, String sourceHash, long checkedAtMs,
                                  String message, String evidence) {
    public enum Status { VERIFIED_RESOLVED, STILL_PRESENT, RECHECK_UNCONFIRMED, STALE }

    public QualityIssueRecheck forSource(String currentSourceHash) {
        if (Objects.equals(sourceHash, currentSourceHash)) return this;
        return new QualityIssueRecheck(Status.STALE, contentHash, manuscriptHash, planHash,
                sourceHash, checkedAtMs, "正文、计划或问题来源已变化；历史复核不适用于当前版本", evidence);
    }
}
