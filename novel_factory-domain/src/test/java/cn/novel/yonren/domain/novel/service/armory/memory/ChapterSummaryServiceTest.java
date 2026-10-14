package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 章节摘要服务测试：结构化解析回填、模型异常/解析失败质量门硬失败、抢救残缺记忆
 */
class ChapterSummaryServiceTest {

    private LlmGateway llmGateway;
    private ChapterSummaryService service;

    @BeforeEach
    void setUp() {
        llmGateway = mock(LlmGateway.class);
        service = new ChapterSummaryService(llmGateway);
    }

    @Test
    void summarize_parsesStructuredOutputAndBackfills() {
        String json = "{\"summary\":\"小陈被废去丹田后，古物吞噬灵气为他重塑经脉。\","
                + "\"characterStates\":[{\"name\":\"小陈\",\"status\":\"炼气一层，藏身石缝\",\"evidence\":\"正文\"},"
                + "{\"name\":\"赵阔\",\"status\":\"派人监视小陈\",\"evidence\":\"正文\"}],"
                + "\"itemStates\":[{\"name\":\"古物\",\"status\":\"异动显威\",\"evidence\":\"正文\"}],"
                + "\"factionStates\":[],"
                + "\"foreshadowingNew\":[\"体内雷纹的来历\"],"
                + "\"foreshadowingResolved\":[],"
                + "\"timePoint\":\"被废当夜，外门石缝角落\","
                + "\"continuityConflicts\":[\"正文写小陈后腰被踹，账本无此情节\"]}";
        stubModel(json);

        ChapterSummaryEntity summary = service.summarize(storyVO(), planItem(2), "第2章正文……", 7, "【角色账本】\n- 小陈：重伤");

        assertNotNull(summary);
        assertEquals(7, summary.getChapterNo());
        assertEquals("古物奇谈", summary.getTitle());
        assertEquals(2, summary.getCharacterStates().size());
        assertEquals("炼气一层，藏身石缝", summary.getCharacterStates().get(0).getStatus());
        assertEquals(1, summary.getItemStates().size());
        assertEquals(List.of("体内雷纹的来历"), summary.getForeshadowingNew());
        assertEquals("被废当夜，外门石缝角落", summary.getTimePoint());
        assertEquals(List.of("正文写小陈后腰被踹，账本无此情节"), summary.getContinuityConflicts());
    }

    @Test
    void summarize_modelErrorThrowsQualityGate() {
        when(llmGateway.complete(any(), any(LlmCall.class))).thenThrow(new RuntimeException("timeout"));

        AppException e = assertThrows(AppException.class,
                () -> service.summarize(storyVO(), planItem(3), "第3章正文……", 3, null));

        assertTrue(e.getInfo().contains("摘要彻底失败"));
    }

    @Test
    void summarizeSeparatesUnverifiedConsistencyFacts() {
        String json = "{\"summary\":\"守军抵达北境。\",\"characterStates\":[],"
                + "\"consistencyFacts\":["
                + "{\"type\":\"NUMBER\",\"subject\":\"北境守军\",\"value\":\"三千人\",\"scope\":\"北境\",\"evidence\":\"三千守军列阵关前\"},"
                + "{\"type\":\"TERM\",\"subject\":\"天门关\",\"value\":\"天门城\",\"evidence\":\"正文里不存在的证据\"}]}";
        stubModel(json);

        ChapterSummaryEntity summary = service.summarize(
                storyVO(), planItem(2), "三千守军列阵关前。", 2, null);

        assertEquals(1, summary.getConsistencyFacts().size());
        assertEquals("NUMBER", summary.getConsistencyFacts().get(0).getType());
        assertEquals(1, summary.getPendingConsistencyFacts().size());
        assertEquals("TERM", summary.getPendingConsistencyFacts().get(0).getType());
    }

    @Test
    void summarize_invalidJsonThrowsQualityGate() {
        stubModel("抱歉，我无法按要求输出。");

        AppException e = assertThrows(AppException.class,
                () -> service.summarize(storyVO(), planItem(3), "第3章正文……", 3, null));

        assertTrue(e.getInfo().contains("摘要彻底失败"));
    }

    @Test
    void summarize_repairsDanglingCommaWithoutRetry() {
        // 第 5 章实测故障：对象内悬空逗号 ",}"
        String bad = "{\"summary\":\"小陈夜探废矿。\",\"characterStates\":[{\"name\":\"小陈\",\"status\":\"重伤，藏身石缝\",\"evidence\":\"正文\",}],\"timePoint\":\"当夜废矿\"}";
        stubModel(bad);

        ChapterSummaryEntity summary = service.summarize(storyVO(), planItem(2), "正文", 2, null);

        // 文本修复后一次解析成功，无需重试 LLM
        assertNotNull(summary);
        assertEquals(2, summary.getChapterNo());
        assertEquals(1, summary.getCharacterStates().size());
        assertEquals("当夜废矿", summary.getTimePoint());
        verify(llmGateway, times(1)).complete(any(), any(LlmCall.class));
    }

