package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowScheduleEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.entity.VolumeBlueprintEntity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 滚动大纲服务测试：触发判断、首版固定/后续自适应窗口、四件套 prompt、解析规整与降级
 */
class RollingOutlineServiceTest {

    private final RollingOutlineService service = new RollingOutlineService();

    @Test
    void needsGeneration_triggersWhenMissingOrBeyondCoverage() {
        assertTrue(service.needsGeneration(null, 1));
        assertTrue(service.needsGeneration(List.of(), 1));
        assertTrue(service.needsGeneration(List.of(blueprint(1, 1, 10)), 11));
        assertTrue(service.needsGeneration(List.of(blueprint(1, 1, null)), 1));
        // 覆盖区间未及下一章才生成：区间内复用
        assertFalse(service.needsGeneration(List.of(blueprint(1, 1, 10)), 10));
        assertFalse(service.needsGeneration(List.of(blueprint(1, 1, 10)), 1));
    }

    @Test
    void nextWindow_freshStartUsesFixedDefault() {
        // 故事起步（下一章仍在默认窗长内）：首版蓝图窗口写死第 1-10 章
        RollingOutlineService.StageWindow window = service.nextWindow(null, 1);
        assertEquals(1, window.stageNo());
        assertEquals(1, window.startChapter());
        assertFalse(window.adaptive());
        assertFalse(service.nextWindow(null, 10).adaptive());
    }

    @Test
    void nextWindow_midFlightAdoptionStartsAdaptiveFromNextChapter() {
        // 老故事中途接入（已连载超出默认窗长）：首版蓝图不再写死，直接从下一章起自适应
        RollingOutlineService.StageWindow window = service.nextWindow(null, 15);
        assertEquals(1, window.stageNo());
        assertEquals(15, window.startChapter());
        assertTrue(window.adaptive());
    }

    @Test
    void nextWindow_subsequentStagesStartAfterPreviousEnd() {
        RollingOutlineService.StageWindow window = service.nextWindow(blueprint(2, 11, 50), 55);
        assertEquals(3, window.stageNo());
        assertEquals(51, window.startChapter());
        assertTrue(window.adaptive());
    }

    @Test
    void nextWindow_previousWithoutEndFallsBackToNextChapter() {
        RollingOutlineService.StageWindow window = service.nextWindow(blueprint(1, 1, null), 15);
        assertEquals(2, window.stageNo());
        assertEquals(15, window.startChapter());
    }

    @Test
    void buildGenerationPrompt_carriesFourInputsAndAntiQuota() {
        StageBlueprintEntity previous = blueprint(1, 1, 10);
        previous.setStageGoal("站稳外门");
        previous.setTasks(List.of("上一任务甲"));
        List<ChapterSummaryEntity> summaries = List.of(summary(9), summary(10));
        List<String> foreshadows = List.of("【硬】古物来历（第2章埋）");
        // 200 章长篇：起点 11 的窗口保持标准 30-80 章约束，不触发收束全书分支
        StoryContextEntity longStory = StoryContextEntity.builder()
                .novel_title("镜中仙途").theme("仙侠").protagonist("小陈")
                .outline("主角身世之谜").chapterCount(200).build();

        String prompt = service.buildGenerationPrompt(longStory, previous, summaries, foreshadows,
                RollingOutlineService.StageWindow.adaptive(2, 11), 200);

        assertTrue(prompt.contains("主角身世之谜"));
        assertTrue(prompt.contains("小陈"));
        assertTrue(prompt.contains("- 上一任务甲"));
        assertTrue(prompt.contains("第9章"));
        assertTrue(prompt.contains("第10章"));
        assertTrue(prompt.contains("【硬】古物来历（第2章埋）"));
        // 后续阶段窗口不再写死：起点机械给定，终点由模型按弧线自定并钳制 30-80 章
        assertTrue(prompt.contains("起点为第 11 章"));
        assertTrue(prompt.contains("第 11 章起（第 2 阶段）"));
        assertTrue(prompt.contains("窗长约束 30-80 章"));
        assertTrue(prompt.contains("40~90"));
        // 里程碑而非配额：反注水约束必须在场
        assertTrue(prompt.contains("严禁写成活动配额"));
        assertTrue(prompt.contains("严禁静默丢弃未完成任务"));
        // 状态机两清单：进入护栏与退出条件（可核对谓词）
        assertTrue(prompt.contains("entryConstraints"));
        assertTrue(prompt.contains("exitConditions"));
        assertTrue(prompt.contains("可核对的达成谓词"));
        assertTrue(prompt.contains("\"entryConstraints\":["));
        assertTrue(prompt.contains("\"exitConditions\":["));
        assertTrue(prompt.contains("\"stageNo\":2"));
        assertTrue(prompt.contains("\"startChapter\":11"));
        assertTrue(prompt.contains("\"endChapter\":55"));
    }

    @Test
    void buildGenerationPrompt_carriesExitConditionReviewForCarryForward() {
        StageBlueprintEntity previous = blueprint(1, 1, 10);
        previous.setStageGoal("站稳外门");
        previous.setExitResults(List.of(
                new StageBlueprintEntity.ExitConditionResult("主角突破至炼气九层", false, null, null, "境界仍为炼气八层"),
                new StageBlueprintEntity.ExitConditionResult("幽冥谷与外门公开敌对", true, 9, "公开敌对", null)));
        StoryContextEntity longStory = StoryContextEntity.builder()
                .novel_title("镜中仙途").theme("仙侠").chapterCount(200).build();

        String prompt = service.buildGenerationPrompt(longStory, previous, List.of(), List.of(),
                RollingOutlineService.StageWindow.adaptive(2, 11), 200);

        // 核验结果进入生成 prompt：未达标机械化结转，已达标给证据
        assertTrue(prompt.contains("【上一阶段退出条件核验】"));
        assertTrue(prompt.contains("未达成：主角突破至炼气九层（缺口：境界仍为炼气八层）"));
        assertTrue(prompt.contains("已达成：幽冥谷与外门公开敌对（证据：第9章「公开敌对」）"));
        // 退场规则：未达成条件由系统原样注入下阶段 exitConditions 并重验一次，
        // 模型不得重复登记、不得改写措辞、不得标放弃
        assertTrue(prompt.contains("原样注入"));
        assertTrue(prompt.contains("严禁改写其措辞或标为放弃"));
        assertTrue(prompt.contains("本阶段 tasks 必须包含使其达成的路径"));
    }

