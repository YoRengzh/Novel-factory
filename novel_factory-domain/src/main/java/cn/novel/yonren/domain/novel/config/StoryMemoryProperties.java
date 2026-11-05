package cn.novel.yonren.domain.novel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 故事记忆层（二期跨章剧情记忆 + 三期人物/世界观记忆）行为开关。
 * 与一期参考资料检索共用向量设施（EmbeddingGateway / VectorStore），
 * 但语料是运行时逐章落盘的故事自身记忆，集合按故事隔离（novel-memory-{故事目录名}）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "story.prompt.story-memory")
public class StoryMemoryProperties {

    /** 总开关：关闭后不索引、不检索，记忆前缀不含唤醒节 */
    private boolean enabled = true;

    /** 集合名前缀：实际集合名 = 前缀 + "-" + 故事目录名 */
    private String collectionPrefix = "novel-memory";

    /** 检索候选池大小（chapter/ledger/bible 三类混排，按分数降序）；实际注入条数由 maxRecallChars 决定 */
    private int topK = 10;

    /** 最低相似分（Cosine 越大越相近）；低于则丢弃，防无关记忆注水 */
    private double minScore = 0.35;

    /**
     * 唤醒节注入的字符预算：按分数降序贪心累计，装不下的候选跳过（不再就此截断后续）。
     * 单条上限为 MEM_CHUNK_CHARS（600，与记忆点切块粒度一致），故实际注入条数 ≈ 本值 / 600，
     * topK 应 ≥ 该比值——否则候选池不足以填满预算（2000 ≈ 3~4 条）
     */
    private int maxRecallChars = 2000;

    /**
     * 检索瞬时失败的最大尝试次数（含首次）；1=不重试。
     * 可自愈判定见 StoryMemoryService#isRetryableRetrievalFailure——
     * 瞬时失败（限流/5xx/网络 IO/向量库调用异常）重试，确定性失败（欠费/鉴权/未配置）立即终止
     */
    private int retrieveMaxAttempts = 3;

    /** 世界集合名前缀（五期系列化共享世界观）：实际集合名 = 前缀 + "-" + worldId；仅 bible 点共享 */
    private String worldCollectionPrefix = "novel-world";

    /** 世界集合最低相似分；null 时回退 minScore（世界集合与故事集合共用同一门槛） */
    private Double worldMinScore;

}