    @Test
    void summarize_salvagesSummaryFromMalformedOutput() {
        // 第 9 章实测故障：数组未闭合，timePoint/continuityConflicts 以 {name,status} 对象错位塞进数组
        String bad = "{\"summary\":\"小陈深入废矿，遭遇巡山弟子搜捕。\","
                + "\"characterStates\":[{\"name\":\"小陈\",\"status\":\"重伤\"}],"
                + "\"foreshadowingNew\":[\"矿洞深处雷鸣\"],"
                + "\"foreshadowingResolved\":[\"古物可稳伤\"],"
                + "{\"name\":\"timePoint\",\"status\":\"当夜废矿\"},{\"name\":\"continuityConflicts\",\"status\":\"[]\"}]}";
        stubModel(bad);

        ChapterSummaryEntity summary = service.summarize(storyVO(), planItem(9), "正文", 9, null);

        // 结构错乱无法修复 → 两次尝试后降级抢救 summary，剧情事实至少入账，且带 partial 残缺标记
        assertNotNull(summary);
        assertEquals(9, summary.getChapterNo());
        assertEquals("比试前夜", summary.getTitle());
        assertEquals("小陈深入废矿，遭遇巡山弟子搜捕。", summary.getSummary());
        assertNull(summary.getCharacterStates());
        assertTrue(summary.isPartial());
        verify(llmGateway, times(2)).complete(any(), any(LlmCall.class));
    }

    @Test
    void summarize_retriesAndRecoversOnSecondCall() {
        // 第一次为修复也救不回的结构错乱（对象混入字符串数组且数组未闭合），重试后恢复正常
        String bad = "{\"summary\":\"第一次输出错乱。\",\"characterStates\":[{\"name\":\"小陈\",\"status\":\"重伤\"}],"
                + "\"foreshadowingResolved\":[\"伏笔A\"],{\"name\":\"timePoint\",\"status\":\"当夜\"}]}";
        String good = "{\"summary\":\"剧情完整版。\",\"characterStates\":[],\"timePoint\":\"当夜\"}";
        stubModelSequence(bad, good);

        ChapterSummaryEntity summary = service.summarize(storyVO(), planItem(5), "正文", 5, null);

        assertNotNull(summary);
        assertEquals("剧情完整版。", summary.getSummary());
        assertEquals(5, summary.getChapterNo());
        verify(llmGateway, times(2)).complete(any(), any(LlmCall.class));
    }

    @Test
    void summarize_stripsMarkdownFence() {
        stubModel("```json\n{\"summary\":\"剧情。\",\"characterStates\":[],\"timePoint\":\"当夜\"}\n```");

        ChapterSummaryEntity summary = service.summarize(storyVO(), planItem(3), "正文", 3, null);

        assertNotNull(summary);
        assertEquals("剧情。", summary.getSummary());
    }

    @Test
    void summarize_validatesForeshadowSeedExcerptAgainstContent() {
        // excerpt 必须是当章正文连续子串：编造引用置空，真实引用保留，超长截断
        String json = "{\"summary\":\"剧情。\",\"characterStates\":[],"
                + "\"foreshadowingNew\":[\"体内雷纹的来历\"],"
                + "\"foreshadowSeeds\":["
                + "{\"content\":\"体内雷纹的来历\",\"excerpt\":\"雷纹在他丹田碎裂时烫了一下\",\"importance\":4},"
                + "{\"content\":\"编造的伏笔\",\"excerpt\":\"这句正文里根本没有\"}],"
                + "\"timePoint\":\"当夜\"}";
        stubModel(json);

        ChapterSummaryEntity summary = service.summarize(storyVO(), planItem(2),
                "小陈跌坐石缝，怀中古物发烫，雷纹在他丹田碎裂时烫了一下。", 2, null);

        assertNotNull(summary);
        assertEquals(2, summary.getForeshadowSeeds().size());
        assertEquals("雷纹在他丹田碎裂时烫了一下", summary.getForeshadowSeeds().get(0).getExcerpt());
        assertEquals(4, summary.getForeshadowSeeds().get(0).getImportance());
        assertNull(summary.getForeshadowSeeds().get(1).getExcerpt());
        assertNull(summary.getForeshadowSeeds().get(1).getImportance());
        assertEquals("编造的伏笔", summary.getForeshadowSeeds().get(1).getContent());
    }

