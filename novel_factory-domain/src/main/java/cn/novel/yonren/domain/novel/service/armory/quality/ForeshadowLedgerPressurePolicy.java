package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowSettlementEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;


public final class ForeshadowLedgerPressurePolicy {


    public static final int HIGH_PRESSURE_PENDING = 40;


    public static final int HIGH_PRESSURE_NET_GROWTH_CAP = 0;

    public static final int HIGH_PRESSURE_RECOVERY_CAP_PER_CHAPTER = 2;

    private ForeshadowLedgerPressurePolicy() {
    }

    /**
     * 当前在途伏笔数（= 已埋 − 已回收），口径与体检
     * {@link ForeshadowSpanPolicy#pending} **完全一致**（严格口径：{@code resolvable=TRUE} 的种子；
     * 已回收按同章归一化匹配核销；已弃置出账的排除）。
     *
     * @param voided 已弃置条目的登记原文（{@code ForeshadowSettlementEntity.voidedContents}）——
     *               弃置出账的线不再欠账，不推高压力读数
     */
    public static int pendingCount(List<ChapterSummaryEntity> summaries, Collection<String> voided) {
        if (summaries == null || summaries.isEmpty()) {
            return 0;
        }
        int latest = latestChapterNo(summaries);
        if (latest <= 0) {
            return 0;
        }
        return ForeshadowSpanPolicy.pending(summaries, latest, voided).size();
    }

    /** 是否处于高压期（账已满，应转收线优先） */
    public static boolean isHighPressure(List<ChapterSummaryEntity> summaries, Collection<String> voided) {
        return pendingCount(summaries, voided) >= HIGH_PRESSURE_PENDING;
    }

    /**
     * 渲染**账本压力块**（注入规划 prompt）：把在途规模、本批净增摆到规划那一刻，
     * 并在高压期给出"收放平衡"的硬要求。
     *
     * <p><b>为什么必须给具体数字</b>：只说"伏笔太多了"时模型无法判断"太多"到什么程度、
     * 该减到多少。给出在途条数 + 目标水位 + 净增上限，规划层才能把它当成可执行的约束
     * （与 {@code ForeshadowPriorityService} 给逾期条目带"逾期 N 章"同一纪律）。
     *
     * @param recentLookback 统计"最近净增"的章数窗口（0 或负数时跳过净增统计）
     * @return 低压期返回 null（不占预算；只有账真的满了才提醒）
     */
    public static String render(List<ChapterSummaryEntity> summaries, Collection<String> voided,
                               int recentLookback) {
        if (summaries == null || summaries.isEmpty()) {
            return null;
        }
        int pending = pendingCount(summaries, voided);
        if (pending < HIGH_PRESSURE_PENDING) {
            return null;
        }
        StringBuilder sb = new StringBuilder("\n\n【伏笔账·压力预警】当前待回收伏笔 **")
                .append(pending).append(" 条**，已超过健康水位 ").append(HIGH_PRESSURE_PENDING)
                .append(" 条——账太满时，读者感受到的不是「悬念多」，而是「作者到处挖坑不填」。");
        if (recentLookback > 0) {
            NetGrowth growth = netGrowth(summaries, recentLookback, voided);
            if (growth.totalNew() > 0 || growth.totalResolved() > 0) {
                sb.append("\n最近 ").append(growth.windowSize()).append(" 章：新埋 ")
                        .append(growth.totalNew()).append(" 条 / 回收 ").append(growth.totalResolved())
                        .append(" 条，净增 ").append(growth.net() >= 0 ? "+" : "").append(growth.net()).append(" 条。");
                if (growth.net() > 0) {
                    sb.append("**收不抵支**——这正是账越滚越大的原因。");
                }
            }
        }
        sb.append("\n\n本段计划必须进入**收线优先**模式：")
                .append("\n1. **净增上限 ").append(HIGH_PRESSURE_NET_GROWTH_CAP).append(" 条**：本段回收条数必须 ≥ 新埋条数——")
                .append("想新开一条线，就先在本段收掉一条；做不到就先不收不放。")
                .append("\n2. **回收吞吐放宽到每章 ").append(HIGH_PRESSURE_RECOVERY_CAP_PER_CHAPTER).append(" 条**：")
                .append("常规期「每章至多 1 条回收」的上限在高压期不适用——账满时必须提高回收吞吐，")
                .append("优先把既有欠账的兑现排进关键事件，而不是把回收任务继续摊到以后。")
                .append("\n3. **每章新埋至多 2 条**，且只在剧情自然带出时登记——")
                .append("严禁为了「埋而埋」、严禁把一句氛围描写或人物的状态判断登记成伏笔。")
                .append("\n4. **优先回收**：本段的关键事件应优先安排既有欠账的兑现，而不是继续铺新线。")
                .append("待回收清单里滞留最久的几条，如果剧情能自然接住，就在本段收掉。")
                .append("\n5. 阶段蓝图的 tasks 若是「为后续铺垫」型（开启新东西），")
                .append("本段必须同时安排「关闭旧东西」的关键事件来配平——不许整段只铺不收。");
        return sb.toString();
    }

    /** 最近窗口内的新埋/回收统计 */
    public record NetGrowth(int windowSize, int totalNew, int totalResolved) {
        public int net() {
            return totalNew - totalResolved;
        }
    }

    /**
     * 统计最近 {@code lookback} 章的新埋与回收条数。
     *
     * <p>口径与在途读数同源（严格口径）：新埋 = 窗口内 {@code resolvable=TRUE} 的种子数
     * （已弃置的排除）；回收 = 埋设—回收配对成功且**回收章落在窗口内**的跨度数。
     * 两处若用不同口径，就会出现"净增为 0 却在途上涨"的自相矛盾读数。
     */
    static NetGrowth netGrowth(List<ChapterSummaryEntity> summaries, int lookback, Collection<String> voided) {
        List<ChapterSummaryEntity> ordered = new ArrayList<>();
        for (ChapterSummaryEntity s : summaries) {
            if (s != null && s.getChapterNo() != null) {
                ordered.add(s);
            }
        }
        ordered.sort(Comparator.comparingInt(ChapterSummaryEntity::getChapterNo));
        int from = Math.max(0, ordered.size() - lookback);
        List<ChapterSummaryEntity> window = ordered.subList(from, ordered.size());
        if (window.isEmpty()) {
            return new NetGrowth(0, 0, 0);
        }
        int windowStartNo = window.get(0).getChapterNo();
        int totalNew = 0;
        for (ChapterSummaryEntity s : window) {
            totalNew += ForeshadowSpanPolicy.strictSeedCount(s, voided);
        }
        int totalResolved = (int) ForeshadowSpanPolicy.spans(summaries, voided).stream()
                .filter(span -> span.resolveChapterNo() >= windowStartNo)
                .count();
        return new NetGrowth(window.size(), totalNew, totalResolved);
    }

    /** 从结算台账取弃置清单（便捷入口；调用方通常已有 settlements） */
    public static Collection<String> voidedOf(List<ForeshadowSettlementEntity> settlements) {
        return ForeshadowSettlementEntity.voidedContents(settlements);
    }

    private static int latestChapterNo(List<ChapterSummaryEntity> summaries) {
        int latest = 0;
        for (ChapterSummaryEntity s : summaries) {
            if (s != null && s.getChapterNo() != null && s.getChapterNo() > latest) {
                latest = s.getChapterNo();
            }
        }
        return latest;
    }
}
