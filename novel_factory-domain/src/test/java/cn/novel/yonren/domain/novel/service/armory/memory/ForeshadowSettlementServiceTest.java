package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowSettlementEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 卷末清账测试：按位置对齐回填账本原文（不信任模型回抄）、非法决策归弃置、
 * 条数不齐/解析失败 fail-soft、结算台账辅助查询（弃置原文提取 / 限期回收目标按段起点匹配）
 */
class ForeshadowSettlementServiceTest {

    private LlmGateway llmGateway;
    private ForeshadowSettlementService service;

    @BeforeEach
    void setUp() {
        llmGateway = mock(LlmGateway.class);
        service = new ForeshadowSettlementService(llmGateway,
                new ChapterMemoryService(new ForeshadowPriorityService()));
    }

    @Test
    void settle_alignsByPositionAndBackfillsLedgerContent() {
        stubModel("{\"results\":["
                + "{\"index\":1,\"decision\":\"RECOVER\",\"reason\":\"黑客学妹线第二阶段可自然接住\"},"
                + "{\"index\":2,\"decision\":\"VOID\",\"reason\":\"剧情已走远\"}]}");

        List<ForeshadowPriorityService.ScoredForeshadow> breakers = breakers();
        List<ForeshadowSettlementEntity.SettlementDecision> decisions =
                service.settle(module(), stage(), "21-63章 隐藏人设解锁", breakers, summaries(), new HashMap<>());

        assertEquals(2, decisions.size());
        // content 机械回填账本条目原文（不信任模型回抄的 index/content）
        assertEquals("旧笔记本里的陌生借书卡", decisions.get(0).getContent());
        assertEquals(3, decisions.get(0).getChapterNo());
        assertEquals(ForeshadowSettlementEntity.DECISION_RECOVER, decisions.get(0).getDecision());
        assertEquals("废弃的转发链路线索", decisions.get(1).getContent());
        assertEquals(ForeshadowSettlementEntity.DECISION_VOID, decisions.get(1).getDecision());
    }

    @Test
    void settle_normalizesIllegalDecisionToVoid() {
        stubModel("{\"results\":["
                + "{\"index\":1,\"decision\":\"随便吧\",\"reason\":\"\"},"
                + "{\"index\":2,\"decision\":\"RECOVER\",\"reason\":\"\"}]}");

        List<ForeshadowSettlementEntity.SettlementDecision> decisions =
                service.settle(module(), stage(), null, breakers(), summaries(), null);

        // 非法决策值按保守语义归为弃置（宁弃置不强收）
        assertEquals(ForeshadowSettlementEntity.DECISION_VOID, decisions.get(0).getDecision());
        assertTrue(decisions.get(0).getReason().contains("非法决策值"));
        // 合法 RECOVER 但理由缺失 → 默认理由补齐
        assertEquals(ForeshadowSettlementEntity.DECISION_RECOVER, decisions.get(1).getDecision());
        assertEquals("未给出连接点", decisions.get(1).getReason());
    }

    @Test
    void settle_isFailSoftOnCountMismatchOrGarbage() {
        // 条数不齐：期望 2 条只回 1 条
        stubModel("{\"results\":[{\"index\":1,\"decision\":\"VOID\",\"reason\":\"剧情已走远\"}]}");
        assertNull(service.settle(module(), stage(), null, breakers(), summaries(), null));

        // 解析失败
        stubModel("不是 JSON");
        assertNull(service.settle(module(), stage(), null, breakers(), summaries(), null));
    }

    /**
     * DONE 是第三个出口：线其实已在已写章节里兑现，回写排期并停止追问。
     *
     * <p>原 schema 只有 RECOVER / VOID，而逾期排期线的题面标注是「已过计划回收章——欠账」，
     * 模型只能选 RECOVER（= 结转下一阶段再收一次），闭环于是永远关不上。
     * 但 DONE 必须带章号才可用：没有章号无法核验、也无法回写排期表——降级回 RECOVER，
     * 因为误关会永久丢掉一条真伏笔。
     */
    @Test
    void settle_doneRequiresPayoffChapterOrDowngradesToRecover() {
        stubModel("{\"results\":["
                + "{\"index\":1,\"decision\":\"DONE\",\"reason\":\"第18章已确认\"},"
                + "{\"index\":2,\"decision\":\"DONE\",\"reason\":\"第30章已确认\",\"actualPayoffChapter\":30}]}");

        List<ForeshadowSettlementEntity.SettlementDecision> decisions =
                service.settle(module(), stage(), null, breakers(), summaries(), null);

        assertEquals(ForeshadowSettlementEntity.DECISION_RECOVER, decisions.get(0).getDecision(),
                "缺章号的 DONE 必须降级为 RECOVER（继续追踪）");
        assertNull(decisions.get(0).getActualPayoffChapter());
        assertTrue(decisions.get(0).getReason().contains("降级"), decisions.get(0).getReason());
        assertEquals(ForeshadowSettlementEntity.DECISION_DONE, decisions.get(1).getDecision());
        assertEquals(30, decisions.get(1).getActualPayoffChapter().intValue());
    }

