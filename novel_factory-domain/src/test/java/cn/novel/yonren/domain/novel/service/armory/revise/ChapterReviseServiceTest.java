package cn.novel.yonren.domain.novel.service.armory.revise;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.RevisionDecisionEntity;
import cn.novel.yonren.domain.novel.model.entity.StyleStatEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.memory.StyleStatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 章节修订服务测试
 */
class ChapterReviseServiceTest {

    private LlmGateway llmGateway;
    private StyleStatService styleStatService;
    private ChapterReviseService service;

    @BeforeEach
    void setUp() {
        llmGateway = mock(LlmGateway.class);
        styleStatService = new StyleStatService();
        // 既有用例全部针对「整章重写」形态，关掉定向补丁让它们保持"恰好一次调用"的断言强度；
        // 定向补丁路径由下面的 patch* 用例单独覆盖（各自传入开启补丁的配置）
        service = new ChapterReviseService(llmGateway, styleStatService, reviseProperties(false));
    }

    private static StoryProperties reviseProperties(boolean patchEnabled) {
        StoryProperties properties = new StoryProperties();
        properties.getRevise().setPatchEnabled(patchEnabled);
        return properties;
    }

    /** 开启定向补丁的服务实例 */
    private ChapterReviseService patchService() {
        return new ChapterReviseService(llmGateway, styleStatService, reviseProperties(true));
    }

    @Test
    void revise_acceptsWhenAllGatesPass() {
        String revisedJson = "{\"chapterNo\":1,\"title\":\"测试章\",\"content\":\"小陈拔剑，事件A发生，事件B也发生，结尾呼应钩子。\"}";
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(revisedJson);

        ChapterReviseService.ReviseResult result = service.revise(
                storyVO(), planItem(), "原稿正文，小陈拔剑。",
                List.of(blockingIssue()), null, null, emptyStyleStat(), 1, null);

        assertTrue(result.decision().isAttempted());
        assertTrue(result.decision().isAccepted());
        assertNotNull(result.revisedContent());
        assertEquals("小陈拔剑，事件A发生，事件B也发生，结尾呼应钩子。", result.revisedContent().getContent());
    }

    @Test
    void revise_rejectsWhenTooShort() {
        String revisedJson = "{\"chapterNo\":1,\"title\":\"测试章\",\"content\":\"短。\"}";
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(revisedJson);

        ChapterReviseService.ReviseResult result = service.revise(
                storyVO(), planItem(), "原稿正文比较长，包含很多内容。",
                List.of(blockingIssue()), null, null, emptyStyleStat(), 1, null);

        assertTrue(result.decision().isAttempted());
        assertFalse(result.decision().isAccepted());
        assertNull(result.revisedContent());
    }

    @Test
    void revise_toleratesSingleKeyEventLoss_forBlockingFix() {
        // 允许以 ≤1 条关键事件损失换取 BLOCKING 修复——违规现场常与事件场景重叠
        //（"幼年写下未来年份"本身就是排期项），修复必然改写该场景，一字不差的要求只会造成不可修复的拒绝循环
        String revisedJson = "{\"chapterNo\":1,\"title\":\"测试章\",\"content\":\"小陈拔剑，事件A发生，随后他收剑环顾，把余下段落补足长度，使修订稿不至于因字数闸被拒，结尾落在事件C上。\"}";
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(revisedJson);

        ChapterReviseService.ReviseResult result = service.revise(
                storyVO(), planItem(), "原稿正文，小陈拔剑，事件A发生，事件B发生。",
                List.of(blockingIssue()), null, null, emptyStyleStat(), 1, null);

        assertTrue(result.decision().isAttempted());
        assertTrue(result.decision().isAccepted(), "丢失 1 条事件（事件B）应放行——BLOCKING 修复优先");
    }

