package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collection;
import java.util.List;
import java.util.Set;


public final class ForeshadowSpanPolicy {


    public static final int SHORT_SPAN_CHAPTERS = 3;

    /** 触发反馈所需的最小样本量：回收条数太少时比例没有统计意义，不报 */
    public static final int MIN_SAMPLES_FOR_FEEDBACK = 5;

    /** 触发反馈的短命占比线：低于此值说明伏笔结构已经健康，不再唠叨 */
    public static final double SHORT_SPAN_RATE_LINE = 0.5;

    private static final int MATCH_MIN_CHARS = 6;

    private ForeshadowSpanPolicy() {
    }

    public record Span(int plantChapterNo, int resolveChapterNo, String content) {
        public int chapters() {
            return resolveChapterNo - plantChapterNo;
        }

        public boolean shortLived() {
            return chapters() < SHORT_SPAN_CHAPTERS;
        }
    }

    public static List<Span> spans(List<ChapterSummaryEntity> summaries) {
        return spans(summaries, true, List.of());
    }

    public static List<Span> spans(List<ChapterSummaryEntity> summaries, Collection<String> voided) {
        return spans(summaries, true, voided);
    }

    public static List<Span> feedbackSpans(List<ChapterSummaryEntity> summaries) {
        return feedbackSpans(summaries, List.of());
    }

    /** 同上（带弃置排除）：严格样本不足时退回宽松口径，仍排除已弃置条目 */
    public static List<Span> feedbackSpans(List<ChapterSummaryEntity> summaries, Collection<String> voided) {
        List<Span> strict = spans(summaries, true, voided);
        return strict.size() >= MIN_SAMPLES_FOR_FEEDBACK ? strict : spans(summaries, false, voided);
    }

    private static List<Span> spans(List<ChapterSummaryEntity> summaries, boolean strict,
                                    Collection<String> voided) {
        List<Span> result = new ArrayList<>();
        if (summaries == null || summaries.isEmpty()) {
            return result;
        }
        // 汇总埋设条目（回收时只在更早的章里找配对）
        Set<String> voidedSet = normalizedVoided(voided);
        List<Seed> seeds = new ArrayList<>();
        for (ChapterSummaryEntity summary : summaries) {
            if (summary == null || summary.getChapterNo() == null) {
                continue;
            }
            seeds.addAll(seedsOf(summary, strict, voidedSet));
        }
        for (ChapterSummaryEntity summary : summaries) {
            if (summary == null || summary.getChapterNo() == null) {
                continue;
            }
            int resolveNo = summary.getChapterNo();
            List<String> resolved = summary.getForeshadowingResolved();
            if (resolved == null || resolved.isEmpty()) {
                continue;
            }
            for (String raw : resolved) {
                if (StringUtils.isBlank(raw)) {
                    continue;
                }
                int matched = matchSeedIndex(seeds, resolveNo, raw);
                if (matched >= 0) {
                    result.add(new Span(seeds.get(matched).chapterNo(), resolveNo, raw.trim()));
                }
            }
        }
        return result;
    }

