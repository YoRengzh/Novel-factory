package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public final class DialogueRatioPolicy {

    // ---------- 行级占比（全局线：实测两题材几乎无差异，不为题材感知而硬套） ----------
    // romance 35 章 均值 0.370／中位 0.375；fantasy 353 章 均值 0.344／中位 0.324——差异不足以分线
    private static final double RATIO_OK = 0.28;
    private static final double RATIO_DEGRADED = 0.16;
    // ---------- 轮次密度（每千**有效字**）（题材差异显著，故分线） ----------
    // 全样本 200 章：均值 15.2／中位 15.0／p10 8.3；恋爱同类 11.5-12.6；被评那本 8.4（离群低）
    private static final double DENSITY_OK = 9.0;
    private static final double DENSITY_DEGRADED = 6.0;
    private static final double DENSITY_OK_DIALOGUE_DRIVEN = 11.5;
    private static final double DENSITY_DEGRADED_DIALOGUE_DRIVEN = 9.0;

    private static final String[] QUOTES = {"「", "」", "“", "”", "\""};
    /** 对白轮次：一对中文/直角/ASCII 引号包裹的发言算 1 次（跨行用 DOTALL 非贪婪） */
    private static final Pattern UTTERANCE = Pattern.compile("(?:[“「].*?[”」]|\"[^\"\\r\\n]*\")", Pattern.DOTALL);

    /**
     * 提取全部对白片段（**含引号本身**）——仓库内"什么算一句对白"的唯一定义。
     *
     * <p>跨章对白查重（{@code StyleStatService}）必须与轮次统计用同一份口径，
     * 否则会出现"同一句对白在一处算重复、在另一处不计入"的分裂——本项目已在两份 LCS 口径上吃过同类亏。
     */
    public static List<String> utterances(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        Matcher matcher = UTTERANCE.matcher(content);
        List<String> result = new ArrayList<>();
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    private DialogueRatioPolicy() {
    }

    /** 是否对话驱动题材（轮次要求更严）。当前只认 ROMANCE——DEFAULT 下的智斗/掉马子类尚未覆盖，
     *  需按数据再定；已知局限记在此处，避免日后误以为"已全面覆盖" */
    public static boolean isDialogueDriven(String genreCode) {
        return "romance".equalsIgnoreCase(genreCode);
    }

    /** 行级占比的健康线（全局；题材差异实测不足以分线） */
    public static double ratioOk() {
        return RATIO_OK;
    }

    /** 行级占比的劣化线（全局） */
    public static double ratioDegraded() {
        return RATIO_DEGRADED;
    }

    public static double densityOk(String genreCode) {
        return isDialogueDriven(genreCode) ? DENSITY_OK_DIALOGUE_DRIVEN : DENSITY_OK;
    }

    public static double densityDegraded(String genreCode) {
        return isDialogueDriven(genreCode) ? DENSITY_DEGRADED_DIALOGUE_DRIVEN : DENSITY_DEGRADED;
    }

    /** 对白行占比（0~1）：含引号的非空行 / 全部非空行；无有效行返回 0 */
    public static double ratioOf(String content) {
        if (content == null || content.isBlank()) {
            return 0.0;
        }
        List<String> lines = List.of(content.split("\n"));
        int nonBlank = 0;
        int dialogue = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            nonBlank++;
            for (String q : QUOTES) {
                if (line.contains(q)) {
                    dialogue++;
                    break;
                }
            }
        }
        return nonBlank == 0 ? 0.0 : (double) dialogue / nonBlank;
    }

    /** 对白轮次：引号成对包裹的发言次数 */
    public static int utteranceCount(String content) {
        return utterances(content).size();
    }

    /** 轮次密度 = 轮次 / (有效字/1000)；有效字缺失或为 0 时返回 0（观测层跳过该章） */
    public static double utteranceDensity(int utterances, Integer effectiveChars) {
        if (effectiveChars == null || effectiveChars <= 0) {
            return 0.0;
        }
        return utterances / (effectiveChars / 1000.0);
    }

    /** 判坍缩的最小篇幅：更短的正文样本太小，宁可漏报不误伤。
     *  自 2026-10-09 起同时对轮次密度检测（{@code QualityGate#dialogueDensityCollapse}）开放：
     * 同一份"样本太小就不断言"的口径必须只有一处，否则两条检测会对同一章给出相反结论。 */
    public static final int FORMAT_COLLAPSE_MIN_CHARS = 800;

    public static List<ChapterIssueEntity> checkFormatCollapse(String content, String chapterTypeCode) {
        if (StringUtils.isBlank(content) || content.length() < FORMAT_COLLAPSE_MIN_CHARS) {
            return List.of();
        }
        if (chapterTypeCode != null && "transition".equalsIgnoreCase(chapterTypeCode.trim())) {
            return List.of();
        }
        if (utteranceCount(content) > 0 || ratioOf(content) > 0) {
            return List.of();
        }
        ChapterIssueEntity issue = new ChapterIssueEntity();
        issue.setDimension("aesthetic");
        issue.setSeverity("MINOR");
        issue.setEvidence("全章 " + content.length() + " 字，对白引号出现 0 次（对白轮次=0，行占比=0）");
        issue.setDescription("对白格式坍缩：全章没有任何引号包裹的角色发言，叙述与对白混作一团，"
                + "读者无法分辨谁在说话——疑似生成时把纯文本禁令误扩展到了对白。");
        issue.setSuggestion("为全部角色出声发言补中文双引号“”包裹；注意纯文本禁令只约束 "
                + "Markdown 标记与章节编号等元信息，对白引号合法且必需。");
        return List.of(issue);
    }

    /** 括号包对话的最小篇幅：短样本太小，宁可漏报不误伤（与格式坍缩同口径） */
    private static final int BRACKET_DIALOGUE_MIN_CHARS = 800;

    /** 疑为台词的行：整行以括号起止、且内容长度像一句话（避免误伤"（此时他还不知道）"这类旁注） */
    private static final Pattern BRACKET_LINE =
            Pattern.compile("^\\s*[（(【\\[][^）)】\\]]{6,80}[）)】\\]]\\s*$");

    /** 台词常见的语气/人称线索：命中其一才算"像台词"，纯说明性旁注不算 */
    private static final String[] SPEECH_HINTS = {
            "你", "我", "他", "她", "咱", "们", "吧", "呢", "吗", "啊", "呀", "了", "不", "么",
            "什么", "怎么", "为什", "走", "来", "去", "好", "行", "是", "别", "要", "能", "会"};

    public static List<ChapterIssueEntity> checkBracketDialogue(String content, String chapterTypeCode) {
        if (StringUtils.isBlank(content) || content.length() < BRACKET_DIALOGUE_MIN_CHARS) {
            return List.of();
        }
        if (chapterTypeCode != null && "transition".equalsIgnoreCase(chapterTypeCode.trim())) {
            return List.of();
        }
        List<String> samples = new ArrayList<>();
        for (String line : content.split("\\n")) {
            String trimmed = line.trim();
            if (!BRACKET_LINE.matcher(trimmed).matches()) {
                continue;
            }
            // 去掉括号本身后再判"像不像一句话"，避免括号内残留标点干扰
            String inner = trimmed.replaceAll("^[（(【\\[]|[）)】\\]]$", "");
            if (!containsAny(inner, SPEECH_HINTS)) {
                continue;
            }
            samples.add(StringUtils.abbreviate(trimmed, 40));
            if (samples.size() >= 3) {
                break;
            }
        }
        if (samples.size() < BRACKET_DIALOGUE_MIN_LINES) {
            return List.of();
        }
        ChapterIssueEntity issue = new ChapterIssueEntity();
        issue.setDimension("aesthetic");
        issue.setSeverity("MINOR");
        issue.setEvidence("疑似以括号标注台词的整行 ≥ " + samples.size() + " 处：" + samples);
        issue.setDescription("角色台词用括号包裹而非引号：括号在中文排版里是旁注/舞台提示，"
                + "用它当引号会让读者分不清谁在说话；且这类句子不被计入对白统计，"
                + "会把「对白行占比」「轮次密度」虚报成达标。");
        issue.setSuggestion("把全部人物台词改成成对的中文双引号“”：括号只留给旁注与说明。"
                + "引号内只放实际说出的话，动作与神态放在引号外的叙述里。");
        return List.of(issue);
    }

    /** 括号包对话的判定行数门槛：单行可能是排版偶然，多行才是系统性写法 */
    private static final int BRACKET_DIALOGUE_MIN_LINES = 2;

    /** 引号风格漂移的最小篇幅与最少台词数：样本太小不断言（与其它格式检测同款"宁漏勿滥"） */
    private static final int QUOTE_STYLE_MIN_CHARS = 800;
    private static final int QUOTE_STYLE_MIN_UTTERANCES = 6;

    /** 直角引号（本书主流）与弯引号（排版漂移）的开引号计数口径 */
    private static final char CORNER_OPEN = '「';
    private static final char CURLY_OPEN = '\u201C';

    /**
     * 引号风格漂移检测（机械，MINOR）：本章台词几乎全用弯引号 {@code “”}
     * 而本书主流是直角引号 {@code 「」}。
     *
     * <p><b>为什么单独判</b>：两种引号在中文小说里都合法，{@link #checkFormatCollapse}
     * 与 {@link #utteranceCount} 也都认（正则同时覆盖），所以**任何现有检测都不会报**——
     * 但同一本书里混用是读者一眼可见的排版不一致。实测某书一章整章重写稿：台词引号
     * 几乎全用弯引号，而其余各章全部用直角引号（全书仅此一章例外）——换模型重写时写手带回了
     * 自己的排版习惯，而那一章的前缀预算 99% 打满、文风指纹块被裁掉（风格锚不在场）。
     *
     * <p><b>口径从严（宁漏勿滥）</b>：非 transition 章、篇幅 ≥ {@value #QUOTE_STYLE_MIN_CHARS}、
     * 台词数 ≥ {@value #QUOTE_STYLE_MIN_UTTERANCES}，且弯引号占比 ≥ 80% 才判——
     * 单章里偶用弯引号作强调、或引文旁注不算漂移。MINOR 落债回灌（不触发修订：
     * 为改引号重写整章不成比例；根因在 prompt 侧的风格锚缺失，已由文风指纹提档缓解）。
     *
     * @param mainstreamCurly 本书主流是否为弯引号（由调用方按历史统计给出；null/未知时跳过检测）
     */
    public static List<ChapterIssueEntity> checkQuoteStyleDrift(String content, String chapterTypeCode,
                                                               Boolean mainstreamCurly) {
        if (StringUtils.isBlank(content) || content.length() < QUOTE_STYLE_MIN_CHARS
                || mainstreamCurly == null) {
            return List.of();
        }
        if (chapterTypeCode != null && "transition".equalsIgnoreCase(chapterTypeCode.trim())) {
            return List.of();
        }
        int corner = 0;
        int curly = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == CORNER_OPEN) {
                corner++;
            } else if (c == CURLY_OPEN) {
                curly++;
            }
        }
        int total = corner + curly;
        if (total < QUOTE_STYLE_MIN_UTTERANCES) {
            return List.of();
        }
        // 主流已是弯引号时，直角引号占比高才算漂移（同一口径镜像使用）
        int minority = mainstreamCurly ? corner : curly;
        String minorityMark = mainstreamCurly ? "「」" : "“”";
        String mainstreamMark = mainstreamCurly ? "“”" : "「」";
        if (minority * 5 < total * 4) {
            return List.of();
        }
        ChapterIssueEntity issue = new ChapterIssueEntity();
        issue.setDimension("aesthetic");
        issue.setSeverity("MINOR");
        issue.setEvidence(String.format("全章台词引号 %d 组，其中 %s 占 %d 组（本书主流为 %s）",
                total, minorityMark, minority, mainstreamMark));
        issue.setDescription("引号风格漂移：本章台词改用 " + minorityMark + " 包裹，而本书既有章节主流用 "
                + mainstreamMark + "。两种写法各自都合法，但同一本书内混用是读者一眼可见的排版不一致"
                + "（典型成因：整章由另一模型重写时带回了自己的排版习惯）。");
        issue.setSuggestion("把本章台词引号统一为本书主流的 " + mainstreamMark + "；只改引号本身，"
                + "正文文字与段落结构不要改动。");
        return List.of(issue);
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
