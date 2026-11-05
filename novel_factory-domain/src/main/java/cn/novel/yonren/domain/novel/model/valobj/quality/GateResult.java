package cn.novel.yonren.domain.novel.model.valobj.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;

import java.util.List;


public record GateResult(List<ChapterIssueEntity> unresolvedBlocking, PassGrade grade,
                         int minorCount, List<ChapterIssueEntity> mechanicalMinor, int reviseRounds,
                         List<ChapterIssueEntity> semanticMinors, boolean auditVerifyDegraded) {

    /** 通过质量分级 */
    public enum PassGrade {
        /** 首轮全票通过：零修订轮次且零 MINOR 残留 */
        CLEAN_PASS,
        /** 首轮通过但有叙事 MINOR 残留（低置信通过） */
        MINOR_RESIDUE,
        /** 经修订闭环后才通过（低置信通过） */
        REVISED_PASS,
        /** 修订耗尽仍有未修复 BLOCKING（质量债） */
        DEBT
    }

    /**
     * 工厂：按未修复 BLOCKING / 修订轮数 / 叙事 MINOR 残留推导分级。
     * 机械文风 MINOR 不传入——它不影响分级。
     */
    public static GateResult of(List<ChapterIssueEntity> unresolvedBlocking, int minorCount, int reviseRounds) {
        return of(unresolvedBlocking, minorCount, List.of(), reviseRounds);
    }

    /**
     * 工厂：带机械文风 MINOR 的重载。
     * grade 只用 minorCount 推导，mechanicalMinor 仅随结果传递供上层记债/统计。
     */
    public static GateResult of(List<ChapterIssueEntity> unresolvedBlocking, int minorCount,
                                List<ChapterIssueEntity> mechanicalMinor, int reviseRounds) {
        return of(unresolvedBlocking, minorCount, mechanicalMinor, reviseRounds, List.of());
    }

    /** 工厂：带语义 MINOR 清单的全参重载（审校反馈回灌的数据源）；未验证标记默认 false */
    public static GateResult of(List<ChapterIssueEntity> unresolvedBlocking, int minorCount,
                                List<ChapterIssueEntity> mechanicalMinor, int reviseRounds,
                                List<ChapterIssueEntity> semanticMinors) {
        return of(unresolvedBlocking, minorCount, mechanicalMinor, reviseRounds, semanticMinors, false);
    }


    public static GateResult of(List<ChapterIssueEntity> unresolvedBlocking, int minorCount,
                                List<ChapterIssueEntity> mechanicalMinor, int reviseRounds,
                                List<ChapterIssueEntity> semanticMinors, boolean auditVerifyDegraded) {
        boolean debt = unresolvedBlocking != null && !unresolvedBlocking.isEmpty();
        PassGrade grade;
        if (debt) {
            grade = PassGrade.DEBT;
        } else if (reviseRounds > 0) {
            grade = PassGrade.REVISED_PASS;
        } else {
            grade = minorCount > 0 ? PassGrade.MINOR_RESIDUE : PassGrade.CLEAN_PASS;
        }
        return new GateResult(debt ? unresolvedBlocking : List.of(), grade, minorCount,
                mechanicalMinor == null ? List.of() : mechanicalMinor, reviseRounds,
                semanticMinors == null ? List.of() : semanticMinors, auditVerifyDegraded);
    }


    public boolean isLowConfidencePass() {
        return grade == PassGrade.MINOR_RESIDUE || grade == PassGrade.REVISED_PASS
                || grade == PassGrade.DEBT;
    }
}
