package cn.novel.yonren.domain.novel.service.armory.audit;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.entity.AuditResultEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.plan.ScenePlan;
import cn.novel.yonren.domain.novel.model.valobj.quality.DeliveryReview;
import cn.novel.yonren.domain.novel.model.valobj.revise.RewriteContext;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import cn.novel.yonren.domain.novel.service.armory.llm.LlmLocalRejectionException;
import cn.novel.yonren.domain.novel.service.armory.quality.DeliveryReviewPolicy;
import cn.novel.yonren.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
                + "\"evidence\":\"他从袖里取出已交出的药签\",\"factEvidence\":\"他把药签交给执事\"}]}");
        var context = new cn.novel.yonren.domain.novel.model.valobj.revise.RewriteContext("药签已经交给执事", null,
                new cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript(1, "标题", "他把药签交给执事。"));
        var target = ChapterIssueEntity.builder().dimension("continuity").severity("BLOCKING").description("事件未交付").build();
        var result = service.verifyFixes(storyVO(), planItem(), "他从袖里取出已交出的药签。", List.of(target), null, null, 1, context);
        assertFalse(result.degraded());
        assertTrue(result.introducedFactConflict());
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
                "\"evidence\":\"小陈推门而入\",\"factEvidence\":\"小陈已经死了\",\"description\":\"角色死亡后再次出场\"," +
                "\"suggestion\":\"删除该出场或改为回忆\"}]}";
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(json);

        AuditResultEntity result = service.audit(storyVO(), planItem(),
                "小陈推门而入。", "小陈已经死了，遗体尚在停尸房。", null, null, 1);

        assertNotNull(result);
        assertEquals(1, result.getIssues().size());
        ChapterIssueEntity issue = result.getIssues().get(0);
        assertEquals("consistency", issue.getDimension());
        assertEquals("BLOCKING", issue.getSeverity());
        assertEquals("小陈推门而入", issue.getEvidence());
        assertEquals("小陈已经死了", issue.getFactEvidence());
    }

    @Test
    void auditRequiresTheCurrentChapterLimitAndBodyQuoteForEarlyReveal() {
        String limit = "林长渊怀疑两处煞气同源，但来不及确认。";
        var item = planItem();
        item.setScenes(List.of(new ScenePlan("mines", List.of("林长渊"), "调查矿料", "不得停留",
                List.of(), limit, "发现疑点", List.of(), null, null)));
        String body = "两处煞气是一个根上长出来的。";
        String response = """
                {"issues":[{"dimension":"foreshadow","severity":"BLOCKING",
                "evidence":"两处煞气是一个根上长出来的。",
                "planEvidence":"林长渊怀疑两处煞气同源，但来不及确认。","description":"提前坐实同源"}]}
                """;
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(response,
                response.replace(limit, "下一章林长渊会拿到证据。"), response);

        var supported = service.audit(storyVO(), item, body, null, null, null, 1);
        var inventedLimit = service.audit(storyVO(), item, body, null, null, null, 1);
        var bodyWithSuspicion = service.audit(storyVO(), item, "这股冷像老矿里吹来的风。他想再查一查。",
                null, null, null, 1);

        assertEquals("BLOCKING", supported.getIssues().get(0).getSeverity());
        assertEquals("MINOR", inventedLimit.getIssues().get(0).getSeverity());
        assertEquals("MINOR", bodyWithSuspicion.getIssues().get(0).getSeverity());
        assertTrue(capturedCalls(3).get(0).getUserPrompt().contains("planEvidence 逐字引用"));
    }

    @Test
    void revisionVerificationRejectsNewEarlyRevealEvenWhenOldIssueIsFixed() {
        var item = planItem();
        item.setSuspenseBeat("林长渊只怀疑两处煞气同源。");
        var original = new ChapterManuscript(1, "标题", "这股冷像是从旧矿吹来的。");
        var context = new RewriteContext(null, null, original);
        var target = ChapterIssueEntity.builder().severity("BLOCKING").description("漏写矿务抽查").build();
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn("""
                {"verdicts":[{"index":1,"fixed":true}],"continuityChecked":true,"introducedConflicts":[
                {"evidence":"两处煞气果然同源。","planEvidence":"林长渊只怀疑两处煞气同源。",
                "description":"把怀疑写成定论"}]}
                """);

        var result = service.verifyFixes(storyVO(), item, "两处煞气果然同源。", List.of(target), null, null, 1, context);

        assertFalse(result.degraded());
        assertTrue(result.introducedFactConflict());
        assertEquals("foreshadow", result.unfixed().get(0).getDimension());
    }

    @Test
    void auditRequiresBothContinuousQuotesForKnownRuleConflict() {
        String rule = "每次停止后须冷却十五分钟。";
        String body = "只过去五分钟，他再次启动了能力。";
        String issue = """
                {"issues":[{"dimension":"consistency","severity":"BLOCKING","evidence":"只过去五分钟，他再次启动了能力。","factEvidence":"每次停止后须冷却十五分钟。","description":"冷却结束前再次使用"}]}
                """;
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(issue,
                issue.replace(rule, "接触物品会自动启动。"),
                issue.replace("只过去五分钟，他再次启动了能力。", "只过去五分钟，……再次启动了能力。"));

        var supported = service.audit(storyVO(), planItem(), body, null, null, null, 1, null, null, rule);
        var onlyAdvice = service.audit(storyVO(), planItem(), body, null, null, null, 1,
                "创作参考：接触物品会自动启动。", null, rule);
        var stitched = service.audit(storyVO(), planItem(), body, null, null, null, 1, null, null, rule);

        assertEquals("BLOCKING", supported.getIssues().get(0).getSeverity());
        assertTrue(capturedCalls(3).get(0).getUserPrompt().contains(rule));
        for (var assessment : List.of(onlyAdvice, stitched)) {
            assertEquals("MINOR", assessment.getIssues().get(0).getSeverity());
            assertTrue(assessment.getIssues().get(0).getDescription().startsWith(ChapterIssueEntity.PENDING_FACT_PREFIX));
        }
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

    // ---- 同次请求的固定计划交付核验：网关均为 mock，不调用真实模型 ----

    @Test
    void deliveryAuditUsesOneCallAndBindsLocalIdentityInsteadOfModelReport() {
        var item = deliveryPlan();
        var request = deliveryRequest(item, DELIVERED);
        String raw = "{\"issues\":[],\"deliveryChecks\":[" + passCheck() + "],"
                + "\"deliveryReview\":{\"status\":\"ISSUE\",\"manuscriptHash\":\"forged\"},"
                + "\"auditUnavailable\":true}";
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(raw);

        var result = auditDelivery(item, DELIVERED, request);

        assertTrue(result.getDeliveryReview().passed());
        assertFalse(result.isAuditUnavailable());
        assertTrue(result.getIssues().isEmpty());
        assertEquals(request.manuscript().hash(), result.getDeliveryReview().manuscriptHash());
        assertEquals(request.contract().planHash(), result.getDeliveryReview().planHash());
        assertEquals(DeliveryReview.Scope.EVENTS, result.getDeliveryReview().scope());
        assertEquals(request.manuscript().hash(), result.getDeliveryReview().checks().get(0).manuscriptHash());
        var call = capturedCalls(1).get(0);
        assertTrue(call.getUserPrompt().contains("计划交付逐项验收"));
        assertTrue(call.getUserPrompt().contains("event:1"));
        assertTrue(call.getUserPrompt().contains("issues 与 deliveryChecks 必须分别显式提供数组"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void deliveryIssueWithEmptyIssuesIsARealConclusionNotEvidenceDowngraded(boolean enforced) {
        var item = deliveryPlan();
        var request = new DeliveryReviewPolicy.Request(DeliveryReviewPolicy.contract(item),
                new ChapterManuscript(1, item.getTitle(), "他在窗下静坐。"), enforced);
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(auditJson(missingDeliveryCheck()));

        var result = auditDelivery(item, request.manuscript().content(), request);

        assertEquals(DeliveryReview.Status.ISSUE, result.getDeliveryReview().status());
        assertEquals(enforced, result.getDeliveryReview().blocksCommit());
        assertTrue(result.getIssues().isEmpty(), "无正文引文的计划缺席不能塞入旧七维并降为 MINOR");
        assertFalse(result.isAuditUnavailable());
        capturedCalls(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"omitted", "empty", "duplicate-target", "unknown-target", "null-check",
            "fabricated-content", "fabricated-plan", "missing-reason", "model-unknown", "unknown-enum", "numeric-enum"})
    void incompleteOrInvalidDeliveryChecksStayUnknownAfterAtMostTwoCalls(String failure) {
        String check = switch (failure) {
            case "empty" -> "";
            case "duplicate-target" -> passCheck() + "," + passCheck();
            case "unknown-target" -> passCheck().replace("event:1", "event:99");
            case "null-check" -> "null";
            case "fabricated-content" -> passCheck().replace(DELIVERED_QUOTE, "他已经把银票交给陌生人");
            case "fabricated-plan" -> passCheck().replace("交付药签", "焚毁银票");
            case "missing-reason" -> passCheck().replace("接收动作已交付", "");
            case "model-unknown" -> passCheck().replace("PASS", "UNKNOWN");
            case "unknown-enum" -> passCheck().replace("PASS", "APPROVED");
            case "numeric-enum" -> passCheck().replace("\"PASS\"", "0");
            default -> passCheck();
        };
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(
                "omitted".equals(failure) ? "{\"issues\":[]}" : auditJson(check));
        var item = deliveryPlan();

        var result = auditDelivery(item, DELIVERED, deliveryRequest(item, DELIVERED));

        assertTrue(result.getDeliveryReview().unknown());
        assertTrue(result.getDeliveryReview().blocksCommit());
        assertFalse(result.isAuditUnavailable(), "完整有效 issues 应在交付协议失败时保全");
        assertFalse(result.getDeliveryReview().diagnostics().isEmpty());
        var calls = capturedCalls(2);
        assertTrue(calls.get(1).getUserPrompt().contains("上次核验未完成"));
        assertTrue(calls.get(1).getUserPrompt().contains(result.getDeliveryReview().diagnostics().get(0)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate-root", "duplicate-check", "truncated", "trailing-json", "dangling-comma",
            "incomplete-fence", "missing-issues", "null-issues"})
    void strictDeliveryJsonCannotBeRepairedIntoPass(String failure) {
        String valid = auditJson(passCheck());
        String raw = switch (failure) {
            case "duplicate-root" -> valid.replace("\"issues\":[]", "\"issues\":[],\"issues\":[]");
            case "duplicate-check" -> valid.replace("\"verdict\":\"PASS\"", "\"verdict\":\"ISSUE\",\"verdict\":\"PASS\"");
            case "truncated" -> valid.substring(0, valid.length() - 2);
            case "trailing-json" -> valid + "{}";
            case "dangling-comma" -> valid.replace("\"issues\":[]", "\"issues\":[,]");
            case "incomplete-fence" -> "```json\n" + valid;
            case "missing-issues" -> valid.replace("\"issues\":[],", "");
            case "null-issues" -> valid.replace("\"issues\":[]", "\"issues\":null");
            default -> throw new AssertionError(failure);
        };
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(raw);
        var item = deliveryPlan();

        var result = auditDelivery(item, DELIVERED, deliveryRequest(item, DELIVERED));

        assertTrue(result.getDeliveryReview().unknown());
        assertTrue(result.isAuditUnavailable(), "整体协议或首审字段无效不能假装零问题");
        capturedCalls(2);
    }

    @Test
    void deliveryAuditAllowsOnlyCompleteMarkdownFenceAndRetriesWithSpecificDiagnostics() {
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(
                auditJson(passCheck().replace(DELIVERED_QUOTE, "原文并未出现的药签转手")),
                "```json\n" + auditJson(passCheck()) + "\n```");
        var item = deliveryPlan();

        var result = auditDelivery(item, DELIVERED, deliveryRequest(item, DELIVERED));

        assertTrue(result.getDeliveryReview().passed());
        var calls = capturedCalls(2);
        assertTrue(calls.get(1).getUserPrompt().contains("DELIVERY_CONTENT_EVIDENCE"));
        assertTrue(calls.get(1).getUserPrompt().contains("[event:1]"));
    }

    @Test
    void deliveryAuditPreservesLatestValidIssuesWhenRetryFailsEntirely() {
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(
                "{\"issues\":[{\"dimension\":\"hook\",\"severity\":\"BLOCKING\","
                        + "\"description\":\"保全问题\",\"evidence\":\"虚构的正文引文\"}],\"deliveryChecks\":[]}")
                .thenThrow(new IllegalStateException("gateway unavailable"));
        var item = deliveryPlan();

        var result = auditDelivery(item, DELIVERED, deliveryRequest(item, DELIVERED));

        assertFalse(result.isAuditUnavailable());
        assertEquals("保全问题", result.getIssues().get(0).getDescription());
        assertEquals("MINOR", result.getIssues().get(0).getSeverity(), "旧七维证据降级继续有效");
        assertTrue(result.getDeliveryReview().unknown());
        capturedCalls(2);
    }

    @Test
    void deliveryAuditTotalGatewayFailureIsUnavailableNotEmptyPass() {
        when(llmGateway.complete(any(), any(LlmCall.class))).thenThrow(new IllegalStateException("offline"));
        var item = deliveryPlan();

        var result = auditDelivery(item, DELIVERED, deliveryRequest(item, DELIVERED));

        assertTrue(result.isAuditUnavailable());
        assertTrue(result.getDeliveryReview().unknown());
        capturedCalls(2);
    }

    @Test
    void revisionNeedsCurrentDeliveryPassEvenWhenOldFixedIsTrue() {
        var item = deliveryPlan();
        var oldIssue = ChapterIssueEntity.builder().dimension("plan-delivery").severity("BLOCKING")
                .description("交付药签缺失").build();
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(
                fixJson("[{\"index\":1,\"fixed\":true}]", missingDeliveryCheck()));
        String revised = "他在窗下静坐。";

        var result = service.verifyFixes(storyVO(), item, revised, List.of(oldIssue), null, null, 1,
                null, List.of(), deliveryRequest(item, revised));

        assertFalse(result.degraded(), "有效交付 ISSUE 不是核验降级");
        assertTrue(result.unfixed().isEmpty(), "旧 fixed 索引语义保留，但不能据此代替交付报告");
        assertEquals(DeliveryReview.Status.ISSUE, result.deliveryReview().status());
        assertTrue(result.deliveryReview().blocksCommit());
        String prompt = capturedCalls(1).get(0).getUserPrompt();
        assertTrue(prompt.contains("不能用无证据 fixed=true 作为交付证明"));
        assertTrue(prompt.contains("不得捏造不存在的正文"));
    }

    @Test
    void revisionDeliveryPassAndOrdinaryFixedTrueNeedOnlyOneCall() {
        var item = deliveryPlan();
        var original = new ChapterManuscript(1, item.getTitle(), "他一直攥着药签。");
        var context = new RewriteContext("冻结事实：药签唯一", "只写既定视角", original);
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(
                fixJson("[{\"index\":1,\"fixed\":true}]", passCheck()));

        var result = service.verifyFixes(storyVO(), item, DELIVERED, List.of(blocking(null)), null, null, 1,
                context, List.of(), deliveryRequest(item, DELIVERED));

        assertFalse(result.degraded());
        assertTrue(result.unfixed().isEmpty(), "普通问题 fixed=true 保留无需缺席证据的旧规则");
        assertTrue(result.deliveryReview().passed());
        assertEquals(new ChapterManuscript(1, item.getTitle(), DELIVERED).hash(), result.deliveryReview().manuscriptHash());
        String prompt = capturedCalls(1).get(0).getUserPrompt();
        assertTrue(prompt.contains(original.content()));
        assertTrue(prompt.contains("冻结事实：药签唯一"));
        assertTrue(prompt.contains("只写既定视角"));
    }

    @Test
    void revisionRunsDeliveryWithNoOldBlockingAndRendersScenesWithoutWriterPrompt() {
        var item = deliveryPlan();
        item.setSchemaVersion(2);
        item.setScenes(List.of(new ScenePlan("handover", List.of("他", "执事"), "交付药签", "接收待确认",
                List.of(), "执事接过药签", "交付确认", List.of(), "窗口", "清晨")));
        String sceneCheck = passCheck().replace("\"targetId\":\"event:1\"", "\"targetId\":\"scene:handover\",\"sceneId\":\"handover\"")
                .replace("keyEvents[0]", "outcome").replace("交付药签", "执事接过药签");
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(fixJson("[]", sceneCheck));
        var context = new RewriteContext(null, null, new ChapterManuscript(1, item.getTitle(), "他握着药签。"));

        var result = service.verifyFixes(storyVO(), item, DELIVERED, List.of(), null, null, 1,
                context, List.of(), deliveryRequest(item, DELIVERED));

        assertFalse(result.degraded());
        assertTrue(result.deliveryReview().passed());
        assertEquals(DeliveryReview.Scope.SCENES, result.deliveryReview().scope());
        String prompt = capturedCalls(1).get(0).getUserPrompt();
        assertTrue(prompt.contains("权威场景契约"));
        assertTrue(prompt.contains("id：handover"));
        assertTrue(prompt.contains("最终结果(outcome)：执事接过药签"));
        assertTrue(prompt.contains("修订前版本"));
        assertTrue(prompt.contains("他握着药签。"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-delivery", "old-quote", "duplicate-key", "truncated", "missing-verdict",
            "duplicate-index", "unknown-index", "missing-fixed", "missing-continuity", "invented-conflict"})
    void revisionProtocolOrEvidenceFailuresRetryAndStayDegradedUnknown(String failure) {
        String raw = fixJson("[{\"index\":1,\"fixed\":true}]", passCheck());
        raw = switch (failure) {
            case "missing-delivery" -> "{\"verdicts\":[{\"index\":1,\"fixed\":true}],\"continuityChecked\":true,\"introducedConflicts\":[]}";
            case "old-quote" -> raw.replace(DELIVERED_QUOTE, "修订前药签仍在他的手里");
            case "duplicate-key" -> raw.replace("\"fixed\":true", "\"fixed\":false,\"fixed\":true");
            case "truncated" -> raw.substring(0, raw.length() - 2);
            case "missing-verdict" -> raw.replace("[{\"index\":1,\"fixed\":true}]", "[]");
            case "duplicate-index" -> raw.replace("[{\"index\":1,\"fixed\":true}]",
                    "[{\"index\":1,\"fixed\":true},{\"index\":1,\"fixed\":true}]");
            case "unknown-index" -> raw.replace("\"index\":1", "\"index\":2");
            case "missing-fixed" -> raw.replace(",\"fixed\":true", "");
            case "missing-continuity" -> raw.replace("\"continuityChecked\":true,", "");
            case "invented-conflict" -> raw.replace("\"introducedConflicts\":[]",
                    "\"introducedConflicts\":[{\"description\":\"凭空出现\",\"evidence\":\"原文不存在的银票\"}]");
            default -> throw new AssertionError(failure);
        };
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(raw);
        var item = deliveryPlan();
        var issue = blocking(null);
        var context = new RewriteContext(null, null,
                new ChapterManuscript(1, item.getTitle(), "修订前药签仍在他的手里。"));

        var result = service.verifyFixes(storyVO(), item, DELIVERED, List.of(issue), null, null, 1,
                context, List.of(), deliveryRequest(item, DELIVERED));

        assertTrue(result.degraded());
        assertEquals(List.of(issue), result.unfixed());
        assertTrue(result.deliveryReview().unknown());
        assertTrue(capturedCalls(2).get(1).getUserPrompt().contains("上次核验未完成"));
    }

    @Test
    void revisionKeepsPlanIntegrityIndicesAndNewContinuityChecksAlongsideDelivery() {
        String conflict = "他又从袖中取出已经交走的药签";
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(
                fixJson("[{\"index\":1,\"fixed\":true},{\"index\":2,\"fixed\":false}]", passCheck())
                        .replace("\"introducedConflicts\":[]", "\"introducedConflicts\":[{\"description\":\"药签重复出现\","
                                + "\"factEvidence\":\"他把药签交给执事\",\"evidence\":\"" + conflict + "\"}]"));
        var item = deliveryPlan();
        String revised = DELIVERED + conflict + "。";

        var result = service.verifyFixes(storyVO(), item, revised, List.of(blocking(null)), "他把药签交给执事。", null, 1,
                null, List.of("保留来访登记"), deliveryRequest(item, revised));

        assertFalse(result.degraded());
        assertTrue(result.deliveryReview().passed());
        assertEquals(2, result.unfixed().size());
        assertEquals(ChapterAuditService.PLAN_INTEGRITY_DIMENSION, result.unfixed().get(0).getDimension());
        assertEquals("continuity", result.unfixed().get(1).getDimension());
        assertEquals("BLOCKING", result.unfixed().get(1).getSeverity());
        assertTrue(capturedCalls(1).get(0).getUserPrompt().contains("2. 【plan-integrity】"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"content", "plan", "chapter"})
    void mismatchedDeliveryRequestIsUnknownWithoutAnyModelCall(String mismatch) {
        var item = deliveryPlan();
        var request = deliveryRequest(item, DELIVERED);
        String content = DELIVERED;
        int chapterNo = 1;
        if ("content".equals(mismatch)) content = "完全不同的修订稿。";
        if ("plan".equals(mismatch)) item.setKeyEvents(List.of("保留药签，不交出"));
        if ("chapter".equals(mismatch)) chapterNo = 2;

        var audit = service.audit(storyVO(), item, content, null, null, null, chapterNo, null, request);
        var revision = service.verifyFixes(storyVO(), item, content, List.of(), null, null, chapterNo,
                null, List.of(), request);

        assertTrue(audit.isAuditUnavailable());
        assertTrue(audit.getDeliveryReview().unknown());
        assertTrue(revision.degraded());
        assertTrue(revision.deliveryReview().unknown());
        verifyNoInteractions(llmGateway);
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "B"})
    void deliveryPromptKeepsStrictMissingProcessBoundaryWithoutChangingOffPrompt(String variant) {
        System.setProperty(AuditPromptVariant.SYSTEM_PROPERTY, variant);
        try {
            var item = deliveryPlan();
            String on = service.buildPrompt(item, DELIVERED, null, null, null, 1, null, deliveryRequest(item, DELIVERED));
            String off = service.buildPrompt(item, DELIVERED, null, null, null, 1, null);
            assertTrue(on.contains("必要过程与结果已兑现但表达简短/衔接偏快 = MINOR"));
            assertTrue(on.contains("计划承诺的必要过程未兑现属于交付缺失"));
            assertTrue(on.contains("不强制对白、动作展示或扩写"));
            assertFalse(on.contains("交锋整段缺失 → MINOR"));
            assertFalse(on.contains("关键事件出现了但展开不足/衔接偏快 = MINOR"));
            assertTrue(off.contains("关键事件出现了但展开不足/衔接偏快 = MINOR"));
            assertFalse(off.contains("deliveryChecks"), "OFF 不暗中请求新核验清单");
        } finally {
            System.clearProperty(AuditPromptVariant.SYSTEM_PROPERTY);
        }
    }

    @Test
    void offVerificationRetainsEmptyFastPathMissingIndexAndFixedWithoutEvidenceRules() {
        var empty = service.verifyFixes(storyVO(), planItem(), "正文", List.of(), null, null, 1);
        assertTrue(empty.unfixed().isEmpty());
        assertFalse(empty.degraded());
        assertNull(empty.deliveryReview());
        verifyNoInteractions(llmGateway);
        when(llmGateway.complete(any(), any(LlmCall.class)))
                .thenReturn("{\"verdicts\":[{\"index\":1,\"fixed\":true}]}");
        var first = blocking(null);
        var second = blocking("缺少条目");

        var result = service.verifyFixes(storyVO(), planItem(), "正文", List.of(first, second), null, null, 1);

        assertFalse(result.degraded());
        assertEquals(List.of(second), result.unfixed());
        assertNull(result.deliveryReview());
        assertFalse(capturedCalls(1).get(0).getUserPrompt().contains("deliveryChecks"));
        assertEquals(List.of(first), new ChapterAuditService.FixVerification(List.of(first), true).unfixed());
        assertFalse(ChapterAuditService.FixVerification.verified(List.of()).degraded());
    }

    @ParameterizedTest
    @ValueSource(strings = {"audit-on", "audit-off", "fix-on", "fix-off"})
    void localRejectionIsNeverRetriedOrConvertedToUnknown(String entry) {
        var rejection = new LlmLocalRejectionException("local limit");
        when(llmGateway.complete(any(), any(LlmCall.class))).thenThrow(new IllegalStateException("wrapped", rejection));
        var item = deliveryPlan();
        var request = entry.endsWith("on") ? deliveryRequest(item, DELIVERED) : null;

        LlmLocalRejectionException thrown = assertThrows(LlmLocalRejectionException.class, () -> {
            if (entry.startsWith("audit")) auditDelivery(item, DELIVERED, request);
            else service.verifyFixes(storyVO(), item, DELIVERED, List.of(blocking(null)), null, null, 1,
                    null, List.of(), request);
        });

        assertSame(rejection, thrown);
        capturedCalls(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"audit-before", "audit-after", "audit-catch", "fix-before", "fix-after", "fix-catch"})
    void requestCancellationPropagatesBeforeAfterAndDuringFailure(String phase) {
        var cancelled = new AtomicBoolean(phase.endsWith("before"));
        var signal = new CancellationException("cancel request");
        var item = deliveryPlan();
        var request = new DeliveryReviewPolicy.Request(DeliveryReviewPolicy.contract(item),
                new ChapterManuscript(1, item.getTitle(), DELIVERED), true, () -> {
                    if (cancelled.get()) throw signal;
                });
        when(llmGateway.complete(any(), any(LlmCall.class))).thenAnswer(invocation -> {
            cancelled.set(true);
            if (phase.endsWith("catch")) throw new IllegalStateException("gateway failure");
            return phase.startsWith("audit") ? auditJson(passCheck()) : fixJson("[]", passCheck());
        });

        var thrown = assertThrows(CancellationException.class, () -> {
            if (phase.startsWith("audit")) auditDelivery(item, DELIVERED, request);
            else service.verifyFixes(storyVO(), item, DELIVERED, List.of(), null, null, 1, null, List.of(), request);
        });

        assertSame(signal, thrown);
        capturedCalls(phase.endsWith("before") ? 0 : 1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void budgetSignalAfterModelCallIsNotSwallowedByProtocolCatch(boolean audit) {
        var exhausted = new AtomicBoolean();
        var signal = new AppException("budget", "预算熔断");
        var item = deliveryPlan();
        var request = new DeliveryReviewPolicy.Request(DeliveryReviewPolicy.contract(item),
                new ChapterManuscript(1, item.getTitle(), DELIVERED), true, () -> {
                    if (exhausted.getAndSet(false)) throw signal;
                });
        when(llmGateway.complete(any(), any(LlmCall.class))).thenAnswer(invocation -> {
            exhausted.set(true);
            return audit ? auditJson(passCheck()) : fixJson("[]", passCheck());
        });

        var thrown = assertThrows(AppException.class, () -> {
            if (audit) auditDelivery(item, DELIVERED, request);
            else service.verifyFixes(storyVO(), item, DELIVERED, List.of(), null, null, 1, null, List.of(), request);
        });

        assertSame(signal, thrown, "一次性预算回调也必须传播，不能再次检查后吞掉");
        capturedCalls(1);
    }

    private static final String DELIVERED_QUOTE = "执事接过药签，核对编号后收进匣子";
    private static final String DELIVERED = "他递出药签。" + DELIVERED_QUOTE + "。";

    private ChapterPlanItemEntity deliveryPlan() {
        var item = planItem();
        item.setKeyEvents(List.of("交付药签"));
        return item;
    }

    private DeliveryReviewPolicy.Request deliveryRequest(ChapterPlanItemEntity item, String content) {
        return new DeliveryReviewPolicy.Request(DeliveryReviewPolicy.contract(item),
                new ChapterManuscript(1, item.getTitle(), content), true);
    }

    private AuditResultEntity auditDelivery(ChapterPlanItemEntity item, String content, DeliveryReviewPolicy.Request request) {
        return service.audit(storyVO(), item, content, null, null, null, 1, null, request);
    }

    private String passCheck() {
        return "{\"targetId\":\"event:1\",\"verdict\":\"PASS\",\"planField\":\"keyEvents[0]\","
                + "\"planEvidence\":\"交付药签\",\"evidence\":\"" + DELIVERED_QUOTE + "\",\"reason\":\"接收动作已交付\"}";
    }

    private String missingDeliveryCheck() {
        return "{\"targetId\":\"event:1\",\"verdict\":\"ISSUE\",\"planField\":\"keyEvents[0]\","
                + "\"planEvidence\":\"交付药签\",\"reason\":\"核对全文，未发生计划要求的药签交付\","
                + "\"repair\":\"补足该交付\",\"failureType\":\"MISSING_DELIVERY\"}";
    }

    private String auditJson(String checks) {
        return "{\"issues\":[],\"deliveryChecks\":[" + checks + "]}";
    }

    private String fixJson(String verdicts, String checks) {
        return "{\"verdicts\":" + verdicts + ",\"continuityChecked\":true,\"introducedConflicts\":[],"
                + "\"deliveryChecks\":[" + checks + "]}";
    }

    private List<LlmCall> capturedCalls(int expected) {
        var captor = ArgumentCaptor.forClass(LlmCall.class);
        verify(llmGateway, times(expected)).complete(any(), captor.capture());
        return captor.getAllValues();
    }

    private static final String CONTENT = "洛恩抬起头，\n“魔力的空的。”老执事收回了手。\n  他退回阴影里，警戒符在高塔上转黑。";

    private ChapterIssueEntity blocking(String evidence) {
        return ChapterIssueEntity.builder()
                .dimension("hook")
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
