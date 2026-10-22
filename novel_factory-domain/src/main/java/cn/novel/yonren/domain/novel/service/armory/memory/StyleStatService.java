package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.model.entity.StyleStatEntity;
import cn.novel.yonren.domain.novel.service.armory.quality.DialogueRatioPolicy;
import cn.novel.yonren.domain.novel.service.armory.quality.RepetitionWatchlistCatalog;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 风格统计服务：机械层的风格账本——跨章逐字重复句查重 + 疲劳词计数，
 * 纯代码统计不经过 LLM，治"古镜发热/咽下血沫"式的跨章路径依赖。
 * 数据随正文逐章滚动合并，与三账本同思路：确定性重建、检查点落盘、前缀注入警示
 */
@Service
public class StyleStatService {

    /** 长句判定下限：短于此的句子不参与查重（称呼、短句复用是正常文体） */
    public static final int MIN_SENTENCE_LENGTH = 10;
    /**
     * 已见句集滚动上限（挤出最老句子，防无界增长）。
     *
     * <p>2026-10-10 由 80 上调至 300：80 句约等于**一章**的量，于是"隔两章的重复"根本进不了视野
     * ——实测第 100 章与第 102 章一字不差重复了同一句，查重两侧都查不到。该列表不参与 prompt 渲染
     * （渲染的只有 {@link #MAX_REPEATED_SENTENCES} 条重复句），所以放宽窗口只增加一点内存与比对，
     * 不增加 prompt 体积。
     */
    public static final int MAX_USED_SENTENCES = 300;
    /** 跨章重复句保留上限（警示渲染用） */
    public static final int MAX_REPEATED_SENTENCES = 20;
    /** 警示渲染的重复句条数上限 */
    private static final int RENDER_REPEATED_COUNT = 10;
    /** 疲劳词警示阈值：全篇出现达到该次数即进超频名单 */
    public static final int FATIGUE_THRESHOLD = 5;

    /**
     * 重复意象的点名阈值：全书累计达到该次数即进警示名单。
     *
     * <p>取 4 而非沿用疲劳词的 5：意象是**叙事元素**，比虚词更显眼——同一个具体符号出现 4 次，
     * 读者已经能感到"作者在反复用这个提醒我"。同时比疲劳词宽松：意象的适度复现是正常的
     * 伏笔呼应（隔几十章回指一次是好写法），要抓的是"变成口头禅"那种密度。
     */
    public static final int IMAGERY_THRESHOLD = 4;

    /** 意象警示的渲染条数上限：只列最超频的几条，避免清单本身占满 prompt */
    private static final int MAX_RENDER_IMAGERY = 6;

    /**
     * 对白句入库下限：**含引号**长度。
     *
     * <p>取 8 是两头的取舍（实测标定 2026-10-10）：要抓的真重复是 10 字的
     * 「三班还等得到吗？」，而套话短答（「我知道了。」6 字、「明天再说。」6 字、「我先走了。」6 字）
     * 在正常文里本来就会自然复现——那条判据要进 BLOCKING 驱动改稿，把套话扫进来等于白改一稿。
     */
    public static final int MIN_DIALOGUE_LINE_LENGTH = 8;
    /**
     * 对白句集滚动上限。远大于句集：对白每章只有二三十句、句子又短，
     * 而"人物反复问同一句"是读者最容易察觉的重复形态，值得看住整卷。
     */
    public static final int MAX_USED_DIALOGUE_LINES = 400;

    /**
     * 近重复判定的相似度下限：字符 3-gram 的 Jaccard 相似度。
     *
     * <p><b>为什么需要它</b>：查重原先用精确 {@code equals}，而模型最爱的重复方式是"改一两个字"——
     * 实测第 9/10 章结尾仅差一个"他"字：
     * 「枕头底下，那根烟硌着<b>他</b>的后脑勺，像一根小小的骨头」，
     * 精确匹配下跨章重复句统计结果为 <b>0 条</b>，等于完全失明。
     *
     * <p>取 0.70 依据实测：上述一对句子的 3-gram Jaccard ≈ 0.77（会被捕获）；
     * 而"同结构但不同内容"的句子（如"他低头看自己的手"vs"他低头看自己的脚"）虽相似，
     * 其 Jaccard 低于该线。该判据只影响<b>风格警示与 MINOR 记账</b>，不进 BLOCKING 闸门，
     * 故取偏保守的阈值：宁可漏报也不要把正常文体误标成重复。
     */
    private static final double NEAR_DUPLICATE_JACCARD = 0.70;

