package cn.novel.yonren.infrastructure.adapter.repository;

import cn.novel.yonren.domain.novel.adapter.repository.VectorStore;
import cn.novel.yonren.domain.novel.model.valobj.ScoredVectorPoint;
import cn.novel.yonren.domain.novel.model.valobj.StoredPoint;
import cn.novel.yonren.domain.novel.model.valobj.VectorCollectionInfo;
import cn.novel.yonren.domain.novel.model.valobj.VectorPoint;
import cn.novel.yonren.domain.novel.model.valobj.VectorPointPage;
import cn.novel.yonren.domain.novel.model.valobj.VectorPointView;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.CollectionStatus;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points.Condition;
import io.qdrant.client.grpc.Points.DeletePoints;
import io.qdrant.client.grpc.Points.FieldCondition;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.Match;
import io.qdrant.client.grpc.Points.PointId;
import io.qdrant.client.grpc.Points.PointsIdsList;
import io.qdrant.client.grpc.Points.PointsSelector;
import io.qdrant.client.grpc.Points.RepeatedStrings;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScrollResponse;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.UpsertPoints;
import io.qdrant.client.grpc.Points.Vector;
import io.qdrant.client.grpc.Points.Vectors;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import io.qdrant.client.grpc.Points.WithVectorsSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * VectorStore 的 Qdrant 适配实现（gRPC，6334）：领域层 curl 三动作的 Java 翻译。
 * 集合维度/距离度量首次创建定死，已存在则跳过创建（冲突维度会在 upsert 时由 Qdrant 报错暴露）。
 * v0.2 起为进阶选项：默认走内置 {@link EmbeddedVectorStore}，本实现经 story.vector-store.mode=qdrant 启用。
 */
