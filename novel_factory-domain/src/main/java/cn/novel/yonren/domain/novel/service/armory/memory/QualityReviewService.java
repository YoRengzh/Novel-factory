package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.properties.QualityReviewProperties;
import cn.novel.yonren.domain.novel.service.armory.quality.DialogueRatioPolicy;
import cn.novel.yonren.types.enums.ModelScene;
import cn.novel.yonren.types.utils.JsonParseFallback;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;


@Slf4j
@Service
@RequiredArgsConstructor
public class QualityReviewService {

    /** 逐章评分的容错解析器（与阶段出口核验同一套：先直解，失败再走 JsonRepair 逐级修复） */
    private static final BeanOutputConverter<ReviewOutput> CONVERTER =
            new BeanOutputConverter<>(ReviewOutput.class);

    /** 维度键：顺序即落盘顺序，前端/分析脚本据此画趋势线 */
    public static final List<String> DIMENSIONS = List.of(
            "personaConsistency", "prose", "dialogue", "expectation", "payoff");

    /** 背离判定的主观分下限：低于该分的主观评价本来就不算"给了好评"，不构成背离 */
    private static final int DIVERGENCE_SUBJECTIVE_FLOOR = 8;

    private final LlmGateway llmGateway;
    private final IStoryRepository storyRepository;
    private final QualityReviewProperties properties;


    public void reviewWindow(Path storyDir, StoryVO storyVO, List<ChapterSummaryEntity> summaries, int windowEnd) {
        if (!properties.isEnabled() || storyDir == null || storyVO == null || storyVO.getModule() == null) {
            return;
        }
        if (windowEnd <= 0 || properties.getIntervalChapters() <= 0) {
            return;
        }
        // 触发规则收敛在服务内部（调用方每章无脑调一次）：末章号不是窗口整数倍就静默返回。
        // 放在这里而不是 worker，是为了让"多久评一次"只有一处定义、可被单测直接覆盖。
        if (windowEnd % properties.getIntervalChapters() != 0) {
            return;
        }
        try {
            int windowStart = Math.max(1, windowEnd - properties.getIntervalChapters() + 1);

            List<Integer> existing = storyRepository.readChapterNumbers(storyDir).stream()
                    .filter(no -> no != null && no >= windowStart && no <= windowEnd)
                    .sorted()
                    .toList();
            if (existing.isEmpty()) {
                log.info("质量评分窗口 {}-{} 内无正文，跳过", windowStart, windowEnd);
                return;
            }
            Map<Integer, ChapterSummaryEntity> metricsByChapterNo = new java.util.HashMap<>();
            if (summaries != null) {
                for (ChapterSummaryEntity s : summaries) {
                    if (s != null && s.getChapterNo() != null) {
                        metricsByChapterNo.put(s.getChapterNo(), s);
                    }
                }
            }
            List<Integer> sampled = sampleChapters(existing, properties.getSampleSize(), metricsByChapterNo);

            List<ChapterReview> reviews = new ArrayList<>();
            for (Integer chapterNo : sampled) {
                ChapterReview review = scoreOne(storyVO, storyDir, chapterNo);
                if (review != null) {
                    reviews.add(review);
                }
            }
            if (reviews.isEmpty()) {
                log.warn("质量评分窗口 {}-{} 全部抽样章节评分失败，本轮不落趋势行", windowStart, windowEnd);
                return;
            }

            Map<String, Object> line = buildTrendLine(storyVO, summaries, windowStart, windowEnd, sampled, reviews);
            storyRepository.appendQualityTrend(storyDir, JSON.toJSONString(line));
            warnOnDivergences(line);
            log.info("质量评分完成：第 {}-{} 章，抽样 {} 章，综合 {}/10（rubric {}）",
                    windowStart, windowEnd, reviews.size(), line.get("overall"), properties.getRubricVersion());
        } catch (Exception e) {
            // 度量失败不得反噬生成：吞掉异常只告警
            log.warn("全书质量评分异常（fail-soft，不影响生成），窗口末章 {}", windowEnd, e);
        }
    }

