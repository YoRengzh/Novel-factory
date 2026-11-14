package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;


public final class UsedPatternPolicy {

    /** 正文层沿用最近 5 章（与原黑名单窗口一致，保持行为可比、便于对照） */
    public static final int BODY_LOOKBACK = 5;

    /**
     * 规划层回看更长，避免仅看当前段长度时遗漏跨段重复舞台与套路。
     */
    public static final int PLAN_LOOKBACK = 10;

    /** 同一舞台在窗口内出现达到该次数即点名（反复用同一舞台与"每章换场"是一对反向病症） */
    private static final int PLACE_REPEAT_ALERT = 3;

    /** 同一（舞台 × 章型）组合达到该次数即点名 */
    private static final int COMBO_REPEAT_ALERT = 3;

    /** 聚合统计里"典型样例"的摘要截断长度 */
    private static final int SUMMARY_EXCERPT = 80;


    private static final String[] CONFLICT_MARKERS = {
            "抢", "夺", "报复", "揭穿", "打", "骂", "逼", "威胁", "骗", "偷", "拦", "堵",
            "施压", "催促", "栽赃", "恐吓", "争执", "刁难", "挤兑", "挑衅", "找茬"};

    /** 同一角色在窗口内作为冲突发起方达到该章数即点名 */
    private static final int CONFLICT_ROLE_ALERT = 3;

    /** 能力展示频次的告警阈值：按窗口长度的约一半起算（5 章窗口 2 次即告警，10 章窗口 5 次），
     *  且至少为 2，避免一次展示就被当成重复套路。 */
    private static int abilityShowcaseAlertThreshold(int windowSize) {
        return Math.max(2, windowSize / 2);
    }

    private UsedPatternPolicy() {
    }