@Component
@ConditionalOnProperty(name = "story.vector-store.mode", havingValue = "qdrant")
public class QdrantVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStore.class);
    private static final long TIMEOUT_SECONDS = 30;
    /** 滚动分页页大小：对账要把整集合读回来（不含向量，单点很轻），256 足够少批次 */
    private static final int SCROLL_PAGE_SIZE = 256;
    private static final String CURSOR_PREFIX = "qdrant-v1-";
    private static final int MAX_CURSOR_LENGTH = 100_000;
    private final String collectionPrefix;
    //熔断到哪个时刻
    private volatile long circuitOpenUntilMs = 0;
    /**
     * 单次 upsert 的点数上限：1024 维 float 单点约 4KB，取 100 点 ≈ 400KB/条消息，
     * 远低于 gRPC 默认 4MB 上限。不分批时长故事重建集合时（188 章 ≈ 900 点 ≈ 3.7MB）
     * 单条消息逼近上限，超限即整集合重建失败并降级"沿用旧集合"——恰在长故事最需要重建时失效。
     */
    private static final int UPSERT_BATCH_POINTS = 100;

    private final QdrantClient client;

    @Autowired
    public QdrantVectorStore(@Value("${story.vector-store.host:localhost}") String host,
                             @Value("${story.vector-store.port:6334}") int port,
                             @Value("${story.memory.collection-prefix:novel-memory}") String collectionPrefix) {
        validateCollectionName(collectionPrefix);
        this.collectionPrefix = collectionPrefix;
        QdrantClient created = null;
        try {
            // QdrantGrpcClient 构建时会做一次版本健康检查，Qdrant 未启动会在此抛异常；
            // 不让向量库缺席拖垮应用启动，运行时由调用方（DynamicReferenceService）降级回 LLM 选择器
            created = new QdrantClient(QdrantGrpcClient.newBuilder(host, port, false).build());
            log.info("Qdrant 客户端初始化：{}:{}（gRPC）", host, port);
        } catch (Exception e) {
            log.warn("Qdrant 客户端初始化失败（{}:{}），向量链路降级回 LLM 选择器：{}", host, port, e.getMessage());
        }
        this.client = created;
    }

    /** 保留原两参数生产构造的源兼容性；Spring 显式使用带 prefix 的构造。 */
    public QdrantVectorStore(String host, int port) {
        this(host, port, "novel-memory");
    }

    /** 测试只注入 mock，不构建 gRPC channel，也不连接任何真实服务。 */
    QdrantVectorStore(QdrantClient client) {
        this(client, "novel-memory");
    }

    QdrantVectorStore(QdrantClient client, String collectionPrefix) {
        validateCollectionName(collectionPrefix);
        this.client = Objects.requireNonNull(client);
        this.collectionPrefix = collectionPrefix;
    }

    @Override
    public String backendName() {
        return "qdrant";
    }

    @Override
    public List<VectorCollectionInfo> listCollections() {
        QdrantClient qdrant = requireClient();
        List<String> names;
        try {
            names = qdrant.listCollectionsAsync().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("向量集合列举被中断", e);
        } catch (Exception e) {
            tripCircuitOnConnectionFailure(e);
            throw new IllegalStateException("向量集合列举失败: " + e.getMessage(), e);
        }
        List<VectorCollectionInfo> result = new ArrayList<>();
        for (String name : names.stream().filter(this::isManagedCollection).distinct().sorted().toList()) {
            try {
                CollectionInfo info = qdrant.getCollectionInfoAsync(name).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                int size = vectorSizeOf(info);
                String error = info.hasOptimizerStatus() ? info.getOptimizerStatus().getError() : "";
                if (error.isBlank() && info.getStatus() == CollectionStatus.Red) {
                    error = "Qdrant 集合状态异常：" + info.getStatus();
                }
                result.add(new VectorCollectionInfo(name, size > 0 ? size : null, info.getPointsCount(),
                        error.isBlank() ? "ready" : "error", error.isBlank() ? null : error));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("向量集合列举被中断", e);
            } catch (Exception e) {
                tripCircuitOnConnectionFailure(e);
                result.add(new VectorCollectionInfo(name, null, -1, "error", e.getMessage()));
            }
        }
        return List.copyOf(result);
    }

    @Override
    public VectorPointPage pagePoints(String collection, String cursor, int limit) {
        validateCollectionName(collection);
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("向量分页 limit 必须为 1..100");
        }
        PointId offset = cursor == null ? null : decodeCursor(collection, cursor);
        QdrantClient qdrant = requireClient();
        try {
            if (!qdrant.collectionExistsAsync(collection).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return new VectorPointPage(List.of(), null);
            }
            ScrollPoints.Builder request = ScrollPoints.newBuilder()
                    .setCollectionName(collection)
                    .setLimit(limit)
                    .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                    .setWithVectors(WithVectorsSelector.newBuilder().setEnable(false).build());
            if (offset != null) {
                request.setOffset(offset);
            }
            ScrollResponse page = qdrant.scrollAsync(request.build()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<VectorPointView> points = page.getResultList().stream()
                    .map(point -> new VectorPointView(pointIdOf(point.getId()), toStringPayload(point.getPayloadMap())))
                    .toList();
            return new VectorPointPage(points, page.hasNextPageOffset()
                    ? encodeCursor(collection, page.getNextPageOffset()) : null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("向量分页被中断", e);
        } catch (Exception e) {
            tripCircuitOnConnectionFailure(e);
            throw new IllegalStateException("向量分页失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void dropCollection(String collection) {
        removeCollection(collection, "删除");
    }

    /** Qdrant 初始化失败后 client 为 null，各操作抛出可被降级链捕获的异常 */
    private QdrantClient requireClient() {
        long now = System.currentTimeMillis();
        if (client == null) {
            throw new IllegalStateException("Qdrant 客户端不可用（初始化时连接失败）");
        }
        if (now < circuitOpenUntilMs) {
            // 熔断期内：不再尝试连接，直接抛——调用方（StoryMemoryService）按既有降级链处理
            throw new IllegalStateException("Qdrant 熔断中(连接失败后的冷静期，"
                    + ((circuitOpenUntilMs - now) / 1000) + " 秒后重试)");
        }
        return client;
    }

    @Override
    public void ensureCollection(String collection, int vectorSize) {
        validateCollectionName(collection);
        QdrantClient qdrant = requireClient();
        try {
            // 先查存在性：每章检查点都会幂等重入，盲调 create 会让客户端对 ALREADY_EXISTS 打 ERROR 堆栈
            if (qdrant.collectionExistsAsync(collection).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                // 维度守卫：集合已存在时校验其维度与本次向量长度一致。
                // 原实现把冲突留给 upsert 暴露——但故事记忆的写入是 fail-soft（索引静默停更），
                // 检索失败也只降级留痕（RECALL_DEGRADED，起），维度冲突若不在此处拦截
                // 会表现为"检索一直空召回"式的隐性劣化。换 embedding 模型后这里立刻给出
                // 可行动的错误：**不同模型的向量空间互不可比，即使维度巧合相同也必须重嵌**
                int existing = vectorSizeOf(qdrant, collection);
                if (existing > 0 && existing != vectorSize) {
                    throw new VectorDimensionMismatchException("向量集合 " + collection + " 的维度是 " + existing
                            + "，与当前 embedding 模型的输出 " + vectorSize + " 维不一致。"
                            + "不同模型的向量空间互不可比（即使维度相同也不可混用）——"
                            + "请删除该集合后重新索引，或回退 embedding-api 配置");
                }
                return;
            }
            qdrant.createCollectionAsync(CreateCollection.newBuilder()
                    .setCollectionName(collection)
                    .setVectorsConfig(VectorsConfig.newBuilder()
                            .setParams(VectorParams.newBuilder()
                                    .setSize(vectorSize)
                                    .setDistance(Distance.Cosine)
                                    .build())
                            .build())
                    .build()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("向量集合已创建：{}（{} 维，Cosine）", collection, vectorSize);
        } catch (VectorDimensionMismatchException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("向量集合创建被中断", e);
        } catch (Exception e) {
            tripCircuitOnConnectionFailure(e);
            // 已存在属预期（并发竞态）；真实故障由随后的 upsert 暴露
            log.info("向量集合 {} 创建跳过（已存在或异常）：{}", collection, e.getMessage());
        }
    }

    /** 集合当前向量维度；结构缺失（老集合/并发删除）返回 0，交由后续 upsert 暴露 */
    private int vectorSizeOf(QdrantClient qdrant, String collection) throws Exception {
        CollectionInfo info = qdrant.getCollectionInfoAsync(collection).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return vectorSizeOf(info);
    }

    private int vectorSizeOf(CollectionInfo info) {
        if (!info.hasConfig() || !info.getConfig().hasParams() || !info.getConfig().getParams().hasVectorsConfig()) {
            return 0;
        }
        VectorsConfig vectors = info.getConfig().getParams().getVectorsConfig();
        long size = vectors.hasParams() ? vectors.getParams().getSize() : 0;
        if (vectors.hasParamsMap()) {
            // 管理摘要只有一个 dimensions 槽；多 named vector 不冒充单一维度。
            if (vectors.getParamsMap().getMapCount() != 1) {
                return 0;
            }
            size = vectors.getParamsMap().getMapMap().values().iterator().next().getSize();
        }
        return size > 0 && size <= Integer.MAX_VALUE ? (int) size : 0;
    }

    /** 集合已存在但维度与当前 embedding 输出不一致：必须显式失败并指引重建，禁止静默混用 */
    private static final class VectorDimensionMismatchException extends IllegalStateException {
        private VectorDimensionMismatchException(String message) {
            super(message);
        }
    }

    @Override
    public void upsert(String collection, List<VectorPoint> points) {
        validateCollectionName(collection);
        QdrantClient qdrant = requireClient();
        if (points == null || points.isEmpty()) {
            return;
        }
        // 按批切分发送：此前一次发全部点，而 1024 维 float 的单点约 4KB——
        // 长故事重建集合时（188 章 ≈ 900 点）单条 gRPC 消息逼近 4MB 默认上限，
        // 超限会让整集合重建失败并降级为"沿用旧集合"，恰好在最需要重建时失效。
        // 分批后单条消息远低于上限，语义（wait=true 逐批确认）不变。
        int batchSize = UPSERT_BATCH_POINTS;
        int written = 0;
        for (int start = 0; start < points.size(); start += batchSize) {
            List<VectorPoint> slice = points.subList(start, Math.min(start + batchSize, points.size()));
            List<PointStruct> structs = new ArrayList<>(slice.size());
            for (VectorPoint p : slice) {
                Map<String, JsonWithInt.Value> payload = new HashMap<>();
                p.getPayload().forEach((k, v) -> payload.put(k,
                        JsonWithInt.Value.newBuilder().setStringValue(v).build()));
                structs.add(PointStruct.newBuilder()
                        .setId(parsePointId(p.getId()))
                        .setVectors(Vectors.newBuilder()
                                .setVector(Vector.newBuilder().addAllData(toFloatList(p.getVector())).build())
                                .build())
                        .putAllPayload(payload)
                        .build());
            }
            try {
                qdrant.upsertAsync(UpsertPoints.newBuilder()
                        .setCollectionName(collection)
                        .addAllPoints(structs)
                        .setWait(true)
                        .build()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                written += structs.size();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("向量写入被中断", e);
            } catch (Exception e) {
                tripCircuitOnConnectionFailure(e);
                throw new IllegalStateException("向量写入失败（已写入 " + written + "/" + points.size()
                        + " 点）: " + e.getMessage(), e);
            }
        }
        log.info("向量批量写入完成：{} 个点 → {}", points.size(), collection);
    }

    @Override
    public void clear(String collection) {
        removeCollection(collection, "清空");
    }

    private void removeCollection(String collection, String action) {
        validateCollectionName(collection);
        QdrantClient qdrant = requireClient();
        try {
            if (qdrant.collectionExistsAsync(collection).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                qdrant.deleteCollectionAsync(collection).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                log.info("向量集合已{}（删除后将由下次写入重建）：{}", action, collection);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("向量集合" + action + "被中断", e);
        } catch (Exception e) {
            tripCircuitOnConnectionFailure(e);
            throw new IllegalStateException("向量集合" + action + "失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<ScoredVectorPoint> search(String collection, float[] queryVector, int limit) {
        return search(collection, queryVector, limit, null, null);
    }

    @Override
    public List<ScoredVectorPoint> search(String collection, float[] queryVector, int limit,
                                          String field, java.util.Collection<String> fieldValues) {
        validateCollectionName(collection);
        if (fieldValues != null && fieldValues.isEmpty()) {
            return List.of();
        }
        if (fieldValues != null && (field == null || field.isBlank())) {
            throw new IllegalArgumentException("白名单检索必须指定 payload 字段");
        }
        QdrantClient qdrant = requireClient();
        try {
            SearchPoints.Builder request = SearchPoints.newBuilder()
                    .setCollectionName(collection)
                    .setLimit(limit)
                    .addAllVector(toFloatList(queryVector))
                    .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build());
            if (fieldValues != null) {
                // 服务端白名单过滤：应用层事后删点会让非法候选先占满 topK。
                request.setFilter(Filter.newBuilder().addMust(Condition.newBuilder()
                        .setField(FieldCondition.newBuilder()
                                .setKey(field)
                                .setMatch(Match.newBuilder().setKeywords(
                                        RepeatedStrings.newBuilder().addAllStrings(fieldValues).build()))
                                .build())
                        .build()));
            }
            List<ScoredPoint> results = qdrant.searchAsync(request.build()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<ScoredVectorPoint> points = new ArrayList<>(results.size());
            for (ScoredPoint sp : results) {
                points.add(ScoredVectorPoint.builder()
                        .id(pointIdOf(sp.getId()))
                        .score(sp.getScore())
                        .payload(toStringPayload(sp.getPayloadMap()))
                        .build());
            }
            return points;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("向量检索被中断", e);
        } catch (Exception e) {
            tripCircuitOnConnectionFailure(e);
            throw new IllegalStateException("向量检索失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<StoredPoint> listPoints(String collection) {
        validateCollectionName(collection);
        QdrantClient qdrant = requireClient();
        List<StoredPoint> points = new ArrayList<>();
        try {
            if (!qdrant.collectionExistsAsync(collection).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                // 集合不存在 = 里面什么都没有：调用方据此走"全量写入"，无需特判异常
                return points;
            }
            PointId offset = null;
            while (true) {
                ScrollPoints.Builder request = ScrollPoints.newBuilder()
                        .setCollectionName(collection)
                        .setLimit(SCROLL_PAGE_SIZE)
                        .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                        .setWithVectors(WithVectorsSelector.newBuilder().setEnable(false).build());
                if (offset != null) {
                    request.setOffset(offset);
                }
                ScrollResponse page = qdrant.scrollAsync(request.build()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                for (RetrievedPoint point : page.getResultList()) {
                    Map<String, String> payload = toStringPayload(point.getPayloadMap());
                    points.add(StoredPoint.builder()
                            .id(pointIdOf(point.getId()))
                            .kind(payload.get("kind"))
                            .text(payload.get("text"))
                            .build());
                }
                if (!page.hasNextPageOffset()) {
                    return points;
                }
                offset = page.getNextPageOffset();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("向量列举被中断", e);
        } catch (Exception e) {
            tripCircuitOnConnectionFailure(e);
            throw new IllegalStateException("向量列举失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String collection, List<String> ids) {
        validateCollectionName(collection);
        if (ids == null || ids.isEmpty()) {
            return;
        }
        QdrantClient qdrant = requireClient();
        PointsIdsList idList = PointsIdsList.newBuilder()
                .addAllIds(ids.stream().map(QdrantVectorStore::parsePointId).toList())
                .build();
        try {
            qdrant.deleteAsync(DeletePoints.newBuilder()
                    .setCollectionName(collection)
                    .setPoints(PointsSelector.newBuilder().setPoints(idList).build())
                    .setWait(true)
                    .build()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("向量按 id 删除完成：{} 个点 ← {}", ids.size(), collection);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("向量删除被中断", e);
        } catch (Exception e) {
            tripCircuitOnConnectionFailure(e);
            throw new IllegalStateException("向量删除失败: " + e.getMessage(), e);
        }
    }

    @Override
    public long count(String collection) {
        validateCollectionName(collection);
        QdrantClient qdrant = requireClient();
        try {
            if (!qdrant.collectionExistsAsync(collection).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return 0;
            }
            return qdrant.countAsync(collection).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("向量计数被中断", e);
        } catch (Exception e) {
            tripCircuitOnConnectionFailure(e);
            throw new IllegalStateException("向量计数失败: " + e.getMessage(), e);
        }
    }

    /** 数值 id 为 Qdrant 的 uint64，必须按无符号十进制还原，不能变成负数字符串。 */
    private String pointIdOf(PointId id) {
        if (id.hasUuid()) {
            return id.getUuid();
        }
        if (id.hasNum()) {
            return Long.toUnsignedString(id.getNum());
        }
        throw new IllegalStateException("Qdrant 返回空点 id");
    }

    private static PointId parsePointId(String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("Qdrant 点 id 为空");
        }
        try {
            if (id.matches("[0-9]{1,20}")) {
                return PointId.newBuilder().setNum(Long.parseUnsignedLong(id)).build();
            }
            // uuid 缩写或路径不被接收，保留已存在的标准 UUID 字面值。
            if (!UUID.fromString(id).toString().equalsIgnoreCase(id)) {
                throw new IllegalArgumentException("Qdrant UUID 格式非法");
            }
            return PointId.newBuilder().setUuid(id).build();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Qdrant 点 id 必须为 UUID 或 uint64", e);
        }
    }

    private void validateCollectionName(String name) {
        if (name == null || name.isBlank() || name.contains("..") || name.endsWith(".")) {
            throw new IllegalArgumentException("向量集合名非法");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '-' && c != '_' && c != '.') {
                throw new IllegalArgumentException("向量集合名含非法字符");
            }
        }
        if (collectionPrefix != null && name.startsWith(collectionPrefix + "-")) {
            String suffix = name.substring(collectionPrefix.length() + 1);
            if (suffix.isBlank() || suffix.equals(".") || suffix.endsWith(".")) {
                throw new IllegalArgumentException("故事集合 suffix 非法");
            }
        }
    }

    private boolean isManagedCollection(String name) {
        try {
            validateCollectionName(name);
            return matchesPrefix(name, "novel-references") || matchesPrefix(name, "novel-reference-blocks")
                    || matchesPrefix(name, "novel-world")
                    || (name.startsWith(collectionPrefix + "-") && name.length() > collectionPrefix.length() + 1);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean matchesPrefix(String name, String prefix) {
        return name.equals(prefix) || (name.startsWith(prefix + "-") && name.length() > prefix.length() + 1);
    }

    /** cursor 封装原始 protobuf PointId；UUID/num oneof 与 scroll offset 语义完全保留。 */
    private static String encodeCursor(String collection, PointId offset) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeUTF(collection);
                out.write(offset.toByteArray());
            }
            return CURSOR_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("向量分页游标编码失败", e);
        }
    }

    private static PointId decodeCursor(String collection, String cursor) {
        if (!cursor.startsWith(CURSOR_PREFIX) || cursor.length() > MAX_CURSOR_LENGTH) {
            throw new IllegalArgumentException("向量分页游标非法");
        }
        try {
            String token = cursor.substring(CURSOR_PREFIX.length());
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token)) {
                throw new IllegalArgumentException("向量分页游标编码非法");
            }
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                if (!collection.equals(in.readUTF())) {
                    throw new IllegalArgumentException("向量分页游标不属于本集合");
                }
                PointId offset = PointId.parseFrom(in.readAllBytes());
                if ((!offset.hasUuid() && !offset.hasNum()) || !offset.getUnknownFields().asMap().isEmpty()) {
                    throw new IllegalArgumentException("向量分页游标缺少有效点 id");
                }
                if (offset.hasUuid()) {
                    parsePointId(offset.getUuid());
                }
                return offset;
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("向量分页游标非法", e);
        }
    }

    private List<Float> toFloatList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float v : vector) {
            list.add(v);
        }
        return list;
    }

    private Map<String, String> toStringPayload(Map<String, JsonWithInt.Value> payloadMap) {
        Map<String, String> result = new HashMap<>();
        payloadMap.forEach((k, v) -> {
            if (v.hasStringValue()) {
                result.put(k, v.getStringValue());
            }
        });
        return result;
    }

    /** 连接级失败（UNAVAILABLE/ConnectException）→ 打开熔断 3 分钟；其他异常不熔断（可能是欠费/鉴权，重试有意义） */
    private void tripCircuitOnConnectionFailure(Exception e) {
        String msg = String.valueOf(e.getCause() != null ? e.getCause() : e);
        if (msg.contains("UNAVAILABLE") || msg.contains("Connect") || msg.contains("Connection refused")) {
            circuitOpenUntilMs = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(3);
            log.warn("Qdrant 连接失败，进入 10 分钟熔断期（期间向量链路直接降级，不再尝试连接）：{}", msg);
        }
    }

}