    /** 单章评分：读正文 → 调用强模型 → 容错解析；任一步失败返回 null（由调用方跳过该样本） */
    private ChapterReview scoreOne(StoryVO storyVO, Path storyDir, int chapterNo) {
        try {
            ChapterContentEntity content = storyRepository.readChapter(storyDir, chapterNo);
            if (content == null || StringUtils.isBlank(content.getContent())) {
                log.info("质量评分跳过第 {} 章：正文为空", chapterNo);
                return null;
            }
            String text = truncate(content.getContent(), properties.getMaxChapterChars());
            String raw = llmGateway.complete(storyVO.getModule(), LlmCall.builder()
                    .systemPrompt(systemPrompt())
                    .userPrompt(buildUserPrompt(chapterNo, content.getTitle(), text))
                    .label("quality-review-第" + chapterNo + "章")
                    .scene(ModelScene.QUALITY_REVIEW)
                    .build());
            ChapterReview review = parse(raw, chapterNo);
            if (review == null) {
                log.warn("质量评分第 {} 章输出无法解析，丢弃该样本", chapterNo);
            }
            return review;
        } catch (Exception e) {
            log.warn("质量评分第 {} 章调用失败，丢弃该样本（fail-soft）", chapterNo, e);
            return null;
        }
    }

    /**
     * 在窗口内抽样：首（最老）尾（最新）**必取**，**中间名额给机械最差章**
     * （有效字最低者优先，指标缺失的章不参与竞选）。
     *
     * <p>为什么要这样抽：均匀等距三点是首/中/尾固定落点，会**系统性错过"内部坏章"**。
     * 实测 101-110 窗：两个最差章（108 章 1055 字、109 章对白 5 句）恰落在采样点之间，
     * 主观 7.7 与机械面背离，且因样本不差 {@code divergences} 恒空——两把尺子自检形同虚设。
     * 首章保留是因为"前段变差"是最典型的衰退形态，不能不看；尾章是最新产出必须看。
     *
     * <p>章节清单以磁盘文件为准（{@code chapterNos}），机械指标只用于"最差"排序；
     * 指标全缺失（老数据窗）时回退等距填充，不因缺指标拒绝抽样。
     */
    static List<Integer> sampleChapters(List<Integer> chapterNos, int sampleSize,
                                        Map<Integer, ChapterSummaryEntity> metricsByChapterNo) {
        if (chapterNos == null || chapterNos.isEmpty()) {
            return List.of();
        }
        int k = Math.min(Math.max(sampleSize, 1), chapterNos.size());
        if (k >= chapterNos.size()) {
            return List.copyOf(chapterNos);
        }
        if (k == 1) {
            return List.of(chapterNos.get(0));
        }
        LinkedHashSet<Integer> picked = new LinkedHashSet<>();
        picked.add(chapterNos.get(0));
        picked.add(chapterNos.get(chapterNos.size() - 1));
        List<Integer> middleByWorst = chapterNos.subList(1, chapterNos.size() - 1).stream()
                .filter(no -> metricsByChapterNo.containsKey(no)
                        && metricsByChapterNo.get(no).getValidChars() != null)
                .sorted(Comparator.comparingInt(no -> metricsByChapterNo.get(no).getValidChars()))
                .toList();
        for (Integer no : middleByWorst) {
            if (picked.size() >= k) {
                break;
            }
            picked.add(no);
        }
        // 机械指标缺失的回退：剩余名额在未抽中的章里等距取（老数据窗，与旧均匀口径兼容）
        int need = k - picked.size();
        if (need > 0) {
            List<Integer> unpicked = chapterNos.stream().filter(no -> !picked.contains(no)).toList();
            for (int i = 0; i < need && !unpicked.isEmpty(); i++) {
                int index = Math.round((i + 1) * (unpicked.size() - 1) / (float) (need + 1));
                picked.add(unpicked.get(Math.min(index, unpicked.size() - 1)));
            }
        }
        return picked.stream().sorted().toList();
    }

    /**
     * 背离留痕：主观分与机械指标对同一章给出相反结论时告警。
     *
     * <p>不改变任何分数、不触发任何动作——它只说清"这一条读数该信谁"。
     * 反复出现同维度的背离，说明该维度的主观尺子在这本书/这个题材上没有分辨力。
     */
    @SuppressWarnings("unchecked")
    private void warnOnDivergences(Map<String, Object> line) {
        List<Map<String, Object>> divergences = (List<Map<String, Object>>) line.get("divergences");
        if (divergences == null || divergences.isEmpty()) {
            return;
        }
        for (Map<String, Object> row : divergences) {
            log.warn("质量评分与机械指标背离：第 {} 章 {} 主观 {}/10 而机械 {}/千字（劣化线 {}）"
                            + "——该维度以机械判据为准，主观分不可信",
                    row.get("chapterNo"), row.get("dimension"), row.get("subjective"),
                    row.get("mechanicalDensity"), row.get("degradedLine"));
        }
    }

