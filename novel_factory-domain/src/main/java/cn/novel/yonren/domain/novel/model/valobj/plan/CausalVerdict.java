package cn.novel.yonren.domain.novel.model.valobj.plan;

/** 模型逐场景因果核验结果；PASS/ISSUE 都必须绑定当前稿与可核对事实，不等于永久证明。 */
public record CausalVerdict(String sceneId, Status verdict, String evidence,
                            String basisEvidence, String reason, String repair, FailureType failureType,
                            String factId, Integer sourceChapter, String manuscriptHash, String factType) {
    public enum Status { PASS, ISSUE, UNKNOWN }
    public enum FailureType { KNOWLEDGE_REGRESSION, CAPABILITY_CONFLICT, RESULT_WITHOUT_CAUSE }

    public CausalVerdict(String sceneId, Status verdict, String evidence, String basisEvidence,
                         String reason, String repair) {
        this(sceneId, verdict, evidence, basisEvidence, reason, repair, null, null, null, null, null);
    }

    public CausalVerdict(String sceneId, Status verdict, String evidence, String basisEvidence,
                         String reason, String repair, FailureType failureType) {
        this(sceneId, verdict, evidence, basisEvidence, reason, repair, failureType, null, null, null, null);
    }
}