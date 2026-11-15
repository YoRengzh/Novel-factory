package cn.novel.yonren.domain.novel.model.valobj.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GateResultTest {

    @Test
    void feedbackRequiresSameManuscriptAndVerifiableEvidence() {
        ChapterManuscript manuscript = new ChapterManuscript(1, "药签", "他把药签交给执事。执事收进匣中。");
        ChapterIssueEntity supported = issue("他把药签交给执事", "交付动作被无意义重复");
        ChapterIssueEntity fabricated = issue("他把药签烧掉", "药签去向冲突");
        ChapterIssueEntity missing = issue(null, "需要更多对白");
        ChapterIssueEntity punctuation = issue("……。", "节奏偏慢");
        GateResult result = GateResult.of(List.of(), 4, List.of(), 0,
                List.of(supported, fabricated, missing, punctuation)).withSemanticFeedbackHash(manuscript.hash());
        assertEquals(List.of(supported), result.feedbackMinorsFor(manuscript));
        assertEquals(4, result.minorCount(), "首审统计保留，不把反馈过滤冒充问题修复");
        assertTrue(result.feedbackMinorsFor(new ChapterManuscript(1, "药签", "他把药签留在袖中。")).isEmpty());
        assertTrue(result.feedbackMinorsFor(null).isEmpty());
    }

    @Test
    void feedbackDoesNotTreatBlockingOrEmptyDescriptionsAsAdvice() {
        ChapterManuscript manuscript = new ChapterManuscript(1, "药签", "他把药签交给执事。");
        ChapterIssueEntity blocking = issue("他把药签交给执事", "缺少必要过程");
        blocking.setSeverity("BLOCKING");
        ChapterIssueEntity empty = issue("他把药签交给执事", " ");
        GateResult result = GateResult.of(List.of(), 2, List.of(), 0, List.of(blocking, empty))
                .withSemanticFeedbackHash(manuscript.hash());
        assertTrue(result.feedbackMinorsFor(manuscript).isEmpty());
    }

    private ChapterIssueEntity issue(String evidence, String description) {
        return ChapterIssueEntity.builder().dimension("pacing").severity("MINOR")
                .evidence(evidence).description(description).build();
    }
}