    /**
     * 最新一窗的读数（从 {@code quality-trend.jsonl} 的行解析，**末行为最新**）。
     *
     * <p>静态方法 + 只吃字符串列表：读文件与解析分家，体检层可以直接消费而不必注入本服务。
     * 解析失败或无行时返回 null——调用方据此**跳过该指标**，而不是造一个 0 分出来
     * （0 分会被读成"质量极差"，那是最糟的误报）。
     */
    public static QualitySnapshot latestSnapshot(List<String> trendLines) {
        if (trendLines == null || trendLines.isEmpty()) {
            return null;
        }
        String last = trendLines.get(trendLines.size() - 1);
        try {
            JSONObject json = JSON.parseObject(last);
            if (json == null) {
                return null;
            }
            Map<String, Double> avg = new LinkedHashMap<>();
            JSONObject avgObject = json.getJSONObject("avg");
            if (avgObject != null) {
                for (String dimension : DIMENSIONS) {
                    avg.put(dimension, avgObject.getDouble(dimension));
                }
            }
            List<String> divergences = new ArrayList<>();
            JSONArray rows = json.getJSONArray("divergences");
            if (rows != null) {
                for (int i = 0; i < rows.size(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    if (row == null) {
                        continue;
                    }
                    divergences.add("第" + row.get("chapterNo") + "章" + row.get("dimension")
                            + "（主观" + row.get("subjective") + " vs 机械 " + row.get("mechanicalDensity") + " 次/千字）");
                }
            }
            return new QualitySnapshot(json.getIntValue("windowStart"), json.getIntValue("windowEnd"),
                    json.getDouble("overall"), avg, divergences);
        } catch (Exception e) {
            log.warn("质量趋势行无法解析，本轮跳过该指标：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 最新一窗的读数：{@code overall} 为综合分，{@code avg} 为各维度均值，
     * {@code divergences} 为"主观分与机械指标背离"的可读描述（每项一章）。
     */
    public record QualitySnapshot(int windowStart, int windowEnd, Double overall,
                                  Map<String, Double> avg, List<String> divergences) {
    }

    /** 单章评分：读正文 → 调用强模型 → 容错解析；任一步失败返回 null（由调用方跳过该样本） */
    private Map<String, Object> buildTrendLine(StoryVO storyVO, List<ChapterSummaryEntity> summaries,
                                               int windowStart, int windowEnd,
                                               List<Integer> sampled, List<ChapterReview> reviews) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("ts", Instant.now().toString());
        line.put("rubricVersion", properties.getRubricVersion());
        line.put("windowStart", windowStart);
        line.put("windowEnd", windowEnd);
        line.put("sampledChapters", sampled);
        line.put("scene", ModelScene.QUALITY_REVIEW.getConfigKey());
        // 场景"配置"的模型名（运行时覆盖/降级后的真实模型以 llm-usage.jsonl 里 label=quality-review-* 为准）
        line.put("configuredModel", configuredModel(storyVO));
        line.put("samples", reviews);
        line.put("avg", averageScores(reviews));
        line.put("overall", overallScore(reviews));
        line.put("mechanical", mechanicalMetrics(summaries, windowStart, windowEnd));
        line.put("divergences", detectDivergences(reviews, summaries));
        return line;
    }

    /**
     * 主观分与机械指标**背离**的样本：同一章、同一维度，两把尺子给出相反结论。
     *
     * <p>目前只做对白维度——它是五个维度里唯一有干净机械对应物（轮次密度）的一个。
     * 其余四维（人设/文风/期待/爽点）没有机械对照，背离无从机械判定，这是本自检的已知边界。
     */
    private List<Map<String, Object>> detectDivergences(List<ChapterReview> reviews,
                                                        List<ChapterSummaryEntity> summaries) {
        List<Map<String, Object>> divergences = new ArrayList<>();
        if (reviews == null || reviews.isEmpty() || summaries == null || summaries.isEmpty()) {
            return divergences;
        }
        String genre = summaries.stream()
                .map(ChapterSummaryEntity::getStoryGenre)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        double degradedLine = DialogueRatioPolicy.densityDegraded(genre);
        for (ChapterReview review : reviews) {
            Integer dialogueScore = review.score("dialogue");
            if (dialogueScore == null || dialogueScore < DIVERGENCE_SUBJECTIVE_FLOOR) {
                continue;
            }
            ChapterSummaryEntity summary = summaries.stream()
                    .filter(s -> s != null && s.getChapterNo() != null
                            && s.getChapterNo().equals(review.getChapterNo()))
                    .findFirst().orElse(null);
            if (summary == null || summary.getDialogueUtterances() == null
                    || summary.getValidChars() == null || summary.getValidChars() <= 0) {
                continue;
            }
            double density = DialogueRatioPolicy.utteranceDensity(
                    summary.getDialogueUtterances(), summary.getValidChars());
            if (density >= degradedLine) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chapterNo", review.getChapterNo());
            row.put("dimension", "dialogue");
            row.put("subjective", dialogueScore);
            row.put("mechanicalDensity", round1(density));
            row.put("degradedLine", degradedLine);
            divergences.add(row);
        }
        return divergences;
    }

    private String configuredModel(StoryVO storyVO) {
        try {
            StoryVO.Module.ChatModel scene = storyVO.getModule().getSceneModels() == null
                    ? null : storyVO.getModule().getSceneModels().get(ModelScene.QUALITY_REVIEW.getConfigKey());
            return scene != null ? scene.getModel() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Double> averageScores(List<ChapterReview> reviews) {
        Map<String, Double> avg = new LinkedHashMap<>();
        for (String dim : DIMENSIONS) {
            OptionalDouble mean = reviews.stream()
                    .map(r -> r.score(dim))
                    .filter(java.util.Objects::nonNull)
                    .mapToDouble(Integer::doubleValue)
                    .average();
            avg.put(dim, mean.isPresent() ? round1(mean.getAsDouble()) : null);
        }
        return avg;
    }

    /** 综合分 = 各维度均值的平均（维度等权，避免人为加权后再也说不清"综合分变了是谁变了"） */
    private Double overallScore(List<ChapterReview> reviews) {
        OptionalDouble mean = averageScores(reviews).values().stream()
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average();
        return mean.isPresent() ? round1(mean.getAsDouble()) : null;
    }

    /** 窗口机械指标：来自 summaries（零额外成本），与 LLM 主观分互为交叉验证 */
    private Map<String, Object> mechanicalMetrics(List<ChapterSummaryEntity> summaries,
                                                  int windowStart, int windowEnd) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        List<ChapterSummaryEntity> inWindow = summaries == null ? List.of() : summaries.stream()
                .filter(s -> s != null && s.getChapterNo() != null
                        && s.getChapterNo() >= windowStart && s.getChapterNo() <= windowEnd)
                .toList();
        metrics.put("chapterCount", inWindow.size());
        metrics.put("avgValidChars", meanRounded(inWindow.stream()
                .map(ChapterSummaryEntity::getValidChars).filter(java.util.Objects::nonNull)
                .mapToDouble(Integer::doubleValue).average()));
        metrics.put("minValidChars", inWindow.stream()
                .map(ChapterSummaryEntity::getValidChars).filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue).min().orElse(0));
        metrics.put("chaptersBelow1500", inWindow.stream()
                .map(ChapterSummaryEntity::getValidChars).filter(java.util.Objects::nonNull)
                .filter(v -> v < 1500).count());
        metrics.put("avgDialogueRatio", meanRounded(inWindow.stream()
                .map(ChapterSummaryEntity::getDialogueRatio).filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue).average()));
        metrics.put("minDialogueRatio", round1(inWindow.stream()
                .map(ChapterSummaryEntity::getDialogueRatio).filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue).min().orElse(Double.NaN)));
        return metrics;
    }

    private static Double round1(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return null;
        }
        return Math.round(value * 10.0) / 10.0;
    }

