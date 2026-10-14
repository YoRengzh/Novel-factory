package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.QualityDebtEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.types.enums.ChapterTypeVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批末体检测试：逐指标分级、总体聚合（多项劣化升级 CRITICAL）、以及**对改造前真实基线的干跑验证**
 * ——体检层若在已知病态的 162 章数据上判"健康"，它就失去了区分力。
 */
class BatchHealthServiceTest {

    private final BatchHealthService service = new BatchHealthService();

    // ---------------- 构造辅助 ----------------

    /** 造一批"健康"章节：账本全部入账、地点复用、过渡章占比合规、字数达标 */
    private static List<ChapterSummaryEntity> healthyChapters(int count) {
        List<ChapterSummaryEntity> list = new ArrayList<>();
        for (int ch = 1; ch <= count; ch++) {
            list.add(ChapterSummaryEntity.builder()
                    .chapterNo(ch)
                    .validChars(2200)
                    .chapterType(ch == 2 || ch == 7 ? ChapterTypeVO.TRANSITION.getCode()
                            : ChapterTypeVO.NORMAL.getCode())
                    .placePoint(ch <= 3 ? "讲堂" : (ch <= 6 ? "后山禁地" : "议事厅"))
                    .characterStates(List.of(entry("陆沉", "exact")))
                    .build());
        }
        return list;
    }

    private static ChapterSummaryEntity.StateEntry entry(String name, String tier) {
        ChapterSummaryEntity.StateEntry e = new ChapterSummaryEntity.StateEntry(name, "状态", "证据");
        e.setEvidenceTier(tier);
        return e;
    }

    private static QualityDebtEntity debt(boolean resolved) {
        return QualityDebtEntity.builder().chapterNo(1).resolved(resolved).issues(List.of()).build();
    }

    private static StageBlueprintEntity stageWith(boolean met, Integer atoms, Integer metAtoms) {
        StageBlueprintEntity.ExitConditionResult r =
                new StageBlueprintEntity.ExitConditionResult("条件", met, met ? 3 : null,
                        met ? "证据" : null, null, metAtoms, atoms);
        return StageBlueprintEntity.builder().stageNo(1).exitResults(List.of(r)).build();
    }

    // ---------------- 分级 ----------------

    @Test
    @DisplayName("样本不足：不给出健康结论，明确提示样本数与阈值需校准")
    void insufficientSample() {
        BatchHealthReport report = service.assess(healthyChapters(3), List.of(), List.of());

        assertEquals(3, report.chapterCount());
        assertTrue(report.metrics().isEmpty(), "样本不足时不给指标，避免给出虚假的健康结论");
        assertTrue(report.recommendations().get(0).contains("样本不足"));
        assertTrue(report.recommendations().get(0).contains(BatchHealthService.THRESHOLD_NOTE));
    }

    @Test
    @DisplayName("阶段规划覆盖：蓝图止点落后 = 本批在旧蓝图下滑行，应 DEGRADED 并给补跑建议")
    void stagePlanningCoverage_uncoveredBlueprintIsDegraded() {
        // 蓝图 fail-soft 降级的显式化——蓝图止点落后于已写末章 = 本批在旧蓝图下滑行
        List<ChapterSummaryEntity> chapters = healthyChapters(12);
        StageBlueprintEntity stale = StageBlueprintEntity.builder().stageNo(7).endChapter(5).build();

        BatchHealthReport report = service.assess(chapters, List.of(), List.of(stale));

        BatchHealthReport.Metric m = metric(report, "stagePlanningCoverage");
        assertEquals(BatchHealthReport.Level.DEGRADED, m.level());
        assertTrue(m.detail().contains("第 6-12 章在旧蓝图下生成"), m.detail());
        assertTrue(report.recommendations().stream().anyMatch(r -> r.contains("阶段规划缺位")), "应给出补跑建议");
    }

    @Test
    void stagePlanningCoverage_coveredIsOk() {
        List<ChapterSummaryEntity> chapters = healthyChapters(12);
        StageBlueprintEntity covering = StageBlueprintEntity.builder().stageNo(8).endChapter(12).build();

        BatchHealthReport report = service.assess(chapters, List.of(), List.of(covering));

        assertEquals(BatchHealthReport.Level.OK, metric(report, "stagePlanningCoverage").level());
        assertTrue(report.recommendations().stream().noneMatch(r -> r.contains("阶段规划缺位")));
    }

    @Test
    void stagePlanningCoverage_skippedForLegacyNoBlueprintStories() {
        // 从未启用蓝图的故事（无蓝图模式）不算降级——本闸只针对"蓝图链存在但止点落后"
        BatchHealthReport report = service.assess(healthyChapters(12), List.of(), List.of());
        assertFalse(metricOrNone(report, "stagePlanningCoverage"), "无蓝图故事不应渲染该条目");
    }

