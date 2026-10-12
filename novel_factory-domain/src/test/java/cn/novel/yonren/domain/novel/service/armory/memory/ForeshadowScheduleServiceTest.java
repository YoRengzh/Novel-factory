package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowScheduleEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowSettlementEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 伏笔排期打标测试（2026-10-02，P2b）。
 *
 * <p>打标是整条 P2 链路的**枢纽**：没有它，排期表只是个落盘的死文件——
 * 清账过滤、段计划注入、漏收指标全都读不到 {@code scheduledPayoffChapter}。
 */
class ForeshadowScheduleServiceTest {

    private final ForeshadowScheduleService service = new ForeshadowScheduleService();

    private static ChapterSummaryEntity chapter(int no) {
        ChapterSummaryEntity s = new ChapterSummaryEntity();
        s.setChapterNo(no);
        s.setForeshadowSeeds(new ArrayList<>());
        s.setForeshadowingResolved(new ArrayList<>());
        return s;
    }

    private static void plant(ChapterSummaryEntity summary, String content) {
        ChapterSummaryEntity.SeedEntry seed = new ChapterSummaryEntity.SeedEntry();
        seed.setContent(content);
        seed.setResolvable(Boolean.TRUE);
        summary.getForeshadowSeeds().add(seed);
    }

    private static void resolve(ChapterSummaryEntity summary, String content) {
        summary.getForeshadowingResolved().add(content);
    }

    private static ForeshadowScheduleEntity.ScheduleItem item(String intent, int plant, int payoff) {
        return ForeshadowScheduleEntity.ScheduleItem.builder()
                .intent(intent).plantChapter(plant).payoffChapter(payoff)
                .status(ForeshadowScheduleEntity.STATUS_PLANNED)
                .span(payoff - plant).build();
    }

    private static List<ForeshadowScheduleEntity> scheduleWith(ForeshadowScheduleEntity.ScheduleItem... items) {
        List<ForeshadowScheduleEntity> list = new ArrayList<>();
        list.add(ForeshadowScheduleEntity.builder().stageNo(1).startChapter(1).endChapter(50)
                .items(new ArrayList<>(List.of(items))).build());
        return list;
    }

    /** 排期章命中：窗口内埋下即回填 payoff 章并推进 PLANTED */
    @Test
    @DisplayName("stamp：埋在排期章时回填 scheduledPayoffChapter")
    void stamp_fillsScheduledPayoffOnExactChapter() {
        ChapterSummaryEntity summary = chapter(12);
        plant(summary, "顾老头是否收陆瑾瑜为徒");
        List<ForeshadowScheduleEntity> schedules = scheduleWith(item("顾老头是否收陆瑾瑜为徒", 12, 30));

        ForeshadowScheduleService.StampResult r = service.stamp(summary, schedules);

        assertEquals(1, r.planted());
        assertEquals(30, summary.getForeshadowSeeds().get(0).getScheduledPayoffChapter(),
                "必须回填计划回收章——它是清账过滤与漏收指标的唯一依据");
        assertEquals(ForeshadowScheduleEntity.STATUS_PLANTED,
                schedules.get(0).getItems().get(0).getStatus());
        assertEquals(12, schedules.get(0).getItems().get(0).getActualPlantChapter());
    }

    /**
     * **窗口容错**：模型实测会偏移排期 1-2 章（它连 mainLineAdvance 都要靠 JSON 示例才肯回填）。
     * 按"章号精确相等"匹配会让整表腐烂成 MISSED。
     */
    @Test
    @DisplayName("stamp：排期章 ±2 窗口内都能命中")
    void stamp_matchesWithinWindow() {
        for (int offset : new int[]{-2, -1, 1, 2}) {
            ChapterSummaryEntity summary = chapter(12 + offset);
            plant(summary, "顾老头是否收陆瑾瑜为徒");
            List<ForeshadowScheduleEntity> schedules = scheduleWith(item("顾老头是否收陆瑾瑜为徒", 12, 30));

            assertEquals(1, service.stamp(summary, schedules).planted(),
                    "偏移 " + offset + " 章应仍能命中");
        }
    }

