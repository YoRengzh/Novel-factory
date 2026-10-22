package cn.novel.yonren.domain.novel.service.armory.plan;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.service.armory.memory.OutlineSegmentParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 年份解析统一口径测试。
 *
 * <p>背景（实测缺陷）：模型写 timePoint 时阿拉伯与中文数字混用——同一本书里第 1 章是
 * "2002年7月16日傍晚"、第 81 章是"二〇〇八年六月十二傍晚"。旧实现只认阿拉伯数字，
 * 于是解析器一路回溯到第 1 章，把"已跳到 2008 年"误判成"仍停留在 2002 年"：
 * 体检侧报"进度滞后 6 段"，规划侧会据此反复注入跳年任务。
 */
class StoryPacingTest {

    private static ChapterSummaryEntity summary(int chapterNo, String timePoint) {
        ChapterSummaryEntity s = new ChapterSummaryEntity();
        s.setChapterNo(chapterNo);
        s.setTimePoint(timePoint);
        return s;
    }

    // ===== parseYear：阿拉伯数字 =====

    @Test
    void parseYear_arabic() {
        assertEquals(2008, StoryPacing.parseYear("2008年秋"));
        assertEquals(2002, StoryPacing.parseYear("2002年7月16日傍晚"));
        assertEquals(2017, StoryPacing.parseYear("2017年上半年"));
    }

    // ===== parseYear：中文数字（本次修复的核心）=====

    @Test
    void parseYear_chineseDigits() {
        assertEquals(2008, StoryPacing.parseYear("二〇〇八年六月十二傍晚"));
        assertEquals(2009, StoryPacing.parseYear("二〇〇九年初夏"));
        assertEquals(2010, StoryPacing.parseYear("二〇一〇年秋"));
    }

    @Test
    void parseYear_chineseVariants() {
        // 三种"零"的写法混用：〇（大字）、零（小写）、○（圆圈）
        assertEquals(2008, StoryPacing.parseYear("二零零八年"));
        assertEquals(2008, StoryPacing.parseYear("二○○八年"));
        // 全角数字
        assertEquals(2010, StoryPacing.parseYear("二０一０年秋"));
    }

    @Test
    void parseYear_financeStyle() {
        // 金融式：两千零八年
        assertEquals(2008, StoryPacing.parseYear("两千零八年夏"));
    }

    // ===== parseYear：边界 =====

    @Test
    void parseYear_blankOrNull() {
        assertNull(StoryPacing.parseYear(null));
        assertNull(StoryPacing.parseYear(""));
        assertNull(StoryPacing.parseYear("   "));
    }

    @Test
    void parseYear_noYearAtAll() {
        assertNull(StoryPacing.parseYear("六月竞赛日上午十点许"));
        assertNull(StoryPacing.parseYear("七月初二夜"));
    }

    @Test
    void parseYear_arabicWinsOverChinese() {
        // 两者同时出现时取阿拉伯数字（更明确、位置无关）
        assertEquals(2009, StoryPacing.parseYear("二〇〇八年末到2009年初"));
    }

    // ===== latestAnchorYear：跨写法回溯 =====

    @Test
    void latestAnchorYear_readsChineseYearAtTail() {
        List<ChapterSummaryEntity> chapters = new ArrayList<>();
        chapters.add(summary(1, "2002年7月16日傍晚"));
        chapters.add(summary(80, "六月初六清晨"));
        // 关键：末章是中文数字年份，必须能读到，而不是回溯到第 1 章的 2002
        chapters.add(summary(81, "二〇〇八年六月十二傍晚"));

        assertEquals(2008, StoryPacing.latestAnchorYear(chapters));
    }

    @Test
    void latestAnchorYear_fallsBackWhenTailHasNoYear() {
        List<ChapterSummaryEntity> chapters = new ArrayList<>();
        chapters.add(summary(1, "2002年7月16日傍晚"));
        chapters.add(summary(81, "当日傍晚六点过后")); // 无年份 → 回溯

        assertEquals(2002, StoryPacing.latestAnchorYear(chapters));
    }

    @Test
    void latestAnchorYear_emptyOrAllBlank() {
        assertNull(StoryPacing.latestAnchorYear(null));
        assertNull(StoryPacing.latestAnchorYear(List.of()));
        List<ChapterSummaryEntity> noYears = new ArrayList<>();
        noYears.add(summary(1, "傍晚"));
        noYears.add(summary(2, null));
        assertNull(StoryPacing.latestAnchorYear(noYears));
    }

