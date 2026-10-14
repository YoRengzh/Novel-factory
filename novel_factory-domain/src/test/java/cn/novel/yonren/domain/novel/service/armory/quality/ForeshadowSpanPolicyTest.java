package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 伏笔寿命度量测试（2026-10-01）。
 *
 * <p>反例直接用实测分布：第 1–15 章回收 13 条伏笔，跨度 1 章 ×8、2 章 ×2、3 ×1、6 ×1、8 ×1，
 * 平均 2.23 章、77% 在 2 章内兑现——"埋了就收"，读者来不及惦记。
 */
class ForeshadowSpanPolicyTest {

    /**
     * 造一章：只填埋设与回收，其余不参与本策略。
     * 埋设条目默认 {@code resolvable=TRUE}（声明了兑现义务）——策略只统计这类条目。
     */
    private static ChapterSummaryEntity chapter(int no, List<String> seeds, List<String> resolved) {
        return chapter(no, seeds, resolved, Boolean.TRUE);
    }

    /** 指定 resolvable 的变体：用于验证"不承担兑现义务"与"未标注"两种条目被排除 */
    private static ChapterSummaryEntity chapter(int no, List<String> seeds, List<String> resolved,
                                                Boolean resolvable) {
        ChapterSummaryEntity summary = new ChapterSummaryEntity();
        summary.setChapterNo(no);
        List<ChapterSummaryEntity.SeedEntry> entries = new ArrayList<>();
        for (String s : seeds) {
            ChapterSummaryEntity.SeedEntry entry = new ChapterSummaryEntity.SeedEntry();
            entry.setContent(s);
            entry.setResolvable(resolvable);
            entries.add(entry);
        }
        summary.setForeshadowSeeds(entries);
        summary.setForeshadowingResolved(resolved == null ? List.of() : resolved);
        return summary;
    }

    /** 回归：实测形态——跨度 1 占绝对多数 */
    @Test
    @DisplayName("spans：还原埋设章与跨度")
    void spans_computesRealShape() {
        List<ChapterSummaryEntity> summaries = List.of(
                chapter(1, List.of("表哥拉父亲投资一个稳赚不赔的项目"), null),
                chapter(2, List.of("陆建军后天将带正式合同登门签约"), null),
                // 跨度 1
                chapter(3, List.of(), List.of("表哥拉父亲投资一个稳赚不赔的项目")),
                // 跨度 2
                chapter(4, List.of(), List.of("陆建军后天将带正式合同登门签约")));

        List<ForeshadowSpanPolicy.Span> spans = ForeshadowSpanPolicy.spans(summaries);

        assertEquals(2, spans.size());
        assertEquals(2, spans.get(0).chapters(), "第1章埋、第3章收 = 跨度 2");
        assertEquals(2, spans.get(1).chapters(), "第2章埋、第4章收 = 跨度 2");
        assertEquals(2.0, ForeshadowSpanPolicy.averageSpan(summaries), 1e-9);
    }

    /** 措辞被改写时靠最长公共子串仍能配对 */
    @Test
    @DisplayName("spans：改写措辞仍可配对")
    void spans_matchesRewrittenContent() {
        List<ChapterSummaryEntity> summaries = List.of(
                chapter(1, List.of("角色辰私攒两百块买书钱已取出，旧书铺购书结果未定"), null),
                chapter(2, List.of(),
                        List.of("角色辰私攒两百块买书钱已取出，但旧书铺的购书结果仍然没有确定")));

        List<ForeshadowSpanPolicy.Span> spans = ForeshadowSpanPolicy.spans(summaries);

        assertEquals(1, spans.size(), "改写措辞应仍能配对");
        assertEquals(1, spans.get(0).chapters());
    }

    /** 配不上的条目丢弃不计——宁可漏报也不制造假数据 */
    @Test
    @DisplayName("spans：无对应埋设条目不计数")
    void spans_dropsUnmatched() {
        List<ChapterSummaryEntity> summaries = List.of(
                chapter(1, List.of("一个完全无关的伏笔描述内容"), null),
                chapter(2, List.of(), List.of("完全不同的另一件事情发生了")));

        assertTrue(ForeshadowSpanPolicy.spans(summaries).isEmpty());
        assertEquals(0.0, ForeshadowSpanPolicy.averageSpan(summaries), 1e-9);
    }