    @Test
    void buildGenerationPrompt_fixedFirstStageStatesSystemWindow() {
        // 24 章书：固定首版 1-10 窗口不触及全书末章，保持默认窗长表述
        StoryContextEntity story = StoryContextEntity.builder()
                .novel_title("回南天").theme("悬疑").chapterCount(24).build();
        String prompt = service.buildGenerationPrompt(story, null, List.of(), List.of(),
                RollingOutlineService.StageWindow.fixed(1, 1), 24);

        assertTrue(prompt.contains("窗口由系统默认值固定"));
        assertTrue(prompt.contains("第 1-10 章"));
        assertTrue(prompt.contains("\"endChapter\":10"));
    }

    @Test
    void buildGenerationPrompt_firstVersionMarksNoPreviousAndNoSummaries() {
        String prompt = service.buildGenerationPrompt(story(), null, List.of(), List.of(),
                RollingOutlineService.StageWindow.fixed(1, 1), 5);

        assertTrue(prompt.contains("（无——这是第一版阶段蓝图）"));
        assertTrue(prompt.contains("（尚无章节摘要"));
        assertTrue(prompt.contains("（暂无）"));
    }

    @Test
    void buildGenerationPrompt_recentSummariesCappedToTen() {
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        for (int no = 1; no <= RollingOutlineService.BLUEPRINT_SUMMARY_COUNT + 3; no++) {
            summaries.add(summary(no));
        }

        String prompt = service.buildGenerationPrompt(story(), null, summaries, List.of(),
                RollingOutlineService.StageWindow.adaptive(2, 11), 5);

        assertTrue(prompt.contains("第13章"));
        assertFalse(prompt.contains("第1章《"));
        assertFalse(prompt.contains("第3章《"));
        assertTrue(prompt.contains("第4章"));
    }

    @Test
    void parse_fixedFirstStageOverridesModelWindowAndCapsTasks() {
        String raw = "{\"stageNo\":9,\"startChapter\":99,\"endChapter\":999,\"stageGoal\":\" 目标 \","
                + "\"tasks\":[" + tasks(RollingOutlineService.MAX_TASKS + 2) + "],"
                + "\"carriedTasks\":[{\"content\":\"旧任务\",\"status\":\"\",\"note\":\"说明\"}],\"extra\":\"未知字段\"}";

        StageBlueprintEntity blueprint = service.parse(raw, null, 1, 0);

        // 首版窗口以默认值写死（不信任模型输出的章号）；空任务剔除；结转状态兜底；未知字段容忍
        assertEquals(1, blueprint.getStageNo());
        assertEquals(1, blueprint.getStartChapter());
        assertEquals(10, blueprint.getEndChapter());
        assertEquals("目标", blueprint.getStageGoal());
        assertEquals(RollingOutlineService.MAX_TASKS, blueprint.getTasks().size());
        assertEquals("任务1", blueprint.getTasks().get(0));
        assertEquals("进行中", blueprint.getCarriedTasks().get(0).getStatus());
        assertEquals("说明", blueprint.getCarriedTasks().get(0).getNote());
    }

    @Test
    void parse_adaptiveStageClampsModelEndWithinThirtyToEighty() {
        StageBlueprintEntity previous = blueprint(1, 1, 10);
        // 起点 11 → 钳制区间 [40, 90]：区间内采纳，越界钳制，漏报取区间下限；序号/起点机械给定
        StageBlueprintEntity normal = service.parse(blueprintRaw(55), previous, 11, 0);
        assertEquals(2, normal.getStageNo());
        assertEquals(11, normal.getStartChapter());
        assertEquals(55, normal.getEndChapter());
        assertEquals(40, service.parse(blueprintRaw(20), previous, 11, 0).getEndChapter());
        assertEquals(90, service.parse(blueprintRaw(999), previous, 11, 0).getEndChapter());
        assertEquals(40, service.parse("{\"stageGoal\":\"目标\"}", previous, 11, 0).getEndChapter());
    }

    @Test
    void parse_adaptiveStageNeverExceedsBookEnd() {
        StageBlueprintEntity previous = blueprint(1, 1, 10);
        // 全书 24 章：起点 11 的钳制区间收缩为 [24, 24]，无论模型报多少都强制收束全书
        assertEquals(24, service.parse(blueprintRaw(55), previous, 11, 24).getEndChapter());
        assertEquals(24, service.parse(blueprintRaw(20), previous, 11, 24).getEndChapter());
        assertEquals(24, service.parse("{\"stageGoal\":\"目标\"}", previous, 11, 24).getEndChapter());
    }

    @Test
    void parse_fixedStageClampedToBookEnd() {
        // 短篇（8 章）起步首版：固定窗长 1-10 钳制到全书末章
        StageBlueprintEntity blueprint = service.parse(blueprintRaw(999), null, 1, 8);
        assertEquals(1, blueprint.getStartChapter());
        assertEquals(8, blueprint.getEndChapter());
    }

    @Test
    void parse_longBookKeepsStandardWindow() {
        // 长篇（200 章）不受影响：起点 11 仍是标准 [40, 90] 窗长
        StageBlueprintEntity previous = blueprint(1, 1, 10);
        assertEquals(90, service.parse(blueprintRaw(999), previous, 11, 200).getEndChapter());
    }