    @Test
    void revise_rejectsWhenMultipleKeyEventsMissing() {
        String revisedJson = "{\"chapterNo\":1,\"title\":\"测试章\",\"content\":\"小陈收剑入鞘，独自走向山门，夜里无话，梦里全是白天的影子，醒来时天已大亮，但事件C被删除了。\"}";
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(revisedJson);

        ChapterReviseService.ReviseResult result = service.revise(
                storyVO(), planItem(), "原稿正文，小陈拔剑，事件A发生，事件B发生，结尾他望向远方。",
                List.of(blockingIssue()), null, null, emptyStyleStat(), 1, null);

        assertTrue(result.decision().isAttempted());
        assertFalse(result.decision().isAccepted(), "丢失 ≥2 条事件说明修订在跑偏，必须拒绝");
        assertTrue(result.decision().getReason().contains("事件A"));
    }

    @Test
    void revise_ignoresEventsOriginalNeverCovered() {
        // 原稿本就未覆盖的事件不再强求：生成期已放行的既成事实，修订无从"恢复"从未存在的内容
        String keyEvent = "主角乙分析结晶，发现它并非天然矿石，而是某种阵法的核心组件，且内部封存着一缕残魂";
        String revisedJson = "{\"chapterNo\":1,\"title\":\"测试章\",\"content\":\"小陈拔剑，事件A发生，事件B发生，他还检查了洞府的禁制并记下了三处松动，结尾呼应钩子。\"}";
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(revisedJson);

        ChapterPlanItemEntity item = planItem();
        item.setKeyEvents(List.of("事件A", keyEvent));

        ChapterReviseService.ReviseResult result = service.revise(
                storyVO(), item, "原稿正文，小陈拔剑，事件A发生，事件B发生，结尾他望向远方。",
                List.of(blockingIssue()), null, null, emptyStyleStat(), 1, null);

        assertTrue(result.decision().isAttempted());
        assertTrue(result.decision().isAccepted(),
                "原稿未覆盖 keyEvent（主角乙结晶）不得成为拒绝理由——修订不需要恢复从未存在的内容");
    }

    @Test
    void revise_acceptsWhenLongKeyEventCoveredByParaphrase() {
        // 长关键事件：正文用转化后的措辞覆盖核心要素（"天然矿石""一缕残魂"等专有短语），而非一字不差照抄
        String keyEvent = "主角乙分析结晶，发现它并非天然矿石，而是某种阵法的核心组件，且内部封存着一缕残魂";
        String revisedJson = "{\"chapterNo\":1,\"title\":\"测试章\",\"content\":\"主角乙拿起那枚结晶核心，用灵力探查，发现它根本不是天然矿石，而是阵法核心，其中还封着一缕残魂。\"}";
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(revisedJson);

        ChapterPlanItemEntity item = planItem();
        item.setKeyEvents(List.of(keyEvent));

        ChapterReviseService.ReviseResult result = service.revise(
                storyVO(), item, "原稿正文比较长，主角乙分析结晶，发现它并非天然矿石，内部还封存着一缕残魂，用于满足字数闸。",
                List.of(blockingIssue()), null, null, emptyStyleStat(), 1, null);

        assertTrue(result.decision().isAttempted());
        assertTrue(result.decision().isAccepted(), "关键事件被转化覆盖时不应误判为缺失");
    }

    @Test
    void revise_rejectsWhenLongKeyEventTrulyMissing() {
        String keyEvent = "主角乙分析结晶，发现它并非天然矿石，而是某种阵法的核心组件，且内部封存着一缕残魂";
        String secondEvent = "主角乙在封存处外围布下三道警戒符，确认无人跟踪后才落锁闭门";
        // 修订稿与两条事件不得共享 ≥4 字连续子串（否则按"转化覆盖"放行），且长度须过 0.8 字数闸
        String revisedJson = "{\"chapterNo\":1,\"title\":\"测试章\",\"content\":\"洞府里的烛火晃了晃，什么异常都没有发生，天色渐暗，他把卷宗收回匣中，吹熄了灯，转身回了小屋休息，一路无话，梦里只有风声掠过屋檐的声音，醒来时天已大亮，昨夜的事像没有发生过一样。\"}";
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(revisedJson);

        ChapterPlanItemEntity item = planItem();
        item.setKeyEvents(List.of(keyEvent, secondEvent));

        ChapterReviseService.ReviseResult result = service.revise(
                storyVO(), item, "原稿正文比较长，主角乙分析结晶，发现它并非天然矿石，内部还封存着一缕残魂，随后又在封存处外围布下三道警戒符，确认无人跟踪后才落锁闭门。",
                List.of(blockingIssue()), null, null, emptyStyleStat(), 1, null);

        assertTrue(result.decision().isAttempted());
        assertFalse(result.decision().isAccepted());
        assertTrue(result.decision().getReason().contains("缺失关键事件"));
    }