    @Test
    @DisplayName("全部健康：总体 OK、无需人工过问")
    void allHealthy() {
        BatchHealthReport report = service.assess(healthyChapters(12), List.of(), List.of());

        assertEquals(BatchHealthReport.Level.OK, report.overall());
        assertFalse(report.needsAttention());
        assertTrue(report.recommendations().isEmpty(), "无越线指标就不该给建议");
        assertTrue(report.render().contains("各指标均在健康线内"));
    }

    @Test
    @DisplayName("账本挂起过半：账本完整度判 DEGRADED 并给出裁决通道建议")
    void ledgerIncomplete() {
        List<ChapterSummaryEntity> chapters = new ArrayList<>();
        for (int ch = 1; ch <= 12; ch++) {
            ChapterSummaryEntity s = healthyChapters(1).get(0);
            s.setChapterNo(ch);
            // 挂起多于入账 → 完整度 < 50%
            s.setPendingFacts(List.of(entry("甲", null), entry("乙", null), entry("丙", null)));
            chapters.add(s);
        }

        BatchHealthReport report = service.assess(chapters, List.of(), List.of());

        BatchHealthReport.Metric metric = metric(report, "ledgerCompleteness");
        assertEquals(BatchHealthReport.Level.DEGRADED, metric.level());
        assertTrue(report.recommendations().stream().anyMatch(r -> r.contains("挂起裁决通道")));
    }

    @Test
    @DisplayName("每章换新地点：新地点率判 DEGRADED 并指向【地点轨迹】回灌")
    void placeChurn() {
        List<ChapterSummaryEntity> chapters = new ArrayList<>();
        for (int ch = 1; ch <= 12; ch++) {
            chapters.add(ChapterSummaryEntity.builder()
                    .chapterNo(ch).validChars(2200)
                    .chapterType(ChapterTypeVO.NORMAL.getCode())
                    .placePoint("地点" + ch)   // 12 章 12 个地点
                    .characterStates(List.of(entry("陆沉", "exact")))
                    .build());
        }

        BatchHealthReport report = service.assess(chapters, List.of(), List.of());

        assertEquals(BatchHealthReport.Level.DEGRADED, metric(report, "newPlaceRate").level());
        assertTrue(report.recommendations().stream().anyMatch(r -> r.contains("地点轨迹")));
    }

    @Test
    @DisplayName("过渡章占比为 0：判 DEGRADED（改造前正是如此——章型无法声明）")
    void transitionShareZero() {
        List<ChapterSummaryEntity> chapters = new ArrayList<>();
        for (int ch = 1; ch <= 12; ch++) {
            ChapterSummaryEntity s = healthyChapters(1).get(0);
            s.setChapterNo(ch);
            s.setChapterType(ChapterTypeVO.NORMAL.getCode());
            chapters.add(s);
        }

        BatchHealthReport report = service.assess(chapters, List.of(), List.of());

        assertEquals(BatchHealthReport.Level.DEGRADED, metric(report, "transitionShare").level());
        assertTrue(report.recommendations().stream().anyMatch(r -> r.contains("没有过渡章")));
    }

    @Test
    @DisplayName("过渡章占比过高：判 DEGRADED 并提示节奏偏松")
    void transitionShareTooHigh() {
        List<ChapterSummaryEntity> chapters = new ArrayList<>();
        for (int ch = 1; ch <= 12; ch++) {
            ChapterSummaryEntity s = healthyChapters(1).get(0);
            s.setChapterNo(ch);
            s.setChapterType(ch <= 8 ? ChapterTypeVO.TRANSITION.getCode() : ChapterTypeVO.NORMAL.getCode());
            chapters.add(s);
        }

        BatchHealthReport report = service.assess(chapters, List.of(), List.of());

        assertEquals(BatchHealthReport.Level.DEGRADED, metric(report, "transitionShare").level());
        assertTrue(report.recommendations().stream().anyMatch(r -> r.contains("过渡章占比偏高")));
    }

    @Test
    @DisplayName("低密度章占比：过渡章被排除，不把有意留白算作供给不足")
    void lowDensityExcludesTransition() {
        List<ChapterSummaryEntity> chapters = new ArrayList<>();
        for (int ch = 1; ch <= 10; ch++) {
            ChapterSummaryEntity s = healthyChapters(1).get(0);
            s.setChapterNo(ch);
            s.setChapterType(ChapterTypeVO.NORMAL.getCode());
            s.setValidChars(1000);              // 全部短
            chapters.add(s);
        }
        // 前 4 章改标过渡章：短但不计入分母
        for (int ch = 0; ch < 4; ch++) {
            chapters.get(ch).setChapterType(ChapterTypeVO.TRANSITION.getCode());
        }

        BatchHealthReport report = service.assess(chapters, List.of(), List.of());

        BatchHealthReport.Metric metric = metric(report, "lowDensityShare");
        assertTrue(metric.detail().contains("6 / 6 章"), "分母应排除 4 个过渡章，实际明细：" + metric.detail());
        assertEquals(100.0, metric.value(), 0.001);
    }

