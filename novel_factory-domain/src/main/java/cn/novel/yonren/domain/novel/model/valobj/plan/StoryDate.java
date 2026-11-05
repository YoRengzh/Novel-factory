package cn.novel.yonren.domain.novel.model.valobj.plan;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 故事日期：可比较的绝对日期值 + 自由文本的解析口径。
 */
public final class StoryDate implements Comparable<StoryDate> {

    private static final Pattern YMD = Pattern.compile("(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]");
    private static final Pattern YM = Pattern.compile("(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月");
    /**
     * 月日写法：N月M日。**必须排除"某日/某月"**——"七月某日"是模糊表述（不定位具体日期），
     * 若被当成 7 月固定日期解析，会拿链上基准年份拼出一个看似合理实则编造的日期
     * 模糊日期宁可返回 null 跳过验收，也不产出假值。
     */
    private static final Pattern MD = Pattern.compile("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]");
    /**
     * 推进量：N 天 / N 周 / N 个月（"推进24天""两周后""三个月后"）。
     * **必须排除"第N天/第三天"**——那是序数指示（"事情发生后的第三天"），
     */
    private static final Pattern ADVANCE = Pattern.compile(
            "(?<!第)(\\d{1,4}|[一二三四五六七八九十]{1,3})\\s*(天|日|周|个?月)(?!后\\s*[上下]旬)");
    /** 相对表述：次日 / 第二天 / 当晚 / 同日 / 当日 / 隔日 / 翌日 */
    private static final Pattern REL_NEXT_DAY = Pattern.compile("(次日|第二天|隔日|翌日|转天)");
    private static final Pattern REL_SAME_DAY = Pattern.compile("(同日|当日|当晚|当天|是日)");
    /** 模糊日期（不可定位）："七月某日""某月中旬""次年春天"——一律不解析，宁 null 不编造 */
    private static final Pattern FUZZY_DATE = Pattern.compile("(某日|某月|某天|次年|翌年|第二年春|开春|春天|夏天|秋天|冬天|初春|盛夏|深秋|隆冬)");

    private final LocalDate date;

    private StoryDate(LocalDate date) {
        this.date = date;
    }

    /**
     * 构造：月/日超界时钳到该月的合法范围（模型偶尔写"13月""2月30日"），
     * 避免解析异常打断整批。按真实历法取当月天数，不做"每月 28/30 天"的粗糙截断——
     * 那会让 7 月 30 日、31 日这类正常日期被静默改写。
     */
    public static StoryDate of(int year, int month, int day) {
        int y = clamp(year, 1, 9999);
        int m = clamp(month, 1, 12);
        int maxDay = LocalDate.of(y, m, 1).lengthOfMonth();
        return new StoryDate(LocalDate.of(y, m, clamp(day, 1, maxDay)));
    }

    public int year() {
        return date.getYear();
    }

    public int month() {
        return date.getMonthValue();
    }

    public int day() {
        return date.getDayOfMonth();
    }

    /** 与另一日期的天数差（本日期 − 另一日期）；负数表示本日期更早 */
    public int daysUntil(StoryDate other) {
        return other == null ? 0 : (int) ChronoUnit.DAYS.between(date, other.date);
    }

    @Override
    public int compareTo(StoryDate other) {
        return other == null ? 1 : date.compareTo(other.date);
    }

    public boolean isAfter(StoryDate other) {
        return other != null && date.isAfter(other.date);
    }

    public StoryDate plusDays(int days) {
        return days == 0 ? this : new StoryDate(date.plusDays(days));
    }

    public StoryDate plusMonths(int months) {
        return months == 0 ? this : new StoryDate(date.plusMonths(months));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StoryDate d && date.equals(d.date);
    }

    @Override
    public int hashCode() {
        return date.hashCode();
    }

    @Override
    public String toString() {
        return date.getYear() + "年" + date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
    }