    @Test
    void parse_midFlightFirstBlueprintIsAdaptiveFromNextChapter() {
        // 老故事第 15 章接入：首版蓝图不写死 1-10，从下一章起按 30-80 章钳制（区间 [44, 94]）
        StageBlueprintEntity blueprint = service.parse(blueprintRaw(60), null, 15, 0);
        assertEquals(1, blueprint.getStageNo());
        assertEquals(15, blueprint.getStartChapter());
        assertEquals(60, blueprint.getEndChapter());
    }

    @Test
    void parse_capsEntryConstraintsAndExitConditions() {
        String raw = "{\"stageGoal\":\"目标\",\"endChapter\":55,"
                + "\"entryConstraints\":[\"护1\",\"护2\",\"护3\",\"护4\",\"护5\",\"护6\",\"护7\"],"
                + "\"exitConditions\":[\"条1\",\"条2\",\"条3\",\"条4\",\"条5\",\"条6\",\"条7\",\"条8\",\"条9\"]}";

        StageBlueprintEntity blueprint = service.parse(raw, null, 1, 0);

        // 新清单按上限封顶（护栏 6、退出条件 8）
        assertEquals(RollingOutlineService.MAX_ENTRY_CONSTRAINTS, blueprint.getEntryConstraints().size());
        assertEquals(RollingOutlineService.MAX_EXIT_CONDITIONS, blueprint.getExitConditions().size());
    }

    @Test
    void parse_capsCarriedTasks() {
        StringBuilder carried = new StringBuilder("[");
        for (int i = 0; i < RollingOutlineService.MAX_CARRIED_TASKS + 3; i++) {
            if (i > 0) {
                carried.append(",");
            }
            carried.append("{\"content\":\"旧任务").append(i).append("\",\"status\":\"完成\"}");
        }
        carried.append("]");
        String raw = "{\"stageGoal\":\"目标\",\"tasks\":[\"任务\"],\"carriedTasks\":" + carried + "}";

        StageBlueprintEntity blueprint = service.parse(raw, null, 1, 0);

        assertEquals(RollingOutlineService.MAX_CARRIED_TASKS, blueprint.getCarriedTasks().size());
        assertEquals("旧任务0", blueprint.getCarriedTasks().get(0).getContent());
    }

    @Test
    void parse_unparseableReturnsNull() {
        assertNull(service.parse("这不是JSON", null, 11, 0));
        assertNull(service.parse(null, null, 11, 0));
        assertNull(service.parse("{\"tasks\":\"不是数组\"}", null, 11, 0));
    }

    @Test
    void parse_hardTotalForcesFinalVolumeWhenEndReachesCap() {
        // 全书硬上限 88：起点 40 的窗口终点被钳制/收束到 88（直达上限），机械强制收官卷
        StageBlueprintEntity previous = blueprint(2, 30, 39);
        StageBlueprintEntity bp = service.parse(
                "{\"stageGoal\":\"目标\",\"endChapter\":120,\"storyPhase\":\"WAR\",\"finalVolumeDeclared\":false}",
                previous, 40, 88, 88);

        assertEquals(88, bp.getEndChapter());
        assertTrue(bp.getFinalVolumeDeclared());
        assertEquals("RESOLUTION", bp.getStoryPhase());
    }

    @Test
    void parse_hardTotalNotReachedKeepsPhaseAndFinalFlagFromModel() {
        // 同窗口但模型终点 70（落在标准窗 [69,88] 内，未达上限 88）：不强制收官，保留模型自评
        StageBlueprintEntity previous = blueprint(2, 30, 39);
        StageBlueprintEntity bp = service.parse(
                "{\"stageGoal\":\"目标\",\"endChapter\":70,\"storyPhase\":\"WAR\",\"finalVolumeDeclared\":false}",
                previous, 40, 88, 88);

        assertEquals(70, bp.getEndChapter());
        assertFalse(bp.getFinalVolumeDeclared());
        assertEquals("WAR", bp.getStoryPhase());
    }

    @Test
    void buildGenerationPrompt_hardTotalInjectsCapAndConvergenceAndFinalize() {
        StageBlueprintEntity previous = blueprint(1, 1, 10);
        StoryContextEntity story = StoryContextEntity.builder()
                .novel_title("镜中仙途").theme("仙侠").chapterCount(200).build();

        // ① 距上限很远：仅注入硬上限，不触发收敛/收官
        String far = service.buildGenerationPrompt(story, previous, List.of(), List.of(),
                RollingOutlineService.StageWindow.adaptive(2, 11), 200, 200);
        assertTrue(far.contains("全书硬性完结上限：第 200 章"));
        assertFalse(far.contains("收官收敛"));
        assertFalse(far.contains("是全书最后一个阶段"));

        // ② 距上限 ≤ CONVERGENCE_LEAD：进入收官收敛（不开新线/逐步清空终局节点）
        String converge = service.buildGenerationPrompt(story, previous, List.of(), List.of(),
                RollingOutlineService.StageWindow.adaptive(2, 150), 200, 200);
        assertTrue(converge.contains("收官收敛"));
        assertFalse(converge.contains("是全书最后一个阶段"));

        // ③ 窗口直达上限：强制收官卷（EPILOGUE + 终局节点全部完成）
        String finalize = service.buildGenerationPrompt(story, previous, List.of(), List.of(),
                RollingOutlineService.StageWindow.adaptive(2, 190), 200, 200);
        assertTrue(finalize.contains("是全书最后一个阶段"));
        assertTrue(finalize.contains("storyPhase 必须为 EPILOGUE"));
    }

