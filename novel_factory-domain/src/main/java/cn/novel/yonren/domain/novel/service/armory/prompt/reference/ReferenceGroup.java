package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import cn.novel.yonren.types.enums.PromptScene;

import java.util.Set;

/**
 * 参考资料分组：按「这份资料服务哪类场景」归类，用于在交给选择器之前裁剪索引。
 *
 * <p><b>为什么需要裁剪</b>：58 份资料平铺时，ref-select 的 prompt 里是一张 58 行的大表，
 * 模型要在长表里挑 3 份，选准率随条目数下降。按场景先筛到 10-20 行，选择范围更聚焦。
 *
 * <p><b>为什么不做目录重组</b>：文件名已是对外标识（选择结果、缓存键、测试断言都依赖它），
 * 物理移动文件会牵连一大片。这里用逻辑分组（按文件名映射），文件仍留在 references 根下。
 *
 * <p><b>归属原则</b>：一份资料如果有跨场景价值（如 de-ai-principles 既管正文也管修订），
 * 归入最宽的那个组（TECHNIQUE），保证不因分组而漏检。
 */
public final class ReferenceGroup {

    /** 写作技法：正文、修订、审校场景用（AI 味、情绪、对话、句式、语域…） */
    public static final String TECHNIQUE = "technique";

    /** 结构模板：计划与蓝图场景用（情节结构、大纲模板、开篇设计、节奏控制…） */
    public static final String STRUCTURE = "structure";

    /** 题材与设定：设定集、世界观、蓝图场景用（种子原子、题材包、世界观、人物档案…） */
    public static final String GENRE = "genre";

    private ReferenceGroup() {
    }

    /**
     * 场景 → 该场景可见的资料分组。
     * 未列出的场景返回全部三组（保守策略：新场景先给全量，观察后再收窄）。
     */
    public static Set<String> groupsFor(PromptScene scene) {
        if (scene == null) {
            return Set.of(TECHNIQUE, STRUCTURE, GENRE);
        }
        return switch (scene) {
            // 写正文/修订/审校：技法为主，结构参考（如节奏、钩子）同样有用
            case CHAPTER_CONTENT, CHAPTER_REVISE, CHAPTER_AUDIT ->
                    Set.of(TECHNIQUE, STRUCTURE);
            // 规划类：结构模板为主，题材打法辅助（哪类题材走哪套节拍）
            case CHAPTER_PLAN -> Set.of(STRUCTURE, GENRE);
            // 设定集与蓝图：题材原子、世界观、人物/反派设计
            case STAGE_BLUEPRINT, VOLUME_BLUEPRINT, SETTING_DRAFT ->
                    Set.of(GENRE, STRUCTURE);
            default -> Set.of(TECHNIQUE, STRUCTURE, GENRE);
        };
    }

    /**
     * 文件名 → 分组。按前缀/显式清单归类，未命中者归 TECHNIQUE（保守：至少能被搜到）。
     * 新增资料时若属于结构或题材类，加进对应清单即可。
     */
    public static String groupOf(String fileName) {
        if (fileName == null) {
            return TECHNIQUE;
        }
        if (STRUCTURE_FILES.contains(fileName) || fileName.startsWith("plot-structure-")) {
            return STRUCTURE;
        }
        if (GENRE_FILES.contains(fileName) || fileName.startsWith("seed-")) {
            return GENRE;
        }
        return TECHNIQUE;
    }

    /** 结构类：情节骨架、大纲与章节模板、开篇与节奏 */
    private static final Set<String> STRUCTURE_FILES = Set.of(
            "outline-template", "chapter-template", "chapter-guide",
            "opening-design", "pacing-control", "longform-planning",
            "plot-structure-circular", "plot-structure-five-act", "plot-structure-fragmented",
            "plot-structure-frame", "plot-structure-heros-journey", "plot-structure-kishotenketsu",
            "plot-structure-multiline", "plot-structure-mystery", "plot-structure-network",
            "plot-structure-romance", "plot-structure-save-the-cat", "plot-structure-seven-point",
            "plot-structure-short", "plot-structure-story-circle", "plot-structure-three-act",
            "plot-structure-thriller", "plot-structure-twist",
            "multi-thread", "transitions", "emotion-curve", "hook-library"
    );

    /** 题材/设定类：世界观、人物档案、反派、题材打法、种子原子 */
    private static final Set<String> GENRE_FILES = Set.of(
            "worldbuilding", "character-building", "character-template", "villain-design",
            "genre-flow-tactics", "differentiation", "trope-usage", "subject-and-ops",
            "seed-architecture", "seed-atoms-fantasy", "seed-atoms-romance",
            "transmigration-transition", "opening-hooks-goldfinger"
    );
}