    /**
     * 解析自由文本里的**绝对日期**：完整年月日优先，其次年月（日按 1 号）、再次月日（年按基准补）。
     * 取不到返回 null——调用方据此走相对推算或判"无法验收"，不臆造。
     *
     * **模糊日期一律不收**："七月某日""某月中旬""次年春天"这类表述不含可定位的数字，
     * 收下等于编造。宁可 null 让该章跳过验收。
     *
     * @param base 补全缺省年份用的基准（月日写法常见于同一年的上下文）；可为 null
     */
    public static StoryDate parseAbsolute(String text, StoryDate base) {
        if (StringUtils.isBlank(text) || FUZZY_DATE.matcher(text).find()) {
            return null;
        }
        Matcher ymd = YMD.matcher(text);
        if (ymd.find()) {
            return of(num(ymd.group(1)), num(ymd.group(2)), num(ymd.group(3)));
        }
        Matcher ym = YM.matcher(text);
        if (ym.find()) {
            return of(num(ym.group(1)), num(ym.group(2)), 1);
        }
        Matcher md = MD.matcher(text);
        if (md.find()) {
            return of(base == null ? 2000 : base.year(), num(md.group(1)), num(md.group(2)));
        }
        return null;
    }

    /**
     * 沿链推算摘要时点的绝对日期。
     *
     * 为什么必须沿链：摘要 {@code timePoint} 有 127/242 章不含年份（"次日""承接上章同日"
     * "搬家当日傍晚"），单独看无法定位。但这些表述的语义恰好是**相对上一章**的增量，
     * 从最近一个已知绝对日期的章节向后滚，就能把整条链还原成绝对日期。
     *
     * 推算规则（从严到宽，命中即停）：
     *
     *   · 自身含绝对日期 → 直接采用（链的锚点）；
     *   · "同日/当日/当晚" → 沿用上一章日期（不推进）；
     *   · "次日/第二天/隔日/翌日" → 上一章 + 1 天；
     *   · "N 天后/N 周后" → 上一章 + 相应天数；
     *   · 以上都不中 → 保持 null（**不臆造推进**）。
     *
     *
     * @return 与入参等长的日期列表，无法定位的位次为 null（保留 null 而不是填假值，
     *         调用方据此跳过该章的验收，避免拿编造的日期去判违规）
     */
    public static List<StoryDate> resolveChain(List<ChapterSummaryEntity> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return List.of();
        }
        List<StoryDate> resolved = new ArrayList<>(summaries.size());
        StoryDate last = null;
        for (ChapterSummaryEntity summary : summaries) {
            StoryDate current = summary == null ? null : parseAbsolute(summary.getTimePoint(), last);
            if (current == null && last != null && summary != null) {
                current = advanceFromRelative(summary.getTimePoint(), last);
            }
            resolved.add(current);
            if (current != null) {
                last = current;
            }
        }
        return resolved;
    }

    /** 相对表述 → 基于上一章日期推算；无法判定返回 null（不猜）。模糊表述（"某日""次年"）同样拒绝 */
    static StoryDate advanceFromRelative(String text, StoryDate base) {
        if (StringUtils.isBlank(text) || base == null || FUZZY_DATE.matcher(text).find()) {
            return null;
        }
        if (REL_SAME_DAY.matcher(text).find()) {
            return base;
        }
        if (REL_NEXT_DAY.matcher(text).find()) {
            return base.plusDays(1);
        }
        Matcher adv = ADVANCE.matcher(text);
        if (adv.find()) {
            int n = parseCount(adv.group(1));
            String unit = adv.group(2);
            if (n <= 0) {
                return null;
            }
            if (unit.endsWith("周")) {
                return base.plusDays(n * 7);
            }
            if (unit.endsWith("月")) {
                return base.plusMonths(n);
            }
            return base.plusDays(n);
        }
        return null;
    }

    private static int num(String s) {
        return Integer.parseInt(s);
    }

    private static int parseCount(String s) {
        if (StringUtils.isBlank(s)) {
            return 0;
        }
        if (s.matches("\\d+")) {
            return Integer.parseInt(s);
        }
        return cnNumber(s);
    }

    /** 中文数字 → int（一~九十九）；无法解析返回 0 */
    private static int cnNumber(String cn) {
        if (StringUtils.isBlank(cn)) {
            return 0;
        }
        String digits = "〇一二三四五六七八九";
        if (cn.length() == 1) {
            int idx = digits.indexOf(cn);
            return idx < 0 ? 0 : idx;
        }
        if (cn.equals("十")) {
            return 10;
        }
        int tenIdx = cn.indexOf('十');
        if (tenIdx >= 0) {
            int tens = tenIdx == 0 ? 1 : digits.indexOf(cn.charAt(0));
            int ones = tenIdx == cn.length() - 1 ? 0 : digits.indexOf(cn.charAt(cn.length() - 1));
            return (tens < 0 ? 0 : tens) * 10 + (ones < 0 ? 0 : ones);
        }
        return 0;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