    @Test
    void buildGenerationPrompt_finalStageForcesWrapUpAndForeshadowRecovery() {
        // 批次末章 24、阶段起点 11：剩余不足一个标准窗长 → 强制 endChapter=24 并要求伏笔全回收
        StoryContextEntity story = StoryContextEntity.builder()
                .novel_title("回南天").theme("悬疑").chapterCount(24).build();
        RollingOutlineService.StageWindow window = service.nextWindow(blueprint(1, 1, 10), 11);

        String prompt = service.buildGenerationPrompt(story, blueprint(1, 1, 10), List.of(), List.of(), window, 24);

        assertTrue(prompt.contains("覆盖至第 24 章"));
        assertTrue(prompt.contains("直接收束到批次末章"));
        assertTrue(prompt.contains("待回收伏笔"));
    }

    @Test
    void segmentBatch_singleStageReturnsWholeBatch() {
        StageBlueprintEntity stage1 = blueprint(1, 1, 10);
        List<RollingOutlineService.StageSegment> segments = service.segmentBatch(List.of(stage1), 1, 5);

        assertEquals(1, segments.size());
        assertEquals(1, segments.get(0).startChapter());
        assertEquals(5, segments.get(0).endChapter());
        assertEquals(stage1, segments.get(0).blueprint());
    }

    @Test
    void segmentBatch_splitsAcrossStageBoundary() {
        StageBlueprintEntity stage1 = blueprint(1, 1, 10);
        StageBlueprintEntity stage2 = blueprint(2, 11, 55);
        List<RollingOutlineService.StageSegment> segments = service.segmentBatch(List.of(stage1, stage2), 8, 17);

        assertEquals(2, segments.size());
        assertEquals(8, segments.get(0).startChapter());
        assertEquals(10, segments.get(0).endChapter());
        assertEquals(stage1, segments.get(0).blueprint());
        assertEquals(11, segments.get(1).startChapter());
        assertEquals(17, segments.get(1).endChapter());
        assertEquals(stage2, segments.get(1).blueprint());
    }

    @Test
    void segmentBatch_batchEndingAtBoundaryStaysInOneSegment() {
        StageBlueprintEntity stage1 = blueprint(1, 1, 10);
        StageBlueprintEntity stage2 = blueprint(2, 11, 55);
        List<RollingOutlineService.StageSegment> segments = service.segmentBatch(List.of(stage1, stage2), 1, 10);

        assertEquals(1, segments.size());
        assertEquals(10, segments.get(0).endChapter());
        assertEquals(stage1, segments.get(0).blueprint());
    }

    @Test
    void segmentBatch_tailWithoutCoverageFallsBackToLatestBlueprint() {
        // 蓝图链缺口（第 2 阶段生成失败）：第 11 章起回退上一版蓝图，与 fail-soft 语义一致
        StageBlueprintEntity stage1 = blueprint(1, 1, 10);
        List<RollingOutlineService.StageSegment> segments = service.segmentBatch(List.of(stage1), 8, 17);

        assertEquals(2, segments.size());
        assertEquals(stage1, segments.get(0).blueprint());
        assertEquals(11, segments.get(1).startChapter());
        assertEquals(17, segments.get(1).endChapter());
        assertEquals(stage1, segments.get(1).blueprint());
    }

    @Test
    void segmentBatch_noBlueprintsReturnsWholeBatchWithNullBlueprint() {
        List<RollingOutlineService.StageSegment> segments = service.segmentBatch(null, 1, 5);

        assertEquals(1, segments.size());
        assertEquals(1, segments.get(0).startChapter());
        assertEquals(5, segments.get(0).endChapter());
        assertNull(segments.get(0).blueprint());
    }

    @Test
    void segmentBatch_invalidRangeReturnsEmpty() {
        assertTrue(service.segmentBatch(null, 5, 1).isEmpty());
    }

    @Test
    void needsVolumeGeneration_triggersWhenMissingOrBeyondCoverage() {
        assertTrue(service.needsVolumeGeneration(null, 1));
        assertTrue(service.needsVolumeGeneration(List.of(), 1));
        assertTrue(service.needsVolumeGeneration(List.of(volume(1, 1, 300)), 301));
        assertTrue(service.needsVolumeGeneration(List.of(volume(1, 1, null)), 1));
        // 最新卷覆盖区间未及下一章才重新生成：区间内复用
        assertFalse(service.needsVolumeGeneration(List.of(volume(1, 1, 300)), 300));
        assertFalse(service.needsVolumeGeneration(List.of(volume(1, 1, 300)), 1));
    }

    @Test
    void nextVolumeStart_noVolumeFallsBackToNextChapter() {
        assertEquals(5, service.nextVolumeStart(null, 5));
        assertEquals(5, service.nextVolumeStart(List.of(volume(1, 1, null)), 5));
    }

    @Test
    void volumeAt_picksVolumeCoveringTheChapterNotTheNewest() {
        // 批次跨卷边界：上一卷终点落在批次中间（300），新卷从 301 起——
        // 第 150 章属于第 1 卷，若取最新卷会把第 2 卷方向提前泄给旧卷章节
        List<VolumeBlueprintEntity> volumes = List.of(volume(1, 1, 300), volume(2, 301, 600));
        assertEquals(1, service.volumeAt(volumes, 150).getVolumeNo());
        assertEquals(1, service.volumeAt(volumes, 300).getVolumeNo());
        assertEquals(2, service.volumeAt(volumes, 301).getVolumeNo());
        assertEquals(2, service.volumeAt(volumes, 599).getVolumeNo());
    }

    @Test
    void volumeAt_returnsNullWhenChapterPrecedesEveryVolume() {
        // 卷生成失败退化为两段式 / 章号早于所有卷起点：不注入方向锚（也不回退最新卷）
        assertNull(service.volumeAt(null, 5));
        assertNull(service.volumeAt(List.of(), 5));
        assertNull(service.volumeAt(List.of(volume(1, 100, 400)), 99));
    }

    @Test
    void nextVolumeStart_subsequentVolumeContinuesAfterPreviousEnd() {
        // 卷与卷连续覆盖：下一卷起点 = 上一卷终点 + 1，不留缺口
        assertEquals(301, service.nextVolumeStart(List.of(volume(1, 1, 300)), 500));
    }

