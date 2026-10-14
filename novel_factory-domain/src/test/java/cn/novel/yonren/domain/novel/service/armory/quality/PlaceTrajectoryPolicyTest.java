package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 地点轨迹判据测试。
 *
 * <p>背景（162 章实测）：地点只存在于 timePoint 自由文本且从不回灌规划层，
 * 导致每章新地点率 0.827、10 章窗口内中位 10 个不同地点（= 每章都在换新场景）。
 */
class PlaceTrajectoryPolicyTest {

    private static ChapterSummaryEntity summary(int ch, String placePoint, String timePoint) {
        return ChapterSummaryEntity.builder()
                .chapterNo(ch).placePoint(placePoint).timePoint(timePoint).build();
    }

    @Test
    void placePointTakesPriorityOverTimePoint() {
        assertEquals("主角居所", PlaceTrajectoryPolicy.placeOf(
                summary(1, "主角居所", "外门大比当日深夜，某个不一致的旧地点")));
    }

    @Test
    void fallsBackToLastSegmentOfTimePointForLegacyData() {
        // 老数据无 placePoint：摘要 prompt 的样例是"时间，地点"，地点在后
        assertEquals("主角居所", PlaceTrajectoryPolicy.placeOf(
                summary(1, null, "外门大比当日深夜，主角居所")));
        assertEquals("阵眼核心大殿内", PlaceTrajectoryPolicy.placeOf(
                summary(2, null, "当日深夜，阵眼核心大殿内")));
    }

    @Test
    void blankInputsYieldNullSoTheyNeverPolluteStats() {
        assertNull(PlaceTrajectoryPolicy.placeOf(null));
        assertNull(PlaceTrajectoryPolicy.placeOf(summary(1, "  ", "   ")));
        assertNull(PlaceTrajectoryPolicy.extractFromTimePoint(null));
        assertNull(PlaceTrajectoryPolicy.extractFromTimePoint(" "));
    }

    @Test
    void singleShortSegmentFallsBackToWholeString() {
        assertEquals("密室内", PlaceTrajectoryPolicy.extractFromTimePoint("密室内"));
        // 切段后最后一段只有 1 字 → 退整串，不制造空值
        assertEquals("夜，山", PlaceTrajectoryPolicy.extractFromTimePoint("夜，山"));
    }

    @Test
    void churnAlarmOnlyWhenEveryChapterIsANewPlace() {
        assertTrue(PlaceTrajectoryPolicy.isChurnAlarming(10, 10));
        assertTrue(PlaceTrajectoryPolicy.isChurnAlarming(11, 10));
        assertFalse(PlaceTrajectoryPolicy.isChurnAlarming(9, 10));
        assertFalse(PlaceTrajectoryPolicy.isChurnAlarming(0, 0));
    }

    @Test
    void trajectoryIsEmptyWithoutAnyPlaceSignal() {
        assertTrue(PlaceTrajectoryPolicy.recentTrajectory(List.of(), 10).isEmpty());
        assertNull(PlaceTrajectoryPolicy.renderTrajectory(List.of()));
        assertNull(PlaceTrajectoryPolicy.renderTrajectory(List.of(summary(1, null, null))));
    }

    @Test
    void trajectoryListsReusedPlacesAndAppendsWarningOnChurn() {
        List<ChapterSummaryEntity> churn = List.of(
                summary(1, "甲", null), summary(2, "乙", null), summary(3, "丙", null),
                summary(4, "丁", null), summary(5, "戊", null));

        String block = PlaceTrajectoryPolicy.renderTrajectory(churn);

        assertEquals(true, block != null);
        assertTrue(block.contains("第1章：甲"));
        assertTrue(block.contains("第5章：戊"));
        assertTrue(block.contains("每个地点都只出现过一次"));
        assertTrue(block.contains("⚠ 警示"));
    }

    @Test
    void trajectoryWindowOnlyLooksBackConfiguredChapters() {
        // 只回看 TRAJECTORY_WINDOW 章：更早的地点不进入轨迹块
        int window = PlaceTrajectoryPolicy.TRAJECTORY_WINDOW;
        List<ChapterSummaryEntity> many = new java.util.ArrayList<>();
        many.add(summary(1, "很久以前的地方", null));
        for (int ch = 2; ch <= window + 2; ch++) {
            many.add(summary(ch, "讲堂", null));
        }

        String block = PlaceTrajectoryPolicy.renderTrajectory(many);

        assertEquals(false, block.contains("很久以前的地方"));
        // 窗口 = 最后 window 章，全部是"讲堂"
        assertTrue(block.contains("讲堂×" + window));
        assertFalse(block.contains("⚠ 警示"), "窗口内高度复用，不应警示");
    }

    @Test
    void trajectoryRequiresConsistentPlaceNaming() {
        // 命名一致性改为"源头约束"而非"机械归并"：
        // 曾按公共子串把变体名归并，但在 162 章基线上把「九渊封存处」建筑群下 33 个子区域
        //（外围冰瀑下 / 最高处葬剑台 / 核心虚空内 / 前往途中…）全部吞并成一个地点——
        // 同一建筑群的不同子区域与"同一处的不同叫法"字面不可分，归并会抹平真实换场。
        // 故本块保持字面统计，改为明确要求规划层统一命名。
        List<ChapterSummaryEntity> summaries = List.of(
                summary(1, "某宅客厅", null),
                summary(2, "角色丙老家客厅", null));

        String block = PlaceTrajectoryPolicy.renderTrajectory(summaries);

        assertTrue(block.contains("命名要求"), "应给出命名一致性要求：" + block);
        assertTrue(block.contains("不得另起叫法"), block);
        // 两个叫法按字面仍是两个地点——口径诚实，不做不可靠的归并
        assertTrue(block.contains("某宅客厅") && block.contains("角色丙老家客厅"),
                "字面统计：不同叫法各自呈现，交由规划层统一：" + block);
    }
}
