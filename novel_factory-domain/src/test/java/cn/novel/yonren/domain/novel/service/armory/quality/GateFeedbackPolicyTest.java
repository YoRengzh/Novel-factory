package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.valobj.quality.GateResult;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GateFeedbackPolicyTest {

    @Test
    void feedbackRequiresSameManuscriptAndVerifiableEvidence() {
        ChapterManuscript manuscript = new ChapterManuscript(1, "药签", "他把药签交给执事。执事收进匣中。");
        ChapterIssueEntity supported = issue("他把药签交给执事", "交付动作被无意义重复");
        ChapterIssueEntity fabricated = issue("他把药签烧掉", "药签去向冲突");
        ChapterIssueEntity missing = issue(null, "需要更多对白");
        ChapterIssueEntity punctuation = issue("……。", "节奏偏慢");
        GateResult result = GateResult.of(List.of(), 4, List.of(), 0,
                List.of(supported, fabricated, missing, punctuation)).withSemanticFeedbackHash(manuscript.hash());
        assertEquals(List.of(supported), GateFeedbackPolicy.feedbackMinorsFor(result, manuscript));
        assertEquals(4, result.minorCount(), "首审统计保留，不把反馈过滤冒充问题修复");
        assertTrue(GateFeedbackPolicy.feedbackMinorsFor(result,
                new ChapterManuscript(1, "药签", "他把药签留在袖中。")).isEmpty());
        assertTrue(GateFeedbackPolicy.feedbackMinorsFor(result, null).isEmpty());
    }

    @Test
    void feedbackDoesNotTreatBlockingOrEmptyDescriptionsAsAdvice() {
        ChapterManuscript manuscript = new ChapterManuscript(1, "药签", "他把药签交给执事。");
        ChapterIssueEntity blocking = issue("他把药签交给执事", "缺少必要过程");
        blocking.setSeverity("BLOCKING");
        ChapterIssueEntity empty = issue("他把药签交给执事", " ");
        GateResult result = GateResult.of(List.of(), 2, List.of(), 0, List.of(blocking, empty))
                .withSemanticFeedbackHash(manuscript.hash());
        assertTrue(GateFeedbackPolicy.feedbackMinorsFor(result, manuscript).isEmpty());
    }

    @Test
    void feedbackRejectsChangedChapterNumberTitleOrBodyWhitespace() {
        ChapterManuscript original = new ChapterManuscript(1, "药签", "执事收好药签。");
        ChapterIssueEntity supported = issue("执事收好药签", "收纳动作重复");
        GateResult result = GateResult.of(List.of(), 1, List.of(), 0, List.of(supported))
                .withSemanticFeedbackHash(original.hash());
        for (ChapterManuscript changed : List.of(
                new ChapterManuscript(2, original.title(), original.content()),
                new ChapterManuscript(1, "新标题", original.content()),
                new ChapterManuscript(1, original.title(), "执事 收好药签。"))) {
            assertEquals(EvidenceMatch.normalize(original.content()), EvidenceMatch.normalize(changed.content()));
            assertTrue(GateFeedbackPolicy.feedbackMinorsFor(result, changed).isEmpty(),
                    "证据仍可定位也不能跨稿件身份复用反馈");
        }
    }

    @Test
    void feedbackRequiresResultManuscriptAndBoundSource() {
        ChapterManuscript manuscript = new ChapterManuscript(1, "药签", "执事收好药签。");
        GateResult unbound = GateResult.of(List.of(), 1, List.of(), 0,
                List.of(issue("执事收好药签", "收纳动作重复")));
        assertTrue(GateFeedbackPolicy.feedbackMinorsFor(null, manuscript).isEmpty());
        assertTrue(GateFeedbackPolicy.feedbackMinorsFor(unbound, manuscript).isEmpty());
        assertTrue(GateFeedbackPolicy.feedbackMinorsFor(unbound.withSemanticFeedbackHash(manuscript.hash()), null).isEmpty());
        GateResult missingIssues = new GateResult(List.of(), GateResult.PassGrade.MINOR_RESIDUE,
                1, List.of(), 0, null, false, manuscript.hash());
        assertTrue(GateFeedbackPolicy.feedbackMinorsFor(missingIssues, manuscript).isEmpty());
    }

    @Test
    void feedbackIgnoresNullIssuesSeverityAndDescriptions() {
        ChapterManuscript manuscript = new ChapterManuscript(1, "药签", "执事收好药签。");
        ChapterIssueEntity supported = issue("执事收好药签", "收纳动作重复");
        supported.setSeverity("minor");
        ChapterIssueEntity missingSeverity = issue("执事收好药签", "收纳动作重复");
        missingSeverity.setSeverity(null);
        List<ChapterIssueEntity> observations = new ArrayList<>(Arrays.asList(null, missingSeverity,
                issue("执事收好药签", null), issue("执事收好药签", ""),
                issue("执事收好药签", " \t\n\u3000"), supported));
        List<ChapterIssueEntity> snapshot = new ArrayList<>(observations);
        GateResult result = GateResult.of(List.of(), 6, List.of(), 0, observations)
                .withSemanticFeedbackHash(manuscript.hash());
        assertEquals(List.of(supported), GateFeedbackPolicy.feedbackMinorsFor(result, manuscript));
        assertSame(observations, result.semanticMinors());
        assertEquals(snapshot, observations, "过滤不能改写审校原始清单");
        assertEquals(6, result.minorCount());
        assertEquals(GateResult.PassGrade.MINOR_RESIDUE, result.grade());
        assertEquals(manuscript.hash(), result.semanticFeedbackHash());
    }

    @Test
    void feedbackRejectsEmptyWhitespaceAndPurePunctuationEvidence() {
        ChapterManuscript manuscript = new ChapterManuscript(1, "药签", "执事说道：“药签已收好。”……！『』");
        for (String evidence : Arrays.asList(null, "", " \t\n\u3000", "“”", "「」", "『』",
                "。", "……", "……。", "...", "……！", "“……。”")) {
            GateResult result = GateResult.of(List.of(), 1, List.of(), 0,
                    List.of(issue(evidence, "节奏偏慢"))).withSemanticFeedbackHash(manuscript.hash());
            assertTrue(GateFeedbackPolicy.feedbackMinorsFor(result, manuscript).isEmpty(),
                    "不能把无实词证据回灌：" + evidence);
        }
    }

    @Test
    void feedbackAcceptsShortLiteralEvidence() {
        ChapterManuscript manuscript = new ChapterManuscript(1, "药签", "执事说道：“退婚。”编号7。");
        List<ChapterIssueEntity> supported = List.of(issue("退婚", "决定重复说明"), issue("7", "编号反复强调"));
        GateResult result = GateResult.of(List.of(), 2, List.of(), 0, supported)
                .withSemanticFeedbackHash(manuscript.hash());
        assertEquals(supported, GateFeedbackPolicy.feedbackMinorsFor(result, manuscript));
    }

    @Test
    void feedbackNormalizesQuotesNewlinesAndFullWidthSpaces() {
        ChapterManuscript manuscript = new ChapterManuscript(1, "药签", "执事说：“交出药签。”\n‘收到。’\t药签\u3000入匣。");
        List<ChapterIssueEntity> supported = List.of(
                issue("执事说：\"交出药签。\"\\n'收到。'\t药签 入匣。", "交付动作重复"),
                issue("「执事说：“交出药签。”\n‘收到。’\t药签\u3000入匣。」", "交付动作重复"));
        GateResult result = GateResult.of(List.of(), 2, List.of(), 0, supported)
                .withSemanticFeedbackHash(manuscript.hash());
        assertEquals(supported, GateFeedbackPolicy.feedbackMinorsFor(result, manuscript));
    }

    @Test
    void feedbackRequiresEveryEllipsisFragmentToMatch() {
        ChapterManuscript manuscript = new ChapterManuscript(1, "药签", "执事让人交出药签。核对编号后，药签入匣。");
        ChapterIssueEntity chinese = issue("交出药签……药签入匣", "交付动作重复");
        ChapterIssueEntity english = issue("交出药签...药签入匣", "交付动作重复");
        ChapterIssueEntity reordered = issue("药签入匣……交出药签", "交付动作重复");
        ChapterIssueEntity fabricatedChinese = issue("交出药签……药签焚毁", "药签去向冲突");
        ChapterIssueEntity fabricatedEnglish = issue("交出药签...药签焚毁", "药签去向冲突");
        GateResult result = GateResult.of(List.of(), 5, List.of(), 0,
                List.of(chinese, english, reordered, fabricatedChinese, fabricatedEnglish))
                .withSemanticFeedbackHash(manuscript.hash());
        assertEquals(List.of(chinese, english, reordered), GateFeedbackPolicy.feedbackMinorsFor(result, manuscript));
        assertEquals(5, result.minorCount());
    }

    private ChapterIssueEntity issue(String evidence, String description) {
        return ChapterIssueEntity.builder().dimension("pacing").severity("MINOR")
                .evidence(evidence).description(description).build();
    }
}