    @Test
    void revise_acceptsWhenStyleWorsens_styleGateIsAdvisory() {
        // 风格账闸降级为告知——"新增跨章重复句/疲劳词跨线"是 MINOR 级问题，
        // 不得否决 BLOCKING 修复（原实现直接拒稿，导致多章两轮修订全灭、硬伤带病落盘）
        String revisedJson = "{\"chapterNo\":1,\"title\":\"测试章\",\"content\":\"小陈拔剑，事件A发生，事件B发生，结尾呼应钩子。这也是一句很长的话用来凑字数。\"}";
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(revisedJson);

        StyleStatEntity stat = styleStatWithRepeatedSentence();

        ChapterReviseService.ReviseResult result = service.revise(
                storyVO(), planItem(), "原稿正文，小陈拔剑，事件A发生，事件B发生，结尾呼应钩子。",
                List.of(blockingIssue()), null, null, stat, 1, null);

        assertTrue(result.decision().isAttempted());
        assertTrue(result.decision().isAccepted(), "风格问题不再拒稿：BLOCKING 修复优先");
        assertNotNull(result.revisedContent());
    }

    @Test
    void revise_acceptsWhenFatigueWordCrossesThreshold() {
        // 疲劳词越线同样降级为告知（原实现会拒稿）
        StyleStatEntity stat = emptyStyleStat();
        // 全书已累计 4 次（阈值 5）——修订稿把"微微"推过线
        styleStatService.merge(stat, "微微点头，微微抬眸，微微弯起嘴角，微微退后半步。");
        String revisedJson = "{\"chapterNo\":1,\"title\":\"测试章\",\"content\":\"小陈拔剑，微微侧目，事件A发生，尾音微微上扬，事件B也发生，结尾呼应钩子。\"}";
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(revisedJson);

        ChapterReviseService.ReviseResult result = service.revise(
                storyVO(), planItem(), "原稿正文，小陈拔剑。",
                List.of(blockingIssue()), null, null, stat, 1, null);

        assertTrue(result.decision().isAttempted());
        assertTrue(result.decision().isAccepted(), "疲劳词越线不再拒稿：BLOCKING 修复优先");
        assertNotNull(result.revisedContent());
    }

    @Test
    void revise_promptCarriesMicroActionBlacklistWithCounts() {
        String revisedJson = "{\"chapterNo\":1,\"title\":\"测试章\",\"content\":\"小陈拔剑，事件A发生，事件B也发生，结尾呼应钩子。\"}";
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(revisedJson);

        String original = "他喉结滚动了一下。她又喉结滚动，再次喉结滚动。他呼吸一滞，随即呼吸一滞。";
        service.revise(storyVO(), planItem(), original,
                List.of(blockingIssue()), null, null, emptyStyleStat(), 1, null);

        ArgumentCaptor<LlmCall> captor = ArgumentCaptor.forClass(LlmCall.class);
        verify(llmGateway).complete(any(), captor.capture());
        String prompt = captor.getValue().getUserPrompt();

        assertTrue(prompt.contains("【微动作黑名单】"));
        assertTrue(prompt.contains("至多 1 次"));
        assertTrue(prompt.contains("喉结滚动×3"));
        assertTrue(prompt.contains("呼吸一滞×2"));
        assertTrue(prompt.contains("替换为更具体的环境描写"));
    }

