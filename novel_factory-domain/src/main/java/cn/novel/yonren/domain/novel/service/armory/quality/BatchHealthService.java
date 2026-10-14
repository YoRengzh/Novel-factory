package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowSettlementEntity;
import cn.novel.yonren.domain.novel.service.armory.candidate.CandidateSampleService;
import cn.novel.yonren.domain.novel.model.entity.QualityDebtEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.types.enums.ChapterTypeVO;
import lombok.extern.slf4j.Slf4j;
import cn.novel.yonren.domain.novel.service.armory.memory.OutlineSegmentParser;
import cn.novel.yonren.domain.novel.service.armory.plan.StoryPacing;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


@Slf4j
@Service
public class BatchHealthService {

    /** 阈值标定基准说明：写在代码里，避免后人把初值当圣旨 */
    public static final String THRESHOLD_NOTE =
            "阈值为改造前整本书基线的标定初值，第一批改造后数据出齐后必须重新校准";

    /** 体检最小样本：低于此章数各项比率噪声过大，直接返回"数据不足" */
    public static final int MIN_SAMPLE_CHAPTERS = 5;

    /** 劣化指标达到该数量即升级为 CRITICAL（多项同时劣化通常意味着某层改造没生效） */
    public static final int CRITICAL_METRIC_COUNT = 3;

    /** 低密度章判定线：与规划层 density feedback 的参考线一致 */
    private static final int LOW_DENSITY_CHARS = 1500;

    // ---------- 阈值：账本完整度（改造前基线 65.2%） ----------
    private static final double LEDGER_OK = 0.90;
    private static final double LEDGER_DEGRADED = 0.80;
    /** 分档计权时"放宽档"的权重：证据宽松得来，可信度低于逐字命中的严格档 */
    private static final double LOOSE_WEIGHT = 0.5;
    // ---------- 生成模式：兜底路径被吃掉的频率 ----------
    // ⚠️ 无历史基线（截至 两批 12 章全部走 FREE），阈值暂定，先当"观察项"用
    private static final double FALLBACK_MODE_OK = 0.20;
    private static final double FALLBACK_MODE_DEGRADED = 0.50;

    /** 无检索唤醒章数占比（基础设施指标）：0 = 完全没有降级，是正常态；≥0.30 说明向量链路在持续抖动 */
    private static final double RECALL_DEGRADED_OK = 0.0;
    private static final double RECALL_DEGRADED_LINE = 0.30;

    /**
     * 修订验证未跑成占比（基础设施指标）：0 = 正常态。
     * 与「无检索唤醒章数占比」同则——都是"降级不得静默"的落点，且都不进质量债。
     */
    private static final double VERIFY_DEGRADED_OK = 0.0;
    private static final double VERIFY_DEGRADED_LINE = 0.30;

    /**
     * 超长章占比（注水指标）：有效字 > {@link ChapterLengthPolicy#MAXIMUM_REFERENCE_CHARACTERS} 的章占比。
     * 与「低密度章占比」是一对——下沿查"料太少"，上沿查"水太多"。**刻意不豁免过渡章**。
     */
    private static final double OVERSIZED_OK = 0.10;
    private static final double OVERSIZED_DEGRADED = 0.25;
    // ---------- 正文复核改判占比：摘要粒度缺口的度量 ----------
    private static final double RECHECK_OK = 0.20;
    private static final double RECHECK_DEGRADED = 0.50;
    // ---------- 主线最长停留：与 SuspenseLadderPolicy 的闸门判据同源 ----------
    /** 连续同档上限：与计划闸门一致（≥3 章同档即违例），单位是"章"不是比例 */
    private static final double SUSPENSE_HOLD_OK = 2;
    private static final double SUSPENSE_HOLD_DEGRADED = 3;

    /**
     * 伏笔平均保密跨度（章）——2026-10-01 新增。
     * 实测第 1–15 章平均 2.23 章、77% 在 2 章内兑现，是"读起来浅"的结构性指标。
     * ⚠️ 无历史基线，取值按实测分布定，须经更多批次校准（项目约定：不要凭直觉取整）。
     */
    private static final double FORESHADOW_SPAN_OK = 4.0;
    private static final double FORESHADOW_SPAN_DEGRADED = 3.0;

    /**
     * 在途伏笔滞留中位数（章）——2026-10-02 新增，`foreshadowSpan` 的互补指标。
     * 实测：41 条埋设中 31 条在途、其中 18 条滞留 ≥5 章、最长 14 章。
     * ⚠️ 无法与历史比较（该口径首次引入），取值先按实测分布设定，须经更多批次校准。
     */
    private static final double FORESHADOW_PENDING_OK = 6.0;
    private static final double FORESHADOW_PENDING_DEGRADED = 9.0;
    /** 单条伏笔滞留达到该章数即视为"陈旧"，单独计数供人工分辨长线/遗忘 */
    private static final int FORESHADOW_PENDING_STALE = 5;

    /** 伏笔未标注占比的可容忍上沿：超过即认为寿命/在途两项指标的样本已被掏空 */
    private static final double FORESHADOW_UNLABELED_OK_TOLERANCE = 0.20;

    /** 漏收伏笔数分级（2026-10-02 新增，P2a）：无历史基线，先按"每阶段允许欠 1 条"设定，须校准 */
    private static final double FORESHADOW_MISSED_OK = 1.0;
    private static final double FORESHADOW_MISSED_DEGRADED = 3.0;
    // ---------- 每章新地点率（改造前基线 0.83，10 章窗口内中位 10 个不同地点） ----------
    private static final double NEW_PLACE_OK = 0.30;
    private static final double NEW_PLACE_DEGRADED = 0.60;
    // ---------- 过渡章占比（改造前恒为 0：章型无法声明） ----------
    private static final double TRANSITION_MIN_OK = 0.08;
    private static final double TRANSITION_MIN_DEGRADED = 0.04;
    private static final double TRANSITION_MAX_OK = 0.33;
    private static final double TRANSITION_MAX_DEGRADED = 0.50;
    // ---------- 最长连续过渡章（与规划 prompt 的硬约束一致：不得连续超过 2 章） ----------
    private static final int TRANSITION_RUN_OK = 2;
    private static final int TRANSITION_RUN_DEGRADED = 4;
    // ---------- 低密度章占比（排除过渡章） ----------
    private static final double LOW_DENSITY_OK = 0.15;
    private static final double LOW_DENSITY_DEGRADED = 0.35;
    // ---------- 未核销质量债 ----------
    private static final int DEBT_OK = 3;
    private static final int DEBT_DEGRADED = 8;
    // ---------- 阶段出口条件达成率（改造前基线 22%） ----------
    private static final double EXIT_OK = 0.60;
    private static final double EXIT_DEGRADED = 0.35;
    // ---------- 放宽档入账占比（留痕 spread/anchored + 裁决 adjudicated） ----------
    private static final double LOOSE_OK = 0.10;
    private static final double LOOSE_DEGRADED = 0.25;
    // ---------- 候选链路（触发率历史基线 19%；采纳率历史基线 48%） ----------
    private static final double CANDIDATE_TRIGGER_OK = 0.30;
    private static final double CANDIDATE_TRIGGER_DEGRADED = 0.60;
    private static final double CANDIDATE_ADOPTION_OK = 0.45;
    private static final double CANDIDATE_ADOPTION_DEGRADED = 0.30;

    /**
     * 批末体检（纯函数）。
     *
     * @param summaries  全量章节摘要（按章号排序不敏感，内部会排）
     * @param debts      质量债清单
     * @param blueprints 阶段蓝图链（取 exitResults 统计出口条件达成率）
     */
    public BatchHealthReport assess(List<ChapterSummaryEntity> summaries,
                                    List<QualityDebtEntity> debts,
                                    List<StageBlueprintEntity> blueprints) {
        return assess(summaries, debts, blueprints, null, null);
    }

    public BatchHealthReport assess(List<ChapterSummaryEntity> summaries,
                                    List<QualityDebtEntity> debts,
                                    List<StageBlueprintEntity> blueprints,
                                    CandidateSampleService.CandidateStats candidates) {
        return assess(summaries, debts, blueprints, candidates, null);
    }


    public BatchHealthReport assess(List<ChapterSummaryEntity> summaries,
                                    List<QualityDebtEntity> debts,
                                    List<StageBlueprintEntity> blueprints,
                                    CandidateSampleService.CandidateStats candidates,
                                    List<ForeshadowSettlementEntity> settlements) {
        return assess(summaries, debts, blueprints, candidates, settlements, null);
    }

    public BatchHealthReport assess(List<ChapterSummaryEntity> summaries,
                                    List<QualityDebtEntity> debts,
                                    List<StageBlueprintEntity> blueprints,
                                    CandidateSampleService.CandidateStats candidates,
                                    List<ForeshadowSettlementEntity> settlements,
                                    String chapterGoal) {
        return assess(summaries, debts, blueprints, candidates, settlements, chapterGoal, List.of());
    }

