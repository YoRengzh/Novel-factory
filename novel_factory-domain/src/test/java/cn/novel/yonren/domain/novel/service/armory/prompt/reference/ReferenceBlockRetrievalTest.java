package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import cn.novel.yonren.domain.novel.adapter.llm.EmbeddingGateway;
import cn.novel.yonren.domain.novel.adapter.repository.VectorStore;
import cn.novel.yonren.domain.novel.config.ReferenceSelectorProperties;
import cn.novel.yonren.domain.novel.model.valobj.ScoredVectorPoint;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import cn.novel.yonren.types.enums.PromptScene;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 块级检索离线测试：不访问网络、向量库或真实 embedding。 */
class ReferenceBlockRetrievalTest {

    private ReferenceIndexLoader indexLoader;
    private PromptRuleFileLoader fileLoader;
    private ReferenceAtomBuilder atomBuilder;
    private ReferenceChunker chunker;
    private FakeEmbedding embedding;
    private FakeVectorStore store;
    private ReferenceSelectorProperties properties;
    private ReferenceBlockIndexer indexer;
    private ReferenceBlockRetriever retriever;

    @BeforeEach
    void setUp() {
        indexLoader = mock(ReferenceIndexLoader.class);
        fileLoader = mock(PromptRuleFileLoader.class);
        atomBuilder = new ReferenceAtomBuilder();
        chunker = new ReferenceChunker();
        embedding = new FakeEmbedding();
        store = new FakeVectorStore();
        properties = new ReferenceSelectorProperties();
        indexer = new ReferenceBlockIndexer(indexLoader, fileLoader, atomBuilder, chunker, embedding, store);
        retriever = new ReferenceBlockRetriever(embedding, store, indexer, properties);
    }

    @Test
    void atomIdentityIgnoresSectionIndexAndKeepsDependencies() {
        String content = String.join("\n",
                "# 合成资料",
                "",
                "卷首说明",
                "",
                "## 基础原则",
                "",
                "原则正文",
                "",
                "## 进阶技巧",
                "",
                "技巧正文");
        List<ReferenceAtom> atoms = atomBuilder.build("synthetic-doc", "合成资料", "technique", content, chunker);

        assertFalse(atoms.isEmpty());
        assertTrue(atoms.stream().anyMatch(atom -> "导言".equals(atom.headingPath()) && !atom.standalone()));
        ReferenceAtom principle = atoms.stream().filter(atom -> "基础原则".equals(atom.headingPath())).findFirst().orElseThrow();
        assertFalse(principle.atomId().contains("|0"), "原子身份不应由原文序号决定");
        assertTrue(principle.standalone());
    }

    @Test
    void atomPolicyRejectsMissingSplitHeading() {
        ReferenceAtomBuilder broken = new ReferenceAtomBuilder(
                "{\"version\":\"2\",\"docs\":{\"synthetic-doc\":{\"splitTitles\":[\"不存在的小节\"]}}}");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> broken.build("synthetic-doc", "合成资料", "technique", "# 标题\n\n## 实际小节\n正文", chunker));

