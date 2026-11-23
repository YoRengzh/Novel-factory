package cn.novel.yonren.domain.novel.model.valobj.rebase;

import java.util.List;
import java.util.Map;

/**
 * 一次重基候选的完整记录：原计划、候选、差异与校验结果。
 *
 * <p>只作观测/人工复核用，不构成新的权威计划；{@code adopted} 在本阶段恒为 false。
 */
public record RebaseRecord(String schema, String chapterNo, String originalPlanHash, String stateHash,
                           String candidatePlanHash, String model, String reviewVersion, String status,
                           List<Change> changes, List<String> diagnostics, boolean adopted,
                           Map<String, Object> candidatePlan) {

    public static final String SCHEMA = "REBASE_PROPOSAL_V1";

    /** 一处计划字段改动：路径 + 原值 + 新值 + 模型给的理由。 */
    public record Change(String sceneId, String field, String before, String after, String reason) {
    }
}
