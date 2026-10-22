package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowSettlementEntity;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * **伏笔账本压力**：把"账已经太满、别再加"这件事变成规划层看得见的信号。
 *
 * <p><b>为什么需要它</b>：规划层此前能看到的伏笔信号只有【伏笔账·待回收】（当前欠着哪些线）
 * 与【伏笔长度反馈】（收起得太快）。**没有任何一条说"账本身已经太满"**——
 * 实测第 146-150 章：新埋 12 条 vs 回收 5 条，净增 +7，在途从 44 涨到 55；
 * 而同一批的批末体检里「在途伏笔滞留中位数 25 章（目标 ≤6）」已是 DEGRADED——
 * 那个警报到不了规划层（它是给"要不要续批"用的），于是规划层在**盲区**里继续铺线。
 *
 * <p><b>为什么用"在途条数"而不只是"滞留时长"</b>：滞留时长答的是"这些线养得久不久"，
 * 在途条数答的是"账有多满"。两者是不同问题：账满到 50+ 条时，即使每一条都不算久，
 * 规划层也已经**没有余力**去安排回收——它每段能落的回收任务有限（常规期每章至多 1 条），
 * 而新埋是无限的。收不抵支就是账越滚越大，读者的"作者挖坑不填"观感正来自这里。
 *
 * <p><b>口径与 /health 同源</b>（2026-10-14 修正）：本类此前自算在途（读 {@code foreshadowingNew}
 * + 双向包含核销），与体检的 {@link ForeshadowSpanPolicy#pending}（读 {@code foreshadowSeeds}
 * 中 {@code resolvable=TRUE} 的条目、按同章归一化匹配核销）**是两个口径**——实测同一份数据
 * 前者 64 条、后者 71 条。分裂的代价是不对称的：**低估欠账 ⇒ 低估压力 ⇒ 少踩刹车**，
 * 而且规划层看到的数字与批末体检对不上，复盘时无法互证。现统一复用
 * {@code ForeshadowSpanPolicy}：压力读数即体检读数。
 *
 * <p><b>与 {@code ForeshadowSpanPolicy} 的分工</b>：那份管**已回收**样本的跨度统计
 * （"收得太快"）；本类管**在途**规模（"欠得太多"）。互补，不重叠。
 */
public final class ForeshadowLedgerPressurePolicy {

    /**
     * 在途条数的高压线：达到即判定"账已满"，规划层进入**收线优先**模式。
     *
     * <p>取 40 的依据（实测标定）：第 141-145 章在途 44 条时是唯一一批"净减少"的
     * （新埋 6 / 回收 8），说明这个水位以下规划层仍有余力回收；而第 146-150 章
     * 涨到 55 条时净增 +7，开始失控。40 落在两者之间，是"该踩刹车"的位置。
     *
     * <p>⚠️ 口径为**严格口径**（{@code resolvable=TRUE} 的种子，与体检同源）。
     * 旧的自算口径读数偏低约一成（64 vs 71），故本阈值在新口径下比旧口径更早触发——
     * 这是有意的：压力信号的代价不对称，**漏报比误报贵得多**。
     */
    public static final int HIGH_PRESSURE_PENDING = 40;

    /**
     * 单段净增上限：高压期每段允许的"新埋 − 回收"上限（0 = 只许持平或减少）。
     *
     * <p>高压期不是"禁止新埋"——完全不埋会让剧情失去前进动力，且有些线是剧情自然带出的。
     * 但必须**收放平衡**：净增为 0 意味着"新开一条就得先收一条"，这才是可持续的节奏。
     */
    public static final int HIGH_PRESSURE_NET_GROWTH_CAP = 0;

    /**
     * 高压期**回收吞吐**上限（每章）：常规期规划 prompt 的上限是"每章至多 1 条回收"，
     * 它与"每章至多 2 条新埋"叠加时净增最多只能压到 +1/章——**两条规则直接打架**，
     * 压力块要求的"净增 ≤0"在规则层面就不可满足。故高压期必须显式放宽回收上限，
     * 否则规划层要么违反新埋上限、要么违反净增上限，两难之下只会忽略整块警告。
     */
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
     * 伏笔登记的识别式样：规划 prompt 教给模型的写法是**行内括号注**——
     * 回收侧 {@code （回收第N章埋设的XX）}、新埋侧 {@code （新埋第N章XX）}
     * （见 {@code ChapterPlanPromptService} 第 5/5.1 条）。
     *
     * <p>另兼容两种历史/防御写法：前缀式 {@code （伏笔埋设）…} / {@code （伏笔回收）…}
     * （{@code ChapterPlanChecks.stripForeshadowTemplate} 认的那套）。
     * 三种写法都认，是因为模型可能按任一形态输出——判据要覆盖实际会出现的形态，
     * 否则闸门会静默空转（实测：只认前缀式时，整段计划统计出 0 埋 0 收，闸门形同不存在）。
     *
     * <p>只认这些结构化标记，不做语义猜测——"哪条关键事件是在埋/收伏笔"由规划层按契约声明，
     * 机械层只数数。
     */
    private static final String[] PLANT_MARKERS = {"（新埋", "(新埋", "（伏笔埋设）", "(伏笔埋设)"};
    private static final String[] RECOVER_MARKERS = {"（回收第", "(回收第", "（伏笔回收）", "(伏笔回收)",
            "（回收", "(回收"};
    /** 行内标记的兜底：标记出现在事件中后段（如「…。（新埋第163章…）」）时按包含判定 */
    private static final String[] PLANT_INLINE = {"（新埋", "(新埋"};
    private static final String[] RECOVER_INLINE = {"（回收第", "(回收第"};

    /**
     * **计划层的伏笔流量校验**（高压期硬约束）：账已满时，本段计划的"新埋条数"不得多于"回收条数"。
     *
     * <p><b>为什么必须有机械执法</b>：压力块（{@link #render}）用自然语言给出了"净增上限 0"，
     * 但自然语言只是**警告**——规划层可以无视。实测同一份计划里，压力块写明了"本段计划必须进入
     * 收线优先模式、净增上限 0 条"，同批计划仍在 goal 里写"埋设第 N 章长线"。
     * 本项目对其它规划约束都配了机械执法（关键事件覆盖、对话承载、场景重演、悬念档位序），
     * 唯独伏笔净增只有软警告——这是同类约束里的一个执法缺口。
     *
     * <p><b>口径</b>：只数关键事件里带结构化标记的条目（回收侧 {@code （回收第N章埋设的…）}、
     * 新埋侧 {@code （新埋第N章…）}，兼容前缀式），与压力块给规划层的口径一致（都是"本段的埋与收"）。
     * 不带标记的条目不计——模型漏标时本项静默放行，宁可漏报不误伤（与其它规划闸门同款保守口径）。
     *
     * <p>低压期返回 null（不做任何要求）：本条只治"账已满还在铺新线"，不是常规期的埋收配比检查。
     *
     * @param chapters 本段计划章节
     * @param summaries 历史摘要（判定是否高压期）
     * @param voided 已弃置条目（与压力读数同口径）
     * @return null = 通过或非高压期；非 null = 人类可读的违规说明（调用方带反馈重规划）
     */
    public static String validatePlanNetGrowth(List<ChapterPlanItemEntity> chapters,
                                               List<ChapterSummaryEntity> summaries,
                                               Collection<String> voided) {
        if (chapters == null || chapters.isEmpty()) {
            return null;
        }
        if (!isHighPressure(summaries, voided)) {
            return null;
        }
        int planted = 0;
        int recovered = 0;
        for (ChapterPlanItemEntity chapter : chapters) {
            if (chapter == null || chapter.getKeyEvents() == null) {
                continue;
            }
            for (String event : chapter.getKeyEvents()) {
                if (StringUtils.isBlank(event)) {
                    continue;
                }
                String text = event.trim();
                // 先判回收：回收侧标记（「回收第N章埋设的…」）本身含"埋设"二字，
                // 若先判新埋会被误计（顺序不能颠倒）
                if (startsWithAny(text, RECOVER_MARKERS) || containsAny(text, RECOVER_INLINE)) {
                    recovered++;
                } else if (startsWithAny(text, PLANT_MARKERS) || containsAny(text, PLANT_INLINE)) {
                    planted++;
                }
            }
        }
        if (planted <= recovered) {
            return null;
        }
        return "本段计划新埋 " + planted + " 条 / 回收 " + recovered + " 条，净增 +" + (planted - recovered)
                + "——伏笔账已满（在途超过健康水位），高压期要求本段回收条数 ≥ 新埋条数。"
                + "请把新埋压到不超过回收数（想新开一条线，就先在本段收掉一条），"
                + "并把本段的关键事件优先用于兑现既有欠账";
    }

    private static boolean startsWithAny(String text, String[] prefixes) {
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String text, String[] markers) {
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 违规信息回注规划层的修正指令（与其它规划闸门的反馈块同款：点名 + 可执行改法） */
    public static String netGrowthFeedback(String issue) {
        return "\n\n【上一版计划的伏笔净增被机械驳回】以下问题必须修正：\n- " + issue
                + "\n\n修正要求（逐条执行，**不得只换措辞**）："
                + "\n1. 本段带「（新埋…）」标记的关键事件数必须 ≤ 带「（回收第N章埋设的…）」标记的条数；"
                + "\n2. 优先把回收排进关键事件：从待回收账里挑滞留最久、剧情能自然接住的几条，"
                + "安排成真正的兑现（谁知道了什么 / 什么被确认或公开），而不是一笔带过；"
                + "\n3. 确实需要保留的新埋，只留剧情自然带出的那些，删掉为了铺垫而铺垫的登记；"
                + "\n4. 重新输出**完整**计划（章数不变）。";
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