    @Test
    void summarize_verifiedEvidenceKeepsEntryInLedger() {
        // evidence 是当章正文连续子串：状态留在账本，pendingFacts 为空
        String json = "{\"summary\":\"剧情。\","
                + "\"characterStates\":[{\"name\":\"小陈\",\"status\":\"重伤\","
                + "\"evidence\":\"小陈咬破舌尖，强行催动古物\"}],"
                + "\"itemStates\":[{\"name\":\"古物\",\"status\":\"镜面裂开细纹\","
                + "\"evidence\":\"镜面裂开一道细纹\"}],"
                + "\"timePoint\":\"当夜\"}";
        stubModel(json);

        ChapterSummaryEntity summary = service.summarize(storyVO(), planItem(2),
                "小陈咬破舌尖，强行催动古物。镜面裂开一道细纹。", 2, null);

        assertEquals(1, summary.getCharacterStates().size());
        assertEquals(1, summary.getItemStates().size());
        assertNull(summary.getPendingFacts());
    }

    @Test
    void summarize_fabricatedEvidenceQuarantinesEntry() {
        // evidence 非当章正文子串（编造/漂移）：该条移出三账本，隔离至待人工确认
        String json = "{\"summary\":\"剧情。\","
                + "\"characterStates\":["
                + "{\"name\":\"小陈\",\"status\":\"重伤\",\"evidence\":\"小陈咬破舌尖，强行催动古物\"},"
                + "{\"name\":\"赵阔\",\"status\":\"已飞升\",\"evidence\":\"正文里根本没有这句话\"}],"
                + "\"itemStates\":[{\"name\":\"古物\",\"status\":\"碎裂\",\"evidence\":\"碎成了八瓣\"}],"
                + "\"timePoint\":\"当夜\"}";
        stubModel(json);

        ChapterSummaryEntity summary = service.summarize(storyVO(), planItem(2),
                "小陈咬破舌尖，强行催动古物。镜面裂开一道细纹。", 2, null);

        // 可验证的入账本，编造的隔离待人工确认（不入账本前缀）
        assertEquals(1, summary.getCharacterStates().size());
        assertEquals("小陈", summary.getCharacterStates().get(0).getName());
        assertEquals(0, summary.getItemStates().size());
        assertNotNull(summary.getPendingFacts());
        assertEquals(2, summary.getPendingFacts().size());
        assertEquals("赵阔", summary.getPendingFacts().get(0).getName());
        assertEquals("古物", summary.getPendingFacts().get(1).getName());
    }

    @Test
    void summarize_missingEvidenceGoesToPendingForAdjudication() {
        // 修正：未附 evidence（降级路径/模型未遵从）**不再直接入账**。
        // 原实现是后门——模型只要不写引文，状态就全部绕过反编造门进账本，
        // 且裁决层永远看不到它们。现转入挂起层：
        //  · 必须带 sourceAccount（否则裁决回写按 CHARACTER 兜底，物品/势力会被写错账）
        //  · evidenceTier 留空（adjudicable 对空档位放行 → 走 L1 裁决拟稿并经 EvidenceMatch 复核）
        String json = "{\"summary\":\"剧情。\","
                + "\"characterStates\":[{\"name\":\"小陈\",\"status\":\"重伤\"}],"
                + "\"timePoint\":\"当夜\"}";
        stubModel(json);

        ChapterSummaryEntity summary = service.summarize(storyVO(), planItem(2), "第2章正文……", 2, null);

        assertTrue(summary.getCharacterStates() == null || summary.getCharacterStates().isEmpty(),
                "未附证据的状态不得直接入账本");
        assertNotNull(summary.getPendingFacts());
        assertEquals(1, summary.getPendingFacts().size());
        ChapterSummaryEntity.StateEntry pending = summary.getPendingFacts().get(0);
        assertEquals("小陈", pending.getName());
        assertEquals(ChapterSummaryEntity.AccountKind.CHARACTER.getCode(), pending.getSourceAccount(),
                "挂起条目必须记录来源账本，裁决回写才不会错账");
        assertTrue(pending.getEvidenceTier() == null || pending.getEvidenceTier().isBlank(),
                "档位留空才可进入裁决（adjudicable 对空档位放行）");
    }

