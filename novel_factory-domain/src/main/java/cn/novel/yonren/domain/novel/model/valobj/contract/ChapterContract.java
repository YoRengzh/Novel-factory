package cn.novel.yonren.domain.novel.model.valobj.contract;

import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.types.enums.ChapterType;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public record ChapterContract(
        String chapterTitle,
        String dramaticTask,
        List<String> characters,
        List<String> mustHappen,
        String endingImage,
        String povCharacter,
        String knowledgeBoundary,
        List<String> mustNotReveal,
        ChapterType chapterType) {

    /** 认知边界回溯章数：取最近若干章的角色账本（越靠后的状态越新） */
    private static final int KNOWLEDGE_LOOKBACK_CHAPTERS = 3;

    /** 认知类状态的特征词：命中即视为"谁知不知道什么" */
    private static final List<String> KNOWLEDGE_MARKERS = List.of(
            "知道", "不知", "怀疑", "确认", "以为", "猜测", "认为", "身份", "察觉", "发现", "误以为");


    public static ChapterContract of(ChapterPlanItemEntity item, List<ChapterSummaryEntity> summaries) {
        if (item == null) {
            return null;
        }
        return new ChapterContract(
                item.getTitle(),
                item.getGoal(),
                item.getCharacters(),
                item.getKeyEvents(),
                item.getEndingHook(),
                null,
                extractKnowledgeBoundary(summaries, item.getChapterNo()),
                null,
                item.getChapterType());
    }


    private static String extractKnowledgeBoundary(List<ChapterSummaryEntity> summaries,
                                                   Integer beforeChapterNo) {
        if (summaries == null || summaries.isEmpty()) {
            return null;
        }
        List<ChapterSummaryEntity> history = new ArrayList<>();
        for (ChapterSummaryEntity summary : summaries) {
            if (summary != null && summary.getChapterNo() != null
                    && (beforeChapterNo == null || summary.getChapterNo() < beforeChapterNo)) {
                history.add(summary);
            }
        }
        if (history.isEmpty()) {
            return null;
        }
        int from = Math.max(0, history.size() - KNOWLEDGE_LOOKBACK_CHAPTERS);
        Map<String, String> latest = new LinkedHashMap<>();
        for (int i = from; i < history.size(); i++) {
            ChapterSummaryEntity summary = history.get(i);
            if (summary.getCharacterStates() == null) {
                continue;
            }
            for (ChapterSummaryEntity.StateEntry state : summary.getCharacterStates()) {
                if (state == null || StringUtils.isBlank(state.getName())) {
                    continue;
                }
                String status = StringUtils.defaultString(state.getStatus());
                if (isKnowledgeState(status)) {
                    // 后写的覆盖先写的 —— 同一角色保留最新认知状态
                    latest.put(state.getName(), status);
                }
            }
        }
        if (latest.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        latest.forEach((name, status) -> {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(name).append("——").append(status);
        });
        return sb.toString();
    }

    private static boolean isKnowledgeState(String status) {
        for (String marker : KNOWLEDGE_MARKERS) {
            if (status.contains(marker)) {
                return true;
            }
        }
        return false;
    }


    public boolean isComplete() {
        return StringUtils.isNotBlank(dramaticTask)
                && mustHappen != null && !mustHappen.isEmpty()
                && StringUtils.isNotBlank(endingImage);
    }

    /** 缺失项（供兜底补全与观测归因） */
    public List<String> missingParts() {
        List<String> missing = new ArrayList<>();
        if (StringUtils.isBlank(dramaticTask)) {
            missing.add("核心任务(goal)");
        }
        if (mustHappen == null || mustHappen.isEmpty()) {
            missing.add("必须事件(keyEvents)");
        }
        if (StringUtils.isBlank(endingImage)) {
            missing.add("结尾落点(endingHook)");
        }
        return missing;
    }


    public String suggestedSkeleton() {
        ChapterType type = chapterType == null ? ChapterType.NORMAL : chapterType;
        return switch (type) {
            case TRANSITION -> "日常动作 → 关系或状态变化 → 下一步决定";
            case CLIMAX -> "压力升级 → 做出选择 → 付出代价 → 暂时结果";
            case FINALE -> "收束既定线索 → 交代人物去向 → 落在收尾画面上";
            case NORMAL -> "目标 → 阻碍 → 转折 → 结果";
        };
    }
}
