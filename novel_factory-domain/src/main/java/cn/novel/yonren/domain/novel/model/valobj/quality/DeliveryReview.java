package cn.novel.yonren.domain.novel.model.valobj.quality;

import cn.novel.yonren.domain.novel.model.valobj.plan.CausalVerdict;

import java.util.List;

/** 固定计划的交付验收快照；版本身份由服务层绑定，UNKNOWN 不等于内容缺陷。 */
public record DeliveryReview(Status status, boolean enforced, Scope scope,
                             String manuscriptHash, String planHash,
                             List<CausalVerdict> checks, List<String> diagnostics) {
    public enum Status { NOT_CHECKED, PASS, ISSUE, UNKNOWN }
    public enum Scope { SCENES, EVENTS }

    public DeliveryReview {
        status = status == null ? Status.UNKNOWN : status;
        checks = checks == null ? List.of() : List.copyOf(checks);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public static DeliveryReview notChecked() {
        return new DeliveryReview(Status.NOT_CHECKED, false, null, null, null, List.of(), List.of());
    }

    public boolean passed() {
        return status == Status.PASS;
    }

    public boolean unknown() {
        return status == Status.UNKNOWN;
    }

    public boolean blocksCommit() {
        return enforced && !passed();
    }
}