    /**
     * 为一条回收登记找最匹配的埋设种子：**ID 全等优先，其次归一化全等，最后最长公共子串**。
     *
     * <p><b>ID 优先是关键路径</b>：账本渲染带 {@code [F7-2]} 前缀、模型回抄 ID 销账
     * （见 {@code ChapterMemoryService.buildForeshadowContextListWithIds}），而回抄的 ID 里
     * **不含任何语义内容**——只做文本全等/子串匹配时，这类回收在体检口径里
     * 配不上任何埋设，该线永远停在"在途"。实测第 143-149 章的 ID 回抄回收**全部漏配**，
     * 在途数被单调虚高。账本侧早已 ID 感知（{@code ChapterMemoryService.isResolved}），
     * 指标侧漏改是纯粹的口径分裂。
     *
     * <p>ID 形态但无对应埋设时**不回退文本**：一条纯 ID 的回收登记不含语义，
     * 拿 {@code [F142-1]} 去做子串匹配只会撞出噪声（老数据无 ID 的线由文本路径覆盖）。
     * 带前缀又带描述的形态（{@code [F7-2] 描述}）在 ID 未命中时用**剩余描述**回退文本匹配。
     *
     * @return 命中的种子下标（无命中返回 -1）；只有**严格早于回收章**的埋设参与匹配
     */
    private static int matchSeedIndex(List<Seed> seeds, int resolveNo, String raw) {
        String id = ForeshadowIds.normalize(raw);
        String textPart = raw;
        if (id == null) {
            String prefix = ForeshadowIds.extractPrefix(raw);
            if (prefix != null) {
                id = prefix;
                textPart = ForeshadowIds.stripPrefix(raw);
            }
        }
        if (id != null) {
            for (int i = 0; i < seeds.size(); i++) {
                Seed seed = seeds.get(i);
                if (seed.chapterNo() < resolveNo && seed.foreshadowId() != null
                        && seed.foreshadowId().equalsIgnoreCase(id)) {
                    return i;
                }
            }
            // 带描述的 ID 形态：剩余文本仍可作为回退（老数据埋设无 ID）
            if (StringUtils.isBlank(textPart) || textPart.equals(raw)) {
                return -1;
            }
        }
        String target = normalize(textPart);
        if (target.isEmpty()) {
            return -1;
        }
        int bestIdx = -1;
        int bestScore = 0;
        for (int i = 0; i < seeds.size(); i++) {
            Seed seed = seeds.get(i);
            if (seed.chapterNo() >= resolveNo) {
                continue;
            }
            String candidate = normalize(seed.content());
            if (candidate.isEmpty()) {
                continue;
            }
            int score = candidate.equals(target) ? Integer.MAX_VALUE
                    : longestCommonSubstring(candidate, target);
            if (score > bestScore) {
                bestScore = score;
                bestIdx = i;
            }
        }
        return bestScore >= MATCH_MIN_CHARS ? bestIdx : -1;
    }

    /**
     * 已被回收登记命中的埋设种子集合（按 record 值相等判定）。
     *
     * <p>与 {@link #spans} 共用 {@link #matchSeedIndex}——两处若各持一套配对逻辑，
     * "寿命跨度算作已回收"与"在途统计算作未回收"会对同一条线给出相反结论。
     */
    private static Set<Seed> closedSeeds(List<ChapterSummaryEntity> summaries, List<Seed> seeds) {
        Set<Seed> closed = new HashSet<>();
        if (seeds.isEmpty()) {
            return closed;
        }
        for (ChapterSummaryEntity summary : safeList(summaries)) {
            if (summary == null || summary.getChapterNo() == null
                    || summary.getForeshadowingResolved() == null) {
                continue;
            }
            int resolveNo = summary.getChapterNo();
            for (String raw : summary.getForeshadowingResolved()) {
                if (StringUtils.isBlank(raw)) {
                    continue;
                }
                int idx = matchSeedIndex(seeds, resolveNo, raw);
                if (idx >= 0) {
                    closed.add(seeds.get(idx));
                }
            }
        }
        return closed;
    }

    /** 平均跨度（章）；无样本返回 0（调用方据此跳过指标，而不是报 0） */
    public static double averageSpan(List<ChapterSummaryEntity> summaries) {
        List<Span> all = spans(summaries);
        if (all.isEmpty()) {
            return 0.0;
        }        return all.stream().mapToInt(Span::chapters).average().orElse(0.0);
    }

    /** 短命伏笔占比（0~1）；无样本返回 0 */
    public static double shortSpanRate(List<ChapterSummaryEntity> summaries) {
        List<Span> all = spans(summaries);
        if (all.isEmpty()) {
            return 0.0;
        }
        long shortCount = all.stream().filter(Span::shortLived).count();
        return (double) shortCount / all.size();
    }