    /**
     * 体检入口（增质量评分行）。
     *
     * <p><b>为什么把质量评分接进来</b>：那个通道（强模型每 10 章抽 3 章打 5 维分）此前
     * **只落盘、无人读**——每轮花的钱换来的读数直接躺在文件里。接进体检的用意不是让它当门禁，
     * 而是让"这本书在变好还是变差"和"主观尺子在哪些维度不可信"进入批末结论。
     * 它是**唯一的 LLM 主观打分通道**，与其余纯机械指标互补。
     */
    public BatchHealthReport assess(List<ChapterSummaryEntity> summaries,
                                    List<QualityDebtEntity> debts,
                                    List<StageBlueprintEntity> blueprints,
                                    CandidateSampleService.CandidateStats candidates,
                                    List<ForeshadowSettlementEntity> settlements,
                                    String chapterGoal,
                                    List<String> qualityTrendLines) {
        List<ChapterSummaryEntity> chapters = summaries == null ? List.of() : summaries.stream()
                .filter(s -> s != null && s.getChapterNo() != null)
                .sorted(Comparator.comparing(ChapterSummaryEntity::getChapterNo))
                .toList();

        List<BatchHealthReport.Metric> metrics = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();

        if (chapters.size() < MIN_SAMPLE_CHAPTERS) {
            return new BatchHealthReport(chapters.size(), metrics, BatchHealthReport.Level.OK,
                    List.of("样本不足 " + MIN_SAMPLE_CHAPTERS + " 章，体检结论不可信；" + THRESHOLD_NOTE));
        }

        addLedgerCompleteness(metrics, recommendations, chapters);
        addNewPlaceRate(metrics, recommendations, chapters);
        addTransitionShare(metrics, recommendations, chapters);
        addTransitionRun(metrics, recommendations, chapters);
        addLowDensityShare(metrics, recommendations, chapters);
        addOversizedChapterShare(metrics, recommendations, chapters);
        addUnresolvedDebt(metrics, recommendations, debts);
        addExitConditionRate(metrics, recommendations, blueprints);
        addLooseTierShare(metrics, recommendations, chapters);
        addDialogueMetrics(metrics, recommendations, chapters);
        addCandidateMetrics(metrics, recommendations, chapters.size(), candidates);
        // 补齐本轮新机制的观测。
        // 此前"生成模式 / 认知边界 / 正文复核"只写代码和日志，体检完全看不到——
        // 后果是改了也说不清效果（"三模式只用 FREE"只能靠 grep 日志得出，而不是看指标）。
        addGenerationModeShare(metrics, recommendations, chapters);
        addKnowledgeBoundaryCoverage(metrics, recommendations, chapters);
        addRecallDegradedShare(metrics, recommendations, chapters);
        addAuditVerifyDegradedShare(metrics, recommendations, chapters);
        addRecheckRecoveredShare(metrics, recommendations, blueprints);
        addSuspenseHold(metrics, recommendations, chapters, blueprints);
        // 蓝图 fail-soft 降级的显式化——此前只留一条易被淹没的 WARN，
        // 36-40 章实测整批在旧蓝图上滑行（排期 71 条原地踏步）而批末体检完全看不到
        addStagePlanningCoverage(metrics, recommendations, chapters, blueprints);
        // 进度对齐——大纲（chapterGoal）按章段预算的时间/年龄 vs 时序锚实际值。
        // chapterGoal 缺失或无结构时豁免（fail-soft，与 stagePlanningCoverage 的 legacy 豁免同款）
        addOutlinePacing(metrics, recommendations, chapters, chapterGoal);
        // 伏笔类指标必须与账本同口径：先取清账弃置清单，排除掉"已被判死的条目"
        List<String> voided = ForeshadowSettlementEntity.voidedContents(settlements);
        addForeshadowSpan(metrics, recommendations, chapters, voided);
        addForeshadowPending(metrics, recommendations, chapters, voided);
        // 漏收伏笔数另需排除"长线在养"：那条指标的定位是「有意养的长线」与「被写忘了的线」的分界线，
        // 在养项已被阶段出口明确裁定为前者，再算它漏收就与该指标自己的定义打架。
        // 注意「在途伏笔滞留中位数」不排除在养项——它的说明里写明"本项偏高说明长线在养"，
        // 在养本就该算在途。
        List<String> notMissed = new java.util.ArrayList<>(voided);
        notMissed.addAll(ForeshadowSettlementEntity.parkedContents(settlements));
        addForeshadowMissed(metrics, recommendations, chapters, notMissed);
        addSettlementAudit(metrics, recommendations, settlements);
        // 主观质量评分（唯一的 LLM 打分通道）与"评分-机械背离"：读数与自检，不作门禁
        addQualityReview(metrics, recommendations,
                cn.novel.yonren.domain.novel.service.armory.memory.QualityReviewService
                        .latestSnapshot(qualityTrendLines));

        BatchHealthReport.Level overall = overall(metrics);
        return new BatchHealthReport(chapters.size(), metrics, overall, recommendations);
    }

