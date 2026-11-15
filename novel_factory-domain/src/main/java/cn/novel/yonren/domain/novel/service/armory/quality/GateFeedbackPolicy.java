package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.valobj.quality.GateResult;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;

import java.util.List;


public final class GateFeedbackPolicy {

    private GateFeedbackPolicy() {
    }

    /** 首审统计保留；只有同版稿件且原文证据可核对的意见可作为后续写作参考。 */
    public static List<ChapterIssueEntity> feedbackMinorsFor(GateResult result, ChapterManuscript manuscript) {
        if (result == null || manuscript == null || result.semanticFeedbackHash() == null
                || !result.semanticFeedbackHash().equals(manuscript.hash()) || result.semanticMinors() == null) {
            return List.of();
        }
        String content = EvidenceMatch.normalize(manuscript.content());
        return result.semanticMinors().stream()
                .filter(issue -> issue != null && "MINOR".equalsIgnoreCase(issue.getSeverity())
                        && issue.getDescription() != null && !issue.getDescription().isBlank()
                        && EvidenceMatch.contained(issue.getEvidence(), content))
                .toList();
    }
}