    /**
     * PARKED 是第四个出口：有意慢慢养的长线保留追踪但不再每段催收——这正是「2026」那条被催了
     * 几十章、只催出一连串静态动作的解法。
     *
     * <p>但它必须说明"为什么是长线"：没有理由的在养与"被写忘了的线"无法区分，
     * 而两者的处置正好相反（前者不催、后者要催），故缺理由时降级回 RECOVER 继续追踪。
     */
    @Test
    void settle_parkedRequiresReasonOrDowngradesToRecover() {
        stubModel("{\"results\":["
                + "{\"index\":1,\"decision\":\"PARKED\",\"reason\":\"\"},"
                + "{\"index\":2,\"decision\":\"PARKED\",\"reason\":\"指向身份暴露危机，是贯穿全书的暗线\"}]}");

        List<ForeshadowSettlementEntity.SettlementDecision> decisions =
                service.settle(module(), stage(), null, breakers(), summaries(), null);

        assertEquals(ForeshadowSettlementEntity.DECISION_RECOVER, decisions.get(0).getDecision(),
                "缺理由的 PARKED 必须降级为 RECOVER（否则与「被写忘了的线」无法区分）");
        assertTrue(decisions.get(0).getReason().contains("降级"), decisions.get(0).getReason());
        assertEquals(ForeshadowSettlementEntity.DECISION_PARKED, decisions.get(1).getDecision());
    }

    @Test
    void settlementLedger_extractsVoidedContentsAndRecoverTargets() {
        ForeshadowSettlementEntity settlement = ForeshadowSettlementEntity.builder()
                .stageNo(1).stageEndChapter(20)
                .decisions(List.of(
                        decision("旧笔记本里的陌生借书卡", ForeshadowSettlementEntity.DECISION_RECOVER, "黑客线可接住"),
                        decision("废弃的转发链路线索", ForeshadowSettlementEntity.DECISION_VOID, "剧情已走远"),
                        decision("  ", ForeshadowSettlementEntity.DECISION_VOID, "空白条目")))
                .build();

        List<String> voided = ForeshadowSettlementEntity.voidedContents(Arrays.asList(settlement, null));
        assertEquals(1, voided.size());
        assertEquals("废弃的转发链路线索", voided.get(0));

        // 段起点-1 == 阶段末章（21 章段承接第 20 章出口）才命中
        assertEquals(1, ForeshadowSettlementEntity.recoverTargets(List.of(settlement), 21).size());
        assertTrue(ForeshadowSettlementEntity.recoverTargets(List.of(settlement), 22).isEmpty());
        assertTrue(ForeshadowSettlementEntity.voidedContents(null).isEmpty());
    }

    private ForeshadowSettlementEntity.SettlementDecision decision(String content, String decision, String reason) {
        return ForeshadowSettlementEntity.SettlementDecision.builder()
                .content(content).chapterNo(3).decision(decision).reason(reason).build();
    }

    /** 两条长期未填伏笔：重要度 4（非主线核心）且滞留足够久，tierOf 必然判 BREAKER */
    private List<ForeshadowPriorityService.ScoredForeshadow> breakers() {
        ChapterMemoryService.PendingForeshadow first =
                new ChapterMemoryService.PendingForeshadow(3, "旧笔记本里的陌生借书卡", "借书卡", 4);
        ChapterMemoryService.PendingForeshadow second =
                new ChapterMemoryService.PendingForeshadow(5, "废弃的转发链路线索", null, 4);
        return List.of(
                new ForeshadowPriorityService.ScoredForeshadow(first, 100, ForeshadowPriorityService.Tier.BREAKER),
                new ForeshadowPriorityService.ScoredForeshadow(second, 100, ForeshadowPriorityService.Tier.BREAKER));
    }

    private List<ChapterSummaryEntity> summaries() {
        return List.of(ChapterSummaryEntity.builder()
                .chapterNo(20).title("倒霉两小时").summary("陆泽在走廊连摔两跤。")
                .build());
    }

    private StageBlueprintEntity stage() {
        return StageBlueprintEntity.builder()
                .stageNo(1).startChapter(1).endChapter(20).stageGoal("弹幕初现与求生")
                .build();
    }

    private StoryVO.Module module() {
        return new StoryVO.Module();
    }

    private void stubModel(String raw) {
        when(llmGateway.complete(any(), any())).thenReturn(raw);
    }
}
