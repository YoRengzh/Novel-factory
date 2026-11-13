package cn.novel.yonren.domain.novel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import cn.novel.yonren.types.enums.PromptScene;

/**
 * 动态资料选择器（skill 式按需注入）的行为开关：领域策略参数，归 domain 所有。
 * 与模型接入配置（同在 domain config 的 StoryProperties）分离——一个管"领域行为开不开"，一个管"用哪个供应商"
 */
@Data
@Component
@ConfigurationProperties(prefix = "story.prompt.reference-selector")
public class ReferenceSelectorProperties {

    /** 总开关：关闭后不发起选择调用、不注入动态资料 */
    private boolean enabled = true;

    /** 选择结果缓存：按 场景+题材+风格+章节类型+模型接入+索引版本 复用，关闭则每章实时选择 */
    private boolean cacheEnabled = true;

    /** 选择结果缓存容量上限（LRU 淘汰，防无界膨胀） */
    private int cacheMaxEntries = 64;

    /** 单次最多注入的资料份数 */
    private int maxSelected = 3;

    /** 单次动态资料注入的总字符预算，避免整篇长文挤占创作上下文 */
    private int maxRecallChars = 12000;

    /** 正文场景的字符预算（写正文时模型需要的是本章计划而非教材，预算收紧防注意力稀释） */
    private int contentSceneRecallChars = 4000;

    /**
     * 选择模式：llm = LLM 读索引选择；vector = 向量语义检索。
     *
     * vector 模式失败时快速失败终止作业，不降级回 llm（见 {@code DynamicReferenceService}）：
     * llm 选择质量明显差于向量检索，静默降级会让作业在一整批低质量注入下跑完，
     * 而"资料注错了"从产出上几乎看不出来——比停下来更贵。
     */
    private String mode = "llm";

    /** 向量检索命中点数（聚合到文件级后仍受 maxSelected 截断） */
    private int topK = 3;

    /** 向量检索最低相似分（Cosine 越大越相近）；0 表示不设门槛 */
    private double minScore = 0.0;

    /**
     * 块级注入：命中完整资料块而不是整份文件。默认关闭，关闭时保持文件级行为。
     * 打开后按原子块检索，依赖项随带，装不下的整块放弃，绝不截断原文。
     */
    private ContentBlocks contentBlocks = new ContentBlocks();

    public ContentBlocks blocksFor(PromptScene scene) {
        if (scene == null) {
            return null;
        }
        return contentBlocks;
    }

    @Data
    public static class ContentBlocks {

        /** 总开关；关闭后回退文件级选择，其余配置保留 */
        private boolean enabled = false;

        /** 独立候选池大小，不为凑满预算注入无关块 */
        private int candidateTopK = 12;

        /** 场景文档数上限；null 继承 max-selected */
        private Integer maxDocs;

        /** 场景相似度阈值；null 继承 min-score */
        private Double minScore;

        /** 最多注入的完整块数（含随带依赖） */
        private int maxBlocks = 6;

        /** 块级总字符预算（含来源标注与分隔符） */
        private int maxChars = 2000;

        /** 每份来源文档的字符上限；≤0 表示不限 */
        private int perDocMaxChars = 1500;
    }

}