    @Test
    @DisplayName("质量债积压：≤3 OK、4~8 WATCH、>8 DEGRADED")
    void unresolvedDebtTiers() {
        List<QualityDebtEntity> ok = new ArrayList<>();
        for (int i = 0; i < 3; i++) ok.add(debt(false));
        assertEquals(BatchHealthReport.Level.OK,
                metric(service.assess(healthyChapters(12), ok, List.of()), "unresolvedDebt").level());

        List<QualityDebtEntity> watch = new ArrayList<>(ok);
        watch.add(debt(false));
        assertEquals(BatchHealthReport.Level.WATCH,
                metric(service.assess(healthyChapters(12), watch, List.of()), "unresolvedDebt").level());

        List<QualityDebtEntity> degraded = new ArrayList<>(watch);
        for (int i = 0; i < 5; i++) degraded.add(debt(false));
        assertEquals(BatchHealthReport.Level.DEGRADED,
                metric(service.assess(healthyChapters(12), degraded, List.of()), "unresolvedDebt").level());
    }

    @Test
    @DisplayName("已核销的债不计入未核销数")
    void resolvedDebtNotCounted() {
        List<QualityDebtEntity> debts = List.of(debt(true), debt(true), debt(true), debt(true), debt(true));
        assertEquals(0.0, metric(service.assess(healthyChapters(12), debts, List.of()), "unresolvedDebt").value());
    }

    @Test
    @DisplayName("出口条件达成率：老蓝图（无原子数据）用条件级口径，新蓝图附原子进度")
    void exitConditionRate() {
        List<StageBlueprintEntity> blueprints = new ArrayList<>();
        blueprints.add(stageWith(true, null, null));
        blueprints.add(stageWith(false, 3, 1));
        blueprints.add(stageWith(false, 2, 0));
        blueprints.add(stageWith(false, null, null));

        BatchHealthReport report = service.assess(healthyChapters(12), List.of(), blueprints);

        BatchHealthReport.Metric metric = metric(report, "exitConditionRate");
        assertEquals(25.0, metric.value(), 0.001, "1/4 条达成");
        assertTrue(metric.detail().contains("1 / 4 条"));
        assertTrue(metric.detail().contains("原子进度 1/5"), "实际：" + metric.detail());
    }

    @Test
    @DisplayName("放宽档入账占比：留痕/裁决档过半即判 DEGRADED（门被放宽过头的刹车表）")
    void looseTierShare() {
        List<ChapterSummaryEntity> chapters = new ArrayList<>();
        for (int ch = 1; ch <= 12; ch++) {
            ChapterSummaryEntity s = healthyChapters(1).get(0);
            s.setChapterNo(ch);
            s.setCharacterStates(List.of(entry("甲", "exact"), entry("乙", "spread"), entry("丙", "adjudicated")));
            chapters.add(s);
        }

        BatchHealthReport report = service.assess(chapters, List.of(), List.of());

        BatchHealthReport.Metric metric = metric(report, "looseTierShare");
        assertEquals(BatchHealthReport.Level.DEGRADED, metric.level());
        assertTrue(metric.detail().contains("24 / 36 条"), "实际：" + metric.detail());
    }

    @Test
    @DisplayName("三项同时劣化：总体升级为 CRITICAL（单层改造没生效的典型信号）")
    void multipleDegradedEscalatesToCritical() {
        List<ChapterSummaryEntity> chapters = new ArrayList<>();
        for (int ch = 1; ch <= 12; ch++) {
            chapters.add(ChapterSummaryEntity.builder()
                    .chapterNo(ch)
                    .validChars(800)                                  // 低密度 → DEGRADED
                    .chapterType(ChapterTypeVO.NORMAL.getCode())      // 无过渡章 → DEGRADED
                    .placePoint("地点" + ch)                           // 每章换场 → DEGRADED
                    .characterStates(List.of(entry("陆沉", "exact")))
                    .build());
        }

        BatchHealthReport report = service.assess(chapters, List.of(), List.of());

        assertEquals(BatchHealthReport.Level.CRITICAL, report.overall());
        assertTrue(report.needsAttention());
    }

    @Test
    @DisplayName("render：包含分级、逐项指标与建议")
    void renderContainsEverything() {
        BatchHealthReport report = service.assess(healthyChapters(12), List.of(), List.of());
        String text = report.render();

        assertTrue(text.contains("批末体检：OK"));
        assertTrue(text.contains("样本 12 章"));
        assertTrue(text.contains("账本完整度"));
        assertTrue(text.contains("每章新地点率"));
        assertFalse(text.contains("出口条件达成率"), "无阶段蓝图时不产生该指标");
    }

