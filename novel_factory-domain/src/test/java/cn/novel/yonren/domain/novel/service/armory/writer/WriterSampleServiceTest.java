package cn.novel.yonren.domain.novel.service.armory.writer;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.config.StoryProperties.CausalityProperties.Mode;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.valobj.plan.CausalVerdict;
import cn.novel.yonren.domain.novel.model.valobj.quality.DeliveryReview;
import cn.novel.yonren.domain.novel.model.valobj.quality.GateResult;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import cn.novel.yonren.domain.novel.service.armory.quality.DeliveryReviewPolicy;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 样本仍可混合旧等级，但 UNKNOWN/UNVERIFIED/严格交付阻断稿不得成为合格范文。 */
@ExtendWith(MockitoExtension.class)
class WriterSampleServiceTest {

    private static final Path STORY_DIR = Path.of("story-sample-test");
    private final ChapterPlanItemEntity item = ChapterPlanItemEntity.builder().chapterNo(1)
            .title("交接").keyEvents(List.of("交付药签")).build();
    private final ChapterContentEntity content = ChapterContentEntity.builder().chapterNo(1).title("交接")
            .content("她递出药签，掌柜逐行核对后收下。").build();

    @Mock
    private IStoryRepository storyRepository;

    @InjectMocks
    private WriterSampleService service;

