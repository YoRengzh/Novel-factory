package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NarrativeProgressionPolicyTest {

    @Test
    void toleratesMissingAndLegacyData() {
        assertTrue(NarrativeProgressionPolicy.issues(null, null).isEmpty());
        assertTrue(NarrativeProgressionPolicy.issues(StageBlueprintEntity.builder().startChapter(1).build(), null).isEmpty());
        assertTrue(NarrativeProgressionPolicy.issues(plan(1, "不同的推进"), Arrays.asList(null,
                ChapterSummaryEntity.builder().build())).isEmpty());
    }

    @Test
    void catchesExactRestatementEvenWhenLineTagsChange() {
        List<String> issues = NarrativeProgressionPolicy.issues(plan(1,
                "【学习】甲拒绝担保，因此失去乙的支持。", "【关系】甲拒绝担保，因此失去乙的支持！"), null);
        assertEquals(1, issues.size());
        assertTrue(issues.get(0).contains("完全相同"));
    }

    @Test
    void preservesMeaningfulNumbers() {
        assertTrue(NarrativeProgressionPolicy.issues(plan(1,
                "【本金】甲与乙协商后取得10%的股权", "【本金】甲与乙协商后取得20%的股权"), null).isEmpty());
    }

    @Test
    void preservesDecimalPointsAndNumericSigns() {
        assertTrue(NarrativeProgressionPolicy.issues(plan(1,
                "甲与乙协商后取得1.5%的股权", "甲与乙协商后取得15%的股权"), null).isEmpty());
        assertTrue(NarrativeProgressionPolicy.issues(plan(1,
                "甲向乙报告今天账户收益为-10元", "甲向乙报告今天账户收益为10元"), null).isEmpty());
    }

    @Test
    void negatedOrRefusedActionsAreNotSuccessAndApplause() {
        assertTrue(NarrativeProgressionPolicy.issues(plan(1,
                "发现机房故障，主角未能修复，同事并不佩服",
                "遇到邻里难题，主角拒绝解决，邻居没有赞叹"), null).isEmpty());
    }

    @Test
    void allowsOnePayoffWithoutForcingFailureOrCost() {
        assertTrue(NarrativeProgressionPolicy.issues(plan(1,
                "发现水泵故障，主角修好水泵，乡亲惊叹", "乡亲兑现承诺，主角拿到第一份报酬"), null).isEmpty());
    }

    @Test
    void catchesReskinnedProblemSolutionApplauseCycles() {
        List<String> issues = NarrativeProgressionPolicy.issues(plan(1,
                "【程序】发现电脑故障，主角修复电脑，同事震惊",
                "【关系】遇到水管难题，主角修好水管，邻居惊叹"), null);
        assertEquals(1, issues.size());
        assertTrue(issues.get(0).contains("关键词启发式"));
        assertTrue(issues.get(0).contains("第 1-1 章、第 2-2 章"));
    }

    @Test
    void catchesCyclesSplitAcrossChapters() {
        List<String> issues = NarrativeProgressionPolicy.issues(plan(1,
                "发现电脑故障", "主角修复电脑", "同事震惊",
                "遇到水管难题", "主角修好水管", "邻居惊叹"), null);
        assertEquals(1, issues.size());
        assertTrue(issues.get(0).contains("第 1-3 章、第 4-6 章"));
    }

    @Test
    void usesActualHistoryAcrossBlueprintBoundary() {
        List<String> issues = NarrativeProgressionPolicy.issues(plan(11,
                "遇到水管难题，主角修好水管，邻居惊叹"), List.of(
                summary(10, "发现电脑故障，主角修复电脑，同事震惊")));
        assertEquals(1, issues.size());
        assertTrue(issues.get(0).contains("第 10-10 章、第 11-11 章"));
    }

    @Test
    void doesNotAskToRewriteHistoricalProblemsWhenNewPlanIsDifferent() {
        assertTrue(NarrativeProgressionPolicy.issues(plan(11, "甲拒绝续约，乙因此转投对手"), List.of(
                summary(9, "发现电脑故障，主角修复电脑，同事震惊"),
                summary(10, "遇到水管难题，主角修好水管，邻居惊叹"))).isEmpty());
    }

    @Test
    void excludesFutureSummariesAndOutOfWindowBeats() {
        StageBlueprintEntity blueprint = plan(11, "甲拒绝续约，乙因此转投对手");
        blueprint.getMainLineByChapter().add(new StageBlueprintEntity.MainLineBeat(20,
                "遇到水管难题，主角修好水管，邻居惊叹"));
        assertTrue(NarrativeProgressionPolicy.issues(blueprint, List.of(
                summary(12, "发现电脑故障，主角修复电脑，同事震惊"))).isEmpty());
    }

    @Test
    void doesNotCountOverlappingWindowsAsMultipleCycles() {
        assertTrue(NarrativeProgressionPolicy.issues(plan(1,
                "发现电脑故障", "主角修复电脑", "同事震惊", "主角受到同事赞叹"), null).isEmpty());
    }

    @Test
    void doesNotInventCyclesAcrossMissingChapters() {
        StageBlueprintEntity blueprint = plan(11, "甲拒绝续约，乙因此转投对手");
        assertTrue(NarrativeProgressionPolicy.issues(blueprint, List.of(
                summary(1, "发现电脑故障"), summary(5, "主角修复电脑"), summary(10, "同事震惊"))).isEmpty());
    }

    @Test
    void requiresProblemThenSolutionThenReactionInThatOrder() {
        assertTrue(NarrativeProgressionPolicy.issues(plan(1,
                "众人惊叹先前的表演，随后发现电脑故障，主角修复电脑",
                "邻居佩服他的耐心，后来遇到水管难题，主角修好水管"), null).isEmpty());
    }

    @Test
    void boundsHistoricalWindow() {
        List<ChapterSummaryEntity> history = new ArrayList<>();
        history.add(summary(1, "发现电脑故障，主角修复电脑，同事震惊"));
        for (int i = 2; i <= 11; i++) {
            history.add(summary(i, "过往独立事件" + i));
        }
        assertTrue(NarrativeProgressionPolicy.issues(plan(12,
                "遇到水管难题，主角修好水管，邻居惊叹"), history).isEmpty());
    }

    private StageBlueprintEntity plan(int start, String... advances) {
        List<StageBlueprintEntity.MainLineBeat> beats = new ArrayList<>();
        for (int i = 0; i < advances.length; i++) {
            beats.add(new StageBlueprintEntity.MainLineBeat(start + i, advances[i]));
        }
        return StageBlueprintEntity.builder().startChapter(start).endChapter(start + advances.length - 1)
                .mainLineByChapter(beats).build();
    }

    private ChapterSummaryEntity summary(int chapterNo, String text) {
        return ChapterSummaryEntity.builder().chapterNo(chapterNo).summary(text).build();
    }
}
