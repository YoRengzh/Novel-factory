package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.valobj.quality.GateResult;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SemanticFeedbackVersionTest {
    private final ChapterManuscript original = new ChapterManuscript(1, "标题", "原稿没有对白。");
    private final ChapterIssueEntity issue = ChapterIssueEntity.builder()
            .severity("MINOR").dimension("aesthetic").description("有效对话为0句").build();

    @Test
    void revisedManuscriptDoesNotReuseInitialFeedbackOrEraseObservations() {
        GateResult checks = GateResult.of(List.of(), 1, List.of(), 1, List.of(issue), false)
                .withSemanticFeedbackHash(original.hash());
        assertTrue(checks.feedbackMinorsFor(new ChapterManuscript(1, "标题", "“回来。”\n“好。”")).isEmpty());
        assertEquals(List.of(issue), checks.semanticMinors());
        assertEquals(1, checks.minorCount());
        assertEquals(GateResult.PassGrade.REVISED_PASS, checks.grade());
    }

    @Test
    void unchangedManuscriptRetainsFeedbackRegardlessOfRevisionRound() {
        GateResult checks = GateResult.of(List.of(), 1, List.of(), 2, List.of(issue), false)
                .withSemanticFeedbackHash(original.hash());
        assertEquals(List.of(issue), checks.feedbackMinorsFor(original));
        assertTrue(checks.feedbackMinorsFor(new ChapterManuscript(1, "新标题", original.content())).isEmpty());
    }

    @Test
    void historicalChecksWithoutFeedbackSourceStayRecoverableButDoNotInstructWriting() {
        GateResult historical = GateResult.of(List.of(), 1, List.of(), 0, List.of(issue), false);
        GateResult recovered = JSON.parseObject(JSON.toJSONString(historical), GateResult.class);
        assertNull(recovered.semanticFeedbackHash());
        assertTrue(recovered.feedbackMinorsFor(original).isEmpty());
        assertEquals(1, recovered.minorCount());
    }

    @Test
    void feedbackSourceSurvivesJsonWithoutBeingReboundToTheAcceptedManuscript() {
        GateResult checks = GateResult.of(List.of(), 1, List.of(), 1, List.of(issue), false)
                .withSemanticFeedbackHash(original.hash());
        GateResult recovered = JSON.parseObject(JSON.toJSONString(checks), GateResult.class);
        assertEquals(original.hash(), recovered.semanticFeedbackHash());
        assertEquals(1, recovered.feedbackMinorsFor(original).size());
        assertTrue(recovered.feedbackMinorsFor(new ChapterManuscript(1, "标题", "修订后的正文。")).isEmpty());
    }
}