    /** 短命判定与占比：跨度 1 / 2 / 4 → 2 条短命 */
    @Test
    @DisplayName("shortSpanRate：跨度 <3 计为短命")
    void shortSpanRate_countsShortLived() {
        List<ChapterSummaryEntity> summaries = List.of(
                chapter(1, List.of("伏笔甲是一个足够长的描述文本"), null),
                // 跨度 1：第1章埋、第2章收
                chapter(2, List.of("伏笔乙是另一个足够长的描述文本"),
                        List.of("伏笔甲是一个足够长的描述文本")),
                chapter(3, List.of("伏笔丙是第三个足够长的描述文本"), null),
                // 跨度 2：第2章埋、第4章收
                chapter(4, List.of(), List.of("伏笔乙是另一个足够长的描述文本")),
                // 跨度 4：第3章埋、第7章收
                chapter(7, List.of(), List.of("伏笔丙是第三个足够长的描述文本")));

        List<ForeshadowSpanPolicy.Span> spans = ForeshadowSpanPolicy.spans(summaries);
        assertEquals(3, spans.size(), "三条都应配上：" + spans);
        assertEquals(2.0 / 3.0, ForeshadowSpanPolicy.shortSpanRate(summaries), 1e-9);
        assertEquals(2, ForeshadowSpanPolicy.shortest(summaries, 10).size());
    }

    /** 样本不足时不发反馈——欠采样下比例没有统计意义 */
    @Test
    @DisplayName("shouldAdvise：样本不足不发反馈")
    void shouldAdvise_skipsUnderSampled() {
        List<ChapterSummaryEntity> summaries = List.of(
                chapter(1, List.of("伏笔甲是一个足够长的描述文本"), null),
                chapter(2, List.of(), List.of("伏笔甲是一个足够长的描述文本")));

        assertFalse(ForeshadowSpanPolicy.shouldAdvise(summaries),
                "只回收 1 条（<" + ForeshadowSpanPolicy.MIN_SAMPLES_FOR_FEEDBACK + "）时不该发反馈");
    }

    /** 健康态（跨度普遍够长）不发反馈 */
    @Test
    @DisplayName("shouldAdvise：跨度健康不发反馈")
    void shouldAdvise_skipsHealthySpans() {
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        summaries.add(chapter(1, List.of("长线伏笔甲足够长的描述"), null));
        summaries.add(chapter(2, List.of("长线伏笔乙足够长的描述"), null));
        summaries.add(chapter(3, List.of("长线伏笔丙足够长的描述"), null));
        summaries.add(chapter(4, List.of("长线伏笔丁足够长的描述"), null));
        summaries.add(chapter(5, List.of("长线伏笔戊足够长的描述"), null));
        summaries.add(chapter(6, List.of("长线伏笔己足够长的描述"), null));
        // 全部在 6 章后回收 → 跨度 5，远超判定线
        summaries.add(chapter(7, List.of(), List.of(
                "长线伏笔甲足够长的描述", "长线伏笔乙足够长的描述", "长线伏笔丙足够长的描述",
                "长线伏笔丁足够长的描述", "长线伏笔戊足够长的描述", "长线伏笔己足够长的描述")));

        assertFalse(ForeshadowSpanPolicy.shouldAdvise(summaries), "跨度健康时不该唠叨");
    }

    /** 空输入不抛异常 */
    @Test
    @DisplayName("空输入安全")
    void emptySafe() {
        assertTrue(ForeshadowSpanPolicy.spans(null).isEmpty());
        assertTrue(ForeshadowSpanPolicy.spans(List.of()).isEmpty());
        assertFalse(ForeshadowSpanPolicy.shouldAdvise(null));
        assertEquals(0.0, ForeshadowSpanPolicy.averageSpan(null), 1e-9);
    }

    // ==================== resolvable 过滤 ====================