    @Test
    void revise_promptCarriesFatigueBlacklistWithNearLimitWords() {
        String revisedJson = "{\"chapterNo\":1,\"title\":\"测试章\",\"content\":\"小陈拔剑，事件A发生，事件B也发生，结尾呼应钩子。\"}";
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(revisedJson);

        StyleStatEntity stat = emptyStyleStat();
        // 模拟全书已累计 4 次（阈值 5），逼近超频红线
        styleStatService.merge(stat, "微微点头，微微抬眸，微微弯起嘴角，微微退后半步。");

        service.revise(storyVO(), planItem(), "原稿正文，小陈拔剑。",
                List.of(blockingIssue()), null, null, stat, 1, null);

        ArgumentCaptor<LlmCall> captor = ArgumentCaptor.forClass(LlmCall.class);
        verify(llmGateway).complete(any(), captor.capture());
        String prompt = captor.getValue().getUserPrompt();

        assertTrue(prompt.contains("【疲劳词禁新增名单】"));
        assertTrue(prompt.contains("微微"));
        assertTrue(prompt.contains("全书已累计"));
        assertTrue(prompt.contains("绝对禁止再出现"));
    }

    @Test
    void revise_promptCarriesPreviousRejectionReason() {
        String revisedJson = "{\"chapterNo\":1,\"title\":\"测试章\",\"content\":\"小陈拔剑，事件A发生，事件B也发生，结尾呼应钩子。\"}";
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(revisedJson);

        service.revise(storyVO(), planItem(), "原稿正文，小陈拔剑。",
                List.of(blockingIssue()), null, null, emptyStyleStat(), 1,
                "修订稿使疲劳词 '轻轻' 达到超频阈值");

        ArgumentCaptor<LlmCall> captor = ArgumentCaptor.forClass(LlmCall.class);
        verify(llmGateway).complete(any(), captor.capture());
        String prompt = captor.getValue().getUserPrompt();

        assertTrue(prompt.contains("【上一轮修订反馈】"));
        assertTrue(prompt.contains("修订稿使疲劳词 '轻轻' 达到超频阈值"));
    }

    @Test
    void revise_promptCarriesKeyEventRedline() {
        String revisedJson = "{\"chapterNo\":1,\"title\":\"测试章\",\"content\":\"小陈拔剑，事件A发生，事件B也发生，结尾呼应钩子。\"}";
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(revisedJson);

        service.revise(storyVO(), planItem(), "原稿正文，小陈拔剑。",
                List.of(blockingIssue()), null, null, emptyStyleStat(), 1, null);

        ArgumentCaptor<LlmCall> captor = ArgumentCaptor.forClass(LlmCall.class);
        verify(llmGateway).complete(any(), captor.capture());
        String prompt = captor.getValue().getUserPrompt();

        assertTrue(prompt.contains("【关键事件红线】"));
        assertTrue(prompt.contains("严禁删除、合并或把关键事件降级为一句背景概述"));
        assertTrue(prompt.contains("采纳闸门会逐条核对关键事件是否仍被正文覆盖，缺失任何一条即拒稿"));
    }

    @Test
    void revise_promptOmitsKeyEventRedline_whenPlanHasNoKeyEvents() {
        String revisedJson = "{\"chapterNo\":1,\"title\":\"测试章\",\"content\":\"结尾呼应钩子。\"}";
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(revisedJson);

        ChapterPlanItemEntity item = planItem();
        item.setKeyEvents(null);

        service.revise(storyVO(), item, "原稿正文。",
                List.of(blockingIssue()), null, null, emptyStyleStat(), 1, null);

        ArgumentCaptor<LlmCall> captor = ArgumentCaptor.forClass(LlmCall.class);
        verify(llmGateway).complete(any(), captor.capture());
        assertFalse(captor.getValue().getUserPrompt().contains("【关键事件红线】"));
    }

    @Test
    void revise_promptCarriesMechanicalEvidenceSection_forAestheticIssues() {
        String revisedJson = "{\"chapterNo\":1,\"title\":\"测试章\",\"content\":\"小陈拔剑，事件A发生，事件B也发生，结尾呼应钩子。\"}";
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(revisedJson);

        ChapterIssueEntity mechanical = ChapterIssueEntity.builder()
                .dimension("aesthetic")
                .severity("BLOCKING")
                .evidence("喉结滚动×2、缓缓×3")
                .description("身体反应套话复读")
                .suggestion("替换为微动作")
                .build();
        service.revise(storyVO(), planItem(), "原稿正文，小陈拔剑。",
                List.of(mechanical), null, null, emptyStyleStat(), 1, null);

        ArgumentCaptor<LlmCall> captor = ArgumentCaptor.forClass(LlmCall.class);
        verify(llmGateway).complete(any(), captor.capture());
        String prompt = captor.getValue().getUserPrompt();

        assertTrue(prompt.contains("【机械扫描确认的文风违规点】"));
        assertTrue(prompt.contains("喉结滚动×2、缓缓×3"));
        assertTrue(prompt.contains("逐处改写"));
    }

