package cn.novel.yonren.domain.novel.service.armory.audit;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.entity.AuditResultEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 章节审校服务测试：JSON 解析/修复/质量门硬失败，以及 BLOCKING 证据校验——* 逐字/空白差异/省略号截断命中不降级，改写与空证据降级，MINOR 不受影响
 */
class ChapterAuditServiceTest {

    private LlmGateway llmGateway;
    private ChapterAuditService service;

    @BeforeEach
    void setUp() {
        llmGateway = mock(LlmGateway.class);
        service = new ChapterAuditService(llmGateway);
    }

    // ---- A2 判据目录守卫：变体 B 必须带编号目录与三态样例，A 分支保持无目录 ----

    @Test
    void prompt_variantB_containsCatalogIdsAndTriStateExamples() {
        System.setProperty(AuditPromptVariant.SYSTEM_PROPERTY, "B");
        try {
            String prompt = service.buildPrompt(
                    ChapterPlanItemEntity.builder().chapterNo(1).title("标题").goal("目标")
                            .keyEvents(List.of("事件")).build(),
                    "正文内容", null, null, null, 1, null);

            assertTrue(prompt.contains("判据目录"), "变体 B 必须包含编号判据目录");
            for (String id : List.of("C1", "H2", "P1", "N2", "F2", "K1", "A3", "A4")) {
                assertTrue(prompt.contains(id), "目录编号缺失：" + id);
            }
            assertTrue(prompt.contains("边界="), "三态锚样例缺失——颗粒度对齐靠样例不靠先验");
            assertTrue(prompt.contains("判据编号"), "MINOR 标注判据编号的输出规则缺失");
            assertTrue(prompt.contains("仅限以下四类硬伤"), "severity 白名单不得被目录改动");
        } finally {
            System.clearProperty(AuditPromptVariant.SYSTEM_PROPERTY);
        }
    }

    @Test
    void prompt_variantA_hasNoCatalog() {
        String prompt = service.buildPrompt(
                ChapterPlanItemEntity.builder().chapterNo(1).title("标题").goal("目标")
                        .keyEvents(List.of("事件")).build(),
                "正文内容", null, null, null, 1, null);

        assertFalse(prompt.contains("判据目录"), "A 分支（生产默认）不得注入目录");
        assertTrue(prompt.contains("宁可漏报不可错报"), "severity 标准必须保留");
    }

    @Test
    void prompt_injectsAbilityBoundaryCriterionOnlyWhenTimeAnchorPresent() {
        // 机械层测不了"阶段越界的能力展示"。时序锚由 guard 带入，
        // 有时序锚才要求审校查阶段越界；无锚时不输出，避免让模型去猜一个不存在的锚。
        ChapterPlanItemEntity item = ChapterPlanItemEntity.builder().chapterNo(26).title("标题").goal("目标")
                .keyEvents(List.of("事件")).build();

        String with = service.buildPrompt(item, "正文", null, null, null, 26,
                "【时序锚】角色丙年龄：十一个月（第16章摘要记录）。");
        assertTrue(with.contains("能力与阶段边界"), "有时序锚时必须要求审校查能力边界");
        assertTrue(with.contains("本书明确允许的认知超前、特殊能力与表达媒介必须保留"),
                "已建立的认知超前和特殊能力不能被年龄常识抹平");
        assertTrue(with.contains("本书未建立的新能力或新媒介不能临场发明"));
        assertTrue(with.contains("不规定必须观察几次"), "确认按证据，不设固定观察次数");
        assertFalse(with.contains("可被他人在事后解读出具体含义"), "不得按载体形式一刀切禁展示");
        assertFalse(with.contains("多次重复"), "不得规定固定确认套路");

        String without = service.buildPrompt(item, "正文", null, null, null, 26, null);
        assertFalse(without.contains("能力与阶段边界"), "无锚时不输出该判据");
        assertTrue(without.contains("年龄/能力越界归入第 1 类"), "BLOCKING 归类说明常驻（条件满足时才适用）");
    }

