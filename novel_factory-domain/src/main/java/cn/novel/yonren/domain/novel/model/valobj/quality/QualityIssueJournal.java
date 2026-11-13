package cn.novel.yonren.domain.novel.model.valobj.quality;

import java.util.List;
import java.util.Map;

/** Independent append-only maintenance history. Never added to generation basis or quality debts. */
public record QualityIssueJournal(int schemaVersion, String storyDirName,
                                   Map<String, List<Event>> histories) {
    public record Event(QualityIssueDisposition.Action action, String reason, String sourceHash,
                        long atMs, QualityIssueDisposition disposition, QualityIssueRecheck recheck) { }

    public static QualityIssueJournal empty(String storyDirName) {
        return new QualityIssueJournal(1, storyDirName, Map.of());
    }
}
