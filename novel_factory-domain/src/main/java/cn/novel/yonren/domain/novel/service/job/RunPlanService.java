package cn.novel.yonren.domain.novel.service.job;

import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.quality.BatchHealthReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;


@Slf4j
@Service
@RequiredArgsConstructor
public class RunPlanService {

    private final StoryProperties storyProperties;

    /** 启动即告警：无界配置最坏是"无人值守烧到天荒地老"，必须在日志里显式点名而非静默放行 */
    @jakarta.annotation.PostConstruct
    void warnIfUnbounded() {
        String unbounded = validateBounded();
        if (unbounded != null) {
            log.error("【自动续批配置告警】{}", unbounded);
        }
    }

    /**
     * 续批决策。
     *
     * @param continueNext 是否继续下一批
     * @param reason       人类可读理由（写入日志与作业状态，供"为什么停/为什么续"归因）
     */
    public record Decision(boolean continueNext, String reason) {
    }

    /** 是否开启自动续批（yml: story.run-plan.enabled，默认 false——它会自动连续花 token） */
    public boolean enabled() {
        StoryProperties.RunPlanProperties plan = plan();
        return plan != null && plan.isEnabled();
    }

    /**
     * 续批配置的**有限性校验**：开启自动续批时，至少要有一样东西能让它停下来。
     *
     * <p><b>为什么必须校验</b>：三个独立配置的默认值叠加后形成"无人值守 + 无目标 + 无上限"——
     * {@code targetChapters} 默认 null（且 constraints 未开时 {@link #targetChapters()} 也返回 null，
     * "达标"判停消失）、{@code maxBatches} 默认 0（不限）、budget 两阈值默认 null（不熔断）。
     * 此时唯一的停线是批末体检 CRITICAL，而体检本身是 fail-soft 的（读取失败即跳过判停）。
     * 与项目在其它处的做法一致（无法识别的配置取最保守值，见 {@link #parseStopLevel}）。
     *
     * @return null = 配置可用；非 null = 人类可读的拒绝理由（调用方应拒绝启用并告警）
     */
    public String validateBounded() {
        if (!enabled()) {
            return null;
        }
        StoryProperties.RunPlanProperties plan = plan();
        boolean hasTarget = targetChapters() != null;
        boolean hasBatchLimit = plan != null && plan.getMaxBatches() > 0;
        StoryProperties.BudgetProperties budget = storyProperties.getBudget();
        boolean hasBudgetFuse = budget != null
                && (budget.getHardTotalTokens() != null || budget.getWarnTotalTokens() != null);
        if (hasTarget || hasBatchLimit || hasBudgetFuse) {
            return null;
        }
        return "自动续批已开启（story.run-plan.enabled=true），但三个停机条件同时缺失："
                + "未配置 target-chapters、max-batches 为 0（不限）、budget 未配任何阈值——"
                + "此时只有批末体检能停（且体检读取失败会跳过判停），作业可能无界连续烧 token。"
                + "请至少配置其中一项（推荐 budget.hard-total-tokens）。";
    }

    /**
     * 续批决策。批次进度与体检结论都由调用方给出（作业层已持有），本方法保持纯函数。
     *
     * @param chaptersDone 当前已完成章数（含本批）
     * @param batchesDone  已完成批次数（含本批）
     * @param health       本批批末体检结论，可为 null（样本不足/读取失败时不做健康判停）
     */
    public Decision decide(int chaptersDone, int batchesDone, BatchHealthReport health) {
        if (!enabled()) {
            return new Decision(false, "未开启自动续批（story.run-plan.enabled=false）");
        }
        // 无界配置在续批入口也拦一道：启动校验之外的运行时兜底（配置可在前端热改）
        String unbounded = validateBounded();
        if (unbounded != null) {
            return new Decision(false, "自动续批配置无界，已停止：" + unbounded);
        }
        Integer target = targetChapters();
        if (target != null && chaptersDone >= target) {
            return new Decision(false, "已达标：" + chaptersDone + "/" + target + " 章");
        }
        StoryProperties.RunPlanProperties plan = plan();
        if (plan.getMaxBatches() > 0 && batchesDone >= plan.getMaxBatches()) {
            return new Decision(false, "已达续批上限 " + plan.getMaxBatches() + " 批（已完成 " + chaptersDone + " 章）");
        }
        BatchHealthReport.Level stopLevel = parseStopLevel(plan.getStopOnHealthLevel());
        if (health != null && health.overall().ordinal() >= stopLevel.ordinal()) {
            return new Decision(false, "批末体检 " + health.overall() + " 达到停线 " + stopLevel
                    + "，停止自动续批交人工过问（详情见批末体检日志或 /health 端点）");
        }
        String progress = target == null ? chaptersDone + " 章（未设目标）" : chaptersDone + "/" + target + " 章";
        return new Decision(true, "继续自动续批：已完成 " + progress + "，即将开始第 " + (batchesDone + 1) + " 批");
    }