    @Test
    void normalizeVolume_chainsVolumeNoAndStartFromPrevious() {
        VolumeBlueprintEntity previous = volume(3, 290, 480);
        VolumeBlueprintEntity out = service.normalizeVolume(
                VolumeBlueprintEntity.builder().endChapter(900).build(), previous, 500, 0, null);

        assertEquals(4, out.getVolumeNo());
        assertEquals(481, out.getStartChapter());
    }

    @Test
    void normalizeVolume_clampsEndWithinVolumeWindow() {
        // 起点 1 → 钳制卷窗 [300, 800]：区间内采纳，越界钳制，漏报取下限
        VolumeBlueprintEntity norm = service.normalizeVolume(
                VolumeBlueprintEntity.builder().endChapter(500).build(), null, 1, 0, null);
        assertEquals(1, norm.getVolumeNo());
        assertEquals(1, norm.getStartChapter());
        assertEquals(500, norm.getEndChapter());
        assertEquals(300, service.normalizeVolume(
                VolumeBlueprintEntity.builder().endChapter(50).build(), null, 1, 0, null).getEndChapter());
        assertEquals(800, service.normalizeVolume(
                VolumeBlueprintEntity.builder().endChapter(9999).build(), null, 1, 0, null).getEndChapter());
        assertEquals(300, service.normalizeVolume(
                VolumeBlueprintEntity.builder().build(), null, 1, 0, null).getEndChapter());
    }

    @Test
    void normalizeVolume_respectsHardTotalCap() {
        VolumeBlueprintEntity out = service.normalizeVolume(
                VolumeBlueprintEntity.builder().endChapter(9999).build(), null, 1, 0, 450);
        assertEquals(450, out.getEndChapter());
    }

    @Test
    void normalizeVolume_capsListsAndDropsBlanks() {
        List<String> many = new ArrayList<>(List.of("承甲", "转乙", "合丙", "  ", "胜丁"));
        for (int i = 6; i <= RollingOutlineService.MAX_VOLUME_EXIT_CONDITIONS + 3; i++) {
            many.add("条" + i);
        }
        VolumeBlueprintEntity out = service.normalizeVolume(VolumeBlueprintEntity.builder()
                .endChapter(500)
                .beats(many)
                .arcPlan(List.of(
                        VolumeBlueprintEntity.ArcPlan.builder().oneLineGoal(null).build(),
                        VolumeBlueprintEntity.ArcPlan.builder().arcNo(7).oneLineGoal(" 封内门 ").build(),
                        VolumeBlueprintEntity.ArcPlan.builder().oneLineGoal("").build()))
                .volumeExitConditions(many)
                .seeds(many)
                .build(), null, 1, 0, null);

        assertEquals(RollingOutlineService.MAX_VOLUME_BEATS, out.getBeats().size());
        assertEquals("胜丁", out.getBeats().get(3));
        assertEquals(1, out.getArcPlan().size());
        assertEquals(7, out.getArcPlan().get(0).getArcNo());
        assertEquals("封内门", out.getArcPlan().get(0).getOneLineGoal());
        assertEquals(RollingOutlineService.MAX_VOLUME_EXIT_CONDITIONS, out.getVolumeExitConditions().size());
        assertEquals(RollingOutlineService.MAX_VOLUME_SEEDS, out.getSeeds().size());
    }

    @Test
    void parseVolume_unparseableReturnsNull() {
        assertNull(service.parseVolume("这不是JSON", null, 1, 0, null));
        assertNull(service.parseVolume(null, null, 1, 0, null));
        assertNull(service.parseVolume("{\"beats\":\"不是数组\"}", null, 1, 0, null));
    }

    @Test
    void parseVolume_parsesAndNormalizesWindow() {
        VolumeBlueprintEntity out = service.parseVolume(
                "{\"volumeNo\":9,\"startChapter\":99,\"endChapter\":9999,\"themeShift\":\" 蜕变  \",\"extra\":\"忽略\"}",
                null, 1, 0, null);

        assertEquals(1, out.getVolumeNo());
        assertEquals(1, out.getStartChapter());
        assertEquals(800, out.getEndChapter());
        assertEquals("蜕变", out.getThemeShift());
    }

    @Test
    void buildVolumePrompt_firstVolumeMarksNoPreviousAndNoSummaries() {
        String prompt = service.buildVolumePrompt(story(), null, List.of(),
                new RollingOutlineService.VolumeWindow(1, 1), 300);

        assertTrue(prompt.contains("主角身世之谜"));
        assertTrue(prompt.contains("小陈"));
        assertTrue(prompt.contains("（无——这是第一卷）"));
        assertTrue(prompt.contains("（尚无章节摘要"));
        assertTrue(prompt.contains("第 1 章起（第 1 卷）"));
        assertTrue(prompt.contains("arcPlan"));
        assertTrue(prompt.contains("volumeExitConditions"));
        assertTrue(prompt.contains("\"volumeNo\":1"));
        assertTrue(prompt.contains("\"startChapter\":1"));
    }

    @Test
    void buildVolumePrompt_carriesPreviousAndRecentSummaries() {
        VolumeBlueprintEntity previous = volume(1, 1, 300);
        previous.setThemeShift("外门站稳");
        previous.setBeats(List.of("承甲", "转乙"));
        List<ChapterSummaryEntity> summaries = List.of(summary(298), summary(301));

        String prompt = service.buildVolumePrompt(story(), previous, summaries,
                new RollingOutlineService.VolumeWindow(2, 301), 600);

        assertTrue(prompt.contains("上一卷终点：第300章"));
        assertTrue(prompt.contains("卷主旨：外门站稳"));
        assertTrue(prompt.contains("- 承甲"));
        assertTrue(prompt.contains("第301章"));
        assertTrue(prompt.contains("第 301 章起（第 2 卷）"));
    }

