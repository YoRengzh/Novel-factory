package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import cn.novel.yonren.domain.novel.adapter.llm.EmbeddingGateway;
import cn.novel.yonren.domain.novel.adapter.repository.VectorStore;
import cn.novel.yonren.domain.novel.config.ReferenceSelectorProperties;
import cn.novel.yonren.domain.novel.model.valobj.ScoredVectorPoint;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.types.enums.PromptScene;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 请求级冻结会话检索：prepare 用于缓存版本，retrieve 返回本次命中及其唯一快照。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReferenceBlockRetriever {

    static final int MAX_QUERY_CHARS = 2048;

    private final EmbeddingGateway embeddingGateway;
    private final VectorStore vectorStore;
    private final ReferenceBlockIndexer indexer;
    private final ReferenceSelectorProperties properties;

    /** 仅加载本地不可变语料并创建冻结会话，不调用 embedding 或向量存储。 */
    public Prepared prepare(StoryVO.Module module) {
        EmbeddingGateway.EmbeddingSession session = Objects.requireNonNull(
                embeddingGateway.session(module), "embedding session");
        return new Prepared(indexer.loadSnapshot(session), session);
    }

    public static final class Prepared {
        private final ReferenceBlockSnapshot snapshot;
        private final EmbeddingGateway.EmbeddingSession session;

        private Prepared(ReferenceBlockSnapshot snapshot, EmbeddingGateway.EmbeddingSession session) {
            this.snapshot = Objects.requireNonNull(snapshot, "reference snapshot");
            this.session = Objects.requireNonNull(session, "embedding session");
        }

        public String versionKey() {
            return snapshot.version();
        }

        /** 本次 prepare 的不可变本地快照，供用途资格计算；不信任向量 payload 的用途标签。 */
        public ReferenceBlockSnapshot snapshot() {
            return snapshot;
        }
    }

    public record Retrieval(ReferenceBlockSnapshot snapshot, List<ReferenceHit> hits) {
        public Retrieval {
            Objects.requireNonNull(snapshot, "reference snapshot");
            hits = List.copyOf(hits);
        }
    }

    /** 兼容入口：空白名单直接返回，不创建会话。 */
    public List<ReferenceHit> retrieve(StoryVO.Module module, PromptScene scene, PromptContext ctx,
                                       Set<String> allowedDocIds) {
        if (allowedDocIds == null || allowedDocIds.isEmpty()) {
            return List.of();
        }
        return retrieve(module, scene, ctx, allowedDocIds, null);
    }

    /** 显式原子资格白名单：空集拒绝全部且不创建会话。 */
    public List<ReferenceHit> retrieve(StoryVO.Module module, PromptScene scene, PromptContext ctx,
                                       Set<String> allowedDocIds, Set<String> eligibleAtomIds) {
        if (allowedDocIds == null || allowedDocIds.isEmpty()
                || (eligibleAtomIds != null && eligibleAtomIds.isEmpty())) {
            return List.of();
        }
        return retrieve(prepare(module), ReferenceQueryBuilder.build(scene, ctx), allowedDocIds,
                eligibleAtomIds, Math.max(1, properties.getTopK()), properties.getMinScore()).hits();
    }

    public Retrieval retrieve(Prepared prepared, String query, Set<String> allowedDocIds,
                              int topK, double minScore) {
        return retrieve(prepared, query, allowedDocIds, null, topK, minScore);
    }

    /** 本地资格与文档、独立候选交集直接传给服务端过滤，必须在截断 topK 之前生效。 */
    public Retrieval retrieve(Prepared prepared, String query, Set<String> allowedDocIds,
                              Set<String> eligibleAtomIds, int topK, double minScore) {
        Objects.requireNonNull(prepared, "prepared reference retrieval");
        if (allowedDocIds == null || allowedDocIds.isEmpty()
                || (eligibleAtomIds != null && eligibleAtomIds.isEmpty())) {
            return new Retrieval(prepared.snapshot, List.of());
        }
        Set<String> allowed = Set.copyOf(allowedDocIds);
        Set<String> eligible = eligibleAtomIds == null ? null : Set.copyOf(eligibleAtomIds);
        Set<String> candidates = prepared.snapshot.standaloneAtoms(allowed).stream()
                .map(ReferenceAtom::atomId)
                .filter(id -> eligible == null || eligible.contains(id))
                .collect(Collectors.toUnmodifiableSet());
        if (candidates.isEmpty()) {
            return new Retrieval(prepared.snapshot, List.of());
        }
        if (query == null || query.isBlank() || query.length() > MAX_QUERY_CHARS) {
            throw new IllegalArgumentException("资料检索查询必须非空且不超过 " + MAX_QUERY_CHARS + " 字符");
        }
        if (topK <= 0 || !Double.isFinite(minScore)) {
            throw new IllegalArgumentException("资料检索 topK 必须为正数且 minScore 必须为有限数值");
        }
        List<float[]> queryVectors = prepared.session.embed(List.of(query));
        if (queryVectors == null || queryVectors.size() != 1) {
            throw new IllegalStateException("查询 embedding 返回数量不匹配：期望 1，实际 "
                    + (queryVectors == null ? 0 : queryVectors.size()));
        }
        float[] queryVector = queryVectors.get(0);
        ReferenceBlockIndexer.validateVector(queryVector, prepared.snapshot.dimensions(), "query");
        // 网关可能复用内部缓冲区，索引批调用不能改变已经冻结的查询向量。
        queryVector = queryVector.clone();
        ReferenceBlockSnapshot snapshot = indexer.ensureIndex(prepared.snapshot, prepared.session, queryVector.length);
        List<ScoredVectorPoint> points = vectorStore.search(snapshot.collection(), queryVector, topK,
                ReferenceBlockIndexer.PAYLOAD_ATOM_ID, candidates);
        if (points == null) {
            throw new IllegalStateException("资料块向量检索返回空响应");
        }

        List<ReferenceHit> hits = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int rejected = 0;
        for (ScoredVectorPoint point : points) {
            ReferenceAtom atom = validAtom(point, snapshot, candidates);
            if (atom == null || !Double.isFinite(point.getScore()) || point.getScore() < minScore) {
                rejected++;
                continue;
            }
            if (seen.add(atom.atomId())) {
                hits.add(new ReferenceHit(atom, point.getScore()));
            }
        }
        hits.sort(Comparator.comparingDouble(ReferenceHit::score).reversed().thenComparing(ReferenceHit::atomId));
        List<ReferenceHit> selected = hits.size() <= topK ? hits : hits.subList(0, topK);
        log.info("资料块检索完成：候选 {} 点，命中 {} 块，拒绝 {} 点，集合 {}",
                points.size(), selected.size(), rejected, snapshot.collection());
        if (selected.size() < topK) {
            log.warn("REFERENCE_UNDERFILLED requested={} selected={} eligible={}; 不放宽用途/场景，不补 LLM 重排",
                    topK, selected.size(), candidates.size());
        }
        return new Retrieval(snapshot, selected);
    }

    private static ReferenceAtom validAtom(ScoredVectorPoint point, ReferenceBlockSnapshot snapshot,
                                           Set<String> candidates) {
        if (point == null || point.getPayload() == null) {
            return null;
        }
        Map<String, String> payload = point.getPayload();
        String atomId = payload.get(ReferenceBlockIndexer.PAYLOAD_ATOM_ID);
        if (atomId == null || !candidates.contains(atomId)) {
            return null;
        }
        ReferenceAtom atom = snapshot.byId().get(atomId);
        if (atom == null || !ReferenceBlockIndexer.stableId(atomId).equals(point.getId())) {
            return null;
        }
        // 包括显式 atomId、原文、正文哈希、完整语料/空间版本、schema 与全部定位元信息。
        for (Map.Entry<String, String> expected : ReferenceBlockIndexer.payload(atom, snapshot).entrySet()) {
            if (!Objects.equals(expected.getValue(), payload.get(expected.getKey()))) {
                return null;
            }
        }
        String text = payload.get(ReferenceBlockIndexer.PAYLOAD_TEXT);
        return Objects.equals(ReferenceAtomBuilder.hash(text), payload.get(ReferenceBlockIndexer.PAYLOAD_HASH))
                ? atom : null;
    }
}
