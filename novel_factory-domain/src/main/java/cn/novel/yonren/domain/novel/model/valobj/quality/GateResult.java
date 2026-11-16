package cn.novel.yonren.domain.novel.model.valobj.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;

import java.util.List;


public record GateResult(List<ChapterIssueEntity> unresolvedBlocking, PassGrade grade,
                         int minorCount, List<ChapterIssueEntity> mechanicalMinor, int reviseRounds,
                         List<ChapterIssueEntity> semanticMinors, boolean auditVerifyDegraded,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                         String semanticFeedbackHash,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                         String planAdjustmentReason,
                         DeliveryReview deliveryReview) {

    public GateResult {
        deliveryReview = deliveryReview == null ? DeliveryReview.notChecked() : deliveryReview;
        if (deliveryReview.unknown()) grade = PassGrade.UNVERIFIED;
        else if (deliveryReview.blocksCommit()) grade = PassGrade.DEBT;
    }

    public GateResult(List<ChapterIssueEntity> unresolvedBlocking, PassGrade grade,
                      int minorCount, List<ChapterIssueEntity> mechanicalMinor, int reviseRounds,
                      List<ChapterIssueEntity> semanticMinors, boolean auditVerifyDegraded,
                      String semanticFeedbackHash, String planAdjustmentReason) {
        this(unresolvedBlocking, grade, minorCount, mechanicalMinor, reviseRounds,
                semanticMinors, auditVerifyDegraded, semanticFeedbackHash, planAdjustmentReason,
                DeliveryReview.notChecked());
    }

    public GateResult(List<ChapterIssueEntity> unresolvedBlocking, PassGrade grade,
                      int minorCount, List<ChapterIssueEntity> mechanicalMinor, int reviseRounds,
                      List<ChapterIssueEntity> semanticMinors, boolean auditVerifyDegraded,
                      String semanticFeedbackHash) {
        this(unresolvedBlocking, grade, minorCount, mechanicalMinor, reviseRounds,
                semanticMinors, auditVerifyDegraded, semanticFeedbackHash, null);
    }

    public GateResult withSemanticFeedbackHash(String hash) {
        return new GateResult(unresolvedBlocking, grade, minorCount, mechanicalMinor, reviseRounds,
                semanticMinors, auditVerifyDegraded, hash, planAdjustmentReason, deliveryReview);
    }

    public GateResult withDeliveryReview(DeliveryReview review) {
        return new GateResult(unresolvedBlocking, gradeOf(unresolvedBlocking, minorCount, reviseRounds),
                minorCount, mechanicalMinor, reviseRounds, semanticMinors, auditVerifyDegraded,
                semanticFeedbackHash, planAdjustmentReason, review);
    }

    /** 计划与可信事实冲突：保留当前稿并停止无效重试，等待调整计划，而不是伪装成普通质量债。 */
    public GateResult withPlanAdjustmentRequired(String reason) {
        return new GateResult(unresolvedBlocking, grade, minorCount, mechanicalMinor, reviseRounds,
                semanticMinors, auditVerifyDegraded, semanticFeedbackHash, reason, deliveryReview);
    }

    public boolean planAdjustmentRequired() {
        return planAdjustmentReason != null && !planAdjustmentReason.isBlank();
    }

    /** 通过质量分级；交付是否核验及其版本同时保存在 deliveryReview，OFF 不代表已做语义验收。 */
    public enum PassGrade {
        /** 首轮全票通过：零修订轮次且零叙事 MINOR 残留 */
        CLEAN_PASS,
        /** 首轮通过但有叙事 MINOR 残留（低置信通过） */
        MINOR_RESIDUE,
        /** 经修订闭环后才通过（低置信通过） */
        REVISED_PASS,
        /** 修订耗尽仍有未修复 BLOCKING（质量债） */
        DEBT,
        /** 已启用的交付验收没有完成；不是内容缺陷，不驱动改稿或候选挑战。 */
        UNVERIFIED
    }

    /** 工厂：机械文风 MINOR 不影响通过分级。 */
    public static GateResult of(List<ChapterIssueEntity> unresolvedBlocking, int minorCount, int reviseRounds) {
        return of(unresolvedBlocking, minorCount, List.of(), reviseRounds);
    }

    /** 工厂：带机械 MINOR 的重载；机械项只随结果传递，不参与分级。 */
    public static GateResult of(List<ChapterIssueEntity> unresolvedBlocking, int minorCount,
                                List<ChapterIssueEntity> mechanicalMinor, int reviseRounds) {
        return of(unresolvedBlocking, minorCount, mechanicalMinor, reviseRounds, List.of());
    }

    /** 工厂：保留首审语义 MINOR，反馈来源版本由调用方另行绑定。 */
    public static GateResult of(List<ChapterIssueEntity> unresolvedBlocking, int minorCount,
                                List<ChapterIssueEntity> mechanicalMinor, int reviseRounds,
                                List<ChapterIssueEntity> semanticMinors) {
        return of(unresolvedBlocking, minorCount, mechanicalMinor, reviseRounds, semanticMinors, false);
    }

    public static GateResult of(List<ChapterIssueEntity> unresolvedBlocking, int minorCount,
                                List<ChapterIssueEntity> mechanicalMinor, int reviseRounds,
                                List<ChapterIssueEntity> semanticMinors, boolean auditVerifyDegraded) {
        return new GateResult(unresolvedBlocking == null ? List.of() : unresolvedBlocking,
                gradeOf(unresolvedBlocking, minorCount, reviseRounds), minorCount,
                mechanicalMinor == null ? List.of() : mechanicalMinor, reviseRounds,
                semanticMinors == null ? List.of() : semanticMinors, auditVerifyDegraded, null);
    }

    private static PassGrade gradeOf(List<ChapterIssueEntity> unresolvedBlocking, int minorCount, int reviseRounds) {
        if (unresolvedBlocking != null && !unresolvedBlocking.isEmpty()) return PassGrade.DEBT;
        if (reviseRounds > 0) return PassGrade.REVISED_PASS;
        return minorCount > 0 ? PassGrade.MINOR_RESIDUE : PassGrade.CLEAN_PASS;
    }

    public boolean isLowConfidencePass() {
        return grade == PassGrade.MINOR_RESIDUE || grade == PassGrade.REVISED_PASS || grade == PassGrade.DEBT;
    }
}