    private void addGenerationModeShare(List<BatchHealthReport.Metric> metrics,
                                        List<String> recommendations,
                                        List<ChapterSummaryEntity> chapters) {
        int recorded = 0;
        int fallback = 0;
        for (ChapterSummaryEntity s : chapters) {
            if (s == null || StringUtils.isBlank(s.getGenerationMode())) {
                continue;
            }
            recorded++;
            String mode = s.getGenerationMode().trim().toUpperCase(java.util.Locale.ROOT);
            if ("SCAFFOLDED".equals(mode) || "RECOVERY".equals(mode)) {
                fallback++;
            }
        }
        if (recorded == 0) {
            return;
        }
        double share = (double) fallback / recorded;
        BatchHealthReport.Level level = lowerIsBetter(share, FALLBACK_MODE_OK, FALLBACK_MODE_DEGRADED);
        metrics.add(new BatchHealthReport.Metric("fallbackModeShare", "兜底模式占比",
                share * 100, "%",
                "≤" + pct(FALLBACK_MODE_OK) + "（SCAFFOLDED+RECOVERY 占比；截至 2026-09-22 无历史基线，观察项）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                "兜底 " + fallback + " / 有模式记录 " + recorded + " 章"));
        if (fallback > 0) {
            recommendations.add("兜底模式被触发：" + fallback + " 章走了 SCAFFOLDED/RECOVERY——"
                    + "检查这些章的计划是否缺 endingHook（契约不完整）或节拍生成有没有连续失败");
        }
    }

    private void addKnowledgeBoundaryCoverage(List<BatchHealthReport.Metric> metrics,
                                              List<String> recommendations,
                                              List<ChapterSummaryEntity> chapters) {
        int recorded = 0;
        int injected = 0;
        for (ChapterSummaryEntity s : chapters) {
            if (s == null || s.getKnowledgeBoundaryInjected() == null) {
                continue;
            }
            recorded++;
            if (Boolean.TRUE.equals(s.getKnowledgeBoundaryInjected())) {
                injected++;
            }
        }
        if (recorded == 0) {
            return;
        }
        double share = (double) injected / recorded;
        // 该指标是"能力是否在运转"，低就是问题：账本没记认知状态 或 提取失效
        BatchHealthReport.Level level = higherIsBetter(share, 0.50, 0.20);
        metrics.add(new BatchHealthReport.Metric("knowledgeBoundaryCoverage", "认知边界注入率",
                share * 100, "%", "≥50%（来源：角色账本里的认知类状态；为 0 说明账本没记或提取失效）",
                BatchHealthReport.Direction.HIGHER_IS_BETTER, level,
                "注入 " + injected + " / 有记录 " + recorded + " 章"));
        if (injected == 0) {
            recommendations.add("认知边界一次也没注入：先确认摘要是否记录了认知类状态"
                    + "（含「知道/不知/怀疑/确认/身份」等词），再查 ChapterContract 的提取逻辑");
        }
    }

    private void addRecallDegradedShare(List<BatchHealthReport.Metric> metrics,
                                        List<String> recommendations,
                                        List<ChapterSummaryEntity> chapters) {
        int recorded = 0;
        int degraded = 0;
        for (ChapterSummaryEntity s : chapters) {
            if (s == null || s.getRecallDegraded() == null) {
                continue;
            }
            recorded++;
            if (Boolean.TRUE.equals(s.getRecallDegraded())) {
                degraded++;
            }
        }
        if (recorded == 0) {
            return;
        }
        double share = (double) degraded / recorded;
        BatchHealthReport.Level level = lowerIsBetter(share, RECALL_DEGRADED_OK, RECALL_DEGRADED_LINE);
        metrics.add(new BatchHealthReport.Metric("recallDegradedShare", "无检索唤醒章数占比",
                share * 100, "%",
                "=0（基础设施指标：0 为正常态；>0 说明向量链路抖动，grep RECALL_DEGRADED 归因；观察项）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                "无检索唤醒 " + degraded + " / 有记录 " + recorded + " 章"));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("向量链路抖动：本批有 " + degraded + " 章在**无跨章记忆唤醒**下生成"
                    + "（grep RECALL_DEGRADED 看归因）。生成流程未中断，但这些章的长程一致性只靠"
                    + "近章摘要与账本承载，值得复核；应先查 embedding / 向量库 / 鉴权链路，而不是调阈值。");
        }
    }


    private void addAuditVerifyDegradedShare(List<BatchHealthReport.Metric> metrics,
                                             List<String> recommendations,
                                             List<ChapterSummaryEntity> chapters) {
        int recorded = 0;
        int degraded = 0;
        for (ChapterSummaryEntity s : chapters) {
            if (s == null || s.getAuditVerifyDegraded() == null) {
                continue;
            }
            recorded++;
            if (Boolean.TRUE.equals(s.getAuditVerifyDegraded())) {
                degraded++;
            }
        }
        if (recorded == 0) {
            return;
        }
        double share = (double) degraded / recorded;
        BatchHealthReport.Level level = lowerIsBetter(share, VERIFY_DEGRADED_OK, VERIFY_DEGRADED_LINE);
        metrics.add(new BatchHealthReport.Metric("auditVerifyDegradedShare", "修订验证未跑成占比",
                share * 100, "%",
                "=0（基础设施指标：0 为正常态；>0 说明审计链路抖动，grep AUDIT_VERIFY_DEGRADED 归因；观察项）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                "未验证 " + degraded + " / 有记录 " + recorded + " 章"));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("修订验证未跑成：本批有 " + degraded + " 章带着**未验证**的 BLOCKING 结案"
                    + "（grep AUDIT_VERIFY_DEGRADED 看归因）。这些章**没有计入质量债**——"
                    + "它们的问题状态是「未知」而非「未修复」，别把债数当作质量下降的信号；"
                    + "应先查审计链路（网关/超时/输出解析）。");
        }
    }


    private void addOversizedChapterShare(List<BatchHealthReport.Metric> metrics,
                                          List<String> recommendations,
                                          List<ChapterSummaryEntity> chapters) {
        int recorded = 0;
        int oversized = 0;
        for (ChapterSummaryEntity s : chapters) {
            if (s == null || s.getValidChars() == null) {
                continue;
            }
            recorded++;
            if (s.getValidChars() > ChapterLengthPolicy.MAXIMUM_REFERENCE_CHARACTERS) {
                oversized++;
            }
        }
        if (recorded == 0) {
            return;
        }
        double share = (double) oversized / recorded;
        BatchHealthReport.Level level = lowerIsBetter(share, OVERSIZED_OK, OVERSIZED_DEGRADED);
        metrics.add(new BatchHealthReport.Metric("oversizedChapterShare", "超长章占比",
                share * 100, "%",
                "≤" + pct(OVERSIZED_OK) + "（注水信号：有效字 >"
                        + ChapterLengthPolicy.MAXIMUM_REFERENCE_CHARACTERS
                        + " 且关键事件数未增；**不豁免过渡章**）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                "超长 " + oversized + " / 有记录 " + recorded + " 章"));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("篇幅注水：本批有 " + oversized + " 章超出参考上沿——先确认"
                    + "story.paragraph-audit.enabled 是否开启（它是唯一能挤掉"
                    + "「只重复已知状态 / 原地循环情绪」段落的组件），再确认规划层的【注水反馈】块已回灌；"
                    + "不要在写手侧加压——拒收长章只会逼出另一种形态的注水。");
        }
    }

    private void addRecheckRecoveredShare(List<BatchHealthReport.Metric> metrics,
                                          List<String> recommendations,
                                          List<StageBlueprintEntity> blueprints) {
        if (blueprints == null || blueprints.isEmpty()) {
            return;
        }
        int recovered = 0;
        int remainedUnmet = 0;
        for (StageBlueprintEntity blueprint : blueprints) {
            if (blueprint == null || blueprint.getExitResults() == null) {
                continue;
            }
            for (StageBlueprintEntity.ExitConditionResult result : blueprint.getExitResults()) {
                if (result == null || result.getNote() == null) {
                    continue;
                }
                if (result.getNote().contains(RECHECK_MARK)) {
                    recovered++;
                } else if (Boolean.FALSE.equals(result.getMet())) {
                    remainedUnmet++;
                }
            }
        }
        int total = recovered + remainedUnmet;
        if (total == 0) {
            return;
        }
        double share = (double) recovered / total;
        BatchHealthReport.Level level = lowerIsBetter(share, RECHECK_OK, RECHECK_DEGRADED);
        metrics.add(new BatchHealthReport.Metric("recheckRecoveredShare", "正文复核改判占比",
                share * 100, "%",
                "≤" + pct(RECHECK_OK) + "（摘要粒度缺口的度量：越低说明摘要越完整、一阶段核验越可信）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                "改判 " + recovered + " / 未达成合计 " + total));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("正文复核改判占比偏高：" + recovered + " 条靠正文才救回来——"
                    + "说明摘要丢掉了核验需要的细节，考虑在摘要 prompt 里要求原样记录关键动作/标志性描写");
        }
    }

    /** 正文复核改判的留痕标记（与 StageExitReviewService 写入的 note 保持一致） */
    private static final String RECHECK_MARK = "正文复核通过";

    private void addForeshadowPending(List<BatchHealthReport.Metric> metrics,
                                      List<String> recommendations,
                                      List<ChapterSummaryEntity> chapters,
                                      List<String> voided) {
        int current = chapters.stream()
                .filter(c -> c != null && c.getChapterNo() != null)
                .mapToInt(ChapterSummaryEntity::getChapterNo)
                .max().orElse(0);
        List<ForeshadowSpanPolicy.Pending> pending = ForeshadowSpanPolicy.pending(chapters, current, voided);
        if (pending.isEmpty()) {
            return;
        }
        double median = ForeshadowSpanPolicy.pendingAgeMedian(chapters, current, voided);
        long stale = pending.stream()
                .filter(p -> p.ageChapters() >= FORESHADOW_PENDING_STALE)
                .count();
        BatchHealthReport.Level level = lowerIsBetter(median, FORESHADOW_PENDING_OK, FORESHADOW_PENDING_DEGRADED);
        metrics.add(new BatchHealthReport.Metric("foreshadowPending", "在途伏笔滞留中位数",
                median, "章",
                "≤" + BatchHealthReport.format(FORESHADOW_PENDING_OK) + " 章（埋了未收的伏笔已滞留多久；"
                        + "滞留 ≥" + FORESHADOW_PENDING_STALE + " 章 " + stale + " 条。"
                        + "⚠️ 与 foreshadowSpan 是互补指标：本项偏高说明长线在养，也可能是线被忘了，"
                        + "须配合卷末清账裁决判断；仅统计声明兑现义务的伏笔）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                "在途 " + pending.size() + " 条 / 滞留 ≥" + FORESHADOW_PENDING_STALE
                        + " 章 " + stale + " 条"));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("在途伏笔滞留偏长：中位数 " + BatchHealthReport.format(median)
                    + " 章、滞留 ≥" + FORESHADOW_PENDING_STALE + " 章的有 " + stale
                    + " 条。先分辨是**有意养的长线**还是**被写忘了的线**——"
                    + "核对卷末清账的弃置/限期回收裁决；若是后者，说明规划层缺少"
                    + "「哪一章收哪条线」的安排（伏笔长度反馈只治「埋太快」，治不了「忘了收」）");
        }
    }

    private void addForeshadowMissed(List<BatchHealthReport.Metric> metrics,
                                     List<String> recommendations,
                                     List<ChapterSummaryEntity> chapters,
                                     List<String> voided) {
        int current = chapters.stream()
                .filter(c -> c != null && c.getChapterNo() != null)
                .mapToInt(ChapterSummaryEntity::getChapterNo)
                .max().orElse(0);
        long missed = ForeshadowSpanPolicy.missedCount(chapters, current, voided);
        long scheduled = ForeshadowSpanPolicy.pending(chapters, current, voided).stream()
                .filter(p -> p.scheduledPayoffChapter() != null)
                .count();
        if (scheduled == 0) {
            // 无排期数据（P2b 未上线）：完全不产生该指标——报 0 会让人误以为"漏收为零"
            return;
        }
        BatchHealthReport.Level level = lowerIsBetter(missed, FORESHADOW_MISSED_OK, FORESHADOW_MISSED_DEGRADED);
        metrics.add(new BatchHealthReport.Metric("foreshadowMissed", "漏收伏笔数",
                missed, "条",
                "≤" + BatchHealthReport.format(FORESHADOW_MISSED_OK)
                        + " 条（已过计划回收章仍未回收；这是「有意养的长线」与「被写忘了的线」的分界线）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                "已排期在途 " + scheduled + " 条 / 其中逾期 " + missed + " 条"));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("漏收伏笔 " + missed + " 条：这些线当初被明确排进了兑现计划、到期却没收。"
                    + "核对规划层是否收到「本段内必须兑现」注入；若注入后仍未收，说明段计划没有把排期当硬约束");
        }
    }

    private void addSettlementAudit(List<BatchHealthReport.Metric> metrics,
                                    List<String> recommendations,
                                    List<ForeshadowSettlementEntity> settlements) {
        if (settlements == null || settlements.isEmpty()) {
            return;
        }
        long silent = ForeshadowSettlementPolicy.silentPayoffCount(settlements);
        long voidCount = ForeshadowSettlementPolicy.voidCount(settlements);
        long doneCount = ForeshadowSettlementPolicy.doneCount(settlements);
        if (voidCount == 0 && doneCount == 0) {
            return;
        }
        // 指标值取"静默兑现 + 裁决补认已完成"：两者是同一现象（剧情已收、账本没记）的两个出口。
        // 只数 VOID 那一支的话，这条指标会随着 DONE 的启用凭空变好——而问题一点没变。
        long omitted = silent + doneCount;
        metrics.add(new BatchHealthReport.Metric("foreshadowSilentPayoff", "清账静默兑现数",
                omitted, "条",
                "观测项（无绝对阈值：它度量的是**摘要层漏记回收**的规模，而非清账本身的对错）。"
                        + "数值高说明大量伏笔「剧情已消化、账本却没记回收」",
                BatchHealthReport.Direction.LOWER_IS_BETTER,
                omitted > 0 ? BatchHealthReport.Level.WATCH : BatchHealthReport.Level.OK,
                "静默兑现 " + silent + " / 弃置 " + voidCount
                        + "；另由裁决直接补认「已完成」 " + doneCount + " 条"));
        if (omitted > 0) {
            recommendations.add("摘要层漏记回收的证据共 " + omitted + " 条"
                    + "（清账静默兑现 " + silent + " 条，占弃置 " + voidCount + " 条的 "
                    + Math.round(silent * 100.0 / Math.max(1, voidCount)) + "%；"
                    + "另 " + doneCount + " 条被裁决直接判为「已完成」）："
                    + "这些伏笔**剧情其实已经收了**，只是摘要没写进 foreshadowingResolved ⇒ "
                    + "**伏笔跨度口径被系统性低估**（真实跨度更长）。"
                    + "摘要侧已强化「已兑现则必须登记回收」，并保证已过计划回收章的线一定在登记清单里；"
                    + "在这两项生效前，跨度类指标先按此条读数打折");
        }
    }

    private void addForeshadowSpan(List<BatchHealthReport.Metric> metrics,
                                   List<String> recommendations,
                                   List<ChapterSummaryEntity> chapters,
                                   List<String> voided) {
        // 防伪线先于指标本身：寿命指标只统计 resolvable=TRUE 的条目，
        // 若模型大面积漏标，样本会被掏空——"指标消失"与"伏笔变健康"在报告上长得一模一样。
        // 所以未标注占比必须**先报**，让人知道该不该相信紧随其后的寿命数字。
        double unlabeled = ForeshadowSpanPolicy.unlabeledRate(chapters);
        if (unlabeled > 0) {
            metrics.add(new BatchHealthReport.Metric("foreshadowUnlabeled", "伏笔未标注占比",
                    unlabeled * 100, "%",
                    "≤" + pct(FORESHADOW_UNLABELED_OK_TOLERANCE) + "（resolvable 字段未填的比例；"
                            + "本项偏高时寿命指标不可信——它只统计已声明兑现义务的伏笔，"
                            + "样本被掏空会让指标静默失效）",
                    BatchHealthReport.Direction.LOWER_IS_BETTER,
                    unlabeled > FORESHADOW_UNLABELED_OK_TOLERANCE
                            ? BatchHealthReport.Level.WATCH : BatchHealthReport.Level.OK,
                    "未标注 " + BatchHealthReport.format(unlabeled * 100) + "%"));
            if (unlabeled > FORESHADOW_UNLABELED_OK_TOLERANCE) {
                recommendations.add("伏笔未标注占比 " + BatchHealthReport.format(unlabeled * 100)
                        + "%：`resolvable` 字段没填全，寿命/在途两项指标都只统计已标注条目，"
                        + "样本已被掏空——**先修标注再读那两个指标**（老故事的历史数据必然全为未标注，"
                        + "属已知情况；若新章也高，说明摘要 prompt 未生效）");
            }
        }
        List<ForeshadowSpanPolicy.Span> spans = ForeshadowSpanPolicy.spans(chapters, voided);
        if (spans.size() < ForeshadowSpanPolicy.MIN_SAMPLES_FOR_FEEDBACK) {
            // 样本不足：跳过而非报 0，避免"新书前几章"被误判为 0 跨度
            return;
        }
        double avg = spans.stream().mapToInt(ForeshadowSpanPolicy.Span::chapters).average().orElse(0.0);
        int shortCount = (int) spans.stream().filter(ForeshadowSpanPolicy.Span::shortLived).count();
        double shortRate = shortCount / (double) spans.size();
        BatchHealthReport.Level level =
                higherIsBetter(avg, FORESHADOW_SPAN_OK, FORESHADOW_SPAN_DEGRADED);
        metrics.add(new BatchHealthReport.Metric("foreshadowSpan", "伏笔平均保密跨度",
                avg, "章",
                "≥" + BatchHealthReport.format(FORESHADOW_SPAN_OK) + " 章"
                        + "（埋设到回收的平均章距；本批短命伏笔占比 " + pct(shortRate)
                        + "，判定线 <" + ForeshadowSpanPolicy.SHORT_SPAN_CHAPTERS
                        + " 章；无历史基线，须校准）",
                BatchHealthReport.Direction.HIGHER_IS_BETTER, level,
                "样本 " + spans.size() + " 条回收 / 短命 " + shortCount + " 条"));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("伏笔寿命过短：平均 " + BatchHealthReport.format(avg)
                    + " 章、短命占比 " + pct(shortRate)
                    + "——伏笔埋下去立刻收，读者来不及惦记，悬念没有重量。"
                    + "核对规划层的【伏笔长度反馈】是否已注入（它是唯一的治本通道），"
                    + "不要再让计划把兑现日写死在埋设章里");
        }
    }