    /** 均值 → 保留一位小数的 Double；空集合返回 null（写成 {@code round1(mean(...))} 会在空集上拆箱 NPE） */
    private static Double meanRounded(OptionalDouble value) {
        return value.isPresent() ? round1(value.getAsDouble()) : null;
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n…（正文超出评分长度上限，已截断）";
    }

    private ChapterReview parse(String raw, int chapterNo) {
        ReviewOutput output;
        try {
            output = JsonParseFallback.parse(raw, CONVERTER::convert);
        } catch (Exception e) {
            return null;
        }
        if (output == null || output.getReviews() == null || output.getReviews().isEmpty()) {
            return null;
        }
        for (ChapterReview review : output.getReviews()) {
            // 模型偶尔漏填章号：单章调用时可直接回填，避免整条样本作废
            if (review.getChapterNo() == null) {
                review.setChapterNo(chapterNo);
            }
        }
        return output.getReviews().get(0);
    }

    private String systemPrompt() {
        return """
                你是资深长篇小说编辑，负责对已完成章节做**标准化的质量打分**，用于追踪全书质量趋势。
                要求：
                1. 只输出 JSON，不要任何解释性文字、不要 Markdown 代码块；
                2. 只依据正文本身判断，不得因为"这是草稿/后续会修"而放水；
                3. 打分尺度固定（这是一把要跨时间使用的尺子，松紧必须稳定）：
                   1-3 = 明显不合格；4-5 = 勉强合格但有硬伤；6 = 合格；7 = 良好；
                   8 = 优秀（可发表水平）；9-10 = 罕见（留白，非必要不给）。
                4. problems 写真问题并指明位置线索，不要写"可以更精彩"这类无法执行的空话；
                   没有问题时给空数组，不要为凑数编问题。
                """;
    }