    /**
     * 渲染【已用情节模式】块；窗口与全书清单**都为空**时返回 null（首发不注入）。
     *
     * 注意判据是"两者皆空"而非"窗口为空"：全书形态清单不受窗口约束，
     * 若沿用旧判据，在"窗口空但历史有展示形态"的场合（新书早期、长跳段）会让最有价值的
     * 跨段去重信息一起消失——而那恰恰是最需要它的时刻。
     *
     * @param beforeChapterNo 只统计章号 小于 该值的章节（本章/本段尚未发生的不能算"已用"）
     * @param lookback        回看章数；&le;0 时用 {@link #BODY_LOOKBACK}
     */
    public static String render(List<ChapterSummaryEntity> summaries, int beforeChapterNo, int lookback) {
        List<ChapterSummaryEntity> window = window(summaries, beforeChapterNo, lookback <= 0 ? BODY_LOOKBACK : lookback);
        List<String> showcaseForms = allShowcaseForms(summaries, beforeChapterNo);
        if (window.isEmpty() && showcaseForms.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n【已用情节模式·重复待查线索】（以下为最近 ")
                .append(window.size()).append(" 章已使用的舞台与情节模式）");

        // 一、模式指纹：按舞台聚合，重复达到阈值即点名
        Map<String, List<ChapterSummaryEntity>> byPlace = new LinkedHashMap<>();
        for (ChapterSummaryEntity s : window) {
            byPlace.computeIfAbsent(placeLabel(s), k -> new ArrayList<>()).add(s);
        }
        sb.append("\n一、舞台复用统计：");
        List<String> alerts = new ArrayList<>();
        for (Map.Entry<String, List<ChapterSummaryEntity>> entry : byPlace.entrySet()) {
            List<ChapterSummaryEntity> group = entry.getValue();
            Map<String, Integer> typeCount = new TreeMap<>();
            for (ChapterSummaryEntity s : group) {
                typeCount.merge(typeLabel(s), 1, Integer::sum);
            }
            sb.append("\n- ").append(entry.getKey()).append(" ×").append(group.size()).append("（");
            boolean first = true;
            for (Map.Entry<String, Integer> tc : typeCount.entrySet()) {
                sb.append(first ? "" : "、").append(tc.getKey()).append(tc.getValue());
                first = false;
            }
            sb.append("；第").append(chapterNumbers(group)).append("章）");
            if (group.size() >= PLACE_REPEAT_ALERT) {
                alerts.add("舞台「" + entry.getKey() + "」在最近 " + window.size() + " 章已用 "
                        + group.size() + " 次");
            }
            if (group.size() >= COMBO_REPEAT_ALERT && typeCount.size() == 1) {
                alerts.add("「" + entry.getKey() + " · " + typeCount.keySet().iterator().next()
                        + "」这一组合已连用 " + group.size() + " 次");
            }
        }

        // 二、内容复述节（逐章"第N章（舞台 · 章型）：摘要节选；目标→结果"）已删除。两条理由，缺一条都不足以删：
        //  ① 重复：逐章摘要节选与【近章摘要】块（2 档、窗口更大）是同一份内容的第二遍；
        //  ② 预算：它是本块唯一随窗口线性膨胀的部分，"目标→结果"处不设长度上限——本块因此在
        //     第 4 档里装不下而被截断。现在本块只剩聚合统计（舞台复用、冲突发起频次、能力展示频次），
        //     篇幅与窗口基本解耦，"模式指纹"才名副其实。

        Map<String, List<ChapterSummaryEntity>> conflictByRole = new LinkedHashMap<>();
        Map<String, String> sampleByRole = new LinkedHashMap<>();
        for (ChapterSummaryEntity s : window) {
            if (s.getCharacterBeats() == null) {
                continue;
            }
            for (ChapterSummaryEntity.CharacterBeat beat : s.getCharacterBeats()) {
                if (beat == null || StringUtils.isBlank(beat.getName())) {
                    continue;
                }
                String text = StringUtils.defaultString(beat.getGoal())
                        + StringUtils.defaultString(beat.getDecision());
                if (!containsConflictMarker(text)) {
                    continue;
                }
                String role = beat.getName().trim();
                List<ChapterSummaryEntity> seen = conflictByRole.computeIfAbsent(role, k -> new ArrayList<>());
                if (seen.isEmpty() || !seen.get(seen.size() - 1).getChapterNo().equals(s.getChapterNo())) {
                    seen.add(s);
                }
                sampleByRole.putIfAbsent(role, StringUtils.defaultString(beat.getGoal()).trim());
            }
        }
        if (!conflictByRole.isEmpty()) {
            sb.append("\n三、角色冲突发起频次（同一角色反复当「麻烦制造者」，是最典型的套路化指纹）：");
            for (Map.Entry<String, List<ChapterSummaryEntity>> entry : conflictByRole.entrySet()) {
                List<ChapterSummaryEntity> seen = entry.getValue();
                sb.append("\n- ").append(entry.getKey()).append(" ×").append(seen.size())
                        .append("（第").append(chapterNumbers(seen)).append("章）");
                String sample = sampleByRole.get(entry.getKey());
                if (StringUtils.isNotBlank(sample)) {
                    sb.append("；典型：").append(excerpt(sample));
                }
                if (seen.size() >= CONFLICT_ROLE_ALERT) {
                    alerts.add("角色「" + entry.getKey() + "」在最近 " + window.size() + " 章有 "
                            + seen.size() + " 章作为冲突发起方");
                }
            }
            sb.append("\n被点名的角色与冲突形态仅供核对无意义重复；因果必要的连续行动可以保留，不强制换对手或让人物退出。");
        }

        // 四、能力/早慧展示频次 补实测）：「主角微动作展示 → 旁人注意/评价」
        // 这一抽象结构连续复用（5 章 5 次，每次只换载体：蘸水画圈/盯钢琴/翻数字页），是早慧/重生题材
        // 最典型的套路化指纹。与第三节同理由：抽象结构重复抓不了文本相似度，只能靠摘要的结构化标注
        //（abilityShowcased/abilityDisplayForm）——标注是"展示时刻"的判定，不是关键词匹配。
        List<ChapterSummaryEntity> showcases = new ArrayList<>();
        for (ChapterSummaryEntity s : window) {
            if (Boolean.TRUE.equals(s.getAbilityShowcased())) {
                showcases.add(s);
            }
        }
        if (!showcases.isEmpty()) {
            sb.append("\n四、能力/早慧展示频次（「主角微动作展示 → 旁人注意/评价」结构的重复，")
                    .append("是早慧题材最典型的套路指纹）：");
            for (ChapterSummaryEntity s : showcases) {
                sb.append("\n- 第").append(s.getChapterNo()).append("章");
                if (StringUtils.isNotBlank(s.getAbilityDisplayForm())) {
                    sb.append("（").append(excerpt(s.getAbilityDisplayForm())).append("）");
                }
            }
            int threshold = abilityShowcaseAlertThreshold(window.size());
            if (showcases.size() >= threshold) {
                sb.append("\n最近 ").append(window.size()).append(" 章已有 ").append(showcases.size())
                        .append(" 章安排能力展示，次数只是待查线索；核对具体场景是否空转，不据次数强制藏拙、队友高光或失败。");
                alerts.add("能力/早慧展示在最近 " + window.size() + " 章中已出现 " + showcases.size() + " 次");
            }
        }

        // 四之二、**全书已用展示形态清单**：
        // 第四节的窗口统计抓不到跨段重复——实测两次"主角式解题震惊在场权威"
        // 的高光章相隔 9 章，超出正文层 5 章窗口，机制看不到，但读者能看出重复。故额外把**全书**的展示形态汇总点名，让模型知道哪些路数已经用过。
        List<String> allForms = showcaseForms;
        if (!allForms.isEmpty()) {
            sb.append("\n四之二、全书已用过的能力展示形态（用于判断具体重复，不构成禁用清单）：");
            for (String form : allForms) {
                sb.append("\n- ").append(excerpt(form));
            }
            sb.append("\n重复若仍有新的意义或符合人物习惯可以保留，不为换路数临时制造隐藏实力、队友高光或人物失败。");
        }

        sb.append("\n本段按计划与人物因果交付。统计只提示核对重复，不强制更换舞台、解决方式、视角或制造新的冲突。");
        if (!alerts.isEmpty()) {
            sb.append("\n⚠ 重复点名：");
            sb.append(String.join("；", alerts)).append("。");
        }
        return sb.toString();
    }

    /**
     * **全书**（本章之前）已用过的能力展示形态清单，去重后按章号升序。
     *
     * 为什么不能用窗口：窗口统计答的是"最近有没有密集展示"，而跨段重复（两章同类高光相隔 9 章）需要的是"历史上用过哪些路数"——两者是不同的问法。
     * 形态短语本身很短（8-16 字），全书累计也只有几十条，直接列给模型比让它自己回忆可靠。
     *
     * 去重按短语原文：同一形态在不同章重复标注（措辞完全相同）只列一次，
     * 因为要点名的是"路数"而不是"次数"（次数由窗口统计负责）。
     */
    private static List<String> allShowcaseForms(List<ChapterSummaryEntity> summaries, int beforeChapterNo) {
        List<String> forms = new ArrayList<>();
        if (summaries == null) {
            return forms;
        }
        for (ChapterSummaryEntity s : summaries) {
            if (s == null || s.getChapterNo() == null || s.getChapterNo() >= beforeChapterNo) {
                continue;
            }
            if (!Boolean.TRUE.equals(s.getAbilityShowcased())) {
                continue;
            }
            String form = s.getAbilityDisplayForm();
            if (StringUtils.isBlank(form)) {
                continue;
            }
            String trimmed = form.trim();
            if (!forms.contains(trimmed)) {
                forms.add(trimmed);
            }
        }
        return forms;
    }

    /** 节拍文本是否含冲突动词（结构化标签，不依赖语义理解） */
    private static boolean containsConflictMarker(String text) {        if (StringUtils.isBlank(text)) {
            return false;
        }
        for (String marker : CONFLICT_MARKERS) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 只保留章号小于 beforeChapterNo 的章节，取最近 lookback 章 */
    private static List<ChapterSummaryEntity> window(List<ChapterSummaryEntity> summaries,
                                                     int beforeChapterNo, int lookback) {
        List<ChapterSummaryEntity> eligible = new ArrayList<>();
        if (summaries == null) {
            return eligible;
        }
        for (ChapterSummaryEntity s : summaries) {
            if (s == null || s.getChapterNo() == null) {
                continue;
            }
            if (s.getChapterNo() < beforeChapterNo) {
                eligible.add(s);
            }
        }
        int from = Math.max(0, eligible.size() - lookback);
        return new ArrayList<>(eligible.subList(from, eligible.size()));
    }

    /** 舞台标签：与地点轨迹同一口径（placePoint 优先，timePoint 回退），取不到时归为"（未记录地点）" */
    private static String placeLabel(ChapterSummaryEntity summary) {
        String place = PlaceTrajectoryPolicy.placeOf(summary);
        return StringUtils.isBlank(place) ? "（未记录地点）" : place;
    }

    private static String typeLabel(ChapterSummaryEntity summary) {
        return StringUtils.isBlank(summary.getChapterType()) ? "normal" : summary.getChapterType();
    }

    private static String chapterNumbers(List<ChapterSummaryEntity> group) {
        Set<Integer> nos = new LinkedHashSet<>();
        for (ChapterSummaryEntity s : group) {
            nos.add(s.getChapterNo());
        }
        return String.join("/", nos.stream().map(String::valueOf).toList());
    }

    private static String excerpt(String summary) {
        if (StringUtils.isBlank(summary)) {
            return "（无摘要）";
        }
        String trimmed = summary.trim();
        return trimmed.length() > SUMMARY_EXCERPT ? trimmed.substring(0, SUMMARY_EXCERPT) + "…" : trimmed;
    }
}
