package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.valobj.quality.GateResult;

import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.AuditResultEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.RevisionDecisionEntity;
import cn.novel.yonren.domain.novel.model.entity.StyleStatEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.service.armory.prompt.WritingContractPolicy;
import org.mockito.ArgumentCaptor;
import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.audit.AuditSampleService;
import cn.novel.yonren.domain.novel.service.armory.audit.ChapterAuditService;
import cn.novel.yonren.domain.novel.service.armory.memory.ChapterMemoryService;
import cn.novel.yonren.domain.novel.service.armory.revise.ChapterReviseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单章质量门测试（四期闭环自愈 + 六期扩展）：审校关闭/零问题/仅 MINOR 不修订；
 * BLOCKING 按修订开关、采纳闸门与修订稿复审结果决定返回；修订异常保留 BLOCKING 结论不抛出；
 * 未闭环路径落失败样本。六期：GateResult 通过质量分级（MINOR_RESIDUE/REVISED_PASS）；
 * 禁泄关键词机械兜底；低计划覆盖率注入审校加审预警
 */
class QualityGateTest {

    @Mock
    private ChapterAuditService chapterAuditService;

    @Mock
    private ChapterReviseService chapterReviseService;

    @Mock
    private ChapterMemoryService chapterMemoryService;

    @Mock
    private StoryProperties storyProperties;

    @Mock
    private AuditSampleService auditSampleService;

    @Mock
    private cn.novel.yonren.domain.novel.service.armory.memory.ConsistencyIndexService consistencyIndexService;

    /** 跨章对白查重：默认桩成"无重复"，既有用例行为不变；对白重复用例单独覆盖 */
    @Mock
    private cn.novel.yonren.domain.novel.service.armory.memory.StyleStatService styleStatService;

    @InjectMocks
    private QualityGate qualityGate;

