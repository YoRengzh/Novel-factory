package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.service.armory.quality.PlaceTrajectoryPolicy;
import cn.novel.yonren.types.enums.ChapterType;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段节奏报告服务：阶段收束时的纯机械统计（字数/伏笔/账本变更），零 LLM 成本。
 * 报告写回蓝图的 stageReport 随 rolling-outline.json 落盘，并注入下一版蓝图生成 prompt，
 * 供下一阶段规划校准节奏（失衡即警示，不硬阻塞——弱信号不当闸门）
 */
@Service
public class StageReportService {

    /** 章均字数警示阈值：低于此值提示正文密度不足 */
    private static final int AVG_LENGTH_WARN_THRESHOLD = 1500;

    /**
     * 构建阶段节奏报告（纯函数）；区间内无正文时返回 null
     */
    public String buildStageReport(StageBlueprintEntity stage,
                                   List<ChapterSummaryEntity> summaries,
                                   List<ChapterContentEntity> contents) {
        if (stage == null || stage.getStartChapter() == null || stage.getEndChapter() == null) {
            return null;
        }
        int start = stage.getStartChapter();
        int end = stage.getEndChapter();
        List<ChapterContentEntity> stageContents = inRangeContents(contents, start, end);
        if (stageContents.isEmpty()) {
            return null;
        }
        List<ChapterSummaryEntity> stageSummaries = inRangeSummaries(summaries, start, end);

        int totalLength = 0;
        for (ChapterContentEntity content : stageContents) {
            totalLength += content.getContent() == null ? 0 : content.getContent().length();
        }
        int chapterCount = stageContents.size();
        int avgLength = totalLength / chapterCount;

        int foreshadowNew = 0;
        int foreshadowResolved = 0;
        int characterUpdates = 0;
        int itemUpdates = 0;
        int factionUpdates = 0;
        for (ChapterSummaryEntity summary : stageSummaries) {
            foreshadowNew += count(summary.getForeshadowingNew());
            foreshadowResolved += count(summary.getForeshadowingResolved());
            characterUpdates += count(summary.getCharacterStates());
            itemUpdates += count(summary.getItemStates());
            factionUpdates += count(summary.getFactionStates());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("- 章节：第 ").append(start).append("-").append(end).append(" 章共 ").append(chapterCount)
                .append(" 章，合计 ").append(totalLength).append(" 字（章均 ").append(avgLength).append(" 字）\n");
        sb.append("- 伏笔：新埋 ").append(foreshadowNew).append(" 条 / 回收 ").append(foreshadowResolved).append(" 条\n");
        sb.append("- 账本更新：角色 ").append(characterUpdates).append(" 项 / 物品 ").append(itemUpdates)
                .append(" 项 / 势力 ").append(factionUpdates).append(" 项\n");

        // 地点维度 新增）：本阶段不同地点数 / 章均新地点数 + 过渡章数。
        // 报告此前只统字数/伏笔/账本，地点信号完全缺位——而"每章换场"正是由此无人校准的
        Map<String, Integer> placeFreq = new LinkedHashMap<>();
        for (ChapterSummaryEntity summary : stageSummaries) {
            String place = PlaceTrajectoryPolicy.placeOf(summary);
            if (StringUtils.isNotBlank(place)) {
                placeFreq.merge(place, 1, Integer::sum);
            }
        }
        int distinctPlaces = placeFreq.size();
        long transitionCount = stageSummaries.stream()
                .filter(s -> ChapterType.of(s.getChapterType()).isTransition()).count();
        if (distinctPlaces > 0) {
            sb.append("- 地点：本阶段出现 ").append(distinctPlaces).append(" 个不同地点（")
                    .append(String.format("%.2f", (double) distinctPlaces / chapterCount))
                    .append(" 个/章）；其中复用 ≥2 章的地点 ")
                    .append(placeFreq.values().stream().filter(v -> v >= 2).count()).append(" 个\n");
        }
        sb.append("- 章型：过渡章 ").append(transitionCount).append(" 章 / 共 ").append(chapterCount).append(" 章\n");

        if (characterUpdates == 0) {
            sb.append("- 警示：本阶段角色账本零更新，剧情对格局推进不足。\n");
        }
        if (foreshadowNew > 0 && foreshadowResolved == 0) {
            sb.append("- 警示：本阶段伏笔只埋未收，下一阶段需安排回收。\n");
        }
        if (avgLength < AVG_LENGTH_WARN_THRESHOLD) {
            sb.append("- 警示：章均字数偏低，注意正文信息密度。\n");
        }
        if (distinctPlaces >= chapterCount) {
            sb.append("- 警示：本阶段地点数 ≥ 章数（**每章都在换新场景**），空间感无法建立，")
                    .append("下一阶段必须显著回收地点、让同一地点连续承载多章。\n");
        }
        if (transitionCount * 3 > chapterCount) {
            sb.append("- 警示：过渡章占比超过 1/3，节奏偏松，下一阶段应提高推进章比例。\n");
        }
        return sb.toString();
    }

    private List<ChapterContentEntity> inRangeContents(List<ChapterContentEntity> contents, int start, int end) {
        if (contents == null) {
            return List.of();
        }
        return contents.stream()
                .filter(c -> c.getChapterNo() != null && c.getChapterNo() >= start && c.getChapterNo() <= end)
                .sorted(Comparator.comparing(ChapterContentEntity::getChapterNo))
                .toList();
    }

    private List<ChapterSummaryEntity> inRangeSummaries(List<ChapterSummaryEntity> summaries, int start, int end) {
        if (summaries == null) {
            return List.of();
        }
        return summaries.stream()
                .filter(s -> s.getChapterNo() != null && s.getChapterNo() >= start && s.getChapterNo() <= end)
                .sorted(Comparator.comparing(ChapterSummaryEntity::getChapterNo))
                .toList();
    }

    private int count(List<?> values) {
        return values == null ? 0 : values.size();
    }
}