    @Test
    void buildVolumePrompt_hardTotalDeclaresFinalVolumeConvergence() {
        String prompt = service.buildVolumePrompt(story(), null, List.of(),
                new RollingOutlineService.VolumeWindow(1, 1), 120);

        assertTrue(prompt.contains("全书硬性完结上限：第 120 章"));
        assertTrue(prompt.contains("themeShift 必须指向最终状态转变"));
        assertTrue(prompt.contains("禁止为凑体量新增长期主线或新势力"));
    }

    @Test
    void buildGenerationPrompt_withVolume_injectsDirectionAnchor() {
        VolumeBlueprintEntity volume = VolumeBlueprintEntity.builder()
                .volumeNo(1).title("外门篇").startChapter(1).endChapter(300)
                .themeShift("站稳外门，宿敌成形")
                .beats(List.of("承甲", "转乙"))
                .seeds(List.of("古物"))
                .arcPlan(List.of(VolumeBlueprintEntity.ArcPlan.builder().arcNo(1).oneLineGoal("封内门立身").build()))
                .build();
        StageBlueprintEntity previous = blueprint(1, 1, 10);

        String prompt = service.buildGenerationPrompt(story(), previous, List.of(), List.of(),
                RollingOutlineService.StageWindow.adaptive(2, 11), 80, 500, volume);

        assertTrue(prompt.contains("【所属卷方向锚】"));
        assertTrue(prompt.contains("第 1 卷《外门篇》（第 1-300 章）"));
        assertTrue(prompt.contains("卷主旨：站稳外门，宿敌成形"));
        assertTrue(prompt.contains("卷承转合：承甲；转乙"));
        assertTrue(prompt.contains("卷级伏笔（滚向卷尾回收）：古物"));
        assertTrue(prompt.contains("【弧1】封内门立身"));
        // 非末卷：不注入末卷细收敛框架
        assertFalse(prompt.contains("本弧位于全书末卷"));
        // JSON 示例带回卷归属字段
        assertTrue(prompt.contains("arcGoal"));
        assertTrue(prompt.contains("\"volumeNo\":1"));
    }

    @Test
    void buildGenerationPrompt_finalVolume_injectsFineConvergence() {
        VolumeBlueprintEntity finalVolume = VolumeBlueprintEntity.builder()
                .volumeNo(2).title("终局篇").startChapter(301).endChapter(400)
                .themeShift("天地重塑，尘埃落定").build();
        // hardTotal=400，卷终点 400 越过上限 → 全书末卷
        String prompt = service.buildGenerationPrompt(story(), null, List.of(), List.of(),
                RollingOutlineService.StageWindow.adaptive(1, 301), 400, 400, finalVolume);

        assertTrue(prompt.contains("本弧位于全书末卷"));
        assertTrue(prompt.contains("较卷级粗收敛更细地清空终局"));
        assertTrue(prompt.contains("严禁拖戏或注水"));

        // 非末卷不触发
        VolumeBlueprintEntity notFinal = VolumeBlueprintEntity.builder()
                .volumeNo(1).startChapter(1).endChapter(300).build();
        String nonFinal = service.buildGenerationPrompt(story(), null, List.of(), List.of(),
                RollingOutlineService.StageWindow.adaptive(1, 1), 400, 400, notFinal);
        assertFalse(nonFinal.contains("本弧位于全书末卷"));
    }

    @Test
    void buildGenerationPrompt_withoutVolume_degradesToTwoStageLegacy() {
        // 存量故事/卷生成失败：7 参调用不注入卷方向锚（退化两段式）
        StageBlueprintEntity previous = blueprint(1, 1, 10);
        String prompt = service.buildGenerationPrompt(story(), previous, List.of(), List.of(),
                RollingOutlineService.StageWindow.adaptive(2, 11), 80, 80);

        assertFalse(prompt.contains("【所属卷方向锚】"));
        assertFalse(prompt.contains("本弧位于全书末卷"));
    }

    @Test
    void isFinalVolume_judgesByHardTotalCoverage() {
        VolumeBlueprintEntity volume = VolumeBlueprintEntity.builder().endChapter(400).build();
        assertTrue(service.isFinalVolume(volume, 400));
        assertTrue(service.isFinalVolume(volume, 350));
        assertFalse(service.isFinalVolume(volume, 500));
        assertFalse(service.isFinalVolume(volume, null));
        assertFalse(service.isFinalVolume(null, 400));
        assertFalse(service.isFinalVolume(VolumeBlueprintEntity.builder().build(), 400));
    }

    @Test
    void parse_keepsModelArcOwnershipFields() {
        String raw = "{\"stageGoal\":\"目标\",\"endChapter\":55,\"volumeNo\":1,\"volumeTitle\":\"外门篇\",\"arcNo\":2,\"arcGoal\":\"立门外门\"}";
        StageBlueprintEntity bp = service.parse(raw, null, 1, 0);
        assertEquals(2, bp.getArcNo());
        assertEquals("立门外门", bp.getArcGoal());
    }

    private String blueprintRaw(int endChapter) {
        return "{\"stageGoal\":\"目标\",\"endChapter\":" + endChapter + "}";
    }

    private VolumeBlueprintEntity volume(int volumeNo, int start, Integer end) {
        return VolumeBlueprintEntity.builder()
                .volumeNo(volumeNo)
                .startChapter(start)
                .endChapter(end)
                .build();
    }

