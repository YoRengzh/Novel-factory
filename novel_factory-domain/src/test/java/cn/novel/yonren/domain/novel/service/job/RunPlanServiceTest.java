package cn.novel.yonren.domain.novel.service.job;

import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.model.valobj.quality.BatchHealthReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 无人值守续写计划测试。
 *
 * 重点不在"能不能续"，而在**该停的时候一定停**：挂机最怕的不是停不下来，而是该停不停。
 * 因此四条停机条件（未达标 / 未超批数上限 / 体检未达停线 / 未开启）逐条覆盖，
 * 并要求每条都给出人类可读理由——"为什么停了"必须可归因，不必翻日志。
 */
class RunPlanServiceTest {

    private StoryProperties props;
    private RunPlanService service;

    @BeforeEach
    void setUp() {
        props = new StoryProperties();
        service = new RunPlanService(props);
    }

    private void enablePlan(Integer target, Integer batchSize, int maxBatches, String stopLevel) {
        StoryProperties.RunPlanProperties plan = new StoryProperties.RunPlanProperties();
        plan.setEnabled(true);
        plan.setTargetChapters(target);
        plan.setBatchSize(batchSize);
        plan.setMaxBatches(maxBatches);
        plan.setStopOnHealthLevel(stopLevel);
        props.setRunPlan(plan);
    }

    private static ArmoryCommandEntity command(int chapterCount, Integer maxChapterCount) {
        return ArmoryCommandEntity.builder()
                .storyContextEntity(StoryContextEntity.builder()
                        .novel_title("测试书").chapterCount(chapterCount).build())
                .storyVO(new StoryVO())
                .maxChapterCount(maxChapterCount)
                .build();
    }

    private static BatchHealthReport health(BatchHealthReport.Level level) {
        return new BatchHealthReport(20, List.of(), level, List.of());
    }

    // ---------------- 停机条件 ----------------

    @Test
    @DisplayName("默认关闭：不开启就绝不自动续批（它会自动连续花钱，必须是显式选择）")
    void disabledByDefault() {
        assertFalse(service.enabled());
        RunPlanService.Decision decision = service.decide(10, 1, health(BatchHealthReport.Level.OK));
        assertFalse(decision.continueNext());
        assertTrue(decision.reason().contains("未开启自动续批"));
    }

    @Test
    @DisplayName("已达标：停在目标章数上，不超出一章")
    void stopsWhenTargetReached() {
        enablePlan(100, 12, 0, "CRITICAL");

        RunPlanService.Decision decision = service.decide(100, 9, health(BatchHealthReport.Level.OK));

        assertFalse(decision.continueNext());
        assertTrue(decision.reason().contains("已达标"));
        assertTrue(decision.reason().contains("100/100"));
    }

    @Test
    @DisplayName("已达续批上限：停，并把已写字数写进理由")
    void stopsAtMaxBatches() {
        enablePlan(null, 12, 3, "CRITICAL");

        RunPlanService.Decision decision = service.decide(36, 3, health(BatchHealthReport.Level.OK));

        assertFalse(decision.continueNext());
        assertTrue(decision.reason().contains("已达续批上限 3 批"));
    }

    @Test
    @DisplayName("批末体检达停线：停并联到体检详情来源（这是观测层与执行器的接缝）")
    void stopsOnHealthLevel() {
        enablePlan(100, 12, 0, "DEGRADED");

        // DEGRADED 达到停线（ordinal 相等即算达到）
        RunPlanService.Decision degraded = service.decide(40, 4, health(BatchHealthReport.Level.DEGRADED));
        assertFalse(degraded.continueNext());
        assertTrue(degraded.reason().contains("批末体检 DEGRADED"));
        assertTrue(degraded.reason().contains("停线 DEGRADED"));
        assertTrue(degraded.reason().contains("/health"), "理由里要指路到体检详情");

        // WATCH 未达 DEGRADED 停线 → 继续
        assertTrue(service.decide(40, 4, health(BatchHealthReport.Level.WATCH)).continueNext());
    }

    @Test
    @DisplayName("体检达 CRITICAL：默认停线即 CRITICAL，一定停")
    void criticalAlwaysStopsByDefault() {
        enablePlan(100, 12, 0, null);   // 未配 → 默认 CRITICAL

        RunPlanService.Decision decision = service.decide(40, 4, health(BatchHealthReport.Level.CRITICAL));

        assertFalse(decision.continueNext());
        assertTrue(decision.reason().contains("CRITICAL"));
    }