    @Test
    void summarize_promptCarriesPendingForeshadowLedgerForVerbatimResolution() {
        String json = "{\"summary\":\"剧情。\",\"characterStates\":[],\"timePoint\":\"当夜\"}";
        stubModel(json);

        service.summarize(storyVO(), planItem(2), "正文", 2, null,
                List.of("洛恩腕侧旧伤疤与旧库残页上的半枚印记相似"));

        ArgumentCaptor<LlmCall> captor = ArgumentCaptor.forClass(LlmCall.class);
        verify(llmGateway).complete(any(), captor.capture());
        String prompt = captor.getValue().getUserPrompt();
        assertTrue(prompt.contains("【此前埋设、尚未回收的伏笔账】"));
        assertTrue(prompt.contains("洛恩腕侧旧伤疤与旧库残页上的半枚印记相似"));
        // 回收条目要求逐字沿用原文，账本才能按文本匹配核销
        assertTrue(prompt.contains("逐字沿用账中描述"));
        assertTrue(prompt.contains("同一条内容严禁同时出现在 foreshadowingNew 与 foreshadowingResolved"));
        // 重要度评分锚点与防通胀限量
        assertTrue(prompt.contains("importance 为该伏笔对主线的重要度评分"));
        assertTrue(prompt.contains("每章评 5 分的伏笔至多 1 条"));
        // 状态事实证据要求：三账本每条状态必须附逐字原文引用
        assertTrue(prompt.contains("evidence"));
        assertTrue(prompt.contains("严禁改写、拼接或概括"));
    }

    /**
     * 回收登记是一项**义务**，不是可选项（2026-10-10 强化）。
     *
     * <p>原prompt只写了约束（"只能从账中选取""逐字沿用"），没有正面义务、没有例子，
     * 也没有解析后的机械校验。结果是漏记——而漏记的后果不是"少记一笔"：该线永远关不掉账，
     * 会在后续每一段被反复要求回收。体检实测「清账静默兑现 80 条（占弃置 66%）」即为此。
     */
    @Test
    void summarize_promptStatesResolutionDutyWithExamples() {
        stubModel("{\"summary\":\"剧情。\",\"characterStates\":[],\"timePoint\":\"当夜\"}");

        service.summarize(storyVO(), planItem(2), "正文", 2, null,
                List.of("洛恩腕侧旧伤疤与旧库残页上的半枚印记相似"));

        ArgumentCaptor<LlmCall> captor = ArgumentCaptor.forClass(LlmCall.class);
        verify(llmGateway).complete(any(), captor.capture());
        String prompt = captor.getValue().getUserPrompt();
        assertTrue(prompt.contains("这是一项义务，不是可选项"), prompt);
        assertTrue(prompt.contains("必须逐条登记"), prompt);
        assertTrue(prompt.contains("该线永远关不掉账"), "要写清漏记的后果：" + prompt);
        // 判断口径必须写出来，否则模型仍会按"物件是否出现"误判
        assertTrue(prompt.contains("看**状态是否变化**，不是看物件是否出现"), prompt);
        // 正反例子齐备（✅/❌），否则等于只给了约束没给做法。ID 回抄为首选路径
        assertTrue(prompt.contains("✅ 正确一（首选）"), prompt);
        assertTrue(prompt.contains("✅ 正确二（账中无 ID 时）"), prompt);
        assertTrue(prompt.contains("❌ 错误一："), prompt);
        assertTrue(prompt.contains("❌ 错误二："), prompt);
        assertTrue(prompt.contains("老账恰恰最需要登记"), "要点名「老线可以留空」这个误判：" + prompt);
    }

    /**
     * 回收清单的机械兜底：去空、去重，但**刻意不丢弃**未匹配账本的条目。
     *
     * <p>真实兑现的措辞可能与账本不完全一致，误删等于永久丢掉一次回收——而回收一旦丢失，
     * 该线就再也不会关账。未命中只作观测信号（WARN），与体检的「清账静默兑现」同源。
     */
    @Test
    void summarize_sanitizesResolvedWithoutDroppingUnmatched() {
        stubModel("{\"summary\":\"剧情。\",\"characterStates\":[],\"timePoint\":\"当夜\","
                + "\"foreshadowingResolved\":[\" 旧笔记本里的借书卡 \",\"旧笔记本里的借书卡\",\"  \","
                + "\"用自己话写的另一条\"]}");

        ChapterSummaryEntity result = service.summarize(storyVO(), planItem(3), "正文", 3, null,
                List.of("旧笔记本里的借书卡"));

        List<String> resolved = result.getForeshadowingResolved();
        assertEquals(2, resolved.size(), "去空白、去重后应剩两条：" + resolved);
        assertTrue(resolved.contains("旧笔记本里的借书卡"), "应保留匹配账本的那条：" + resolved);
        assertTrue(resolved.contains("用自己话写的另一条"),
                "未匹配账本的条目不丢弃——误删会永久丢掉一次回收：" + resolved);
    }

    private void stubModel(String content) {
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(content);
    }

    private void stubModelSequence(String first, String second) {
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(first, second);
    }

    private StoryVO storyVO() {
        return new StoryVO();
    }

    private ChapterPlanItemEntity planItem(int chapterNo) {
        ChapterPlanItemEntity item = new ChapterPlanItemEntity();
        item.setChapterNo(chapterNo);
        item.setTitle(chapterNo == 2 ? "古物奇谈" : "比试前夜");
        return item;
    }

}
