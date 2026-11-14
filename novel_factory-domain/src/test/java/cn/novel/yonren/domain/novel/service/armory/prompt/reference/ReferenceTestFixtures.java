package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import cn.novel.yonren.domain.novel.adapter.llm.EmbeddingGateway;
import cn.novel.yonren.domain.novel.adapter.repository.VectorStore;
import cn.novel.yonren.domain.novel.model.valobj.ScoredVectorPoint;
import cn.novel.yonren.domain.novel.model.valobj.StoredPoint;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.VectorPoint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 生命周期测试的离线端口替身；冻结会话与服务端白名单语义与生产一致。 */
final class ReferenceTestFixtures {
    private ReferenceTestFixtures() {
    }

    static final class Embedding implements EmbeddingGateway {
        String signature = "fake-space";
        Integer dimensions = 3;
        int actualDimensions = 3;
        int calls;
        int sessionCalls;
        int invalidAtCall = -1;
        int wrongCountAtCall = -1;
        final List<List<String>> texts = new ArrayList<>();

        @Override
        public List<float[]> embed(StoryVO.Module module, List<String> values) {
            throw new AssertionError("生命周期链路必须使用冻结会话而非重新解析配置的 embed(module)");
        }

        @Override
        public EmbeddingSession session(StoryVO.Module module) {
            sessionCalls++;
            String frozenSignature = signature;
            Integer frozenDimensions = dimensions;
            int frozenActual = actualDimensions;
            return new EmbeddingSession() {
                @Override
                public List<float[]> embed(List<String> values) {
                    calls++;
                    texts.add(List.copyOf(values));
                    if (calls == wrongCountAtCall) {
                        return List.of();
                    }
                    List<float[]> result = new ArrayList<>();
                    for (String ignored : values) {
                        float[] vector = new float[frozenActual];
                        java.util.Arrays.fill(vector, 0.5f);
                        if (calls == invalidAtCall) {
                            vector[0] = Float.NaN;
                        }
                        result.add(vector);
                    }
                    return result;
                }

                @Override
                public String signature() {
                    return frozenSignature;
                }

                @Override
                public Integer dimensions() {
                    return frozenDimensions;
                }
            };
        }
    }

    static final class Store implements VectorStore {
        final Map<String, Map<String, VectorPoint>> collections = new LinkedHashMap<>();
        final Map<String, Integer> dimensions = new LinkedHashMap<>();
        String lastField;
        Set<String> lastFilter;
        int writes;
        int clears;
        int deletions;
        int searches;
        boolean ignoreDeletes;
        List<ScoredVectorPoint> overrideHits;

        Map<String, VectorPoint> points(String collection) {
            return collections.computeIfAbsent(collection, ignored -> new LinkedHashMap<>());
        }

        @Override
        public void ensureCollection(String collection, int vectorSize) {
            Integer existing = dimensions.get(collection);
            if (existing != null && existing != vectorSize) {
                throw new IllegalStateException("集合维度不匹配，请显式重建");
            }
            dimensions.put(collection, vectorSize);
            points(collection);
        }

        @Override
        public void upsert(String collection, List<VectorPoint> values) {
            writes++;
            for (VectorPoint point : values) {
                if (point.getVector().length != dimensions.get(collection)) {
                    throw new IllegalStateException("点维度与集合不一致");
                }
                points(collection).put(point.getId(), point);
            }
        }

        @Override
        public List<ScoredVectorPoint> search(String collection, float[] query, int limit) {
            throw new AssertionError("参考检索必须传服务端来源/原子白名单");
        }

        @Override
        public List<ScoredVectorPoint> search(String collection, float[] query, int limit,
                                              String field, Collection<String> fieldValues) {
            searches++;
            lastField = field;
            lastFilter = Set.copyOf(fieldValues);
            if (overrideHits != null) {
                return overrideHits;
            }
            List<ScoredVectorPoint> hits = new ArrayList<>();
            for (VectorPoint point : points(collection).values()) {
                if (!fieldValues.contains(point.getPayload().get(field))) {
                    continue;
                }
                hits.add(ScoredVectorPoint.builder().id(point.getId()).score(0.8).payload(point.getPayload()).build());
            }
            return List.copyOf(hits.subList(0, Math.min(limit, hits.size())));
        }

        @Override
        public void clear(String collection) {
            clears++;
            collections.remove(collection);
            dimensions.remove(collection);
        }

        @Override
        public List<StoredPoint> listPoints(String collection) {
            return points(collection).values().stream().map(point -> StoredPoint.builder().id(point.getId())
                    .kind(point.getPayload().get("kind")).text(point.getPayload().get("text")).build()).toList();
        }

        @Override
        public void delete(String collection, List<String> ids) {
            deletions += ids.size();
            if (!ignoreDeletes) {
                ids.forEach(points(collection)::remove);
            }
        }

        @Override
        public long count(String collection) {
            return points(collection).size();
        }
    }
}