    private void addSuspenseHold(List<BatchHealthReport.Metric> metrics,
                                 List<String> recommendations,
                                 List<ChapterSummaryEntity> chapters,
                                 List<StageBlueprintEntity> blueprints) {
        if (chapters == null || chapters.isEmpty() || blueprints == null || blueprints.isEmpty()) {
            return;
        }
        Map<String, List<SuspenseLadderPolicy.Beat>> groups = new LinkedHashMap<>();
        Map<String, List<String>> ladders = new LinkedHashMap<>();
        for (ChapterSummaryEntity summary : chapters) {
            if (summary == null || summary.getChapterNo() == null
                    || StringUtils.isBlank(summary.getSuspenseBeat())) {
                continue;
            }
            List<String> ladder = ladderOf(blueprints, summary.getChapterNo());
            if (!SuspenseLadderPolicy.usable(ladder)) {
                continue;
            }
            String key = String.join("\u0001", ladder);
            ladders.putIfAbsent(key, ladder);
            groups.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new SuspenseLadderPolicy.Beat(summary.getChapterNo(), summary.getSuspenseBeat(),
                            "TRANSITION".equalsIgnoreCase(StringUtils.defaultString(summary.getChapterType()))));
        }
        int total = groups.values().stream().mapToInt(List::size).sum();
        if (total < SuspenseLadderPolicy.HOLD_LIMIT) {
            return;
        }
        SuspenseLadderPolicy.HoldRun worst = new SuspenseLadderPolicy.HoldRun(0, 0, 0);
        for (Map.Entry<String, List<SuspenseLadderPolicy.Beat>> entry : groups.entrySet()) {
            SuspenseLadderPolicy.HoldRun run =
                    SuspenseLadderPolicy.maxHold(entry.getValue(), ladders.get(entry.getKey()));
            if (run.length() > worst.length()) {
                worst = run;
            }
        }
        BatchHealthReport.Level level = lowerIsBetter(worst.length(), SUSPENSE_HOLD_OK, SUSPENSE_HOLD_DEGRADED);
        metrics.add(new BatchHealthReport.Metric("suspenseHold", "主线最长停留",
                worst.length(), "章",
                "≤2 章（连续停留在同一悬念档位的最大章数；≥3 即与计划闸门同源的违规，无历史基线）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                worst.length() <= 1 ? "无停留（主线逐章推进）"
                        : "最长停留 " + worst.length() + " 章（第 " + worst.startChapterNo()
                        + "-" + worst.endChapterNo() + " 章）"));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("主线停留过长：连续 " + worst.length() + " 章停在同一悬念档位——"
                    + "「发现线索 → 自我否定 → 回到原点」不算推进，核对规划层 suspenseBeat 是否真的在晋升");
        }
    }

    /** 取覆盖该章的阶段蓝图所带的档位表（可能是不同阶段的不同表） */
    private static List<String> ladderOf(List<StageBlueprintEntity> blueprints, int chapterNo) {
        for (StageBlueprintEntity blueprint : blueprints) {
            if (blueprint == null || blueprint.getStartChapter() == null || blueprint.getEndChapter() == null) {
                continue;
            }
            if (chapterNo >= blueprint.getStartChapter() && chapterNo <= blueprint.getEndChapter()
                    && SuspenseLadderPolicy.usable(blueprint.getSuspenseLadder())) {
                return blueprint.getSuspenseLadder();
            }
        }
        return null;
    }

    private void addLedgerCompleteness(List<BatchHealthReport.Metric> metrics,
                                       List<String> recommendations,
                                       List<ChapterSummaryEntity> chapters) {
        long strict = 0;
        long loose = 0;
        long pending = 0;
        for (ChapterSummaryEntity s : chapters) {
            for (List<ChapterSummaryEntity.StateEntry> entries : List.of(
                    safe(s.getCharacterStates()), safe(s.getItemStates()), safe(s.getFactionStates()))) {
                for (ChapterSummaryEntity.StateEntry entry : entries) {
                    if (entry == null) {
                        continue;
                    }
                    if (isLooseTier(entry.getEvidenceTier())) {
                        loose++;
                    } else {
                        strict++;
                    }
                }
            }
            for (ChapterSummaryEntity.ConsistencyFact fact : safe(s.getConsistencyFacts())) {
                if (fact == null) {
                    continue;
                }
                if (isLooseTier(fact.getEvidenceTier())) {
                    loose++;
                } else {
                    strict++;
                }
            }
            pending += size(s.getPendingFacts()) + size(s.getPendingConsistencyFacts());
        }
        long total = strict + loose + pending;
        double weighted = strict + LOOSE_WEIGHT * loose;
        double rate = total == 0 ? 1.0 : weighted / total;
        BatchHealthReport.Level level = higherIsBetter(rate, LEDGER_OK, LEDGER_DEGRADED);
        metrics.add(new BatchHealthReport.Metric("ledgerCompleteness", "账本完整度",
                rate * 100, "%",
                "≥" + pct(LEDGER_OK) + "（改造前基线 65.2%；分档计权：放宽档按 0.5 计）",
                BatchHealthReport.Direction.HIGHER_IS_BETTER, level,
                "严格 " + strict + " / 放宽 " + loose + " / 挂起 " + pending));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("账本挂起存量偏高：确认挂起裁决通道是否开启（story.ledger-adjudicate.enabled），"
                    + "并核对 EvidenceMatch 各档位计数日志——若挂起集中在 phrase-partial，说明机械档已到极限、需靠裁决层");
        }
    }

    /**
     * 每章新地点率 = 首次出现的地点总数 / 有地点记录的章数。
     * 改造前 0.83（10 章窗口内中位 10 个不同地点 = 每章都换新场景，读者无法建立空间感）。
     */
    private void addNewPlaceRate(List<BatchHealthReport.Metric> metrics,
                                 List<String> recommendations,
                                 List<ChapterSummaryEntity> chapters) {
        Set<String> seen = new HashSet<>();
        int withPlace = 0;
        int newPlaces = 0;
        for (ChapterSummaryEntity s : chapters) {
            String place = PlaceTrajectoryPolicy.placeOf(s);
            if (place == null) {
                continue;
            }
            withPlace++;
            if (seen.add(place)) {
                newPlaces++;
            }
        }
        if (withPlace == 0) {
            return;
        }
        double rate = (double) newPlaces / withPlace;
        BatchHealthReport.Level level = lowerIsBetter(rate, NEW_PLACE_OK, NEW_PLACE_DEGRADED);
        metrics.add(new BatchHealthReport.Metric("newPlaceRate", "每章新地点率",
                rate, "个/章", "≤" + BatchHealthReport.format(NEW_PLACE_OK) + "（改造前基线 0.83）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                "新地点 " + newPlaces + " / 有地点记录 " + withPlace + " 章，全部地点 " + seen.size() + " 个"));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("地点密度过高：规划层的【地点轨迹】块应已回灌（PlaceTrajectoryPolicy），"
                    + "检查该块是否被注入——若注入后仍高，说明阶段窗口太短、本阶段无复用余地");
        }
    }

    /**
     * 过渡章占比（区间型指标）：太低说明没有节奏留白（改造前恒为 0，因为章型无法声明），
     * 太高说明节奏偏松。区间内的两端都要判，单侧阈值会漏掉"完全不用 transition"这一现状。
     */
    private void addTransitionShare(List<BatchHealthReport.Metric> metrics,
                                    List<String> recommendations,
                                    List<ChapterSummaryEntity> chapters) {
        long transition = chapters.stream()
                .filter(s -> ChapterTypeVO.of(s.getChapterType()).isTransition())
                .count();
        double share = (double) transition / chapters.size();
        BatchHealthReport.Level level;
        if (share > TRANSITION_MAX_DEGRADED || share < TRANSITION_MIN_DEGRADED) {
            level = BatchHealthReport.Level.DEGRADED;
        } else if (share > TRANSITION_MAX_OK || share < TRANSITION_MIN_OK) {
            level = BatchHealthReport.Level.WATCH;
        } else {
            level = BatchHealthReport.Level.OK;
        }
        metrics.add(new BatchHealthReport.Metric("transitionShare", "过渡章占比",
                share * 100, "%", pct(TRANSITION_MIN_OK) + "–" + pct(TRANSITION_MAX_OK) + "（改造前恒为 0）",
                BatchHealthReport.Direction.HIGHER_IS_BETTER, level,
                "过渡章 " + transition + " / " + chapters.size() + " 章"));
        if (share < TRANSITION_MIN_OK) {
            recommendations.add("没有过渡章：章型已新增 transition，若占比仍为 0，"
                    + "说明规划层未采纳新章型——核对规划 prompt 的 chapterType 指引与解析侧是否吞掉了该值");
        } else if (share > TRANSITION_MAX_OK) {
            recommendations.add("过渡章占比偏高：节奏偏松，检查规划层过渡章配额（≤1/3、不得连续 >2 章）是否被遵守");
        }
    }

    private void addTransitionRun(List<BatchHealthReport.Metric> metrics,
                                  List<String> recommendations,
                                  List<ChapterSummaryEntity> chapters) {
        int maxRun = 0;
        int run = 0;
        int chapterNo = 0;
        for (ChapterSummaryEntity s : chapters) {
            if (ChapterTypeVO.of(s.getChapterType()).isTransition()) {
                run++;
                if (run > maxRun) {
                    maxRun = run;
                    chapterNo = s.getChapterNo() == null ? 0 : s.getChapterNo();
                }
            } else {
                run = 0;
            }
        }
        BatchHealthReport.Level level = maxRun <= TRANSITION_RUN_OK ? BatchHealthReport.Level.OK
                : (maxRun >= TRANSITION_RUN_DEGRADED ? BatchHealthReport.Level.DEGRADED
                        : BatchHealthReport.Level.WATCH);
        metrics.add(new BatchHealthReport.Metric("maxTransitionRun", "最长连续过渡章",
                maxRun, "章", "≤" + TRANSITION_RUN_OK + " 章（规划约束：不得连续超过 2 章）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                maxRun == 0 ? "本样本无过渡章（见过渡章占比指标）"
                        : "最长一段为第 " + (chapterNo - maxRun + 1) + "-" + chapterNo + " 章"));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("过渡章连续段越线：占比达标不代表分布合理，连续 3 章以上过渡会让中段失速。"
                    + "检查规划层的过渡章排布——若同一阶段反复出现，多半是阶段任务供给不足、"
                    + "模型用过渡章拖进度");
        }
    }

    /** 低密度章占比（排除过渡章——过渡章本就是有意留白，计入会逼规划层给留白也塞满事件） */    private void addLowDensityShare(List<BatchHealthReport.Metric> metrics,
                                    List<String> recommendations,
                                    List<ChapterSummaryEntity> chapters) {
        int counted = 0;
        int low = 0;
        for (ChapterSummaryEntity s : chapters) {
            if (s.getValidChars() == null || ChapterTypeVO.of(s.getChapterType()).isTransition()) {
                continue;
            }
            counted++;
            if (s.getValidChars() < LOW_DENSITY_CHARS) {
                low++;
            }
        }
        if (counted == 0) {
            return;
        }
        double share = (double) low / counted;
        BatchHealthReport.Level level = lowerIsBetter(share, LOW_DENSITY_OK, LOW_DENSITY_DEGRADED);
        metrics.add(new BatchHealthReport.Metric("lowDensityShare", "低密度章占比",
                share * 100, "%", "≤" + pct(LOW_DENSITY_OK) + "（非过渡章中有效字 <" + LOW_DENSITY_CHARS + " 的比例）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                low + " / " + counted + " 章（已排除过渡章）"));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("短章偏多：这是规划层「计划供给不足」，不是正文层问题——"
                    + "核对密度反馈块是否已注入，以及本段计划的关键事件是否给足");
        }
        addRecentLowDensityShare(metrics, chapters);
    }

    /** 近期窗口章数：与全量累计值并列展示的"当前水平"读数 */
    private static final int RECENT_WINDOW = 10;

    /**
     * **近期低密度章占比**：只统计**最后 {@value #RECENT_WINDOW} 章**，
     * 与全量累计值（{@link #addLowDensityShare}）并列展示。
     *
     * <p><b>为什么必须有两个口径</b>：累计值把全书历史一起算，于是**早期几十章的坏数据
     * 会永久压着这个指标**——即使规划层已经把后续章节治好，读数也永远回不到健康区，
     * "改好了没有"这个问题在体检单上没有答案。近期口径直接回答它。
     * 两者背离（累计差、近期好）是**修复生效**的正常形态，不应被读成矛盾。
     */
    private void addRecentLowDensityShare(List<BatchHealthReport.Metric> metrics,
                                          List<ChapterSummaryEntity> chapters) {
        if (chapters == null || chapters.isEmpty()) {
            return;
        }
        List<ChapterSummaryEntity> recent = new ArrayList<>();
        for (int i = chapters.size() - 1; i >= 0 && recent.size() < RECENT_WINDOW; i--) {
            ChapterSummaryEntity s = chapters.get(i);
            if (s == null) {
                continue;
            }
            recent.add(s);
        }
        int counted = 0;
        int low = 0;
        for (ChapterSummaryEntity s : recent) {
            if (s.getValidChars() == null || ChapterTypeVO.of(s.getChapterType()).isTransition()) {
                continue;
            }
            counted++;
            if (s.getValidChars() < LOW_DENSITY_CHARS) {
                low++;
            }
        }
        if (counted == 0) {
            return;
        }
        double share = (double) low / counted;
        BatchHealthReport.Level level = lowerIsBetter(share, LOW_DENSITY_OK, LOW_DENSITY_DEGRADED);
        metrics.add(new BatchHealthReport.Metric("recentLowDensityShare",
                "近期低密度章占比（近" + counted + "章）",
                share * 100, "%", "≤" + pct(LOW_DENSITY_OK) + "（同上口径，但只统计最近 " + RECENT_WINDOW + " 章）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                low + " / " + counted + " 章——本指标反映**当前**水平；上方累计值含不可变的历史章，两者背离属正常"));
    }

    /** 未核销质量债：债是跨章回灌信号，积压说明同类问题反复复发没被治住 */
    private void addUnresolvedDebt(List<BatchHealthReport.Metric> metrics,
                                   List<String> recommendations,
                                   List<QualityDebtEntity> debts) {
        long unresolved = debts == null ? 0 : debts.stream()
                .filter(d -> d != null && !d.isResolved())
                .count();
        BatchHealthReport.Level level = unresolved <= DEBT_OK ? BatchHealthReport.Level.OK
                : (unresolved > DEBT_DEGRADED ? BatchHealthReport.Level.DEGRADED : BatchHealthReport.Level.WATCH);
        int total = debts == null ? 0 : debts.size();
        metrics.add(new BatchHealthReport.Metric("unresolvedDebt", "未核销质量债",
                unresolved, "条", "≤" + DEBT_OK + " 条", BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                "未核销 " + unresolved + " / 累计 " + total + " 笔"));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("质量债积压：核对债的维度分布（QualityDebtEntity.issues 的 dimension），"
                    + "确认同维度连续复发是否被 settle 正确核销；若集中在本批新增维度，多半是新闸门刚上线");
        }
    }

    /** 阶段出口条件达成率：改造前仅 22%（含复合谓词整体判定与"永不退场"两重原因） */
    private void addExitConditionRate(List<BatchHealthReport.Metric> metrics,
                                      List<String> recommendations,
                                      List<StageBlueprintEntity> blueprints) {
        if (blueprints == null || blueprints.isEmpty()) {
            return;
        }
        int conditions = 0;
        int met = 0;
        int atoms = 0;
        int metAtoms = 0;
        int writtenOff = 0;
        for (StageBlueprintEntity stage : blueprints) {
            if (stage == null) {
                continue;
            }
            if (stage.getExitResults() != null) {
                for (StageBlueprintEntity.ExitConditionResult r : stage.getExitResults()) {
                    if (r == null) {
                        continue;
                    }
                    conditions++;
                    if (Boolean.TRUE.equals(r.getMet())) {
                        met++;
                    }
                    if (r.getTotalAtoms() != null) {
                        atoms += r.getTotalAtoms();
                        metAtoms += r.getMetAtoms() == null ? 0 : r.getMetAtoms();
                    }
                }
            }
            if (stage.getCarriedTasks() != null) {
                writtenOff += (int) stage.getCarriedTasks().stream()
                        .filter(t -> t != null && t.getContent() != null && t.getContent().contains("出账"))
                        .count();
            }
        }
        if (conditions == 0) {
            return;
        }
        double rate = (double) met / conditions;
        BatchHealthReport.Level level = higherIsBetter(rate, EXIT_OK, EXIT_DEGRADED);
        String atomDetail = atoms > 0
                ? "；原子进度 " + metAtoms + "/" + atoms + "（原子化后新增口径）"
                : "；原子进度暂无数据（老蓝图）";
        metrics.add(new BatchHealthReport.Metric("exitConditionRate", "出口条件达成率",
                rate * 100, "%", "≥" + pct(EXIT_OK) + "（改造前基线 22%）",
                BatchHealthReport.Direction.HIGHER_IS_BETTER, level,
                met + " / " + conditions + " 条" + atomDetail + "，机械出账 " + writtenOff + " 条"));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("出口条件达成率偏低：确认条件已按分句核验（原子化后 {metAtoms}/{totalAtoms} 应可见）——"
                    + "若仍大量整条未达成，检查规划侧是否又把复合谓词写回；机械出账数上升是退场规则在收尾，属正常");
        }
    }

    private void addLooseTierShare(List<BatchHealthReport.Metric> metrics,
                                   List<String> recommendations,
                                   List<ChapterSummaryEntity> chapters) {
        int total = 0;
        int loose = 0;
        for (ChapterSummaryEntity s : chapters) {
            for (List<ChapterSummaryEntity.StateEntry> entries : List.of(
                    safe(s.getCharacterStates()), safe(s.getItemStates()), safe(s.getFactionStates()))) {
                for (ChapterSummaryEntity.StateEntry entry : entries) {
                    if (entry == null) {
                        continue;
                    }
                    total++;
                    if (isLooseTier(entry.getEvidenceTier())) {
                        loose++;
                    }
                }
            }
            for (ChapterSummaryEntity.ConsistencyFact fact : safe(s.getConsistencyFacts())) {
                if (fact == null) {
                    continue;
                }
                total++;
                if (isLooseTier(fact.getEvidenceTier())) {
                    loose++;
                }
            }
        }
        if (total == 0) {
            return;
        }
        double share = (double) loose / total;
        BatchHealthReport.Level level = lowerIsBetter(share, LOOSE_OK, LOOSE_DEGRADED);
        metrics.add(new BatchHealthReport.Metric("looseTierShare", "放宽档入账占比",
                share * 100, "%", "≤" + pct(LOOSE_OK) + "（留痕档 + 裁决档占已入账条目比例，含一致性事实）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                loose + " / " + total + " 条（三账本状态 + 一致性事实）"));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("放宽档占比偏高：反编造门被放宽的比例过大，需复核 EvidenceMatch 的"
                    + "集中度与锚定阈值——放宽过头的代价是编造事实进账本，比缺账更难回滚");
        }
    }

    /** 是否为"放宽入账"档位：留痕档（spread/anchored）或裁决档（adjudicated） */
    private static boolean isLooseTier(String tier) {
        return tier != null && (tier.startsWith("spread") || tier.startsWith("anchored")
                || tier.startsWith("adjudicated"));
    }

    private void addCandidateMetrics(List<BatchHealthReport.Metric> metrics,
                                     List<String> recommendations,
                                     int chapterCount,
                                     cn.novel.yonren.domain.novel.service.armory.candidate.CandidateSampleService.CandidateStats stats) {
        if (stats == null || stats.triggered() <= 0) {
            return;
        }
        double triggerRate = (double) stats.triggered() / Math.max(1, chapterCount);
        BatchHealthReport.Level triggerLevel = lowerIsBetter(triggerRate, CANDIDATE_TRIGGER_OK, CANDIDATE_TRIGGER_DEGRADED);
        metrics.add(new BatchHealthReport.Metric("candidateTriggerRate", "候选选优触发率",
                triggerRate * 100, "%", "≤" + pct(CANDIDATE_TRIGGER_OK) + "（历史基线 19%）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, triggerLevel,
                "触发 " + stats.triggered() + " / " + chapterCount + " 章"));
        if (triggerLevel != BatchHealthReport.Level.OK) {
            recommendations.add("候选选优触发率偏高：审校给出的叙事 MINOR 偏多。"
                    + "注意机械 MINOR（标题/文风/机制）走独立分账、不参与触发——若排除机械因素后仍高，"
                    + "再考虑收紧触发条件（如 minorCount ≥ 2 才触发）");
        }

        if (stats.triggered() > 0) {
            double adoption = (double) stats.adopted() / stats.triggered();
            BatchHealthReport.Level adoptionLevel = higherIsBetter(adoption, CANDIDATE_ADOPTION_OK, CANDIDATE_ADOPTION_DEGRADED);
            metrics.add(new BatchHealthReport.Metric("candidateAdoptionRate", "候选采纳率",
                    adoption * 100, "%", "≥" + pct(CANDIDATE_ADOPTION_OK) + "（历史基线 48%）",
                    BatchHealthReport.Direction.HIGHER_IS_BETTER, adoptionLevel,
                    "采纳 " + stats.adopted() + " / 触发 " + stats.triggered() + " 次"));
            if (adoptionLevel != BatchHealthReport.Level.OK) {
                recommendations.add("候选采纳率偏低：多数重写没有通过盲评，触发条件可能过宽——"
                        + "这是判定候选链路是否在烧钱的**主要**信号，比触发率更值得看");
            }
        }
    }

    private void addDialogueMetrics(List<BatchHealthReport.Metric> metrics,
                                    List<String> recommendations,
                                    List<ChapterSummaryEntity> chapters) {
        String genre = chapters.stream()
                .map(ChapterSummaryEntity::getStoryGenre)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        String genreNote = DialogueRatioPolicy.isDialogueDriven(genre) ? "，对话驱动题材从严" : "";

        List<Double> ratios = chapters.stream()
                .map(ChapterSummaryEntity::getDialogueRatio)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (!ratios.isEmpty()) {
            double avg = ratios.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double min = ratios.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
            // 方向：对白占比**越高越健康**（低=滑向主角单机）——这里踩过两次 lowerIsBetter 的坑
            BatchHealthReport.Level level = higherIsBetter(avg,
                    DialogueRatioPolicy.ratioOk(), DialogueRatioPolicy.ratioDegraded());
            metrics.add(new BatchHealthReport.Metric("dialogueRatio", "对白行占比",
                    avg * 100, "%", "≥" + pct(DialogueRatioPolicy.ratioOk())
                    + "（旧书 45.6%，独白坍缩本 11%" + genreNote + "）",
                    BatchHealthReport.Direction.HIGHER_IS_BETTER, level,
                    String.format("均值 %.1f%%，最低一章 %.1f%%（%d 章有数据）", avg * 100, min * 100, ratios.size())));
            if (level != BatchHealthReport.Level.OK) {
                recommendations.add("对白占比过低：正文滑向「主角单机」——全是内心独白与推演，缺少双向互动。"
                        + "修复靠规划层的人物供给规则（规则 8：非主角主动发起的事件、双向互动、代价多样化）"
                        + "与审校 character 维度的工具人检查");
            }
        }

        List<Double> densities = new java.util.ArrayList<>();
        for (ChapterSummaryEntity s : chapters) {
            if (s.getDialogueUtterances() == null || s.getValidChars() == null || s.getValidChars() <= 0) {
                continue;
            }
            densities.add(DialogueRatioPolicy.utteranceDensity(s.getDialogueUtterances(), s.getValidChars()));
        }
        if (densities.isEmpty()) {
            return;
        }
        double avgDensity = densities.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double minDensity = densities.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        // 方向同上：轮次密度越高越健康
        BatchHealthReport.Level densityLevel = higherIsBetter(avgDensity,
                DialogueRatioPolicy.densityOk(genre), DialogueRatioPolicy.densityDegraded(genre));
        metrics.add(new BatchHealthReport.Metric("dialogueDensity", "对白轮次密度",
                avgDensity, "次/千字", "≥" + trim(DialogueRatioPolicy.densityOk(genre))
                + "（全样本中位 9.5，同批新书 10.0-10.9" + genreNote + "）",
                BatchHealthReport.Direction.HIGHER_IS_BETTER, densityLevel,
                String.format("均值 %.1f 次/千字，最低一章 %.1f（%d 章有数据）",
                        avgDensity, minDensity, densities.size())));
        if (densityLevel != BatchHealthReport.Level.OK) {
            recommendations.add("对白轮次密度不足：占比可能合格但都是一来一回偏少的**长对白段**。"
                    + "恋爱/智斗题材的拉扯感来自高频短交锋（3-5 字一句、一来一回十几轮）——"
                    + "规划层应把对白写成多次短往返，而不是少数长段落");
        }
        addDialogueCollapseShare(metrics, recommendations, chapters, genre);
    }

    /** 对白坍缩章占比的判定线（逐章口径；与低密度章占比同款形状） */
    private static final double DIALOGUE_COLLAPSE_OK = 0.15;
    private static final double DIALOGUE_COLLAPSE_DEGRADED = 0.35;

    /**
     * **对白坍缩章占比**（逐章口径）：非过渡章中轮次密度低于劣化线的比例。
     *
     * <p><b>为什么必须单开一个占比口径</b>：已有的「对白轮次密度」只报批次均值与最小值——
     * 均值会被正常章拉平，最小值只给一个数字、不给章号。实测某章轮次密度远低于劣化线
     * （同批其它章成倍于它），而批次均值照样落在健康区间，于是这一章从体检单上消失了，
     * 直到读者反馈"对话崩了"才被发现。占比口径直接回答"崩了几章、是哪几章"。
     *
     * <p>排除过渡章的理由与 {@link #addLowDensityShare} 同款：过渡章本就该短、该留白。
     */
    private void addDialogueCollapseShare(List<BatchHealthReport.Metric> metrics,
                                          List<String> recommendations,
                                          List<ChapterSummaryEntity> chapters,
                                          String genre) {
        double line = DialogueRatioPolicy.densityDegraded(genre);
        int counted = 0;
        int collapsed = 0;
        StringBuilder collapsedChapters = new StringBuilder();
        for (ChapterSummaryEntity s : chapters) {
            if (s == null || s.getDialogueUtterances() == null || s.getValidChars() == null
                    || s.getValidChars() <= 0 || ChapterTypeVO.of(s.getChapterType()).isTransition()) {
                continue;
            }
            counted++;
            double density = DialogueRatioPolicy.utteranceDensity(s.getDialogueUtterances(), s.getValidChars());
            if (density < line) {
                collapsed++;
                if (collapsedChapters.length() > 0) {
                    collapsedChapters.append("、");
                }
                collapsedChapters.append("第").append(s.getChapterNo()).append("章(")
                        .append(String.format("%.1f", density)).append(")");
            }
        }
        if (counted == 0) {
            return;
        }
        double share = (double) collapsed / counted;
        BatchHealthReport.Level level = lowerIsBetter(share, DIALOGUE_COLLAPSE_OK, DIALOGUE_COLLAPSE_DEGRADED);
        metrics.add(new BatchHealthReport.Metric("dialogueCollapseShare", "对白坍缩章占比",
                share * 100, "%", "≤" + pct(DIALOGUE_COLLAPSE_OK) + "（非过渡章中轮次密度 <"
                + trim(line) + " 的比例）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                collapsed == 0
                        ? collapsed + " / " + counted + " 章（已排除过渡章）"
                        : collapsed + " / " + counted + " 章：" + collapsedChapters + "（已排除过渡章）"));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("对白坍缩章偏多：这些章**篇幅够、事件也在，但角色不开腔**——"
                    + "通篇是动作分解与内心推演，读起来像流程说明而不是戏。"
                    + "修复靠规划层：关键事件要由对话承载（不是「至少 1 条」占位，"
                    + "而是让冲突在台词里发生），并核对该章节拍表的 dialogue 供给是否给足");
        }
    }

    /** 质量评分读数的分级线：rubric 里 6=合格、7=良好（8=优秀）。实测分数集中在 7-9，故只作趋势读数 */
    private static final double QUALITY_OVERALL_OK = 7.0;
    private static final double QUALITY_OVERALL_DEGRADED = 6.0;

    /**
     * 质量评分（主观）与「评分-机械背离」两行读数。
     *
     * <p><b>为什么背离要单独报</b>：那是"两把尺子对同一章给出相反结论"的计数——
     * 实测有章被机械判对白坍缩（密度低于劣化线）而主观给 dialogue=8、问题 0 条。
     * 分数本身只是观测值，但**背离率高说明那把主观尺子在该维度没有分辨力**，
     * 不能拿它去驱动规划层调整（要先改 rubric，或改成与上一窗口成对比较）。
     */
    private void addQualityReview(List<BatchHealthReport.Metric> metrics,
                                  List<String> recommendations,
                                  cn.novel.yonren.domain.novel.service.armory.memory.QualityReviewService
                                          .QualitySnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        String window = "第" + snapshot.windowStart() + "-" + snapshot.windowEnd() + "章窗口";
        if (snapshot.overall() != null) {
            metrics.add(new BatchHealthReport.Metric("qualityOverall", "质量评分（主观）",
                    snapshot.overall(), "分",
                    "≥" + BatchHealthReport.format(QUALITY_OVERALL_OK)
                            + "（rubric 里 6=合格 / 7=良好 / 8=优秀；实测分数集中在 7-9，只作趋势读数不作判据）",
                    BatchHealthReport.Direction.HIGHER_IS_BETTER,
                    higherIsBetter(snapshot.overall(), QUALITY_OVERALL_OK, QUALITY_OVERALL_DEGRADED),
                    window + "；各维度 " + snapshot.avg()));
        }
        List<String> divergences = snapshot.divergences();
        if (divergences != null && !divergences.isEmpty()) {
            metrics.add(new BatchHealthReport.Metric("qualityDivergence", "评分与机械背离",
                    divergences.size(), "项",
                    "=0（主观给 8 分及以上、而机械判该维度劣化的章数）",
                    BatchHealthReport.Direction.LOWER_IS_BETTER, BatchHealthReport.Level.WATCH,
                    String.join("；", divergences)));
            recommendations.add("主观评分与机械指标背离 " + divergences.size() + " 项（" + window + "）："
                    + "这些章机械判对白坍缩、主观却给了 8 分以上——该维度**以机械判据为准**。"
                    + "反复出现说明这把主观尺子在该维度没有分辨力，不要用它驱动规划层调整；"
                    + "要么给 rubric 加锚点样本，要么改成与上一窗口成对比较");
        }
    }

    /** 整数阈值去掉小数尾巴（8.0 → 8），小数阈值保留一位 */
    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.format("%.1f", v);
    }

    private void addStagePlanningCoverage(List<BatchHealthReport.Metric> metrics,
                                          List<String> recommendations,
                                          List<ChapterSummaryEntity> chapters,
                                          List<StageBlueprintEntity> blueprints) {
        int writtenEnd = chapters.isEmpty() ? 0 : chapters.get(chapters.size() - 1).getChapterNo();
        Integer coveredEnd = null;
        if (blueprints != null) {
            for (StageBlueprintEntity b : blueprints) {
                if (b != null && b.getEndChapter() != null) {
                    coveredEnd = coveredEnd == null ? b.getEndChapter() : Math.max(coveredEnd, b.getEndChapter());
                }
            }
        }
        if (coveredEnd == null) {
            // 从未启用蓝图的故事（老故事/无蓝图模式）不算降级——本闸针对"蓝图链存在但止点落后"：
            // 启用过蓝图的批次突然失去规划覆盖，才是需要点名的异常
            return;
        }
        boolean covered = coveredEnd >= writtenEnd;
        BatchHealthReport.Level level = covered ? BatchHealthReport.Level.OK : BatchHealthReport.Level.DEGRADED;
        String detail = covered
                ? "最新蓝图止于第 " + coveredEnd + " 章，已覆盖本批末章"
                : "最新蓝图止于第 " + coveredEnd + " 章，本批第 " + (coveredEnd + 1) + "-"
                        + writtenEnd + " 章在旧蓝图下生成（新任务/退出条件/排期未生成）";
        if (!covered) {
            recommendations.add("阶段规划缺位：蓝图生成/解析失败已降级（fail-soft），第 " + (coveredEnd + 1) + "-"
                    + writtenEnd + " 章沿用旧蓝图——本批的任务、出口条件审计与新排期均未发生。"
                    + "建议补跑一次阶段蓝图（或人工核对本段剧情走向是否仍服从旧蓝图）后再续写后续章段。");
        }
        metrics.add(new BatchHealthReport.Metric("stagePlanningCoverage", "阶段规划覆盖",
                coveredEnd, "章",
                "≥ 已写末章（" + writtenEnd + "）——蓝图止点落后即为本批在旧/无蓝图下滑行",
                BatchHealthReport.Direction.HIGHER_IS_BETTER, level, detail));
    }

    private void addOutlinePacing(List<BatchHealthReport.Metric> metrics,
                                  List<String> recommendations,
                                  List<ChapterSummaryEntity> chapters,
                                  String chapterGoal) {
        OutlineSegmentParser.Outline outline = OutlineSegmentParser.parse(chapterGoal);
        if (outline.isEmpty()) {
            return;
        }
        int writtenEnd = chapters.isEmpty() ? 0 : chapters.get(chapters.size() - 1).getChapterNo();
        OutlineSegmentParser.OutlineSegment budget = outline.segmentFor(writtenEnd);
        if (budget == null) {
            // 已写出大纲覆盖范围：超纲是结构性事件，比滞后更严重
            metrics.add(new BatchHealthReport.Metric("outlinePacing", "进度对齐",
                    writtenEnd, "章",
                    "≤ 大纲覆盖末章（" + outline.segments().get(outline.segments().size() - 1).endChapter() + "）",
                    BatchHealthReport.Direction.LOWER_IS_BETTER, BatchHealthReport.Level.DEGRADED,
                    "已写到第 " + writtenEnd + " 章，超出大纲覆盖范围——章节预算与实际进度彻底脱钩，"
                            + "必须重排大纲或收束剧情"));
            recommendations.add("进度超纲：已写到第 " + writtenEnd + " 章而大纲只预算到 "
                    + outline.segments().get(outline.segments().size() - 1).endChapter()
                    + " 章——重排大纲章段预算，或让剧情向大纲末段收束，二选一。");
            return;
        }
        // 实际时间标记：最新摘要的年龄事实 + 最新 timePoint 的年份（与时序锚渲染同源）
        // 两个维度都走 StoryPacing 统一口径——观测层自写正则会被模型的写法变化击穿：
        // 实测两轮误报，①年份写成"二〇〇八年"读不到（回溯到第 1 章的 2002，报滞后 6 段）；
        // ②年龄把「人物四岁+年份数字」这类叙述串当成当前年龄（contains("岁") 过宽，读成 4 岁）
        Integer actualAge = StoryPacing.latestAnchorAge(chapters);
        Integer actualYear = StoryPacing.latestAnchorYear(chapters);

        // 滞后计算：可数值比较的维度取 max（年龄滞后 / 年份滞后），无量纲维度豁免
        int ageLag = (budget.ageEnd() != null && actualAge != null)
                ? Math.max(0, budget.ageEnd() - actualAge) : -1;
        int yearLag = (budget.yearStart() != null && actualYear != null)
                ? Math.max(0, budget.yearStart() - actualYear) : -1;
        if (ageLag < 0 && yearLag < 0) {
            return; // 该段无数值时间标记（境界纪年等）——不臆造，豁免
        }
        int lag = Math.max(ageLag, yearLag);
        String lagDesc = (ageLag >= 0 ? "年龄预算 " + budget.ageEnd() + " 岁 vs 实际 " + actualAge + " 岁" : "")
                + (ageLag >= 0 && yearLag >= 0 ? "；" : "")
                + (yearLag >= 0 ? "年份预算 " + budget.yearStart() + " vs 实际 " + actualYear : "");
        BatchHealthReport.Level level = lag >= 3 ? BatchHealthReport.Level.DEGRADED
                : lag >= 1 ? BatchHealthReport.Level.WATCH : BatchHealthReport.Level.OK;
        metrics.add(new BatchHealthReport.Metric("outlinePacing", "进度对齐",
                lag, "段", "≤1 段（大纲章段预算的时间标记 vs 时序锚实际值；滞后即正文落后于大纲路标）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                "第 " + writtenEnd + " 章按大纲应处【" + budget.startChapter() + "-" + budget.endChapter()
                        + "章 " + StringUtils.defaultString(budget.timeLabel()) + "】段｜" + lagDesc));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("进度滞后约 " + lag + " 年：大纲把第 " + budget.startChapter() + "-"
                    + budget.endChapter() + " 章预算给了【" + StringUtils.defaultString(budget.timeLabel())
                    + "】段，实际剧情仍在更早时段。两条出路：①规划层加速（跳月/压缩日常，观察后续批次滞后是否收敛）；"
                    + "②承认慢节奏，重排大纲章段预算。不应长期维持现状——路标与正文漂移会越滚越大。");
        }
    }


    private BatchHealthReport.Level overall(List<BatchHealthReport.Metric> metrics) {
        BatchHealthReport.Level worst = BatchHealthReport.Level.OK;
        long degraded = 0;
        for (BatchHealthReport.Metric metric : metrics) {
            worst = worst.worse(metric.level());
            if (metric.level() == BatchHealthReport.Level.DEGRADED
                    || metric.level() == BatchHealthReport.Level.CRITICAL) {
                degraded++;
            }
        }
        if (degraded >= CRITICAL_METRIC_COUNT) {
            return BatchHealthReport.Level.CRITICAL;
        }
        return worst;
    }

    /** "越大越好"型分级：≥ok 为 OK，≥degradedLine 为 WATCH，否则 DEGRADED */
    private static BatchHealthReport.Level higherIsBetter(double value, double ok, double degradedLine) {
        if (value >= ok) {
            return BatchHealthReport.Level.OK;
        }
        return value >= degradedLine ? BatchHealthReport.Level.WATCH : BatchHealthReport.Level.DEGRADED;
    }

    /** "越小越好"型分级：≤ok 为 OK，≤degradedLine 为 WATCH，否则 DEGRADED */
    private static BatchHealthReport.Level lowerIsBetter(double value, double ok, double degradedLine) {
        if (value <= ok) {
            return BatchHealthReport.Level.OK;
        }
        return value <= degradedLine ? BatchHealthReport.Level.WATCH : BatchHealthReport.Level.DEGRADED;
    }

    private static String pct(double ratio) {
        return BatchHealthReport.format(ratio * 100) + "%";
    }

    private static int size(List<?> list) {
        return list == null ? 0 : list.size();
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