    /** 近重复判定的 n-gram 阶数（中文按字取 3 元，兼顾敏感度与噪声） */
    private static final int NEAR_DUPLICATE_NGRAM = 3;

    /** 疲劳词表：与单章机械门禁共用 assets/rules/fatigue-patterns.txt（FatiguePatternCatalog），
     * 覆盖 anti-ai-tone.md 第二节"AI 万能副词"与第三节"空洞神态/情绪词"判据；改词只改 txt 文件 */
    private static final List<String> FATIGUE_WORDS =
            cn.novel.yonren.domain.novel.service.armory.quality.FatiguePatternCatalog.get().all();

    /** 指纹句长度下限（高信息密度长句，与重复句查重下限区分开） */
    public static final int FINGERPRINT_MIN_LENGTH = 18;
    /** 指纹句长度上限（过长的段落句不适合做风格示例） */
    public static final int FINGERPRINT_MAX_LENGTH = 60;
    /** 单章提取条数上限 */
    public static final int FINGERPRINT_PER_CHAPTER = 2;
    /** 指纹库滚动上限（FIFO 挤出最老） */
    public static final int MAX_FINGERPRINTS = 50;

    /**
     * 空统计（新故事起点）
     */
    public StyleStatEntity empty() {
        return StyleStatEntity.builder()
                .usedSentences(new ArrayList<>())
                .repeatedSentences(new ArrayList<>())
                .fatigueWords(new LinkedHashMap<>())
                .usedDialogueLines(new ArrayList<>())
                .imageryWords(new LinkedHashMap<>())
                .build();
    }

    /**
     * 滚动合并一章正文：长句查重（与已见句集相交即记为跨章重复）→ 新句并入 → 对白句入库 → 疲劳词累加
     */
    public void merge(StyleStatEntity stat, String content) {
        merge(stat, content, null);
    }

    /**
     * 同上，并统计**重复意象**（{@code genreCode} 决定取用清单的哪一组）。
     *
     * <p>意象统计与疲劳词同款：跨章逐字累计、随正文滚动合并、零 LLM 成本。
     * 词条来自外置清单（见 {@code RepetitionWatchlistCatalog}）——题材为 null 或该题材无专属词条时，
     * 只统计通用组；清单为空则整体不启用（行为与引入前完全一致）。
     *
     * @param genreCode 题材 code（{@code GenreTypeVO.code}），可空
     */
    public void merge(StyleStatEntity stat, String content, String genreCode) {
        if (stat == null || StringUtils.isBlank(content)) {
            return;
        }
        String normalized = content.replace("\\n", "\n");
        for (String raw : splitSentences(normalized)) {
            String sentence = raw.trim();
            // 短句不参与查重（称呼、短句复用是正常文体）
            if (sentence.length() < MIN_SENTENCE_LENGTH) {
                continue;
            }
            if (isRepeat(stat.getUsedSentences(), sentence)) {
                if (!stat.getRepeatedSentences().contains(sentence)) {
                    stat.getRepeatedSentences().add(sentence);
                    if (stat.getRepeatedSentences().size() > MAX_REPEATED_SENTENCES) {
                        stat.getRepeatedSentences().remove(0);
                    }
                }
            } else {
                stat.getUsedSentences().add(sentence);
                if (stat.getUsedSentences().size() > MAX_USED_SENTENCES) {
                    stat.getUsedSentences().remove(0);
                }
            }
        }
        // 对白句单独入长窗口索引：它们短、少，但"人物反复问同一句"是最刺眼的重复形态，
        // 而通用句集的窗口（一章量）看不住它。入库时去掉空白，比对时同一口径（见 crossChapterDialogueRepeats）
        if (stat.getUsedDialogueLines() == null) {
            stat.setUsedDialogueLines(new ArrayList<>());
        }
        for (String utterance : DialogueRatioPolicy.utterances(normalized)) {
            String line = utterance.replaceAll("\\s", "").trim();
            if (line.length() < MIN_DIALOGUE_LINE_LENGTH) {
                continue;
            }
            if (!stat.getUsedDialogueLines().contains(line)) {
                stat.getUsedDialogueLines().add(line);
                if (stat.getUsedDialogueLines().size() > MAX_USED_DIALOGUE_LINES) {
                    stat.getUsedDialogueLines().remove(0);
                }
            }
        }
        for (String word : FATIGUE_WORDS) {
            int count = countOccurrences(normalized, word);
            if (count > 0) {
                stat.getFatigueWords().merge(word, count, Integer::sum);
            }
        }
        mergeImagery(stat, normalized, genreCode);
    }

