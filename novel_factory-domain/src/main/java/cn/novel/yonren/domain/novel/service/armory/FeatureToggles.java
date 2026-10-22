package cn.novel.yonren.domain.novel.service.armory;

import cn.novel.yonren.api.dto.LlmConfigDTO;
import cn.novel.yonren.domain.novel.model.valobj.properties.QualityReviewProperties;
import cn.novel.yonren.domain.novel.model.valobj.properties.ReferenceSelectorProperties;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryMemoryProperties;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 功能开关注册表：把散落在多个 {@code @ConfigurationProperties} bean 里的"开不开"统一到一个可覆盖的平面。
 *
 * <p><b>为什么需要这一层</b>：安装包用户改不了 jar 内的 novel-generation.yml，而这些开关直接决定
 * 每章要额外跑几次模型调用（审校 1 次、段落审校 1 次、候选重写最多 2 次、账本裁决 1 次……）。
 * 默认全开是"质量优先"档，但单章耗时可能翻数倍；用户必须在设置页能自己切。
 *
 * <p><b>实现方式</b>：启动时快照各 bean 的静态值，每次保存覆盖后按"覆盖优先、未覆盖回退快照"
 * 回写到 bean 上。服务层读的仍是原 bean（零改动、零性能开销），只是值被调整过——
 * 这比让 20 多个调用点改读覆盖层要可靠得多。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeatureToggles {

    /** 章节审校（每章 1 次 LLM） */
    public static final String AUDIT = "audit";
    /** 段落密度审校（每章 1 次 LLM） */
    public static final String PARAGRAPH_AUDIT = "paragraphAudit";
    /** 章节修订（审校发现问题才触发） */
    public static final String REVISE = "revise";
    /** 候选选优（低置信时整章重写 + 双稿盲评，单次 20 分钟级） */
    public static final String CANDIDATE = "candidate";
    /** 账本挂起事实裁决（每章至多 1 次） */
    public static final String LEDGER_ADJUDICATE = "ledgerAdjudicate";
    /** 全书质量评分（每 N 章抽样） */
    public static final String QUALITY_REVIEW = "qualityReview";
    /** 动态资料选择与注入（每章 1 次小调用 + 注入字符预算） */
    public static final String REFERENCE_SELECTOR = "referenceSelector";
    /** 故事记忆向量检索（每章 embedding 调用 + 检索） */
    public static final String STORY_MEMORY = "storyMemory";

    /** 资料分组·写作技法（AI 味/情绪/对话/句式/语域）：正文与修订的主力资料 */
    public static final String REFERENCE_GROUP_TECHNIQUE = "referenceGroupTechnique";
    /** 资料分组·结构模板（情节结构/大纲/节奏/钩子）：计划与蓝图场景的主力资料 */
    public static final String REFERENCE_GROUP_STRUCTURE = "referenceGroupStructure";
    /** 资料分组·题材设定（世界观/人物/种子原子/题材打法）：设定与蓝图场景的主力资料 */
    public static final String REFERENCE_GROUP_GENRE = "referenceGroupGenre";

    private final StoryProperties storyProperties;
    private final ReferenceSelectorProperties referenceSelectorProperties;
    private final StoryMemoryProperties storyMemoryProperties;
    private final QualityReviewProperties qualityReviewProperties;

    /** 开关定义（顺序即前端展示顺序） */
    @Getter
    public enum Def {
        AUDIT(FeatureToggles.AUDIT, "章节审校", "审校正文并给出问题清单；关掉则正文直接落盘不做质量检查", "每章 1 次调用"),
        PARAGRAPH_AUDIT(FeatureToggles.PARAGRAPH_AUDIT, "段落密度审校",
                "逐段检查信息密度与注水句；关掉可省一次调用，但灌水段落不会被拦", "每章 1 次调用"),
        REVISE(FeatureToggles.REVISE, "章节修订",
                "审校发现问题后自动改写；关掉则只报告不修（问题会进质量债）", "发现问题时 1-2 次调用"),
        CANDIDATE(FeatureToggles.CANDIDATE, "候选选优",
                "低置信章节整章重写一稿 + 异模型盲评二选一；质量最高但耗时最长，是单章 20+ 分钟的主因",
                "触发时 +2 次整章级调用（约 20 分钟）"),
        LEDGER_ADJUDICATE(FeatureToggles.LEDGER_ADJUDICATE, "账本事实裁决",
                "回读正文判定悬而未决的状态/一致性事实；关掉则挂起项按未成立处理", "每章至多 1 次调用"),
        QUALITY_REVIEW(FeatureToggles.QUALITY_REVIEW, "全书质量评分",
                "每 N 章抽样打分写趋势；低频高价值，但会额外调用", "每 10 章约 3 次调用"),
        REFERENCE_SELECTOR(FeatureToggles.REFERENCE_SELECTOR, "动态资料注入",
                "按场景挑选写作技法资料注入 prompt；关掉则完全不带参考资料", "每章 1 次小调用"),
        STORY_MEMORY(FeatureToggles.STORY_MEMORY, "跨章记忆检索",
                "从历史章节召回相关情节防失忆；关掉则只靠章节摘要前缀", "每章 1 次 embedding + 检索"),
        REFERENCE_GROUP_TECHNIQUE(FeatureToggles.REFERENCE_GROUP_TECHNIQUE, "资料组 · 写作技法",
                "AI 味判据、情绪外显、句式节奏、语域纪律等——写正文与修订时的主力资料", "不增加调用，只缩小选择范围"),
        REFERENCE_GROUP_STRUCTURE(FeatureToggles.REFERENCE_GROUP_STRUCTURE, "资料组 · 结构模板",
                "情节结构、大纲与章节模板、开篇设计、节奏控制——做计划与蓝图时的主力资料", "不增加调用，只缩小选择范围"),
        REFERENCE_GROUP_GENRE(FeatureToggles.REFERENCE_GROUP_GENRE, "资料组 · 题材设定",
                "世界观、人物与反派设计、题材打法、设定种子原子——做设定集与蓝图时的主力资料", "不增加调用，只缩小选择范围");

        private final String key;
        private final String label;
        private final String description;
        private final String costHint;

        Def(String key, String label, String description, String costHint) {
            this.key = key;
            this.label = label;
            this.description = description;
            this.costHint = costHint;
        }
    }

    /** 启动时快照的 yml 静态值（key → 静态布尔） */
    private final Map<String, Boolean> staticSnapshot = new LinkedHashMap<>();
    /** 当前生效的覆盖值（key → 覆盖布尔） */
    private volatile Map<String, Boolean> overrides = Map.of();

    /**
     * 启动快照：此时各 bean 刚由 yml 绑定完成，记录原始值供"清除覆盖"时回退。
     * 必须在任何 save() 之前执行，因此放在构造后的初始化方法里（Spring 保证依赖注入完成）
     */
    @jakarta.annotation.PostConstruct
    void snapshot() {
        synchronized (staticSnapshot) {
            staticSnapshot.clear();
            for (Def def : Def.values()) {
                staticSnapshot.put(def.getKey(), readStatic(def.getKey()));
            }
        }
        log.info("功能开关静态快照：{}", staticSnapshot);
    }

    /** 应用覆盖到各配置 bean（保存覆盖后调用；启动时也调一次以恢复上次的覆盖） */
    public synchronized void apply(Map<String, Boolean> overlayToggles) {
        Map<String, Boolean> next = new LinkedHashMap<>();
        if (overlayToggles != null) {
            for (Map.Entry<String, Boolean> e : overlayToggles.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && byKey(e.getKey()) != null) {
                    next.put(e.getKey(), e.getValue());
                }
            }
        }
        this.overrides = Map.copyOf(next);
        for (Def def : Def.values()) {
            boolean value = effective(def.getKey());
            write(def.getKey(), value);
        }
        log.info("功能开关覆盖已应用：{}", overrides);
    }

    /** 生效值：覆盖优先，未覆盖回退静态快照（快照缺失时读当前 bean） */
    public boolean effective(String key) {
        Boolean override = overrides.get(key);
        if (override != null) {
            return override;
        }
        Boolean stat = staticSnapshot.get(key);
        return stat != null ? stat : readStatic(key);
    }

    /** 是否被运行时覆盖（前端显示"已覆盖"用） */
    public boolean isOverridden(String key) {
        return overrides.containsKey(key);
    }

    /** 覆盖值（未覆盖返回 null） */
    public Boolean overrideValue(String key) {
        return overrides.get(key);
    }

    /** 构建前端视图（静态值 + 覆盖值 + 生效值） */
    public List<LlmConfigDTO.ToggleView> view() {
        List<LlmConfigDTO.ToggleView> rows = new ArrayList<>();
        for (Def def : Def.values()) {
            LlmConfigDTO.ToggleView row = new LlmConfigDTO.ToggleView();
            row.setKey(def.getKey());
            row.setLabel(def.getLabel());
            row.setDescription(def.getDescription());
            row.setCostHint(def.getCostHint());
            Boolean stat = staticSnapshot.get(def.getKey());
            row.setStaticValue(stat != null ? stat : readStatic(def.getKey()));
            row.setOverrideValue(overrides.get(def.getKey()));
            row.setEffectiveValue(effective(def.getKey()));
            rows.add(row);
        }
        return rows;
    }

    private static Def byKey(String key) {
        for (Def def : Def.values()) {
            if (def.getKey().equals(key)) {
                return def;
            }
        }
        return null;
    }

    private boolean readStatic(String key) {
        return switch (key) {
            case AUDIT -> storyProperties.getAudit() != null && storyProperties.getAudit().isEnabled();
            case PARAGRAPH_AUDIT -> storyProperties.getParagraphAudit() != null
                    && storyProperties.getParagraphAudit().isEnabled();
            case REVISE -> storyProperties.getRevise() != null && storyProperties.getRevise().isEnabled();
            case CANDIDATE -> storyProperties.getCandidate() != null
                    && storyProperties.getCandidate().isEnabled();
            case LEDGER_ADJUDICATE -> storyProperties.getLedgerAdjudicate() != null
                    && storyProperties.getLedgerAdjudicate().isEnabled();
            case QUALITY_REVIEW -> qualityReviewProperties.isEnabled();
            case REFERENCE_SELECTOR -> referenceSelectorProperties.isEnabled();
            case STORY_MEMORY -> storyMemoryProperties.isEnabled();
            // 资料分组开关没有 yml 静态配置：静态值恒为开（保持旧行为），只能由运行时覆盖关闭
            case REFERENCE_GROUP_TECHNIQUE, REFERENCE_GROUP_STRUCTURE, REFERENCE_GROUP_GENRE -> true;
            default -> true;
        };
    }

    private void write(String key, boolean value) {
        switch (key) {
            case AUDIT -> {
                if (storyProperties.getAudit() != null) {
                    storyProperties.getAudit().setEnabled(value);
                }
            }
            case PARAGRAPH_AUDIT -> {
                if (storyProperties.getParagraphAudit() != null) {
                    storyProperties.getParagraphAudit().setEnabled(value);
                }
            }
            case REVISE -> {
                if (storyProperties.getRevise() != null) {
                    storyProperties.getRevise().setEnabled(value);
                }
            }
            case CANDIDATE -> {
                if (storyProperties.getCandidate() != null) {
                    storyProperties.getCandidate().setEnabled(value);
                }
            }
            case LEDGER_ADJUDICATE -> {
                if (storyProperties.getLedgerAdjudicate() != null) {
                    storyProperties.getLedgerAdjudicate().setEnabled(value);
                }
            }
            case QUALITY_REVIEW -> qualityReviewProperties.setEnabled(value);
            case REFERENCE_SELECTOR -> referenceSelectorProperties.setEnabled(value);
            case STORY_MEMORY -> storyMemoryProperties.setEnabled(value);
            default -> { }
        }
    }
}