    @Test
    void revise_promptOmitsMechanicalSection_forNonAestheticIssues() {
        String revisedJson = "{\"chapterNo\":1,\"title\":\"测试章\",\"content\":\"小陈拔剑，事件A发生，事件B也发生，结尾呼应钩子。\"}";
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(revisedJson);

        service.revise(storyVO(), planItem(), "原稿正文，小陈拔剑。",
                List.of(blockingIssue()), null, null, emptyStyleStat(), 1, null);

        ArgumentCaptor<LlmCall> captor = ArgumentCaptor.forClass(LlmCall.class);
        verify(llmGateway).complete(any(), captor.capture());
        assertFalse(captor.getValue().getUserPrompt().contains("【机械扫描确认的文风违规点】"));
    }

    // ==================== 定向补丁 ====================

    /** 含计划里两个关键事件的原稿（补丁只动其中一句，事件不受影响） */
    private static final String PATCH_ORIGINAL = "小陈拔剑，事件A发生，事件B随后发生。他坐下了。";

    private static final String PATCH_ONLY_JSON =
            "{\"patches\":[{\"anchor\":\"他坐下了。\",\"replacement\":\"他坐下了——剑是他刚从石缝里拔出来的。\"}]}";

    /** 补丁被采纳时应得到的正文（原稿其余部分逐字保留） */
    private static final String PATCHED_CONTENT = "小陈拔剑，事件A发生，事件B随后发生。他坐下了——剑是他刚从石缝里拔出来的。";

    private static final String REWRITE_JSON =
            "{\"chapterNo\":1,\"title\":\"测试章\",\"content\":\"小陈拔剑，事件A发生，事件B也发生，结尾呼应钩子。\"}";

    @Test
    void patch_acceptedWhenAnchorAppliesAndGatesPass() {
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(PATCH_ONLY_JSON);

        ChapterReviseService.ReviseResult result = patchService().revise(
                storyVO(), planItem(), PATCH_ORIGINAL,
                List.of(blockingIssue()), null, null, emptyStyleStat(), 1, null);

        assertTrue(result.decision().isAccepted());
        assertEquals(PATCHED_CONTENT, result.revisedContent().getContent());
        assertTrue(result.decision().getReason().contains("定向补丁"));
        // 补丁只动正文局部：标题必须留空，交由 QualityGate 保留原标题（免得把标题清成 null）
        assertNull(result.revisedContent().getTitle(), "补丁路径不该带标题");
        // 只调用一次：补丁成功就完全跳过整章重写
        ArgumentCaptor<LlmCall> captor = ArgumentCaptor.forClass(LlmCall.class);
        verify(llmGateway).complete(any(), captor.capture());
        assertTrue(captor.getValue().getLabel().startsWith("patch-"));
    }

    @Test
    void patch_appliesMultiplePatchesByPosition_notByOrderInJson() {
        String original = "事件A发生。他坐下了。事件B随后发生。他抬起头。";
        // 故意倒序给出：套用必须按"在原稿中的位置"，与 JSON 里的顺序无关
        String json = "{\"patches\":["
                + "{\"anchor\":\"他抬起头。\",\"replacement\":\"他抬起头，看向门口。\"},"
                + "{\"anchor\":\"他坐下了。\",\"replacement\":\"他坐下了，手按在剑柄上。\"}]}";
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(json);

        ChapterReviseService.ReviseResult result = patchService().revise(
                storyVO(), planItem(), original,
                List.of(blockingIssue()), null, null, emptyStyleStat(), 1, null);

        assertTrue(result.decision().isAccepted());
        assertEquals("事件A发生。他坐下了，手按在剑柄上。事件B随后发生。他抬起头，看向门口。",
                result.revisedContent().getContent());
    }

