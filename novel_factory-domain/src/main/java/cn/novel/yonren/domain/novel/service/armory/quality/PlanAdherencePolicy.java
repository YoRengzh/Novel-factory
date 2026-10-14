package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;


public final class PlanAdherencePolicy {

    /** 覆盖率预警阈值：低于该值时给审校注入加审提示 */
    public static final double LOW_COVERAGE_THRESHOLD = 0.6;
    /** 关键事件核心要素命中的最短公共子串长度（与修订闸门同口径） */
    private static final int KEY_EVENT_MIN_MATCH = 4;

    /**
     * 关键事件覆盖率：正文中可检出（LCS ≥ 4 字）的事件数 / 有效事件总数；无关键事件视为全覆盖
     */
    public static double coverage(List<String> keyEvents, String content) {
        List<String> events = validEvents(keyEvents);
        if (events.isEmpty()) {
            return 1.0;
        }
        String normalizedContent = content == null ? "" : content.replaceAll("\\s+", "");
        long covered = events.stream().filter(event -> covered(normalizedContent, event)).count();
        return covered / (double) events.size();
    }

    /** 未检出（疑似未覆盖）的关键事件清单 */
    public static List<String> uncoveredEvents(List<String> keyEvents, String content) {
        List<String> events = validEvents(keyEvents);
        if (events.isEmpty()) {
            return List.of();
        }
        String normalizedContent = content == null ? "" : content.replaceAll("\\s+", "");
        return events.stream().filter(event -> !covered(normalizedContent, event)).toList();
    }

    /**
     * 覆盖率低于阈值时渲染审校加审提示块（作为 audit guard 注入）；事件为空或达标返回 null。
     * 措辞明确"机械词面匹配可能误报"——审校已用不同措辞覆盖的不得捏造问题
     */
    public static String renderLowCoverageHint(List<String> keyEvents, String content) {
        List<String> events = validEvents(keyEvents);
        if (events.isEmpty()) {
            return null;
        }
        List<String> uncovered = uncoveredEvents(events, content);
        if (uncovered.isEmpty() || 1 - (double) uncovered.size() / events.size() >= LOW_COVERAGE_THRESHOLD) {
            return null;
        }
        StringBuilder sb = new StringBuilder("【计划覆盖预警】机械词面核对发现本章正文疑似未覆盖 ")
                .append(uncovered.size()).append("/").append(events.size()).append(" 条关键事件：\n");
        for (String event : uncovered) {
            sb.append("- ").append(event).append("\n");
        }
        sb.append("请重点核对以上事件：若确属缺失，按 BLOCKING（关键事件缺失）上报并给出去向；")
                .append("若正文已用不同措辞落实了该事件（本核对按词面匹配，可能误报），忽略本条，严禁为凑数捏造问题。");
        return sb.toString();
    }

    /**
     * 判断正文是否覆盖了某条关键事件：先剥离括号注释（"回收第N章埋设的XX"类执行提示，不要求字面落入正文），
     * 再计算正文与关键事件的「最长公共连续子串」；≥ KEY_EVENT_MIN_MATCH 即视为覆盖。
     * 关键事件本身极短（不足阈值）时退化为整段 contains，保留严格语义。
     * （与修订采纳闸门同口径；ChapterReviseService 委托本方法）
     */
    public static boolean covered(String normalizedContent, String keyEvent) {
        String event = stripParenthetical(keyEvent).replaceAll("\\s+", "");
        if (event.isEmpty()) {
            return true;
        }
        if (event.length() < KEY_EVENT_MIN_MATCH) {
            return normalizedContent.contains(event);
        }
        return longestCommonSubstring(normalizedContent, event) >= KEY_EVENT_MIN_MATCH;
    }

    /** 剥离全角/半角括号及其内容（括号内是"回收第N章埋设的XX"类执行提示，不要求字面落入正文） */
    static String stripParenthetical(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[（(][^（）()]*[)）]", "");
    }

    /**
     * 最长公共连续**片段文本**（一维滚动 DP，O(m*n)）：供"跨章场景重演"这类需要把
     * 重合片段回显给模型的判据使用（只报长度对模型没有行动价值，报片段才知道改哪里）。
     */
    public static String longestCommonSubstringText(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return "";
        }
        int n = b.length();
        int[] dp = new int[n + 1];
        int max = 0;
        int maxEnd = 0;
        for (int i = 1; i <= a.length(); i++) {
            int prev = 0;
            for (int j = 1; j <= n; j++) {
                int temp = dp[j];
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    dp[j] = prev + 1;
                    if (dp[j] > max) {
                        max = dp[j];
                        maxEnd = i;
                    }
                } else {
                    dp[j] = 0;
                }
                prev = temp;
            }
        }
        return max == 0 ? "" : a.substring(maxEnd - max, maxEnd);
    }

    /** 最长公共连续子串长度（一维滚动 DP，O(m*n)，正文数千字 × 关键事件几十字，量级可忽略）。伏笔揭示章豁免匹配复用 */
    public static int longestCommonSubstring(String a, String b) {
        return longestCommonSubstringText(a, b).length();
    }

    private static List<String> validEvents(List<String> keyEvents) {
        if (keyEvents == null || keyEvents.isEmpty()) {
            return List.of();
        }
        List<String> events = new ArrayList<>();
        for (String event : keyEvents) {
            if (StringUtils.isNotBlank(event)) {
                events.add(event);
            }
        }
        return events;
    }

    private PlanAdherencePolicy() {
    }
}
