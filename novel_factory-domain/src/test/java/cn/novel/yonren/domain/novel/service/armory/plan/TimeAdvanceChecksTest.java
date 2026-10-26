package cn.novel.yonren.domain.novel.service.armory.plan;

import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时间排期验收测试：日期解析、相对链式推算、欠账计算、单调性校验。
 *
 * <p>夹具取自**生产实测**：238-242 章的计划合计推进 59 天，而摘要记录的时点只走 16 天，
 * 缺口 73%。此前三道关全部失效（单调性只写在提示词、审计只记 MINOR、写手看到的是"允许推进"），
 * 所以这个缺口从未被发现。
 */
class TimeAdvanceChecksTest {

    private static ChapterSummaryEntity summary(int chapterNo, String timePoint) {
        ChapterSummaryEntity s = new ChapterSummaryEntity();
        s.setChapterNo(chapterNo);
        s.setTimePoint(timePoint);
        return s;
    }

    private static ChapterPlanItemEntity plan(int chapterNo, String timeAdvance) {
        return ChapterPlanItemEntity.builder().chapterNo(chapterNo).timeAdvance(timeAdvance).build();
    }

    // ===== StoryDate：绝对日期解析 =====

    @Test
    void parseAbsolute_fullYmd() {
        StoryDate d = StoryDate.parseAbsolute("推进4天，至2012年7月7日晚上", null);

        assertEquals(2012, d.year());
        assertEquals(7, d.month());
        assertEquals(7, d.day());
    }

    @Test
    void parseAbsolute_yearMonthOnly() {
        StoryDate d = StoryDate.parseAbsolute("2012年8月", null);

        assertEquals(2012, d.year());
        assertEquals(8, d.month());
        assertEquals(1, d.day(), "只有年月时取 1 号");
    }

    @Test
    void parseAbsolute_monthDayUsesBaseYear() {
        StoryDate base = StoryDate.of(2012, 7, 4);

        StoryDate d = StoryDate.parseAbsolute("7月20日清晨", base);

        assertEquals(2012, d.year(), "月日写法用基准年份补全");
        assertEquals(7, d.month());
        assertEquals(20, d.day());
    }

    @Test
    void parseAbsolute_returnsNullWhenNoDate() {
        assertNull(StoryDate.parseAbsolute("当日傍晚六点过后", null));
        assertNull(StoryDate.parseAbsolute("", null));
        assertNull(StoryDate.parseAbsolute(null, null));
    }

    // ===== 模糊日期一律拒绝（评审修正：此前会编造假日期）=====

    /**
     * 生产实测第 4 章："七月某日清晨至上午，第三天阻拦后"——旧实现把"某日"当数字解析，
     * 拿链上基准拼出 2003年2月17日（年份来自污染链、月份与锚点矛盾）。
     * 模糊表述必须整条拒绝：宁可该章跳过验收，不可产出假值去判违规。
     */
    @Test
    void parseAbsolute_rejectsFuzzyDates() {
        StoryDate base = StoryDate.of(2002, 7, 17);

        assertNull(StoryDate.parseAbsolute("七月某日清晨", base), "'某日'不可定位");
        assertNull(StoryDate.parseAbsolute("某月中旬", base));
        assertNull(StoryDate.parseAbsolute("次年春天", base), "'次年'是相对年，本类不解析跨年相对");
        assertNull(StoryDate.parseAbsolute("2002年7月某日", base), "含'某日'整条拒绝，即使带了年份");
    }

    /** "第N天"是序数（事件后的第几天），不是从基准推进的增量——当成 +N 会编造推进 */
    @Test
    void relative_ordinalDayIsNotAdvance() {
        StoryDate base = StoryDate.of(2002, 7, 17);

        assertNull(advance(base, "第三天阻拦后约六点半"), "'第三天'是序数指示，不推进");
        assertNull(advance(base, "调离获批后第3天下午"));
        assertEquals(base, advance(base, "搬家当天上午"), "'当天' = 同日沿用");
    }

    /** 模糊词出现在相对推算里同样拒绝（评审补：相对路径与绝对路径必须同一套模糊黑名单） */
    @Test
    void relative_rejectsFuzzy() {
        StoryDate base = StoryDate.of(2002, 7, 17);
        assertEquals(StoryDate.of(2002, 7, 18), advance(base, "次日清晨"), "正常相对推算不受影响");
        assertNull(advance(base, "某日之后"), "相对路径同样拒绝模糊日期");
    }

