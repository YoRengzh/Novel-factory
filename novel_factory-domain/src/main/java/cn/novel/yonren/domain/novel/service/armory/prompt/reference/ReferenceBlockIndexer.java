package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import cn.novel.yonren.domain.novel.adapter.llm.EmbeddingGateway;
import cn.novel.yonren.domain.novel.adapter.repository.VectorStore;
import cn.novel.yonren.domain.novel.model.valobj.StoredPoint;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.VectorPoint;
import cn.novel.yonren.domain.novel.model.valobj.prompt.ReferenceIndexEntry;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 本地语料冻结与版本化向量索引。只有完整向量校验及精确点集对账成功才发布 ready；
 * 更新、删除、策略或 embedding 空间变化均使用新集合，不删除旧集合。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReferenceBlockIndexer {

    public static final String COLLECTION_PREFIX = "novel-reference-blocks";
    /** 标题路径局部身份、完整版本 payload 与实际维度集合命名。 */
    static final int CHUNK_SCHEMA_VERSION = 2;

    static final String PAYLOAD_ATOM_ID = "atomId";
    static final String PAYLOAD_FILE = "fileName";
    static final String PAYLOAD_TITLE = "sectionTitle";
    static final String PAYLOAD_TEXT = "text";
    static final String PAYLOAD_PATH = "headingPath";
    static final String PAYLOAD_DOC_TITLE = "docTitle";
    static final String PAYLOAD_INDEX = "sectionIndex";
    static final String PAYLOAD_HASH = "contentHash";
    static final String PAYLOAD_GROUP = "group";
    static final String PAYLOAD_VERSION = "schemaVersion";
    static final String PAYLOAD_CORPUS_VERSION = "corpusVersion";

    private static final int EMBED_BATCH = 16;

    private final ReferenceIndexLoader referenceIndexLoader;
    private final PromptRuleFileLoader fileLoader;
    private final ReferenceAtomBuilder atomBuilder;
    private final ReferenceChunker chunker;
    private final EmbeddingGateway embeddingGateway;
    private final VectorStore vectorStore;

    /** 只记录经对账发布的集合；所有访问均在索引锁内，不持有请求快照。 */
    private final Set<String> readyCollections = new LinkedHashSet<>();

    /** 只读取 classpath 资料和已冻结会话的元信息，不调用 embedding 或向量存储。 */
    public ReferenceBlockSnapshot loadSnapshot(EmbeddingGateway.EmbeddingSession session) {
        Objects.requireNonNull(session, "embedding session");
        String signature = session.signature();
        Integer dimensions = session.dimensions();
        if (StringUtils.isBlank(signature)) {
            throw new IllegalStateException("embedding 空间签名不能为空");
        }
        if (dimensions != null && dimensions <= 0) {
            throw new IllegalStateException("embedding 配置维度必须为正数");
        }
        String policyVersion = atomBuilder.policyVersion();
        if (StringUtils.isBlank(policyVersion)) {
            throw new IllegalStateException("资料块策略版本不能为空");
        }

        // 目录保留全量正文用于版本；构建仅限启用来源，停用资料的依赖身份由 docId# 前缀识别。
        ReferenceSourcePolicy sourcePolicy = referenceIndexLoader.sourcePolicy();
        Set<String> disabledSources = sourcePolicy == null ? Set.of() : sourcePolicy.disabledSources();
        String loaderVersion = referenceIndexLoader.corpusVersion();
        List<ReferenceIndexEntry> allEntries = referenceIndexLoader.getAllIndex();
        if (allEntries == null || allEntries.isEmpty()) {
            // 旧测试替身只 stub getIndex；真实 loader 的 getAllIndex 始终权威。
            allEntries = referenceIndexLoader.getIndex();
        }
        if (allEntries == null) {
            allEntries = List.of();
        }
        List<Document> documents = new ArrayList<>();
        Set<String> docIds = new LinkedHashSet<>();
        Set<String> enabledDocIds = new LinkedHashSet<>();
        for (ReferenceIndexEntry entry : allEntries) {
            String docId = entry.getFileName();
            String path = entry.getRelativePath();
            if (StringUtils.isBlank(docId) || !docIds.add(docId)) {
                throw new IllegalStateException("资料目录含空白或重复 docId：" + docId);
            }
            if (StringUtils.isBlank(path)) {
                throw new IllegalStateException("资料相对路径不能为空：" + docId);
            }
            if (!disabledSources.contains(docId)) {
                enabledDocIds.add(docId);
            }
            documents.add(new Document(docId, path, entry.getTitle(), entry.getGroup(), fileLoader.load(path)));
        }
        documents.sort(Comparator.comparing(Document::docId).thenComparing(Document::relativePath));

        if (enabledDocIds.isEmpty()) {
            String corpusHash = corpusHash(documents, List.of(), policyVersion, loaderVersion);
            return new ReferenceBlockSnapshot(null, List.of(), Map.of(), corpusHash, signature,
                    policyVersion, CHUNK_SCHEMA_VERSION, dimensions);
        }

        List<ReferenceAtom> atoms = new ArrayList<>();
        Map<String, ReferenceAtom> byId = new LinkedHashMap<>();
        for (Document doc : documents) {
            if (disabledSources.contains(doc.docId())) {
                continue;
            }
            if (StringUtils.isBlank(doc.content())) {
                throw new IllegalStateException("参考资料缺失或为空：" + doc.relativePath());
            }
            for (ReferenceAtom atom : atomBuilder.build(doc.docId(), doc.title(), doc.group(), doc.content(), chunker)) {
                if (atom == null || StringUtils.isBlank(atom.atomId()) || !doc.docId().equals(atom.docId())) {
                    throw new IllegalStateException("资料原子身份不合法：" + doc.docId());
                }
                if (byId.containsKey(atom.atomId())) {
                    throw new IllegalStateException("资料含重复 atomId：" + atom.atomId());
                }
                if (StringUtils.isBlank(atom.content())
                        || !Objects.equals(atom.contentHash(), ReferenceAtomBuilder.hash(atom.content()))) {
                    throw new IllegalStateException("资料原子正文或哈希不合法：" + atom.atomId());
                }
                byId.put(atom.atomId(), atom);
                atoms.add(atom);
            }
        }
        // 只去掉明确指向停用来源的边，再验证全部启用原子，未知依赖/环仍严格失败。
        // 校验在递归排除之前执行，避免同时含坏依赖的原子被停用过滤悄悄吞掉。
        Map<String, ReferenceAtom> closureForValidation = new LinkedHashMap<>();
        for (ReferenceAtom atom : atoms) {
            closureForValidation.put(atom.atomId(), atom.withRequires(atom.requires().stream()
                    .filter(id -> !dependencyDisabled(id, byId, disabledSources)).toList()));
        }
        List<String> problems = atomBuilder.validateClosure(closureForValidation, enabledDocIds);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("资料块依赖闭包校验失败：" + String.join("；", problems));
        }
        Set<String> excluded = new LinkedHashSet<>();
        atoms.stream().filter(atom -> atom.requires().stream()
                        .anyMatch(id -> dependencyDisabled(id, byId, disabledSources)))
                .map(ReferenceAtom::atomId).forEach(excluded::add);
        boolean changed;
        do {
            changed = false;
            for (ReferenceAtom atom : atoms) {
                if (!excluded.contains(atom.atomId()) && atom.requires().stream().anyMatch(excluded::contains)) {
                    changed |= excluded.add(atom.atomId());
                }
            }
        } while (changed);
        atoms = atoms.stream().filter(atom -> !excluded.contains(atom.atomId())).toList();
        Map<String, ReferenceAtom> enabledById = new LinkedHashMap<>();
        atoms.forEach(atom -> enabledById.put(atom.atomId(), atom));
        problems = atomBuilder.validateClosure(enabledById, enabledDocIds);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("资料块启用闭包校验失败：" + String.join("；", problems));
        }
        String corpusHash = corpusHash(documents, atoms, policyVersion, loaderVersion);
        return new ReferenceBlockSnapshot(null, atoms, enabledById, corpusHash, signature,
                policyVersion, CHUNK_SCHEMA_VERSION, dimensions);
    }

    private static boolean dependencyDisabled(String atomId, Map<String, ReferenceAtom> atoms,
                                               Set<String> disabledSources) {
        ReferenceAtom target = atoms.get(atomId);
        if (target != null) {
            return disabledSources.contains(target.docId());
        }
        if (atomId == null) {
            return false;
        }
        int separator = atomId.indexOf('#');
        return separator > 0 && separator < atomId.length() - 1
                && disabledSources.contains(atomId.substring(0, separator));
    }

    public record IndexPreview(String corpusHash, String policyVersion, String embeddingSignature,
                               Integer dimensions, String collection, int documents, int atoms,
                               long embeddingChars, int embeddingBatches) {
    }

    /** 本地迁移预览：不调用 embedding、不读写向量库。 */
    public IndexPreview preview(EmbeddingGateway.EmbeddingSession session) {
        ReferenceBlockSnapshot snapshot = loadSnapshot(session);
        return new IndexPreview(snapshot.corpusHash(), snapshot.policyVersion(), snapshot.signature(),
                snapshot.dimensions(), snapshot.dimensions() == null ? null : collectionName(snapshot, snapshot.dimensions()),
                (int) snapshot.atoms().stream().map(ReferenceAtom::docId).distinct().count(), snapshot.atoms().size(),
                snapshot.atoms().stream().mapToLong(atom -> atom.embedText().length()).sum(),
                (snapshot.atoms().size() + EMBED_BATCH - 1) / EMBED_BATCH);
    }

    /** 兼容独立建索引调用；检索请求必须走带快照、会话和实际查询维度的重载。 */
    public ReferenceBlockSnapshot ensureIndex(StoryVO.Module module) {
        return ensureIndex(embeddingGateway.session(module));
    }

    /** 管理重建复用已冻结会话；未配置维度时由首批合法向量推断。 */
    public ReferenceBlockSnapshot ensureIndex(EmbeddingGateway.EmbeddingSession session) {
        ReferenceBlockSnapshot snapshot = loadSnapshot(session);
        return buildIndex(snapshot, session, snapshot.dimensions());
    }

    /** 只清进程内 ready 集合，不调用 embedding、不删除任何存储集合。 */
    public synchronized void invalidate() {
        readyCollections.clear();
    }

    ReferenceBlockSnapshot ensureIndex(ReferenceBlockSnapshot snapshot,
                                       EmbeddingGateway.EmbeddingSession session, int queryDimensions) {
        if (queryDimensions <= 0) {
            throw new IllegalStateException("查询向量维度必须为正数");
        }
        return buildIndex(snapshot, session, queryDimensions);
    }

    private synchronized ReferenceBlockSnapshot buildIndex(ReferenceBlockSnapshot snapshot,
                                                           EmbeddingGateway.EmbeddingSession session,
                                                           Integer actualDimensions) {
        if (!snapshot.signature().equals(session.signature())
                || !Objects.equals(snapshot.dimensions(), session.dimensions())) {
            throw new IllegalStateException("embedding 会话与准备阶段的空间不一致");
        }
        if (snapshot.dimensions() != null && actualDimensions != null
                && !snapshot.dimensions().equals(actualDimensions)) {
            throw new IllegalStateException("查询维度与 embedding 声明维度不一致");
        }
        if (snapshot.atoms().isEmpty()) {
            return snapshot; // 没有候选时不写空集合，也不需要推断 embedding 维度。
        }
        String collection = actualDimensions == null ? null : collectionName(snapshot, actualDimensions);
        if (collection != null && readyCollections.contains(collection)) {
            try {
                verifyStoredPoints(collection, snapshot);
                return snapshot.indexed(collection, actualDimensions);
            } catch (RuntimeException e) {
                readyCollections.remove(collection);
                throw e;
            }
        }

        if (collection != null && vectorStore.count(collection) > 0) {
            // 进程重启后复用已完整的不可变版本；残缺或外部改动的集合拒绝隐式覆盖。
            verifyStoredPoints(collection, snapshot);
            readyCollections.add(collection);
            log.info("资料块索引复用已存版本：{}，未重新 embedding", collection);
            return snapshot.indexed(collection, actualDimensions);
        }

        // 全部向量先校验成功，再进行任何写入，避免半批非法向量污染集合。
        List<VectorPoint> points = vectorize(snapshot, session, actualDimensions);
        int dimensions = points.get(0).getVector().length;
        collection = collectionName(snapshot, dimensions);
        try {
            vectorStore.ensureCollection(collection, dimensions);
            vectorStore.upsert(collection, points);
            verifyStoredPoints(collection, snapshot);
            readyCollections.add(collection);
        } catch (RuntimeException e) {
            readyCollections.remove(collection);
            throw e;
        }
        log.info("资料块索引就绪：{} 原子，集合 {}，版本 {}，维度 {}",
                points.size(), collection, snapshot.version(), dimensions);
        return snapshot.indexed(collection, dimensions);
    }

    private List<VectorPoint> vectorize(ReferenceBlockSnapshot snapshot,
                                        EmbeddingGateway.EmbeddingSession session, Integer expectedDimensions) {
        List<ReferenceAtom> atoms = snapshot.atoms();
        List<VectorPoint> points = new ArrayList<>(atoms.size());
        Integer dimensions = expectedDimensions;
        for (int i = 0; i < atoms.size(); i += EMBED_BATCH) {
            List<ReferenceAtom> batch = atoms.subList(i, Math.min(i + EMBED_BATCH, atoms.size()));
            List<float[]> vectors = session.embed(batch.stream().map(ReferenceAtom::embedText).toList());
            if (vectors == null || vectors.size() != batch.size()) {
                throw new IllegalStateException("embedding 返回数量不匹配：期望 " + batch.size()
                        + "，实际 " + (vectors == null ? 0 : vectors.size()));
            }
            for (int j = 0; j < batch.size(); j++) {
                ReferenceAtom atom = batch.get(j);
                float[] vector = vectors.get(j);
                validateVector(vector, dimensions, atom.atomId());
                if (dimensions == null) {
                    dimensions = vector.length;
                }
                points.add(VectorPoint.builder().id(stableId(atom.atomId())).vector(vector.clone())
                        .payload(payload(atom, snapshot)).build());
            }
        }
        return points;
    }

    static void validateVector(float[] vector, Integer expectedDimensions, String label) {
        if (vector == null || vector.length == 0) {
            throw new IllegalStateException("embedding 返回空向量：" + label);
        }
        if (expectedDimensions != null && vector.length != expectedDimensions) {
            throw new IllegalStateException("embedding 维度不匹配：期望 " + expectedDimensions
                    + "，实际 " + vector.length + "（" + label + "）");
        }
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalStateException("embedding 返回非法数值：" + label);
            }
        }
    }

    private void verifyStoredPoints(String collection, ReferenceBlockSnapshot snapshot) {
        long count = vectorStore.count(collection);
        if (count != snapshot.atoms().size()) {
            throw new IllegalStateException("资料块索引点数不匹配：期望 " + snapshot.atoms().size()
                    + "，实际 " + count + "（集合 " + collection + "）");
        }
        Map<String, ReferenceAtom> expected = new LinkedHashMap<>();
        for (ReferenceAtom atom : snapshot.atoms()) {
            String id = stableId(atom.atomId());
            if (expected.containsKey(id)) {
                throw new IllegalStateException("资料块点 ID 冲突：" + id);
            }
            expected.put(id, atom);
        }
        List<StoredPoint> stored = vectorStore.listPoints(collection);
        if (stored == null || stored.size() != expected.size()) {
            throw new IllegalStateException("资料块索引点集不完整：" + collection);
        }
        Set<String> actual = new LinkedHashSet<>();
        for (StoredPoint point : stored) {
            ReferenceAtom atom = point == null ? null : expected.get(point.getId());
            if (atom == null || !actual.add(point.getId()) || !atom.content().equals(point.getText())) {
                throw new IllegalStateException("资料块索引点身份或正文不匹配：" + collection);
            }
        }
        if (!actual.equals(expected.keySet())) {
            throw new IllegalStateException("资料块索引点集不匹配：" + collection);
        }
    }

    static Map<String, String> payload(ReferenceAtom atom, ReferenceBlockSnapshot snapshot) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put(PAYLOAD_ATOM_ID, atom.atomId());
        payload.put(PAYLOAD_FILE, atom.docId());
        payload.put(PAYLOAD_TITLE, atom.headingPath());
        payload.put(PAYLOAD_TEXT, atom.content());
        payload.put(PAYLOAD_PATH, atom.headingPath());
        payload.put(PAYLOAD_DOC_TITLE, atom.docTitle());
        payload.put(PAYLOAD_INDEX, String.valueOf(atom.sectionIndex()));
        payload.put(PAYLOAD_HASH, atom.contentHash());
        payload.put(PAYLOAD_GROUP, atom.group());
        payload.put(PAYLOAD_VERSION, String.valueOf(snapshot.schemaVersion()));
        payload.put(PAYLOAD_CORPUS_VERSION, snapshot.version());
        return java.util.Collections.unmodifiableMap(payload);
    }

    /** 集合定位协议；actualDimensions 来自冻结配置或实际返回向量。 */
    public static String collectionName(ReferenceBlockSnapshot snapshot, int dimensions) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("集合维度必须为正数");
        }
        return COLLECTION_PREFIX + "-" + snapshot.version() + "-d" + dimensions;
    }

    /** 长度前缀避免字段串接歧义；完整全文和全部身份、依赖、策略语义都参与 SHA-256。 */
    private static String corpusHash(List<Document> documents, List<ReferenceAtom> atoms, String policyVersion,
                                     String loaderVersion) {
        MessageDigest digest = newDigest();
        update(digest, String.valueOf(CHUNK_SCHEMA_VERSION));
        update(digest, policyVersion);
        update(digest, loaderVersion);
        update(digest, String.valueOf(documents.size()));
        for (Document doc : documents) {
            update(digest, doc.docId());
            update(digest, doc.relativePath());
            update(digest, doc.title());
            update(digest, doc.group());
            update(digest, doc.content());
        }
        update(digest, String.valueOf(atoms.size()));
        for (ReferenceAtom atom : atoms) {
            update(digest, atom.docId());
            update(digest, atom.atomId());
            update(digest, atom.docTitle());
            update(digest, atom.headingPath());
            update(digest, String.valueOf(atom.sectionIndex()));
            update(digest, atom.content());
            update(digest, atom.contentHash());
            update(digest, atom.group());
            update(digest, String.valueOf(atom.standalone()));
            update(digest, String.valueOf(atom.requires().size()));
            atom.requires().forEach(id -> update(digest, id));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String digestOf(String... values) {
        MessageDigest digest = newDigest();
        for (String value : values) {
            update(digest, value);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("资料语料摘要计算失败", e);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes == null ? -1 : bytes.length).array());
        if (bytes != null) {
            digest.update(bytes);
        }
    }

    static String stableId(String atomId) {
        return UUID.nameUUIDFromBytes(atomId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private record Document(String docId, String relativePath, String title, String group, String content) {
    }
}
