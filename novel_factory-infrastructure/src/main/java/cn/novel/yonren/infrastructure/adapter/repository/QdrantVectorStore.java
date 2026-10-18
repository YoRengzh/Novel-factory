package cn.novel.yonren.infrastructure.adapter.repository;

import cn.novel.yonren.domain.novel.adapter.repository.VectorStore;
import cn.novel.yonren.domain.novel.model.valobj.ScoredVectorPoint;
import cn.novel.yonren.domain.novel.model.valobj.StoredPoint;
import cn.novel.yonren.domain.novel.model.valobj.VectorPoint;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points.DeletePoints;
import io.qdrant.client.grpc.Points.PointId;
import io.qdrant.client.grpc.Points.PointsIdsList;
import io.qdrant.client.grpc.Points.PointsSelector;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * VectorStore 的 Qdrant 适配实现（gRPC，6334）：领域层 curl 三动作的 Java 翻译。
 * 集合维度/距离度量首次创建定死，已存在则跳过创建（冲突维度会在 upsert 时由 Qdrant 报错暴露）。
 * v0.2 起为进阶选项：默认走内置 {@link EmbeddedVectorStore}，本实现经 story.vector-store.mode=qdrant 启用。
 */
@Component
@ConditionalOnProperty(name = "story.vector-store.mode", havingValue = "qdrant", matchIfMissing = true)
public class QdrantVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStore.class);
    private static final long TIMEOUT_SECONDS = 30;
    /** 滚动分页页大小：对账要把整集合读回来（不含向量，单点很轻），256 足够少批次 */
    private static final int SCROLL_PAGE_SIZE = 256;
    //熔断到哪个时刻
    private volatile long circuitOpenUntilMs = 0;
    /**
     * 单次 upsert 的点数上限：1024 维 float 单点约 4KB，取 100 点 ≈ 400KB/条消息，
     * 远低于 gRPC 默认 4MB 上限。不分批时长故事重建集合时（188 章 ≈ 900 点 ≈ 3.7MB）
     * 单条消息逼近上限，超限即整集合重建失败并降级"沿用旧集合"——恰在长故事最需要重建时失效。
     */
    private static final int UPSERT_BATCH_POINTS = 100;

    private final QdrantClient client;

    public QdrantVectorStore(@Value("${story.vector-store.host:localhost}") String host,
                             @Value("${story.vector-store.port:6334}") int port) {
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
        if (!info.hasConfig() || !info.getConfig().hasParams() || !info.getConfig().getParams().hasVectorsConfig()) {
            return 0;
        }
        VectorParams params = info.getConfig().getParams().getVectorsConfig().getParams();
        // proto3 标量无 has 方法：未设置时 getSize() 返回 0，按"未知"处理（跳过守卫，交由 upsert 暴露）
        long size = params.getSize();
        return size > 0 ? (int) size : 0;
    }

    /** 集合已存在但维度与当前 embedding 输出不一致：必须显式失败并指引重建，禁止静默混用 */
    private static final class VectorDimensionMismatchException extends IllegalStateException {
        private VectorDimensionMismatchException(String message) {
            super(message);
        }
    }

    @Override
    public void upsert(String collection, List<VectorPoint> points) {
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
                        .setId(PointId.newBuilder().setUuid(p.getId()).build())
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
        QdrantClient qdrant = requireClient();
        try {
            if (qdrant.collectionExistsAsync(collection).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                qdrant.deleteCollectionAsync(collection).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                log.info("向量集合已清空（删除后将由下次写入重建）：{}", collection);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("向量集合清空被中断", e);
        } catch (Exception e) {
            tripCircuitOnConnectionFailure(e);
            throw new IllegalStateException("向量集合清空失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<ScoredVectorPoint> search(String collection, float[] queryVector, int limit) {
        QdrantClient qdrant = requireClient();
        try {
            List<ScoredPoint> results = qdrant.searchAsync(SearchPoints.newBuilder()
                    .setCollectionName(collection)
                    .setLimit(limit)
                    .addAllVector(toFloatList(queryVector))
                    .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                    .build()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<ScoredVectorPoint> points = new ArrayList<>(results.size());
            for (ScoredPoint sp : results) {
                points.add(ScoredVectorPoint.builder()
                        .id(sp.getId().getUuid())
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
        if (ids == null || ids.isEmpty()) {
            return;
        }
        QdrantClient qdrant = requireClient();
        PointsIdsList idList = PointsIdsList.newBuilder()
                .addAllIds(ids.stream().map(id -> PointId.newBuilder().setUuid(id).build()).toList())
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

    /** 点 id 取字面量：本链路写入的点都是 UUID，非法形态按数值型兜底，避免对账时静默丢键 */
    private String pointIdOf(PointId id) {
        return id.hasUuid() ? id.getUuid() : String.valueOf(id.getNum());
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