    @Test
    @DisplayName("最长连续过渡章：占比达标不等于分布合理，连续段要单独看")
    void maxTransitionRunIsTrackedSeparately() {
        // 12 章里第 3-5 章连续 3 个过渡章 → 总占比 25%（落在 8%~33% 健康区间内），
        // 但已违反"不得连续超过 2 章"的约束——单看占比这条塌陷完全不可见
        List<ChapterSummaryEntity> chapters = new ArrayList<>();
        for (int ch = 1; ch <= 12; ch++) {
            ChapterSummaryEntity s = healthyChapters(1).get(0);
            s.setChapterNo(ch);
            s.setChapterType((ch >= 3 && ch <= 5) ? ChapterTypeVO.TRANSITION.getCode()
                    : ChapterTypeVO.NORMAL.getCode());
            chapters.add(s);
        }

        BatchHealthReport report = service.assess(chapters, List.of(), List.of());

        BatchHealthReport.Metric metric = metric(report, "maxTransitionRun");
        assertEquals(3.0, metric.value(), 0.001);
        assertEquals(BatchHealthReport.Level.WATCH, metric.level(), "连续 3 章 = 越线一次");
        assertTrue(metric.detail().contains("第 3-5 章"), "应指出连续段位置，实际：" + metric.detail());
        assertTrue(metric(report, "transitionShare").level() == BatchHealthReport.Level.OK,
                "占比本身是达标的——这正是需要独立指标的原因");
    }

    @Test
    @DisplayName("最长连续过渡章：≥4 章 = DEGRADED（连续多段越线，节奏已塌）")
    void maxTransitionRunDegradesAtFour() {
        List<ChapterSummaryEntity> chapters = new ArrayList<>();
        for (int ch = 1; ch <= 10; ch++) {
            ChapterSummaryEntity s = healthyChapters(1).get(0);
            s.setChapterNo(ch);
            s.setChapterType((ch >= 2 && ch <= 6) ? ChapterTypeVO.TRANSITION.getCode()
                    : ChapterTypeVO.NORMAL.getCode());
            chapters.add(s);
        }

        BatchHealthReport report = service.assess(chapters, List.of(), List.of());

        BatchHealthReport.Metric metric = metric(report, "maxTransitionRun");
        assertEquals(5.0, metric.value(), 0.001);
        assertEquals(BatchHealthReport.Level.DEGRADED, metric.level());
        assertTrue(report.recommendations().stream().anyMatch(r -> r.contains("连续段越线")));
    }

    @Test
    @DisplayName("放宽档占比必须把一致性事实算进去：只看三账本状态会漏报")
    void looseTierShareIncludesConsistencyFacts() {
        // 5 条 exact 状态 + 3 条 adjudicated 一致性事实：旧口径只看状态账 → 0%（漏报），
        // 新口径把一致性事实计入 → 3/8 = 37.5%
        List<ChapterSummaryEntity> chapters = new ArrayList<>();
        for (int ch = 1; ch <= 5; ch++) {
            ChapterSummaryEntity s = healthyChapters(1).get(0);
            s.setChapterNo(ch);
            if (ch <= 3) {
                ChapterSummaryEntity.ConsistencyFact fact = new ChapterSummaryEntity.ConsistencyFact(
                        "TIMELINE", "进入北境" + ch, "寒潮第三日", null, "正文原句");
                fact.setEvidenceTier("adjudicated");
                s.setConsistencyFacts(List.of(fact));
            }
            chapters.add(s);
        }

        BatchHealthReport report = service.assess(chapters, List.of(), List.of());

        BatchHealthReport.Metric metric = metric(report, "looseTierShare");
        assertEquals(37.5, metric.value(), 0.001, "实际：" + metric.detail());
        assertTrue(metric.detail().contains("3 / 8 条"));
        assertTrue(metric.detail().contains("一致性事实"), "口径应在明细里写明覆盖一致性事实");
        assertEquals(BatchHealthReport.Level.DEGRADED, metric.level());
    }

    @Test
    @DisplayName("放宽档占比：一致性事实为 exact 时不计放宽")
    void looseTierShareIgnoresExactConsistencyFacts() {
        List<ChapterSummaryEntity> chapters = new ArrayList<>();
        for (int ch = 1; ch <= 5; ch++) {
            ChapterSummaryEntity s = healthyChapters(1).get(0);
            s.setChapterNo(ch);
            if (ch == 1) {
                ChapterSummaryEntity.ConsistencyFact fact = new ChapterSummaryEntity.ConsistencyFact(
                        "NUMBER", "北境守军", "三千人", "北境", "正文原句");
                fact.setEvidenceTier("exact");
                s.setConsistencyFacts(List.of(fact));
            }
            chapters.add(s);
        }

        assertEquals(0.0, metric(service.assess(chapters, List.of(), List.of()), "looseTierShare").value());
    }

    private static BatchHealthReport.Metric metric(BatchHealthReport report, String key) {
        return report.metrics().stream().filter(m -> m.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("缺少指标：" + key + "，实际有 " + report.metrics()));
    }

    // ---------------- 干跑：对改造前真实基线验证区分力 ----------------