    private String tasks(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            if (i > 1) {
                sb.append(",");
            }
            sb.append("\"任务").append(i).append("\"");
        }
        return sb.toString();
    }

    private StageBlueprintEntity blueprint(int stageNo, int start, Integer end) {
        return StageBlueprintEntity.builder()
                .stageNo(stageNo)
                .startChapter(start)
                .endChapter(end)
                .build();
    }

    /**
     * 阶段蓝图 prompt 必须真的索取 coreSuspense / suspenseLadder。
     * ⚠️ 这条测试守的是一个**静默失效**风险：档位表由蓝图产出，若蓝图 prompt 没要它，
     * 档位表恒为 null，于是计划侧不注入锚块、校验侧直接跳过、体检也不出指标——
     * 整条"治原地转圈"的链路会**一声不响地全部空转**，而人只看日志会以为它跑了。
     */
    @Test
    void buildGenerationPrompt_requiresSuspenseLadder() {
        RollingOutlineService.StageWindow window = service.nextWindow(null, 1);

        String prompt = service.buildGenerationPrompt(story(), null, List.of(), List.of(),
                window, window.startChapter() + 5, null, null);

        assertTrue(prompt.contains("coreSuspense"), "蓝图必须产出核心悬念");
        assertTrue(prompt.contains("suspenseLadder"), "蓝图必须产出档位表");
        assertTrue(prompt.contains("不得连续 3 章"), "必须写明机械校验规则，否则模型不知道会踩线");
        assertTrue(prompt.contains("可观察"), "档位要可观察，否则退回内心感受就无法机械比较");
        assertTrue(prompt.contains("\"suspenseLadder\":["), "JSON schema 必须给出回填字段");
    }

    /**
     * 悬念档位补采 prompt：只问两个字段、结构完整、示例用正确的键名。
     *
     * <p>它存在的理由来自实测——同一条要求放进完整蓝图 prompt（十几个要求 + 长 schema）会被模型
     * 静默省略，而短聚焦 prompt 服从率极高。因此这条 prompt 必须**很短且只问这两项**，
     * 别把上下文又堆回来（那等于把问题复制一遍）。
     */
    @Test
    void buildSuspenseLadderRepairPrompt_isShortAndAsksOnlyTwoFields() {
        StageBlueprintEntity blueprint = StageBlueprintEntity.builder()
                .stageNo(1).stageGoal("确立死敌关系与线上初遇")
                .tasks(List.of("线下冲突升级", "线上结成固定搭档"))
                .build();

        String prompt = service.buildSuspenseLadderRepairPrompt(story(), blueprint, List.of());

        assertTrue(prompt.contains("只补这两项"), "要明确告诉模型这是补字段，不是重出蓝图");
        assertTrue(prompt.contains("coreSuspense"));
        assertTrue(prompt.contains("suspenseLadder"));
        assertTrue(prompt.contains("可观察"), "档位必须可观察，否则退回内心感受就无法机械比较");
        assertTrue(prompt.contains("不得连续 3 章"), "要写明机械规则");
        assertTrue(prompt.contains("确立死敌关系与线上初遇"), "带上本阶段目标做约束");
        assertTrue(prompt.length() < 1200, "补采 prompt 要短——长了就会重演'被省略'");
    }

    @Test
    void parseSuspenseLadderPatch_acceptsUsableAndRejectsUnusable() {
        RollingOutlineService.SuspenseLadderPatch ok = service.parseSuspenseLadderPatch(
                "{\"coreSuspense\":\"身份何时暴露\",\"suspenseLadder\":[\"双方不知\",\"一方起疑\",\"摊牌\"]}");
        assertNotNull(ok);
        assertEquals(3, ok.suspenseLadder().size());
        assertEquals("身份何时暴露", ok.coreSuspense());

        assertNull(service.parseSuspenseLadderPatch(
                        "{\"coreSuspense\":\"x\",\"suspenseLadder\":[\"只有一档\"]}"),
                "不足两档视为不可用");
        assertNull(service.parseSuspenseLadderPatch("这不是 JSON"));
        assertNull(service.parseSuspenseLadderPatch(null));
    }

    private StoryContextEntity story() {
        return StoryContextEntity.builder()
                .novel_title("镜中仙途")
                .theme("仙侠")
                .style("热血")
                .protagonist("小陈")
                .outline("主角身世之谜")
                .chapterCount(5)
                .build();
    }

    private ChapterSummaryEntity summary(int chapterNo) {
        return ChapterSummaryEntity.builder()
                .chapterNo(chapterNo)
                .title("第" + chapterNo + "章")
                .summary("剧情推进。")
                .build();
    }


    // ==================== 伏笔兑现排期表（P2b） ====================

    private static StageBlueprintEntity stage11to55() {
        return StageBlueprintEntity.builder().stageNo(2).startChapter(11).endChapter(55)
                .stageGoal("主角筑基").tasks(List.of("突破")).build();
    }

    private static ForeshadowScheduleEntity.ScheduleItem item(String intent, int plant, int payoff) {
        return ForeshadowScheduleEntity.ScheduleItem.builder()
                .intent(intent).plantChapter(plant).payoffChapter(payoff).build();
    }

    /** 跨阶段要求只在"还有后续章节"时才写进 prompt——否则是要求模型做不可能的事 */
    @Test
    void scheduleRepairPrompt_requiresCrossStageOnlyWhenRoomExists() {
        RollingOutlineService service = new RollingOutlineService();

        String roomy = service.buildForeshadowScheduleRepairPrompt(null, stage11to55(), 300, List.of(), List.of());
        assertTrue(roomy.contains("至少 1 条"), "还有后续章节时必须强制跨阶段：" + roomy);
        assertTrue(roomy.contains("大于 55"), roomy);

        String closing = service.buildForeshadowScheduleRepairPrompt(null, stage11to55(), 55, List.of(), List.of());
        assertFalse(closing.contains("至少 1 条"),
                "全书在本阶段收束时不得再要求跨阶段（永远无法满足）：" + closing);
        assertTrue(closing.contains("必须在本阶段收束") || closing.contains("收完"), closing);
    }

    /** 补采 prompt 必须带上"已结转的长线"，否则模型会重复排同一条 */
    @Test
    void scheduleRepairPrompt_listsCarriedItems() {
        String prompt = new RollingOutlineService().buildForeshadowScheduleRepairPrompt(
                null, stage11to55(), 300,
                List.of(item("顾老头是否收徒", 12, 30)), List.of());

        assertTrue(prompt.contains("不要重复排"), prompt);
        assertTrue(prompt.contains("顾老头是否收徒"), prompt);
    }

    /**
     * 时序锚注入（2026-10-04）：新书首段零摘要时，排期补采必须带设定兜底锚——
     * 否则会产出"4岁主角无意识写出未来年份日期"这类违反时序锚的 intent
     * （实测：经【必须埋设】强压给计划与写手后，审校按锚判 BLOCKING，修订无法不丢事件地修复，整链死锁）。
     */
    @Test
    void scheduleRepairPrompt_injectsTimeAnchorForFreshBook() {
        StoryContextEntity ctx = StoryContextEntity.builder()
                .novel_title("重生四岁").theme("都市").style("温馨")
                .protagonist("角色丙")
                .worldSetting("主角以4岁幼童的身份重生到2002年的江南巷子。")
                .outline("重生者守护家人，从巷子起步。")
                .chapterCount(5)
                .build();
        String prompt = new RollingOutlineService().buildForeshadowScheduleRepairPrompt(
                ctx, stage11to55(), 300, List.of(), List.of());

        assertTrue(prompt.contains("【时序锚】"), "无摘要时必须用设定兜底锚：" + prompt);
        assertTrue(prompt.contains("4岁"), prompt);
        assertTrue(prompt.contains("不得要求主角做出超出其阶段极限的可观察动作"), prompt);
    }

    /** 逐条格式校验：任一不合格即整表退回（半张表比没有更危险——打标会大面积漏配） */
    @Test
    void parseSchedule_rejectsInvalidItems() {
        RollingOutlineService service = new RollingOutlineService();
        StageBlueprintEntity stage = stage11to55();

        // 合法
        assertNotNull(service.parseForeshadowSchedulePatch(
                "{\"foreshadowSchedule\":[{\"intent\":\"a\",\"plantChapter\":12,\"payoffChapter\":30}]}", stage, 300));
        // plant 在阶段区间外
        assertNull(service.parseForeshadowSchedulePatch(
                "{\"foreshadowSchedule\":[{\"intent\":\"a\",\"plantChapter\":5,\"payoffChapter\":30}]}", stage, 300));
        // payoff 不大于 plant
        assertNull(service.parseForeshadowSchedulePatch(
                "{\"foreshadowSchedule\":[{\"intent\":\"a\",\"plantChapter\":20,\"payoffChapter\":20}]}", stage, 300));
        // payoff 超出全书上限
        assertNull(service.parseForeshadowSchedulePatch(
                "{\"foreshadowSchedule\":[{\"intent\":\"a\",\"plantChapter\":12,\"payoffChapter\":400}]}", stage, 300));
        // 缺 intent
        assertNull(service.parseForeshadowSchedulePatch(
                "{\"foreshadowSchedule\":[{\"plantChapter\":12,\"payoffChapter\":30}]}", stage, 300));
        assertNull(service.parseForeshadowSchedulePatch("{}", stage, 300));
    }

    /** 解析成功时机械回填 span 与初始状态 */
    @Test
    void parseSchedule_fillsSpanAndStatus() {
        RollingOutlineService.ForeshadowSchedulePatch patch = new RollingOutlineService()
                .parseForeshadowSchedulePatch(
                        "{\"foreshadowSchedule\":[{\"intent\":\"a\",\"plantChapter\":20,\"payoffChapter\":45}]}",
                        stage11to55(), 300);

        assertNotNull(patch);
        assertEquals(25, patch.foreshadowSchedule().get(0).getSpan(), "span = payoff - plant 应机械回填");
        assertEquals(ForeshadowScheduleEntity.STATUS_PLANNED, patch.foreshadowSchedule().get(0).getStatus());
    }

    /**
     * 链式结转去重：**保留结转版**——它带 status 履历（已 PLANTED 的线不该被打回 PLANNED，
     * 否则打标会重来一遍）。
     */
    @Test
    void mergeScheduleItems_keepsCarriedVersionOnDuplicate() {
        ForeshadowScheduleEntity.ScheduleItem carried = item("顾老头是否收徒", 12, 30);
        carried.setStatus(ForeshadowScheduleEntity.STATUS_PLANTED);
        ForeshadowScheduleEntity.ScheduleItem fresh = item("顾老头是否收徒。", 13, 40); // 归一后同 key

        List<ForeshadowScheduleEntity.ScheduleItem> merged = new RollingOutlineService()
                .mergeScheduleItems(List.of(carried), List.of(fresh, item("新线", 20, 60)));

        assertEquals(2, merged.size(), "重复项应被去重：" + merged);
        assertEquals(ForeshadowScheduleEntity.STATUS_PLANTED, merged.get(0).getStatus(),
                "重复时应保留结转版（带 status 履历）");
        assertEquals(12, merged.get(0).getPlantChapter());
    }

    /** 只结转活线：PAID/MISSED/DROPPED 出表 */
    @Test
    void carriableItems_keepsOnlyActive() {
        ForeshadowScheduleEntity.ScheduleItem planned = item("a", 1, 10);
        planned.setStatus(ForeshadowScheduleEntity.STATUS_PLANNED);
        ForeshadowScheduleEntity.ScheduleItem paid = item("b", 2, 11);
        paid.setStatus(ForeshadowScheduleEntity.STATUS_PAID);
        ForeshadowScheduleEntity.ScheduleItem missed = item("c", 3, 12);
        missed.setStatus(ForeshadowScheduleEntity.STATUS_MISSED);

        List<ForeshadowScheduleEntity.ScheduleItem> carried = ForeshadowScheduleEntity.carriableItems(
                ForeshadowScheduleEntity.builder().items(List.of(planned, paid, missed)).build());

        assertEquals(1, carried.size(), "已兑现/已落空的线不该继续结转：" + carried);
        assertEquals("a", carried.get(0).getIntent());
    }
}
