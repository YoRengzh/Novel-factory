package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class StyleViolationPolicy {

    /** 修订触发档：只有本档进入修订闭环 */
    public static final String SEVERITY_BLOCKING = "BLOCKING";
    /** 记录档：落质量债/供观测统计与规划层回灌，不触发修订也不触发候选选优 */
    public static final String SEVERITY_MINOR = "MINOR";

    public static final double ADVERB_MAX_PER_1000_CHARS = 5.0;

    public static final int EYE_CLICHE_MAX_OCCURRENCES = 3;
    /** 身体反应套话同一词条全章次数上限（达到该值即违规） */
    public static final int BODY_CLICHE_SAME_WORD_MAX = 2;
    /** 章末升华检测：最后一段最小长度（低于此长度不触发） */
    public static final int ENDING_ELEVATION_MIN_LENGTH = 30;

    /** 章节编号元信息泄露正则：匹配"第12章""第十二章""本章""上一章""下一章"等作者层面的元信息 */
    private static final java.util.regex.Pattern CHAPTER_REF_PATTERN =
            java.util.regex.Pattern.compile("第[\\d一二三四五六七八九十百千零〇两]+章|本章|上一章|下一章|前一章|后一章|此章");

    private static final String[] ELEVATION_KEYWORDS = {
            "从此", "这就是", "归根结底", "总而言之",
            "他明白了", "她明白了", "他知道了", "她知道了",
            "命运", "宿命", "新的篇章", "故事才刚刚开始",
            "从此以后"
    };

    /** 常见动作动词表（用于判断最后一段是否停在动作上） */
    private static final String[] ACTION_VERBS = {
            "说", "道", "喊", "叫", "笑", "哭", "走", "跑", "站", "坐",
            "拿", "放", "打", "砍", "刺", "握", "攥", "推", "拉", "开",
            "关", "看", "望", "盯", "转", "动", "伸手", "抬头", "低头",
            "转身", "拔出", "抽出", "落下", "升起", "敲响", "推开", "关上"
    };

    /**
     * 扫描正文，返回文风 issue（严重度按项分层，见类注释）。
     * 每条 evidence 为"词条×次数"清单，供修订定点清除（BLOCKING 档）或观测统计（MINOR 档）。
     */
    public static List<ChapterIssueEntity> check(String content) {
        if (StringUtils.isBlank(content)) {
            return List.of();
        }
        FatiguePatternCatalog.Catalog catalog = FatiguePatternCatalog.get();
        int effectiveChars = Math.max(1, ChapterLengthPolicy.effectiveCharacterCount(content));

        List<ChapterIssueEntity> issues = new ArrayList<>();

        // 1. 万能副词密度（程度问题，MINOR）
        Map<String, Integer> adverbHits = countHits(content, catalog.adverbs());
        int adverbTotal = adverbHits.values().stream().mapToInt(Integer::intValue).sum();
        double perThousand = adverbTotal * 1000.0 / effectiveChars;
        if (perThousand > ADVERB_MAX_PER_1000_CHARS) {
            issues.add(buildIssue(
                    SEVERITY_MINOR,
                    "万能副词密度超标（" + adverbTotal + " 次 / " + effectiveChars + " 有效字，上限 "
                            + ADVERB_MAX_PER_1000_CHARS + " 次/千字）",
                    renderHits(adverbHits),
                    "删掉后句意不变就删（“他不禁笑了”→“他笑了”）；确需保留的每千字至多 "
                            + ADVERB_MAX_PER_1000_CHARS + " 次"));
        }

        // 2. 眼神套话合计（程度问题，MINOR）
        Map<String, Integer> eyeHits = countHits(content, catalog.eyeCliches());
        int eyeTotal = eyeHits.values().stream().mapToInt(Integer::intValue).sum();
        if (eyeTotal >= EYE_CLICHE_MAX_OCCURRENCES) {
            issues.add(buildIssue(
                    SEVERITY_MINOR,
                    "眼神套话命中 " + eyeTotal + " 次（眼睛没有“复杂”这种通用表情，读者无法成像）",
                    renderHits(eyeHits),
                    "核对这几处是否表达同一反应。可以省去已由上下文交代的那处。"));
        }

        // 3. 身体反应套话同一词条复读（程度问题，MINOR）
        Map<String, Integer> bodyHits = countHits(content, catalog.bodyCliches());
        Map<String, Integer> repeated = bodyHits.entrySet().stream()
                .filter(e -> e.getValue() >= BODY_CLICHE_SAME_WORD_MAX)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
        if (!repeated.isEmpty()) {
            issues.add(buildIssue(
                    SEVERITY_MINOR,
                    "身体反应套话复读（同一词条全章至多 1 次）",
                    renderHits(repeated),
                    "同一反应已在前文交代时，可以省去重复处；保留会影响后续行动的信息。"));
        }

        // 4. 章末升华检测（程度问题，MINOR）
        String endingEvidence = checkEndingElevation(content);
        if (endingEvidence != null) {
            issues.add(buildIssue(
                    SEVERITY_MINOR,
                    "章末升华候选，需核对结尾判断是否复述前文",
                    endingEvidence,
                    "只压缩重复点题的部分，保留人物刚获得的信息和由此产生的判断。"));
        }

        // 5. 章节编号元信息泄露检测（硬伤，BLOCKING——精确串、可定点清除，读者看到立刻出戏）
        String chapterRefEvidence = checkChapterReference(content);
        if (chapterRefEvidence != null) {
            issues.add(buildIssue(
                    SEVERITY_BLOCKING,
                    "章节编号元信息泄露（叙述中出现作者层面的章节指代，读者立刻出戏，意识到这是AI写的小说）",
                    chapterRefEvidence,
                    "用时间/地点/事件指代替代章节编号：\"第12章他在阵眼核心发现的\"→\"十二天前在阵眼核心发现的\"；\"第28章袭击事件后\"→\"上次遇袭之后\"；\"本章/上一章\"→删除或改为\"此刻/此前\""));
        }

        return issues;
    }

    private static String checkEndingElevation(String content) {
        String[] paragraphs = content.split("\\n+");
        // 从后往前找第一个长度达标的非空段落
        String lastParagraph = null;
        for (int i = paragraphs.length - 1; i >= 0; i--) {
            String trimmed = paragraphs[i].trim();
            if (trimmed.length() >= ENDING_ELEVATION_MIN_LENGTH) {
                lastParagraph = trimmed;
                break;
            }
        }
        if (lastParagraph == null) return null;

        // 检查是否包含总结性词汇
        boolean hasElevationWord = false;
        for (String keyword : ELEVATION_KEYWORDS) {
            if (lastParagraph.contains(keyword)) {
                hasElevationWord = true;
                break;
            }
        }
        if (!hasElevationWord) return null;

        // 检查是否包含动作动词
        boolean hasAction = false;
        for (String verb : ACTION_VERBS) {
            if (lastParagraph.contains(verb)) {
                hasAction = true;
                break;
            }
        }

        // 检查是否包含对话引号（必须含中文弯引号 U+201C/U+201D：正文实际用弯引号，
        // 只判 ASCII 双引号与「」会漏判，导致末段有对白仍被误判为"章末升华"）
        boolean hasDialogue = lastParagraph.contains("\"") || lastParagraph.contains("\u201C")
                || lastParagraph.contains("\u201D") || lastParagraph.contains("「")
                || lastParagraph.contains("『");

        // 判定：有升华词 + 长度达标 + 既无动作又无对白 → 才是旁白总结
        if (!hasAction && !hasDialogue) {
            String evidence = lastParagraph.length() > 80
                    ? lastParagraph.substring(0, 80) + "..."
                    : lastParagraph;
            return evidence;
        }
        return null;
    }

    public static String checkChapterReference(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        java.util.regex.Matcher m = CHAPTER_REF_PATTERN.matcher(content);
        Map<String, Integer> hits = new LinkedHashMap<>();
        while (m.find()) {
            if (insideDialogue(content, m.start())) {
                continue;
            }
            hits.merge(m.group(), 1, Integer::sum);
        }
        if (hits.isEmpty()) return null;
        return hits.entrySet().stream()
                .map(e -> e.getKey() + "×" + e.getValue())
                .collect(Collectors.joining("、"));
    }

    /** 引号字符（中文弯引号成对出现；ASCII 双引号按奇偶配对计） */
    private static boolean isQuoteChar(char c) {
        return c == '\u201C' || c == '\u201D' || c == '\u300C' || c == '\u300D'
                || c == '\u300E' || c == '\u300F' || c == '"';
    }

    /**
     * 判定某下标是否位于引号（对白）内部：统计其之前的引号字符数，奇数即在引号内。
     * 正文用成对弯引号，奇偶判据足够稳健；混排 ASCII 双引号亦按同一奇偶口径处理。
     */
    static boolean insideDialogue(String content, int pos) {
        int quotes = 0;
        for (int i = 0; i < pos && i < content.length(); i++) {
            if (isQuoteChar(content.charAt(i))) {
                quotes++;
            }
        }
        return quotes % 2 == 1;
    }

    public static boolean hasChapterReference(String content) {
        if (StringUtils.isBlank(content)) return false;
        java.util.regex.Matcher m = CHAPTER_REF_PATTERN.matcher(content);
        while (m.find()) {
            if (!insideDialogue(content, m.start())) {
                return true;
            }
        }
        return false;
    }

    public static String renderChapterRefAuditHint(String evidence) {
        if (evidence == null) return null;
        return "【章节编号元信息泄露·加审预警】正文检测到作者层面的章节指代（" + evidence
                + "），读者看到会立刻出戏。审校时必须逐处定位并替换为时间/地点/事件指代："
                + "\"第12章他在阵眼核心发现的\"→\"十二天前在阵眼核心发现的\"；"
                + "\"第28章袭击事件后\"→\"上次遇袭之后\"；\"第6章那个模糊的感知\"→\"之前那次模糊的感知\"；"
                + "\"本章/上一章\"→删除或改为\"此刻/此前\"。修订时必须全部清除，不得残留任何章节编号。";
    }

    private static ChapterIssueEntity buildIssue(String severity, String description,
                                                 String evidence, String suggestion) {
        ChapterIssueEntity issue = new ChapterIssueEntity();
        issue.setDimension("aesthetic");
        issue.setSeverity(severity);
        issue.setDescription(description);
        issue.setEvidence(evidence);
        issue.setSuggestion(suggestion);
        return issue;
    }

    /** 逐词计数（String.indexOf 线性扫描，正文数千字 × 数十词，量级可忽略） */
    private static Map<String, Integer> countHits(String content, List<String> words) {
        Map<String, Integer> hits = new LinkedHashMap<>();
        for (String word : words) {
            int count = 0;
            int idx = 0;
            while ((idx = content.indexOf(word, idx)) >= 0) {
                count++;
                idx += word.length();
            }
            if (count > 0) {
                hits.put(word, count);
            }
        }
        return hits;
    }

    private static String renderHits(Map<String, Integer> hits) {
        return hits.entrySet().stream()
                .map(e -> e.getKey() + "×" + e.getValue())
                .collect(Collectors.joining("、"));
    }

    private StyleViolationPolicy() { }
}
