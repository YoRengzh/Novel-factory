package cn.novel.yonren.infrastructure.adapter.repository;

import cn.novel.yonren.domain.novel.adapter.repository.VectorStore;
import cn.novel.yonren.domain.novel.model.valobj.ScoredVectorPoint;
import cn.novel.yonren.domain.novel.model.valobj.StoredPoint;
import cn.novel.yonren.domain.novel.model.valobj.VectorCollectionInfo;
import cn.novel.yonren.domain.novel.model.valobj.VectorPoint;
import cn.novel.yonren.domain.novel.model.valobj.VectorPointPage;
import cn.novel.yonren.domain.novel.model.valobj.VectorPointView;
import com.alibaba.fastjson2.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VectorStore 的内置实现（v0.2 默认）：进程内余弦索引 + 每集合一个二进制文件落盘，
 * 替代外部 Qdrant 进程——发布物自此零向量库依赖。
 *
 * 规模前提（选型依据，换量级时重估）：单故事集合点数较少，暴力遍历提供精确检索。
 * HNSW 等近似索引更适合十万点级或高并发场景，本实现优先降低本地部署成本。
 * 任意 embedding 维度（1024/2048/3072…）天然支持：维度是每点自描述的运行时数据，非结构绑定。
 *
 * 分数语义对齐：Qdrant 用 {@code Distance.Cosine}（内部归一化，越大越相近）。
 * 本实现写入时归一化为单位向量、检索时点积——单位向量的点积在数学上恒等于余弦相似度，
 * 召回排序与 Qdrant 时代严格一致；少一次检索期除法，代价是写入期一次性归一化。
 *
 * 落盘契约：每集合一个文件（验证后使用原名，不做可能碰撞的字符替换），写全部走
 * 临时文件 + 原子替换（同 {@link StoryRepository} 的崩溃安全模式），读走内存快照。
 * 全实例锁覆盖读、读改写、落盘和删除，避免旧快照在删除后复活集合。
 * 文件格式见 {@link #MAGIC}。
 *
 * 集合路由：故事集合（前缀 novel-memory-）写入根目录下对应故事目录的 memory/，
 * 世界/参考集合（novel-world- / novel-references）写入根目录的 vector-store/ 共享区。
 * 由根目录配置驱动（默认 data/vector-store），不耦合 StoryRepository 的目录结构细节。
 */
@Component
@ConditionalOnProperty(name = "story.vector-store.mode", havingValue = "embedded", matchIfMissing = true)
public class EmbeddedVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedVectorStore.class);

    /** 文件模数：快速识别"这不是我的格式"，防误读任意文件后解出乱码 */
    private static final int MAGIC = 0x4E565442; // "NVTB" (NoVel Vector Bin)
    /** 格式版本号：将来字段增删时按版本分支解析，旧文件可识别可迁移 */
    private static final int FORMAT_VERSION = 1;
    /** 已知共享集合的落盘目录名；故事集合按前缀路由进故事目录 */
    private static final String SHARED_DIR = "vector-store";
    /** 文件扩展名 */
    private static final String EXT = ".nvbin";
    private static final String CURSOR_PREFIX = "embedded-v1-";
    private static final int MAX_CURSOR_LENGTH = 180_000;
    private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};

    /** 共享集合（世界/参考资料）的落盘根目录 */
    private final Path rootDir;
    /** 故事集合的落盘根（与 StoryRepository 的 WORKSPACE_DIR 同源，保证"删故事目录=全清"） */
    private final Path workspaceDir;
    /** 故事集合名前缀（与 StoryMemoryService.collectionOf 的构造约定一致） */
    private final String collectionPrefix;
    /** 内存索引：集合名 → (点 id → 点)；读路径只读此快照，写路径改后整体替换 */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Stored>> collections = new ConcurrentHashMap<>();
    /** 集合声明维度伴随表：空集合（刚 ensureCollection 完还没 upsert）也有维度，守卫依赖它 */
    private final ConcurrentHashMap<String, Integer> dimensions = new ConcurrentHashMap<>();

    /**
     * 生产构造：占位符注入两个落盘根。
     * ⚠️ 类里有两个构造器（本构造 + 包私有的测试构造），Spring 多构造器时必须
     * 显式标 @Autowired 指定注入入口，否则会去选无参构造并抛 NoSuchMethodException。
     */
    @Autowired
    public EmbeddedVectorStore(@Value("${story.vector-store.root-dir:data/vector-store}") String rootDir,
                               @Value("${story.memory.collection-prefix:novel-memory}") String collectionPrefix) {
        this(Path.of(rootDir), Path.of(StorageKeys.WORKSPACE_DIR), collectionPrefix);
    }

    /** 测试用构造：显式指定两个落盘根，隔离于真实仓库目录 */
    EmbeddedVectorStore(Path rootDir, Path workspaceDir, String collectionPrefix) {
        validateName(collectionPrefix);
        this.rootDir = rootDir.toAbsolutePath().normalize();
        this.workspaceDir = workspaceDir.toAbsolutePath().normalize();
        this.collectionPrefix = collectionPrefix;
        log.info("内置向量库初始化：共享根 {}，故事根 {}（mode=embedded，零外部进程）",
                rootDir.toAbsolutePath(), workspaceDir.toAbsolutePath());
    }

    /** 单点的内存形态：归一化后的向量 + 业务 payload */
    private record Stored(float[] vector, Map<String, String> payload) {
        private Stored {
            vector = vector.clone();
            payload = payload == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        }
    }

    // ==================== 管理只读视图与删除 ====================

    @Override
    public synchronized String backendName() {
        return "embedded";
    }

    @Override
    public synchronized List<VectorCollectionInfo> listCollections() {
        Map<String, VectorCollectionInfo> result = new TreeMap<>();
        Path shared = checkedPath(rootDir, rootDir.resolve(SHARED_DIR));
        try {
            if (Files.isDirectory(shared, NOFOLLOW)) {
                try (var files = Files.list(shared)) {
                    for (Path file : files.toList()) {
                        String name = collectionNameOf(file);
                        if (name == null || !isManagedCollection(name) || name.startsWith(collectionPrefix + "-")) {
                            continue;
                        }
                        result.put(name, infoOf(name));
                    }
                }
            }
            checkedPath(workspaceDir, workspaceDir);
            if (Files.isDirectory(workspaceDir, NOFOLLOW)) {
                try (var stories = Files.list(workspaceDir)) {
                    for (Path story : stories.toList()) {
                        // 不跟随故事链接，也不扫描正文/任意子目录，仅检查约定的向量文件。
                        if (!Files.isDirectory(story, NOFOLLOW)) {
                            continue;
                        }
                        String name = collectionPrefix + "-" + story.getFileName();
                        try {
                            Path file = fileOf(name);
                            if (Files.exists(file, NOFOLLOW)) {
                                result.put(name, infoOf(name));
                            }
                        } catch (IllegalArgumentException e) {
                            // 不合法的磁盘目录不成为管理入口；合法名称的链接/越界则保留 error 留痕。
                            if (isValidName(name) && isValidName(story.getFileName().toString())) {
                                result.put(name, errorInfo(name, e));
                            }
                        } catch (IllegalStateException e) {
                            result.put(name, errorInfo(name, e));
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("向量集合列举失败: " + e.getMessage(), e);
        }
        return List.copyOf(result.values());
    }

    @Override
    public synchronized VectorPointPage pagePoints(String collection, String cursor, int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("向量分页 limit 必须为 1..100");
        }
        fileOf(collection);
        String afterId = cursor == null ? null : decodeCursor(collection, cursor);
        CollectionState state = readFromDisk(collection);
        List<VectorPointView> points = state.points.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(e -> afterId == null || e.getKey().compareTo(afterId) > 0)
                .limit(limit + 1L)
                .map(e -> new VectorPointView(e.getKey(), e.getValue().payload()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        String nextCursor = null;
        if (points.size() > limit) {
            points.remove(points.size() - 1);
            nextCursor = encodeCursor(collection, points.get(points.size() - 1).id());
        }
        return new VectorPointPage(points, nextCursor);
    }

    @Override
    public synchronized void dropCollection(String collection) {
        removeCollection(collection, "删除");
    }

    private VectorCollectionInfo infoOf(String name) {
        try {
            // 管理摘要读取磁盘快照而非热缓存，运行中的文件损坏同样必须可见。
            CollectionState state = readFromDisk(name);
            return new VectorCollectionInfo(name, state.vectorSize > 0 ? state.vectorSize : null,
                    state.points.size(), "ready", null);
        } catch (RuntimeException e) {
            return errorInfo(name, e);
        }
    }

    private VectorCollectionInfo errorInfo(String name, RuntimeException e) {
        return new VectorCollectionInfo(name, null, -1, "error", e.getMessage());
    }

    // ==================== 既有业务端口方法 ====================

    @Override
    public synchronized void ensureCollection(String collection, int vectorSize) {
        if (vectorSize <= 0) {
            throw new IllegalStateException("向量集合 " + collection + " 的声明维度非法：" + vectorSize);
        }
        Path file = fileOf(collection);
        if (Files.exists(file)) {
            // 维度守卫：语义与 QdrantVectorStore 完全一致——换 embedding 模型后维度不同
            // 必须显式失败指引重建，禁止静默混用（不同模型向量空间互不可比，维度相同也不可混用）
            CollectionState state = load(collection);
            if (state.vectorSize > 0 && state.vectorSize != vectorSize) {
                throw new IllegalStateException("向量集合 " + collection + " 的维度是 " + state.vectorSize
                        + "，与当前 embedding 模型的输出 " + vectorSize + " 维不一致。"
                        + "不同模型的向量空间互不可比（即使维度相同也不可混用）——"
                        + "请删除该集合文件后重新索引，或回退 embedding-api 配置");
            }
            if (state.vectorSize <= 0) {
                // 旧文件无维度记录（理论上不会发生）：按声明值补写头部，宁可多写不可丢集合
                persist(collection, vectorSize, snapshotOf(collection));
            }
            return;
        }
        // 新集合：空文件落盘即"存在"，声明维度入头部；幂等重入时走上面的存在分支
        persist(collection, vectorSize, Map.of());
        log.info("向量集合已创建：{}（{} 维，Cosine，内置文件 {}）", collection, vectorSize, file);
    }

    @Override
    public synchronized void upsert(String collection, List<VectorPoint> points) {
        fileOf(collection);
        if (points == null || points.isEmpty()) {
            return;
        }
        CollectionState state = load(collection);
        // 维度守卫：写入前校验每点向量长度与集合声明一致，坏点显式失败而非静默截断
        for (VectorPoint p : points) {
            if (p.getVector() == null || p.getVector().length != state.vectorSize) {
                throw new IllegalStateException("向量写入维度不符：集合 " + collection + " 声明 "
                        + state.vectorSize + " 维，收到 " + (p.getVector() == null ? 0 : p.getVector().length)
                        + " 维（点 id=" + p.getId() + "）。不同 embedding 模型的向量不可混用");
            }
        }
        Map<String, Stored> next = new LinkedHashMap<>(state.points);
        for (VectorPoint p : points) {
            // 写入时归一化：单位向量的点积 == 余弦，检索期免除法且分数语义与 Qdrant 对齐。
            // 若向量已单位长度则点积不变（幂等）；缩放不改变方向，只统一长度基准
            next.put(p.getId(), new Stored(normalize(p.getVector()), p.getPayload()));
        }
        persist(collection, state.vectorSize, next);
    }

    @Override
    public synchronized List<ScoredVectorPoint> search(String collection, float[] queryVector, int limit) {
        return search(collection, queryVector, limit, null, null);
    }

    /**
     * 带白名单的相似检索：在计算分数与截断 topK 之前先按 payload 字段过滤。
     * 事后删点会让不允许的候选占满名额，合法但分数稍低的点永远拿不到。
     */
    @Override
    public synchronized List<ScoredVectorPoint> search(String collection, float[] queryVector, int limit,
                                          String field, java.util.Collection<String> fieldValues) {
        fileOf(collection);
        if (fieldValues != null && fieldValues.isEmpty()) {
            return List.of();
        }
        if (fieldValues != null && (field == null || field.isBlank())) {
            throw new IllegalArgumentException("白名单检索必须指定 payload 字段");
        }
        CollectionState state = load(collection);
        if (state.points.isEmpty() || queryVector == null || queryVector.length == 0) {
            return List.of();
        }
        if (queryVector.length != state.vectorSize) {
            throw new IllegalStateException("检索向量维度不符：集合 " + collection + " 为 "
                    + state.vectorSize + " 维，查询为 " + queryVector.length + " 维");
        }
        // 查询向量同样归一化：与写入侧对称，保证点积 == 余弦
        float[] unit = normalize(queryVector);
        // 暴力精确检索：全部点逐一点积，取分数降序前 limit。规模百点级，毫秒内完成
        List<ScoredVectorPoint> scored = new ArrayList<>(state.points.size());
        for (Map.Entry<String, Stored> e : state.points.entrySet()) {
            Map<String, String> payload = e.getValue().payload();
            // 服务端过滤语义：不合格的点不参与打分与名额竞争。
            if (fieldValues != null && (payload == null || !fieldValues.contains(payload.get(field)))) {
                continue;
            }
            float dot = 0f;
            float[] v = e.getValue().vector();
            for (int i = 0; i < unit.length; i++) {
                dot += unit[i] * v[i];
            }
            scored.add(ScoredVectorPoint.builder()
                    .id(e.getKey())
                    .score(dot)
                    .payload(payload == null ? Map.of() : payload)
                    .build());
        }
        scored.sort(Comparator.comparingDouble(ScoredVectorPoint::getScore).reversed());
        return scored.size() <= limit ? scored : new ArrayList<>(scored.subList(0, limit));
    }

    @Override
    public synchronized void clear(String collection) {
        removeCollection(collection, "清空");
    }

    private void removeCollection(String collection, String action) {
        Path file = fileOf(collection);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new IllegalStateException("向量集合" + action + "失败: " + e.getMessage(), e);
        }
        collections.remove(collection);
        dimensions.remove(collection);
        log.info("向量集合已{}（删除文件，下次 ensureCollection 可重建）：{}", action, collection);
    }

    @Override
    public synchronized List<StoredPoint> listPoints(String collection) {
        // 对账契约：集合不存在 = 空表（与 QdrantVectorStore 语义一致，调用方据此走全量写入）
        Path file = fileOf(collection);
        if (!Files.exists(file)) {
            return List.of();
        }
        CollectionState state = load(collection);
        List<StoredPoint> points = new ArrayList<>(state.points.size());
        for (Map.Entry<String, Stored> e : state.points.entrySet()) {
            Map<String, String> payload = e.getValue().payload();
            points.add(StoredPoint.builder()
                    .id(e.getKey())
                    .kind(payload == null ? null : payload.get("kind"))
                    .text(payload == null ? null : payload.get("text"))
                    .build());
        }
        return points;
    }

    @Override
    public synchronized void delete(String collection, List<String> ids) {
        fileOf(collection);
        if (ids == null || ids.isEmpty()) {
            return;
        }
        CollectionState state = load(collection);
        Map<String, Stored> next = new LinkedHashMap<>(state.points);
        boolean removed = false;
        //即使前面已经删掉一个，后面的 id 仍然逐个尝试
        for (String id : ids) {
            removed |= next.remove(id) != null; // id 不存在静默忽略（幂等删除）
        }
        if (removed) {
            persist(collection, state.vectorSize, next);
        }
        log.info("向量按 id 删除完成：{} 个请求 ← {}（实际移除 {}）", ids.size(), collection, removed ? "有" : "无");
    }

    @Override
    public synchronized long count(String collection) {
        Path file = fileOf(collection);
        if (!Files.exists(file)) {
            return 0;
        }
        return load(collection).points.size();
    }

    // ==================== 落盘与恢复 ====================

    /** 集合当前状态：声明维度 + 全部点（从内存快照或磁盘加载） */
    private record CollectionState(int vectorSize, Map<String, Stored> points) {
    }

    private CollectionState load(String collection) {
        Path file = fileOf(collection);
        ConcurrentHashMap<String, Stored> map = collections.get(collection);
        if (map != null) {
            // 缓存命中前先确认磁盘文件仍在：用户可能手动删除故事目录（连同 nvbin）以释放空间。
            // 文件消失 → 丢弃缓存并回到"集合不存在"状态，避免已删数据被搜索命中或被下次写入复活。
            // 一次 stat 调用微秒级，且只在内存命中的路径上多这一次。
            if (!Files.exists(file, NOFOLLOW)) {
                collections.remove(collection);
                dimensions.remove(collection);
                log.info("向量集合 {} 的磁盘文件已不存在（故事目录被删除），缓存已失效", collection);
                return new CollectionState(0, Map.of());
            }
            // 缓存命中：维度从缓存伴随值取（空集合也有维度——ensureCollection 建集时即记录）
            int dim = dimensions.getOrDefault(collection, 0);
            return new CollectionState(dim, Map.copyOf(map));
        }
        CollectionState loaded = readFromDisk(collection);
        if (!Files.exists(file, NOFOLLOW)) {
            return loaded;
        }
        collections.put(collection, toConcurrent(loaded.points()));
        dimensions.put(collection, loaded.vectorSize());
        return new CollectionState(loaded.vectorSize(), Map.copyOf(loaded.points()));
    }

    private CollectionState readFromDisk(String collection) {
        Path file = fileOf(collection);
        if (!Files.exists(file)) {
            return new CollectionState(0, Map.of());
        }
        try (InputStream in = new BufferedInputStream(
                Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            DataInputStream dis = new DataInputStream(in);
            int magic = dis.readInt();
            if (magic != MAGIC) {
                throw new IllegalStateException("向量文件格式不符（非本实现产物）：" + file);
            }
            int version = dis.readInt();
            if (version != FORMAT_VERSION) {
                throw new IllegalStateException("向量文件版本不支持：" + version + "（当前 " + FORMAT_VERSION + "）：" + file);
            }
            int dim = dis.readInt();
            int count = dis.readInt();
            // NVTB v1 不变；先校验自描述长度，损坏头部不能触发超大数组分配或静默空集合。
            long bytes = Files.size(file);
            long minimumPointBytes = 4L + 4L * dim;
            if (dim <= 0 || count < 0 || (count > 0 && minimumPointBytes > (bytes - 16) / count)) {
                throw new IllegalStateException("向量文件头部非法（维度/点数越界）：" + file);
            }
            Map<String, Stored> points = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                String id = dis.readUTF();
                float[] vector = new float[dim];
                for (int d = 0; d < dim; d++) {
                    vector[d] = dis.readFloat();
                }
                String payloadJson = dis.readUTF();
                Map<String, String> payload = payloadJson.isEmpty()
                        ? null : JSON.<Map<String, String>>parseObject(payloadJson, mapType());
                if (points.put(id, new Stored(vector, payload)) != null) {
                    throw new IllegalStateException("向量文件包含重复点 id：" + file);
                }
            }
            if (dis.read() != -1) {
                throw new IllegalStateException("向量文件含多余尾部数据：" + file);
            }
            return new CollectionState(dim, points);
        } catch (IOException e) {
            // 与 StoryRepository 同则：记忆类文件损坏硬失败，绝不带着可疑索引继续跑
            throw new IllegalStateException("向量文件读取失败（" + file + "）：" + e.getMessage(), e);
        }
    }

    /**
     * 写入 = 内存快照更新 + 整文件原子替换。不做增量追加：
     * 百点级全量重写是毫秒级，换来"文件要么是完整新状态、要么是完整旧状态"的崩溃安全——
     * 与 StoryRepository 的临时文件模式同构。
     */
    private void persist(String collection, int vectorSize, Map<String, Stored> points) {
        Path file = fileOf(collection);
        Path tmp = null;
        try {
            Files.createDirectories(file.getParent());
            file = fileOf(collection); // 建目录后再次校验链接与真实根边界。
            tmp = Files.createTempFile(file.getParent(), file.getFileName() + ".tmp-", "");
            try (OutputStream out = new BufferedOutputStream(
                    Files.newOutputStream(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                            LinkOption.NOFOLLOW_LINKS))) {
                DataOutputStream dos = new DataOutputStream(out);
                dos.writeInt(MAGIC);
                dos.writeInt(FORMAT_VERSION);
                dos.writeInt(vectorSize);
                dos.writeInt(points.size());
                for (Map.Entry<String, Stored> e : points.entrySet()) {
                    dos.writeUTF(e.getKey());
                    float[] v = e.getValue().vector();
                    for (float f : v) {
                        dos.writeFloat(f);
                    }
                    Map<String, String> payload = e.getValue().payload();
                    dos.writeUTF(payload == null || payload.isEmpty()
                            ? "" : JSON.toJSONString(payload));
                }
            }
            file = fileOf(collection);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException fallback) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            collections.put(collection, toConcurrent(points));
            dimensions.put(collection, vectorSize);
        } catch (IOException e) {
            throw new IllegalStateException("向量写入失败（" + file + "）：" + e.getMessage(), e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException e) {
                    log.warn("向量临时文件清理失败（{}）：{}", tmp, e.getMessage());
                }
            }
        }
    }

    private ConcurrentHashMap<String, Stored> toConcurrent(Map<String, Stored> points) {
        ConcurrentHashMap<String, Stored> map = new ConcurrentHashMap<>(Math.max(16, points.size() * 2));
        map.putAll(points);
        return map;
    }

    private Map<String, Stored> snapshotOf(String collection) {
        ConcurrentHashMap<String, Stored> map = collections.get(collection);
        return map == null ? Map.of() : Map.copyOf(map);
    }

    // ==================== 命名与路由 ====================

    /**
     * 集合名 → 落盘文件。故事集合（前缀命中）路由进故事目录（workspaceDir 布局）的
     * memory/vectors/ 子目录，实现"删故事目录 = 向量随葬"；其余（世界/参考资料）进共享目录。
     */
    /** 原名落盘：保留已有安全中文名与 -_.，非法名字必须拒绝，绝不替换成另一个集合。 */
    private Path fileOf(String collection) {
        validateName(collection);
        String prefix = collectionPrefix + "-";
        if (collection.startsWith(prefix)) {
            String storyDir = collection.substring(prefix.length());
            validateName(storyDir);
            Path file = workspaceDir.resolve(storyDir)
                    .resolve(StorageKeys.MEMORY_DIR).resolve("vectors")
                    .resolve(collection + EXT);
            return checkedPath(workspaceDir, file);
        }
        return checkedPath(rootDir, rootDir.resolve(SHARED_DIR).resolve(collection + EXT));
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank() || name.contains("..") || name.endsWith(".")) {
            throw new IllegalArgumentException("向量集合名/故事目录名非法");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '-' && c != '_' && c != '.') {
                throw new IllegalArgumentException("向量集合名/故事目录名含非法字符");
            }
        }
        String stem = name.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        if (stem.matches("CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³]")) {
            throw new IllegalArgumentException("向量集合名/故事目录名为 Windows 保留名称");
        }
    }

    private static boolean isValidName(String name) {
        try {
            validateName(name);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isManagedCollection(String name) {
        return isValidName(name) && (matchesPrefix(name, "novel-references")
                || matchesPrefix(name, "novel-reference-blocks")
                || matchesPrefix(name, "novel-world")
                || (name.startsWith(collectionPrefix + "-")
                    && isValidName(name.substring(collectionPrefix.length() + 1))));
    }

    private static boolean matchesPrefix(String name, String prefix) {
        return name.equals(prefix) || (name.startsWith(prefix + "-") && name.length() > prefix.length() + 1);
    }

    private static String collectionNameOf(Path file) {
        String filename = file.getFileName().toString();
        return filename.endsWith(EXT) ? filename.substring(0, filename.length() - EXT.length()) : null;
    }

    /** 校验每个已存在的路径组件，包括配置根的祖先；不跟随符号链接/Windows junction。 */
    private static Path checkedPath(Path root, Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) {
            throw new IllegalArgumentException("向量文件路径越出配置根目录");
        }
        Path current = absolute.getRoot();
        try {
            for (Path part : absolute) {
                current = current.resolve(part);
                if (!Files.exists(current, NOFOLLOW)) {
                    break;
                }
                BasicFileAttributes attrs = Files.readAttributes(current, BasicFileAttributes.class, NOFOLLOW);
                Path real = current.toRealPath();
                if (attrs.isSymbolicLink() || attrs.isOther() || !real.equals(current.toRealPath(NOFOLLOW))) {
                    throw new IllegalArgumentException("向量文件路径包含符号链接或重解析路径");
                }
                // Windows 不区分大小写；另一个名字不能成为已有集合/故事目录的别名。
                if (current.startsWith(root) && !current.equals(root)
                        && !current.getFileName().toString().equals(real.getFileName().toString())) {
                    throw new IllegalArgumentException("向量文件路径与既有名称发生别名碰撞");
                }
            }
            if (Files.exists(root, NOFOLLOW) && Files.exists(absolute, NOFOLLOW)
                    && !absolute.toRealPath().startsWith(root.toRealPath())) {
                throw new IllegalArgumentException("向量文件真实路径越出配置根目录");
            }
        } catch (IOException e) {
            throw new IllegalStateException("向量文件路径校验失败: " + e.getMessage(), e);
        }
        return absolute;
    }

    private static String encodeCursor(String collection, String id) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeUTF(collection);
                out.writeUTF(id);
            }
            return CURSOR_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("向量分页游标编码失败", e);
        }
    }

    private static String decodeCursor(String collection, String cursor) {
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
                String encodedCollection = in.readUTF();
                String id = in.readUTF();
                if (!collection.equals(encodedCollection) || in.read() != -1) {
                    throw new IllegalArgumentException("向量分页游标不属于本集合");
                }
                return id;
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("向量分页游标非法", e);
        }
    }

    // ==================== 数学 ====================

    /**
     * 归一化为单位向量。零向量（模为 0，理论上 embedding 不会产生）按原样返回——
     * 点积恒为 0，检索自然不会命中高分，语义安全。
     */
    private static float[] normalize(float[] v) {
        double sum = 0;
        for (float f : v) {
            sum += (double) f * f;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0 || norm == 1.0) {
            return v;
        }
        float[] unit = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            unit[i] = (float) (v[i] / norm);
        }
        return unit;
    }

    @SuppressWarnings("unchecked")
    private static Class<Map<String, String>> mapType() {
        return (Class<Map<String, String>>) (Class<?>) Map.class;
    }

}