    @Test
    @DisplayName("干跑：改造前的 162 章基线应被判为需人工过问（否则体检层没有区分力）")
    void realStoryBaselineIsFlagged() throws Exception {
        Path storyDir = locateStoryDir();
        Assumptions.assumeTrue(storyDir != null, "本地故事数据不存在，跳过干跑");

        ObjectMapper mapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        List<ChapterSummaryEntity> summaries = mapper.readValue(
                storyDir.resolve("memory").resolve("summaries.json").toFile(),
                new TypeReference<List<ChapterSummaryEntity>>() { });
        List<QualityDebtEntity> debts = mapper.readValue(
                storyDir.resolve("memory").resolve("quality-debts.json").toFile(),
                new TypeReference<List<QualityDebtEntity>>() { });
        List<StageBlueprintEntity> blueprints = mapper.readValue(
                storyDir.resolve("memory").resolve("rolling-outline.json").toFile(),
                new TypeReference<List<StageBlueprintEntity>>() { });

        BatchHealthReport report = service.assess(summaries, debts, blueprints);

        System.out.println("干跑体检（改造前基线）：\n" + report.render());

        assertNotNull(report);
        assertTrue(report.chapterCount() >= 162, "样本应覆盖全书实际章数，实际 " + report.chapterCount());
        assertTrue(report.needsAttention(),
                "改造前基线（账本 65% / 每章换场 / 无过渡章）必须被判为需人工过问，否则阈值无区分力");
        // 逐项抽查：三个已知病态必须各自被识别出来
        assertFalse(metric(report, "ledgerCompleteness").level() == BatchHealthReport.Level.OK,
                "账本完整度 65% 不应判健康");
        assertEquals(BatchHealthReport.Level.DEGRADED, metric(report, "newPlaceRate").level(),
                "每章 0.83 个新地点应判 DEGRADED");
        assertEquals(BatchHealthReport.Level.DEGRADED, metric(report, "transitionShare").level(),
                "过渡章占比 0 应判 DEGRADED");
        assertEquals(BatchHealthReport.Level.CRITICAL, report.overall());
    }

    private static Path locateStoryDir() {
        String[] candidates = {
                "../docs/workspace/stories/20260913-story-0001",
                "docs/workspace/stories/20260913-story-0001",
        };
        for (String candidate : candidates) {
            Path path = Paths.get(candidate);
            if (Files.isDirectory(path.resolve("memory")) && Files.isDirectory(path.resolve("chapters"))) {
                return path;
            }
        }
        return null;
    }

    @Test
    @DisplayName("候选链路：高触发(75%)与低采纳(22%)同时出现 → 两项都 DEGRADED")
    void candidateMetricsReflectTriggerAndAdoption() {
        var stats = new cn.novel.yonren.domain.novel.service.armory.candidate.CandidateSampleService.CandidateStats(9, 2);

        BatchHealthReport report = service.assess(healthyChapters(12), List.of(), List.of(), stats);

        assertEquals(BatchHealthReport.Level.DEGRADED, metric(report, "candidateTriggerRate").level());
        assertEquals(75.0, metric(report, "candidateTriggerRate").value(), 0.001, "9/12 章");
        assertEquals(BatchHealthReport.Level.DEGRADED, metric(report, "candidateAdoptionRate").level());
        assertEquals(22.22, metric(report, "candidateAdoptionRate").value(), 0.01);
    }

    @Test
    @DisplayName("候选链路：高触发 + 低采纳同时出现 → 两项都劣化并给出收紧建议")
    void candidateMetricsFlagWastedRewrites() {
        var stats = new cn.novel.yonren.domain.novel.service.armory.candidate.CandidateSampleService.CandidateStats(12, 2);

        BatchHealthReport report = service.assess(healthyChapters(12), List.of(), List.of(), stats);

        assertEquals(BatchHealthReport.Level.DEGRADED, metric(report, "candidateTriggerRate").level());
        assertEquals(BatchHealthReport.Level.DEGRADED, metric(report, "candidateAdoptionRate").level());
        assertTrue(report.recommendations().stream().anyMatch(r -> r.contains("采纳率偏低")));
    }

    @Test
    @DisplayName("候选统计为 null 或触发为 0：不产生该指标（避免误导性的 0%）")
    void candidateMetricsSkippedWithoutStats() {
        assertFalse(metricOrNone(service.assess(healthyChapters(12), List.of(), List.of()), "candidateTriggerRate"));
        var zero = new cn.novel.yonren.domain.novel.service.armory.candidate.CandidateSampleService.CandidateStats(0, 0);
        assertFalse(metricOrNone(service.assess(healthyChapters(12), List.of(), List.of(), zero), "candidateTriggerRate"));
    }

    private static boolean metricOrNone(BatchHealthReport report, String key) {
        return report.metrics().stream().anyMatch(m -> m.key().equals(key));
    }

    @Test
    @DisplayName("对白行占比：新书实测均值 11.1% 判 DEGRADED，旧书水平判 OK")
    void dialogueRatioFlagsMonologueChapters() {
        List<ChapterSummaryEntity> chapters = healthyChapters(12);
        // 11.1% ≈ 11 章无人值守实测均值；旧书基线 45.6%
        chapters.get(0).setDialogueRatio(0.111);
        for (int i = 1; i < 12; i++) chapters.get(i).setDialogueRatio(0.111);

        BatchHealthReport report = service.assess(chapters, List.of(), List.of());

        assertEquals(BatchHealthReport.Level.DEGRADED, metric(report, "dialogueRatio").level());
        assertTrue(report.recommendations().stream().anyMatch(r -> r.contains("对白占比过低")));
        assertTrue(metric(report, "dialogueRatio").detail().contains("最低一章"));
    }