    /**
     * 该不该给规划层发反馈：样本足够且短命占比超线。
     * 返回 false 时不注入 prompt——**欠采样与健康态都不该唠叨**。
     *
     * <p>样本取自 {@link #feedbackSpans}（严格口径不足时退回宽松口径）——
     * 用严格口径会让"老数据全未标注"的故事**永久失明**，治本通道静默失效。
     */
    public static boolean shouldAdvise(List<ChapterSummaryEntity> summaries) {
        List<Span> all = feedbackSpans(summaries);
        if (all.size() < MIN_SAMPLES_FOR_FEEDBACK) {
            return false;
        }
        long shortCount = all.stream().filter(Span::shortLived).count();
        return (double) shortCount / all.size() > SHORT_SPAN_RATE_LINE;
    }

    /**
     * 最典型的短命样本（按跨度升序取前 N 条），供反馈文本举例。
     * 与 {@link #shouldAdvise} 同口径（{@link #feedbackSpans}），否则会出现
     * "触发了反馈却举不出例子"或"举例数与触发数对不上"的矛盾。
     */
    public static List<Span> shortest(List<ChapterSummaryEntity> summaries, int limit) {
        return feedbackSpans(summaries).stream()
                .filter(Span::shortLived)
                .sorted((a, b) -> Integer.compare(a.chapters(), b.chapters()))
                .limit(Math.max(0, limit))
                .toList();
    }

    // ==================== 在途（互补指标） ====================

    /**
     * 一条**尚未兑现**的伏笔：埋设章 + 至今滞留的章数。
     *
     * @param plantChapterNo 埋设章
     * @param ageChapters    滞留章数（当前末章 − 埋设章）
     */
    public record Pending(int plantChapterNo, int ageChapters, String content,
                          Integer scheduledPayoffChapter) {

        /** 兼容构造（无排期信息） */
        public Pending(int plantChapterNo, int ageChapters, String content) {
            this(plantChapterNo, ageChapters, content, null);
        }
    }

    public static List<Pending> pending(List<ChapterSummaryEntity> summaries, int currentChapter) {
        return pending(summaries, currentChapter, List.of());
    }

    /** 同上（带弃置排除）：已弃置的条目不算在途——否则死条目会永久推高滞留中位数 */
    public static List<Pending> pending(List<ChapterSummaryEntity> summaries, int currentChapter,
                                       Collection<String> voided) {
        List<Pending> result = new ArrayList<>();
        if (summaries == null || summaries.isEmpty() || currentChapter <= 0) {
            return result;
        }
        Set<String> voidedSet = normalizedVoided(voided);
        List<Seed> seeds = new ArrayList<>();
        for (ChapterSummaryEntity summary : safeList(summaries)) {
            if (summary != null && summary.getChapterNo() != null) {
                // 在途统计与寿命指标同口径（严格）：只算声明了兑现义务的条目
                seeds.addAll(seedsOf(summary, true, voidedSet));
            }
        }
        // 已回收判定与 spans 共用同一条匹配路径（ID 全等 → 归一化全等 → LCS）：
        // 此前这里自持一套"埋设章 + 归一化全等"的判定，模型回抄 ID 或改写措辞的回收
        // 全被判成"仍在途"——同一份数据里寿命指标认为已收、在途指标认为没收
        Set<Seed> closed = closedSeeds(summaries, seeds);
        for (Seed seed : seeds) {
            if (!closed.contains(seed) && seed.chapterNo() < currentChapter) {
                result.add(new Pending(seed.chapterNo(), currentChapter - seed.chapterNo(), seed.content(),
                        seed.scheduledPayoffChapter()));
            }
        }
        return result;
    }

    /**
     * 单章**严格口径**的埋设条数：声明了兑现义务（{@code resolvable=TRUE}）且未被弃置的种子数。
     *
     * <p>供"流量统计"（伏笔账压力块的最近净增）与"存量读数"（{@link #pending}）共用同一把尺——
     * 两份实现稍有差异就会出现"净增为 0 却在途上涨"的自相矛盾。
     */
    public static int strictSeedCount(ChapterSummaryEntity summary, Collection<String> voided) {
        if (summary == null) {
            return 0;
        }
        return seedsOf(summary, true, normalizedVoided(voided)).size();
    }

    /** 在途伏笔滞留章数的中位数；无样本返回 0 */
    public static double pendingAgeMedian(List<ChapterSummaryEntity> summaries, int currentChapter) {
        return pendingAgeMedian(summaries, currentChapter, List.of());
    }