    private StoryDate advance(StoryDate base, String text) {
        try {
            java.lang.reflect.Method m = StoryDate.class.getDeclaredMethod(
                    "advanceFromRelative", String.class, StoryDate.class);
            m.setAccessible(true);
            return (StoryDate) m.invoke(null, text, base);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ===== StoryDate：相对表述沿链推算 =====

    /**
     * 生产实测：242 章摘要里 127 章不含年份（"次日""承接上章同日""搬家当日傍晚"）。
     * 单独看无法定位，但它们的语义恰好是"相对上一章的增量"，沿链滚就能还原成绝对日期。
     */
    @Test
    void resolveChain_reconstructsRelativeTimePoints() {
        List<ChapterSummaryEntity> summaries = List.of(
                summary(1, "2012年7月4日下午"),
                summary(2, "次日清晨"),
                summary(3, "当晚"),
                summary(4, "推进6天，至7月13日下午"),
                summary(5, "承接上章同日"));

        List<StoryDate> chain = StoryDate.resolveChain(summaries);

        assertEquals(StoryDate.of(2012, 7, 4), chain.get(0), "绝对日期是链锚点");
        assertEquals(StoryDate.of(2012, 7, 5), chain.get(1), "次日 = +1 天");
        assertEquals(StoryDate.of(2012, 7, 5), chain.get(2), "当晚 = 同日，不推进");
        // "推进6天，至7月13日下午"：既有推进量、又有明确目标日 → 目标日优先（13 日），
        // 而不是拿 7/5 再推 6 天（那会得出 7/11，与声明矛盾）
        assertEquals(StoryDate.of(2012, 7, 13), chain.get(3), "月日写法按目标日定位");
        assertEquals(StoryDate.of(2012, 7, 13), chain.get(4), "承接同日 = 沿用");
    }

    /** 无法定位的位次保留 null，不填假值——调用方据此跳过该章验收，避免拿编造日期判违规 */
    @Test
    void resolveChain_keepsNullWhenNoAnchorYet() {
        List<ChapterSummaryEntity> summaries = List.of(
                summary(1, "晚饭后，次日签合同前夜"),
                summary(2, "2012年7月4日"));

        List<StoryDate> chain = StoryDate.resolveChain(summaries);

        assertNull(chain.get(0), "链首无锚点时无法推算，保持 null");
        assertEquals(StoryDate.of(2012, 7, 4), chain.get(1));
    }

    @Test
    void plusDays_rollsOverMonthsAndYears() {
        assertEquals(StoryDate.of(2012, 8, 1), StoryDate.of(2012, 7, 30).plusDays(2));
        assertEquals(StoryDate.of(2013, 1, 1), StoryDate.of(2012, 12, 30).plusDays(2));
        // 真实历法：7 月有 31 天，不能按"每月 30 天"近似（否则 7/31 会被静默改写）
        assertEquals(StoryDate.of(2012, 8, 1), StoryDate.of(2012, 7, 31).plusDays(1));
    }

    @Test
    void daysUntil_measuresGap() {
        assertEquals(24, StoryDate.of(2012, 7, 20).daysUntil(StoryDate.of(2012, 8, 13)));
        assertEquals(-9, StoryDate.of(2012, 7, 4).daysUntil(StoryDate.of(2012, 7, 13)) * -1);
    }

    // ===== TimeAdvanceChecks：计划目标解析 =====

    @Test
    void planTarget_prefersTargetDate() {
        StoryDate d = TimeAdvanceChecks.planTarget("推进4天，至2012年7月7日晚上", null);

        assertEquals(StoryDate.of(2012, 7, 7), d);
    }

    @Test
    void planTarget_fallsBackToAnyAbsoluteDate() {
        StoryDate d = TimeAdvanceChecks.planTarget("跨月推进，2012年8月13日傍晚", null);

        assertEquals(StoryDate.of(2012, 8, 13), d);
    }

    // ===== TimeAdvanceChecks：欠账计算（生产同构）=====

    /** 生产实测 238-242：计划 59 天、正文 16 天，四章连续欠账共 87 天 */
    @Test
    void findArrears_detectsProductionGap() {
        List<ChapterSummaryEntity> summaries = new ArrayList<>(List.of(
                summary(237, "2012年7月3日晚上九点多"),
                summary(238, "2012年7月4日下午"),
                summary(239, "2012年7月4日下午（承接上章同日）"),
                summary(240, "2012年7月15日下午"),
                summary(241, "2012年7月15日下午（承接上章同日）"),
                summary(242, "2012年7月16日傍晚")));
        List<ChapterPlanItemEntity> plan = List.of(
                plan(238, "推进4天，至2012年7月7日晚上"),
                plan(239, "推进6天，至2012年7月13日下午"),
                plan(240, "推进7天，至2012年7月20日清晨"),
                plan(241, "推进24天，至2012年8月13日傍晚"),
                plan(242, "推进18天，至2012年8月31日上午"));

        List<TimeAdvanceChecks.Arrears> arrears = TimeAdvanceChecks.findArrears(plan, summaries);

        // 238：计划 7/7、实际 7/4 → 欠 3 天，恰在容差边界内（不大于 3 天）→ 不报
        assertEquals(4, arrears.size(), "238 欠 3 天在容差内，其余四章均超容差");
        assertEquals(239, arrears.get(0).chapterNo());
        assertEquals(9, arrears.get(0).behindDays());
        assertEquals(240, arrears.get(1).chapterNo());
        assertEquals(5, arrears.get(1).behindDays());
        assertEquals(241, arrears.get(2).chapterNo());
        assertEquals(29, arrears.get(2).behindDays(), "第241章计划推进24天、实际0天（7/15 → 8/13 共 29 天）");
        assertEquals(242, arrears.get(3).chapterNo());
        assertEquals(46, arrears.get(3).behindDays(), "7/16 → 8/31 共 46 天");
    }

    /** 欠账在容差内不报：正常创作波动（一两天）不该回灌 */
    @Test
    void findArrears_ignoresSmallDrift() {
        List<ChapterSummaryEntity> summaries = List.of(
                summary(1, "2012年7月4日"),
                summary(2, "2012年7月8日"));
        List<ChapterPlanItemEntity> plan = List.of(plan(2, "推进5天，至2012年7月9日"));

        assertTrue(TimeAdvanceChecks.findArrears(plan, summaries).isEmpty(), "欠 1 天属正常波动");
    }

    /** 正文比计划**超前**不算欠账 */
    @Test
    void findArrears_ignoresBeingAhead() {
        List<ChapterSummaryEntity> summaries = List.of(
                summary(1, "2012年7月4日"),
                summary(2, "2012年7月20日"));
        List<ChapterPlanItemEntity> plan = List.of(plan(2, "推进5天，至2012年7月9日"));

        assertTrue(TimeAdvanceChecks.findArrears(plan, summaries).isEmpty());
    }

    /** 任一侧日期无法定位时跳过，不臆造违规 */
    @Test
    void findArrears_skipsWhenUnresolvable() {
        List<ChapterSummaryEntity> summaries = List.of(summary(1, "当日傍晚"));
        List<ChapterPlanItemEntity> plan = List.of(plan(1, "推进5天，至2012年7月9日"));

        assertTrue(TimeAdvanceChecks.findArrears(plan, summaries).isEmpty());
    }

    // ===== TimeAdvanceChecks：单调性机械校验 =====

    /** 这条规则此前只写在提示词里（"全段 timeAdvance 必须单调递增"），没有任何执行通道 */
    @Test
    void validateMonotonic_detectsBackwardJump() {
        List<ChapterPlanItemEntity> plan = List.of(
                plan(1, "推进5天，至2012年7月9日"),
                plan(2, "推进3天，至2012年7月12日"),
                plan(3, "推进2天，至2012年7月8日"));

        String issue = TimeAdvanceChecks.validateMonotonic(plan);

        assertTrue(issue != null && issue.contains("第3章"), issue);
        assertTrue(issue.contains("早于第2章"), issue);
    }

    @Test
    void validateMonotonic_acceptsSameDayAndForward() {
        List<ChapterPlanItemEntity> plan = List.of(
                plan(1, "推进5天，至2012年7月9日"),
                plan(2, "同日夜间"),
                plan(3, "推进3天，至2012年7月12日"));

        assertNull(TimeAdvanceChecks.validateMonotonic(plan), "同日与前进都合法");
    }

    @Test
    void validateMonotonic_skipsUnparseable() {
        List<ChapterPlanItemEntity> plan = List.of(
                plan(1, "推进5天，至2012年7月9日"),
                plan(2, "第二天清晨"),
                plan(3, "推进3天，至2012年7月12日"));

        assertNull(TimeAdvanceChecks.validateMonotonic(plan), "解析不出的章跳过，不误伤");
    }

    // ===== 回灌文案 =====

    @Test
    void arrearsNotice_namesChapterTargetAndGap() {
        List<TimeAdvanceChecks.Arrears> arrears = List.of(
                new TimeAdvanceChecks.Arrears(241, StoryDate.of(2012, 8, 13), StoryDate.of(2012, 7, 15),
                        28, "推进24天，至2012年8月13日傍晚"));

        String notice = TimeAdvanceChecks.arrearsNotice(arrears);

        assertTrue(notice.contains("第241章"), notice);
        assertTrue(notice.contains("2012年8月13日"), notice);
        assertTrue(notice.contains("欠 28 天"), notice);
    }

    @Test
    void arrearsNotice_returnsNullForEmpty() {
        assertNull(TimeAdvanceChecks.arrearsNotice(List.of()));
        assertNull(TimeAdvanceChecks.arrearsNotice(null));
    }
}
