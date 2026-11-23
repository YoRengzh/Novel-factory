package cn.novel.yonren.domain.novel.service.armory.quality;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NarrativeStylePolicyTest {
    @Test
    void findsCorrectionsWithDifferentPunctuationAndInterveningClause() {
        var observed = NarrativeStylePolicy.inspect("不是血，痂壳没裂，是汗。不是暗下去的。是灭的。"
                + "不是生气，也不是好奇，只是确认。“不是打量，是比对。”要不是下雨，船早到了。");
        assertEquals(3, observed.negativeTurns().size());
        assertTrue(observed.negativeTurns().contains("不是暗下去的。是灭的"));
    }

    @Test
    void excludesCommonLiteralUsesAndDialogue() {
        var observed = NarrativeStylePolicy.inspect("他画了幅画像。这里不像样子。柜角像一把锉刀。冷得像冰似的。"
                + "他只剩一息尚存。十来息过去，他又等了几息。「再等三息。」三样东西，一根线。");
        assertEquals(2, observed.similes().size());
        assertEquals(2, observed.breathTimings().size());
        assertEquals(1, observed.summaryCues().size());
    }

    @Test
    void flagsAClusterThatChapterAverageWouldHide() {
        String first = "水面像一块铜镜。";
        String second = "缆绳像墨线。";
        String third = "桨柄像秤杆。";
        String filler = "风从破帆边钻过去。".repeat(130);
        String dispersed = first + filler + second + filler + third;
        String clustered = first + second + third + filler + filler;
        assertTrue(NarrativeStylePolicy.inspect(dispersed).issues(2400).isEmpty());
        var issues = NarrativeStylePolicy.inspect(clustered).issues(2400);
        assertEquals(1, issues.size());
        assertTrue(issues.get(0).getDescription().contains("邻近"));
        assertTrue(NarrativeStylePolicy.regressionReason(dispersed, clustered).contains("明喻"));
    }

    @Test
    void comparisonKeepsExistingRepetitionAndIsolatedUsefulExpressions() {
        String before = "不是催促，是告别。不是打量，是比对。桌面像个棋盘。";
        assertNull(NarrativeStylePolicy.regressionReason(before, before + "木纹像潮水。"));
        assertNotNull(NarrativeStylePolicy.regressionReason(before, before + "不是许可，是试探。"));
        assertNull(NarrativeStylePolicy.regressionReason(before, before.replace("告别", "告辞")));
    }
}
