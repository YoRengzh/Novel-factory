package cn.novel.yonren.domain.novel.service.armory.plan;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.service.armory.memory.OutlineSegmentParser;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 故事时间定位：时序锚实际值（最新摘要）与大纲段预算年的统一解析口径。
 * 规划层（时间账重锚与 fail-closed 校验）与观测层（进度达标指标）必须看同一把尺子，
 * 两边各算各的就会出现"体检说滞后、规划说无需跳接"的口径分裂。
 *
 * <p>{@link #parseYear(String)} 是全书唯一的自由文本取年口径：摘要 timePoint 的年份写法
 * 在阿拉伯数字（2008年）与中文数字（二〇〇八年）之间摇摆，两侧实现都必须调它而不是各写正则。
 */
public final class StoryPacing {

    private static final Pattern YEAR = Pattern.compile("(\\d{4})年");

    /**
     * 中文数字年份：二〇〇八年 / 二零零八年 / 二０一０年 / 两千零八年（模型写摘要时的常见变体）。
     * 前两种逐位取数拼成四位；"两千零八年"这类位值写法由 {@link #HK_FINANCE_YEAR} 兜底。
     */
    private static final Pattern CN_YEAR = Pattern.compile("([〇零○ＯO0一二三四五六七八九０-９]{4})年");
    /**
     * 位值式年份兜底：凡出现"X千…年"即按位值解析（"两千零八年"→2008、"一千九百八十七年"→1987）。
     * 不写死"百/十"的出现顺序——"两千零八"里既无"百"也无"十"，硬套模式会解析失败，
     * 而解析失败会退回上一处年份、触发整段误判（比"少认一种写法"危险得多）。
     */
    private static final Pattern HK_FINANCE_YEAR = Pattern.compile(
            "([一二三四五六七八九两])千([〇零○一二三四五六七八九十百]*)年");

    private static final Map<Character, Integer> CN_DIGIT = buildCnDigitMap();

    private static Map<Character, Integer> buildCnDigitMap() {
        Map<Character, Integer> map = new java.util.HashMap<>();
        char[] digits = {'〇', '一', '二', '三', '四', '五', '六', '七', '八', '九'};
        for (int i = 0; i < digits.length; i++) {
            map.put(digits[i], i);
        }
        // 零的常见异体：零（小写）、○（圆圈）、０（全角）、O/o（字母，模型偶发）
        map.put('零', 0);
        map.put('○', 0);
        map.put('０', 0);
        map.put('O', 0);
        map.put('o', 0);
        // 全角数字 ０-９
        for (int i = 0; i <= 9; i++) {
            map.put((char) ('０' + i), i);
        }
        return Map.copyOf(map);
    }

    private StoryPacing() {
    }

    /**
     * 从一段自由文本里解析年份（全书唯一的年份解析口径）：
     * 优先阿拉伯数字（2008年），其次中文数字（二〇〇八年）；都取不到返回 null。
     *
     * <p><b>为什么必须两种都认</b>：模型写 timePoint 时会在两种写法间摇摆（实测同一本书里
     * 第 1 章写"2002年"、第 81 章写"二〇〇八年"）。只认阿拉伯数字时，中文写法会让解析器
     * 一路回溯到上一处阿拉伯年份，把"已跳到 2008 年"误判成"仍停留在 2002 年"——
     * 规划侧据此反复注入跳年任务、体检侧误报滞后，两侧同时失真。
     */
    public static Integer parseYear(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        Matcher arabic = YEAR.matcher(text);
        if (arabic.find()) {
            return Integer.parseInt(arabic.group(1));
        }
        Matcher cn = CN_YEAR.matcher(text);
        if (cn.find()) {
            Integer parsed = digitsToYear(cn.group(1));
            if (parsed != null) {
                return parsed;
            }
        }
        // 金融式大写年份（两千零八年）——极少见，兜底
        Matcher hk = HK_FINANCE_YEAR.matcher(text);
        if (hk.find()) {
            Integer parsed = hkFinanceToYear(hk);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    /** 四位中文数字字符 → 年份（"二〇〇八" → 2008）；任一位不可识别返回 null */
    private static Integer digitsToYear(String fourDigits) {
        int year = 0;
        for (int i = 0; i < fourDigits.length(); i++) {
            Integer d = CN_DIGIT.get(fourDigits.charAt(i));
            if (d == null) {
                return null;
            }
            year = year * 10 + d;
        }
        return year;
    }

    /**
     * 位值式年份："两千零八年"→2008、"一千九百八十七年"→1987。
     * 读法：先取千位，再把尾巴里的"某百/某十/某"逐项累加；"两"按 2 处理。
     */
    private static Integer hkFinanceToYear(Matcher m) {
        char thousandChar = m.group(1).charAt(0);
        Integer thousand = (thousandChar == '两') ? 2 : CN_DIGIT.get(thousandChar);
        if (thousand == null) {
            return null;
        }
        int year = thousand * 1000;
        String tail = m.group(2);
        if (StringUtils.isBlank(tail)) {
            return year;
        }
        Integer pending = null; // 尚未落位的数字（如"九百"里的九）
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (c == '百') {
                year += (pending == null ? 1 : pending) * 100;
                pending = null;
            } else if (c == '十') {
                year += (pending == null ? 1 : pending) * 10;
                pending = null;
            } else {
                Integer d = CN_DIGIT.get(c);
                if (d != null) {
                    // 单位前的数字先挂起；连续数字（"零八"这种）直接落位到更低位
                    if (pending != null) {
                        year += pending;
                    }
                    pending = d;
                }
            }
        }
        if (pending != null) {
            year += pending;
        }
        return year;
    }

    /** 当前时序锚年份：从最新章向前找第一个可解析年份的 timePoint；全书未写或摘要均无时间记录时返回 null */
    public static Integer latestAnchorYear(List<ChapterSummaryEntity> chapters) {
        if (chapters == null) {
            return null;
        }
        for (int i = chapters.size() - 1; i >= 0; i--) {
            ChapterSummaryEntity s = chapters.get(i);
            if (s == null || StringUtils.isBlank(s.getTimePoint())) {
                continue;
            }
            Integer year = parseYear(s.getTimePoint());
            if (year != null) {
                return year;
            }
        }
        return null;
    }

    /** 大纲对 chapterNo 所在段预算的起始年份；大纲缺该段、超纲或段无数值年份（境界纪年等）时返回 null（豁免跳接） */
    public static Integer budgetStartYear(String chapterGoal, int chapterNo) {
        if (StringUtils.isBlank(chapterGoal)) {
            return null;
        }
        OutlineSegmentParser.Outline outline = OutlineSegmentParser.parse(chapterGoal);
        if (outline.isEmpty()) {
            return null;
        }
        OutlineSegmentParser.OutlineSegment budget = outline.segmentFor(chapterNo);
        return budget == null ? null : budget.yearStart();
    }

    // ==================== 年龄 ====================

    /**
     * 年龄类事实的 subject 识别词（与 {@code ConsistencyIndexService.AGE_SUBJECT_KEYWORDS} 同源）。
     *
     * <p><b>刻意不含裸"岁"字</b>：subject 是模型自由写的短串，含"岁"不等于在陈述当前年龄——
     * 实测反例：「主角甲四岁涂2026」（叙述"六年前四岁时涂的格子纸"）也含"岁"，
     * 被误当作当前年龄读成 4 岁，直接让体检报出"年龄滞后 7 岁"的假 CRITICAL。
     * 只有"年龄/月龄/岁数"这类**状态词**才代表当前状态。
     */
    private static final String[] AGE_SUBJECT_KEYWORDS = {"月龄", "年龄", "岁数"};

    /** 年龄值：阿拉伯数字 + 岁（整串匹配，"11 岁"） */
    private static final Pattern ARABIC_AGE = Pattern.compile("(\\d{1,3})\\s*岁");
    /** 年龄值：中文数字 + 岁（整串匹配，"十一岁"） */
    private static final Pattern CN_AGE = Pattern.compile("([一二三四五六七八九十]{1,3})\\s*岁");
    /** 年龄值：纯中文数字（整串匹配，"十一"——摘要 age 事实的 value 有时不带"岁"） */
    private static final Pattern CN_NUMERAL_ONLY = Pattern.compile("[一二三四五六七八九十]{1,3}");

    /** 年龄取数所用的状态词命中判断（供观测层与规划层共用同一口径） */
    public static boolean isAgeSubject(String subject) {
        if (StringUtils.isBlank(subject)) {
            return false;
        }
        for (String keyword : AGE_SUBJECT_KEYWORDS) {
            if (subject.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从年龄值串里解析岁数（全书唯一的年龄取数口径）：
     * 兼容阿拉伯（"9岁" "11 岁" "14"）与中文数字（"四岁" "十一岁" "十一"）；其余返回 null。
     *
     * <p><b>整体匹配而非全文搜索</b>：只有"整串就是年龄"才算数。一旦改成在长串里找数字，
     * "六年前四岁"这类叙述也会被读出 4 岁——这正是体检误报"年龄滞后 7 岁"的成因之一。
     * 月龄写法（"十一个月"）同样返回 null：它是月不是岁，当 11 岁用会把婴儿读成少年；
     * 调用方拿到 null 即豁免该维度，不臆造。
     */
    public static Integer parseAge(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        String trimmed = value.trim();
        // 纯数字（"14"）——最明确的写法
        if (trimmed.matches("\\d{1,3}")) {
            return Integer.parseInt(trimmed);
        }
        // 阿拉伯数字 + 岁（"9岁" "11 岁"）——整串匹配
        Matcher arabic = ARABIC_AGE.matcher(trimmed);
        if (arabic.matches()) {
            return Integer.parseInt(arabic.group(1));
        }
        // 中文数字 + 岁（"四岁" "十一岁"）——整串匹配
        Matcher cn = CN_AGE.matcher(trimmed);
        if (cn.matches()) {
            return OutlineSegmentParser.chineseNumeral(cn.group(1));
        }
        // 纯中文数字（"十一" "四"）——实录：摘要 age 事实的 value 有时不带"岁"
        if (CN_NUMERAL_ONLY.matcher(trimmed).matches()) {
            return OutlineSegmentParser.chineseNumeral(trimmed);
        }
        return null;
    }

    /**
     * 当前主角年龄：从最新章向前找第一条**状态类**年龄事实（subject 含 年龄/月龄/岁数）。
     *
     * <p>回溯语义与 {@link #latestAnchorYear} 一致：越靠近末章越新；找不到返回 null
     * （调用方据此豁免该维度，不臆造）。
     */
    public static Integer latestAnchorAge(List<ChapterSummaryEntity> chapters) {
        if (chapters == null) {
            return null;
        }
        for (int i = chapters.size() - 1; i >= 0; i--) {
            ChapterSummaryEntity s = chapters.get(i);
            if (s == null || s.getConsistencyFacts() == null) {
                continue;
            }
            for (ChapterSummaryEntity.ConsistencyFact f : s.getConsistencyFacts()) {
                if (f == null || f.getType() == null || !"NUMBER".equalsIgnoreCase(f.getType().trim())) {
                    continue;
                }
                if (!isAgeSubject(f.getSubject())) {
                    continue;
                }
                Integer age = parseAge(f.getValue());
                if (age != null) {
                    return age;
                }
            }
        }
        return null;
    }

    // ==================== 大纲重锚（2026-10-11，进度治理方案 A） ====================

    /**
     * 重锚后的段预算：大纲段时间标记按正文实际锚点整体平移后的取值。
     *
     * @param yearStart/yearEnd 平移后的段预算年份（原段无年份时为 null）
     * @param ageStart/ageEnd   平移后的段预算年龄
     * @param shiftYears        平移量（正 = 大纲跑在正文前面、预算向后让；0 = 无滞后）
     */
    public record SlidBudget(Integer yearStart, Integer yearEnd, Integer ageStart, Integer ageEnd, int shiftYears) {
    }

    /**
     * **重锚平移量**：大纲段起始年份到正文实际锚点的距离（大纲跑在正文前面为正）。
     * 同一次蓝图生成/校验内的所有段必须共用**同一个**平移量（从段首所在段计算），
     * 否则多段窗口的段间距会被压乱——调用方不得逐段各算各的。
     * 锚点缺失（全书未写/摘要无时间记录）或段无数值年份（境界纪年）时返回 null（不平移，不臆造）。
     */
    public static Integer calendarShiftYears(OutlineSegmentParser.OutlineSegment seg, Integer anchorYear) {
        if (seg == null || anchorYear == null || seg.yearStart() == null) {
            return null;
        }
        return seg.yearStart() - anchorYear;
    }

    /**
     * 按平移量滑动段预算。年龄维度优先用独立平移量（{@code shiftAges}，由调用方按
     * 实际年龄锚计算；年龄≠年份，生日在年内漂移）；取不到时退用年份平移量（一岁≈一年）。
     * shiftYears 为 null（无锚/无预算）时原样返回，不平移。
     */
    public static SlidBudget slideSegment(OutlineSegmentParser.OutlineSegment seg,
                                          Integer shiftYears, Integer shiftAges) {
        if (seg == null || shiftYears == null) {
            return new SlidBudget(seg == null ? null : seg.yearStart(), seg == null ? null : seg.yearEnd(),
                    seg == null ? null : seg.ageStart(), seg == null ? null : seg.ageEnd(), 0);
        }
        int ageShift = shiftAges != null ? shiftAges : shiftYears;
        return new SlidBudget(
                seg.yearStart() == null ? null : seg.yearStart() - shiftYears,
                seg.yearEnd() == null ? null : seg.yearEnd() - shiftYears,
                seg.ageStart() == null ? null : seg.ageStart() - ageShift,
                seg.ageEnd() == null ? null : seg.ageEnd() - ageShift,
                shiftYears);
    }

    // ==================== 阶段窗口的时间预算（2026-10-26） ====================

    /**
     * 阶段窗口的时间预算 = **正文锚点 + 本窗口章号区间在大纲里自身跨过的时间**。
     *
     * <p><b>为什么不能直接继承"某一段的段末"</b>：大纲里存在**快进段**——
     * {@code 231-240章 2013年秋至2016年夏} 用 10 章覆盖 3 年，{@code 241-250章 2016年秋}
     * 直接从 2016 起。5 章的小阶段只要尾章落进快进段，预算就被抬到数年之后：
     * 实测某阶段（5 章，跨过卷界）被回填成 2015 年，而正文实际只有 2012 年；
     * 该值又成为下一阶段的单调下限，年份链**单向棘轮**，再也回不到正文实际时间。
     *
     * <p><b>口径</b>：只取大纲时间在窗口起止章之间的**增量**，加在正文锚点上。段内按章号线性
     * 插值（单一年份的段视为常数）。于是 5 章的窗口若只从 2015 走到 2016（增量 1 年）→ 2013；
     * 而完整覆盖快进段的窗口（10 章）增量 3 年，快进授权不受影响。
     * 关键性质：**只看窗口自身**，既不提前继承后段段末，也不把上一阶段虚高的声明传给下一阶段。
     *
     * <p><b>预算不设上限</b>：本方法不限制"故事能走多远"——正文真推进了锚点随之前移；
     * 大纲若给本窗口安排了十年跨度，增量就是十年，照常放行。
     *
     * @param anchor       正文实际锚点（年份或年龄）；为 null 时返回 null（调用方退回旧口径）
     * @param ageDimension true=年龄维度，false=年份维度
     */
    public static Integer pacedBudget(OutlineSegmentParser.Outline outline, Integer startChapter,
                                      Integer endChapter, Integer anchor, boolean ageDimension) {
        if (outline == null || anchor == null || startChapter == null || endChapter == null) {
            return null;
        }
        Integer atStart = outlineTimeAt(outline, startChapter, ageDimension);
        Integer atEnd = outlineTimeAt(outline, endChapter, ageDimension);
        if (atStart == null || atEnd == null) {
            return null;
        }
        return anchor + Math.max(0, atEnd - atStart);
    }

    /**
     * 大纲在指定章号处的时间值：段内按章号线性插值，单一年份的段为常数。
     * 章号超出大纲覆盖时取最近的一段（末段/首段），与 {@code segmentFor} 的 fail-soft 口径一致。
     */
    public static Integer outlineTimeAt(OutlineSegmentParser.Outline outline, int chapterNo, boolean ageDimension) {
        if (outline == null || outline.segments().isEmpty()) {
            return null;
        }
        List<OutlineSegmentParser.OutlineSegment> segments = outline.segments();
        OutlineSegmentParser.OutlineSegment seg = outline.segmentFor(chapterNo);
        if (seg == null) {
            int lastEnd = segments.get(segments.size() - 1).endChapter();
            seg = chapterNo > lastEnd ? segments.get(segments.size() - 1) : segments.get(0);
        }
        Integer start = ageDimension ? seg.ageStart() : seg.yearStart();
        Integer end = ageDimension ? seg.ageEnd() : seg.yearEnd();
        if (start == null) {
            return end;
        }
        if (end == null || end.equals(start)) {
            return start;
        }
        int span = Math.max(1, seg.endChapter() - seg.startChapter() + 1);
        if (span == 1) {
            return start;
        }
        int offset = Math.min(Math.max(chapterNo, seg.startChapter()), seg.endChapter()) - seg.startChapter();
        // 分母取 span-1：段首章 = yearStart、**段末章 = yearEnd**。用 span 作分母时末章只到
        // start + (span-1)/span 的进度，段末年永远差一点——快进段（10 章跨 3 年）会少算 0.3 年
        return start + (int) Math.round((double) offset * (end - start) / (span - 1));
    }
}