    /** 同上（带弃置排除） */
    public static double pendingAgeMedian(List<ChapterSummaryEntity> summaries, int currentChapter,
                                          Collection<String> voided) {
        List<Pending> all = pending(summaries, currentChapter, voided);
        if (all.isEmpty()) {
            return 0.0;
        }
        List<Integer> ages = all.stream().map(Pending::ageChapters).sorted().toList();
        int size = ages.size();
        return size % 2 == 1
                ? ages.get(size / 2)
                : (ages.get(size / 2 - 1) + ages.get(size / 2)) / 2.0;
    }

    // ==================== 漏收检测（排期落空） ====================

    public static long missedCount(List<ChapterSummaryEntity> summaries, int currentChapter) {
        return missedCount(summaries, currentChapter, List.of());
    }

    /** 同上（带弃置排除） */
    public static long missedCount(List<ChapterSummaryEntity> summaries, int currentChapter,
                                   Collection<String> voided) {
        if (summaries == null || currentChapter <= 0) {
            return 0L;
        }
        List<Pending> inFlight = pending(summaries, currentChapter, voided);
        return inFlight.stream().filter(p -> p.scheduledPayoffChapter() != null
                && currentChapter > p.scheduledPayoffChapter()).count();
    }

    /** 已弃置内容的归一化集合（供比对照用） */
    private static Set<String> normalizedVoided(Collection<String> voided) {
        if (voided == null || voided.isEmpty()) {
            return Set.of();
        }
        Set<String> set = new HashSet<>();
        for (String v : voided) {
            if (StringUtils.isNotBlank(v)) {
                set.add(normalize(v));
            }
        }
        return set;
    }

    // ==================== 内部 ====================

    private record Seed(int chapterNo, String content, Integer scheduledPayoffChapter, String foreshadowId) {
    }

    private static List<Seed> seedsOf(ChapterSummaryEntity summary, boolean strict, Set<String> voided) {
        List<Seed> list = new ArrayList<>();
        if (summary.getForeshadowSeeds() == null) {
            return list;
        }
        for (ChapterSummaryEntity.SeedEntry entry : summary.getForeshadowSeeds()) {
            if (entry == null || StringUtils.isBlank(entry.getContent())) {
                continue;
            }
            if (strict && !entry.countedForSpan()) {
                continue;
            }
            // 已弃置的条目一律不计（与账本同口径——账本读 foreshadowingNew，已被 strip 掉）
            if (voided.contains(normalize(entry.getContent()))) {
                continue;
            }
            list.add(new Seed(summary.getChapterNo(), entry.getContent().trim(),
                    entry.getScheduledPayoffChapter(), entry.getForeshadowId()));
        }
        return list;
    }

    public static double unlabeledRate(List<ChapterSummaryEntity> summaries) {
        int total = 0;
        int unlabeled = 0;
        for (ChapterSummaryEntity summary : safeList(summaries)) {
            if (summary == null || summary.getForeshadowSeeds() == null) {
                continue;
            }
            for (ChapterSummaryEntity.SeedEntry entry : summary.getForeshadowSeeds()) {
                if (entry == null || StringUtils.isBlank(entry.getContent())) {
                    continue;
                }
                total++;
                if (entry.getResolvable() == null) {
                    unlabeled++;
                }
            }
        }
        return total == 0 ? 0.0 : (double) unlabeled / total;
    }

    private static List<ChapterSummaryEntity> safeList(List<ChapterSummaryEntity> summaries) {
        return summaries == null ? List.of() : summaries;
    }


    public static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[\\s\u3000“”\"「」『』]", "");
    }


    public static int longestCommonSubstring(String a, String b) {
        return PlanAdherencePolicy.longestCommonSubstring(a, b);
    }

    /** 最长公共连续**片段文本**（委托实现）：供需要把重合片段回显给模型的判据使用 */
    public static String longestCommonSubstringText(String a, String b) {
        return PlanAdherencePolicy.longestCommonSubstringText(a, b);
    }
}