    // ===== budgetStartYear：大纲段预算年 =====

    @Test
    void budgetStartYear_readsOutlineSegment() {
        String goal = "【卷一·测试】(1-90章)：1-10章 2002年（4岁） 开局；"
                + "81-90章 2008-2009年（10-11岁） 六年后过渡开场；";

        assertEquals(2002, StoryPacing.budgetStartYear(goal, 5));
        // 两年区间的段取起始年
        assertEquals(2008, StoryPacing.budgetStartYear(goal, 85));
    }

    @Test
    void budgetStartYear_noOutlineOrOutOfRange() {
        assertNull(StoryPacing.budgetStartYear(null, 1));
        assertNull(StoryPacing.budgetStartYear("", 1));
        assertNull(StoryPacing.budgetStartYear("没有结构的大纲文本", 1));
        // 超纲章号返回 null（调用方据此豁免跳接）
        assertNull(StoryPacing.budgetStartYear("【卷一·测试】(1-90章)：1-10章 2002年（4岁） 开局；", 500));
    }

    // ==================== 年龄 ====================

    private static ChapterSummaryEntity withAge(int chapterNo, String timePoint, String subject, String value) {
        ChapterSummaryEntity s = summary(chapterNo, timePoint);
        ChapterSummaryEntity.ConsistencyFact f = new ChapterSummaryEntity.ConsistencyFact();
        f.setType("NUMBER");
        f.setSubject(subject);
        f.setValue(value);
        f.setScope("角色丙");
        s.setConsistencyFacts(new ArrayList<>(List.of(f)));
        return s;
    }

    // ===== parseAge：值解析 =====

    @Test
    void parseAge_arabicAndChinese() {
        assertEquals(9, StoryPacing.parseAge("9岁"));
        assertEquals(14, StoryPacing.parseAge("14"));
        assertEquals(4, StoryPacing.parseAge("四岁"));
        assertEquals(11, StoryPacing.parseAge("十一岁"));
        assertEquals(11, StoryPacing.parseAge("11 岁"));
        // 实录 ch71：value 不带"岁"的纯中文数字
        assertEquals(11, StoryPacing.parseAge("十一"));
    }

    @Test
    void parseAge_rejectsNonAgeValues() {
        assertNull(StoryPacing.parseAge(null));
        assertNull(StoryPacing.parseAge(""));
        assertNull(StoryPacing.parseAge("未提及"));
        // 月龄不是岁数——当成 11 岁会把婴儿读成少年，宁可豁免
        assertNull(StoryPacing.parseAge("十一个月"));
        // 长叙述串（"六年前四岁"）：整体匹配不命中，不从中抠数字
        assertNull(StoryPacing.parseAge("六年前四岁"));
        assertNull(StoryPacing.parseAge("某角色幼时写下成年年份"));
    }

    // ===== isAgeSubject：subject 筛选（本次修复的核心）=====

    @Test
    void isAgeSubject_acceptsStateKeywords() {
        assertTrue(StoryPacing.isAgeSubject("角色丙年龄"));
        assertTrue(StoryPacing.isAgeSubject("主角年龄"));
        assertTrue(StoryPacing.isAgeSubject("某角色月龄"));
        assertTrue(StoryPacing.isAgeSubject("年龄"));
    }

    @Test
    void isAgeSubject_rejectsNarrativeWithSui() {
        // 实测反例：叙述"六年前四岁时涂的记号纸"——含"岁"但不是在陈述当前年龄
        assertFalse(StoryPacing.isAgeSubject("某角色幼时写下成年年份"));
        assertFalse(StoryPacing.isAgeSubject("四岁那年搬家"));
        assertFalse(StoryPacing.isAgeSubject("角色丙四岁"));
        assertFalse(StoryPacing.isAgeSubject(null));
        assertFalse(StoryPacing.isAgeSubject(""));
    }

    // ===== latestAnchorAge：回溯与优先级 =====

    @Test
    void latestAnchorAge_skipsNarrativeFactAndFindsRealAge() {
        List<ChapterSummaryEntity> chapters = new ArrayList<>();
        chapters.add(withAge(80, "六月初六清晨", "角色丙年龄", "四岁"));
        chapters.add(withAge(87, "七月初二夜", "某角色幼时写下成年年份", "四岁")); // 叙述串 → 必须跳过
        chapters.add(withAge(84, "当日傍晚六点过后", "角色丙年龄", "11岁"));

        // 末章是 87 的叙述串，但回溯应拿到 84 的真实年龄
        assertEquals(11, StoryPacing.latestAnchorAge(chapters));
    }

