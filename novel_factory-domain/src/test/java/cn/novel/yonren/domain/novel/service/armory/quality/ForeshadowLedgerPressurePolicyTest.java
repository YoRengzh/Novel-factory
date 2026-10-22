package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ForeshadowLedgerPressurePolicyTest {

    @Test
    @DisplayName("在途统计：严格口径累加、回收核销、弃置出账不计")
    void pendingCount_addsNewAndNetsResolved() {
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        summaries.add(chapter(1, List.of("甲条目的完整描述文本", "乙条目的完整描述文本"), List.of()));
        // 回收登记逐字沿用账本原文（最常见形态）
        summaries.add(chapter(2, List.of("丙条目的完整描述文本"), List.of("甲条目的完整描述文本")));
        summaries.add(chapter(3, List.of(), List.of()));

        // 3 埋 − 1 收 = 2 在途
        assertEquals(2, ForeshadowLedgerPressurePolicy.pendingCount(summaries, List.of()));

        // 弃置出账的线不再欠账
        assertEquals(1, ForeshadowLedgerPressurePolicy.pendingCount(summaries, List.of("乙条目的完整描述文本")));
    }

    @Test
    @DisplayName("在途统计：只算 resolvable=TRUE 的种子（氛围/未标注不计）")
    void pendingCount_onlyCountsResolvableSeeds() {
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        summaries.add(chapter(1, List.of("承诺改日打一把旧锁的完整描述"), List.of()));
        summaries.get(0).getForeshadowSeeds().get(0).setResolvable(Boolean.FALSE);
        summaries.add(chapter(2, List.of("一条没有标注的旧数据伏笔描述"), List.of()));
        summaries.get(1).getForeshadowSeeds().get(0).setResolvable(null);
        summaries.add(chapter(3, List.of("真正承担兑现义务的伏笔描述"), List.of()));
        summaries.add(chapter(4, List.of(), List.of()));

        assertEquals(1, ForeshadowLedgerPressurePolicy.pendingCount(summaries, List.of()),
                "只有 resolvable=TRUE 的那条算在途（与体检/health 同口径）");
    }

    @Test
    @DisplayName("在途统计：ID 回抄的回收同样核销（2026-10-14 口径分裂修复）")
    void pendingCount_closesByIdEchoBack() {
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        ChapterSummaryEntity plant = chapter(142, List.of("角色甲在旧本夹层藏了地址纸条"), List.of());
        plant.getForeshadowSeeds().get(0).setForeshadowId("F142-1");
        summaries.add(plant);
        // 模型回抄 ID 销账（账本渲染带 [F142-1] 前缀时的标准形态）
        summaries.add(chapter(146, List.of(), List.of("[F142-1]")));
        summaries.add(chapter(147, List.of(), List.of()));

        assertEquals(0, ForeshadowLedgerPressurePolicy.pendingCount(summaries, List.of()),
                "ID 回抄必须能核销——否则模型越听话回抄 ID，在途读数越虚高");
    }

    @Test
    @DisplayName("净增统计：窗口内严格口径新埋与回收计数")
    void netGrowth_countsWindowOnly() {
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        summaries.add(chapter(1, List.of("窗口外的甲条伏笔描述", "窗口外的乙条伏笔描述",
                "窗口外的丙条伏笔描述"), List.of()));           // 窗口外（只看最近 2 章）
        summaries.add(chapter(2, List.of("窗口内的丁条伏笔描述", "窗口内的戊条伏笔描述"),
                List.of("窗口外的甲条伏笔描述")));
        summaries.add(chapter(3, List.of("窗口内的己条伏笔描述"),
                List.of("窗口外的乙条伏笔描述", "窗口内的丁条伏笔描述")));

        ForeshadowLedgerPressurePolicy.NetGrowth growth =
                ForeshadowLedgerPressurePolicy.netGrowth(summaries, 2, List.of());

        assertEquals(2, growth.windowSize());
        assertEquals(3, growth.totalNew(), "窗口内新埋 d,e,f = 3");
        assertEquals(3, growth.totalResolved(), "窗口内回收 a,b,d = 3");
        assertEquals(0, growth.net(), "3 埋 3 收 = 净增 0");
    }

    @Test
    @DisplayName("低压期不产块：账未满时不占 prompt 预算")
    void render_returnsNullBelowHighPressure() {
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        // 造 10 条在途（< 40 高压线）
        List<String> newItems = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            newItems.add("伏笔" + i + "的完整描述文本");
        }
        summaries.add(chapter(1, newItems, List.of()));
        summaries.add(chapter(2, List.of(), List.of()));

        assertNull(ForeshadowLedgerPressurePolicy.render(summaries, List.of(), 5),
                "未达高压线不得产块");
        assertFalse(ForeshadowLedgerPressurePolicy.isHighPressure(summaries, List.of()));
    }

    @Test
    @DisplayName("高压期产块：带在途条数、净增读数、回收吞吐放宽与收线优先要求")
    void render_highPressureGivesNumbersAndConstraints() {
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        // 造 45 条在途（> 40 高压线）
        List<String> newItems = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            newItems.add("伏笔" + i + "的完整描述文本");
        }
        summaries.add(chapter(1, newItems, List.of()));
        // 第 2 章：新埋 2 收 1
        summaries.add(chapter(2, List.of("新甲条目的完整描述", "新乙条目的完整描述"),
                List.of("伏笔0的完整描述文本")));
        summaries.add(chapter(3, List.of("新丙条目的完整描述"), List.of()));
        summaries.add(chapter(4, List.of(), List.of()));

        String block = ForeshadowLedgerPressurePolicy.render(summaries, List.of(), 3);

        assertNotNull(block, "超过高压线必须产块");
        assertTrue(block.contains("压力预警"), "应带块标题：" + block);
        // 45 埋 − 1 收（伏笔0） + ch2 新埋 2 + ch3 新埋 1 = 47 在途
        assertTrue(block.contains("47 条"), "必须给出准确在途条数（45-1+2+1）：" + block);
        // 窗口 = ch2..ch4：新埋 3 / 回收 1 → 净增 +2
        assertTrue(block.contains("净增 +2"), "必须给出净增读数：" + block);
        assertTrue(block.contains("收不抵支"), "净增为正时应点明收支失衡：" + block);
        // 只报警不给约束等于把判断推回给模型，必须给可执行的约束
        assertTrue(block.contains("净增上限 0 条"), "必须给净增上限：" + block);
        assertTrue(block.contains("每章新埋至多 2 条"), "必须给单章新埋上限：" + block);
        assertTrue(block.contains("优先回收"), "必须要求优先回收：" + block);
        // 与规划 prompt「每章至多 1 条回收」打架的规则必须被显式放宽——
        // 否则"净增 ≤0"叠加"每章埋 2"在规则层面不可满足，模型只会忽略整块警告
        assertTrue(block.contains("回收吞吐放宽到每章 2 条"),
                "高压期必须放宽回收上限，否则净增 ≤0 不可满足：" + block);
    }

    @Test
    @DisplayName("高压线边界：达到即判定（含等号）")
    void highPressureThresholdIsInclusive() {
        List<String> exactly = new ArrayList<>();
        for (int i = 0; i < ForeshadowLedgerPressurePolicy.HIGH_PRESSURE_PENDING; i++) {
            exactly.add("伏笔" + i + "的完整描述文本");
        }
        List<ChapterSummaryEntity> summaries = List.of(
                chapter(1, exactly, List.of()),
                chapter(2, List.of(), List.of()));

        assertTrue(ForeshadowLedgerPressurePolicy.isHighPressure(summaries, List.of()),
                "正好达到高压线即判定为高压（与项目其它阈值含等号的惯例一致）");
        assertNotNull(ForeshadowLedgerPressurePolicy.render(summaries, List.of(), 5));
    }

    /**
     * 造一章：new 与 seeds 同步填充（严格口径读 seeds，两者必须一致）。
     * 种子默认 {@code resolvable=TRUE}——只有声明了兑现义务的条目才进在途统计。
     */
    private static ChapterSummaryEntity chapter(int no, List<String> newItems, List<String> resolved) {
        List<ChapterSummaryEntity.SeedEntry> seeds = new ArrayList<>();
        for (String item : newItems) {
            ChapterSummaryEntity.SeedEntry seed = new ChapterSummaryEntity.SeedEntry();
            seed.setContent(item);
            seed.setResolvable(Boolean.TRUE);
            seeds.add(seed);
        }
        return ChapterSummaryEntity.builder()
                .chapterNo(no)
                .summary("剧情。")
                .foreshadowingNew(new ArrayList<>(newItems))
                .foreshadowSeeds(seeds)
                .foreshadowingResolved(new ArrayList<>(resolved))
                .build();
    }


    // ==================== 移植自旧系统的闸门用例 ====================

    /** 高压期：计划里新埋多于回收 → 驳回（自然语言警告之外必须有人执法） */
    @Test
    @DisplayName("净增闸门：高压期新埋多于回收即驳回")
    void validatePlanNetGrowth_blocksWhenPlantedExceedsRecovered() {
        List<ChapterSummaryEntity> summaries = highPressureSummaries();

        List<ChapterPlanItemEntity> plan = planWith(161,
                "某人做了一件事。（新埋第161章某条线索）",
                "另一件事发生。（新埋第161章另一条线索）",
                "真相揭晓。（回收第120章埋设的某条）");
        String issue = ForeshadowLedgerPressurePolicy.validatePlanNetGrowth(plan, summaries, List.of());
        assertNotNull(issue, "新埋 2 / 回收 1 应被驳回");
        assertTrue(issue.contains("净增"), "理由应给出净增读数：" + issue);
    }

    /** 未带结构化前缀的条目不计——模型漏标时静默放行（宁可漏报不误伤） */
    @Test
    @DisplayName("净增闸门：未标前缀的事件不计入")
    void validatePlanNetGrowth_ignoresUnmarkedEvents() {
        List<ChapterSummaryEntity> summaries = highPressureSummaries();

        List<ChapterPlanItemEntity> plan = planWith(161,
                "某人做了一件事，带来了某个变化",
                "真相揭晓。（回收第120章埋设的某条）");
        assertNull(ForeshadowLedgerPressurePolicy.validatePlanNetGrowth(plan, summaries, List.of()),
                "无前缀的普通事件不算新埋");
    }

    /** 高压期：回收 ≥ 新埋 → 放行 */
    @Test
    @DisplayName("净增闸门：高压期回收不少于新埋即放行")
    void validatePlanNetGrowth_passesWhenBalanced() {
        List<ChapterSummaryEntity> summaries = highPressureSummaries();

        List<ChapterPlanItemEntity> plan = planWith(161,
                "某人做了一件事。（新埋第161章某条线索）",
                "真相揭晓。（回收第120章埋设的某条）");
        assertNull(ForeshadowLedgerPressurePolicy.validatePlanNetGrowth(plan, summaries, List.of()),
                "回收=新埋应放行");
    }

    /** 行内标记形态（prompt 实际教的写法）必须被识别——只认前缀式会让闸门静默空转 */
    @Test
    @DisplayName("净增闸门：识别行内标记（回收第N章埋设的/新埋第N章）")
    void validatePlanNetGrowth_recognizesInlineMarkers() {
        List<ChapterSummaryEntity> summaries = highPressureSummaries();

        List<ChapterPlanItemEntity> bad = planWith(161,
                "他做了一件事。（新埋第161章某条线索）",
                "又发生一件事。（新埋第161章另一条线索）",
                "某事揭晓。（回收第120章埋设的某条）");
        assertNotNull(ForeshadowLedgerPressurePolicy.validatePlanNetGrowth(bad, summaries, List.of()),
                "行内标记 2 埋 1 收应被驳回（前缀式识别会漏掉这种形态）");

        List<ChapterPlanItemEntity> good = planWith(161,
                "他做了一件事。（新埋第161章某条线索）",
                "某事揭晓。（回收第120章埋设的某条）",
                "另一事确认。（回收第130章埋设的某条）");
        assertNull(ForeshadowLedgerPressurePolicy.validatePlanNetGrowth(good, summaries, List.of()),
                "行内标记 1 埋 2 收应放行");
    }

    /** 低压期：不做净增要求（本条只治"账已满还在铺新线"） */
    @Test
    @DisplayName("净增闸门：低压期不做要求")
    void validatePlanNetGrowth_skipsWhenNotHighPressure() {
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        summaries.add(chapter(1, List.of("某条伏笔的完整描述文本"), List.of()));
        summaries.add(chapter(2, List.of(), List.of()));

        List<ChapterPlanItemEntity> plan = planWith(3,
                "（伏笔埋设）埋设第3章计划中的《甲》：某事发生",
                "（伏笔埋设）埋设第3章计划中的《乙》：另一事发生");
        assertNull(ForeshadowLedgerPressurePolicy.validatePlanNetGrowth(plan, summaries, List.of()),
                "低压期不要求净增配平");
    }


    // ---- 移植用例的辅助构造 ----

    /** 造高压期历史（45 条在途 > 40 高压线） */
    private static List<ChapterSummaryEntity> highPressureSummaries() {
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        List<String> newItems = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            newItems.add("伏笔" + i + "的完整描述文本");
        }
        summaries.add(chapter(1, newItems, List.of()));
        summaries.add(chapter(2, List.of(), List.of()));
        return summaries;
    }

    /** 造一段含指定关键事件的计划（单章），包成列表供闸门消费 */
    private static List<ChapterPlanItemEntity> planWith(int chapterNo, String... keyEvents) {
        ChapterPlanItemEntity item = ChapterPlanItemEntity.builder()
                .chapterNo(chapterNo)
                .keyEvents(List.of(keyEvents))
                .build();
        return List.of(item);
    }
}