    @Test
    void patch_fallsBackToRewrite_whenModelGivesNoPatches() {
        // 模型判定"不是局部问题"（空补丁）＝ 正常回退信号，不是错误
        when(llmGateway.complete(any(), any(LlmCall.class)))
                .thenReturn("{\"patches\":[]}", REWRITE_JSON);

        ChapterReviseService.ReviseResult result = patchService().revise(
                storyVO(), planItem(), PATCH_ORIGINAL,
                List.of(blockingIssue()), null, null, emptyStyleStat(), 1, null);

        assertTrue(result.decision().isAccepted());
        assertEquals("小陈拔剑，事件A发生，事件B也发生，结尾呼应钩子。", result.revisedContent().getContent());
        assertTrue(result.decision().getReason().contains("采纳闸门"));
    }

    @Test
    void patch_fallsBackToRewrite_whenAnchorNotFound() {
        // 模型改写过锚点（不是逐字复制原文）→ 套用失败，绝不模糊匹配
        String json = "{\"patches\":[{\"anchor\":\"他坐了下来。\",\"replacement\":\"他坐下了。\"}]}";
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(json, REWRITE_JSON);

        ChapterReviseService.ReviseResult result = patchService().revise(
                storyVO(), planItem(), PATCH_ORIGINAL,
                List.of(blockingIssue()), null, null, emptyStyleStat(), 1, null);

        assertEquals("小陈拔剑，事件A发生，事件B也发生，结尾呼应钩子。", result.revisedContent().getContent());
    }

    @Test
    void patch_fallsBackToRewrite_whenAnchorAppearsMoreThanOnce() {
        // 锚点不唯一 → 无法确定该改哪一处，整份作废（套错位置比不修更糟）
        String original = "事件A发生。他坐下了。事件B发生。他坐下了。";
        String json = "{\"patches\":[{\"anchor\":\"他坐下了。\",\"replacement\":\"他坐稳了。\"}]}";
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(json, REWRITE_JSON);

        ChapterReviseService.ReviseResult result = patchService().revise(
                storyVO(), planItem(), original,
                List.of(blockingIssue()), null, null, emptyStyleStat(), 1, null);

        assertEquals("小陈拔剑，事件A发生，事件B也发生，结尾呼应钩子。", result.revisedContent().getContent());
    }

    @Test
    void patch_fallsBackToRewrite_whenAnchorsOverlap() {
        // 一个锚点套住另一个 → "谁先改"会改变结果，拒绝套用
        String original = "事件A发生。他坐下了。事件B发生。";
        String json = "{\"patches\":["
                + "{\"anchor\":\"事件A发生。他坐下了。\",\"replacement\":\"事件A发生。他坐下了，手按在剑柄上。\"},"
                + "{\"anchor\":\"他坐下了。\",\"replacement\":\"他坐稳了。\"}]}";
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(json, REWRITE_JSON);

        ChapterReviseService.ReviseResult result = patchService().revise(
                storyVO(), planItem(), original,
                List.of(blockingIssue()), null, null, emptyStyleStat(), 1, null);

        assertEquals("小陈拔剑，事件A发生，事件B也发生，结尾呼应钩子。", result.revisedContent().getContent());
    }

    @Test
    void patch_fallsBackToRewrite_whenPatchChangesNothing() {
        // 空操作补丁：套用"成功"但正文一字未变。若放过，会把"什么也没修"当成"修完了"
        String json = "{\"patches\":[{\"anchor\":\"他坐下了。\",\"replacement\":\"他坐下了。\"}]}";
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(json, REWRITE_JSON);

        ChapterReviseService.ReviseResult result = patchService().revise(
                storyVO(), planItem(), PATCH_ORIGINAL,
                List.of(blockingIssue()), null, null, emptyStyleStat(), 1, null);

        assertEquals("小陈拔剑，事件A发生，事件B也发生，结尾呼应钩子。", result.revisedContent().getContent());
    }

