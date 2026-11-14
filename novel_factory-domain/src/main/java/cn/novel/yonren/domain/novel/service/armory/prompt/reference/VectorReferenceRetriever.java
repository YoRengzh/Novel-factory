package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import cn.novel.yonren.domain.novel.adapter.llm.EmbeddingGateway;
import cn.novel.yonren.domain.novel.adapter.repository.VectorStore;
import cn.novel.yonren.domain.novel.model.valobj.ScoredVectorPoint;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.config.ReferenceSelectorProperties;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.types.enums.PromptScene;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 文件级检索：冻结空间与全文版本，对账后检索；停用来源不参与 topK 名额竞争。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorReferenceRetriever {

    private final EmbeddingGateway embeddingGateway;
    private final VectorStore vectorStore;
    private final ReferenceVectorIndexer indexer;
    private final ReferenceSelectorProperties properties;

    private volatile String indexedVersion;

    /** 冻结当前实际 embedding 空间并检查已存索引；不调用付费 embedding、不写向量库。 */
    public Prepared prepare(StoryVO.Module module) {
        Set<String> allowed = indexer.enabledSources();
        if (allowed.isEmpty()) {
            return new Prepared("empty-" + indexer.corpusVersion(), null, allowed);
        }
        EmbeddingGateway.EmbeddingSession session = Objects.requireNonNull(
                embeddingGateway.session(module), "embedding session");
        indexer.checkSpace(session);
        return new Prepared(indexer.versionKey(session), session, allowed);
    }

    public static final class Prepared {
        private final String versionKey;
        private final EmbeddingGateway.EmbeddingSession session;
        private final Set<String> allowedSources;

        private Prepared(String versionKey, EmbeddingGateway.EmbeddingSession session, Set<String> allowedSources) {
            this.versionKey = Objects.requireNonNull(versionKey, "reference version");
            this.session = session;
            this.allowedSources = Set.copyOf(allowedSources);
        }

        public String versionKey() {
            return versionKey;
        }
    }

    public List<String> retrieve(StoryVO.Module module, PromptScene scene, PromptContext ctx) {
        if (indexer.enabledSources().isEmpty()) {
            indexer.index(module); // 全禁用也必须移除旧点，但不创建会话、不调用 embedding。
            invalidate();
            return List.of();
        }
        return retrieve(prepare(module), scene, ctx);
    }

    /** 使用查缓存前已冻结的会话；检索/资料批次不重新解析接入配置。 */
    public List<String> retrieve(Prepared prepared, PromptScene scene, PromptContext ctx) {
        Objects.requireNonNull(prepared, "prepared reference retrieval");
        if (prepared.allowedSources.isEmpty()) {
            return List.of();
        }
        Set<String> allowed = prepared.allowedSources;
        EmbeddingGateway.EmbeddingSession session = prepared.session;
        List<float[]> vectors = session.embed(List.of(buildQueryText(scene, ctx)));
        if (vectors == null || vectors.size() != 1) {
            throw new IllegalStateException("查询 embedding 返回数量不匹配：期望 1，实际 "
                    + (vectors == null ? 0 : vectors.size()));
        }
        float[] queryVector = vectors.get(0);
        ReferenceBlockIndexer.validateVector(queryVector, session.dimensions(), "query");
        queryVector = queryVector.clone();
        ensureIndexed(session, prepared.versionKey, queryVector.length);

        List<ScoredVectorPoint> hits = vectorStore.search(ReferenceVectorIndexer.COLLECTION,
                queryVector, properties.getTopK(), ReferenceVectorIndexer.PAYLOAD_FILE, allowed);
        if (hits == null) {
            throw new IllegalStateException("参考资料向量检索返回空响应");
        }
        List<String> fileNames = new ArrayList<>();
        for (ScoredVectorPoint hit : hits) {
            if (hit == null || hit.getPayload() == null || !Double.isFinite(hit.getScore())
                    || hit.getScore() < properties.getMinScore()) {
                continue;
            }
            String fileName = hit.getPayload().get(ReferenceVectorIndexer.PAYLOAD_FILE);
            if (fileName != null && allowed.contains(fileName) && !fileNames.contains(fileName)) {
                fileNames.add(fileName);
            }
        }
        log.info("向量资料检索完成：命中 {} 点，聚合 {} 份资料：{}", hits.size(), fileNames.size(), fileNames);
        return List.copyOf(fileNames);
    }

    /** 管理操作失效标记，不触发付费重嵌；下次检索会验证当前完整版本。 */
    public synchronized void invalidate() {
        indexedVersion = null;
    }

    private synchronized void ensureIndexed(EmbeddingGateway.EmbeddingSession session, String preparedVersion,
                                             int queryDimensions) {
        String key = preparedVersion + "-d" + queryDimensions;
        if (!key.equals(indexedVersion)) {
            indexedVersion = null;
        }
        // 即使进程内版本未变，也核验存储点集，避免集合被管理删除后沿用布尔 ready。
        indexer.ensureIndex(session, queryDimensions);
        indexedVersion = key;
    }

    private String buildQueryText(PromptScene scene, PromptContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("写作场景：").append(scene == PromptScene.CHAPTER_PLAN ? "规划全书章节大纲" : "撰写章节正文");
        if (ctx.getTheme() != null) {
            sb.append("；题材：").append(ctx.getTheme());
        }
        if (ctx.getStyle() != null) {
            sb.append("；风格：").append(ctx.getStyle());
        }
        if (ctx.getChapterType() != null) {
            sb.append("；章节类型：").append(ctx.getChapterType().getDesc());
        }
        if (ctx.getChapterBrief() != null) {
            sb.append("；本章要点：").append(ctx.getChapterBrief());
        }
        return sb.toString();
    }
}