    /**
     * `resolvable=false` 的条目（人物状态/氛围点缀）不计入寿命统计。
     *
     * <p>动机：实测 41 条埋设里大量是"角色戊评价儿子反常""路人甲说小孩子眼睛干净"这类
     * 状态/氛围描写，它们永远"在途"，把分母注水、让指标惩罚正确的修复。
     */
    @Test
    @DisplayName("resolvable=false 的条目不进寿命样本")
    void falseResolvable_excludedFromSpan() {
        List<ChapterSummaryEntity> summaries = List.of(
                // 状态型：不计入
                chapter(1, List.of("父母觉得这个孩子有点反常"), null, Boolean.FALSE),
                // 真伏笔：计入
                chapter(1, List.of("陆周氏承诺改日给孙子打一把旧锁"), null, Boolean.TRUE),
                chapter(3, List.of(), List.of("陆周氏承诺改日给孙子打一把旧锁"), Boolean.TRUE));

        List<ForeshadowSpanPolicy.Span> spans = ForeshadowSpanPolicy.spans(summaries);

        assertEquals(1, spans.size(), "只应统计 resolvable=true 的那条：" + spans);
        assertEquals(2, spans.get(0).chapters());
    }

    /** `resolvable=null`（老数据/模型漏标）同样不进样本，但要有 unlabeledRate 可见 */
    @Test
    @DisplayName("resolvable=null 不计入样本，但未标注占比可观测")
    void nullResolvable_excludedAndObservable() {
        List<ChapterSummaryEntity> summaries = List.of(
                chapter(1, List.of("一条没标注的旧数据伏笔"), null, null),
                chapter(1, List.of("父母觉得孩子反常"), null, Boolean.FALSE),
                chapter(2, List.of(), List.of("一条没标注的旧数据伏笔")));

        assertTrue(ForeshadowSpanPolicy.spans(summaries).isEmpty(),
                "未标注条目不得计入寿命样本（否则旧数据继续污染指标）");
        // 2 条有效埋设，其中 1 条未标注 ⇒ 0.5
        assertEquals(0.5, ForeshadowSpanPolicy.unlabeledRate(summaries), 1e-9,
                "未标注占比必须可观测，否则样本被掏空也看不出来");
    }

    /** 全部标注正常时未标注占比为 0 */
    @Test
    @DisplayName("全部标注时未标注占比为 0")
    void unlabeledRate_zeroWhenAllLabeled() {
        List<ChapterSummaryEntity> summaries = List.of(
                chapter(1, List.of("一条标注明确的伏笔描述"), null, Boolean.TRUE));

        assertEquals(0.0, ForeshadowSpanPolicy.unlabeledRate(summaries), 1e-9);
    }

    // ==================== 在途指标（互补） ====================

    /**
     * 在途伏笔的滞留章数。动机：`spans()` 只算已回收的，
     * **只统计闭环事件的指标会惩罚正确的修复**（养长线反而让指标变差）。
     */
    @Test
    @DisplayName("pending：算出在途伏笔的滞留章数")
    void pending_computesAges() {
        List<ChapterSummaryEntity> summaries = List.of(
                chapter(1, List.of("第1章埋下的长线伏笔描述"), null),
                chapter(3, List.of("第3章埋的短线伏笔描述"), null),
                chapter(4, List.of(), List.of("第3章埋的短线伏笔描述")));

        List<ForeshadowSpanPolicy.Pending> pending = ForeshadowSpanPolicy.pending(summaries, 10);

        assertEquals(1, pending.size(), "只有第1章那条还挂着：" + pending);
        assertEquals(1, pending.get(0).plantChapterNo());
        assertEquals(9, pending.get(0).ageChapters(), "滞留 = 当前末章 10 − 埋设章 1");
    }