    /**
     * 重复意象累计：按外置清单逐字统计（子串匹配，非分词）。
     *
     * <p>只累加不衰减——与疲劳词同语义（"全篇出现了多少次"），超频判定交给渲染侧，
     * 因为"该不该点名"要看的是**全篇累计**而非窗口：实测「敲钟/金锤/股权」这类意象
     * 每隔几章出现一次，任何有限窗口都会漏掉（这与跨段的能力展示套路是同一类问题）。
     */
    private void mergeImagery(StyleStatEntity stat, String normalized, String genreCode) {
        List<String> phrases = RepetitionWatchlistCatalog.get().forGenre(genreCode);
        if (phrases.isEmpty()) {
            return;
        }
        if (stat.getImageryWords() == null) {
            stat.setImageryWords(new LinkedHashMap<>());
        }
        for (String phrase : phrases) {
            int count = countOccurrences(normalized, phrase);
            if (count > 0) {
                stat.getImageryWords().merge(phrase, count, Integer::sum);
            }
        }
    }

    /**
     * 本章对白中**与早期章节逐字相同**的句子（去空白后精确相等）。
     *
     * <p>只认精确相等，不做近重复：这条判据要进 BLOCKING 走定向修订，误报的代价是白改一稿；
     * 而"同一句台词隔几章一字不差地又出现"本身就是硬伤，放宽口径反而会把正常呼应也扫进来。
     * 近重复（改一两个字）仍走 {@link #merge} 的 MINOR 记账与风格警示那条通道。
     *
     * @return 重复的对白原文（含引号，已去空白）；无重复时返回空表
     */
    public List<String> crossChapterDialogueRepeats(StyleStatEntity stat, String content) {
        if (stat == null || StringUtils.isBlank(content) || stat.getUsedDialogueLines() == null
                || stat.getUsedDialogueLines().isEmpty()) {
            return List.of();
        }
        List<String> repeats = new ArrayList<>();
        for (String utterance : DialogueRatioPolicy.utterances(content)) {
            String line = utterance.replaceAll("\\s", "").trim();
            if (line.length() < MIN_DIALOGUE_LINE_LENGTH || repeats.contains(line)) {
                continue;
            }
            if (stat.getUsedDialogueLines().contains(line)) {
                repeats.add(line);
            }
        }
        return repeats;
    }

