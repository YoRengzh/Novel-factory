package cn.novel.yonren.domain.novel.model.valobj.plan;

import java.util.List;

/** 计划采纳结果：阻断项与可观测警告分开，警告不构成拒绝。 */
public record PlanValidationResult(List<String> blocking, List<String> warnings) {
    public PlanValidationResult {
        blocking = blocking == null ? List.of() : List.copyOf(blocking);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public boolean acceptable() {
        return blocking.isEmpty();
    }
}
