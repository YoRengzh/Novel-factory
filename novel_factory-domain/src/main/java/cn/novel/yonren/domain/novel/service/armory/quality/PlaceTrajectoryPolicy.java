package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;


public final class PlaceTrajectoryPolicy {

    /** 轨迹回看窗口（章）：与密度反馈的 DENSITY_LOOKBACK 同一量级，便于规划层横向对照 */
    public static final int TRAJECTORY_WINDOW = 10;

    /** 单个规划段建议新增地点上限：超出说明在"赶路"，应改用一两句过渡带过 */
    public static final int NEW_PLACE_BUDGET_PER_SEGMENT = 3;

    /** 同一地点建议连续承载的章数下限：低于此值即为"打点式换场" */
    public static final int MIN_CHAPTERS_PER_PLACE = 2;

    /** 地点与时间在 timePoint 里的常见分隔符 */
    private static final Pattern SEG_SPLIT = Pattern.compile("[，,；;]");

    private PlaceTrajectoryPolicy() {
    }

    /**
     * 取某章的地点：优先 {@code placePoint}，缺失时从 {@code timePoint} 回退提取。
     * 两者都取不到返回 null（不参与统计，宁可漏报不误伤）。
     */
    public static String placeOf(ChapterSummaryEntity summary) {
        if (summary == null) {
            return null;
        }
        if (StringUtils.isNotBlank(summary.getPlacePoint())) {
            return summary.getPlacePoint().trim();
        }
        return extractFromTimePoint(summary.getTimePoint());
    }

    /** 老数据回退口径：按分隔符切段取最后一段（长度 ≥2 才采用），两端口径见类注释 */
    static String extractFromTimePoint(String timePoint) {
        if (StringUtils.isBlank(timePoint)) {
            return null;
        }
        String trimmed = timePoint.trim();
        String[] segs = SEG_SPLIT.split(trimmed);
        String last = null;
        for (int i = segs.length - 1; i >= 0; i--) {
            String seg = segs[i].trim();
            if (!seg.isEmpty()) {
                last = seg;
                break;
            }
        }
        if (last == null || last.length() < 2) {
            return trimmed.length() >= 2 ? trimmed : null;
        }
        return last;
    }

    /**
     * 是否为"每章一个新地点"：窗口内不同地点数达到窗口长度本身。
     * 取这个阈值而非中位数之类的相对值——它是一个有明确语义的量：
     * 窗口长度 = 每章都换新场景，不再是"密度偏高"而是"完全没有复用"。
     */
    public static boolean isChurnAlarming(int distinctInWindow, int windowSize) {
        return windowSize > 0 && distinctInWindow >= windowSize;
    }

    /** 最近 window 章的 (章号, 地点) 序列，按章号升序；取不到地点的章被跳过 */
    public static List<Map.Entry<Integer, String>> recentTrajectory(List<ChapterSummaryEntity> summaries, int window) {
        List<Map.Entry<Integer, String>> rows = new ArrayList<>();
        if (summaries == null || summaries.isEmpty() || window <= 0) {
            return rows;
        }
        int from = Math.max(0, summaries.size() - window);
        for (int i = from; i < summaries.size(); i++) {
            ChapterSummaryEntity summary = summaries.get(i);
            if (summary == null || summary.getChapterNo() == null) {
                continue;
            }
            String place = placeOf(summary);
            if (StringUtils.isNotBlank(place)) {
                rows.add(Map.entry(summary.getChapterNo(), place));
            }
        }
        return rows;
    }

    /**
     * 渲染【地点轨迹】块（回灌规划层）；无任何地点信息返回 null（老数据没 timePoint 时不注入）。
     *
     * 无论是否超预算都会注入：规划层此前完全看不到地点信息，
     * 先把它摆出来才有"复用已建立地点"的可能；超预算时再追加一句警示。
     */
    public static String renderTrajectory(List<ChapterSummaryEntity> summaries) {
        List<Map.Entry<Integer, String>> rows = recentTrajectory(summaries, TRAJECTORY_WINDOW);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> row : rows) {
            freq.merge(row.getValue(), 1, Integer::sum);
        }
        int distinct = freq.size();
        int fromChapter = rows.get(0).getKey();
        int toChapter = rows.get(rows.size() - 1).getKey();

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n【地点轨迹】最近 ").append(rows.size()).append(" 章的实际地点（第 ")
                .append(fromChapter).append("-").append(toChapter).append(" 章）：");
        for (Map.Entry<Integer, String> row : rows) {
            sb.append("\n- 第").append(row.getKey()).append("章：").append(row.getValue());
        }
        sb.append("\n可复用地点（出现 ≥2 章的，规划层优先在这里展开）：");
        StringBuilder reused = new StringBuilder();
        for (Map.Entry<String, Integer> e : freq.entrySet()) {
            if (e.getValue() >= MIN_CHAPTERS_PER_PLACE) {
                reused.append(reused.length() == 0 ? "" : "、").append(e.getKey()).append("×").append(e.getValue());
            }
        }
        sb.append(reused.length() > 0 ? reused.toString() : "（无——窗口内每个地点都只出现过一次）");
        sb.append("\n本段规划的地点要求：");
        sb.append("\n1. 本段新增地点 ≤ ").append(NEW_PLACE_BUDGET_PER_SEGMENT)
                .append(" 个，其余章节在已建立的地点里展开；");
        sb.append("\n2. 同一地点连续承载至少 ").append(MIN_CHAPTERS_PER_PLACE)
                .append(" 章再迁移，严禁每章换新场景；");
        sb.append("\n3. 若剧情需要跨地移动，用一两句过渡带过，不要为每个途经点单独开一章。");
        if (isChurnAlarming(distinct, rows.size())) {
            sb.append("\n⚠ 警示：上述 ").append(rows.size()).append(" 章出现了 ").append(distinct)
                    .append(" 个不同地点——**每一章都在换新场景**，地点密度已远超合理范围，"
                            + "读者无法建立空间感。本段必须显著回收地点，优先复用上面列出的地点。");
        }
        sb.append("\n命名要求：写同一处地点时必须沿用上面出现过的名字，不得另起叫法"
                + "（本块按字面统计，不同叫法会被计为不同地点）。");
        return sb.toString();
    }
}