    /** 在途滞留中位数（偶数条时取两数均值） */
    @Test
    @DisplayName("pendingAgeMedian：中位数计算")
    void pendingAgeMedian_computes() {
        List<ChapterSummaryEntity> summaries = List.of(
                chapter(1, List.of("甲：第1章埋的长线伏笔描述"), null),
                chapter(3, List.of("乙：第3章埋的长线伏笔描述"), null),
                chapter(5, List.of("丙：第5章埋的长线伏笔描述"), null));

        // 以第 10 章为末章：滞留 9 / 7 / 5 ⇒ 中位数 7
        assertEquals(7.0, ForeshadowSpanPolicy.pendingAgeMedian(summaries, 10), 1e-9);
    }

    /** resolvable=false 的条目同样不进在途统计（否则"在途 31 条"继续注水） */
    @Test
    @DisplayName("pending：不承担兑现义务的条目不进在途数")
    void pending_excludesNonResolvable() {
        List<ChapterSummaryEntity> summaries = List.of(
                chapter(1, List.of("父母觉得这个孩子有点反常"), null, Boolean.FALSE),
                chapter(2, List.of("有人答应改日送来一把旧锁"), null, Boolean.TRUE));

        List<ForeshadowSpanPolicy.Pending> pending = ForeshadowSpanPolicy.pending(summaries, 10);

        assertEquals(1, pending.size(), "只有真伏笔计入在途：" + pending);
        assertEquals(2, pending.get(0).plantChapterNo());
    }

    // ==================== 反馈口径：欠采样时退回宽松 回归） ====================

    /**
     * **回归**：老故事的历史埋设全部未标注（`resolvable=null`），严格口径样本为 0，
     * 于是 `shouldAdvise` 因欠采样返回 false ⇒ **伏笔长度反馈整段不注入、治本通道静默失效**。
     *
     * <p>实测证据：第 16-20 章那批的 `input.json` 里「伏笔长度反馈」出现 **0 次**（上一批是 1 次），
     * 而按宽松口径本该触发（10 条样本 / 80% 短命 / 均 1.90 章）。
     *
     * <p>结论：**指标宁可失明也不许脏，反馈宁可多唠叨也不许瞎。**
     */
    @Test
    @DisplayName("feedbackSpans：严格样本不足时退回宽松口径，反馈不静默失效")
    void feedbackSpans_fallsBackToLenientWhenStrictUnderSampled() {
        // 全部未标注（模拟老数据），且每条第 2 章就收 ⇒ 6 条样本、跨度全为 1、短命率 100%
        String c1 = "第1章埋下的短命伏笔描述内容";
        String c2 = "第2章埋下的短命伏笔描述内容";
        String c3 = "第3章埋下的短命伏笔描述内容";
        String c4 = "第4章埋下的短命伏笔描述内容";
        String c5 = "第5章埋下的短命伏笔描述内容";
        String c6 = "第6章埋下的短命伏笔描述内容";
        List<ChapterSummaryEntity> summaries = List.of(
                chapter(1, List.of(c1), null, null),
                chapter(2, List.of(c2), List.of(c1), null),
                chapter(3, List.of(c3), List.of(c2), null),
                chapter(4, List.of(c4), List.of(c3), null),
                chapter(5, List.of(c5), List.of(c4), null),
                chapter(6, List.of(c6), List.of(c5), null),
                chapter(7, List.of(), List.of(c6), null));

        assertTrue(ForeshadowSpanPolicy.spans(summaries).isEmpty(),
                "严格口径（指标用）应仍为空——老数据不得污染指标");
        assertEquals(6, ForeshadowSpanPolicy.feedbackSpans(summaries).size(),
                "反馈口径应在欠采样时退回宽松，否则治本通道静默失效");
        assertTrue(ForeshadowSpanPolicy.shouldAdvise(summaries),
                "宽松口径下 6 条全短命，反馈必须触发");
        assertFalse(ForeshadowSpanPolicy.shortest(summaries, 10).isEmpty(),
                "触发了反馈就必须举得出例子（同口径）");
    }