    /**
     * **短锚点回归**（2026-10-03，老书两批实测）：排期 intent 与摘要 seed 是两个模型的措辞，
     * 同一件事实测被改写掉 1-2 个字——「老银镯子」(LCS=4) 在旧 6 字门槛下被误判 MISSED，
     * 而它**分毫不差埋在排期章上**。阈值降到 4 后此类必须命中。
     */
    @Test
    @DisplayName("stamp：4 字锚点（老银镯子）在窗口内必须命中")
    void stamp_matchesShortAnchor() {
        ChapterSummaryEntity summary = chapter(28);
        plant(summary, "陆周氏将借条与老银镯子塞入陆瑾瑜摇篮缝隙棉布下，称等他长大自己来看");
        List<ForeshadowScheduleEntity> schedules = scheduleWith(item(
                "陆周氏拿出棺材本时，特意取出一对老银镯子说留给未来孙媳妇", 29, 52));

        ForeshadowScheduleService.StampResult r = service.stamp(summary, schedules);

        assertEquals(1, r.planted(), "LCS='老银镯子'（4 字）必须命中——旧 6 字门槛下的实测假阴性形态");
        assertEquals(52, summary.getForeshadowSeeds().get(0).getScheduledPayoffChapter());
    }

    /** 超出窗口则不认领，且排期项最终会转 MISSED（"模型没按排期埋"，与"埋了没收"分开记） */
    @Test
    @DisplayName("stamp：超出窗口不命中，到期后转 MISSED")
    void stamp_outsideWindowBecomesMissed() {
        ChapterSummaryEntity summary = chapter(20);
        plant(summary, "一条与排期意图完全无关的新伏笔描述");
        List<ForeshadowScheduleEntity> schedules = scheduleWith(item("顾老头是否收陆瑾瑜为徒", 12, 30));

        ForeshadowScheduleService.StampResult r = service.stamp(summary, schedules);

        assertEquals(0, r.planted(), "明显不相关的内容不该被排期项吸走");
        assertNull(summary.getForeshadowSeeds().get(0).getScheduledPayoffChapter());
        assertEquals(1, r.missed(), "已过 plantChapter+窗口 ⇒ 到期未埋");
        assertEquals(ForeshadowScheduleEntity.STATUS_MISSED, schedules.get(0).getItems().get(0).getStatus());
    }

    /** 本章回收清单命中已排期的线 ⇒ PAID */
    @Test
    @DisplayName("stamp：回收清单命中则判 PAID")
    void stamp_marksPaidWhenResolved() {
        ChapterSummaryEntity summary = chapter(30);
        resolve(summary, "顾老头是否收陆瑾瑜为徒");
        ForeshadowScheduleEntity.ScheduleItem planted = item("顾老头是否收陆瑾瑜为徒", 12, 30);
        planted.setStatus(ForeshadowScheduleEntity.STATUS_PLANTED);
        planted.setActualPlantChapter(12);
        List<ForeshadowScheduleEntity> schedules = scheduleWith(planted);

        ForeshadowScheduleService.StampResult r = service.stamp(summary, schedules);

        assertEquals(1, r.paid());
        assertEquals(ForeshadowScheduleEntity.STATUS_PAID, schedules.get(0).getItems().get(0).getStatus());
        assertEquals(30, schedules.get(0).getItems().get(0).getActualPayoffChapter());
    }

    /** 无排期表（老故事/未启用）→ 完全惰性，行为与引入前一致 */
    @Test
    @DisplayName("stamp：无排期表时完全惰性")
    void stamp_inertWithoutSchedule() {
        ChapterSummaryEntity summary = chapter(12);
        plant(summary, "随便一条伏笔");

        assertEquals(ForeshadowScheduleService.StampResult.EMPTY, service.stamp(summary, null));
        assertEquals(ForeshadowScheduleService.StampResult.EMPTY, service.stamp(summary, List.of()));
        assertNull(summary.getForeshadowSeeds().get(0).getScheduledPayoffChapter(),
                "无排期时不得打标——否则下游指标拿到伪造的排期信息");
    }

    /** 已打标的种子不被二次认领（避免一条意图吸走多个种子） */
    @Test
    @DisplayName("stamp：已打标的种子不重复认领")
    void stamp_doesNotReclaimStampedSeed() {
        ChapterSummaryEntity summary = chapter(12);
        plant(summary, "顾老头是否收陆瑾瑜为徒");
        summary.getForeshadowSeeds().get(0).setScheduledPayoffChapter(30);
        List<ForeshadowScheduleEntity> schedules = scheduleWith(item("顾老头是否收陆瑾瑜为徒", 12, 30));

        assertEquals(0, service.stamp(summary, schedules).planted(),
                "已带 scheduledPayoffChapter 的种子不该被再次认领");
    }

    /** 空输入安全 */
    @Test
    @DisplayName("stamp：空输入安全")
    void stamp_emptySafe() {
        assertTrue(!service.stamp(null, null).changed());
        assertTrue(!service.stamp(chapter(1), null).changed());
    }