    @Test
    @DisplayName("对白行占比：旧书水平（45.6%）判 OK")
    void dialogueRatioPassesAtOldBookLevel() {
        List<ChapterSummaryEntity> chapters = healthyChapters(12);
        for (ChapterSummaryEntity s : chapters) s.setDialogueRatio(0.456);

        assertEquals(BatchHealthReport.Level.OK,
                metric(service.assess(chapters, List.of(), List.of()), "dialogueRatio").level());
    }

    @Test
    @DisplayName("旧摘要无 dialogueRatio 字段：跳过指标而非误报 0%")
    void dialogueRatioSkippedWithoutData() {
        assertFalse(metricOrNone(service.assess(healthyChapters(12), List.of(), List.of()), "dialogueRatio"));
    }

    // ---- 的观测项：补齐"生成模式 / 认知边界 / 正文复核"三处此前的观测盲区 ----

    @Test
    @DisplayName("主线最长停留：整批卡在同一档时给出违规级指标与排查方向")
    void suspenseHold() {
        List<String> ladder = List.of("双方不知", "一方起疑", "双方持证", "接近摊牌");
        List<ChapterSummaryEntity> chapters = healthyChapters(6);
        for (ChapterSummaryEntity s : chapters) {
            s.setSuspenseBeat(ladder.get(1)); // 6 章全卡在"一方起疑"——真实病症的复现
        }
        List<StageBlueprintEntity> blueprints = List.of(StageBlueprintEntity.builder()
                .stageNo(1).startChapter(1).endChapter(6)
                .coreSuspense("双方的真实身份何时暴露")
                .suspenseLadder(ladder)
                .build());

        BatchHealthReport report = service.assess(chapters, List.of(), blueprints);

        assertEquals(6.0, metric(report, "suspenseHold").value(), 0.001,
                "指标报的是整段停留的全长（1-6 章都没动），不是闸门首次达限的那一章");
        assertTrue(metric(report, "suspenseHold").detail().contains("第 1-6 章"));
        assertEquals(BatchHealthReport.Level.DEGRADED, metric(report, "suspenseHold").level());
        assertTrue(report.recommendations().stream().anyMatch(r -> r.contains("主线停留过长")));
    }

    @Test
    @DisplayName("主线逐章推进：指标为 OK")
    void suspenseHoldOkWhenAdvancing() {
        List<String> ladder = List.of("双方不知", "一方起疑", "双方持证", "接近摊牌");
        List<ChapterSummaryEntity> chapters = healthyChapters(6);
        int[] indices = {0, 0, 1, 1, 2, 3}; // 单调不减、最长停留 2 章
        for (int i = 0; i < chapters.size(); i++) {
            chapters.get(i).setSuspenseBeat(ladder.get(indices[i]));
        }
        List<StageBlueprintEntity> blueprints = List.of(StageBlueprintEntity.builder()
                .stageNo(1).startChapter(1).endChapter(6)
                .suspenseLadder(ladder)
                .build());

        assertEquals(BatchHealthReport.Level.OK,
                metric(service.assess(chapters, List.of(), blueprints), "suspenseHold").level());
    }

    @Test
    @DisplayName("无档位数据（老批次/无蓝图）：跳过指标而不是报 0")
    void suspenseHoldSkippedWithoutData() {
        assertFalse(metricOrNone(service.assess(healthyChapters(6), List.of(), List.of()), "suspenseHold"),
                "没有档位记录时不产生指标");
        assertFalse(metricOrNone(service.assess(healthyChapters(6), List.of(), blueprintsWithoutLadder()),
                "suspenseHold"), "蓝图没给档位表时同样跳过");
    }

    private static List<StageBlueprintEntity> blueprintsWithoutLadder() {
        return List.of(StageBlueprintEntity.builder()
                .stageNo(1).startChapter(1).endChapter(6)
                .build());
    }

    @Test
    @DisplayName("兜底模式占比：全 FREE 判 OK；出现 SCAFFOLDED/RECOVERY 计入并给建议")
    void fallbackModeShare() {
        List<ChapterSummaryEntity> chapters = healthyChapters(6);
        for (ChapterSummaryEntity s : chapters) {
            s.setGenerationMode("FREE");
        }
        BatchHealthReport allFree = service.assess(chapters, List.of(), List.of());
        assertEquals(0.0, metric(allFree, "fallbackModeShare").value(), 0.001);
        assertEquals(BatchHealthReport.Level.OK, metric(allFree, "fallbackModeShare").level());

        // 6 章里 2 章走兜底 = 33%，超过 OK 线
        chapters.get(0).setGenerationMode("RECOVERY");
        chapters.get(1).setGenerationMode("SCAFFOLDED");
        BatchHealthReport mixed = service.assess(chapters, List.of(), List.of());
        assertEquals(100.0 / 3, metric(mixed, "fallbackModeShare").value(), 0.5);
        assertTrue(mixed.recommendations().stream().anyMatch(r -> r.contains("兜底模式被触发")),
                "触发兜底要给出排查方向（计划缺 endingHook 或节拍连续失败）");
    }

