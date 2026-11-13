package cn.novel.yonren.domain.novel.model.valobj.quality;

/** Author bookkeeping is deliberately independent of generation debts and model rechecks. */
public record QualityIssueDisposition(State state, String note, String revision) {
    public enum State { OPEN, DEFERRED, FALSE_POSITIVE }
    public enum Action { NOTE, DEFER, FALSE_POSITIVE, RECHECK }

    public static QualityIssueDisposition empty() {
        return new QualityIssueDisposition(State.OPEN, "", "0");
    }
}