    @Test
    void patch_fallsBackToRewrite_whenPatchedTextFailsGate() {
        // 补丁把大段正文删掉 → 字数闸挡住。补丁与整章重写走的是同一套闸门，没有豁免
        String json = "{\"patches\":[{\"anchor\":\"小陈拔剑，事件A发生，事件B随后发生。\",\"replacement\":\"\"}]}";
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(json, REWRITE_JSON);

        ChapterReviseService.ReviseResult result = patchService().revise(
                storyVO(), planItem(), PATCH_ORIGINAL,
                List.of(blockingIssue()), null, null, emptyStyleStat(), 1, null);

        assertEquals("小陈拔剑，事件A发生，事件B也发生，结尾呼应钩子。", result.revisedContent().getContent());
    }

    @Test
    void patch_disabledByConfig_goesStraightToRewrite() {
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(REWRITE_JSON);

        ChapterReviseService.ReviseResult result = service.revise(
                storyVO(), planItem(), PATCH_ORIGINAL,
                List.of(blockingIssue()), null, null, emptyStyleStat(), 1, null);

        assertTrue(result.decision().isAccepted());
        // 关掉补丁时只有一次调用，且是整章重写那条路径
        ArgumentCaptor<LlmCall> captor = ArgumentCaptor.forClass(LlmCall.class);
        verify(llmGateway).complete(any(), captor.capture());
        assertTrue(captor.getValue().getLabel().startsWith("revise-"));
    }

    @Test
    void patch_promptDemandsVerbatimUniqueAnchor_andSharesBusinessContext() {
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(PATCH_ONLY_JSON);

        patchService().revise(storyVO(), planItem(), PATCH_ORIGINAL,
                List.of(blockingIssue()), "账本内容", null, emptyStyleStat(), 1, null);

        ArgumentCaptor<LlmCall> captor = ArgumentCaptor.forClass(LlmCall.class);
        verify(llmGateway).complete(any(), captor.capture());
        String prompt = captor.getValue().getUserPrompt();

        assertTrue(prompt.contains("定向补丁"));
        assertTrue(prompt.contains("逐字存在、且只出现一次"), "必须讲清锚点的唯一性要求");
        assertTrue(prompt.contains("anchor 会被程序逐字索引"), "要让它知道改一个字即整份丢弃的代价");
        assertTrue(prompt.contains("{\"patches\":[]}"), "必须显式给出'放弃'的出口，否则会硬凑不合法补丁");
        // 两种形态共用业务上下文：补丁 prompt 同样带关键事件红线与账本
        assertTrue(prompt.contains("【关键事件红线】"));
        assertTrue(prompt.contains("账本内容"));
        // 但不应带整章重写的输出 schema
        assertFalse(prompt.contains("请严格按照以下 JSON 格式输出整章："));
    }

    private ChapterIssueEntity blockingIssue() {
        return ChapterIssueEntity.builder()
                .dimension("consistency")
                .severity("BLOCKING")
                .evidence("小陈已死又出场")
                .description("事实矛盾")
                .suggestion("修复")
                .build();
    }

    private StoryVO storyVO() {
        StoryVO storyVO = new StoryVO();
        StoryVO.Module module = new StoryVO.Module();
        StoryVO.Module.ChatModel chatModel = new StoryVO.Module.ChatModel();
        chatModel.setMaxTokens(16384L);
        module.setChatModel(chatModel);
        storyVO.setModule(module);
        return storyVO;
    }

    private ChapterPlanItemEntity planItem() {
        ChapterPlanItemEntity item = new ChapterPlanItemEntity();
        item.setChapterNo(1);
        item.setTitle("测试章");
        item.setGoal("测试");
        item.setKeyEvents(List.of("事件A", "事件B"));
        return item;
    }

    private StyleStatEntity emptyStyleStat() {
        return StyleStatEntity.builder()
                .usedSentences(new ArrayList<>())
                .repeatedSentences(new ArrayList<>())
                .fatigueWords(new LinkedHashMap<>())
                .build();
    }

    private StyleStatEntity styleStatWithRepeatedSentence() {
        StyleStatEntity stat = emptyStyleStat();
        // 先让 service 按自己的切分规则记录长句，保证测试句子与运行时一致
        styleStatService.merge(stat, "这也是一句很长的话用来凑字数");
        return stat;
    }

}