    /**
     * 是否与已见句集重复：精确相等，**或**近重复（改一两个字）。
     * 近重复是模型最偏爱的重复方式——只看精确相等会整类漏掉（实测跨章精确重复 0 条，
     * 而第 9/10 章结尾仅差一个"他"字）。
     */
    private static boolean isRepeat(List<String> usedSentences, String sentence) {
        for (String used : usedSentences) {
            if (used.equals(sentence) || isNearDuplicate(used, sentence)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 近重复：字符 n-gram 的 Jaccard 相似度达阈值。
     * 长度差超过一倍时先短路——那已经不是"改一两个字"，既省算力也避免误判。
     */
    static boolean isNearDuplicate(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        int maxLen = Math.max(a.length(), b.length());
        int minLen = Math.min(a.length(), b.length());
        if (minLen < MIN_SENTENCE_LENGTH || maxLen > minLen * 2) {
            return false;
        }
        Set<String> ga = ngrams(a);
        Set<String> gb = ngrams(b);
        if (ga.isEmpty() || gb.isEmpty()) {
            return false;
        }
        Set<String> intersection = new HashSet<>(ga);
        intersection.retainAll(gb);
        Set<String> union = new HashSet<>(ga);
        union.addAll(gb);
        return !union.isEmpty() && (double) intersection.size() / union.size() >= NEAR_DUPLICATE_JACCARD;
    }

    /** 字符 n-gram 集合；文本短于 n 时退化为整串（避免空集导致除零与漏判） */
    private static Set<String> ngrams(String text) {
        Set<String> grams = new HashSet<>();
        if (text.length() < NEAR_DUPLICATE_NGRAM) {
            grams.add(text);
            return grams;
        }
        for (int i = 0; i + NEAR_DUPLICATE_NGRAM <= text.length(); i++) {
            grams.add(text.substring(i, i + NEAR_DUPLICATE_NGRAM));
        }
        return grams;
    }

    /**
     * 渲染风格警示节（拼入下一章记忆前缀）；无重复句且无超频词时返回空串
     */
    public String renderWarning(StyleStatEntity stat) {
        if (stat == null) {
            return "";
        }
        List<String> overused = stat.getFatigueWords().entrySet().stream()
                .filter(e -> e.getValue() >= FATIGUE_THRESHOLD)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> e.getKey() + "(" + e.getValue() + "次)")
                .collect(Collectors.toList());
        if (stat.getRepeatedSentences().isEmpty() && overused.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("==== 以下为风格警示，本章必须遵守 ====\n");
        if (!stat.getRepeatedSentences().isEmpty()) {
            sb.append("【跨章重复句】以下句子已在早期章节出现过，或与早期句子高度相似（仅改一两个字也算），"
                    + "同类句式严禁再次使用——换一个切入角度或落笔对象，而不是换个词重说一遍：\n");
            List<String> recent = stat.getRepeatedSentences();
            recent.stream()
                    .skip(Math.max(0, recent.size() - RENDER_REPEATED_COUNT))
                    .forEach(s -> sb.append("- ").append(s).append("\n"));
        }
        if (!overused.isEmpty()) {
            sb.append("【疲劳词超频】以下词全篇已超频，本章禁用或至多出现一次：")
                    .append(String.join("、", overused)).append("\n");
        }
        return sb.toString();
    }

    /**
     * 渲染修订侧疲劳词禁新增名单（注入修订 prompt）：全文词表 + 已累计逼近/达到阈值的词点名。
     *
     * <p>2026-10-03 措辞同步：风格账闸已降级为"只告知不拒稿"（修订优先修 BLOCKING），
     * 故不再声称"即拒稿"——但新增仍会推高全书累计、由后续每一章的风格警示承担代价，措辞如实说明。
     */
    public String renderReviseFatigueBlacklist(StyleStatEntity stat) {
        StringBuilder sb = new StringBuilder("【疲劳词禁新增名单】修订稿严禁新增或推高以下词的全书累计次数（跨章累计达到")
                .append(FATIGUE_THRESHOLD).append("次即进入全书风格警示，代价由后续每一章承担）：");
        for (String word : FATIGUE_WORDS) {
            sb.append("、").append(word);
        }
        sb.append("\n注意：即便原稿某词未超频，修订稿也绝不能在全书累计上再推高它。");
        List<String> nearLimit = nearLimitWords(stat);
        if (!nearLimit.isEmpty()) {
            sb.append("\n【已逼近阈值的词】").append(String.join("、", nearLimit)).append("——修订中绝对禁止再出现");
        }
        return sb.toString();
    }

    /**
     * 渲染正文侧疲劳词预警名单（前置注入正文 prompt）：把机械门禁的词表与红线提前亮给模型，
     * 治"初稿必超标、靠修订擦屁股"——初稿就少犯，比每章烧一轮修订更省。
     * 逼近阈值的词与修订侧同一判定口径（≥阈值-1）。
     */
    public String renderContentFatigueBlacklist(StyleStatEntity stat) {
        StringBuilder sb = new StringBuilder("【疲劳词预警名单】以下词受机械门禁监控（万能副词单章密度超 3 次/千字、")
                .append("眼神套话全章合计 2 次、同一身体套话词条全章 2 次，即打回修订），本章写作请主动避免：");
        for (String word : FATIGUE_WORDS) {
            sb.append("、").append(word);
        }
        List<String> nearLimit = nearLimitWords(stat);
        if (!nearLimit.isEmpty()) {
            sb.append("\n【已逼近阈值的词】").append(String.join("、", nearLimit)).append("——本章绝对禁止再出现");
        }
        return sb.toString();
    }

    /** 全书累计已逼近/达到阈值（≥阈值-1）的疲劳词：正文/修订两侧 prompt 共用同一判定口径 */
    private List<String> nearLimitWords(StyleStatEntity stat) {
        if (stat == null || stat.getFatigueWords() == null) {
            return List.of();
        }
        return FATIGUE_WORDS.stream()
                .filter(w -> stat.getFatigueWords().containsKey(w))
                .filter(w -> stat.getFatigueWords().get(w) >= FATIGUE_THRESHOLD - 1)
                .map(w -> w + "（全书已累计" + stat.getFatigueWords().get(w) + "次）")
                .collect(Collectors.toList());
    }

    /**
     * 渲染**重复意象警示**（注入正文 prompt）：把全书累计达到阈值的意象点名，并要求换载体。
     *
     * <p><b>为什么要"换什么"而不只是"别再用"</b>：只下禁令时模型最容易的做法是把同一个意象
     * 换个说法继续用（实测「敲钟/金锤/股权」反复出现正是这个形态）。故点名后必须给替换方向——
     * 这与 {@code UsedPatternPolicy} 第三节（角色冲突"至少换两项"）是同一套措辞纪律。
     *
     * <p>阈值取 {@link #IMAGERY_THRESHOLD}：比疲劳词宽松（意象是叙事元素，适度复现是正常的
     * 伏笔呼应；要抓的是"变成作者口头禅"那种密度）。
     *
     * @return 无超频意象时返回 null（调用方直接跳过，不占 prompt 预算）
     */
    public String renderImageryWarning(StyleStatEntity stat) {
        List<String> overused = overusedImagery(stat);
        if (overused.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("【重复意象警示】以下意象在全书已反复出现，");
        sb.append("本章**不得再用它们承担抒情或前世/记忆锚点功能**：");
        sb.append(String.join("、", overused));
        sb.append("\n替换方向：换成**别的具体载体**——一件当时在场的普通物件、一句没说完的话、");
        sb.append("一个具体的人或一次没赴的约；关键是让读者感到「这一次不一样」，");
        sb.append("而不是又一次用同一个符号提醒他们旧事。同一意象若要再次出现，至少间隔 20 章以上，");
        sb.append("且必须承担与上次**不同**的叙事功能。");
        return sb.toString();
    }

    /** 全书累计超频（≥{@link #IMAGERY_THRESHOLD}）的意象：按次数降序，最多 {@link #MAX_RENDER_IMAGERY} 条 */
    private List<String> overusedImagery(StyleStatEntity stat) {
        if (stat == null || stat.getImageryWords() == null || stat.getImageryWords().isEmpty()) {
            return List.of();
        }
        return stat.getImageryWords().entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() >= IMAGERY_THRESHOLD)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(MAX_RENDER_IMAGERY)
                .map(e -> e.getKey() + "（全书已出现" + e.getValue() + "次）")
                .collect(Collectors.toList());
    }

    /**
     * 提取高信息密度句作为本书文风指纹（正向激励，补"只有黑名单没有白名单"）：
     * 仅"审校全票通过"（GateResult.CLEAN_PASS）的章节由调用方提取入库。
     * 机械判据：句长 18-60、不含疲劳词；含数字者优先（硬信息密度信号），其次取更长句。
     * 返回至多 {@link #FINGERPRINT_PER_CHAPTER} 条
     */
    public List<String> extractFingerprints(String content) {
        if (StringUtils.isBlank(content)) {
            return List.of();
        }
        String normalized = content.replace("\\n", "\n");
        List<String> candidates = new ArrayList<>();
        for (String raw : splitSentences(normalized)) {
            String sentence = raw.trim();
            if (sentence.length() < FINGERPRINT_MIN_LENGTH || sentence.length() > FINGERPRINT_MAX_LENGTH) {
                continue;
            }
            if (containsFatigueWord(sentence)) {
                continue;
            }
            candidates.add(sentence);
        }
        candidates.sort(Comparator
                .comparingInt((String s) -> containsDigit(s) ? 1 : 0).reversed()
                .thenComparing(Comparator.comparingInt(String::length).reversed()));
        return candidates.stream().limit(FINGERPRINT_PER_CHAPTER).collect(Collectors.toList());
    }

    /**
     * 指纹库滚动合并：新句插到尾部（最新文风优先展示），FIFO 挤出最老，上限 {@link #MAX_FINGERPRINTS}。
     * 防御 null 入参；指纹句本身已在 usedSentences 中（正文合并时已收录），后续章节逐字复用
     * 会被既有跨章重复句查重命中，与"禁止逐字复用"的注入措辞形成自洽闭环
     */
    public void mergeFingerprints(List<String> fingerprints, List<String> additions) {
        if (fingerprints == null || additions == null || additions.isEmpty()) {
            return;
        }
        for (String sentence : additions) {
            if (StringUtils.isNotBlank(sentence) && !fingerprints.contains(sentence)) {
                fingerprints.add(sentence);
            }
        }
        while (fingerprints.size() > MAX_FINGERPRINTS) {
            fingerprints.remove(0);
        }
    }

    /**
     * 渲染文风基准节（注入正文 prompt）：示例句代表本书已建立的密度与句式节奏，
     * 明令对齐而非逐字复用（复用会被跨章重复句查重命中）
     */
    public String renderFingerprints(List<String> fingerprints) {
        if (fingerprints == null || fingerprints.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("==== 以下为本书已建立的文风基准 ====\n");
        sb.append("以下句子出自本书已定稿章节，代表本书确立的信息密度与句式节奏。")
                .append("写作时对齐其密度与节奏，但严禁逐字复用其中任何一句（跨章重复句查重会命中）：\n");
        for (String sentence : fingerprints) {
            sb.append("- ").append(sentence).append("\n");
        }
        return sb.toString();
    }

    private boolean containsFatigueWord(String sentence) {
        for (String word : FATIGUE_WORDS) {
            if (sentence.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsDigit(String sentence) {
        for (int i = 0; i < sentence.length(); i++) {
            if (Character.isDigit(sentence.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * **章内相邻整句重复**（机械）：同一句在正文中紧邻或极近距离内一字不差地出现两次。
     *
     * <p><b>为什么要与跨章查重分开单列</b>：{@link #crossChapterDialogueRepeats} 只比"本章 vs 历史章节"，
     * 本章自己内部撞车不在它的射程内；而相邻整句复制是最刺眼的一类硬伤（读者立刻看出"这段写了两遍"），
     * 且**它常常不是写手原始稿就有的**——段落密度审校的改写补丁把"删除该段"误写成"保留原句再接一遍"
     * 时会引入，而那条路径的采纳闸门只查机械文风违规与语义锚，重复句两条都不碰。
     *
     * <p>口径只认**精确相等**（去空白后）：误报代价是白改一稿，与跨章对白查重同款。
     * 近重复（改一两个字）不走这里——正常行文里"他点点头"这类短句的近似复现是自然现象，
     * 交给风格警示的软通道，不进 BLOCKING。
     *
     * <p>判定范围取**整章**而非只看紧邻两句：重复的两句之间常隔着别的短句（如"她没睡。"），
     * 只比相邻会漏掉这种形态。同一句在章内出现两次以上时，只报一次（去重）。
     *
     * @return 重复句原文（去空白前保留原样，便于回显给修订）；无重复时返回空表
     */
    public static List<String> intraChapterDuplicateSentences(String content) {
        if (StringUtils.isBlank(content)) {
            return List.of();
        }
        String normalized = content.replace("\\n", "\n");
        List<String> sentences = Arrays.stream(normalized.split("[。！？…\\n]+"))
                .map(String::trim)
                .filter(StringUtils::isNotEmpty)
                .collect(Collectors.toList());
        Map<String, String> firstSeen = new LinkedHashMap<>();
        Set<String> reported = new LinkedHashSet<>();
        for (String raw : sentences) {
            String trimmed = raw.trim();
            String key = trimmed.replaceAll("\\s", "");
            // 短句不参与：称呼、短答、"他点点头"这类复用是正常文体，与查重下限同一把尺
            if (key.length() < MIN_SENTENCE_LENGTH) {
                continue;
            }
            if (firstSeen.containsKey(key)) {
                reported.add(trimmed);
            } else {
                firstSeen.put(key, trimmed);
            }
        }
        return new ArrayList<>(reported);
    }

    /**
     * 按中英文句末符与换行切句
     */
    private List<String> splitSentences(String text) {
        return Arrays.stream(text.split("[。！？…\\n]+"))
                .map(String::trim)
                .filter(StringUtils::isNotEmpty)
                .collect(Collectors.toList());
    }

    private int countOccurrences(String text, String word) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(word, idx)) >= 0) {
            count++;
            idx += word.length();
        }
        return count;
    }

}
