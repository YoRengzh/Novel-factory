package cn.novel.yonren.domain.novel.service.armory.writer;

import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.service.armory.memory.ChapterMemoryService;
import cn.novel.yonren.domain.novel.service.armory.memory.StoryMemoryService;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** DIRECT 路径的按需装配：不建立全量规划前缀，只准备正文会实际消费的语义材料。 */
public final class WriterContextSupport {

    private static final int INITIAL_SETTING_CLIP_CHARS = 260;
    private static final int DIRECT_MEMORY_RECALL_CHARS = 1000;
    private static final int DIRECT_RECENT_SYNOPSIS_CHARS = 700;
    private static final int DIRECT_RECENT_SYNOPSIS_ITEM_CHARS = 220;
    private static final int DIRECT_RECENT_SYNOPSIS_CHAPTERS = 3;
    private static final String RECENT_SYNOPSIS_HEADER =
            "【近章前情】续接已发生的交涉与结果。摘要供写手衔接，各人物只能使用自己已获知的信息。";

    private WriterContextSupport() {
    }

    public static String edgeState(ChapterMemoryService memory, List<cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity> summaries) {
        String edge = memory.renderLedgerEdgeState(summaries);
        if (StringUtils.isBlank(edge)) {
            return "";
        }
        // 末态红线是程序对齐标签，正文模型只需事实和约束，不需要"第4档前缀"等内部措辞。
        return edge.replace("==== 以下为账本末态红线（截至上一章结束时的既成事实），正文开场的人物位置、持有物、修为必须与其一致；位置/持有物变化必须有过渡描写，严禁无过渡跳变 ====",
                "【截至上一章末的物理状态】人物位置、持有物、修为须连续；变化须有实际过程。");
    }

    /** 伏笔只保留与本章计划有文本关联的条目；无关联时不用全量账本占据正文注意力。 */
    public static String relevantContinuity(ChapterMemoryService memory,
                                            List<cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity> summaries,
                                            ChapterPlanItemEntity item) {
        String plan = planText(item);
        List<String> related = memory.buildPendingForeshadowDetailed(summaries).stream()
                .filter(p -> p != null && StringUtils.isNotBlank(p.content()))
                .filter(p -> related(plan, p.content()))
                .limit(4)
                .map(p -> "- " + p.content().strip())
                .toList();
        if (related.isEmpty()) {
            return "";
        }
        return "【与本章有关的未收线索】只在场景实际触及时处理，不为回收而增加事件；本章未兑现的线索仍保持未决。\n"
                + String.join("\n", related);
    }

    /**
     * 在场人物的物理末态。角色账本含情绪/认知等混合文本，不能假装这里只有物理事实；
     * 因此只取当前人物的最新条目并标明它是"开场前状态"，由场内新证据更新，不注入历史证据和章号。
     */
    public static String castStates(ChapterMemoryService memory,
                                    List<cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity> summaries,
                                    ChapterPlanItemEntity item) {
        if (item == null) return "";
        Set<String> cast = new LinkedHashSet<>();
        if (item.getCharacters() != null) {
            item.getCharacters().stream().filter(StringUtils::isNotBlank).forEach(cast::add);
        }
        if (item.getScenes() != null) {
            item.getScenes().stream().filter(Objects::nonNull)
                    .forEach(scene -> scene.characters().stream().filter(StringUtils::isNotBlank).forEach(cast::add));
        }
        List<String> states = memory.buildCharacterLedger(summaries).stream()
                .filter(entry -> cast.contains(entry.getName()))
                .filter(entry -> StringUtils.isNotBlank(entry.getStatus()))
                .limit(8)
                .map(entry -> entry.isReversalSuspect()
                        ? "- " + entry.getName() + "：生死记录存在冲突，复活尚未确认；不得作为已恢复行动能力的依据"
                        : "- " + entry.getName() + "：" + entry.getStatus().strip())
                .toList();
        if (states.isEmpty()) return "";
        return "【本章开场前的在场人物状态】场内发生的新变化以实际正文为准，不把这份开场状态反复写回。\n"
                + String.join("\n", states);
    }

    /** 供给 DIRECT 写手的近几章事件摘要，避免跨章忘记已发生过的对话、判定与交涉。 */
    public static String recentSynopsis(List<ChapterSummaryEntity> summaries, int currentChapterNo) {
        if (summaries == null || summaries.isEmpty() || currentChapterNo <= 1) {
            return "";
        }
        List<ChapterSummaryEntity> recent = summaries.stream()
                .filter(s -> s != null && s.getChapterNo() != null
                        && s.getChapterNo() < currentChapterNo && StringUtils.isNotBlank(s.getSummary()))
                .sorted(Comparator.comparingInt(ChapterSummaryEntity::getChapterNo))
                .toList();
        if (recent.isEmpty()) {
            return "";
        }
        int start = Math.max(0, recent.size() - DIRECT_RECENT_SYNOPSIS_CHAPTERS);
        List<String> lines = new ArrayList<>();
        for (int i = start; i < recent.size(); i++) {
            ChapterSummaryEntity item = recent.get(i);
            String clipped = clipSynopsis(item.getSummary());
            lines.add("- 第" + item.getChapterNo() + "章：" + clipped);
        }
        while (lines.size() > 1 && formattedSynopsisLength(lines) > DIRECT_RECENT_SYNOPSIS_CHARS) {
            lines.remove(0);
        }
        return RECENT_SYNOPSIS_HEADER + "\n" + String.join("\n", lines);
    }