    /**
     * 目标总章数：run-plan.target-chapters 优先；未配置时用 constraints（仅在 enforce-chapter-limit 开启时生效）。
     * 都取不到返回 null —— 此时只有续批上限与体检判停，没有"达标"判停。
     */
    public Integer targetChapters() {
        StoryProperties.RunPlanProperties plan = plan();
        if (plan != null && plan.getTargetChapters() != null && plan.getTargetChapters() > 0) {
            return plan.getTargetChapters();
        }
        StoryVO.Constraints constraints = storyProperties.getConstraints();
        if (constraints != null && Boolean.TRUE.equals(constraints.getEnforceChapterLimit())
                && constraints.getMaxChapterCount() != null && constraints.getMaxChapterCount() > 0) {
            return constraints.getMaxChapterCount();
        }
        return null;
    }


    public ArmoryCommandEntity nextBatchCommand(ArmoryCommandEntity current, String storyDirName, int chaptersDone) {
        if (current == null || StringUtils.isBlank(storyDirName)) {
            return null;
        }
        ArmoryCommandEntity next = new ArmoryCommandEntity();
        next.setStoryVO(current.getStoryVO());
        next.setMaxChapterCount(current.getMaxChapterCount());
        next.setResumeStoryDir(storyDirName);
        // 导演通道：创作要点随批传递（自续批保持同一创意方向）
        next.setCreativeNotes(current.getCreativeNotes());

        StoryContextEntity context = new StoryContextEntity();
        if (current.getStoryContextEntity() != null) {
            BeanUtils.copyProperties(current.getStoryContextEntity(), context);
        }
        StoryProperties.RunPlanProperties plan = plan();
        int size = batchSize(current, plan);
        Integer target = targetChapters();
        if (target != null) {
            int remaining = target - chaptersDone;
            if (remaining > 0) {
                size = Math.min(size, remaining);
            }
        }
        context.setChapterCount(Math.max(1, size));
        next.setStoryContextEntity(context);
        return next;
    }

    /** 批大小：run-plan.batch-size 优先，未配置沿用原请求（chapterCount 即批次大小） */
    private int batchSize(ArmoryCommandEntity current, StoryProperties.RunPlanProperties plan) {
        if (plan != null && plan.getBatchSize() != null && plan.getBatchSize() > 0) {
            return plan.getBatchSize();
        }
        Integer requested = current.getStoryContextEntity() == null
                ? null : current.getStoryContextEntity().getChapterCount();
        return requested == null || requested <= 0 ? 1 : requested;
    }

    /** 停线解析：无法识别的值一律取最保守的 CRITICAL（宁可能多跑，不误停） */
    static BatchHealthReport.Level parseStopLevel(String configured) {
        if (StringUtils.isBlank(configured)) {
            return BatchHealthReport.Level.CRITICAL;
        }
        for (BatchHealthReport.Level level : BatchHealthReport.Level.values()) {
            if (level.name().equalsIgnoreCase(configured.trim())) {
                return level;
            }
        }
        log.warn("run-plan.stop-on-health-level 配置值无法识别：{}，按 CRITICAL 处理", configured);
        return BatchHealthReport.Level.CRITICAL;
    }

    private StoryProperties.RunPlanProperties plan() {
        return storyProperties == null ? null : storyProperties.getRunPlan();
    }
}
