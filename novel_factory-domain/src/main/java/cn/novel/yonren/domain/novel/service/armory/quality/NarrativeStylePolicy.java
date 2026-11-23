package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 叙述中可直接定位的重复表达。词面候选供编辑复核，不能证明一处比喻或判断多余。 */
public final class NarrativeStylePolicy {
    private static final Pattern NEGATIVE_TURN = Pattern.compile(
            "(?<![是要若倘])不是[^，,。！？!?；;\r\n]{1,50}?"
                    + "(?:[，,][^，,。！？!?；;\r\n]{1,18})?"
                    + "(?:[，,：:。]\\h*(?:而是|只是|是)|—+\\h*(?:而是|只是|是)|而是)"
                    + "[^，,。！？!?；;\r\n]{1,50}");
    private static final String NUMBER_AND_CLASSIFIER = "[一二两三几数半]?[大小]?[个条把块团片层粒柄口根道只张场股缕枚座面滩截束]";
    private static final Pattern LIKE = Pattern.compile(
            "(?<![不画雕塑铜佛偶肖头图录摄映成造镜显想影人好])像(?![素貌差]|样[子儿]?|是(?!"
                    + NUMBER_AND_CLASSIFIER + ")|什么都|往常|平时|[他她]自己|[他她]这[样种])"
                    + "|(?<=[影人头])像(?=" + NUMBER_AND_CLASSIFIER + ")"
                    + "|好像(?=" + NUMBER_AND_CLASSIFIER + ")");
    private static final Pattern CLAUSE = Pattern.compile("[^，,；;：:]+");
    private static final Pattern SENTENCE = Pattern.compile("[^。！？!?；;\r\n]+[。！？!?；;]?");
    private static final int SIMILE_WINDOW_CHARS = 160;
    private static final Pattern BREATH_TIME = Pattern.compile(
            "(?<!奄奄)(?<![一二两三四五六七八九十百千万零〇\\d])"
                    + "(?:[一二两三四五六七八九十百千万零〇\\d]+(?:来|余|多|几)?|半|几|数)息"
                    + "(?![尚犹仍]存|未[绝尽断]|尚在|尚未断|一吐|入定|法|观)");
    private static final Pattern SUMMARY = Pattern.compile(
            "把这件事记下了|记在了?心里|这笔账[，,]?[他她]算得清楚"
                    + "|前者[^。！？；;]{1,50}?后者|归根结底|总而言之"
                    + "|[两二三四五几][样件条](?:东西|线索|证物)[，,]一[根条]线");

    private NarrativeStylePolicy() { }

    public record Observations(List<String> negativeTurns, List<String> similes,
                               List<String> breathTimings, List<String> summaryCues, List<String> closestSimiles) {
        public Observations {
            negativeTurns = List.copyOf(negativeTurns);
            similes = List.copyOf(similes);
            breathTimings = List.copyOf(breathTimings);
            summaryCues = List.copyOf(summaryCues);
            closestSimiles = List.copyOf(closestSimiles);
        }

        public List<ChapterIssueEntity> issues(int effectiveCharacters) {
            List<ChapterIssueEntity> result = new ArrayList<>();
            for (Family family : families(effectiveCharacters)) {
                if (family.samples().size() <= family.allowance()) continue;
                result.add(ChapterIssueEntity.builder().dimension("aesthetic").severity("MINOR")
                        .description(family.name() + "候选共 " + family.samples().size() + " 处，需结合原文复核")
                        .evidence(family.samples().get(family.allowance())).suggestion(family.suggestion()).build());
            }
            if (closestSimiles.size() >= 3 && similes.size() <= simileAllowance(effectiveCharacters)) {
                result.add(ChapterIssueEntity.builder().dimension("aesthetic").severity("MINOR")
                        .description("邻近 " + SIMILE_WINDOW_CHARS + " 字内出现 " + closestSimiles.size() + " 处明喻候选")
                        .evidence(closestSimiles.get(1)).suggestion("在原段核对比喻是否各有作用。").build());
            }
            return result;
        }

        private List<Family> families(int effectiveCharacters) {
            // 短样稿至少出现四处才按明喻密度提示，防止单一修辞被放大为每千字超标。
            return List.of(
                    new Family("否定转折", 2, negativeTurns, true, "优先压缩重复纠正同一判断的句子，保留有依据的辨析。"),
                    new Family("明喻", simileAllowance(effectiveCharacters), similes, true, "逐句核对喻体带来的新信息，保留有作用的比喻。"),
                    new Family("按息计时", 2, breathTimings, true, "优先省去多余的等待刻度，保留设定和事件依赖的时限。"),
                    new Family("旁白小结", 0, summaryCues, false, "核对这句是否重复读者已知的结论，保留有新信息的记忆、比较或判断。"));
        }

        private static int simileAllowance(int effectiveCharacters) {
            return (int) Math.max(3L, effectiveCharacters * 3L / 1000);
        }
    }

    private record Family(String name, int allowance, List<String> samples, boolean checkCountOnRevision,
                          String suggestion) { }

