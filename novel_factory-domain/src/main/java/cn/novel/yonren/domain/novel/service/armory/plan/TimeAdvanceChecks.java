package cn.novel.yonren.domain.novel.service.armory.plan;

import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 时间推进的机械验收：计划声明的时间 vs 正文实际到达的时间。
 *
 * <p><b>为什么需要（2026-10-26）</b>：实测 238-242 章的计划合计推进 59 天，
 * 而摘要记录的时点只走了 16 天——**缺口 73%**，但此前无人发现。原因是三道关都失效了：
 * <ol>
 *   <li>{@code ChapterPlanChecks} 里"全段 timeAdvance 必须单调递增"只写在提示词里，
 *       <b>没有机械校验</b>（本类 {@link #validateMonotonic} 补上）；</li>
 *   <li>审计对"声明推进却无流逝"只记 MINOR，不拦截，也不回灌下一章；</li>
 *   <li>写手看到的时序锚是"**允许**推进至 X"——许可可以不行使，缺声明时更直接冻住时间。</li>
 * </ol>
 *
 * <p>本类只做**计算**：把计划目标日期与正文实际日期相减，产出可注入下一章的欠账描述。
 * 处置策略（回灌/记债/驳回）由调用方决定——与其它 policy 类同款分工。
 *
 * <p><b>验收口径</b>：
 * <ul>
 *   <li>计划侧取 {@code timeAdvance} 的目标日期（"推进4天，至2012年7月7日晚上" → 7月7日）；</li>
 *   <li>事实侧取摘要 {@code timePoint} 沿链推算出的绝对日期（见 {@link StoryDate#resolveChain}）；</li>
 *   <li>任一缺失则**跳过该章**（不臆造），只在两侧都有值时比较；</li>
 *   <li>欠账 = 目标 − 实际。超出 {@link #TOLERANCE_DAYS} 天才算欠账——
 *       正文比计划略快或略慢都属正常创作波动，只有量级差异才值得回灌。</li>
 * </ul>
 */
public final class TimeAdvanceChecks {

    /**
     * 允许的时间欠账天数：小于此值不回灌。
     *
     * <p>取 3 天的依据：段计划的常规推进节奏是"3-7 天/章"，一天两天的偏差属正常创作波动；
     * 而实测事故的量级是"计划 24 天、实际 0 天"，两者差两个数量级，用 3 天阈值足以区分。
     */
    public static final int TOLERANCE_DAYS = 3;

    private static final Pattern TARGET_DATE = Pattern.compile(
            "(?:至|到)\\s*(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})?");

    private TimeAdvanceChecks() {
    }

    /**
     * 解析计划 {@code timeAdvance} 声明的**目标日期**。
     * 优先取"至/到 X年Y月Z日"，其次取文本内任一绝对日期（{@link StoryDate#parseAbsolute}）。
     */
    public static StoryDate planTarget(String timeAdvance, StoryDate base) {
        if (StringUtils.isBlank(timeAdvance)) {
            return null;
        }
        Matcher m = TARGET_DATE.matcher(timeAdvance);
        if (m.find()) {
            int year = Integer.parseInt(m.group(1));
            int month = Integer.parseInt(m.group(2));
            int day = m.group(3) == null ? 1 : Integer.parseInt(m.group(3));
            return StoryDate.of(year, month, day);
        }
        return StoryDate.parseAbsolute(timeAdvance, base);
    }

    /**
     * 逐章比较计划与实际的欠账。
     *
     * @param chapters  段计划（按章号升序；取 {@code timeAdvance}）
     * @param summaries 历史摘要（按章号升序；事实侧按章号索引）
     * @return 欠账清单，按章号升序；无欠账返回空列表
     */
    public static List<Arrears> findArrears(List<ChapterPlanItemEntity> chapters,
                                            List<ChapterSummaryEntity> summaries) {
        if (chapters == null || chapters.isEmpty() || summaries == null || summaries.isEmpty()) {
            return List.of();
        }
        List<StoryDate> resolved = StoryDate.resolveChain(summaries);
        // 章号 → 实际日期（resolveChain 与 summaries 下标对齐）
        java.util.Map<Integer, StoryDate> actualByChapter = new java.util.HashMap<>();
        for (int i = 0; i < summaries.size(); i++) {
            ChapterSummaryEntity s = summaries.get(i);
            if (s != null && s.getChapterNo() != null && i < resolved.size()) {
                actualByChapter.put(s.getChapterNo(), resolved.get(i));
            }
        }
        // 基准日期：段首章之前最近一章的实际日期，供"月日"写法补全年份
        StoryDate base = null;
        for (int i = summaries.size() - 1; i >= 0; i--) {
            ChapterSummaryEntity s = summaries.get(i);
            if (s == null || s.getChapterNo() == null) {
                continue;
            }
            int firstPlan = chapters.get(0).getChapterNo() == null ? 0 : chapters.get(0).getChapterNo();
            if (s.getChapterNo() < firstPlan && actualByChapter.get(s.getChapterNo()) != null) {
                base = actualByChapter.get(s.getChapterNo());
                break;
            }
        }

        List<Arrears> result = new ArrayList<>();
        for (ChapterPlanItemEntity chapter : chapters) {
            if (chapter == null || chapter.getChapterNo() == null) {
                continue;
            }
            StoryDate target = planTarget(chapter.getTimeAdvance(), base);
            StoryDate actual = actualByChapter.get(chapter.getChapterNo());
            if (target == null || actual == null) {
                continue; // 任一侧无法定位 → 跳过，不臆造违规
            }
            int behind = actual.daysUntil(target);
            if (behind > TOLERANCE_DAYS) {
                result.add(new Arrears(chapter.getChapterNo(), target, actual, behind,
                        StringUtils.defaultString(chapter.getTimeAdvance())));
            }
        }
        return result;
    }

    /**
     * 时间推进的**单调性**机械校验：同一段内 {@code timeAdvance} 的目标日期不得倒退。
     *
     * <p>这条规则此前只存在于提示词（"全段 timeAdvance 必须单调递增"），没有执行通道——
     * 与"跳月吞线"、"伏笔净增"等闸门引入前的状态相同：写在 prompt 里的规则不校验就等于没有。
     *
     * <p>只比较**可解析出目标日期**的章；解析不出的章跳过（自由文本格式不强制，
     * 强行解析会误伤"同日夜间""第二天清晨"这类合理写法）。
     *
     * @return 违规描述，通过返回 null
     */
    /**
 * 时间声明的**倒退检查**由「全段单调递增」改为「只防倒退」。
     *
     * <p><b>为什么不再要求单调递增</b>：原实现要求全段 timeAdvance 严格递增，等于禁止倒叙。
     * 但倒叙、插叙、回忆杀都是合法叙事，真正该防的是**写回已确立时点之前**造成的时间线矛盾。
     * 现在只在"本章时间早于段内已确立的最近时点"时记违规，不限制"后一章比前一章晚"这个顺序本身。
     *
     * <p>只比较**可解析出目标日期**的章；解析不出的章跳过（自由文本格式不强制，
     * 强行解析会误伤"同日夜间""第二天清晨"这类合理写法）。
     *
     * @return 违规描述，通过返回 null
     */
    public static String validateMonotonic(List<ChapterPlanItemEntity> chapters) {
        if (chapters == null || chapters.isEmpty()) {
            return null;
        }
        StoryDate earliest = null;
        Integer earliestNo = null;
        List<String> issues = new ArrayList<>();
        for (ChapterPlanItemEntity chapter : chapters) {
            if (chapter == null || chapter.getChapterNo() == null) {
                continue;
            }
            StoryDate target = planTarget(chapter.getTimeAdvance(), earliest);
            if (target == null) {
                continue;
            }
            // 只防"早于已确立的最近时点"，不强制递增——倒叙、插叙、同日都合法。
            if (earliest != null && target.compareTo(earliest) < 0) {
                issues.add("第" + chapter.getChapterNo() + "章（" + target + "）早于已确立时点（第"
                        + earliestNo + "章 " + earliest + "），属时间线倒退");
            }
            if (earliest == null || target.compareTo(earliest) > 0) {
                earliest = target;
                earliestNo = chapter.getChapterNo();
            }
        }
        return issues.isEmpty() ? null : String.join("；", issues);
    }

    /** 单调性违规的修正指令（与其它规划闸门同款：点名 + 可执行改法） */
    public static String monotonicFeedback(String issue) {
        return "\n\n【上一版计划的时间声明被机械驳回：时间线倒退】\n- " + issue
                + "\n\n修正要求（逐条执行，**不得只换措辞**）："
                + "\n1. 不得写回已确立时点之前——这是唯一硬约束；"
                + "\n2. 倒叙、插叙、同日连续都是**合法**的，不需要为了“递增”而改时间；"
                + "\n3. 若确实需要回忆更早的事，把回忆放进正文叙述（如“三年前那场雨”），"
                + "timeAdvance 仍写本章实际所处的时间点。";
    }

    /**
     * 欠账回灌文案：注入下一章的记忆前缀（【偏差警示】块）。
     *
     * <p>措辞刻意写成**义务**而不是提醒——实测写手对"允许推进"的响应率极低，
     * 对"必须到达"才有动作（与 {@code ChapterMemoryService} 时间锁同款纪律）。
     */
    public static String arrearsNotice(List<Arrears> arrears) {
        if (arrears == null || arrears.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Arrears a : arrears) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append("第").append(a.chapterNo()).append("章排期应到 ").append(a.target())
                    .append("，实际停在 ").append(a.actual()).append("（欠 ").append(a.behindDays()).append(" 天）");
        }
        return sb.toString();
    }

    /**
     * 一条时间欠账。
     *
     * @param chapterNo  章号
     * @param target     计划声明的目标日期
     * @param actual     摘要记录的实到日期
     * @param behindDays 欠账天数（正数）
     * @param declaration 原始 timeAdvance 文本（供日志/复盘）
     */
    public record Arrears(int chapterNo, StoryDate target, StoryDate actual, int behindDays, String declaration) {
    }
}
