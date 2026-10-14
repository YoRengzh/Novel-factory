package cn.novel.yonren.domain.novel.service.armory.memory;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public final class OutlineSegmentParser {

    /** 卷头： 【卷一·巷子烟火与早慧神童】(1-90章)： */
    private static final Pattern VOLUME_PATTERN = Pattern.compile(
            "【卷([一二三四五六七八九十]{1,3})[·・]?([^】]*)】\\s*[(（]\\s*(\\d+)\\s*[-–—~]\\s*(\\d+)\\s*章\\s*[)）]");
    /** 段头： 41-50章 …（也匹配"41-50 章"宽容空格） */
    private static final Pattern SEGMENT_HEAD = Pattern.compile(
            "(\\d+)\\s*[-–—~]\\s*(\\d+)\\s*章");
    /** 年份： 2002年 / 2005-2006年 / 2011-2013年 */
    private static final Pattern YEAR_PATTERN = Pattern.compile(
            "(\\d{4})\\s*(?:[-–—~]\\s*(\\d{4}))?\\s*年");
    /** 年龄： （9岁）/（7-8岁）/（13-15岁，初二至高一） */
    private static final Pattern AGE_PATTERN = Pattern.compile(
            "[（(]\\s*(\\d{1,3})\\s*(?:[-–—~]\\s*(\\d{1,3}))?\\s*岁");

    private static final Map<String, Integer> CN_NUMERAL = Map.ofEntries(
            Map.entry("一", 1), Map.entry("二", 2), Map.entry("三", 3), Map.entry("四", 4),
            Map.entry("五", 5), Map.entry("六", 6), Map.entry("七", 7), Map.entry("八", 8),
            Map.entry("九", 9), Map.entry("十", 10));

    private OutlineSegmentParser() {
    }

    /** 卷骨架：卷号 + 卷名 + 章区间 */
    public record OutlineVolumeSkeleton(int volumeNo, String title, int startChapter, int endChapter) {
    }

    /**
     * 章段：章区间 + 时间标记（全部可缺省）+ 里程碑描述。
     * timeLabel 保留原文（如"2007年（9岁）""2010年秋""2017年上半年"），渲染时逐字引用不转译。
     */
    public record OutlineSegment(int startChapter, int endChapter, int volumeNo, String timeLabel,
                                 Integer yearStart, Integer yearEnd, Integer ageStart, Integer ageEnd,
                                 String milestone) {
    }

    /** 解析产物：卷骨架 + 章段（各自可为空列表——空=大纲无可识别结构，消费端 fail-soft 豁免） */
    public record Outline(List<OutlineVolumeSkeleton> volumes, List<OutlineSegment> segments) {

        public static final Outline EMPTY = new Outline(List.of(), List.of());

        public boolean isEmpty() {
            return volumes.isEmpty() && segments.isEmpty();
        }

        /** 定位章号所在段；超出大纲覆盖（含章号大于最末段）返回 null——调用方不得拿别的段冒充 */
        public OutlineSegment segmentFor(int chapterNo) {
            for (OutlineSegment s : segments) {
                if (chapterNo >= s.startChapter() && chapterNo <= s.endChapter()) {
                    return s;
                }
            }
            return null;
        }
    }

    /** 解析入口：null/空白返回 {@link Outline#EMPTY}；解析不出的片段跳过 */
    public static Outline parse(String chapterGoal) {
        if (StringUtils.isBlank(chapterGoal)) {
            return Outline.EMPTY;
        }
        List<OutlineVolumeSkeleton> volumes = new ArrayList<>();
        List<OutlineSegment> segments = new ArrayList<>();
        record Token(int pos, boolean isVolume, int volumeNo, String volumeTitle,
                     int volStart, int volEnd, int segStart, int segEnd) {
        }
        List<Token> tokens = new ArrayList<>();
        Matcher vm = VOLUME_PATTERN.matcher(chapterGoal);
        // 卷头先摘出，并把卷头区间在掩码副本中置空——卷头的"(1-90章)"不能被段头正则误匹配成幻影段
        List<int[]> volumeSpans = new ArrayList<>();
        while (vm.find()) {
            tokens.add(new Token(vm.start(), true, chineseNumeral(vm.group(1)),
                    vm.group(2).trim(), Integer.parseInt(vm.group(3)), Integer.parseInt(vm.group(4)), 0, 0));
            volumeSpans.add(new int[]{vm.start(), vm.end()});
        }
        char[] masked = chapterGoal.toCharArray();
        for (int[] span : volumeSpans) {
            for (int i = span[0]; i < span[1]; i++) {
                masked[i] = ' ';
            }
        }
        String maskedText = new String(masked);
        Matcher sm = SEGMENT_HEAD.matcher(maskedText);
        while (sm.find()) {
            tokens.add(new Token(sm.start(), false, 0, null, 0, 0,
                    Integer.parseInt(sm.group(1)), Integer.parseInt(sm.group(2))));
        }
        if (tokens.isEmpty()) {
            return Outline.EMPTY;
        }
        tokens.sort(Comparator.comparingInt(Token::pos));

        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            int bodyEnd = i + 1 < tokens.size() ? tokens.get(i + 1).pos() : chapterGoal.length();
            String body = chapterGoal.substring(t.pos(), Math.min(bodyEnd, chapterGoal.length()));
            if (t.isVolume()) {
                volumes.add(new OutlineVolumeSkeleton(t.volumeNo(), t.volumeTitle(), t.volStart(), t.volEnd()));
            } else {
                TimeMark mark = parseTimeMark(body);
                int volNo = volumes.isEmpty() ? 0 : volumes.get(volumes.size() - 1).volumeNo();
                segments.add(new OutlineSegment(t.segStart(), t.segEnd(), volNo,
                        mark.label(), mark.yearStart(), mark.yearEnd(), mark.ageStart(), mark.ageEnd(), mark.milestone()));
            }
        }
        return new Outline(List.copyOf(volumes), List.copyOf(segments));
    }

    /** 时间标记提取结果：label 保留原文供渲染，数值供进度对齐计算（均可空） */
    private record TimeMark(String label, Integer yearStart, Integer yearEnd,
                            Integer ageStart, Integer ageEnd, String milestone) {
    }

    /**
     * 从段正文提取时间标记与里程碑：年份/年龄取**首个**匹配（段首的时间标记才是本段的，
     * 后文的年份多为事件叙述），里程碑 = 剥离时间标记后的剩余文本。
     */
    private static TimeMark parseTimeMark(String body) {
        String work = StringUtils.defaultString(body);
        Integer yearStart = null;
        Integer yearEnd = null;
        String yearLabel = null;
        Matcher ym = YEAR_PATTERN.matcher(work);
        if (ym.find()) {
            yearStart = Integer.parseInt(ym.group(1));
            yearEnd = ym.group(2) != null ? Integer.parseInt(ym.group(2)) : yearStart;
            yearLabel = ym.group(0).trim();
        }
        Integer ageStart = null;
        Integer ageEnd = null;
        String ageLabel = null;
        Matcher am = AGE_PATTERN.matcher(work);
        if (am.find()) {
            ageStart = Integer.parseInt(am.group(1));
            ageEnd = am.group(2) != null ? Integer.parseInt(am.group(2)) : ageStart;
            ageLabel = am.group(0).trim();
        }
        String label = StringUtils.joinWith("，",
                StringUtils.defaultIfBlank(yearLabel, null),
                StringUtils.defaultIfBlank(ageLabel, null));
        String milestone = stripLeadingMarks(work, yearLabel, ageLabel);
        return new TimeMark(label, yearStart, yearEnd, ageStart, ageEnd, milestone);
    }

    /** 剥离段首的时间标记与分隔符，余下文本即里程碑描述 */
    private static String stripLeadingMarks(String body, String yearLabel, String ageLabel) {
        String work = StringUtils.defaultString(body).trim();
        if (StringUtils.isNotBlank(yearLabel) && work.startsWith(yearLabel)) {
            work = work.substring(yearLabel.length());
        }
        if (StringUtils.isNotBlank(ageLabel) && work.contains(ageLabel)) {
            int idx = work.indexOf(ageLabel);
            work = work.substring(0, idx) + work.substring(idx + ageLabel.length());
        }
        return work.replaceAll("^[\\s：:、,，.．]+", "").trim();
    }

    /** 中文数字 → 序号（一~十九；无法解析返回 null） */
    public static Integer chineseNumeral(String cn) {
        if (cn == null || cn.isEmpty()) {
            return null;
        }
        Integer n = CN_NUMERAL.get(cn);
        if (n != null) {
            return n;
        }
        // 简单组合（十一~十九、二十~二十九）
        if (cn.startsWith("十") && cn.length() == 2) {
            Integer ones = CN_NUMERAL.get(cn.substring(1));
            return ones == null ? null : 10 + ones;
        }
        if (cn.length() == 2 && cn.endsWith("十")) {
            Integer tens = CN_NUMERAL.get(cn.substring(0, 1));
            return tens == null ? null : tens * 10;
        }
        return null;
    }
}