    private static int formattedSynopsisLength(List<String> lines) {
        int linesLength = lines.stream().mapToInt(String::length).sum() + Math.max(0, lines.size() - 1);
        return RECENT_SYNOPSIS_HEADER.length() + 1 + linesLength;
    }

    private static String clipSynopsis(String raw) {
        String text = raw.strip();
        if (text.length() <= DIRECT_RECENT_SYNOPSIS_ITEM_CHARS) {
            return text;
        }
        // 多数摘要按事件顺序写，末尾优先保留交割、尚未处理的事与离场状态。
        String opening = clipAtBoundary(text, DIRECT_RECENT_SYNOPSIS_ITEM_CHARS / 3);
        int tailBudget = DIRECT_RECENT_SYNOPSIS_ITEM_CHARS - opening.length() - 3;
        String tail = text.substring(text.length() - tailBudget);
        for (int i = 0; i < tail.length() / 2; i++) {
            if ("。！？；\n".indexOf(tail.charAt(i)) >= 0) {
                tail = tail.substring(i + 1).stripLeading();
                break;
            }
        }
        return opening + " … " + tail;
    }

    /** 首章没有历史状态，需要最小世界与人物设定；续写不重复整份大纲。 */
    public static String initialFacts(cn.novel.yonren.domain.novel.model.entity.StoryContextEntity story,
                                      List<cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity> summaries) {
        if (story == null || summaries != null && !summaries.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder("【开篇必要设定】只使用与本章场景有关的部分，不在开头集中讲解。\n");
        appendClipped(text, "世界与处境", story.getWorldSetting());
        appendClipped(text, "主人公", story.getProtagonist());
        appendClipped(text, "主要人物", story.getCharacterProfiles());
        return text.length() > 30 ? text.toString() : "";
    }

    public static String memories(List<StoryMemoryService.RecallHit> hits) {
        return memories(hits, null);
    }

    /** 过滤卷级/设定块防终局大纲剧透，并跳过上一章（已由物理状态与衔接尾段供给），只保留跨章旧事。 */
    public static String memories(List<StoryMemoryService.RecallHit> hits, Integer currentChapterNo) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder("【相关旧事·只在当前场需要时使用】\n");
        int chars = 0;
        for (var hit : hits) {
            if (hit == null || StringUtils.isBlank(hit.text())) continue;
            if (StoryMemoryService.KIND_VOLUME.equals(hit.kind())
                    || StoryMemoryService.KIND_BIBLE.equals(hit.kind())) {
                continue;
            }
            if (currentChapterNo != null && hit.chapterNo() != null
                    && hit.chapterNo() >= currentChapterNo - 1) {
                continue;
            }
            String line = "- " + hit.text().strip().replace('\n', '；') + "\n";
            if (chars + line.length() > DIRECT_MEMORY_RECALL_CHARS) continue;
            text.append(line);
            chars += line.length();
        }
        return chars == 0 ? "" : text.toString();
    }

    private static void appendClipped(StringBuilder text, String label, String value) {
        if (StringUtils.isNotBlank(value)) {
            text.append(label).append("：").append(clipAtBoundary(value, INITIAL_SETTING_CLIP_CHARS)).append('\n');
        }
    }

    static String clipAtBoundary(String raw, int maxChars) {
        String text = raw.strip();
        if (text.length() <= maxChars) {
            return text;
        }
        String head = text.substring(0, maxChars);
        int cut = -1;
        for (char delim : new char[]{'\n', '。', '；', '！', '？'}) {
            int idx = head.lastIndexOf(delim);
            if (idx > cut) {
                cut = idx;
            }
        }
        if (cut >= maxChars / 2) {
            boolean newline = head.charAt(cut) == '\n';
            return text.substring(0, newline ? cut : cut + 1).strip();
        }
        return text.substring(0, Math.max(1, maxChars - 1)).strip() + "…";
    }

    private static String planText(ChapterPlanItemEntity item) {
        if (item == null) return "";
        StringBuilder text = new StringBuilder(StringUtils.defaultString(item.getGoal()))
                .append('\n').append(StringUtils.defaultString(item.getEndingHook()));
        if (item.getKeyEvents() != null) text.append('\n').append(String.join("\n", item.getKeyEvents()));
        if (item.getScenes() != null) {
            item.getScenes().stream().filter(java.util.Objects::nonNull).forEach(scene -> text.append('\n')
                    .append(StringUtils.defaultString(scene.intention())).append('\n')
                    .append(StringUtils.defaultString(scene.resistance())).append('\n')
                    .append(StringUtils.defaultString(scene.outcome())).append('\n')
                    .append(StringUtils.defaultString(scene.location())));
        }
        return text.toString();
    }

    /** 避免只用完整包含匹配：计划常把旧线索缩写成核心名词。 */
    private static boolean related(String plan, String pending) {
        String normalizedPlan = plan.replaceAll("[\\s，。；：、“”‘’（）()《》]", "");
        String normalized = pending.replaceAll("[\\s，。；：、“”‘’（）()《》]", "");
        if (normalized.length() >= 4 && normalizedPlan.contains(normalized)) return true;
        for (String token : pending.split("[\\s，。；：、“”‘’（）()《》]")) {
            if (token.length() >= 4 && plan.contains(token)) return true;
        }
        return false;
    }
}
