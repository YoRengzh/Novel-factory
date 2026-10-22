package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.quality.EvidenceMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账本挂起裁决（L1 兜底通道）测试。
 *
 * <p>核心是两条：<b>能救的救回来</b>（按来源账本写回、留痕），以及
 * <b>救不回来的一条都不许混进账本</b>——模型声称有据但引文过不了机械校验时必须按不支持处理。
 * 后者是本通道存在的安全前提：裁决层只能补全证据，不能凭空引入事实。
 */
class LedgerAdjudicateServiceTest {

    /** 议事厅里没人说话。周伯通负责协调灵石与人力，断臂绷带透着暗红血迹。他退到一旁。 */
    private static final String CONTENT =
            "议事厅里没人说话。周伯通负责协调灵石与人力，断臂绷带透着暗红血迹。他退到一旁。";

    private LlmGateway llmGateway;
    private StoryProperties properties;
    private LedgerAdjudicateService service;

    @BeforeEach
    void setUp() {
        llmGateway = mock(LlmGateway.class);
        properties = new StoryProperties();
        StoryProperties.LedgerAdjudicateProperties props = new StoryProperties.LedgerAdjudicateProperties();
        props.setEnabled(true);
        props.setMaxItems(12);
        properties.setLedgerAdjudicate(props);
        service = new LedgerAdjudicateService(llmGateway, properties);
    }

    @Test
    @DisplayName("引文可机械校验 → 救回并按来源账本写回，留痕 adjudicated")
    void rescuesItemWhenQuoteVerifies() {
        ChapterSummaryEntity summary = summaryWithPendingState("character", "phrase-partial");
        stubModel("{\"verdicts\":[{\"index\":1,\"supported\":true,"
                + "\"quote\":\"断臂绷带透着暗红血迹\",\"note\":null}]}");

        int rescued = service.adjudicate(storyVO(), summary, CONTENT);

        assertEquals(1, rescued);
        // 写回角色账本
        assertEquals(1, summary.getCharacterStates().size());
        ChapterSummaryEntity.StateEntry entry = summary.getCharacterStates().get(0);
        assertEquals("周伯通", entry.getName());
        // 证据被替换为可逐字核对的新引文
        assertEquals("断臂绷带透着暗红血迹", entry.getEvidence());
        assertTrue(EvidenceMatch.classify(entry.getEvidence(), CONTENT).isAccepted());
        // 留痕档位，供观测层统计「LLM 救回」占比
        assertEquals(LedgerAdjudicateService.ADJUDICATED_TIER, entry.getEvidenceTier());
        // 来源标记已消费
        assertNull(entry.getSourceAccount());
        // 挂起层清空（空列表写 null，保持原有落盘形态）
        assertNull(summary.getPendingFacts());
    }

    @Test
    @DisplayName("模型声称有据但引文过不了机械校验 → 按不支持处理，条目留在挂起层")
    void rejectsQuoteThatFailsMechanicalCheck() {
        ChapterSummaryEntity summary = summaryWithPendingState("character", "phrase-partial");
        // 模型编了一条正文里没有的引文——裁决层不许把它放进账本
        stubModel("{\"verdicts\":[{\"index\":1,\"supported\":true,"
                + "\"quote\":\"这句话在正文里根本不存在\",\"note\":null}]}");

        int rescued = service.adjudicate(storyVO(), summary, CONTENT);

        assertEquals(0, rescued);
        assertNull(summary.getCharacterStates());
        assertEquals(1, summary.getPendingFacts().size());
        // 原样保留：档位与来源都不被改写
        assertEquals("phrase-partial", summary.getPendingFacts().get(0).getEvidenceTier());
        assertEquals("character", summary.getPendingFacts().get(0).getSourceAccount());
    }