    @Test
    @DisplayName("体检为 null（样本不足/读盘失败）时不拿健康判停，其余条件照判")
    void nullHealthDoesNotBlock() {
        enablePlan(100, 12, 0, "CRITICAL");

        assertTrue(service.decide(40, 4, null).continueNext());
    }

    @Test
    @DisplayName("四条都通过：继续，并给出进度与下一批序号")
    void continuesWhenAllConditionsPass() {
        enablePlan(100, 12, 0, "CRITICAL");

        RunPlanService.Decision decision = service.decide(48, 4, health(BatchHealthReport.Level.WATCH));

        assertTrue(decision.continueNext());
        assertTrue(decision.reason().contains("48/100"));
        assertTrue(decision.reason().contains("第 5 批"));
    }

    // ---------------- 目标章数解析 ----------------

    @Test
    @DisplayName("目标未配置时回落 constraints（且仅在 enforce-chapter-limit 开启时生效）")
    void targetFallsBackToConstraints() {
        enablePlan(null, 12, 0, "CRITICAL");
        assertNull(service.targetChapters(), "未开 enforce 时不应把 maxChapterCount 当目标");

        StoryVO.Constraints constraints = new StoryVO.Constraints();
        constraints.setEnforceChapterLimit(true);
        constraints.setMaxChapterCount(180);
        props.setConstraints(constraints);
        assertEquals(180, service.targetChapters());

        constraints.setEnforceChapterLimit(false);
        assertNull(service.targetChapters());
    }

    @Test
    @DisplayName("停线配置容错：无法识别的值取最保守的 CRITICAL（宁可能多跑，不误停）")
    void unparsableStopLevelIsConservative() {
        assertEquals(BatchHealthReport.Level.CRITICAL, RunPlanService.parseStopLevel(null));
        assertEquals(BatchHealthReport.Level.CRITICAL, RunPlanService.parseStopLevel("  "));
        assertEquals(BatchHealthReport.Level.CRITICAL, RunPlanService.parseStopLevel("WHATEVER"));
        assertEquals(BatchHealthReport.Level.WATCH, RunPlanService.parseStopLevel(" watch "));
        assertEquals(BatchHealthReport.Level.DEGRADED, RunPlanService.parseStopLevel("Degraded"));
    }

    // ---------------- 下一批命令 ----------------

    @Test
    @DisplayName("下一批命令：克隆原请求、指向已完成的故事目录、批大小取 run-plan 配置")
    void nextBatchCommandClonesAndPointsToStoryDir() {
        enablePlan(100, 8, 0, "CRITICAL");
        ArmoryCommandEntity current = command(12, 180);

        ArmoryCommandEntity next = service.nextBatchCommand(current, "20260913-story-0001", 40);

        assertEquals("20260913-story-0001", next.getResumeStoryDir());
        assertEquals(8, next.getStoryContextEntity().getChapterCount(), "批大小取 run-plan.batch-size");
        assertEquals("测试书", next.getStoryContextEntity().getNovel_title(), "其余上下文必须原样克隆");
        assertEquals(180, next.getMaxChapterCount());
        assertEquals(current.getStoryVO(), next.getStoryVO());
        // 原请求不被改动（克隆而非就地改）
        assertEquals(12, current.getStoryContextEntity().getChapterCount());
        assertNull(current.getResumeStoryDir());
    }

    @Test
    @DisplayName("最后一批按剩余章数收敛：不依赖到顶保护兜底，让请求本身精确")
    void lastBatchIsClampedToRemaining() {
        enablePlan(100, 12, 0, "CRITICAL");

        ArmoryCommandEntity next = service.nextBatchCommand(command(12, null), "story-x", 96);

        assertEquals(4, next.getStoryContextEntity().getChapterCount(), "只剩 4 章就只要 4 章");
    }

    @Test
    @DisplayName("批大小未配置时沿用原请求的 chapterCount（chapterCount 即批次大小）")
    void batchSizeFallsBackToRequest() {
        enablePlan(null, null, 0, "CRITICAL");

        ArmoryCommandEntity next = service.nextBatchCommand(command(15, null), "story-x", 30);

        assertEquals(15, next.getStoryContextEntity().getChapterCount());
    }

    @Test
    @DisplayName("故事目录未知或命令为空：返回 null，由调用方跳过续批（不构造半成品命令）")
    void invalidInputsYieldNull() {
        enablePlan(100, 12, 0, "CRITICAL");
        assertNull(service.nextBatchCommand(null, "story-x", 10));
        assertNull(service.nextBatchCommand(command(12, null), null, 10));
        assertNull(service.nextBatchCommand(command(12, null), "  ", 10));
    }
}