    @Test
    void revisionVerificationChecksNewFactConflictsAgainstOriginalContextInTheSameCall() {
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn("{\"verdicts\":[{\"index\":1,\"fixed\":true}],"
                + "\"continuityChecked\":true,\"introducedConflicts\":[{\"description\":\"已交出的药签无依据重现\","
                + "\"evidence\":\"他从袖里取出已交出的药签\"}]}");
        var context = new cn.novel.yonren.domain.novel.model.valobj.revise.RewriteContext("药签已经交给执事", null,
                new cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript(1, "标题", "他把药签交给执事。"));
        var target = ChapterIssueEntity.builder().dimension("continuity").severity("BLOCKING").description("事件未交付").build();
        var result = service.verifyFixes(storyVO(), planItem(), "他从袖里取出已交出的药签。", List.of(target), null, null, 1, context);
        assertFalse(result.degraded());
        assertEquals(1, result.unfixed().size());
        assertEquals("BLOCKING", result.unfixed().get(0).getSeverity());
        assertTrue(result.unfixed().get(0).getDescription().contains("药签"));
        org.mockito.ArgumentCaptor<LlmCall> call = org.mockito.ArgumentCaptor.forClass(LlmCall.class);
        org.mockito.Mockito.verify(llmGateway, org.mockito.Mockito.times(1)).complete(any(), call.capture());
        assertTrue(call.getValue().getUserPrompt().contains("修订影响范围连续性核对"));
        assertTrue(call.getValue().getUserPrompt().contains("药签已经交给执事"));
    }

    @Test
    void missingContinuityCoverageDoesNotMasqueradeAsRevisionPass() {
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn("{\"verdicts\":[{\"index\":1,\"fixed\":true}]}");
        var context = new cn.novel.yonren.domain.novel.model.valobj.revise.RewriteContext("冻结事实", null,
                new cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript(1, "标题", "原稿"));
        var target = ChapterIssueEntity.builder().severity("BLOCKING").description("缺少关键事件").build();
        var result = service.verifyFixes(storyVO(), planItem(), "修订稿", List.of(target), null, null, 1, context);
        assertTrue(result.degraded());
        assertEquals(List.of(target), result.unfixed());
    }

    @Test
    void audit_parsesValidJson() {
        String json = "{\"issues\":[{\"dimension\":\"consistency\",\"severity\":\"BLOCKING\"," +
                "\"evidence\":\"小陈已死，后文又出现\",\"description\":\"角色死亡后再次出场\"," +
                "\"suggestion\":\"删除该出场或改为回忆\"}]}";
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(json);

        AuditResultEntity result = service.audit(storyVO(), planItem(),
                "前文交代小陈已死，后文又出现小陈的身影。", null, null, null, 1);

        assertNotNull(result);
        assertEquals(1, result.getIssues().size());
        ChapterIssueEntity issue = result.getIssues().get(0);
        assertEquals("consistency", issue.getDimension());
        // 证据能在正文中命中，保持 BLOCKING
        assertEquals("BLOCKING", issue.getSeverity());
        assertEquals("小陈已死，后文又出现", issue.getEvidence());
    }

    @Test
    void audit_downgradesBlockingWithFabricatedEvidence() {
        String json = "{\"issues\":[{\"dimension\":\"continuity\",\"severity\":\"BLOCKING\"," +
                "\"evidence\":\"这段引文正文里根本不存在\",\"description\":\"程度问题被硬标\",\"suggestion\":\"降级\"}]}";
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(json);

        AuditResultEntity result = service.audit(storyVO(), planItem(), "正文", null, null, null, 1);

        assertNotNull(result);
        assertEquals("MINOR", result.getIssues().get(0).getSeverity());
    }

    @Test
    void audit_repairsDanglingComma() {
        String bad = "{\"issues\":[{\"dimension\":\"aesthetic\",\"severity\":\"MINOR\",\"evidence\":\"一段原文\",\"description\":\"AI味\",\"suggestion\":\"改\"},]}";
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(bad);

        AuditResultEntity result = service.audit(storyVO(), planItem(), "正文", null, null, null, 1);

        assertNotNull(result);
        assertEquals(1, result.getIssues().size());
        assertEquals("MINOR", result.getIssues().get(0).getSeverity());
    }

    @Test
    void audit_throwsWhenUnparseable() {
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn("不是 JSON");

        AppException e = assertThrows(AppException.class,
                () -> service.audit(storyVO(), planItem(), "正文", null, null, null, 1));

        assertTrue(e.getInfo().contains("审校彻底失败"));
    }

