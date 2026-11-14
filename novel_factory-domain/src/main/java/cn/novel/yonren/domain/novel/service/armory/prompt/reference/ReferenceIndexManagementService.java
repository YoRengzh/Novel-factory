package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import cn.novel.yonren.api.dto.ReferenceIndexDTO;
import cn.novel.yonren.domain.novel.adapter.llm.EmbeddingGateway;
import cn.novel.yonren.domain.novel.adapter.repository.VectorStore;
import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.service.armory.memory.StoryMemoryService;
import cn.novel.yonren.domain.novel.config.StoryMemoryProperties;
import cn.novel.yonren.types.utils.StoryBibleParser;
import cn.novel.yonren.domain.novel.config.ReferenceSelectorProperties;
import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.model.valobj.VectorCollectionInfo;
import cn.novel.yonren.domain.novel.model.valobj.VectorPointPage;
import cn.novel.yonren.domain.novel.model.valobj.VectorPointView;
import cn.novel.yonren.domain.novel.model.valobj.prompt.ReferenceIndexEntry;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import cn.novel.yonren.domain.novel.service.armory.LlmRuntimeConfig;
import cn.novel.yonren.types.enums.PromptScene;
import cn.novel.yonren.domain.novel.service.job.JobRegistry;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/** 受管小说、参考资料和共享世界的集合级维护；不允许点级编辑或任意磁盘路径。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReferenceIndexManagementService {

    private static final long PREVIEW_TTL_MS = Duration.ofMinutes(5).toMillis();
    private static final int MAX_PREVIEWS = 32;
    private static final int MAX_TASKS = 20;
    private static final Set<String> ACTIONS = Set.of("disable", "enable", "clear", "delete", "rebuild");

    private final VectorStore vectorStore;
    private final ReferenceIndexLoader indexLoader;
    private final PromptRuleFileLoader fileLoader;
    private final ReferenceBlockIndexer blockIndexer;
    private final ReferenceVectorIndexer legacyIndexer;
    private final VectorReferenceRetriever legacyRetriever;
    private final DynamicReferenceService dynamicReferences;
    private final EmbeddingGateway embeddingGateway;
    private final StoryProperties storyProperties;
    private final ReferenceSelectorProperties selectorProperties;
    private final JobRegistry jobRegistry;

    @Resource(name = "referenceIndexExecutor")
    private ThreadPoolExecutor executor;

    @Value("${story.memory.collection-prefix:novel-memory}")
    private String memoryCollectionPrefix = "novel-memory";

    @Resource
    private LlmRuntimeConfig llmRuntimeConfig;

    @Resource
    private IStoryRepository storyRepository;

    @Resource
    private StoryMemoryService storyMemoryService;

    @Resource
    private StoryMemoryProperties storyMemoryProperties;

    private final Map<String, Command> previews = new ConcurrentHashMap<>();
    private final Map<String, ReferenceIndexDTO.TaskView> tasks = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private volatile ReferenceIndexDTO.TaskView latestTask;

    public static class ConflictException extends IllegalStateException {
        public ConflictException(String message) {
            super(message);
        }
    }

    private record BookSource(ReferenceIndexDTO.Owner owner, Path path, String worldId, String basis, String error) {
    }

    private record Inventory(String version, List<ReferenceIndexDTO.CollectionView> collections,
                             List<ReferenceIndexDTO.SourceView> sources, boolean blockMode,
                             EmbeddingGateway.EmbeddingSession session, ReferenceBlockSnapshot snapshot,
                             Map<String, BookSource> books) {
    }

    private record Command(String action, String sourceId, String collectionId, String version, long expiresAt,
                           List<String> collectionIds, String targetVersion) {
        private Command(String action, String sourceId, String collectionId, String version, long expiresAt) {
            this(action, sourceId, collectionId, version, expiresAt, List.of(), null);
        }
    }

    private record RebuildTarget(ReferenceIndexDTO.CollectionView collection, Runnable rebuild,
                                 String sourceVersion, long chars, int batches) {
    }

    public ReferenceIndexDTO view() {
        Inventory inventory = inventory();
        return new ReferenceIndexDTO(vectorStore.backendName(), inventory.version(), jobRegistry.isReferenceBusy(),
                jobRegistry.referenceBusyReason(), inventory.collections(), inventory.sources(), latestTask);
    }

    public ReferenceIndexDTO.PointPage points(String collectionId, String cursor, int limit) {
        ReferenceIndexDTO.CollectionView collection = requireCollection(inventory(), collectionId);
        if (limit < 1 || limit > 100 || cursor != null && cursor.length() > 512) {
            throw new IllegalArgumentException("分页参数不合法");
        }
        VectorPointPage page = vectorStore.pagePoints(collection.name(), cursor, limit);
        return new ReferenceIndexDTO.PointPage(page.points().stream()
                .map(point -> new ReferenceIndexDTO.PointView(point.id(), Map.copyOf(point.payload())))
                .toList(), page.nextCursor());
    }

    /** 预览只读本地语料和索引，不调用 embedding，也不改变任何资料。 */
    public ReferenceIndexDTO.Preview preview(String action, String sourceId, String collectionId) {
        if (!ACTIONS.contains(action == null ? "" : action)) {
            throw new IllegalArgumentException("不支持的资料索引操作");
        }
        requireIdle();
        Inventory inventory = inventory();
        List<ReferenceIndexDTO.CollectionView> affectedCollections = new ArrayList<>();
        List<ReferenceIndexDTO.SourceView> affectedSources = new ArrayList<>();
        long embeddingChars = 0;
        int embeddingBatches = 0;
        String title;
        String message;
        if (action.equals("disable") || action.equals("enable")) {
            if (collectionId != null) throw new IllegalArgumentException("资料启停不能指定集合");
            ReferenceIndexDTO.SourceView source = inventory.sources().stream()
                    .filter(item -> item.id().equals(sourceId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("资料来源不存在，请刷新列表"));
            affectedSources.add(source);
            affectedCollections.addAll(referenceCollections(inventory));
            title = (action.equals("disable") ? "停用" : "恢复") + "资料「" + source.title() + "」";
            message = "原文保留，不删除导入源文件。为防止旧快照、依赖块或缓存继续注入，全部共享参考索引将失效并清理，"
                    + "不影响小说记忆。后续仅对启用资料重建；停用状态跨重启保留，修改同名原文也不会自动恢复。";
        } else if (action.equals("clear") || action.equals("delete")) {
            if (sourceId != null) throw new IllegalArgumentException("集合清理不能指定资料来源");
            ReferenceIndexDTO.CollectionView collection = requireCollection(inventory, collectionId);
            requireWritable(collection, action);
            affectedCollections.add(collection);
            title = (action.equals("clear") ? "清空索引「" : "删除历史集合「") + collection.name() + "」";
            message = action.equals("clear")
                    ? "仅移除该参考资料索引，原文和启停状态保留。下次检索可能重新向量化；要永久排除污染资料，请先停用来源。"
                    : "仅删除所列历史参考集合，不删除原文或小说记忆。此操作不能撤销，仍启用的资料今后可重新向量化。";
        } else {
            if (sourceId != null || collectionId != null) throw new IllegalArgumentException("重建使用当前参考语料，不能指定任意集合");
            if (inventory.session() == null && indexLoader.getIndex().size() > 0) {
                throw new IllegalStateException("当前向量接入无法创建冻结会话，请先检查向量模型配置");
            }
            affectedCollections.addAll(rebuildTargets(inventory));
            affectedSources.addAll(inventory.sources().stream().filter(ReferenceIndexDTO.SourceView::enabled).toList());
            if (inventory.session() != null) {
                if (inventory.blockMode()) {
                    ReferenceBlockIndexer.IndexPreview cost = blockIndexer.preview(inventory.session());
                    embeddingChars = cost.embeddingChars();
                    embeddingBatches = cost.embeddingBatches();
                } else {
                    ReferenceChunker chunker = new ReferenceChunker();
                    int chunks = 0;
                    for (ReferenceIndexEntry entry : indexLoader.getIndex()) {
                        String content = fileLoader.load(entry.getRelativePath());
                        if (content == null || content.isBlank()) continue;
                        var blocks = chunker.chunk(entry.getFileName(), content);
                        chunks += blocks.size();
                        embeddingChars += blocks.stream().mapToLong(block -> block.embedText().length()).sum();
                    }
                    embeddingBatches = (chunks + 15) / 16;
                }
            }
            title = "重建参考资料索引";
            message = "将按当前启用资料和冻结的向量接入重新构建并核对索引，可能产生向量模型费用。"
                    + "所列现用索引将重新创建；历史集合保留供另行清理。构建失败会明确报告，不静默沿用污染索引。"
                    + "不会生成小说正文，也不会修改小说记忆。"
                    + ("vector".equalsIgnoreCase(effectiveReferenceMode()) ? ""
                        : " 当前资料检索为 LLM 模式：本次仅维护备用向量索引，不会自动切换检索方式。");
        }
        long now = System.currentTimeMillis();
        previews.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
        if (previews.size() >= MAX_PREVIEWS) throw new ConflictException("未确认预览过多，请稍后重试");
        String token = UUID.randomUUID().toString();
        previews.put(token, new Command(action, sourceId, collectionId, inventory.version(), now + PREVIEW_TTL_MS));
        return new ReferenceIndexDTO.Preview(token, action, title, message, inventory.version(),
                List.copyOf(affectedCollections), List.copyOf(affectedSources), embeddingChars, embeddingBatches);
    }

    /** 集合级批量预览，所有目标只接受本机目录登记的逻辑 ID。 */
    public ReferenceIndexDTO.Preview previewCollections(String action, List<String> collectionIds) {
        if (!Set.of("delete", "rebuild").contains(action == null ? "" : action)) {
            throw new IllegalArgumentException("集合批量操作只支持删除或重建");
        }
        requireIdle();
        Inventory inventory = inventory();
        List<ReferenceIndexDTO.CollectionView> selected = selectCollections(inventory, collectionIds, action);
        List<RebuildTarget> targets = action.equals("rebuild") ? prepareTargets(inventory, selected) : List.of();
        long chars = targets.stream().mapToLong(RebuildTarget::chars).sum();
        int batches = targets.stream().mapToInt(RebuildTarget::batches).sum();
        String title = action.equals("delete") ? "删除所选向量集合" : "重建所选向量集合";
        String targetNames = targets.stream().map(target -> target.collection().name()).distinct()
                .collect(java.util.stream.Collectors.joining("、"));
        String message = "只操作预览所列向量集合，不删除小说正文、摘要、账本、设定或参考资料原文。"
                + "共享世界集合的全部关联小说会受到该集合操作影响，未列出的集合保持不变。"
                + (action.equals("delete")
                    ? " 删除索引不可撤销；保留的源数据以后可重新索引，本操作不关闭小说记忆或停用参考资料。"
                    : " 使用冻结的向量配置重新构建，可能产生费用。历史 reference 按当前启用资料重建现用索引，不还原旧原文，重复目标仅构建一次。")
                + (action.equals("rebuild") ? " 实际重建目标（去重后）：" + targetNames + "。" : "")
                + " 批量操作不是跨集合事务；发生失败即停止余下操作并逐项报告。";
        long now = System.currentTimeMillis();
        previews.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
        if (previews.size() >= MAX_PREVIEWS) throw new ConflictException("未确认预览过多，请稍后重试");
        String token = UUID.randomUUID().toString();
        previews.put(token, new Command(action, null, null, inventory.version(), now + PREVIEW_TTL_MS,
                selected.stream().map(ReferenceIndexDTO.CollectionView::id).toList(),
                action.equals("rebuild") ? targetVersion(targets) : null));
        List<ReferenceIndexDTO.CollectionView> affected = new ArrayList<>(selected);
        if (action.equals("rebuild") && selected.stream().anyMatch(item -> item.kind().equals("reference"))) {
            for (ReferenceIndexDTO.CollectionView current : rebuildTargets(inventory)) {
                if (affected.stream().noneMatch(item -> item.id().equals(current.id()))) affected.add(current);
            }
        }
        return new ReferenceIndexDTO.Preview(token, action, title, message, inventory.version(), List.copyOf(affected), List.of(), chars, batches);
    }

    private List<ReferenceIndexDTO.CollectionView> selectCollections(Inventory inventory, List<String> ids, String action) {
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(Objects::isNull)
                || ids.stream().distinct().count() != ids.size()) {
            throw new IllegalArgumentException("请选择 1 至 100 个不同的受管集合");
        }
        List<ReferenceIndexDTO.CollectionView> selected = new ArrayList<>();
        for (String id : ids) {
            ReferenceIndexDTO.CollectionView collection = requireCollection(inventory, id);
            if (action.equals("delete") ? !collection.canDelete() : !collection.canRebuild()) {
                throw new IllegalArgumentException("该集合不支持所选操作：" + collection.name());
            }
            selected.add(collection);
        }
        return List.copyOf(selected);
    }

    private List<RebuildTarget> prepareTargets(Inventory inventory, List<ReferenceIndexDTO.CollectionView> selected) {
        List<RebuildTarget> targets = new ArrayList<>();
        boolean referencePrepared = false;
        for (ReferenceIndexDTO.CollectionView collection : selected) {
            if (collection.kind().equals("reference")) {
                if (referencePrepared) continue;
                referencePrepared = true;
                if (inventory.session() == null && !indexLoader.getIndex().isEmpty()) {
                    throw new IllegalStateException("参考向量接入未配置，无法重建");
                }
                long chars = 0;
                int batches = 0;
                if (inventory.blockMode() && inventory.session() != null) {
                    ReferenceBlockIndexer.IndexPreview cost = blockIndexer.preview(inventory.session());
                    chars = cost.embeddingChars();
                    batches = cost.embeddingBatches();
                } else {
                    ReferenceChunker chunker = new ReferenceChunker();
                    int chunks = 0;
                    for (ReferenceIndexEntry entry : indexLoader.getIndex()) {
                        String content = fileLoader.load(entry.getRelativePath());
                        if (content == null || content.isBlank()) continue;
                        var parts = chunker.chunk(entry.getFileName(), content);
                        chunks += parts.size();
                        chars += parts.stream().mapToLong(part -> part.embedText().length()).sum();
                    }
                    batches = (chunks + 15) / 16;
                }
                String currentName = inventory.blockMode() && inventory.snapshot() != null
                        ? inventory.snapshot().dimensions() == null ? "当前参考集合（维度由首批向量确定）"
                            : ReferenceBlockIndexer.collectionName(inventory.snapshot(), inventory.snapshot().dimensions())
                        : ReferenceVectorIndexer.COLLECTION;
                ReferenceIndexDTO.CollectionView target = new ReferenceIndexDTO.CollectionView(collection.id(), currentName,
                        collection.kind(), "current", collection.dimensions(), collection.pointCount(), null,
                        collection.canClear(), collection.canDelete(), collection.canRebuild(), collection.owners());
                targets.add(new RebuildTarget(target, () -> rebuildReference(inventory), indexLoader.corpusVersion(), chars, batches));
            } else {
                if (storyMemoryService == null) throw new IllegalStateException("小说集合重建服务不可用");
                StoryMemoryService.CollectionPlan plan;
                if (collection.kind().equals("story")) {
                    BookSource book = requireBook(inventory, collection.owners().get(0).storyDirName());
                    try {
                        storyRepository.assertStoryMaintenanceWriteBoundary(book.path());
                        plan = storyMemoryService.prepareStoryCollection(storyProperties.getModule(), book.path(),
                                storyRepository.readChapterSummaries(book.path()), storyRepository.readVolumes(book.path()), inventory.session());
                    } catch (Exception e) {
                        throw new IllegalStateException("小说集合来源无法完整读取：" + book.owner().novelTitle(), e);
                    }
                } else {
                    List<Path> paths = collection.owners().stream().map(owner -> requireBook(inventory, owner.storyDirName()).path()).toList();
                    for (Path path : paths) {
                        try { storyRepository.assertStoryMaintenanceWriteBoundary(path); }
                        catch (Exception e) { throw new IllegalStateException("共享世界的来源小说存在未完成编辑，不能重建", e); }
                    }
                    plan = storyMemoryService.prepareWorldCollection(storyProperties.getModule(), worldIdOf(collection.name()),
                            paths, inventory.session());
                }
                if (!plan.preview().collection().equals(collection.name())) throw new IllegalStateException("重建目标集合与所选项不一致");
                targets.add(new RebuildTarget(collection, () -> storyMemoryService.rebuildCollection(plan), plan.preview().sourceVersion(),
                        plan.preview().embeddingChars(), plan.preview().embeddingBatches()));
            }
        }
        return List.copyOf(targets);
    }

    private List<ReferenceIndexDTO.CollectionResult> deleteCollections(List<ReferenceIndexDTO.CollectionView> selected) {
        List<ReferenceIndexDTO.CollectionResult> results = new ArrayList<>();
        boolean failed = false;
        for (ReferenceIndexDTO.CollectionView collection : selected) {
            if (failed) {
                results.add(new ReferenceIndexDTO.CollectionResult(collection.id(), collection.name(), "skipped", "前序集合失败，未执行"));
                continue;
            }
            try {
                BookSource ownerBook = collection.kind().equals("story") && !collection.owners().isEmpty()
                        ? readBookSources().get(collection.owners().get(0).storyDirName()) : null;
                dropManagedCollection(collection);
                if (ownerBook != null && ownerBook.path() != null && ownerBook.error() == null && storyMemoryService != null) {
                    storyMemoryService.markCollectionDeleted(storyProperties.getModule(), ownerBook.path());
                }
                generation.incrementAndGet();
                results.add(new ReferenceIndexDTO.CollectionResult(collection.id(), collection.name(), "completed", "集合已删除，原文保留"));
            } catch (Exception e) {
                failed = true;
                results.add(new ReferenceIndexDTO.CollectionResult(collection.id(), collection.name(), "failed", safeMessage(e)));
            }
        }
        return List.copyOf(results);
    }

    private void dropManagedCollection(ReferenceIndexDTO.CollectionView collection) {
        if (kindOf(collection.name()) == null) throw new IllegalArgumentException("集合不在受管目录中");
        vectorStore.dropCollection(collection.name());
        if (vectorStore.count(collection.name()) != 0) throw new IllegalStateException("集合删除后仍有数据");
        if (collection.kind().equals("reference")) invalidate();
    }

    private static String targetVersion(List<RebuildTarget> targets) {
        return digest(targets.stream().map(target -> target.collection().id() + "|" + target.sourceVersion())
                .collect(java.util.stream.Collectors.joining("\n")));
    }

    private ReferenceIndexDTO.OperationResult scheduleCollectionRebuild(Inventory inventory,
                                                                        List<ReferenceIndexDTO.CollectionView> selected,
                                                                        String expectedVersion) {
        if (executor == null) throw new IllegalStateException("集合维护执行器不可用");
        List<RebuildTarget> targets = prepareTargets(inventory, selected);
        if (!Objects.equals(expectedVersion, targetVersion(targets))) {
            throw new ConflictException("重建来源在预览后已变化，请重新预览确认");
        }
        String id = newTaskId("collection-");
        List<ReferenceIndexDTO.CollectionResult> initial = targets.stream().map(target ->
                new ReferenceIndexDTO.CollectionResult(target.collection().id(), target.collection().name(), "skipped", "等待执行")).toList();
        updateTask(id, "queued", "已确认重建所选集合", initial);
        try {
            executor.execute(() -> {
                List<ReferenceIndexDTO.CollectionResult> results = new ArrayList<>();
                boolean failed = false;
                try {
                    for (RebuildTarget target : targets) {
                        if (failed) {
                            results.add(new ReferenceIndexDTO.CollectionResult(target.collection().id(), target.collection().name(), "skipped", "前序集合失败，未执行"));
                            continue;
                        }
                        updateTask(id, "running", "正在重建 " + target.collection().name(), results);
                        try {
                            target.rebuild().run();
                            generation.incrementAndGet();
                            results.add(new ReferenceIndexDTO.CollectionResult(target.collection().id(), target.collection().name(), "completed", "重建并对账完成"));
                        } catch (Exception e) {
                            failed = true;
                            results.add(new ReferenceIndexDTO.CollectionResult(target.collection().id(), target.collection().name(), "failed", safeMessage(e)));
                        }
                    }
                    updateTask(id, failed ? "failed" : "completed", failed ? "部分集合重建失败，请核对逐项结果" : "所选集合重建完成，未修改原文", results);
                } finally {
                    jobRegistry.endReferenceOperation();
                }
            });
        } catch (RuntimeException e) {
            updateTask(id, "failed", "集合重建任务无法提交", initial);
            throw e;
        }
        return new ReferenceIndexDTO.OperationResult(false, id, "重建已提交，等待逐集合结果", initial);
    }

    private void rebuildReference(Inventory inventory) {
        drop(rebuildTargets(inventory));
        invalidate();
        if (indexLoader.getIndex().isEmpty()) return;
        if (inventory.blockMode()) blockIndexer.ensureIndex(inventory.session());
        else legacyIndexer.index(storyProperties.getModule(), inventory.session());
    }

    private static BookSource requireBook(Inventory inventory, String name) {
        BookSource book = inventory.books().get(name);
        if (book == null || book.path() == null || book.error() != null) throw new IllegalStateException("集合所属小说来源不存在或不可读取");
        return book;
    }

    /** 一次性预览令牌与全局维护门闩同时约束执行，状态变化后必须重新确认。 */
    public ReferenceIndexDTO.OperationResult execute(String token) {
        if (token == null || token.length() > 64) throw new IllegalArgumentException("缺少有效的操作预览");
        if (!jobRegistry.tryBeginReferenceOperation()) throw busy();
        boolean handedToWorker = false;
        try {
            Command command = previews.remove(token);
            if (command == null || command.expiresAt() < System.currentTimeMillis()) {
                throw new ConflictException("操作预览已过期或已执行，请重新预览确认");
            }
            Inventory inventory = inventory();
            if (!inventory.version().equals(command.version())) {
                throw new ConflictException("资料、向量配置或集合已变化，请重新预览确认");
            }
            if (!command.collectionIds().isEmpty()) {
                List<ReferenceIndexDTO.CollectionView> selected = selectCollections(inventory, command.collectionIds(), command.action());
                if (command.action().equals("rebuild")) {
                    ReferenceIndexDTO.OperationResult result = scheduleCollectionRebuild(inventory, selected, command.targetVersion());
                    handedToWorker = true;
                    return result;
                }
                List<ReferenceIndexDTO.CollectionResult> results = deleteCollections(selected);
                boolean completed = results.stream().allMatch(result -> result.status().equals("completed"));
                return new ReferenceIndexDTO.OperationResult(completed, null,
                        completed ? "所选向量集合已删除，原文保持不变" : "集合删除未全部完成，请查看逐项结果", results);
            }
            if (command.action().equals("rebuild")) {
                ReferenceIndexDTO.OperationResult result = scheduleRebuild(inventory);
                handedToWorker = true;
                return result;
            }
            if (command.action().equals("disable") || command.action().equals("enable")) {
                indexLoader.sourcePolicy().setEnabled(command.sourceId(), command.action().equals("enable"));
                invalidate();
                drop(referenceCollections(inventory));
            } else {
                ReferenceIndexDTO.CollectionView collection = requireCollection(inventory, command.collectionId());
                requireWritable(collection, command.action());
                drop(List.of(collection));
                invalidate();
            }
            generation.incrementAndGet();
            return new ReferenceIndexDTO.OperationResult(true, null, "资料索引操作已完成，原文与小说记忆保持不变");
        } finally {
            if (!handedToWorker) jobRegistry.endReferenceOperation();
        }
    }

    public ReferenceIndexDTO.TaskView task(String id) {
        ReferenceIndexDTO.TaskView task = id == null ? null : tasks.get(id);
        if (task == null) throw new IllegalArgumentException("资料维护任务不存在或已过期");
        return task;
    }

    /** 原资料增删改与管理操作共用门闩和失效逻辑，避免只有新 UI 安全、旧资产入口仍可污染。 */
    public void mutateReference(Runnable mutation) {
        if (!jobRegistry.tryBeginReferenceOperation()) throw busy();
        try {
            mutation.run();
            invalidate();
            drop(referenceCollections(inventory()));
            generation.incrementAndGet();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException("资料变更或索引清理未全部完成，原文可能已保存；请重新读取资料并检查索引后重试", e);
        } finally {
            invalidate();
            jobRegistry.endReferenceOperation();
        }
    }

    private ReferenceIndexDTO.OperationResult scheduleRebuild(Inventory inventory) {
        if (executor == null) throw new IllegalStateException("资料索引维护执行器不可用");
        String id = newTaskId("reference-");
        updateTask(id, "queued", "已确认重建，等待资料索引维护线程执行");
        try {
            executor.execute(() -> {
                updateTask(id, "running", "正在清理目标索引并批量向量化；此期间不可开始生成或修改资料");
                try {
                    drop(rebuildTargets(inventory));
                    invalidate();
                    if (!indexLoader.getIndex().isEmpty()) {
                        if (inventory.blockMode()) {
                            blockIndexer.ensureIndex(inventory.session());
                        } else {
                            legacyIndexer.index(storyProperties.getModule(), inventory.session());
                        }
                    }
                    generation.incrementAndGet();
                    updateTask(id, "completed", "参考资料索引已重建并核对完成，未修改原文或小说记忆");
                } catch (Exception e) {
                    invalidate();
                    updateTask(id, "failed", "索引重建失败：" + safeMessage(e));
                    log.error("资料索引维护任务 {} 失败", id, e);
                } finally {
                    jobRegistry.endReferenceOperation();
                }
            });
        } catch (RuntimeException e) {
            updateTask(id, "failed", "资料索引维护任务无法提交，请稍后重试");
            throw e;
        }
        return new ReferenceIndexDTO.OperationResult(false, id, "重建任务已提交，完成情况请查看任务状态");
    }

    private String newTaskId(String prefix) {
        tasks.entrySet().removeIf(entry -> tasks.size() >= MAX_TASKS
                && Set.of("completed", "failed").contains(entry.getValue().status()));
        return prefix + UUID.randomUUID();
    }

    private void updateTask(String id, String status, String message) {
        updateTask(id, status, message, List.of());
    }

    private void updateTask(String id, String status, String message, List<ReferenceIndexDTO.CollectionResult> results) {
        ReferenceIndexDTO.TaskView view = new ReferenceIndexDTO.TaskView(id, status, message, results);
        tasks.put(id, view);
        latestTask = view;
    }

    private void invalidate() {
        fileLoader.evictAll();
        indexLoader.refresh();
        blockIndexer.invalidate();
        legacyIndexer.invalidate();
        legacyRetriever.invalidate();
        dynamicReferences.invalidate();
    }

    private void drop(List<ReferenceIndexDTO.CollectionView> collections) {
        for (ReferenceIndexDTO.CollectionView collection : collections) {
            if (!collection.kind().equals("reference") || !isReference(collection.name())) {
                throw new IllegalArgumentException("只能清理受管参考资料集合");
            }
            vectorStore.dropCollection(collection.name());
            if (vectorStore.count(collection.name()) != 0) {
                throw new IllegalStateException("集合清理后仍有数据，请刷新检查");
            }
        }
    }

    private Inventory inventory() {
        String referenceMode = effectiveReferenceMode();
        boolean blockMode = "vector".equalsIgnoreCase(referenceMode)
                && java.util.Arrays.stream(PromptScene.values()).map(selectorProperties::blocksFor)
                        .anyMatch(blocks -> blocks != null && blocks.isEnabled());
        EmbeddingGateway.EmbeddingSession session = null;
        ReferenceBlockSnapshot snapshot = null;
        try {
            session = embeddingGateway.session(storyProperties.getModule());
            if (blockMode) snapshot = blockIndexer.loadSnapshot(session);
        } catch (RuntimeException e) {
            log.debug("资料管理当前索引版本无法预览：{}", e.getClass().getSimpleName());
            session = null;
        }
        List<ReferenceIndexDTO.CollectionView> collections = new ArrayList<>();
        Map<String, Integer> blockCounts = new HashMap<>();
        Map<String, List<String>> sourceCollections = new HashMap<>();
        StringBuilder fingerprint = new StringBuilder(vectorStore.backendName())
                .append('|').append(indexLoader.corpusVersion()).append('|').append(generation.get())
                .append('|').append(referenceMode).append('|').append(blockMode)
                .append('|').append(session == null ? "unconfigured" : session.signature())
                .append('|').append(session == null ? "" : session.dimensions());
        Map<String, BookSource> books = readBookSources();
        for (BookSource book : books.values()) {
            fingerprint.append('|').append(book.owner().storyDirName()).append('|').append(book.worldId())
                    .append('|').append(book.basis()).append('|').append(book.error());
        }
        List<VectorCollectionInfo> catalog = new ArrayList<>(vectorStore.listCollections());
        catalog.sort(Comparator.comparing(VectorCollectionInfo::name));
        Map<String, List<VectorPointView>> collectionPoints = new HashMap<>();
        for (VectorCollectionInfo info : catalog) {
            String kind = kindOf(info.name());
            if (kind == null) continue;
            String id = collectionId(info.name());
            String state = stateOf(info, blockMode, snapshot);
            String error = info.error() == null ? null : safeMessage(new IllegalStateException(info.error()));
            fingerprint.append('|').append(info.name()).append(':').append(info.dimensions())
                    .append(':').append(info.pointCount()).append(':').append(info.status());
            if (!state.equals("error")) {
                try {
                    String cursor = null;
                    Set<String> seen = new java.util.HashSet<>();
                    do {
                        VectorPointPage page = vectorStore.pagePoints(info.name(), cursor, 100);
                        collectionPoints.computeIfAbsent(info.name(), ignored -> new ArrayList<>()).addAll(page.points());
                        for (VectorPointView point : page.points()) {
                            fingerprint.append('|').append(point.id()).append(':').append(new TreeMap<>(point.payload()));
                            String source = point.payload().get("fileName");
                            if (source != null) {
                                List<String> ids = sourceCollections.computeIfAbsent(source, ignored -> new ArrayList<>());
                                if (!ids.contains(id)) ids.add(id);
                                if (state.equals("current") || state.equals("legacy")) blockCounts.merge(source, 1, Integer::sum);
                            }
                        }
                        cursor = page.nextCursor();
                        if (cursor != null && !seen.add(cursor)) throw new IllegalStateException("分页游标未前进");
                    } while (cursor != null);
                    if (state.equals("current") && snapshot != null) {
                        Map<String, String> expected = new HashMap<>();
                        for (ReferenceAtom atom : snapshot.atoms()) {
                            expected.put(UUID.nameUUIDFromBytes(atom.atomId().getBytes(StandardCharsets.UTF_8)).toString(), atom.content());
                        }
                        List<VectorPointView> stored = collectionPoints.getOrDefault(info.name(), List.of());
                        if (stored.size() != expected.size() || stored.stream().map(VectorPointView::id).distinct().count() != stored.size()
                                || stored.stream().anyMatch(point -> !expected.containsKey(point.id())
                                    || !Objects.equals(expected.get(point.id()), point.payload().get("text")))) {
                            state = "error";
                            error = "当前资料集合与语料版本不一致，请清空后重建";
                        }
                    }
                } catch (RuntimeException e) {
                    state = "error";
                    error = "集合分块无法读取：" + safeMessage(e);
                    fingerprint.append(":error");
                }
            }
            boolean reference = kind.equals("reference");
            List<ReferenceIndexDTO.Owner> owners;
            if (kind.equals("story")) {
                BookSource book = books.get(info.name().substring(memoryPrefix().length() + 1));
                owners = book == null ? List.of() : List.of(book.owner());
                if (book == null) state = "orphan";
                else if (book.error() != null) { state = "error"; error = book.error(); }
            } else if (kind.equals("world")) {
                String worldId = worldIdOf(info.name());
                owners = books.values().stream().filter(book -> Objects.equals(book.worldId(), worldId))
                        .map(BookSource::owner).toList();
                if (owners.isEmpty()) state = "orphan";
                else if (books.values().stream().anyMatch(book -> book.error() != null)) {
                    error = "部分小说来源不可读取，不能完整确认共享世界范围；暂不能重建";
                }
            } else owners = List.of();
            boolean canRebuild = reference ? session != null || indexLoader.getIndex().isEmpty()
                    : !owners.isEmpty() && session != null && owners.stream()
                        .allMatch(owner -> books.get(owner.storyDirName()).error() == null)
                        && !(kind.equals("world") && books.values().stream().anyMatch(book -> book.error() != null));
            boolean canDelete = !kind.equals("world") || books.values().stream().noneMatch(book -> book.error() != null);
            collections.add(new ReferenceIndexDTO.CollectionView(id, info.name(), kind, state, info.dimensions(),
                    info.pointCount(), error, canDelete, canDelete, canRebuild, owners));
        }
        List<ReferenceIndexDTO.SourceView> sources = indexLoader.getAllIndex().stream().map(entry -> {
            String id = entry.getFileName();
            boolean builtin = !Files.isRegularFile(fileLoader.userAssetRoot().resolve("references").resolve(id + ".md"),
                    LinkOption.NOFOLLOW_LINKS);
            return new ReferenceIndexDTO.SourceView(id, entry.getTitle(), entry.getGroup(), builtin,
                    indexLoader.sourcePolicy().isEnabled(id), blockCounts.getOrDefault(id, 0),
                    List.copyOf(sourceCollections.getOrDefault(id, List.of())));
        }).toList();
        String version = digest(fingerprint.toString());
        return new Inventory(version, List.copyOf(collections), sources, blockMode, session, snapshot, books);
    }

    private Map<String, BookSource> readBookSources() {
        if (storyRepository == null) return Map.of();
        List<IStoryRepository.StorySummary> summaries;
        try { summaries = storyRepository.listStories(); }
        catch (Exception e) { throw new IllegalStateException("小说归属列表无法读取，暂停集合管理", e); }
        Map<String, BookSource> sources = new java.util.LinkedHashMap<>();
        for (IStoryRepository.StorySummary summary : summaries) {
            ReferenceIndexDTO.Owner owner = new ReferenceIndexDTO.Owner(summary.storyDirName(), summary.novelTitle());
            try {
                Path path = storyRepository.resolveStoryDirectoryReadOnly(summary.storyDirName());
                String bible = storyRepository.readStoryBible(path);
                StoryBibleParser.ParseResult parsed = StoryBibleParser.parseDiagnostic(bible);
                if (parsed.diagnostics().stream().anyMatch(item -> item.label().equals(StoryBibleParser.LABEL_WORLD_ID))) {
                    throw new IllegalStateException("世界 ID 存在歧义，不能确定集合归属");
                }
                String world = parsed.snapshot().worldId();
                String basis = storyRepository.readStoryMaintenanceBasis(path);
                if (basis == null || basis.isBlank()) throw new IllegalStateException("小说来源缺少可核对版本");
                sources.put(summary.storyDirName(), new BookSource(owner, path, world, basis, null));
            } catch (Exception e) {
                sources.put(summary.storyDirName(), new BookSource(owner, null, null, null, "小说来源无法完整读取：" + safeMessage(e)));
            }
        }
        return Map.copyOf(sources);
    }

    private String memoryPrefix() {
        return storyMemoryProperties == null ? memoryCollectionPrefix : storyMemoryProperties.getCollectionPrefix();
    }

    private String worldPrefix() {
        return storyMemoryProperties == null ? "novel-world" : storyMemoryProperties.getWorldCollectionPrefix();
    }

    private String worldIdOf(String name) {
        if (name == null || !name.startsWith(worldPrefix() + "-")) throw new IllegalArgumentException("共享世界集合不合法");
        return name.substring(worldPrefix().length() + 1);
    }

    private String effectiveReferenceMode() {
        return llmRuntimeConfig == null ? selectorProperties.getMode()
                : llmRuntimeConfig.effectiveReferenceMode(selectorProperties.getMode());
    }

    private String stateOf(VectorCollectionInfo info, boolean blockMode, ReferenceBlockSnapshot snapshot) {
        if (!"ready".equals(info.status())) return "error";
        if (!isReference(info.name())) return "unknown";
        if (ReferenceVectorIndexer.COLLECTION.equals(info.name())) return "legacy";
        if (!blockMode) return "historical";
        if (snapshot == null) return "unknown";
        String currentPrefix = ReferenceBlockIndexer.COLLECTION_PREFIX + "-" + snapshot.version() + "-d";
        boolean current = info.name().startsWith(currentPrefix)
                && (snapshot.dimensions() == null || snapshot.dimensions().equals(info.dimensions()));
        return current ? "current" : "historical";
    }

    private List<ReferenceIndexDTO.CollectionView> rebuildTargets(Inventory inventory) {
        return inventory.collections().stream().filter(collection -> collection.kind().equals("reference"))
                .filter(collection -> inventory.blockMode()
                        ? collection.state().equals("current")
                            || inventory.snapshot() != null && collection.name().startsWith(
                                ReferenceBlockIndexer.COLLECTION_PREFIX + "-" + inventory.snapshot().version() + "-d")
                        : collection.name().equals(ReferenceVectorIndexer.COLLECTION))
                .toList();
    }

    private static List<ReferenceIndexDTO.CollectionView> referenceCollections(Inventory inventory) {
        return inventory.collections().stream().filter(collection -> collection.kind().equals("reference")).toList();
    }

    private static ReferenceIndexDTO.CollectionView requireCollection(Inventory inventory, String id) {
        if (id == null || !id.matches("collection-[a-f0-9]{24}")) {
            throw new IllegalArgumentException("必须选择列表中的受管集合");
        }
        return inventory.collections().stream().filter(collection -> collection.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("集合不存在或已变化，请刷新列表"));
    }

    private static void requireWritable(ReferenceIndexDTO.CollectionView collection, String action) {
        if (!collection.kind().equals("reference") || !collection.canClear()
                || action.equals("delete") && !collection.canDelete()) {
            throw new IllegalArgumentException("小说／世界记忆只读；删除仅允许已确认的历史参考集合");
        }
    }

    private void requireIdle() {
        if (jobRegistry.isReferenceBusy()) throw busy();
    }

    private ConflictException busy() {
        return new ConflictException(jobRegistry.referenceBusyReason());
    }

    private String collectionId(String name) {
        return "collection-" + digest(vectorStore.backendName() + "|" + name).substring(0, 24);
    }

    private static boolean isReference(String name) {
        return name != null && (name.equals(ReferenceVectorIndexer.COLLECTION)
                || name.matches("novel-reference-blocks-[a-zA-Z0-9_-]+-d[1-9][0-9]*"));
    }

    private String kindOf(String name) {
        if (isReference(name)) return "reference";
        if (name != null && name.startsWith(memoryPrefix() + "-")) return "story";
        if (name != null && name.startsWith(worldPrefix() + "-")) return "world";
        return null;
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        message = message.replaceAll("(?i)Bearer\\s+\\S+", "Bearer [已隐藏]")
                .replaceAll("(?i)(api[-_]?key|token|secret)\\s*[=:]\\s*[^\\s,;]+", "$1=[已隐藏]")
                .replaceAll("sk-[a-zA-Z0-9_-]+", "[密钥已隐藏]");
        return message.length() > 240 ? message.substring(0, 240) + "…" : message;
    }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("缺少 SHA-256", e);
        }
    }
}