    @Test
    @DisplayName("模型判不支持 → 条目原样留在挂起层（不静默删除）")
    void keepsPendingWhenModelSaysUnsupported() {
        ChapterSummaryEntity summary = summaryWithPendingState("character", "phrase-partial");
        stubModel("{\"verdicts\":[{\"index\":1,\"supported\":false,\"quote\":null,"
                + "\"note\":\"正文中无支撑该结论的原文\"}]}");

        assertEquals(0, service.adjudicate(storyVO(), summary, CONTENT));
        assertEquals(1, summary.getPendingFacts().size());
    }

    @Test
    @DisplayName("声称有据但漏写引文 → 按不支持处理，不得借空引文入账")
    void rejectsSupportedWithoutQuote() {
        ChapterSummaryEntity summary = summaryWithPendingState("character", "phrase-partial");
        // classify("") 的契约是“不判定”（返回 EXACT，由调用方先校验非空）：
        // 这里若不拦，模型只要回 {"index":1,"supported":true} 就能把编造事实救回账本
        stubModel("{\"verdicts\":[{\"index\":1,\"supported\":true}]}");

        assertEquals(0, service.adjudicate(storyVO(), summary, CONTENT));
        assertNull(summary.getCharacterStates());
        assertEquals(1, summary.getPendingFacts().size());
    }

    @Test
    @DisplayName("救回的条目按来源账本回写：物品 → itemStates（不写错账）")
    void routesRescuedItemBackToItsOwnAccount() {
        ChapterSummaryEntity summary = summaryWithPendingState("item", "phrase-partial");
        stubModel("{\"verdicts\":[{\"index\":1,\"supported\":true,"
                + "\"quote\":\"断臂绷带透着暗红血迹\",\"note\":null}]}");

        assertEquals(1, service.adjudicate(storyVO(), summary, CONTENT));

        assertNull(summary.getCharacterStates(), "不应写到角色账本");
        assertNotNull(summary.getItemStates());
        assertEquals("周伯通", summary.getItemStates().get(0).getName());
    }

    @Test
    @DisplayName("NO_MATCH（全库无据）不进入裁决：不发起调用，保持逐出")
    void neverAdjudicatesNoMatchTier() {
        ChapterSummaryEntity summary = summaryWithPendingState("character", "no-match");

        assertEquals(0, service.adjudicate(storyVO(), summary, CONTENT));

        verify(llmGateway, never()).complete(any(), any());
        assertEquals(1, summary.getPendingFacts().size());
        assertEquals("no-match", summary.getPendingFacts().get(0).getEvidenceTier());
    }

    @Test
    @DisplayName("一致性事实完全未附证据（档位为空）→ 可裁决并写回 consistencyFacts")
    void rescuesConsistencyFactWithBlankEvidence() {
        ChapterSummaryEntity summary = new ChapterSummaryEntity();
        summary.setChapterNo(7);
        summary.setConsistencyFacts(new ArrayList<>());
        ChapterSummaryEntity.ConsistencyFact fact = new ChapterSummaryEntity.ConsistencyFact();
        fact.setType("INJURY");
        fact.setSubject("周伯通");
        fact.setValue("右臂重伤");
        summary.setPendingConsistencyFacts(new ArrayList<>(List.of(fact)));
        stubModel("{\"verdicts\":[{\"index\":1,\"supported\":true,"
                + "\"quote\":\"断臂绷带透着暗红血迹\",\"note\":null}]}");

        assertEquals(1, service.adjudicate(storyVO(), summary, CONTENT));

        assertEquals(1, summary.getConsistencyFacts().size());
        assertEquals("断臂绷带透着暗红血迹", summary.getConsistencyFacts().get(0).getEvidence());
        assertEquals(LedgerAdjudicateService.ADJUDICATED_TIER,
                summary.getConsistencyFacts().get(0).getEvidenceTier());
        assertNull(summary.getPendingConsistencyFacts());
    }