    @ParameterizedTest
    @EnumSource(Mode.class)
    void recordsDeliveryStatusHashesAndChecksAlongsideGateMetadata(Mode mode) throws Exception {
        StoryProperties properties = properties(mode);
        DeliveryReview review = mode == Mode.OFF ? DeliveryReview.notChecked()
                : review(properties, CausalVerdict.Status.PASS);
        GateResult gate = GateResult.of(List.of(), 2,
                        List.of(ChapterIssueEntity.builder().severity("MINOR").description("机械提示").build()), 1,
                        List.of(ChapterIssueEntity.builder().severity("MINOR").description("语义反馈").build()))
                .withSemanticFeedbackHash("反馈指纹")
                .withDeliveryReview(review);

        record(gate);

        JSONObject sample = recordedSample();
        assertEquals("sample-story", sample.getString("storyId"));
        assertEquals("完整正文 prompt", sample.getString("prompt"));
        assertEquals("system prompt", sample.getString("systemPrompt"));
        assertEquals(content.getContent(), sample.getString("output"));
        assertEquals(content.getContent().length(), sample.getIntValue("outputChars"));
        JSONObject savedGate = sample.getJSONObject("gate");
        assertEquals("REVISED_PASS", savedGate.getString("grade"));
        assertEquals(2, savedGate.getIntValue("minorCount"));
        assertEquals(1, savedGate.getIntValue("reviseRounds"));
        assertEquals(1, savedGate.getIntValue("mechanicalMinorCount"));
        assertEquals(1, savedGate.getIntValue("semanticMinorCount"));
        assertFalse(savedGate.getBooleanValue("auditVerifyDegraded"));
        assertEquals("反馈指纹", savedGate.getString("semanticFeedbackHash"));
        JSONObject savedReview = savedGate.getJSONObject("deliveryReview");
        assertNotNull(savedReview);
        assertEquals(review.status().name(), savedReview.getString("status"));
        assertEquals(review.enforced(), savedReview.getBooleanValue("enforced"));
        assertEquals(review.manuscriptHash(), savedReview.getString("manuscriptHash"));
        assertEquals(review.planHash(), savedReview.getString("planHash"));
        if (mode == Mode.OFF) {
            assertEquals("NOT_CHECKED", savedReview.getString("status"));
            assertNull(savedReview.getString("manuscriptHash"));
            assertNull(savedReview.getString("planHash"));
        } else {
            assertEquals("EVENTS", savedReview.getString("scope"));
            assertEquals(1, savedReview.getJSONArray("checks").size());
            JSONObject check = savedReview.getJSONArray("checks").getJSONObject(0);
            assertEquals("event:1", check.getString("targetId"));
            assertEquals(content.getContent(), check.getString("evidence"));
        }
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void ordinaryDebt_retainsLegacyMixedGradeSamples(Mode mode) throws Exception {
        StoryProperties properties = properties(mode);
        DeliveryReview review = mode == Mode.OFF ? DeliveryReview.notChecked()
                : review(properties, CausalVerdict.Status.PASS);
        GateResult debt = GateResult.of(List.of(ChapterIssueEntity.builder()
                        .severity("BLOCKING").description("既有非交付质量债").build()), 0, 2)
                .withDeliveryReview(review);

        record(debt);

        JSONObject gate = recordedSample().getJSONObject("gate");
        assertEquals("DEBT", gate.getString("grade"));
        assertEquals(1, gate.getIntValue("blockingCount"));
        assertFalse(debt.deliveryReview().blocksCommit(), "不把全部普通 DEBT 擅自改成样本禁入");
    }

    @Test
    void observeIssue_isStoredAsObservationNotStrictBlock() throws Exception {
        StoryProperties properties = properties(Mode.OBSERVE);
        DeliveryReview issue = review(properties, CausalVerdict.Status.ISSUE);
        GateResult gate = GateResult.of(List.of(), 0, 0).withDeliveryReview(issue);

        record(gate);

        JSONObject saved = recordedSample().getJSONObject("gate").getJSONObject("deliveryReview");
        assertEquals("ISSUE", saved.getString("status"));
        assertFalse(saved.getBooleanValue("enforced"));
        assertEquals(issue.manuscriptHash(), saved.getString("manuscriptHash"));
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void unknownReview_isNeverWrittenAsQualifiedSample(Mode mode) {
        StoryProperties properties = properties(mode);
        GateResult gate = GateResult.of(List.of(), 0, 0)
                .withDeliveryReview(review(properties, CausalVerdict.Status.UNKNOWN));

        record(gate);

        assertEquals(GateResult.PassGrade.UNVERIFIED, gate.grade());
        verifyNoInteractions(storyRepository);
    }

    @Test
    void unverifiedGradeWithoutUnknownReport_isStillSkipped() {
        properties(Mode.OFF);
        GateResult unverified = new GateResult(List.of(), GateResult.PassGrade.UNVERIFIED, 0,
                List.of(), 0, List.of(), false, null);
        assertEquals(DeliveryReview.Status.NOT_CHECKED, unverified.deliveryReview().status());

        record(unverified);

        verifyNoInteractions(storyRepository);
    }

    @Test
    void enforceIssueOrUncheckedReport_cannotBecomeQualifiedSampleEvenWithEmptyBlocking() {
        StoryProperties properties = properties(Mode.ENFORCE);
        GateResult issue = GateResult.of(List.of(), 0, 0)
                .withDeliveryReview(review(properties, CausalVerdict.Status.ISSUE));
        GateResult unchecked = GateResult.of(List.of(), 0, 0).withDeliveryReview(new DeliveryReview(
                DeliveryReview.Status.NOT_CHECKED, true, DeliveryReview.Scope.EVENTS,
                ChapterManuscript.of(content).hash(), DeliveryReviewPolicy.contract(item).planHash(),
                List.of(), List.of("测试严格模式未检查报告")));
        assertTrue(issue.unresolvedBlocking().isEmpty());
        assertTrue(issue.deliveryReview().blocksCommit());

        record(issue);
        record(unchecked);

        verifyNoInteractions(storyRepository);
    }

    @Test
    void offRetainsLegacyDegradedFlagAndPlanReasonAsMetadata() throws Exception {
        properties(Mode.OFF);
        GateResult gate = GateResult.of(List.of(ChapterIssueEntity.builder()
                                .severity("BLOCKING").description("旧链路质量债").build()),
                        0, List.of(), 1, List.of(), true)
                .withPlanAdjustmentRequired("旧链路计划调整提示");

        record(gate);

        JSONObject saved = recordedSample().getJSONObject("gate");
        assertTrue(saved.getBooleanValue("auditVerifyDegraded"));
        assertEquals("旧链路计划调整提示", saved.getString("planAdjustmentReason"));
        assertEquals("NOT_CHECKED", saved.getJSONObject("deliveryReview").getString("status"));
    }

    @Test
    void repositoryFailure_remainsFailSoft() throws Exception {
        properties(Mode.OFF);
        doThrow(new IllegalStateException("Mock 写样本失败")).when(storyRepository)
                .appendWriterSample(any(), anyString());

        assertDoesNotThrow(() -> record(GateResult.of(List.of(), 0, 0)));
    }

    @Test
    void missingDirectoryOrGate_doesNotWrite() {
        properties(Mode.OFF);
        service.record(null, "sample-story", 1, "prompt", null,
                content, GateResult.of(List.of(), 0, 0), "normal");
        record(null);

        verifyNoInteractions(storyRepository);
    }

    private StoryProperties properties(Mode mode) {
        StoryProperties properties = new StoryProperties();
        properties.getCausality().setAuditMode(mode);
        properties.getCausality().setPlanMode(Mode.OFF);
        return properties;
    }

    /** 只给定 Mock 语义结论，报告仍经过真实的计划、稿件身份与引文定位校验。 */
    private DeliveryReview review(StoryProperties properties, CausalVerdict.Status status) {
        var request = new DeliveryReviewPolicy.Request(DeliveryReviewPolicy.contract(item),
                ChapterManuscript.of(content), DeliveryReviewPolicy.mode(properties) == Mode.ENFORCE);
        if (status == CausalVerdict.Status.UNKNOWN) {
            return DeliveryReviewPolicy.unavailable(request, "Mock 交付核验不可用");
        }
        boolean issue = status == CausalVerdict.Status.ISSUE;
        CausalVerdict check = new CausalVerdict(null, status, content.getContent(), null,
                issue ? "Mock 裁决：必要过程未交付" : "Mock 裁决：药签交接已完成",
                issue ? "补足计划中的必要交接过程" : null,
                issue ? CausalVerdict.FailureType.RESULT_WITHOUT_CAUSE : null,
                null, null, null, null, "event:1", "keyEvents[0]", "交付药签");
        DeliveryReview review = DeliveryReviewPolicy.consume(request, List.of(check));
        assertEquals(status.name(), review.status().name());
        return review;
    }

    private void record(GateResult gate) {
        service.record(STORY_DIR, "sample-story", 1, "完整正文 prompt", "system prompt", content, gate, "normal");
    }

    private JSONObject recordedSample() throws Exception {
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(storyRepository).appendWriterSample(eq(STORY_DIR), json.capture());
        return JSON.parseObject(json.getValue());
    }
}
