package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** 有界的套路风险提示，不把关键词命中伪称为语义审查，也不阻断正文生成。 */
public final class NarrativeProgressionPolicy {

    private static final int HISTORY_LIMIT = 10;
    private static final int MAX_CYCLE_CHAPTERS = 3;
    private static final Pattern PROBLEM = Pattern.compile("发现|遇到|遭遇|面临|出现|质疑|挑衅|刁难|难题|故障|危机");
    private static final Pattern SOLUTION = Pattern.compile("解决|破解|击败|打败|化解|摆平|修复|修好|答出|解出|碾压|打脸|装逼|展示|展露");
    private static final Pattern APPLAUSE = Pattern.compile("震惊|惊叹|惊讶|赞叹|刮目相看|折服|喝彩|目瞪口呆|佩服");
    private static final Pattern NEGATED = Pattern.compile("(?:不|未|没|无法|拒绝|避免|禁止)[\\p{IsHan}]{0,3}$");

    private NarrativeProgressionPolicy() {
    }

    public static String guidance() {
        return """

                【因果推进与反套路】
                - 结构模板及参考资料只是诊断工具，不是必须逐项执行的剧情指令；作者明确指定的结构优先。不得每章、每段或每弧重新套一轮相同节拍，也不按固定比例制造高潮与悬念。
                - 先从人物欲望、已有承诺、信息差与资源约束推导选择，再写选择改变了什么局面。下一步须承接已经发生的后果或预先成立的独立动机，不能只靠空降新敌人、新故障重启任务。
                - 检查近章和本次规划中的「问题来源→关键应对→结果反馈」。换地点、对手、道具、剧情线标签或能力名称，不等于换了戏剧结构；避免反复「发现问题→主角展示能力轻松解决→旁人震惊→又来一个问题」。
                - 每条章级推进写出本章新增的事实、决定或关系/资源变化，并让有后续作用的变化留存。可有顺利成功、安静兑现和日常相处，不要求每章吃亏、反转或埋新谜，也不得机械轮换输赢、强塞代价来假装多样。
                - 若多条推进可以互换顺序而不影响人物选择和阶段结果，重建它们的因果承接；独立支线不必强连，但不得只是重复同一种解题与围观反应。
                """;
    }

    /** 只检查明确复述，以及最多三章闭合的「问题→解围→喝彩」是否重复。 */
    public static List<String> issues(StageBlueprintEntity blueprint, List<ChapterSummaryEntity> summaries) {
        if (blueprint == null || blueprint.getStartChapter() == null || blueprint.getMainLineByChapter() == null) {
            return List.of();
        }
        int start = blueprint.getStartChapter();
        int end = blueprint.getEndChapter() == null ? Integer.MAX_VALUE : blueprint.getEndChapter();
        TreeMap<Integer, String> history = new TreeMap<>();
        if (summaries != null) {
            for (ChapterSummaryEntity summary : summaries) {
                if (summary != null && summary.getChapterNo() != null && summary.getChapterNo() < start
                        && StringUtils.isNotBlank(summary.getSummary())) {
                    history.put(summary.getChapterNo(), summary.getSummary());
                }
            }
        }
        while (history.size() > HISTORY_LIMIT) {
            history.pollFirstEntry();
        }
        TreeMap<Integer, String> timeline = new TreeMap<>(history);
        for (StageBlueprintEntity.MainLineBeat beat : blueprint.getMainLineByChapter()) {
            if (beat != null && beat.getChapterNo() != null && beat.getChapterNo() >= start
                    && beat.getChapterNo() <= end && StringUtils.isNotBlank(beat.getAdvance())) {
                timeline.put(beat.getChapterNo(), beat.getAdvance());
            }
        }
        List<String> issues = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (Map.Entry<Integer, String> entry : timeline.entrySet()) {
            String text = normalizeForRepetition(entry.getValue());
            if (text.length() < 8) {
                continue;
            }
            Integer previous = seen.putIfAbsent(text, entry.getKey());
            if (previous != null && entry.getKey() >= start) {
                issues.add("第 " + previous + "、" + entry.getKey() + " 章推进去掉标签与标点后完全相同，须写出不同的局面变化");
                break;
            }
        }

        List<Map.Entry<Integer, String>> entries = new ArrayList<>(timeline.entrySet());
        List<String> cycles = new ArrayList<>();
        boolean touchesPlan = false;
        for (int i = 0; i < entries.size(); i++) {
            StringBuilder text = new StringBuilder();
            for (int j = i; j < entries.size() && j < i + MAX_CYCLE_CHAPTERS; j++) {
                if (j > i && entries.get(j).getKey() != entries.get(j - 1).getKey() + 1) {
                    break;
                }
                text.append(entries.get(j).getValue()).append('；');
                if (isShowcaseCycle(text.toString())) {
                    cycles.add("第 " + entries.get(i).getKey() + "-" + entries.get(j).getKey() + " 章");
                    touchesPlan |= entries.get(j).getKey() >= start;
                    i = j; // 不重叠计数，同一个高光不能算成两个循环。
                    break;
                }
            }
        }
        if (cycles.size() >= 2 && touchesPlan) {
            issues.add("套路风险（关键词启发式，需结合剧情核对）：" + String.join("、", cycles)
                    + "重复「问题→能力解围→旁人认可」；保留必要爽点，重构重复轮次的应对方式与持续后果，不得仅换人名/场景/标签");
        }
        return issues;
    }

    private static boolean isShowcaseCycle(String text) {
        int problemEnd = nextAffirmed(PROBLEM, text, 0);
        int solutionEnd = problemEnd < 0 ? -1 : nextAffirmed(SOLUTION, text, problemEnd);
        return solutionEnd >= 0 && nextAffirmed(APPLAUSE, text, solutionEnd) >= 0;
    }

    private static int nextAffirmed(Pattern pattern, String text, int from) {
        var matcher = pattern.matcher(text);
        matcher.region(from, text.length());
        while (matcher.find()) {
            String prefix = text.substring(Math.max(0, matcher.start() - 6), matcher.start());
            if (!NEGATED.matcher(prefix).find()) {
                return matcher.end();
            }
        }
        return -1;
    }

    /** 仅去排版噪声，保留小数点、正负号、百分号等可能改变事实含义的符号。 */
    public static String normalizeForRepetition(String text) {
        return text == null ? "" : text.replaceFirst("^\\s*【[^】]+】", "")
                .replaceAll("[\\s,;:!?，。；：！？、‘’“”「」『』（）()]+", "");
    }
}