    /** 严格样本足够时，反馈口径应切回严格——不能让老数据的噪声长期参与 */
    @Test
    @DisplayName("feedbackSpans：严格样本足够时不退回宽松")
    void feedbackSpans_prefersStrictWhenEnough() {
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            summaries.add(chapter(i, List.of("第" + i + "章埋的长线伏笔描述内容"), null, Boolean.TRUE));
        }
        summaries.add(chapter(7, List.of(),
                List.of("第1章埋的长线伏笔描述内容", "第2章埋的长线伏笔描述内容",
                        "第3章埋的长线伏笔描述内容", "第4章埋的长线伏笔描述内容",
                        "第5章埋的长线伏笔描述内容", "第6章埋的长线伏笔描述内容")));

        assertEquals(ForeshadowSpanPolicy.spans(summaries).size(),
                ForeshadowSpanPolicy.feedbackSpans(summaries).size(),
                "严格样本已达下限，反馈应切回严格口径");
    }

    // ==================== 弃置排除（P2a：与账本同口径） ====================

    /**
     * **回归（口径分裂）**：`stripVoidedForeshadows` 只清理 `foreshadowingNew`，
     * 而本策略读 `foreshadowSeeds`——**后者从未被清理**。
     * 若不显式排除，弃置掉的长线会被当成"永远在途"，把滞留中位数单调推高。
     */
    @Test
    @DisplayName("pending/spans：已弃置条目不参与统计")
    void voidedEntries_areExcluded() {
        List<ChapterSummaryEntity> summaries = List.of(
                chapter(1, List.of("一条后来被判死的长线伏笔描述"), null),
                chapter(2, List.of("另一条仍在养的长线伏笔描述"), null));
        List<String> voided = List.of("一条后来被判死的长线伏笔描述");

        List<ForeshadowSpanPolicy.Pending> pending =
                ForeshadowSpanPolicy.pending(summaries, 10, voided);

        assertEquals(1, pending.size(), "已弃置的条目不得算作在途：" + pending);
        assertEquals("另一条仍在养的长线伏笔描述", pending.get(0).content());
        // 不传 voided 时行为与引入前一致（向后兼容）
        assertEquals(2, ForeshadowSpanPolicy.pending(summaries, 10).size());
    }

    // ==================== 漏收检测（排期落空） ====================

    /** 无排期数据时恒为 0（P2b 之前的预期惰性） */
    @Test
    @DisplayName("missedCount：无排期数据时恒为 0")
    void missedCount_zeroWithoutSchedule() {
        List<ChapterSummaryEntity> summaries = List.of(
                chapter(1, List.of("一条没有排期的在途伏笔描述"), null));

        assertEquals(0L, ForeshadowSpanPolicy.missedCount(summaries, 10));
    }

    /** 已过计划回收章仍未收 → 计入漏收；未到期 → 不计 */
    @Test
    @DisplayName("missedCount：只数已过期的在途线")
    void missedCount_countsOverdueOnly() {
        ChapterSummaryEntity overdue = chapter(1, List.of("逾期未收的排期伏笔描述"), null);
        overdue.getForeshadowSeeds().get(0).setScheduledPayoffChapter(5);
        ChapterSummaryEntity onTrack = chapter(2, List.of("尚未到期的排期伏笔描述"), null);
        onTrack.getForeshadowSeeds().get(0).setScheduledPayoffChapter(20);
        List<ChapterSummaryEntity> summaries = List.of(overdue, onTrack);

        assertEquals(1L, ForeshadowSpanPolicy.missedCount(summaries, 10),
                "第10章时：第1章那条计划第5章收（逾期）、第2章那条计划第20章收（未到期）");
        assertEquals(2L, ForeshadowSpanPolicy.missedCount(summaries, 25),
                "到第25章两条都逾期");
    }

    /** 已回收的排期线不算漏收 */
    @Test
    @DisplayName("missedCount：已回收的不算漏收")
    void missedCount_excludesResolved() {
        ChapterSummaryEntity done = chapter(1, List.of("已按期收掉的排期伏笔描述"), null);
        done.getForeshadowSeeds().get(0).setScheduledPayoffChapter(3);
        List<ChapterSummaryEntity> summaries = List.of(
                done,
                chapter(3, List.of(), List.of("已按期收掉的排期伏笔描述")));

        assertEquals(0L, ForeshadowSpanPolicy.missedCount(summaries, 10));
    }
}