    @Test
    void audit_ignoresEmptyIssues() {
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn("{\"issues\":[]}");

        AuditResultEntity result = service.audit(storyVO(), planItem(), "正文", null, null, null, 1);

        assertNotNull(result);
        assertTrue(result.getIssues().isEmpty());
    }

    @Test
    void verify_keepsVerbatimBlockingEvidence() {
        ChapterIssueEntity issue = blocking("“魔力的空的。”老执事收回了手");

        service.verifyEvidence(result(issue), CONTENT, 1);

        assertEquals("BLOCKING", issue.getSeverity());
    }

    @Test
    void verify_toleratesWhitespaceAndQuoteDiffs() {
        // 引用换行折叠、引号形态差异、首尾引号——归一化后应命中
        ChapterIssueEntity issue = blocking("\"魔力的空的。\"老执事收回了手");

        service.verifyEvidence(result(issue), CONTENT, 1);

        assertEquals("BLOCKING", issue.getSeverity());
    }

    @Test
    void verify_acceptsEllipsisTruncatedEvidence() {
        ChapterIssueEntity issue = blocking("洛恩抬起头……警戒符在高塔上转黑");

        service.verifyEvidence(result(issue), CONTENT, 1);

        assertEquals("BLOCKING", issue.getSeverity());
    }

    @Test
    void verify_downgradesParaphrasedEvidence() {
        ChapterIssueEntity issue = blocking("老执事忽然宣布洛恩的魔力耗尽");

        service.verifyEvidence(result(issue), CONTENT, 1);

        assertEquals("MINOR", issue.getSeverity());
    }

    @Test
    void verify_downgradesPartiallyFabricatedEllipsisEvidence() {
        // 省略号分段中有一段是模型改写 → 不命中，降级
        ChapterIssueEntity issue = blocking("洛恩抬起头……执事们齐声宣布审判开始");

        service.verifyEvidence(result(issue), CONTENT, 1);

        assertEquals("MINOR", issue.getSeverity());
    }

    @Test
    void verify_downgradesBlankEvidence() {
        ChapterIssueEntity blank = blocking(null);
        ChapterIssueEntity whitespace = blocking("   ");

        service.verifyEvidence(result(blank, whitespace), CONTENT, 1);

        assertEquals("MINOR", blank.getSeverity());
        assertEquals("MINOR", whitespace.getSeverity());
    }

    @Test
    void verify_leavesMinorUntouched() {
        ChapterIssueEntity minor = ChapterIssueEntity.builder()
                .dimension("hook")
                .severity("MINOR")
                .evidence("完全不存在于正文的句子")
                .build();

        service.verifyEvidence(result(minor), CONTENT, 1);

        assertEquals("MINOR", minor.getSeverity());
    }

    @Test
    void verify_toleratesNullOrBlankContent() {
        ChapterIssueEntity issue = blocking("老执事忽然宣布洛恩的魔力耗尽");

        service.verifyEvidence(result(issue), null, 1);
        service.verifyEvidence(result(issue), "   ", 1);

        // 无正文可比对时不做校验，保持原判定
        assertEquals("BLOCKING", issue.getSeverity());
    }

    private static final String CONTENT = "洛恩抬起头，\n“魔力的空的。”老执事收回了手。\n  他退回阴影里，警戒符在高塔上转黑。";

    private ChapterIssueEntity blocking(String evidence) {
        return ChapterIssueEntity.builder()
                .dimension("continuity")
                .severity("BLOCKING")
                .evidence(evidence)
                .build();
    }

    private AuditResultEntity result(ChapterIssueEntity... issues) {
        return AuditResultEntity.builder()
                .issues(new ArrayList<>(List.of(issues)))
                .build();
    }

    private StoryVO storyVO() {
        return new StoryVO();
    }

    private ChapterPlanItemEntity planItem() {
        ChapterPlanItemEntity item = new ChapterPlanItemEntity();
        item.setChapterNo(1);
        item.setTitle("测试章");
        item.setGoal("测试目标");
        item.setKeyEvents(List.of("事件A", "事件B"));
        item.setEndingHook("测试钩子");
        return item;
    }

}
