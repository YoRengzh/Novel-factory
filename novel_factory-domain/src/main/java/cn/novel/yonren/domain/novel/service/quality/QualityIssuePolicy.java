package cn.novel.yonren.domain.novel.service.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.valobj.quality.QualityIssueDisposition;
import cn.novel.yonren.domain.novel.model.valobj.quality.QualityIssueJournal;
import cn.novel.yonren.domain.novel.model.valobj.quality.QualityIssueRecheck;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import com.alibaba.fastjson2.JSON;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Pure identity, CAS and history rules shared by the application and storage adapter. */
public final class QualityIssuePolicy {
    public static final int MAX_REASON_CHARS = 4000;
    private QualityIssuePolicy() { }

    public static String issueId(int chapterNo, ChapterIssueEntity issue) {
        if (chapterNo <= 0 || issue == null) throw new IllegalStateException("质量问题来源不完整");
        return "qi-" + digest(List.of("quality-issue-v1", chapterNo, text(issue.getDimension()),
                text(issue.getDescription()), text(issue.getEvidence())));
    }

    public static String digest(Object value) {
        return ChapterManuscript.digest(JSON.toJSONString(value));
    }

    public static String text(String value) { return Objects.toString(value, ""); }

    public static void validateExpected(String issueId, String revision, String sourceHash) {
        if (issueId == null || !issueId.matches("qi-[0-9a-f]{64}"))
            throw new IllegalArgumentException("问题标识无效");
        if (revision == null || !revision.matches("0|[1-9][0-9]{0,17}"))
            throw new IllegalArgumentException("必须提供读取到的 expectedRevision");
        if (!isHash(sourceHash)) throw new IllegalArgumentException("必须提供读取到的 expectedSourceHash");
    }

    public static void validateReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > MAX_REASON_CHARS)
            throw new IllegalArgumentException("reason 不能为空且不能超过 " + MAX_REASON_CHARS + " 字符");
    }

    public static boolean isHash(String hash) { return hash != null && hash.matches("[0-9a-f]{64}"); }

    public static void validateJournal(QualityIssueJournal journal, String storyDirName) {
        if (journal == null || journal.schemaVersion() != 1 || !Objects.equals(storyDirName, journal.storyDirName())
                || journal.histories() == null) throw corrupt();
        for (var entry : journal.histories().entrySet()) {
            if (entry.getKey() == null || !entry.getKey().matches("qi-[0-9a-f]{64}")
                    || entry.getValue() == null || entry.getValue().isEmpty()) throw corrupt();
            QualityIssueDisposition previous = QualityIssueDisposition.empty();
            for (int index = 0; index < entry.getValue().size(); index++) {
                var event = entry.getValue().get(index);
                if (event == null || event.action() == null || event.atMs() <= 0 || !isHash(event.sourceHash())
                        || event.disposition() == null || event.disposition().state() == null
                        || event.disposition().note() == null
                        || !Integer.toString(index + 1).equals(event.disposition().revision())) throw corrupt();
                try { validateReason(event.reason()); } catch (IllegalArgumentException invalid) { throw corrupt(); }
                if (event.action() == QualityIssueDisposition.Action.RECHECK) {
                    if (event.recheck() == null || event.recheck().status() == null
                            || event.recheck().status() == QualityIssueRecheck.Status.STALE
                            || !Objects.equals(event.sourceHash(), event.recheck().sourceHash())
                            || event.recheck().checkedAtMs() <= 0 || event.recheck().message() == null
                            || event.recheck().message().isBlank() || event.recheck().evidence() == null
                            || !validOptionalHash(event.recheck().contentHash())
                            || !validOptionalHash(event.recheck().manuscriptHash())
                            || !validOptionalHash(event.recheck().planHash())
                            || event.disposition().state() != previous.state()
                            || !event.disposition().note().equals(previous.note())) throw corrupt();
                    if (event.recheck().status() != QualityIssueRecheck.Status.RECHECK_UNCONFIRMED
                            && (!isHash(event.recheck().contentHash()) || !isHash(event.recheck().manuscriptHash())
                            || !isHash(event.recheck().planHash()))) throw corrupt();
                } else {
                    var expectedState = switch (event.action()) {
                        case NOTE -> previous.state();
                        case DEFER -> QualityIssueDisposition.State.DEFERRED;
                        case FALSE_POSITIVE -> QualityIssueDisposition.State.FALSE_POSITIVE;
                        default -> throw corrupt();
                    };
                    if (event.recheck() != null || event.disposition().state() != expectedState
                            || !event.disposition().note().equals(event.reason())) throw corrupt();
                }
                previous = event.disposition();
            }
        }
    }

    public static QualityIssueDisposition disposition(QualityIssueJournal journal, String issueId) {
        var events = journal.histories().get(issueId);
        return events == null ? QualityIssueDisposition.empty() : events.get(events.size() - 1).disposition();
    }

    public static QualityIssueRecheck latestRecheck(QualityIssueJournal journal, String issueId) {
        var events = journal.histories().getOrDefault(issueId, List.of());
        for (int index = events.size() - 1; index >= 0; index--) {
            if (events.get(index).recheck() != null) return events.get(index).recheck();
        }
        return null;
    }

    public static QualityIssueJournal append(QualityIssueJournal journal, String issueId,
                                             QualityIssueDisposition.Action action, String reason,
                                             String sourceHash, QualityIssueRecheck recheck) {
        validateReason(reason);
        var old = disposition(journal, issueId);
        var state = switch (action) {
            case NOTE, RECHECK -> old.state();
            case DEFER -> QualityIssueDisposition.State.DEFERRED;
            case FALSE_POSITIVE -> QualityIssueDisposition.State.FALSE_POSITIVE;
        };
        var next = new QualityIssueDisposition(state, action == QualityIssueDisposition.Action.RECHECK ? old.note() : reason,
                Long.toString(Math.addExact(Long.parseLong(old.revision()), 1)));
        var histories = new LinkedHashMap<>(journal.histories());
        var events = new ArrayList<>(histories.getOrDefault(issueId, List.of()));
        events.add(new QualityIssueJournal.Event(action, reason, sourceHash, System.currentTimeMillis(), next, recheck));
        histories.put(issueId, List.copyOf(events));
        var replacement = new QualityIssueJournal(1, journal.storyDirName(), histories);
        validateJournal(replacement, journal.storyDirName());
        return replacement;
    }

    private static boolean validOptionalHash(String hash) { return hash == null || isHash(hash); }
    private static IllegalStateException corrupt() {
        return new IllegalStateException("质量问题处置历史损坏，拒绝覆盖；请从备份恢复后重试");
    }
}