    @Test
    @DisplayName("生成模式：老批次无记录时跳过指标，而不是造一个兜底 0% 的假指标")
    void fallbackModeShareSkippedWithoutRecords() {
        assertFalse(metricOrNone(service.assess(healthyChapters(12), List.of(), List.of()),
                "fallbackModeShare"));
    }

    @Test
    @DisplayName("认知边界注入率：为 0 说明账本没记或提取失效，要给出排查建议")
    void knowledgeBoundaryCoverage() {
        List<ChapterSummaryEntity> chapters = healthyChapters(6);
        for (ChapterSummaryEntity s : chapters) {
            s.setKnowledgeBoundaryInjected(false);
        }
        BatchHealthReport none = service.assess(chapters, List.of(), List.of());
        assertEquals(0.0, metric(none, "knowledgeBoundaryCoverage").value(), 0.001);
        assertTrue(none.recommendations().stream().anyMatch(r -> r.contains("认知边界一次也没注入")));

        for (ChapterSummaryEntity s : chapters) {
            s.setKnowledgeBoundaryInjected(true);
        }
        assertEquals(100.0, metric(service.assess(chapters, List.of(), List.of()),
                "knowledgeBoundaryCoverage").value(), 0.001);
    }

    @Test
    @DisplayName("无检索唤醒章数占比：降级要落盘才统计，老批次不进分母（免造假指标）")
    void recallDegradedShare() {
        List<ChapterSummaryEntity> chapters = healthyChapters(6);

        // 老批次：该字段落盘前生成 → 为 null，不应产出这条指标
        //（否则会凭空造出一个"0% 降级"的假指标，与兜底模式占比同一处理）
        assertTrue(service.assess(chapters, List.of(), List.of()).metrics().stream()
                        .noneMatch(m -> m.key().equals("recallDegradedShare")),
                "无任何标记时不应产出该指标");

        // 全部有标记且未降级 → 0%，正常态
        for (ChapterSummaryEntity s : chapters) {
            s.setRecallDegraded(false);
        }
        assertEquals(0.0, metric(service.assess(chapters, List.of(), List.of()),
                "recallDegradedShare").value(), 0.001);

        // 两章裸跑 → 33.3%，越过 DEGRADED 线，并给出"查链路而非调阈值"的建议
        chapters.get(0).setRecallDegraded(true);
        chapters.get(1).setRecallDegraded(true);
        BatchHealthReport report = service.assess(chapters, List.of(), List.of());

        assertEquals(33.33, metric(report, "recallDegradedShare").value(), 0.01);
        assertEquals(BatchHealthReport.Level.DEGRADED, metric(report, "recallDegradedShare").level(),
                "降级占比过高说明向量链路在抖动");
        assertTrue(report.recommendations().stream().anyMatch(r -> r.contains("向量链路抖动")),
                "应给出可执行的排查方向：" + report.recommendations());
    }

    @Test
    @DisplayName("修订验证未跑成占比：基础设施降级要能被看见，且明确不落债")
    void auditVerifyDegradedShare() {
        List<ChapterSummaryEntity> chapters = healthyChapters(6);

        // 老批次无标记 → 不产出该指标
        assertTrue(service.assess(chapters, List.of(), List.of()).metrics().stream()
                        .noneMatch(m -> m.key().equals("auditVerifyDegradedShare")),
                "无任何标记时不应产出该指标");

        for (ChapterSummaryEntity s : chapters) {
            s.setAuditVerifyDegraded(false);
        }
        assertEquals(0.0, metric(service.assess(chapters, List.of(), List.of()),
                "auditVerifyDegradedShare").value(), 0.001);

        // 两章验证未跑成 → 33.3%，应判 DEGRADED 并提示"别把债数当质量信号"
        chapters.get(0).setAuditVerifyDegraded(true);
        chapters.get(1).setAuditVerifyDegraded(true);
        BatchHealthReport report = service.assess(chapters, List.of(), List.of());

        assertEquals(33.33, metric(report, "auditVerifyDegradedShare").value(), 0.01);
        assertEquals(BatchHealthReport.Level.DEGRADED, metric(report, "auditVerifyDegradedShare").level());
        assertTrue(report.recommendations().stream().anyMatch(r -> r.contains("修订验证未跑成")),
                report.recommendations().toString());
    }

