package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import cn.novel.yonren.domain.novel.adapter.llm.EmbeddingGateway;
import cn.novel.yonren.domain.novel.adapter.repository.VectorStore;
import cn.novel.yonren.domain.novel.model.valobj.StoredPoint;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.VectorPoint;
import cn.novel.yonren.domain.novel.model.valobj.prompt.ReferenceChunk;
import cn.novel.yonren.domain.novel.model.valobj.prompt.ReferenceIndexEntry;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 文件级参考索引：完整向量校验后按稳定 file#节序 对账，重启可复用完整版本。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReferenceVectorIndexer {

    public static final String COLLECTION = "novel-references";
    static final String PAYLOAD_FILE = "fileName";
    static final String PAYLOAD_TITLE = "sectionTitle";
    static final String PAYLOAD_TEXT = "text";
    static final String PAYLOAD_CORPUS_VERSION = "corpusVersion";
    static final String PAYLOAD_INDEX_VERSION = "indexVersion";
    static final String PAYLOAD_SIGNATURE = "embeddingSignature";
    static final String PAYLOAD_DIMENSIONS = "dimensions";
    private static final String KIND_PREFIX = "source-index:";
    private static final String SCHEMA = "legacy-reference-v2";
    private static final int BATCH_SIZE = 16;

    private final ReferenceIndexLoader referenceIndexLoader;
    private final PromptRuleFileLoader fileLoader;
    private final ReferenceChunker chunker;
    private final EmbeddingGateway embeddingGateway;
    private final VectorStore vectorStore;

    private volatile String readyVersion;

    /** 显式构建当前语料；空语料清旧集合，不创建 embedding 会话。 */
    public synchronized int index(StoryVO.Module module) {
        Corpus corpus = loadCorpus();
        if (corpus.chunks().isEmpty()) {
            return clearEmptyIndex();
        }
        return indexCorpus(corpus, requireSession(embeddingGateway.session(module)), null, false);
    }

    /** 管理操作可复用执行开始时冻结的会话，所有批次不再重新解析模型配置。 */
    public synchronized int index(StoryVO.Module module, EmbeddingGateway.EmbeddingSession session) {
        Corpus corpus = loadCorpus();
        if (corpus.chunks().isEmpty()) {
            return clearEmptyIndex();
        }
        return indexCorpus(corpus, requireSession(session), null, false);
    }

    /** 明确授权重建 embedding 空间/维度；向量全部合法后才清旧集合。 */
    public synchronized int rebuild(StoryVO.Module module) {
        Corpus corpus = loadCorpus();
        if (corpus.chunks().isEmpty()) {
            return clearEmptyIndex();
        }
        return indexCorpus(corpus, requireSession(embeddingGateway.session(module)), null, true);
    }

    public synchronized int rebuild(StoryVO.Module module, EmbeddingGateway.EmbeddingSession session) {
        Corpus corpus = loadCorpus();
        if (corpus.chunks().isEmpty()) {
            return clearEmptyIndex();
        }
        return indexCorpus(corpus, requireSession(session), null, true);
    }

    /** 只失效进程内 ready 标记，不调用 embedding 或修改存储。 */
    public synchronized void invalidate() {
        readyVersion = null;
    }

    public Set<String> enabledSources() {
        List<ReferenceIndexEntry> entries = referenceIndexLoader.getIndex();
        if (entries == null || entries.isEmpty()) {
            return Set.of();
        }
        ReferenceSourcePolicy policy = referenceIndexLoader.sourcePolicy();
        Set<String> sources = new LinkedHashSet<>();
        for (ReferenceIndexEntry entry : entries) {
            if (entry != null && StringUtils.isNotBlank(entry.getFileName())
                    && (policy == null || policy.isEnabled(entry.getFileName()))) {
                sources.add(entry.getFileName());
            }
        }
        return Set.copyOf(sources);
    }

    /** 全文、来源启停及切块 schema 的本地版本，不调用 embedding。 */
    public String corpusVersion() {
        return loadCorpus().version();
    }

    String versionKey(EmbeddingGateway.EmbeddingSession session) {
        requireSession(session);
        return ReferenceBlockIndexer.digestOf(corpusVersion(), session.signature(),
                String.valueOf(session.dimensions()));
    }

    /** 普通检索在付费向量化之前检查已存空间；配置改变必须由管理显式重建。 */
    synchronized void checkSpace(EmbeddingGateway.EmbeddingSession session) {
        checkSpace(storedPoints(), requireSession(session), session.dimensions());
    }

    /** 查询已确定实际维度：精确版本匹配时不重嵌，语料变更才自动对账。 */
    synchronized String ensureIndex(EmbeddingGateway.EmbeddingSession session, int queryDimensions) {
        requireSession(session);
        if (queryDimensions <= 0) {
            throw new IllegalStateException("查询向量维度必须为正数");
        }
        Corpus corpus = loadCorpus();
        if (corpus.chunks().isEmpty()) {
            clearEmptyIndex();
            return null;
        }
        List<StoredPoint> stored = storedPoints();
        checkSpace(stored, session, queryDimensions);
        String version = indexVersion(corpus, session, queryDimensions);
        if (matchesStored(stored, corpus, session, queryDimensions) && vectorStore.count(COLLECTION) == stored.size()) {
            vectorStore.ensureCollection(COLLECTION, queryDimensions);
            readyVersion = version;
            return version;
        }
        readyVersion = null;
        indexCorpus(corpus, session, queryDimensions, false);
        return readyVersion;
    }

    private Corpus loadCorpus() {
        List<ReferenceIndexEntry> entries = referenceIndexLoader.getIndex();
        if (entries == null) {
            entries = List.of();
        }
        ReferenceSourcePolicy policy = referenceIndexLoader.sourcePolicy();
        List<ReferenceIndexEntry> sorted = entries.stream().filter(Objects::nonNull)
                .sorted(Comparator.comparing(ReferenceIndexEntry::getFileName)).toList();
        List<IndexedChunk> chunks = new ArrayList<>();
        List<String> versionFields = new ArrayList<>();
        versionFields.add(SCHEMA);
        versionFields.add(referenceIndexLoader.corpusVersion());
        Set<String> names = new LinkedHashSet<>();
        for (ReferenceIndexEntry entry : sorted) {
            String file = entry.getFileName();
            if (StringUtils.isBlank(file) || !names.add(file)) {
                throw new IllegalStateException("资料目录含空白或重复 fileName：" + file);
            }
            if (policy != null && !policy.isEnabled(file)) {
                continue;
            }
            String content = fileLoader.load(entry.getRelativePath());
            versionFields.add(file);
            versionFields.add(entry.getRelativePath());
            versionFields.add(content);
            if (StringUtils.isBlank(content)) {
                continue;
            }
            List<ReferenceChunk> sections = chunker.chunk(file, content);
            for (int section = 0; section < sections.size(); section++) {
                chunks.add(new IndexedChunk(sections.get(section), section));
            }
        }
        return new Corpus(List.copyOf(chunks), ReferenceBlockIndexer.digestOf(versionFields.toArray(String[]::new)));
    }

    private int indexCorpus(Corpus corpus, EmbeddingGateway.EmbeddingSession session,
                            Integer expectedDimensions, boolean rebuild) {
        readyVersion = null;
        List<StoredPoint> stored = storedPoints();
        Integer dimensions = expectedDimensions == null ? session.dimensions() : expectedDimensions;
        if (!rebuild) {
            checkSpace(stored, session, dimensions);
        }
        List<float[]> vectors = new ArrayList<>(corpus.chunks().size());
        for (int start = 0; start < corpus.chunks().size(); start += BATCH_SIZE) {
            List<IndexedChunk> batch = corpus.chunks().subList(start, Math.min(start + BATCH_SIZE, corpus.chunks().size()));
            List<float[]> returned = session.embed(batch.stream().map(value -> value.chunk().embedText()).toList());
            if (returned == null || returned.size() != batch.size()) {
                throw new IllegalStateException("embedding 返回数量不匹配：期望 " + batch.size()
                        + "，实际 " + (returned == null ? 0 : returned.size()));
            }
            for (int offset = 0; offset < batch.size(); offset++) {
                float[] vector = returned.get(offset);
                ReferenceBlockIndexer.validateVector(vector, dimensions, batch.get(offset).chunk().fileName());
                if (dimensions == null) {
                    dimensions = vector.length;
                }
                vectors.add(vector.clone());
            }
        }
        if (session.dimensions() != null && !session.dimensions().equals(dimensions)) {
            throw new IllegalStateException("实际 embedding 维度与冻结配置不一致");
        }
        if (!rebuild) {
            checkSpace(stored, session, dimensions);
        }
        String version = indexVersion(corpus, session, dimensions);
        String kind = storedKind(version, session.signature(), dimensions);
        List<VectorPoint> points = new ArrayList<>(vectors.size());
        Set<String> expectedIds = new LinkedHashSet<>();
        for (int i = 0; i < vectors.size(); i++) {
            IndexedChunk value = corpus.chunks().get(i);
            ReferenceChunk chunk = value.chunk();
            String id = stableId(chunk.fileName(), value.sectionIndex());
            if (!expectedIds.add(id)) {
                throw new IllegalStateException("参考资料点 ID 冲突：" + id);
            }
            points.add(VectorPoint.builder().id(id).vector(vectors.get(i)).payload(Map.of(
                    PAYLOAD_FILE, chunk.fileName(), PAYLOAD_TITLE, chunk.sectionTitle(),
                    PAYLOAD_TEXT, chunk.content(), PAYLOAD_CORPUS_VERSION, corpus.version(),
                    PAYLOAD_INDEX_VERSION, version, PAYLOAD_SIGNATURE, session.signature(),
                    PAYLOAD_DIMENSIONS, String.valueOf(dimensions), "kind", kind,
                    "sectionIndex", String.valueOf(value.sectionIndex()))).build());
        }

        // 所有 embedding 成功且全部校验通过后才写入/删除，失败不清旧资料索引。
        if (rebuild) {
            vectorStore.clear(COLLECTION);
        }
        vectorStore.ensureCollection(COLLECTION, dimensions);
        vectorStore.upsert(COLLECTION, points);
        List<String> staleIds = stored.stream().map(StoredPoint::getId).filter(id -> !expectedIds.contains(id)).toList();
        if (!staleIds.isEmpty()) {
            vectorStore.delete(COLLECTION, staleIds);
        }
        List<StoredPoint> actual = storedPoints();
        if (vectorStore.count(COLLECTION) != points.size() || !matchesStored(actual, corpus, session, dimensions)) {
            throw new IllegalStateException("参考资料索引对账失败：期望 " + points.size() + " 点，实际 "
                    + vectorStore.count(COLLECTION));
        }
        readyVersion = version;
        log.info("参考资料向量索引就绪：{} 点，清理 {} 旧点，版本 {}，维度 {}",
                points.size(), staleIds.size(), version, dimensions);
        return points.size();
    }

    private int clearEmptyIndex() {
        readyVersion = null;
        vectorStore.clear(COLLECTION);
        if (vectorStore.count(COLLECTION) != 0 || !storedPoints().isEmpty()) {
            throw new IllegalStateException("空参考资料索引清理后仍残留旧点");
        }
        return 0;
    }

    private List<StoredPoint> storedPoints() {
        List<StoredPoint> stored = vectorStore.listPoints(COLLECTION);
        if (stored == null || stored.stream().anyMatch(point -> point == null || StringUtils.isBlank(point.getId()))) {
            throw new IllegalStateException("参考资料索引点集响应不合法");
        }
        return stored;
    }

    private static void checkSpace(List<StoredPoint> stored, EmbeddingGateway.EmbeddingSession session,
                                   Integer dimensions) {
        String signatureHash = ReferenceBlockIndexer.digestOf(session.signature());
        for (StoredPoint point : stored) {
            String kind = point.getKind();
            if (kind == null) {
                continue; // 老 schema 没有元信息，首次升级通过完整对账替换。
            }
            String[] fields = kind.split(":", -1);
            if (fields.length != 4 || !kind.startsWith(KIND_PREFIX)) {
                throw new IllegalStateException("参考资料索引版本元信息损坏，请显式重建");
            }
            int storedDimensions;
            try {
                storedDimensions = Integer.parseInt(fields[3]);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("参考资料索引维度元信息损坏，请显式重建", e);
            }
            if (!signatureHash.equals(fields[2]) || storedDimensions <= 0
                    || (dimensions != null && storedDimensions != dimensions)) {
                throw new IllegalStateException("参考资料 embedding 空间或维度已变化，请先预览并显式重建共享参考索引");
            }
        }
    }

    private static boolean matchesStored(List<StoredPoint> stored, Corpus corpus,
                                         EmbeddingGateway.EmbeddingSession session, int dimensions) {
        if (stored.size() != corpus.chunks().size()) {
            return false;
        }
        Map<String, String> expected = new LinkedHashMap<>();
        for (IndexedChunk value : corpus.chunks()) {
            expected.put(stableId(value.chunk().fileName(), value.sectionIndex()), value.chunk().content());
        }
        String kind = storedKind(indexVersion(corpus, session, dimensions), session.signature(), dimensions);
        Set<String> actual = new LinkedHashSet<>();
        for (StoredPoint point : stored) {
            if (!actual.add(point.getId()) || !Objects.equals(expected.get(point.getId()), point.getText())
                    || !kind.equals(point.getKind())) {
                return false;
            }
        }
        return actual.equals(expected.keySet());
    }

    private static EmbeddingGateway.EmbeddingSession requireSession(EmbeddingGateway.EmbeddingSession session) {
        Objects.requireNonNull(session, "embedding session");
        if (StringUtils.isBlank(session.signature()) || (session.dimensions() != null && session.dimensions() <= 0)) {
            throw new IllegalStateException("embedding 会话必须提供非空空间签名及有效维度");
        }
        return session;
    }

    private static String indexVersion(Corpus corpus, EmbeddingGateway.EmbeddingSession session, int dimensions) {
        return ReferenceBlockIndexer.digestOf(SCHEMA, corpus.version(), session.signature(), String.valueOf(dimensions));
    }

    private static String storedKind(String version, String signature, int dimensions) {
        // StoredPoint 端口仅公开 id/kind/text；类型化 kind 让重启对账能核验完整版本和向量空间。
        return KIND_PREFIX + version + ":" + ReferenceBlockIndexer.digestOf(signature) + ":" + dimensions;
    }

    static String stableId(String fileName, int sectionIndex) {
        return UUID.nameUUIDFromBytes((fileName + "#" + sectionIndex).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private record IndexedChunk(ReferenceChunk chunk, int sectionIndex) {
    }

    private record Corpus(List<IndexedChunk> chunks, String version) {
    }
}
