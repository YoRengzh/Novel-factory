package cn.novel.yonren.infrastructure.calibration;

import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 审计噪声度量（纯函数，docs/enhancement-plan.md A1）：对同一样本的 k 次审计结果算稳定性指标。
 *
 * 四个指标，各对应一种"抽卡"症状：
 *
 *   issue 条数 CV——总量抖动（变异系数，0=完全稳定）；
 *   BLOCKING 出现率——k 次里有多少次判出 BLOCKING；率不在 {0,1} 即翻车（出现率漂移）；
 *   结果集合 Jaccard——按 (dimension,severity) 多重集的成对均值，衡量"找出的问题集合"
 *       稳不稳（description 措辞逐次漂移，不参与匹配）；
 *   分维度计数 CV——哪个维度最抖，A2 目录化优先级据此排序。
 *
 * 空结果（两次都 0 条）视为完全稳定：Jaccard=1.0、CV=0——"都没发现问题"是可复现结论。
 */
final class AuditEvalMetrics {

    /** 跨次可比的 issue 摘要：措辞会漂移，只有维度与严重度可对齐 */
    record IssueDigest(String dimension, String severity) {
    }

    record DimensionNoise(String dimension, double meanCount, double cv) {
    }

    record SampleNoise(int chapterNo, int runs,
                       List<Integer> issueCounts, double issueCountCv,
                       double blockingAppearanceRate, boolean blockingStable,
                       double setJaccardMean,
                       List<DimensionNoise> dimensions) {
    }

    private AuditEvalMetrics() {
    }

    static IssueDigest digest(ChapterIssueEntity issue) {
        if (issue == null) {
            return null;
        }
        return new IssueDigest(
                issue.getDimension() == null ? "?" : issue.getDimension(),
                issue.getSeverity() == null ? "?" : issue.getSeverity());
    }

    static SampleNoise evaluate(int chapterNo, List<List<IssueDigest>> runs) {
        int runCount = runs.size();
        List<Integer> counts = new ArrayList<>(runCount);
        for (List<IssueDigest> run : runs) {
            counts.add(run.size());
        }
        long blockingRuns = runs.stream()
                .filter(run -> run.stream().anyMatch(i -> "BLOCKING".equalsIgnoreCase(i.severity())))
                .count();
        double blockingRate = runCount == 0 ? 0 : (double) blockingRuns / runCount;

        double jaccardSum = 0;
        int pairs = 0;
        for (int i = 0; i < runCount; i++) {
            for (int j = i + 1; j < runCount; j++) {
                jaccardSum += multisetJaccard(runs.get(i), runs.get(j));
                pairs++;
            }
        }
        double jaccardMean = pairs == 0 ? 1.0 : jaccardSum / pairs;

        Set<String> dimensions = new LinkedHashSet<>();
        for (List<IssueDigest> run : runs) {
            for (IssueDigest digest : run) {
                dimensions.add(digest.dimension());
            }
        }
        List<DimensionNoise> dimStats = new ArrayList<>();
        for (String dimension : dimensions) {
            List<Integer> perRun = new ArrayList<>(runCount);
            for (List<IssueDigest> run : runs) {
                int count = 0;
                for (IssueDigest digest : run) {
                    if (digest.dimension().equals(dimension)) {
                        count++;
                    }
                }
                perRun.add(count);
            }
            dimStats.add(new DimensionNoise(dimension, mean(perRun), cv(perRun)));
        }

        return new SampleNoise(chapterNo, runCount, counts, cv(counts),
                blockingRate, blockingRate == 0.0 || blockingRate == 1.0, jaccardMean, dimStats);
    }

    /** 多重集 Jaccard：交集（按键取 min 计数）/ 并集（按键取 max 计数）；两侧全空 = 1.0 */
    static double multisetJaccard(List<IssueDigest> a, List<IssueDigest> b) {
        Map<String, Long> ca = countByKey(a);
        Map<String, Long> cb = countByKey(b);
        Set<String> keys = new LinkedHashSet<>(ca.keySet());
        keys.addAll(cb.keySet());
        long min = 0;
        long max = 0;
        for (String key : keys) {
            long left = ca.getOrDefault(key, 0L);
            long right = cb.getOrDefault(key, 0L);
            min += Math.min(left, right);
            max += Math.max(left, right);
        }
        return max == 0 ? 1.0 : (double) min / max;
    }

    /** 变异系数（标准差/均值）；均值为 0（全程 0 条）视为 0——空集是稳定结论不是缺失数据 */
    static double cv(List<Integer> values) {
        double avg = mean(values);
        if (avg <= 0) {
            return 0.0;
        }
        double variance = 0;
        for (int value : values) {
            variance += (value - avg) * (value - avg);
        }
        return Math.sqrt(variance / values.size()) / avg;
    }

    static double mean(List<Integer> values) {
        return values.stream().mapToInt(Integer::intValue).average().orElse(0);
    }

    private static Map<String, Long> countByKey(List<IssueDigest> run) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (IssueDigest digest : run) {
            counts.merge(digest.dimension() + "|" + digest.severity(), 1L, Long::sum);
        }
        return counts;
    }
}