    private StoryProperties.AuditProperties audit;
    private StoryProperties.ReviseProperties revise;
    private ArmoryCommandEntity command;
    private ChapterPlanItemEntity item;
    private ChapterContentEntity chapterContent;
    private StyleStatEntity styleStat;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        audit = new StoryProperties.AuditProperties();
        audit.setEnforceMinimumChapterLength(false);
        // 篇幅坍缩底线与 1500 参考线告警是独立开关：本类多数用例用极短占位正文（"原文"）
        // 驱动审校/修订分支，故默认关掉坍缩底线；坍缩相关用例自行打开
        audit.setEnforceCollapseFloor(false);
        revise = new StoryProperties.ReviseProperties();
        when(storyProperties.getAudit()).thenReturn(audit);
        when(storyProperties.getRevise()).thenReturn(revise);
        when(chapterMemoryService.buildSecrecyGuard(anyList(), any()))
                .thenReturn(ChapterMemoryService.NO_SECRECY);
        command = ArmoryCommandEntity.builder().build();
        item = new ChapterPlanItemEntity();
        chapterContent = ChapterContentEntity.builder().chapterNo(1).title("原标题").content("原文").build();
        styleStat = StyleStatEntity.builder().build();
    }

    private AuditResultEntity blockingResult() {
        return AuditResultEntity.builder()
                .issues(List.of(ChapterIssueEntity.builder().severity("BLOCKING").description("连续性断裂").build()))
                .build();
    }

    @Test
    void auditDisabled_returnsEmpty_withoutCallingAudit() {
        audit.setEnabled(false);

        assertTrue(qualityGate.auditAndReviseIfEnabled(command, item, chapterContent, List.of(), styleStat, 1, null)
                .unresolvedBlocking().isEmpty());
        verify(chapterAuditService, never()).audit(any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void chapterBelow1500Characters_noLongerBlocking_proceedsToAudit() {
        // 字数不足改为仅告警（拒收只会逼模型注水，密度信号改由摘要回灌规划层治本）
        audit.setEnforceMinimumChapterLength(true);
        when(chapterAuditService.audit(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(AuditResultEntity.builder().build());

        GateResult result = qualityGate.auditAndReviseIfEnabled(
                command, item, chapterContent, List.of(), styleStat, 1, null);

        assertTrue(result.unresolvedBlocking().isEmpty(), "短章不再是 BLOCKING");
        verify(chapterAuditService).audit(any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void chapterAt1500Characters_continuesToAudit() {
        audit.setEnforceMinimumChapterLength(true);
        chapterContent.setContent("字".repeat(1500));
        when(chapterAuditService.audit(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(AuditResultEntity.builder().build());

        assertTrue(qualityGate.auditAndReviseIfEnabled(command, item, chapterContent, List.of(), styleStat, 1, null)
                .unresolvedBlocking().isEmpty());
        verify(chapterAuditService).audit(any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void shortChapter_doesNotTriggerRevise() {
        // 短章不再触发修订：字数供给问题由规划层的密度反馈解决，不靠正文层注水式返工
        audit.setEnabled(false);
        audit.setEnforceMinimumChapterLength(true);
        revise.setEnabled(true);

        GateResult result = qualityGate.auditAndReviseIfEnabled(
                command, item, chapterContent, List.of(), styleStat, 1, null);

        assertTrue(result.unresolvedBlocking().isEmpty());
        assertEquals("原文", chapterContent.getContent(), "短章保留原稿");
        verify(chapterReviseService, never()).revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any());
        verify(chapterAuditService, never()).audit(any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void auditNoIssues_returnsCleanPass() {
        when(chapterAuditService.audit(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(AuditResultEntity.builder().build());

        GateResult result = qualityGate.auditAndReviseIfEnabled(
                command, item, chapterContent, List.of(), styleStat, 1, null);

        assertTrue(result.unresolvedBlocking().isEmpty());
        assertEquals(GateResult.PassGrade.CLEAN_PASS, result.grade());
        verify(chapterReviseService, never()).revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void onlyMinorIssues_returnsMinorResidue_withoutRevise() {
        when(chapterAuditService.audit(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(AuditResultEntity.builder()
                        .issues(List.of(ChapterIssueEntity.builder().severity("MINOR").description("小瑕疵").build()))
                        .build());

        GateResult result = qualityGate.auditAndReviseIfEnabled(
                command, item, chapterContent, List.of(), styleStat, 1, null);

        assertTrue(result.unresolvedBlocking().isEmpty());
        assertEquals(GateResult.PassGrade.MINOR_RESIDUE, result.grade(), "MINOR 残留 = 低置信通过");
        assertEquals(1, result.minorCount());
        assertTrue(result.isLowConfidencePass());
        verify(chapterReviseService, never()).revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void blocking_withReviseDisabled_returnsBlockingAndRecordsSample() {
        revise.setEnabled(false);
        when(chapterAuditService.audit(any(), any(), any(), any(), any(), any(), anyInt(), any())).thenReturn(blockingResult());

        GateResult result =
                qualityGate.auditAndReviseIfEnabled(command, item, chapterContent, List.of(), styleStat, 1, null);

        assertEquals(1, result.unresolvedBlocking().size());
        assertEquals(GateResult.PassGrade.DEBT, result.grade());
        verify(chapterReviseService, never()).revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any());
        verify(auditSampleService).record(any(), anyInt(), any(), anyString(), anyList(), anyInt(), any(), anyString());
    }

    @Test
    void debt_isLowConfidencePass_soCandidateIsTriggered() {
        // DEBT 此前被 isLowConfidencePass 排除，导致"修订两轮仍不收敛"的章
        // 恰恰是候选选优**唯一没轮到**的一类——而它最需要换一个写法。
        // 这里钉住语义：DEBT 虽不是"通过"，但必须被认作"低置信"，否则候选永远不介入。
        GateResult debt = GateResult.of(blockingResult().getIssues(), 0, 2);

        assertEquals(GateResult.PassGrade.DEBT, debt.grade());
        assertTrue(debt.isLowConfidencePass(), "DEBT 必须触发候选选优");
        assertFalse(debt.unresolvedBlocking().isEmpty(), "但账要照记：DEBT 仍携带未修复 BLOCKING");
    }

    @Test
    void cleanPass_isNotLowConfidence_soCandidateIsSkipped() {
        GateResult clean = GateResult.of(List.of(), 0, 0);

        assertEquals(GateResult.PassGrade.CLEAN_PASS, clean.grade());
        assertFalse(clean.isLowConfidencePass(), "全票通过不必起候选（那是纯成本）");
    }

    /**
     * 跨章完整台词重复必须产生 BLOCKING 并进入定向修订，不能仅留后续软警示。
     */
    @Test
    void crossChapterDialogueRepeat_isBlocking_andDrivesRevision() {
        revise.setEnabled(true);
        when(styleStatService.crossChapterDialogueRepeats(any(), any()))
                .thenReturn(List.of("「那句话还会不会兑现？」"));
        when(chapterAuditService.audit(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(AuditResultEntity.builder().issues(List.of()).build());
        when(chapterReviseService.revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new cn.novel.yonren.domain.novel.service.armory.revise.ChapterReviseService.ReviseResult(
                        RevisionDecisionEntity.builder().accepted(false).reason("修订未通过采纳闸门").build(),
                        null));

        GateResult result =
                qualityGate.auditAndReviseIfEnabled(command, item, chapterContent, List.of(), styleStat, 102, null);

        assertTrue(result.unresolvedBlocking().stream()
                        .anyMatch(i -> i.getEvidence() != null && i.getEvidence().contains("那句话还会不会兑现")),
                "跨章对白重复应记 BLOCKING：" + result.unresolvedBlocking());
        verify(chapterReviseService, atLeastOnce()).revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void planIntegrityCandidateUnfixed_rollsBackAcceptedRevisionAndStopsRetry() {
        revise.setMaxAttempts(3);
        when(chapterAuditService.audit(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(blockingResult());
        when(chapterAuditService.verifyFixes(any(), any(), any(), anyList(), any(), any(), anyInt(), any(), anyList()))
                .thenAnswer(inv -> {
                    // 真实服务会把候选合成 plan-integrity 条目再送复核；mock 直接返回未修复结论。
                    List<ChapterIssueEntity> unfixed = new java.util.ArrayList<>();
                    for (String candidate : (List<String>) inv.getArgument(8)) {
                        unfixed.add(ChapterIssueEntity.builder()
                                .dimension(ChapterAuditService.PLAN_INTEGRITY_DIMENSION)
                                .severity("BLOCKING").evidence(candidate).build());
                    }
                    return new ChapterAuditService.FixVerification(unfixed, false);
                });
        when(chapterReviseService.revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new ChapterReviseService.ReviseResult(
                        RevisionDecisionEntity.builder().attempted(true).accepted(true)
                                .reason("修订完成")
                                .reviewCandidates(List.of("主角交出药签"))
                                .build(),
                        ChapterContentEntity.builder().chapterNo(1).title("删改章").content("修订正文").build()));

        GateResult result =
                qualityGate.auditAndReviseIfEnabled(command, item, chapterContent, List.of(), styleStat, 1, null);

        assertEquals("原文", chapterContent.getContent(), "计划事件未获复核时不得保留修订稿");
        assertTrue(!result.unresolvedBlocking().isEmpty());
        assertEquals(1, result.reviseRounds());
        verify(chapterReviseService, times(1)).revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void planAdjustmentRequired_keepsOriginalAndDoesNotRetry() {
        revise.setMaxAttempts(3);
        when(chapterAuditService.audit(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(blockingResult());
        when(chapterReviseService.revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new ChapterReviseService.ReviseResult(
                        RevisionDecisionEntity.builder().attempted(true).accepted(false)
                                .planAdjustmentRequired(true)
                                .reason("冻结事实中没有取回药签，计划需调整")
                                .build(), null));

        GateResult result =
                qualityGate.auditAndReviseIfEnabled(command, item, chapterContent, List.of(), styleStat, 1, null);

        assertEquals("原文", chapterContent.getContent());
        assertTrue(result.planAdjustmentRequired());
        assertTrue(result.planAdjustmentReason().contains("计划需调整"));
        assertEquals(1, result.reviseRounds(), "计划冲突不应伪装成多轮普通失败");
        verify(chapterReviseService, times(1)).revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void blocking_reviseAccepted_reAuditClean_replacesContent_returnsRevisedPass() {
        // A3 复审验证化：复审=verifyFixes 逐条验证（空列表=全部已修复），不再是第二次全章 audit
        when(chapterAuditService.audit(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(blockingResult());
        when(chapterAuditService.verifyFixes(any(), any(), any(), anyList(), any(), any(), anyInt()))
                .thenReturn(new ChapterAuditService.FixVerification(List.of(), false));
        when(chapterReviseService.revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new ChapterReviseService.ReviseResult(
                        RevisionDecisionEntity.builder().accepted(true).reason("修订质量更高").build(),
                        ChapterContentEntity.builder().chapterNo(1).title("修订标题").content("修订正文").build()));

        GateResult result =
                qualityGate.auditAndReviseIfEnabled(command, item, chapterContent, List.of(), styleStat, 1, null);

        assertTrue(result.unresolvedBlocking().isEmpty());
        assertEquals(GateResult.PassGrade.REVISED_PASS, result.grade(), "经修订闭环 = 低置信通过");
        assertEquals(1, result.reviseRounds());
        assertTrue(result.isLowConfidencePass());
        assertEquals("修订标题", chapterContent.getTitle());
        assertEquals("修订正文", chapterContent.getContent());
        verify(chapterAuditService, times(1)).audit(any(), any(), any(), any(), any(), any(), anyInt(), any());
        verify(chapterAuditService, times(1)).verifyFixes(any(), any(), any(), anyList(), any(), any(), anyInt());
        verify(auditSampleService, never()).record(any(), anyInt(), any(), anyString(), anyList(), anyInt(), any(), anyString());
    }

    @Test
    void revisionKeepsInitialMinorStatisticsButInvalidatesItsWritingFeedback() {
        var minor = ChapterIssueEntity.builder().severity("MINOR").dimension("aesthetic")
                .description("原文在此重复说明同一事实").evidence("原文").build();
        var original = cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript.of(chapterContent);
        var issues = new java.util.ArrayList<>(blockingResult().getIssues());
        issues.add(minor);
        when(chapterAuditService.audit(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(AuditResultEntity.builder().issues(issues).build());
        when(chapterAuditService.verifyFixes(any(), any(), any(), anyList(), any(), any(), anyInt()))
                .thenReturn(new ChapterAuditService.FixVerification(List.of(), false));
        when(chapterReviseService.revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new ChapterReviseService.ReviseResult(
                        RevisionDecisionEntity.builder().accepted(true).reason("补齐对白").build(),
                        ChapterContentEntity.builder().chapterNo(1).title("原标题")
                                .content("“问清楚。”\n“我答应。”\n".repeat(21)).build()));
        GateResult result = qualityGate.auditAndReviseIfEnabled(command, item, chapterContent, List.of(), styleStat, 1, null);
        assertEquals(original.hash(), result.semanticFeedbackHash());
        assertEquals(1, result.minorCount());
        assertEquals(List.of(minor), result.semanticMinors());
        assertTrue(result.feedbackMinorsFor(cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript.of(chapterContent)).isEmpty());
        assertEquals(1, result.feedbackMinorsFor(original).size());
        verify(chapterAuditService, times(1)).audit(any(), any(), any(), any(), any(), any(), anyInt(), any());
        verify(chapterAuditService, times(1)).verifyFixes(any(), any(), any(), anyList(), any(), any(), anyInt());
    }

    @Test
    void verifyDegraded_isFlaggedSeparatelyFromUnfixed_andStaysConservative() {
        // 验证"没跑成"必须与"确认未修复"分开——原先两者都走"未修复"，
        // 于是一次网关抖动会被记成质量债，再回灌给写手当作"你上一章犯的错"。
        // 本用例锁定两件事：①标记透出；②内容侧偏向不变（仍算未通过、不放行未验证的稿）
        when(chapterAuditService.audit(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(blockingResult());
        when(chapterAuditService.verifyFixes(any(), any(), any(), anyList(), any(), any(), anyInt()))
                .thenAnswer(inv -> new ChapterAuditService.FixVerification(
                        new java.util.ArrayList<>(inv.getArgument(3)), true));
        when(chapterReviseService.revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new ChapterReviseService.ReviseResult(
                        RevisionDecisionEntity.builder().accepted(true).reason("修订质量更高").build(),
                        ChapterContentEntity.builder().chapterNo(1).title("修订标题").content("修订正文").build()));

        GateResult result =
                qualityGate.auditAndReviseIfEnabled(command, item, chapterContent, List.of(), styleStat, 1, null);

        assertTrue(result.auditVerifyDegraded(), "验证未跑成必须透出标记，否则上层无从区分【未验证】与【未修复】");
        assertTrue(!result.unresolvedBlocking().isEmpty(), "内容侧仍保守：不放行未验证的稿");
        assertEquals(GateResult.PassGrade.DEBT, result.grade(), "未验证仍算未通过（偏向不变，只是不落债）");
    }

    @Test
    void blocking_reviseAccepted_reAuditStillBlocking_maxAttemptsExhausted_returnsBlockingAndRecordsSample() {
        revise.setMaxAttempts(2);
        // 首审 BLOCKING；两轮复核均确认未修复（verifyFixes 原样回传入参=全部未修复）；两轮修订均被采纳
        when(chapterAuditService.audit(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(blockingResult());
        when(chapterAuditService.verifyFixes(any(), any(), any(), anyList(), any(), any(), anyInt()))
                .thenAnswer(inv -> new ChapterAuditService.FixVerification(
                        new java.util.ArrayList<>(inv.getArgument(3)), false));
        when(chapterReviseService.revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new ChapterReviseService.ReviseResult(
                        RevisionDecisionEntity.builder().accepted(true).reason("尽力修订").build(),
                        ChapterContentEntity.builder().chapterNo(1).title("修订标题").content("修订正文").build()));

        GateResult result =
                qualityGate.auditAndReviseIfEnabled(command, item, chapterContent, List.of(), styleStat, 1, null);

        assertEquals(1, result.unresolvedBlocking().size());
        assertEquals("修订正文", chapterContent.getContent(), "轮次耗尽后保留最后一版采纳稿");
        verify(chapterAuditService, times(1)).audit(any(), any(), any(), any(), any(), any(), anyInt(), any());
        verify(chapterAuditService, times(2)).verifyFixes(any(), any(), any(), anyList(), any(), any(), anyInt());
        verify(chapterReviseService, times(2)).revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any());
        verify(auditSampleService).record(any(), anyInt(), any(), anyString(), anyList(), anyInt(), any(), anyString());
    }

    @Test
    void blocking_reviseAccepted_thenSecondRoundClean_returnsEmpty() {
        revise.setMaxAttempts(2);
        // 第一轮复核确认未修复（回传入参），第二轮复核全部已修复（空列表）
        when(chapterAuditService.audit(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(blockingResult());
        when(chapterAuditService.verifyFixes(any(), any(), any(), anyList(), any(), any(), anyInt()))
                .thenAnswer(inv -> new ChapterAuditService.FixVerification(
                        new java.util.ArrayList<>(inv.getArgument(3)), false))
                .thenReturn(new ChapterAuditService.FixVerification(List.of(), false));
        when(chapterReviseService.revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new ChapterReviseService.ReviseResult(
                        RevisionDecisionEntity.builder().accepted(true).reason("修订质量更高").build(),
                        ChapterContentEntity.builder().chapterNo(1).title("修订标题").content("修订正文").build()));

        GateResult result =
                qualityGate.auditAndReviseIfEnabled(command, item, chapterContent, List.of(), styleStat, 1, null);

        assertTrue(result.unresolvedBlocking().isEmpty());
        verify(chapterAuditService, times(1)).audit(any(), any(), any(), any(), any(), any(), anyInt(), any());
        verify(chapterAuditService, times(2)).verifyFixes(any(), any(), any(), anyList(), any(), any(), anyInt());
        verify(chapterReviseService, times(2)).revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any());
        verify(auditSampleService, never()).record(any(), anyInt(), any(), anyString(), anyList(), anyInt(), any(), anyString());
    }

    @Test
    void blocking_reviseRejected_keepsOriginal_returnsBlockingAndRecordsSample() {
        when(chapterAuditService.audit(any(), any(), any(), any(), any(), any(), anyInt(), any())).thenReturn(blockingResult());
        when(chapterReviseService.revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new ChapterReviseService.ReviseResult(
                        RevisionDecisionEntity.builder().accepted(false).reason("修订不如原稿").build(),
                        ChapterContentEntity.builder().chapterNo(1).title("修订标题").content("修订正文").build()));

        GateResult result =
                qualityGate.auditAndReviseIfEnabled(command, item, chapterContent, List.of(), styleStat, 1, null);

        assertEquals(1, result.unresolvedBlocking().size());
        assertEquals("原文", chapterContent.getContent());
        verify(auditSampleService).record(any(), anyInt(), any(), anyString(), anyList(), anyInt(), any(), anyString());
    }

    @Test
    void mechanicalBlocking_triggersReviseWithoutCallingLlmAudit() {
        // 机械门禁的 BLOCKING 档（章节编号元信息泄露）：审校关闭时依然触发修订
        audit.setEnabled(false);
        chapterContent.setContent("他在第一章里写下了那段推导，随后便把它忘了。");
        when(chapterReviseService.revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new ChapterReviseService.ReviseResult(
                        RevisionDecisionEntity.builder().accepted(true).reason("定点清除元信息").build(),
                        ChapterContentEntity.builder().chapterNo(1).title("修订标题")
                                .content("他写下那段推导，随后便把它忘了。").build()));

        GateResult result = qualityGate.auditAndReviseIfEnabled(
                command, item, chapterContent, List.of(), styleStat, 1, null);

        assertTrue(result.unresolvedBlocking().isEmpty(), "修订稿清除违规后应闭环");
        assertEquals("他写下那段推导，随后便把它忘了。", chapterContent.getContent());
        verify(chapterAuditService, never()).audit(any(), any(), any(), any(), any(), any(), anyInt(), any());
        verify(chapterReviseService).revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void mechanicalBlocking_rescanStillViolating_notClosed() {
        // 修订稿仍带元信息：复审重扫机械门禁，轮次耗尽后记为质量债
        audit.setEnabled(false);
        revise.setMaxAttempts(1);
        chapterContent.setContent("他在第一章里写下了那段推导，随后便把它忘了。");
        when(chapterReviseService.revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new ChapterReviseService.ReviseResult(
                        RevisionDecisionEntity.builder().accepted(true).reason("试图修订").build(),
                        ChapterContentEntity.builder().chapterNo(1).title("修订标题")
                                .content("他翻回第一章，又把那段推导抄了一遍。").build()));

        GateResult result = qualityGate.auditAndReviseIfEnabled(
                command, item, chapterContent, List.of(), styleStat, 1, null);

        assertEquals(1, result.unresolvedBlocking().size());
        assertEquals("aesthetic", result.unresolvedBlocking().get(0).getDimension());
        assertTrue(result.unresolvedBlocking().get(0).getEvidence().contains("第一章"));
        verify(auditSampleService).record(any(), anyInt(), any(), anyString(), anyList(), anyInt(), any(), anyString());
    }

    @Test
    void mechanismIssue_triggersRevise_andClosesOnRescan() {
        // 机制门禁由"只记债不阻塞"改为真 BLOCKING：必须进修订闭环，
        // 且修订稿重算后清掉问题即闭环（两条检查都是纯函数，不会永远挂债）
        audit.setEnabled(false);
        chapterContent.setContent("他只是静静地站着，看着窗外的雪。");
        when(consistencyIndexService.mechanismIssues(any(), anyList(), anyInt(), anyString()))
                .thenReturn(List.of(mechanismIssue()), List.of());
        when(chapterReviseService.revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new ChapterReviseService.ReviseResult(
                        RevisionDecisionEntity.builder().accepted(true).reason("补上金手指运用").build(),
                        ChapterContentEntity.builder().chapterNo(1).title("修订标题")
                                .content("他催动剑意提取系统，把残剑里的剑意抽了出来。").build()));

        GateResult result = qualityGate.auditAndReviseIfEnabled(
                command, item, chapterContent, List.of(), styleStat, 1, null);

        assertTrue(result.unresolvedBlocking().isEmpty(), "修订后机制问题清掉即闭环");
        assertEquals(GateResult.PassGrade.REVISED_PASS, result.grade());
        verify(chapterReviseService).revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void mechanismIssue_reviseExhausted_recordedAsBlockingDebt() {
        // 修订两轮仍未清掉：按真 BLOCKING 记为质量债（severity 与实际行为一致，不再"伪阻塞"）
        audit.setEnabled(false);
        revise.setMaxAttempts(1);
        chapterContent.setContent("他只是静静地站着，看着窗外的雪。");
        when(consistencyIndexService.mechanismIssues(any(), anyList(), anyInt(), anyString()))
                .thenReturn(List.of(mechanismIssue()));
        when(chapterReviseService.revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new ChapterReviseService.ReviseResult(
                        RevisionDecisionEntity.builder().accepted(true).reason("试图修订").build(),
                        ChapterContentEntity.builder().chapterNo(1).title("修订标题")
                                .content("雪还在下，他一动不动。").build()));

        GateResult result = qualityGate.auditAndReviseIfEnabled(
                command, item, chapterContent, List.of(), styleStat, 1, null);

        assertEquals(1, result.unresolvedBlocking().size());
        assertEquals("mechanism", result.unresolvedBlocking().get(0).getDimension());
        assertEquals("BLOCKING", result.unresolvedBlocking().get(0).getSeverity());
        verify(auditSampleService).record(any(), anyInt(), any(), anyString(), anyList(), anyInt(), any(), anyString());
    }

    private ChapterIssueEntity mechanismIssue() {
        return ChapterIssueEntity.builder()
                .dimension("mechanism").severity("BLOCKING")
                .description("剑意提取系统超过 3 章未使用或有意义提及")
                .suggestion("本章在既有能力边界内自然使用")
                .build();
    }

    @Test
    void styleMinorAlone_doesNotRevise_butIsReportedAsMinor() {
        // 严重度分层：纯程度性文风问题（身体套话复读）已降为 MINOR——// 既不触发修订，也不计入 grade（不触发候选选优），但必须随结果返回供上层记债
        audit.setEnabled(false);
        chapterContent.setContent("他喉结滚动，没接话。她转身要走，他又喉结滚动了一下。");

        GateResult result = qualityGate.auditAndReviseIfEnabled(
                command, item, chapterContent, List.of(), styleStat, 1, null);

        assertTrue(result.unresolvedBlocking().isEmpty(), "程度问题不再进 BLOCKING 清单");
        assertEquals(GateResult.PassGrade.CLEAN_PASS, result.grade(),
                "文风 MINOR 不参与 grade 推导，不应变成低置信通过");
        assertEquals(1, result.mechanicalMinor().size(), "降档项须随结果返回供记债/观测");
        assertEquals("MINOR", result.mechanicalMinor().get(0).getSeverity());
        assertTrue(result.mechanicalMinor().get(0).getEvidence().contains("喉结滚动×2"));
        verify(chapterReviseService, never()).revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void styleClean_noLlmAuditIssues_returnsEmpty() {
        // 干净正文：机械门禁与 LLM 审校均零命中，不触发修订
        chapterContent.setContent("他推开门，看见桌上的信。信封没有落款，只有一枚烧焦的蜡印。");
        when(chapterAuditService.audit(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(AuditResultEntity.builder().build());

        assertTrue(qualityGate.auditAndReviseIfEnabled(command, item, chapterContent, List.of(), styleStat, 1, null)
                .unresolvedBlocking().isEmpty());
        verify(chapterReviseService, never()).revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void blocking_reviseThrows_keepsOriginal_returnsBlockingAndRecordsSample() {
        when(chapterAuditService.audit(any(), any(), any(), any(), any(), any(), anyInt(), any())).thenReturn(blockingResult());
        when(chapterReviseService.revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any()))
                .thenThrow(new RuntimeException("修订服务爆炸"));

        GateResult result =
                qualityGate.auditAndReviseIfEnabled(command, item, chapterContent, List.of(), styleStat, 1, null);

        assertEquals(1, result.unresolvedBlocking().size());
        assertEquals("原文", chapterContent.getContent());
        verify(auditSampleService).record(any(), anyInt(), any(), anyString(), anyList(), anyInt(), any(), anyString());
    }

    @Test
    void secrecyKeywordLeak_isBlocking_evenWhenAuditClean() {
        // 伏笔保密边界：已埋未揭伏笔的谜底关键词逐字命中即 BLOCKING（修订关闭时直接成质量债）
        chapterContent.setContent("他握紧怀中的旧玉佩，忽然想起古书上的话——那枚玉佩实为关键凭证。");
        when(chapterMemoryService.buildSecrecyGuard(anyList(), any())).thenReturn(new ChapterMemoryService.SecrecyGuard(
                List.of("那枚玉佩实为关键凭证"), "【本章禁泄清单】……"));
        audit.setEnabled(false);
        revise.setEnabled(false);

        GateResult result = qualityGate.auditAndReviseIfEnabled(
                command, item, chapterContent, List.of(), styleStat, 1, null);

        assertEquals(1, result.unresolvedBlocking().size());
        assertEquals("foreshadow", result.unresolvedBlocking().get(0).getDimension());
        assertTrue(result.unresolvedBlocking().get(0).getEvidence().contains("那枚玉佩实为关键凭证×1"));
        verify(chapterAuditService, never()).audit(any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void secrecyGuard_promptBlock_passedToAuditAndRevise() {
        // 禁泄 prompt 块要同时注入审校（判变相泄露）与修订（防修订引入泄露）
        when(chapterMemoryService.buildSecrecyGuard(anyList(), any())).thenReturn(new ChapterMemoryService.SecrecyGuard(
                List.of("事故背后另有主使"), "【本章禁泄清单】事故背后另有主使"));
        when(chapterAuditService.audit(any(), any(), any(), any(), any(), any(), anyInt(), anyString()))
                .thenReturn(AuditResultEntity.builder().build());
        audit.setEnabled(true);

        qualityGate.auditAndReviseIfEnabled(command, item, chapterContent, List.of(), styleStat, 1, null);

        verify(chapterAuditService).audit(any(), any(), any(), any(), any(), any(), anyInt(),
                contains("【本章禁泄清单】"));
    }

    @Test
    void registerContract_sharedWithWriter_andPreservesAuthorScopeInAuditAndRevision() {
        StoryContextEntity context = StoryContextEntity.builder().theme("玄幻")
                .style("细腻思辨，允许完整内心讲述").perspective("第三人称限知")
                .tone("沉静克制").build();
        command.setStoryContextEntity(context);
        when(chapterAuditService.audit(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(blockingResult());
        when(chapterReviseService.revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new ChapterReviseService.ReviseResult(
                        RevisionDecisionEntity.builder().accepted(false).reason("待核实事实").build(), null));
        revise.setMaxAttempts(1);

        qualityGate.auditAndReviseIfEnabled(command, item, chapterContent, List.of(), styleStat, 1, null);

        ArgumentCaptor<String> auditGuard = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> revisionContext = ArgumentCaptor.forClass(String.class);
        verify(chapterAuditService).audit(any(), any(), any(), any(), any(), any(), anyInt(), auditGuard.capture());
        verify(chapterReviseService).revise(any(), any(), any(), anyList(), any(), revisionContext.capture(), any(), anyInt(), any());
        for (String prompt : List.of(auditGuard.getValue(), revisionContext.getValue())) {
            assertTrue(prompt.contains("【语言与时代语域检查】"), "保留旧标题兼容");
            assertTrue(prompt.contains(WritingContractPolicy.renderRegister(context)));
            assertTrue(prompt.contains(WritingContractPolicy.renderEditorialScope()));
            assertFalse(prompt.contains("修订时必须改写为角色可观察的动作、物件、生活经验和口语"));
        }
    }

    @Test
    void lowPlanCoverage_injectsAdherenceHintIntoAuditGuard() {
        // 大纲偏离检测：覆盖率 < 60% 时给审校注入加审预警（不直接 BLOCKING）
        item.setKeyEvents(List.of("小陈在藏经阁偶得上古残卷", "陆瑶察觉宗门长老的异动"));
        chapterContent.setContent("他推开门，看见桌上的信。信封没有落款，只有一枚烧焦的蜡印。");
        when(chapterAuditService.audit(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(AuditResultEntity.builder().build());
        audit.setEnabled(true);

        qualityGate.auditAndReviseIfEnabled(command, item, chapterContent, List.of(), styleStat, 1, null);

        verify(chapterAuditService).audit(any(), any(), any(), any(), any(), any(), anyInt(),
                contains("【计划覆盖预警】"));
    }

    @Test
    void goodPlanCoverage_noAdherenceHint() {
        // 覆盖率达标（含 4 字以上公共片段视为覆盖）不注入预警
        item.setKeyEvents(List.of("小陈在藏经阁偶得上古残卷"));
        chapterContent.setContent("小陈在藏经阁偶得上古残卷，指尖抚过封皮上斑驳的纹路。");
        when(chapterAuditService.audit(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(AuditResultEntity.builder().build());
        audit.setEnabled(true);

        qualityGate.auditAndReviseIfEnabled(command, item, chapterContent, List.of(), styleStat, 1, null);

        verify(chapterAuditService).audit(any(), any(), any(), any(), any(), any(), anyInt(),
                org.mockito.AdditionalMatchers.not(contains("【计划覆盖预警】")));
    }

    @Test
    void noKeyEvents_noAdherenceHint() {
        when(chapterAuditService.audit(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(AuditResultEntity.builder().build());
        audit.setEnabled(true);

        qualityGate.auditAndReviseIfEnabled(command, item, chapterContent, List.of(), styleStat, 1, null);

        // guard 可能合法携带其它预警（如密度预警），只断言不含计划覆盖预警
        verify(chapterAuditService).audit(any(), any(), any(), any(), any(), any(), anyInt(),
                org.mockito.AdditionalMatchers.not(contains("【计划覆盖预警】")));
    }

    @Test
    void chapterSummaryEntityNullContent_safeReturn() {
        // 正文为 null 的防御路径：机械门禁全跳过，审校调用携带 null 内容
        ChapterContentEntity empty = ChapterContentEntity.builder().chapterNo(1).title("t").content(null).build();
        audit.setEnabled(false);

        GateResult result = qualityGate.auditAndReviseIfEnabled(command, item, empty, List.of(), styleStat, 1, null);

        assertTrue(result.unresolvedBlocking().isEmpty());
    }

    /** 无声明的低对白只是观测，不据此新增对白任务或判断不成戏。 */
    @Test
    void dialogueDensityCollapse_isObservationWithoutQuotaOrDramaticVerdict() {
        audit.setEnabled(false);
        // 有效字只数中文/字母/数字（不含标点），故这里堆够 800 才能进入坍缩判定
        StringBuilder longText = new StringBuilder("他把信纸摊开，折好，塞进信封，指腹把折痕压平。");
        for (int i = 0; i < 50; i++) {
            // 句中加入序号：只为凑足密度判定所需篇幅，不得整句重复（否则会撞章内重复检测）
            longText.append("桌面上只剩算术本和一张报名表复印件，红章露出半截，页角写着第").append(i).append("行批注。");
        }
        longText.append("「明天寄。」");
        chapterContent.setContent(longText.toString());

        GateResult result = qualityGate.auditAndReviseIfEnabled(
                command, item, chapterContent, List.of(), styleStat, 96, null);

        assertTrue(result.unresolvedBlocking().isEmpty());
        ChapterIssueEntity observation = result.mechanicalMinor().stream()
                .filter(i -> i.getDescription() != null && i.getDescription().contains("对白轮次观测"))
                .findFirst().orElseThrow();
        assertEquals("MINOR", observation.getSeverity());
        assertTrue(observation.getSuggestion().contains("不要求补量"));
        assertFalse(observation.getSuggestion().contains("≥2"));
        assertFalse(observation.getSuggestion().contains("交锋"));
        assertFalse(observation.getDescription().contains("不是戏"));
        verify(chapterReviseService, never()).revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any());
    }

    /** 反向：对白密度正常时不得误报（宁可漏报不误伤，与 DialogueRatioPolicy 其它检测同款） */
    @Test
    void healthyDialogueDensity_isNotRecordedAsDebt() {
        audit.setEnabled(false);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            text.append("「你明天去邮局吗。」「去。」「那我也去。」「行。」");
        }
        chapterContent.setContent(text.toString());

        GateResult result = qualityGate.auditAndReviseIfEnabled(
                command, item, chapterContent, List.of(), styleStat, 96, null);

        assertTrue(result.mechanicalMinor().stream()
                        .noneMatch(i -> i.getDescription() != null
                                && i.getDescription().contains("对白轮次观测")),
                "对白密度正常时不应记债：" + result.mechanicalMinor());
    }

    /** 词面缺失只能提示语义核验，不是场景遗漏的事实裁决。 */
    @Test
    void declaredDialogueScenes_lexicalMissing_doesNotBecomeBlocking() {
        audit.setEnabled(false);
        revise.setEnabled(false);
        item.setDialogueScenes(List.of(
                "角色甲：「既然记着，这个也记上。」",
                "角色乙：「嗯。」"));
        StringBuilder longText = new StringBuilder("他把信纸摊开，折好，塞进信封，指腹把折痕压平。");
        for (int i = 0; i < 50; i++) {
            // 句中加入序号：只为凑足密度判定所需篇幅，不得整句重复（否则会撞章内重复检测）
            longText.append("桌面上只剩算术本和一张报名表复印件，红章露出半截，页角写着第").append(i).append("行批注。");
        }
        longText.append("「既然记着，这个也记上。」");
        chapterContent.setContent(longText.toString());

        GateResult result = qualityGate.auditAndReviseIfEnabled(
                command, item, chapterContent, List.of(), styleStat, 110, null);

        assertTrue(result.unresolvedBlocking().isEmpty(), "低密度加词面未命中也不能证明场景缺失");
        ChapterIssueEntity observation = result.mechanicalMinor().stream()
                .filter(i -> i.getDescription() != null && i.getDescription().contains("对白轮次观测"))
                .findFirst().orElseThrow();
        assertTrue(observation.getEvidence().contains("词面未匹配 1 条"));
        assertTrue(observation.getSuggestion().contains("语义核验"));
        assertFalse(observation.getSuggestion().contains("必须补回"));
        verify(chapterReviseService, never()).revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any());
    }

    /** 已兑现的短对白不能只因轮次少而被强制扩写。 */
    @Test
    void declaredDialogueScenes_allDelivered_lowDensity_doesNotRequireExtending() {
        audit.setEnabled(false);
        revise.setEnabled(false);
        item.setDialogueScenes(List.of(
                "角色甲：「既然记着，这个也记上。」",
                "角色乙：「嗯。」"));
        StringBuilder longText = new StringBuilder("他把信纸摊开，折好，塞进信封，指腹把折痕压平。");
        for (int i = 0; i < 50; i++) {
            // 句中加入序号：只为凑足密度判定所需篇幅，不得整句重复（否则会撞章内重复检测）
            longText.append("桌面上只剩算术本和一张报名表复印件，红章露出半截，页角写着第").append(i).append("行批注。");
        }
        longText.append("「既然记着，这个也记上。」").append("「嗯。」");
        chapterContent.setContent(longText.toString());

        GateResult result = qualityGate.auditAndReviseIfEnabled(
                command, item, chapterContent, List.of(), styleStat, 110, null);

        assertTrue(result.unresolvedBlocking().isEmpty());
        assertTrue(result.mechanicalMinor().stream().noneMatch(i -> i.getSuggestion() != null
                && (i.getSuggestion().contains("扩写成有来有回") || i.getSuggestion().contains("必须补回"))));
        verify(chapterReviseService, never()).revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void dialogueLexicalMismatch_canBeEquivalentWithoutBlocking() {
        audit.setEnabled(true);
        item.setDialogueScenes(List.of("甲：“今日在此等候，勿去别处。”"));
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            content.append("天边的暮色沿墙缓慢落下，屋里的人想起旧日来信，重新读到第")
                    .append(i).append("行便停住。\n");
        }
        content.append("甲说：“这一天都别离开这里。”");
        chapterContent.setContent(content.toString());
        assertEquals(1, DialoguePresencePolicy.missingScenes(item.getDialogueScenes(), content.toString()).size(),
                "等价改写刻意不命中词面规则");
        when(chapterAuditService.audit(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(AuditResultEntity.builder().issues(List.of()).build());

        GateResult result = qualityGate.auditAndReviseIfEnabled(command, item, chapterContent, List.of(), styleStat, 1, null);
        assertTrue(result.unresolvedBlocking().isEmpty());
        verify(chapterAuditService).audit(any(), any(), any(), any(), any(), any(), anyInt(), any());
        verify(chapterReviseService, never()).revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any());
    }

    /** 声明了对话场景、密度也健康（写手等价改写/加戏）→ 不得误报：密度不作事件裁决 */
    @Test
    void declaredDialogueScenes_healthyDensity_noDebt() {
        audit.setEnabled(false);
        item.setDialogueScenes(List.of("角色己：「这题我一个人做不完，你来补后半段。」"));
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            text.append("「你明天去邮局吗。」「去。」「那我也去。」「行。」");
        }
        chapterContent.setContent(text.toString());

        GateResult result = qualityGate.auditAndReviseIfEnabled(
                command, item, chapterContent, List.of(), styleStat, 108, null);

        assertTrue(result.unresolvedBlocking().isEmpty(),
                "密度健康时台词改写不应升级：" + result.unresolvedBlocking());
        assertTrue(result.mechanicalMinor().stream()
                        .noneMatch(i -> i.getDescription() != null
                                && i.getDescription().contains("对白轮次观测")));
    }

    /** 过渡章豁免对声明场景同样生效：过渡章本就该短，不因声明了对话而升级 */
    @Test
    void transitionChapter_withDeclaredScenes_stillExempt() {
        audit.setEnabled(false);
        item.setChapterType(cn.novel.yonren.types.enums.ChapterType.TRANSITION);
        item.setDialogueScenes(List.of("角色乙：「嗯。」"));
        StringBuilder longText = new StringBuilder("他把信纸摊开，折好，塞进信封，指腹把折痕压平。");
        for (int i = 0; i < 50; i++) {
            // 句中加入序号：只为凑足密度判定所需篇幅，不得整句重复（否则会撞章内重复检测）
            longText.append("桌面上只剩算术本和一张报名表复印件，红章露出半截，页角写着第").append(i).append("行批注。");
        }
        chapterContent.setContent(longText.toString());

        GateResult result = qualityGate.auditAndReviseIfEnabled(
                command, item, chapterContent, List.of(), styleStat, 109, null);

        assertTrue(result.unresolvedBlocking().isEmpty());
        assertTrue(result.mechanicalMinor().stream()
                        .noneMatch(i -> i.getDescription() != null
                                && i.getDescription().contains("对白轮次观测")),
                "过渡章豁免优先于声明升级：" + result.mechanicalMinor());
    }

    /** 真正缺失由已有语义审校确认；修订兑现后，低轮次观测不能阻止闭环。 */
    @Test
    void semanticallyMissingDialogue_repairClosesLoopDespiteLowDensity() {
        audit.setEnabled(true);
        revise.setEnabled(true);
        revise.setMaxAttempts(1);
        item.setDialogueScenes(List.of("角色乙：「嗯。」"));
        StringBuilder original = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            original.append("桌面上只剩习题册和一张报名表复印件，红章露出半截，页角写着第")
                    .append(i).append("行批注。\n");
        }
        original.append("「既然记着，这个也记上。」");
        chapterContent.setContent(original.toString());
        String repaired = original + "\n「嗯。」";
        when(chapterAuditService.audit(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(AuditResultEntity.builder().issues(List.of(ChapterIssueEntity.builder()
                        .dimension("plan-adherence").severity("BLOCKING")
                        .description("场景要求乙明确接受任务，但正文没有其回应或接受结果")
                        .evidence("甲提出任务后直接离开，乙未回应").build())).build());
        when(chapterAuditService.verifyFixes(any(), any(), any(), anyList(), any(), any(), anyInt()))
                .thenReturn(new ChapterAuditService.FixVerification(List.of(), false));
        when(chapterReviseService.revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new ChapterReviseService.ReviseResult(
                        RevisionDecisionEntity.builder().accepted(true).reason("补回任务接受结果").build(),
                        ChapterContentEntity.builder().chapterNo(1).content(repaired).build()));

        GateResult result = qualityGate.auditAndReviseIfEnabled(
                command, item, chapterContent, List.of(), styleStat, 110, null);

        assertTrue(result.unresolvedBlocking().isEmpty());
        assertEquals(repaired, chapterContent.getContent());
        assertEquals("原标题", chapterContent.getTitle());
        verify(chapterAuditService).verifyFixes(any(), any(), any(), anyList(), any(), any(), anyInt());
        verify(chapterReviseService).revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any());
        verify(auditSampleService, never()).record(any(), anyInt(), any(), anyString(), anyList(), anyInt(), any(), anyString());
    }


    // ==================== 移植自旧系统的闸门用例 ====================

    /** 坍缩底线的边界：800 字正好达标（不小于），799 低于底线必判（修订关闭时记债） */
    @Test
    void collapseFloor_boundaryIsInclusive() {
        audit.setEnabled(false);
        audit.setEnforceMinimumChapterLength(true);
        // 验证坍缩底线本身，须显式打开（setUp 默认关闭以驱动其它分支）
        audit.setEnforceCollapseFloor(true);
        revise.setEnabled(false);

        chapterContent.setContent("字".repeat(800));
        assertTrue(qualityGate.auditAndReviseIfEnabled(command, item, chapterContent, List.of(), styleStat, 1, null)
                .unresolvedBlocking().isEmpty(), "800 字正好达标，不得判坍缩");

        chapterContent.setContent("字".repeat(799));
        assertFalse(qualityGate.auditAndReviseIfEnabled(command, item, chapterContent, List.of(), styleStat, 1, null)
                .unresolvedBlocking().isEmpty(), "799 字低于底线，必须判坍缩");
    }

    /**
     * 篇幅坍缩硬底线：有效字低于 800 时记 BLOCKING 并进入修订，不能只看旧问题已修复。
     */
    @Test
    void collapsedChapter_isBlocking_andDrivesRevision() {
        audit.setEnforceMinimumChapterLength(true);
        // 坍缩底线是独立开关：本用例专门验证它，须显式打开（setUp 默认关闭以驱动其它分支）
        audit.setEnforceCollapseFloor(true);
        revise.setEnabled(true);
        chapterContent.setContent("字".repeat(674));
        when(chapterAuditService.audit(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(AuditResultEntity.builder().issues(List.of()).build());
        when(chapterReviseService.revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new ChapterReviseService.ReviseResult(
                        RevisionDecisionEntity.builder().accepted(false).reason("修订未通过采纳闸门").build(),
                        null));

        GateResult result =
                qualityGate.auditAndReviseIfEnabled(command, item, chapterContent, List.of(), styleStat, 136, null);

        assertTrue(result.unresolvedBlocking().stream()
                        .anyMatch(i -> i.getDescription() != null && i.getDescription().contains("篇幅坍缩")),
                "674 字必须记篇幅坍缩 BLOCKING：" + result.unresolvedBlocking());
        verify(chapterReviseService, atLeastOnce()).revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any());
    }

    /**
     * 修订后仍坍缩的稿件不能因旧 BLOCKING 已修复而闭环放行。
     */
    @Test
    void collapsedRevision_isNotAcceptedAsClosed() {
        audit.setEnforceMinimumChapterLength(true);
        // 复测的是“坍缩硬底线”，必须按它自己的开关（与 1500 参考线告警是两个独立开关）
        audit.setEnforceCollapseFloor(true);
        revise.setMaxAttempts(1);
        revise.setEnabled(true);
        when(chapterAuditService.audit(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(blockingResult());
        when(chapterAuditService.verifyFixes(any(), any(), any(), anyList(), any(), any(), anyInt()))
                .thenReturn(new ChapterAuditService.FixVerification(List.of(), false));
        when(chapterReviseService.revise(any(), any(), any(), anyList(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new ChapterReviseService.ReviseResult(
                        RevisionDecisionEntity.builder().accepted(true).reason("修订稿通过采纳闸门").build(),
                        ChapterContentEntity.builder().chapterNo(1).title("修订标题")
                                .content("字".repeat(674)).build()));

        GateResult result =
                qualityGate.auditAndReviseIfEnabled(command, item, chapterContent, List.of(), styleStat, 136, null);

        assertFalse(result.unresolvedBlocking().isEmpty(),
                "修订稿 674 字仍坍缩，不得当作闭环放行");
        assertTrue(result.unresolvedBlocking().stream()
                        .anyMatch(i -> i.getDescription() != null && i.getDescription().contains("篇幅坍缩")),
                "必须带篇幅坍缩 BLOCKING：" + result.unresolvedBlocking());
    }
}