    /** 每个匹配保留一处连续原文；同句有多处时保留其次数。对白中的句式不参与密度判断。 */
    public static Observations inspect(String content) {
        List<String> negativeTurns = new ArrayList<>();
        List<String> similes = new ArrayList<>();
        List<Integer> similePositions = new ArrayList<>();
        List<String> breathTimings = new ArrayList<>();
        List<String> summaryCues = new ArrayList<>();
        for (Span narration : narrationSpans(content)) {
            Matcher turns = NEGATIVE_TURN.matcher(narration.text());
            while (turns.find()) negativeTurns.add(turns.group());
            Matcher sentences = SENTENCE.matcher(narration.text());
            while (sentences.find()) {
                String sentence = sentences.group();
                collect(BREATH_TIME, sentence, breathTimings);
                collect(SUMMARY, sentence, summaryCues);
                Matcher clauses = CLAUSE.matcher(sentence);
                while (clauses.find()) {
                    String clause = clauses.group();
                    Matcher likes = LIKE.matcher(clause);
                    int clauseStart = narration.start() + sentences.start() + clauses.start();
                    boolean found = false;
                    while (likes.find()) {
                        found = true;
                        similes.add(sentence);
                        similePositions.add(clauseStart + likes.start());
                    }
                    int ending = clause.indexOf("似的");
                    if (!found && ending >= 0) {
                        similes.add(sentence);
                        similePositions.add(clauseStart + ending);
                    }
                }
            }
        }
        int first = 0, peakStart = 0, peakSize = 0;
        for (int i = 0; i < similePositions.size(); i++) {
            while (similePositions.get(i) - similePositions.get(first) >= SIMILE_WINDOW_CHARS) first++;
            int size = i - first + 1;
            if (size > peakSize) {
                peakStart = first;
                peakSize = size;
            }
        }
        return new Observations(negativeTurns, similes, breathTimings, summaryCues,
                similes.subList(peakStart, peakStart + peakSize));
    }

    private record Span(int start, String text) { }

    private static List<Span> narrationSpans(String content) {
        if (content == null || content.isBlank()) return List.of();
        List<Span> spans = new ArrayList<>();
        Deque<Character> quotes = new ArrayDeque<>();
        int start = 0;
        for (int i = 0; i <= content.length(); i++) {
            char c = i < content.length() ? content.charAt(i) : '\0';
            if (!quotes.isEmpty() && c == quotes.peek()) {
                quotes.pop();
                if (quotes.isEmpty()) start = i + 1;
                continue;
            }
            char closing = switch (c) {
                case '“' -> '”';
                case '「' -> '」';
                case '『' -> '』';
                case '"' -> '"';
                default -> '\0';
            };
            if (quotes.isEmpty() && (closing != '\0' || c == '\0')) {
                if (i > start) spans.add(new Span(start, content.substring(start, i)));
                start = i + 1;
            }
            if (closing != '\0') quotes.push(closing);
        }
        return spans;
    }

    private static void collect(Pattern pattern, String sentence, List<String> matches) {
        Matcher matcher = pattern.matcher(sentence);
        while (matcher.find()) matches.add(sentence);
    }

    /** 仅在改稿新增同类表达且已经密集时返回原句；旧稿已有的用法不阻碍事实修复。 */
    public static String regressionReason(String before, String after) {
        var beforeObservations = inspect(before);
        var afterObservations = inspect(after);
        var cluster = afterObservations.closestSimiles();
        if (cluster.size() >= 3 && cluster.size() > beforeObservations.closestSimiles().size()) {
            return "邻近 " + SIMILE_WINDOW_CHARS + " 字内明喻候选增至 " + cluster.size() + " 处。原句：" + cluster.get(1);
        }
        List<Family> original = beforeObservations.families(ChapterLengthPolicy.effectiveCharacterCount(before));
        List<Family> revised = afterObservations.families(ChapterLengthPolicy.effectiveCharacterCount(after));
        for (int i = 0; i < revised.size(); i++) {
            Family family = revised.get(i);
            if (!family.checkCountOnRevision()) continue;
            List<String> old = original.get(i).samples();
            List<String> current = family.samples();
            int excess = current.size() - Math.max(family.allowance(), 1);
            int previousExcess = old.size() - Math.max(original.get(i).allowance(), 1);
            if (excess <= 0 || excess <= previousExcess || current.size() <= old.size()) continue;
            String sample = firstAddedSample(old, current);
            return family.name() + "候选由 " + old.size() + " 处增至 " + current.size() + " 处；请缩小此次改动。原句：" + sample;
        }
        return null;
    }

    private static String firstAddedSample(List<String> original, List<String> revised) {
        Map<String, Integer> remaining = new HashMap<>();
        original.forEach(s -> remaining.merge(s, 1, Integer::sum));
        for (String sample : revised) {
            if (remaining.getOrDefault(sample, 0) == 0) return sample;
            remaining.merge(sample, -1, Integer::sum);
        }
        throw new IllegalStateException("修订稿缺少已计入的新增候选句");
    }
}