    @Test
    void latestAnchorAge_readsChineseNumeralAge() {
        List<ChapterSummaryEntity> chapters = new ArrayList<>();
        chapters.add(withAge(71, "五月八日礼拜五深夜", "角色丙年龄", "十一"));

        assertEquals(11, StoryPacing.latestAnchorAge(chapters));
    }

    @Test
    void latestAnchorAge_noAgeFactsReturnsNull() {
        assertNull(StoryPacing.latestAnchorAge(null));
        assertNull(StoryPacing.latestAnchorAge(List.of()));
        List<ChapterSummaryEntity> none = new ArrayList<>();
        none.add(summary(1, "2002年7月16日傍晚")); // 无 consistencyFacts
        assertNull(StoryPacing.latestAnchorAge(none));
        // 有事实但全非年龄类 → 仍为 null（不臆造）
        List<ChapterSummaryEntity> other = new ArrayList<>();
        other.add(withAge(5, "傍晚", "木匣位置", "抽屉最里层"));
        assertNull(StoryPacing.latestAnchorAge(other));
    }


    // ==================== 移植自旧系统的闸门用例 ====================

    @Test
    void calendarShiftYears_measuresOutlineAheadOfProse() {
        // 大纲 121-130 预算 2012 年起，正文实际 2010 → 大纲跑在正文前面 2 年
        assertEquals(2, StoryPacing.calendarShiftYears(
                segment(121, 130, "2012-2013年（14-15岁）", 2012, 2013, 14, 15), 2010));
        // 正文追平/超前大纲 → 平移量 ≤0（预算不动或前移）
        assertEquals(0, StoryPacing.calendarShiftYears(
                segment(121, 130, "2012-2013年（14-15岁）", 2012, 2013, 14, 15), 2012));
        // 锚点缺失或段无年份（境界纪年）→ null（不臆造）
        assertNull(StoryPacing.calendarShiftYears(
                segment(121, 130, "炼气期", null, null, null, null), 2010));
        assertNull(StoryPacing.calendarShiftYears(
                segment(121, 130, "2012-2013年（14-15岁）", 2012, 2013, 14, 15), null));
    }

    @Test
    void slideSegment_ageShiftFallsBackToYearShift() {
        // 实际年龄锚取不到时，年龄维度退用年份平移量（一岁≈一年）
        OutlineSegmentParser.OutlineSegment seg =
                segment(121, 130, "2012-2013年（14-15岁）", 2012, 2013, 14, 15);
        StoryPacing.SlidBudget slid = StoryPacing.slideSegment(seg, 2, null);

        assertEquals(12, slid.ageStart());
        assertEquals(13, slid.ageEnd());
    }

    @Test
    void slideSegment_slidesYearAndAgeIndependently() {
        // 实测场景：正文 2010 年/12 岁，大纲段 121-130 预算 2012-2013 年/14-15 岁
        OutlineSegmentParser.OutlineSegment seg =
                segment(121, 130, "2012-2013年（14-15岁）", 2012, 2013, 14, 15);
        StoryPacing.SlidBudget slid = StoryPacing.slideSegment(seg, 2, 2);

        assertEquals(2010, slid.yearStart());
        assertEquals(2011, slid.yearEnd());
        assertEquals(12, slid.ageStart());
        assertEquals(13, slid.ageEnd());
        assertEquals(2, slid.shiftYears());
    }

    @Test
    void slideSegment_withoutShiftKeepsOriginalBudget() {
        OutlineSegmentParser.OutlineSegment seg =
                segment(121, 130, "2012-2013年（14-15岁）", 2012, 2013, 14, 15);
        StoryPacing.SlidBudget slid = StoryPacing.slideSegment(seg, null, null);

        assertEquals(2012, slid.yearStart());
        assertEquals(14, slid.ageStart());
        assertEquals(0, slid.shiftYears());
    }

    /** 造一个只关心时间预算的段（其余字段给默认值） */
    private static OutlineSegmentParser.OutlineSegment segment(int start, int end, String timeLabel,
                                                               Integer yearStart, Integer yearEnd,
                                                               Integer ageStart, Integer ageEnd) {
        return new OutlineSegmentParser.OutlineSegment(start, end, 1, timeLabel,
                yearStart, yearEnd, ageStart, ageEnd, "里程碑");
    }
}