    @Test
    @DisplayName("超长章占比：上沿不豁免过渡章，且只统计有 validChars 的章")
    void oversizedChapterShare() {
        List<ChapterSummaryEntity> chapters = healthyChapters(6);
        for (int i = 0; i < chapters.size(); i++) {
            chapters.get(i).setValidChars(2000);
        }
        // 老批次无 validChars → 不产出该指标
        for (ChapterSummaryEntity s : chapters) {
            s.setValidChars(null);
        }
        assertTrue(service.assess(chapters, List.of(), List.of()).metrics().stream()
                        .noneMatch(m -> m.key().equals("oversizedChapterShare")),
                "无 validChars 记录时不应产出该指标");

        for (ChapterSummaryEntity s : chapters) {
            s.setValidChars(2000);
        }
        assertEquals(0.0, metric(service.assess(chapters, List.of(), List.of()),
                "oversizedChapterShare").value(), 0.001);

        // 实测形态：过渡章 4170 有效字（邻章 2000）——低密度指标对此全程无感
        chapters.get(0).setValidChars(4170);
        chapters.get(0).setChapterType(ChapterTypeVO.TRANSITION.getCode());
        chapters.get(1).setValidChars(3000);
        BatchHealthReport report = service.assess(chapters, List.of(), List.of());

        assertEquals(33.33, metric(report, "oversizedChapterShare").value(), 0.01);
        assertEquals(BatchHealthReport.Level.DEGRADED, metric(report, "oversizedChapterShare").level(),
                "两章超长应判 DEGRADED");
        assertTrue(metric(report, "oversizedChapterShare").detail().contains("超长 2 / 有记录 6 章"),
                "过渡章不得被豁免：ch1 正是 transition：" + metric(report, "oversizedChapterShare").detail());
        assertTrue(report.recommendations().stream().anyMatch(r -> r.contains("篇幅注水")),
                "建议应指向 paragraph-audit 与【注水反馈】回灌：" + report.recommendations());
    }

    @Test
    @DisplayName("正文复核改判占比：从蓝图 note 统计，偏高时提示去改摘要 prompt")
    void recheckRecoveredShare() {
        List<StageBlueprintEntity> blueprints = List.of(StageBlueprintEntity.builder()
                .stageNo(1).startChapter(1).endChapter(6)
                .exitResults(List.of(
                        new StageBlueprintEntity.ExitConditionResult("甲", true, 5, "证据",
                                "一阶段（摘要）判未达成，**正文复核通过**：摘要未覆盖该细节"),
                        new StageBlueprintEntity.ExitConditionResult("乙", false, 0, null, "未达成"),
                        new StageBlueprintEntity.ExitConditionResult("丙", false, 0, null, "未达成")))
                .build());

        BatchHealthReport report = service.assess(healthyChapters(6), List.of(), blueprints);

        assertEquals(100.0 / 3, metric(report, "recheckRecoveredShare").value(), 0.5);
        assertTrue(report.recommendations().stream().anyMatch(r -> r.contains("正文复核改判占比偏高")),
                "它衡量的是摘要粒度缺口，偏高该去改摘要而不是靠复核兜底");
    }

    @Test
    @DisplayName("对白轮次密度：恋爱题材 8.1/千字判 DEGRADED（占比合格也拦得住）")
    void dialogueDensityFlagsSparseExchanges() {
        List<ChapterSummaryEntity> chapters = healthyChapters(12);
        for (ChapterSummaryEntity s : chapters) {
            s.setStoryGenre("romance");
            s.setDialogueRatio(0.25);   // 占比勉强合格
            s.setValidChars(1600);
            s.setDialogueUtterances(13);  // 13/1.6 = 8.1 次/千字（romance 劣化线 9.0 之下）
        }

        BatchHealthReport report = service.assess(chapters, List.of(), List.of());

        assertEquals(BatchHealthReport.Level.DEGRADED, metric(report, "dialogueDensity").level());
        assertEquals(BatchHealthReport.Direction.HIGHER_IS_BETTER,
                metric(report, "dialogueDensity").direction());
        assertTrue(report.recommendations().stream().anyMatch(r -> r.contains("高频短交锋")));
        assertTrue(metric(report, "dialogueDensity").healthyLine().contains("对话驱动题材从严"));
    }

    @Test
    @DisplayName("对白轮次密度：同数值在非对话驱动题材下不算劣化（题材感知生效）")
    void dialogueDensityIsGenreAware() {
        List<ChapterSummaryEntity> chapters = healthyChapters(12);
        for (ChapterSummaryEntity s : chapters) {
            s.setStoryGenre("fantasy");
            s.setValidChars(1600);
            s.setDialogueUtterances(13);  // 8.1/千字：低于基础 OK 线 9.0 但高于基础劣化线 6.0 → WATCH
        }

        BatchHealthReport report = service.assess(chapters, List.of(), List.of());

        // 8.1 低于基础 OK 线 9.0 但高于基础 DEGRADED 线 6.0 → WATCH，比 romance 的 DEGRADED 宽松
        assertEquals(BatchHealthReport.Level.WATCH, metric(report, "dialogueDensity").level());
    }

    @Test
    @DisplayName("无 dialogueUtterances 的旧摘要：跳过密度指标")
    void dialogueDensitySkippedWithoutData() {
        assertFalse(metricOrNone(service.assess(healthyChapters(12), List.of(), List.of()), "dialogueDensity"));
    }
}
