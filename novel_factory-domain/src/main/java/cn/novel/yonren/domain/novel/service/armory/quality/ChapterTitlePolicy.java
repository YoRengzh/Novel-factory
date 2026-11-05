package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.types.utils.ChapterTitleNormalizer;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class ChapterTitlePolicy {

    /** 注入规划 prompt 的已用标题数量上限（按最近使用倒序取；超出部分只报计数） */
    public static final int RENDER_LIMIT = 300;

    /** 等价键需剔除的字符：空白 + 中英文常见标点（标题里的标点不承载区分语义） */
    private static final Pattern IGNORED_CHARS =
            Pattern.compile("[\\s\\u3000，,。.、；;：:！!？?…·—\\-－「」『』“”\"'‘’《》〈〉()（）\\[\\]【】]+");

    private ChapterTitlePolicy() {
    }

    /**
     * 标题等价键：剥编号前缀 → 剔空白与标点。
     * 归零后为空（如标题只有"……"）时回退为剥前缀后的原文，避免所有异常标题互相等价。
     */
    public static String dedupeKey(String title) {
        if (StringUtils.isBlank(title)) {
            return "";
        }
        String normalized = ChapterTitleNormalizer.normalize(title);
        String key = IGNORED_CHARS.matcher(StringUtils.defaultString(normalized)).replaceAll("");
        return key.isEmpty() ? StringUtils.trimToEmpty(normalized) : key;
    }

    /**
     * 在历史章节里查找与候选标题等价的章节（返回**最早**出现的那一章号，便于提示"与第N章重复"）。
     * 无冲突返回 null；候选为空返回 null。
     */
    public static Integer findDuplicate(String title, List<ChapterSummaryEntity> history) {
        String key = dedupeKey(title);
        if (key.isEmpty() || history == null || history.isEmpty()) {
            return null;
        }
        Integer earliest = null;
        for (ChapterSummaryEntity summary : history) {
            if (summary == null || summary.getChapterNo() == null || StringUtils.isBlank(summary.getTitle())) {
                continue;
            }
            if (!key.equals(dedupeKey(summary.getTitle()))) {
                continue;
            }
            if (earliest == null || summary.getChapterNo() < earliest) {
                earliest = summary.getChapterNo();
            }
        }
        return earliest;
    }

    /**
     * 本次检查：标题与历史重复时产出一条 MINOR issue（dimension=continuity）。
     * 无冲突返回空表。
     */
    public static List<ChapterIssueEntity> check(String title, List<ChapterSummaryEntity> history) {
        Integer duplicateOf = findDuplicate(title, history);
        if (duplicateOf == null) {
            return List.of();
        }
        ChapterIssueEntity issue = new ChapterIssueEntity();
        issue.setDimension("continuity");
        issue.setSeverity(StyleViolationPolicy.SEVERITY_MINOR);
        issue.setDescription("章节标题与第 " + duplicateOf + " 章重复（长篇里模板化标题反复出现会让读者与检索混淆）");
        issue.setEvidence(title);
        issue.setSuggestion("换一个贴合本章具体事件/意象的标题，不要用「新的开始」「风暴前夕」这类通用模板标题；"
                + "后续章节起名时请对照【已用章节标题】清单避开");
        return List.of(issue);
    }

    /**
     * 渲染【已用章节标题】提示块（回灌规划层）；无任何历史标题返回 null。
     *
     * 一次规划段只注入一次，成本可接受（章均标题 8~12 字）；这是本判据治本的那一半——
     * 检测只能事后记账，把清单摆到起名那一刻才能真的少重复。
     */
    public static String renderUsedTitles(List<ChapterSummaryEntity> history) {
        if (history == null || history.isEmpty()) {
            return null;
        }
        List<ChapterSummaryEntity> ordered = history.stream()
                .filter(s -> s != null && s.getChapterNo() != null && StringUtils.isNotBlank(s.getTitle()))
                .sorted((a, b) -> Integer.compare(b.getChapterNo(), a.getChapterNo()))
                .toList();
        if (ordered.isEmpty()) {
            return null;
        }
        Set<String> distinctKeys = new LinkedHashSet<>();
        List<String> rendered = new ArrayList<>();
        // 注意：**不能**在 rendered 满额时 break——那样 distinctKeys 也停在同一处，
        // 后面的总数就统计不到，"其余省略"提示永远不会触发（去重计数必须走完全程）
        for (ChapterSummaryEntity summary : ordered) {
            String key = dedupeKey(summary.getTitle());
            if (!distinctKeys.add(key)) {
                continue;
            }
            if (rendered.size() < RENDER_LIMIT) {
                rendered.add("第" + summary.getChapterNo() + "章" + summary.getTitle().trim());
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n【已用章节标题】以下是已用过的标题（已去重，共 ").append(ordered.size()).append(" 章）。")
                .append("新章标题**严禁与之重复**——含仅标点/空格/数字不同的写法（如「第3章的约定」与「第3章的约定。」视为重复）。")
                .append("尤其不要使用「新的开始」「风暴前夕」这类通用模板标题。");
        sb.append("\n").append(String.join("、", rendered));
        if (rendered.size() < distinctKeys.size()) {
            sb.append("\n（以上为最近的 ").append(rendered.size()).append(" 个不同标题，其余省略）");
        }
        return sb.toString();
    }
}
