package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 伏笔动态权重打分：把"新/中/老"的静态分带升级为连续分数驱动的生命周期分级。
 * score = importance×10（埋设时摘要模型评估的基础重要度）+ 滞留章数 × 发酵系数，
 * 分数跨过阈值即跃迁：60=软（推荐顺手回收）、80=硬（计划必须评估）、100=熔断（冻结为未填）。
 * 分数是 (importance, 埋设章号, 当前章号) 的纯函数，从摘要可确定性重算，零新增存储；
 * 回收执行权始终归规划层按剧情相关性判断——分数只决定关注强度，绝不强排（防注水）
 */
@Service
public class ForeshadowPriorityService {

    /** 发酵系数：每滞留 1 章累加的分数 */
    public static final int FERMENT_COEFFICIENT = 5;
    /** 软阈值：进入推荐回收区，计划可顺手安排 */
    public static final int SOFT_THRESHOLD = 60;
    /** 硬阈值：计划必须逐条显式评估（相关则回收并注明来源，无关需说明理由） */
    public static final int HARD_THRESHOLD = 80;
    /** 熔断阈值：冻结为未填（长期未兑现），退出自动回收流，留待阶段出口裁决 */
    public static final int BREAKER_THRESHOLD = 100;
    /** 摘要未评出重要度时的默认档（3 档 = 势力/地图级） */
    public static final int DEFAULT_IMPORTANCE = 3;

    /** 优先级分级：硬=必须评估，软=推荐，背景=常规展示，未填=冻结 */
    public enum Tier { HARD, SOFT, BACKGROUND, BREAKER }

    /** 打分结果：原始条目 + 分数 + 分级 */
    public record ScoredForeshadow(ChapterMemoryService.PendingForeshadow item, int score, Tier tier) {
    }

    /**
     * 全量打分并按优先级排序（分数降序，同分先埋优先）。
     * 分级仅由分数决定，条数封顶由渲染层按预算控制
     */
    public List<ScoredForeshadow> score(List<ChapterMemoryService.PendingForeshadow> pending, int latestNo) {
        List<ScoredForeshadow> scored = new ArrayList<>();
        if (pending == null) {
            return scored;
        }
        for (ChapterMemoryService.PendingForeshadow item : pending) {
            int score = scoreOf(item.importance(), item.chapterNo(), latestNo);
            scored.add(new ScoredForeshadow(item, score, tierOf(score, item.importance())));
        }
        scored.sort(Comparator.comparingInt(ScoredForeshadow::score).reversed()
                .thenComparing(s -> s.item().chapterNo()));
        return scored;
    }

    /**
     * 评分公式：基础重要度（1-5 档 × 10，越界收敛）+ 滞留章数 × 发酵系数
     */
    public static int scoreOf(Integer importance, int chapterNo, int latestNo) {
        int level = importance == null ? DEFAULT_IMPORTANCE : importance;
        level = Math.min(5, Math.max(1, level));
        int stagnant = Math.max(0, latestNo - chapterNo);
        return level * 10 + stagnant * FERMENT_COEFFICIENT;
    }

    /**
     * 分级：≥100 未填，≥80 硬，≥60 软，其余背景。
     * 主线核心（5 分）不熔断——未填语义是"剧情走远的闲笔"，整卷级谜题冻结等于让写手
     * 失去主线悬念上下文，故封顶在硬级保持强可见，
     * 其出路归卷末/人工处理
     */
    public static Tier tierOf(int score, Integer importance) {
        boolean spine = importance != null && importance >= 5;
        if (score >= BREAKER_THRESHOLD && !spine) {
            return Tier.BREAKER;
        }
        if (score >= HARD_THRESHOLD) {
            return Tier.HARD;
        }
        if (score >= SOFT_THRESHOLD) {
            return Tier.SOFT;
        }
        return Tier.BACKGROUND;
    }

}