    private String buildUserPrompt(int chapterNo, String title, String text) {
        // 注意：本段用 .formatted 注入章号，JSON 里的引号一律写成 \" ——
        // 直接写成 ASCII 引号会在 `:"` 处凑出三个引号，把 Java 文本块提前终止（编译期语法错误）
        return ("""
                【评分量表版本】%s

                【评分维度】各 1-10 分：
                - personaConsistency 人设一致：人物言行是否符合已建立的身份、年龄、认知边界与利益立场；
                  有无为推进剧情而让人物做出不符合其性格或处境的举动；
                - prose 文风：叙述是否具体可感、有无套话与模板腔、有无时代错位或术语越界、
                  有无报告腔/总结腔；句式是否有变化；
                - dialogue 对话质量：对白是否承担推进情节/揭示人物/制造冲突的功能；
                  有无无意义寒暄凑数；不同人物说话是否有可辨识的差异；
                - expectation 期待感：章末是否把读者拽向下一章；张力曲线是否成立；有无提前泄底或空转；
                - payoff 爽点：本章承诺是否兑现；兑现是否来自主角自己的行动与代价
                  （而非他人施舍或巧合）；是否言之有物而非空喊。

                【待评章节】
                第%d章 %s

                %s

                【输出格式】严格按以下 JSON 输出（scores 为 1-10 整数）：
                {\"reviews\":[{\"chapterNo\":%d,\"personaConsistency\":0,\"prose\":0,\"dialogue\":0,\"expectation\":0,\"payoff\":0,\"problems\":[\"...\"],\"highlight\":\"本章最好的一处\"}]}
                """).formatted(properties.getRubricVersion(), chapterNo,
                StringUtils.defaultString(title), text, chapterNo);
    }

    /** 单章评分结果（字段名与 LLM 输出 JSON 一一对应） */
    @Data
    public static class ChapterReview {
        private Integer chapterNo;
        private Integer personaConsistency;
        private Integer prose;
        private Integer dialogue;
        private Integer expectation;
        private Integer payoff;
        private List<String> problems;
        private String highlight;

        /** 按维度键取分；未知键返回 null（新增维度时不会静默当成 0 拉低均值） */
        public Integer score(String dimension) {
            return switch (dimension) {
                case "personaConsistency" -> personaConsistency;
                case "prose" -> prose;
                case "dialogue" -> dialogue;
                case "expectation" -> expectation;
                case "payoff" -> payoff;
                default -> null;
            };
        }
    }

    /** 模型输出的外层包装 */
    @Data
    public static class ReviewOutput {
        private List<ChapterReview> reviews;
    }
}