        assertTrue(error.getMessage().contains("splitTitles 标题不存在"));
    }

    @Test
    void serverSideFilterRunsBeforeTopK() {
        seedOneDocument();
        ReferenceBlockRetriever.Prepared prepared = retriever.prepare(module());

        ReferenceBlockRetriever.Retrieval retrieval = retriever.retrieve(prepared, "写作场景：撰写章节正文",
                Set.of("synthetic-doc"), Set.of(), 5, 0.0);

        assertTrue(retrieval.hits().isEmpty());
        assertNull(store.lastFilter, "空资格集合必须在本地直接拒绝，不向向量库发起检索");
    }

    @Test
    void retrievalRejectsPayloadThatNoLongerMatchesSnapshot() {
        seedOneDocument();
        ReferenceBlockRetriever.Prepared prepared = retriever.prepare(module());
        ReferenceAtom atom = prepared.snapshot().atoms().stream()
                .filter(ReferenceAtom::standalone).findFirst().orElseThrow();
        store.overrideScore = 0.9;
        store.overridePayload = Map.of(ReferenceBlockIndexer.PAYLOAD_ATOM_ID, atom.atomId(),
                ReferenceBlockIndexer.PAYLOAD_TEXT, "被篡改的正文");

        ReferenceBlockRetriever.Retrieval retrieval = retriever.retrieve(prepared, "写作场景：撰写章节正文",
                Set.of("synthetic-doc"), Set.of(atom.atomId()), 5, 0.0);

        assertTrue(retrieval.hits().isEmpty(), "正文哈希或元数据不一致的命中必须丢弃");
    }

    @Test
    void packagerKeepsWholeBlocksAndNeverExceedsBudget() {
        seedOneDocument();
        ReferenceBlockRetriever.Prepared prepared = retriever.prepare(module());
        ReferenceAtom atom = prepared.snapshot().atoms().stream()
                .filter(ReferenceAtom::standalone).findFirst().orElseThrow();
        // 打包按快照内的真实原子身份校验；命中必须引用同一快照对象。
        List<ReferenceHit> hits = List.of(new ReferenceHit(atom, 0.9));

        // 依赖（卷首导言）也必须具备资格，否则整块按生产语义拒绝。
        Set<String> eligibleAtoms = prepared.snapshot().atoms().stream()
                .map(ReferenceAtom::atomId).collect(java.util.stream.Collectors.toUnmodifiableSet());
        ReferenceBlockPackager.Result packed = ReferenceBlockPackager.pack(hits, prepared.snapshot(),
                Set.of("synthetic-doc"), eligibleAtoms, 3, 6, 4000, 3000);

        assertEquals(2, packed.blockCount(), "命中块与其必需依赖应一起打包");
        assertTrue(packed.usedChars() <= 4000);
        assertTrue(packed.rules().get(0).getContent().contains(atom.content()), "块必须整块保留，不能截断");
        assertTrue(packed.allAtoms().stream().anyMatch(a -> "导言".equals(a.headingPath())),
                "卷首导言作为依赖随带");
    }

    private void seedOneDocument() {
        String content = String.join("\n", "# 合成资料", "", "卷首说明", "",
                "## 基础原则", "", "原则正文");
        when(indexLoader.getIndex()).thenReturn(List.of(
                new cn.novel.yonren.domain.novel.model.valobj.prompt.ReferenceIndexEntry(
                        "synthetic-doc", "合成资料", "", "references/synthetic-doc.md")));
        when(fileLoader.load(anyString())).thenReturn(content);
    }

    private StoryVO.Module module() {
        StoryVO.Module module = new StoryVO.Module();
        StoryVO.Module.EmbeddingApi api = new StoryVO.Module.EmbeddingApi();
        api.setModel("fake-embedding");
        module.setEmbeddingApi(api);
        return module;
    }

    /** 固定维度的小型确定性嵌入：只用于离线结构验证。 */
    private static final class FakeEmbedding implements EmbeddingGateway {
        @Override
        public List<float[]> embed(StoryVO.Module module, List<String> texts) {
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                vectors.add(new float[]{1f, 0.5f, 0.25f});
            }
            return vectors;
        }

        @Override
        public EmbeddingSession session(StoryVO.Module module) {
            return new EmbeddingSession() {
                @Override
                public List<float[]> embed(List<String> texts) {
                    return FakeEmbedding.this.embed(module, texts);
                }

                @Override
                public String signature() {
                    return "fake-space";
                }

                @Override
                public Integer dimensions() {
                    return 3;
                }
            };
        }
    }

    /** 进程内向量库替身：支持服务端过滤语义。 */
    private static final class FakeVectorStore implements VectorStore {
        private final Map<String, cn.novel.yonren.domain.novel.model.valobj.VectorPoint> points = new LinkedHashMap<>();
        private List<String> lastFilter;
        private double overrideScore = Double.NaN;
        private Map<String, String> overridePayload;

        @Override
        public void ensureCollection(String collection, int vectorSize) {
        }

        @Override
        public void upsert(String collection, List<cn.novel.yonren.domain.novel.model.valobj.VectorPoint> values) {
            values.forEach(point -> points.put(point.getId(), point));
        }

        @Override
        public List<ScoredVectorPoint> search(String collection, float[] queryVector, int limit) {
            return search(collection, queryVector, limit, null, null);
        }

        @Override
        public List<ScoredVectorPoint> search(String collection, float[] queryVector, int limit,
                                              String field, java.util.Collection<String> fieldValues) {
            if (fieldValues != null && fieldValues.isEmpty()) {
                return List.of();
            }
            lastFilter = fieldValues == null ? null : List.copyOf(fieldValues);
            List<ScoredVectorPoint> hits = new ArrayList<>();
            for (cn.novel.yonren.domain.novel.model.valobj.VectorPoint point : points.values()) {
                Map<String, String> payload = overridePayload != null ? overridePayload : point.getPayload();
                if (fieldValues != null && !fieldValues.contains(payload.get(field))) {
                    continue;
                }
                hits.add(ScoredVectorPoint.builder().id(point.getId())
                        .score(Double.isNaN(overrideScore) ? 0.8 : overrideScore).payload(payload).build());
            }
            return hits.size() <= limit ? hits : hits.subList(0, limit);
        }

        @Override
        public void clear(String collection) {
            points.clear();
        }

        @Override
        public List<cn.novel.yonren.domain.novel.model.valobj.StoredPoint> listPoints(String collection) {
            List<cn.novel.yonren.domain.novel.model.valobj.StoredPoint> result = new ArrayList<>();
            points.forEach((id, point) -> result.add(cn.novel.yonren.domain.novel.model.valobj.StoredPoint.builder()
                    .id(id).text(point.getPayload().get(ReferenceBlockIndexer.PAYLOAD_TEXT)).build()));
            return result;
        }

        @Override
        public void delete(String collection, List<String> ids) {
            ids.forEach(points::remove);
        }

        @Override
        public long count(String collection) {
            return points.size();
        }
    }
}