    @Test
    @DisplayName("单章裁决有条数上限：超出部分本章不裁、仍留在挂起层")
    void capsItemsPerCallWithoutLosingTheRest() {
        properties.getLedgerAdjudicate().setMaxItems(1);
        ChapterSummaryEntity summary = new ChapterSummaryEntity();
        summary.setChapterNo(3);
        summary.setPendingFacts(new ArrayList<>(List.of(
                pendingState("周伯通"), pendingState("陆沉"))));
        stubModel("{\"verdicts\":[{\"index\":1,\"supported\":true,"
                + "\"quote\":\"断臂绷带透着暗红血迹\",\"note\":null}]}");

        int rescued = service.adjudicate(storyVO(), summary, CONTENT);

        assertEquals(1, rescued, "上限为 1 时只裁一条");
        // 未被裁决的那条必须原样保留，不得因超限而丢失
        assertEquals(1, summary.getPendingFacts().size());
        assertEquals("陆沉", summary.getPendingFacts().get(0).getName());
    }

    @Test
    @DisplayName("调用异常 → fail-soft：挂起层原样保留，不反噬生成流程")
    void keepsPendingLayerIntactOnException() {
        ChapterSummaryEntity summary = summaryWithPendingState("character", "phrase-partial");
        when(llmGateway.complete(any(), any())).thenThrow(new RuntimeException("boom"));

        assertEquals(0, service.adjudicate(storyVO(), summary, CONTENT));
        assertEquals(1, summary.getPendingFacts().size());
        assertNull(summary.getCharacterStates());
    }

    @Test
    @DisplayName("输出无判定列表 → 挂起层原样保留")
    void keepsPendingLayerIntactOnEmptyOutput() {
        ChapterSummaryEntity summary = summaryWithPendingState("character", "phrase-partial");
        stubModel("{\"verdicts\":null}");

        assertEquals(0, service.adjudicate(storyVO(), summary, CONTENT));
        assertEquals(1, summary.getPendingFacts().size());
    }

    @Test
    @DisplayName("开关关闭 → 零调用（零 LLM 成本模式）")
    void disabledMakesNoCall() {
        properties.getLedgerAdjudicate().setEnabled(false);
        ChapterSummaryEntity summary = summaryWithPendingState("character", "phrase-partial");

        assertEquals(0, service.adjudicate(storyVO(), summary, CONTENT));

        verify(llmGateway, never()).complete(any(), any());
        assertEquals(1, summary.getPendingFacts().size());
    }

    @Test
    @DisplayName("无挂起项 → 零调用")
    void noPendingItemsMakesNoCall() {
        ChapterSummaryEntity summary = new ChapterSummaryEntity();
        summary.setChapterNo(1);

        assertEquals(0, service.adjudicate(storyVO(), summary, CONTENT));
        verify(llmGateway, never()).complete(any(), any());
    }

    // ------------------------------------------------------------ 素材

    private ChapterSummaryEntity.StateEntry pendingState(String name) {
        ChapterSummaryEntity.StateEntry entry = new ChapterSummaryEntity.StateEntry();
        entry.setName(name);
        entry.setStatus("右臂缠绷带；负责协调灵石与人力");
        // 真实形态：模型改写了措辞，导致第二个短语无法逐字定位 → PHRASE_PARTIAL
        entry.setEvidence("周伯通负责协调灵石与人力，断臂绷带渗出暗红血迹");
        entry.setEvidenceTier(EvidenceMatch.Tier.PHRASE_PARTIAL.getCode());
        entry.setSourceAccount("character");
        return entry;
    }

    private ChapterSummaryEntity summaryWithPendingState(String account, String tier) {
        ChapterSummaryEntity summary = new ChapterSummaryEntity();
        summary.setChapterNo(42);
        summary.setTitle("议事厅");
        ChapterSummaryEntity.StateEntry entry = pendingState("周伯通");
        entry.setSourceAccount(account);
        entry.setEvidenceTier(tier);
        summary.setPendingFacts(new ArrayList<>(List.of(entry)));
        return summary;
    }

    private void stubModel(String json) {
        when(llmGateway.complete(any(), any())).thenReturn(json);
    }

    private StoryVO storyVO() {
        StoryVO vo = new StoryVO();
        vo.setModule(new StoryVO.Module());
        return vo;
    }
}
