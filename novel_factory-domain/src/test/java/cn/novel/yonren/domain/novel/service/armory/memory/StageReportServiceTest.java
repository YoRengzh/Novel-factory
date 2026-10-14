package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.types.enums.ChapterTypeVO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段节奏报告测试。
 *
 * <p>2026-09-16 起报告新增两个维度：**地点**（本阶段不同地点数 / 章均新地点）与**章型**
 *（过渡章数）。此前报告只统字数/伏笔/账本，而"每章换场"正因为无人统计而无人校准。
 */
class StageReportServiceTest {

    private final StageReportService service = new StageReportService();

    private static StageBlueprintEntity stage(int start, int end) {
        return StageBlueprintEntity.builder().stageNo(1).startChapter(start).endChapter(end).build();
    }

    private static ChapterContentEntity content(int ch, int length) {
        return ChapterContentEntity.builder().chapterNo(ch).title("第" + ch + "章")
                .content("字".repeat(length)).build();
    }

    private static ChapterSummaryEntity summary(int ch, String place, String type) {
        return ChapterSummaryEntity.builder().chapterNo(ch).placePoint(place).chapterType(type).build();
    }

    @Test
    void noContentsReturnsNull() {
        assertNull(service.buildStageReport(stage(1, 4), List.of(), List.of()));
    }

    @Test
    void reportsPlaceAndChapterTypeDimensions() {
        List<ChapterContentEntity> contents = List.of(
                content(1, 2000), content(2, 2000), content(3, 2000));
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        summaries.add(summary(1, "讲堂", null));
        summaries.add(summary(2, "讲堂", ChapterTypeVO.TRANSITION.getCode()));
        summaries.add(summary(3, "讲堂", ChapterTypeVO.NORMAL.getCode()));

        String report = service.buildStageReport(stage(1, 3), summaries, contents);

        assertNotNull(report);
        assertTrue(report.contains("- 地点：本阶段出现 1 个不同地点（0.33 个/章）；其中复用 ≥2 章的地点 1 个"));
        assertTrue(report.contains("- 章型：过渡章 1 章 / 共 3 章"));
        assertFalse(report.contains("每章都在换新场景"), "地点高度复用，不应警示");
        assertFalse(report.contains("过渡章占比超过 1/3"), "1/3 未超过阈值（需严格大于）");
    }

    @Test
    void warnsWhenEveryChapterIsANewPlace() {
        List<ChapterContentEntity> contents = List.of(
                content(1, 2000), content(2, 2000), content(3, 2000), content(4, 2000));
        List<ChapterSummaryEntity> summaries = List.of(
                summary(1, "甲地", null), summary(2, "乙地", null),
                summary(3, "丙地", null), summary(4, "丁地", null));

        String report = service.buildStageReport(stage(1, 4), summaries, contents);

        assertNotNull(report);
        assertTrue(report.contains("每章都在换新场景"), "地点数 ≥ 章数即每章换新场景，必须警示");
    }

    @Test
    void warnsWhenTransitionShareExceedsThird() {
        List<ChapterContentEntity> contents = List.of(
                content(1, 2000), content(2, 2000), content(3, 2000));
        List<ChapterSummaryEntity> summaries = List.of(
                summary(1, "讲堂", ChapterTypeVO.TRANSITION.getCode()),
                summary(2, "讲堂", ChapterTypeVO.TRANSITION.getCode()),
                summary(3, "讲堂", null));

        String report = service.buildStageReport(stage(1, 3), summaries, contents);

        assertNotNull(report);
        assertTrue(report.contains("过渡章占比超过 1/3"), "2/3 过渡章必须警示节奏偏松");
    }

    @Test
    void legacySummariesWithoutPlacePointFallBackToTimePoint() {
        // 老数据无 placePoint：由 timePoint 最后一段回退，保证存量故事同样受统计覆盖
        List<ChapterContentEntity> contents = List.of(content(1, 2000), content(2, 2000));
        List<ChapterSummaryEntity> summaries = List.of(
                ChapterSummaryEntity.builder().chapterNo(1).timePoint("当日深夜，讲堂密室").build(),
                ChapterSummaryEntity.builder().chapterNo(2).timePoint("次日黎明，讲堂密室").build());

        String report = service.buildStageReport(stage(1, 2), summaries, contents);

        assertNotNull(report);
        assertTrue(report.contains("1 个不同地点"));
    }
}