    /**
     * 阶段出口裁决判 DONE 的线必须回写 PAID——闭环的最后一段。
     *
     * <p>只把 DONE 写进结算文件而不回写排期表，它下一段仍会以「逾期补收」再被下一次令：
     * 实测「2026」那条线（第 5 章埋、原计划第 45 章收）到第 100 章仍在被逐段下令补收，
     * 而它其实已在第 93/95 章兑现，只是 {@code matchPayoff} 认不出来。
     */
    @Test
    @DisplayName("applySettlementOutcomes：判 DONE 的线回写 PAID，未判的不得被误关")
    void applySettlementOutcomes_marksPaidAndIgnoresMismatch() {
        ForeshadowScheduleEntity.ScheduleItem done = item("主角无意识写出2026年的日期", 5, 45);
        done.setStatus(ForeshadowScheduleEntity.STATUS_PLANTED);
        done.setActualPlantChapter(5);
        ForeshadowScheduleEntity.ScheduleItem untouched = item("另一条尚未兑现的线", 5, 45);
        untouched.setStatus(ForeshadowScheduleEntity.STATUS_PLANTED);
        List<ForeshadowScheduleEntity> schedules = scheduleWith(done, untouched);

        int written = service.applySettlementOutcomes(schedules, List.of(
                ForeshadowSettlementEntity.SettlementDecision.builder()
                        // 归一化后相等即可命中（去空白/引号是仓库统一口径）
                        .content(" 主角无意识写出2026年的日期 ")
                        .decision(ForeshadowSettlementEntity.DECISION_DONE)
                        .actualPayoffChapter(95).build(),
                ForeshadowSettlementEntity.SettlementDecision.builder()
                        .content("另一条尚未兑现的线")
                        .decision(ForeshadowSettlementEntity.DECISION_RECOVER)
                        .build()), 100);

        assertEquals(1, written);
        assertEquals(ForeshadowScheduleEntity.STATUS_PAID, done.getStatus());
        assertEquals(95, done.getActualPayoffChapter().intValue());
        assertEquals(ForeshadowScheduleEntity.STATUS_PLANTED, untouched.getStatus(),
                "未判 DONE 的线不得被误关——误关会永久丢掉一条真伏笔");
    }

    /** DONE 缺章号时不可回写：无法核验的"已完成"宁可继续追踪 */
    @Test
    @DisplayName("applySettlementOutcomes：缺章号的 DONE 不回写")
    void applySettlementOutcomes_ignoresDoneWithoutChapter() {
        ForeshadowScheduleEntity.ScheduleItem item = item("主角无意识写出2026年的日期", 5, 45);
        item.setStatus(ForeshadowScheduleEntity.STATUS_PLANTED);

        int written = service.applySettlementOutcomes(scheduleWith(item), List.of(
                ForeshadowSettlementEntity.SettlementDecision.builder()
                        .content("主角无意识写出2026年的日期")
                        .decision(ForeshadowSettlementEntity.DECISION_DONE)
                        .build()), 100);

        assertEquals(0, written);
        assertEquals(ForeshadowScheduleEntity.STATUS_PLANTED, item.getStatus());
    }

    /**
     * PARKED 回写：记下"在哪一章出口被裁定为长线"，**不改 status**——它要继续被追踪、
     * 真兑现时仍能自动关账（改 status 会被 {@code carriableItems} 静默丢掉，等于停止追踪）。
     */
    @Test
    @DisplayName("applySettlementOutcomes：判 PARKED 的线打上在养标记且保持 PLANTED")
    void applySettlementOutcomes_marksParkedWithoutChangingStatus() {
        ForeshadowScheduleEntity.ScheduleItem parked = item("主角无意识写出2026年的日期", 5, 45);
        parked.setStatus(ForeshadowScheduleEntity.STATUS_PLANTED);

        int written = service.applySettlementOutcomes(scheduleWith(parked), List.of(
                ForeshadowSettlementEntity.SettlementDecision.builder()
                        .content("主角无意识写出2026年的日期")
                        .decision(ForeshadowSettlementEntity.DECISION_PARKED)
                        .reason("指向身份暴露危机，是贯穿全书的暗线").build()), 100);

        assertEquals(1, written);
        assertTrue(parked.parked(), "应打上在养标记");
        assertEquals(100, parked.getParkedAtChapter().intValue());
        assertEquals(ForeshadowScheduleEntity.STATUS_PLANTED, parked.getStatus(),
                "在养不是关账也不是出账：status 必须保持 PLANTED，它仍要被结转与自动兑现");
        assertTrue(parked.active(), "在养条目仍是活线（否则会被排期表结转丢掉）");
    }
}
