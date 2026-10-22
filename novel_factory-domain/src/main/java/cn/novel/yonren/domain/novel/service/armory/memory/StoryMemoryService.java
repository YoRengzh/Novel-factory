package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.adapter.llm.EmbeddingGateway;
import cn.novel.yonren.domain.novel.adapter.repository.VectorStore;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.entity.VolumeBlueprintEntity;
import cn.novel.yonren.domain.novel.model.valobj.LedgerEntry;
import cn.novel.yonren.domain.novel.model.valobj.ScoredVectorPoint;
import cn.novel.yonren.domain.novel.model.valobj.StoredPoint;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.VectorPoint;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryMemoryProperties;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import cn.novel.yonren.domain.novel.service.armory.LlmRuntimeConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 故事记忆层：二期（跨章剧情记忆）与三期（人物/世界观记忆）共用一套向量通道。
 * 每章检查点把本章摘要、最新三账本条目、story-bible 幂等写入集合 novel-memory-{故事目录名}；
 * 五期扩展：worldId 合法时 bible 点另写 novel-world-{worldId}（仅 bible 共享，chapter/ledger 故事私有）。
 * 写新章 / 规划批次前按相关性检索唤醒久远记忆，由 ChapterMemoryService 渲染进记忆前缀。
 * 相关性门控（minScore）+ 字符预算（maxRecallChars）贯彻反注水原则；
 * 检索链路失败语义（2026-09-28 细化）：**瞬时失败先做有界重试**（限流/5xx/网络 IO/向量库调用异常，
 * 上限见 retrieve-max-attempts），重试耗尽或确定性失败（欠费/鉴权/参数/未配置）仍终止作业——
 * llm 降级与静默跳过的质量不可接受（静默跳过会让整批在无记忆前缀下跑完，产出上看不出来）；
 * 仅索引写入路径保持 fail-soft 告警（失败点可由下次幂等写入补齐）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoryMemoryService {

    public static final String KIND_CHAPTER = "chapter";
    public static final String KIND_LEDGER = "ledger";
    public static final String KIND_BIBLE = "bible";
    public static final String KIND_VOLUME = "volume";

    /** 回滚对账只管这三类点；同集合里的卷方向点（volume）由卷生成路径维护，不参与对账也不被删 */
    private static final Set<String> MANAGED_KINDS = Set.of(KIND_CHAPTER, KIND_LEDGER, KIND_BIBLE);

    private static final int EMBED_BATCH = 16;
    private static final Pattern WORLD_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_-]{0,62}$");

    /**
     * 运行时嵌入覆盖（设置页「向量检索分区」）：索引签名必须按**生效配置**计算。
     * 若只读 yml 静态值，用户换 embedding 模型后签名不变 → 重建守卫静默失效：
     * 维度不同则每章先付费重嵌全量记忆再在 ensureCollection 失败（永久空召回），
     * 维度相同则把两套不可比的向量空间混进同一集合。
     * 字段注入而非构造器参数：不改变既有构造签名（大量单测直接 new）。
     */
    @Autowired(required = false)
    private LlmRuntimeConfig llmRuntimeConfig;

    /**
     * 检索查询串安全上限（字符）：查询只需主题相关性，不需要全文保真。
     * 无界查询（如规划查询拼入整个 storyContext）会被 embedding 供应商按参数错误拒绝
     * （智谱 400/1210），导致该路检索静默失效（fail-soft 跳过、唤醒恒为 0）——
     * 在网关入口统一截断兜底，任何查询构造方超长都不再致命
     */
    private static final int MAX_QUERY_CHARS = 1200;
    /** buildPlanQuery 内 storyContext 贡献段截断（预留任务/目标段预算） */
    private static final int PLAN_QUERY_CONTEXT_CHARS = 500;
    /** buildPlanQuery 内阶段任务拼接段截断 */
    private static final int PLAN_QUERY_TASKS_CHARS = 400;

    /**
     * 单个记忆点的目标字符数（2026-09-27 切块）：此前"一章一个点、整份 bible 一个点"，
     * 单点可达 2000~3000 字，导致唤醒预算实际绑不住（首条整条放进前缀），
     * 且检索粒度太粗——命中一整章会把无关内容也一起拉进来。
     * 现按句边界切成 ~600 字的点，预算才真正可绑定、召回也更精准。
     */
    private static final int MEM_CHUNK_CHARS = 600;

    /** 切块标记：与"点边界"相关（只用于拼接时的自然断句），不计入语义 */
    private static final String CHUNK_SEPARATORS = "。！？；\n";

    /**
     * 记忆索引模式版本：**任何改变"点如何切分 / 键如何生成"的改动都必须递增**。
     * 递增后旧集合会被整集合重建——否则旧键的残留点与旧粒度的点会同时命中，前缀里出现重复内容。
     */
    private static final int MEMORY_SCHEMA_VERSION = 2;

    /** 索引元数据（记录集合里的向量出自哪个 embedding 模型 + 哪版切分方案） */
    private static final String INDEX_META_FILE = "embedding-index-meta.json";

    private final EmbeddingGateway embeddingGateway;
    private final VectorStore vectorStore;
    private final ChapterMemoryService chapterMemoryService;
    private final StoryMemoryProperties properties;

    /** 相关性唤醒命中条目：text 为注入原文（已过 minScore 门控与字符预算） */
    public record RecallHit(String text, double score) {
    }

    /** 待写入点：stableKey 生成稳定点 id，text 用于向量化，payload 携带展示元数据 */
    private record MemText(String stableKey, String text, Map<String, String> payload) {
    }

    /**
     * 每章检查点调用（便捷入口）：取 summaries 最后一章的 chapterNo 委托给 indexChapter。
     * worldId 从外部传入——null 或非法时跳过世界集合操作，行为与二期三期完全一致。
     */
    public void indexAfterChapter(StoryVO.Module module, Path storyDir,
                                  List<ChapterSummaryEntity> summaries, String worldId) {
        if (summaries == null || summaries.isEmpty()) {
            return;
        }
        indexChapter(module, storyDir, summaries, summaries.get(summaries.size() - 1).getChapterNo(), worldId);
    }

    /**
     * 核心索引方法：幂等写入指定章的摘要点 + **本章动过的**账本条目 + 设定点（bible 变更时才写）到故事集合；
     * worldId 合法时 bible 点另写世界集合（novel-world-{worldId}）。
     * 失败仅告警——向量通道不可用不影响章节生成与落盘。
     *
     * <p>为什么账本可以只写"本章动过的条目"（而非旧实现的每章全量重写）：账本合并语义保证
     * {@code lastChapterNo} 每次提及都刷新，所以任一时刻每个条目的点都恰好在它"最后一次被提及"
     * 的那一章被写过；而状态只会在被提及时变化，故"本章没提及"的条目其已存点仍是最新状态。
     * 这个增量把每章写入从"全量账本 + 设定（百章规模已 228 点）"降到"本章点 + 少数变动条目"。
     *
     * <p>安全性依赖两件事，都在下面显式处理：上次写入失败（脏标记）→ 本次全量补偿；
     * bible 被作者改过（指纹不符）→ 本次补写设定点。
     */
    public void indexChapter(StoryVO.Module module, Path storyDir,
                             List<ChapterSummaryEntity> summaries, int chapterNo, String worldId) {
        if (!enabled(module, storyDir) || summaries == null || summaries.isEmpty()) {
            return;
        }
        try {
            // 换 embedding 模型或换切分方案后，集合里的旧向量与新向量**不可比**（维度相同也一样）。
            // 这类污染是静默的——余弦相似度退化成噪声却不报错，从产出上几乎看不出来。
            // 所以这里用元数据把"必须重嵌"从口头约定变成强制执行：签名不符就整集合重建。
            if (needsRebuild(module, storyDir, summaries)) {
                rebuild(module, storyDir, summaries, worldId);
                return;
            }

            ChapterSummaryEntity target = null;
            for (ChapterSummaryEntity s : summaries) {
                if (s.getChapterNo() == chapterNo) {
                    target = s;
                    break;
                }
            }
            if (target == null) {
                return;
            }

            boolean fullRepair = indexDirty(storyDir);
            boolean bibleChanged = fullRepair || bibleIndexOutdated(storyDir);
            List<MemText> storyTexts = new ArrayList<>(chapterTexts(storyDir, target));
            appendLedgerTexts(storyDir, storyTexts, summaries, fullRepair ? null : chapterNo);
            if (bibleChanged) {
                storyTexts.addAll(bibleTexts(storyDir));
            }
            embedAndUpsert(module, storyTexts, collectionOf(storyDir));

            if (isValidWorldId(worldId) && bibleChanged) {
                List<MemText> worldTexts = new ArrayList<>(worldBibleTexts(storyDir, worldId));
                if (!worldTexts.isEmpty()) {
                    embedAndUpsert(module, worldTexts, worldCollectionOf(worldId));
                }
            }
            if (fullRepair) {
                log.info("上次索引写入失败过，本章已按全量补偿（第 {} 章）", chapterNo);
            }
            writeIndexMeta(module, storyDir);
        } catch (Exception e) {
            markIndexDirty(module, storyDir);
            log.warn("故事记忆索引失败，已跳过（不阻塞生成；已标记待补偿）：{}", e.getMessage());
        }
    }

    /**
     * 整集合重建：先清空集合（连旧键的残留点一起清掉），再用当前模型与当前切分方案重新索引全部章节。
     *
     * <p>必须清空而非就地覆盖：切块后键名变了（{@code #chapter#3} → {@code #chapter#3#0}），
     * 只 upsert 会让新旧两套键同时存在，检索命中重复内容。
     * 全量重建的成本是"每章一个点 + 账本 + 设定"，一次批量 embedding，相对一次章节生成可忽略。
     */
    public void rebuild(StoryVO.Module module, Path storyDir,
                        List<ChapterSummaryEntity> summaries, String worldId) {
        if (!enabled(module, storyDir) || summaries == null || summaries.isEmpty()) {
            return;
        }
        try {
            String collection = collectionOf(storyDir);
            vectorStore.clear(collection);
            List<MemText> texts = new ArrayList<>();
            for (ChapterSummaryEntity summary : summaries) {
                if (summary == null || summary.getChapterNo() == null) {
                    continue;
                }
                texts.addAll(chapterTexts(storyDir, summary));
            }
            appendLedgerTexts(storyDir, texts, summaries, null);
            texts.addAll(bibleTexts(storyDir));
            embedAndUpsert(module, texts, collection);

            if (isValidWorldId(worldId)) {
                String worldCollection = worldCollectionOf(worldId);
                List<MemText> worldTexts = new ArrayList<>(worldBibleTexts(storyDir, worldId));
                if (!worldTexts.isEmpty()) {
                    vectorStore.clear(worldCollection);
                    embedAndUpsert(module, worldTexts, worldCollection);
                }
            }
            writeIndexMeta(module, storyDir);
            log.info("故事记忆整集合重建完成（embedding 模型或切分方案已变更）：{} 点 → {}",
                    texts.size(), collection);
        } catch (Exception e) {
            // 与索引一致：向量通道不可用不得反噬生成。但要让运维看得见——否则会一直用着被污染的旧集合
            log.warn("故事记忆整集合重建失败，本次继续沿用既有集合（可能为旧模型向量，检索质量不可信）：{}",
                    e.getMessage());
        }
    }

    /**
     * 是否需要整集合重建：索引元数据缺失（老故事首次接入）或签名不符。
     *
     * <p>元数据缺失时分两种情况：全新建书（只有 1 章）视为正常首建，直接索引即可；
     * 已有历史章节却无元数据，则无法确认集合里的向量出自哪个模型/哪版切分方案，保守重建。
     */
    private boolean needsRebuild(StoryVO.Module module, Path storyDir, List<ChapterSummaryEntity> summaries) {
        String actual = indexSignature(module);
        String recorded = readIndexSignature(storyDir);
        if (recorded == null) {
            return summaries.size() > 1;
        }
        return !recorded.equals(actual);
    }

    /** 生效的嵌入配置：叠加设置页运行时覆盖；单测直构（未注入）时原样返回 */
    private StoryVO.Module effectiveEmbeddingModule(StoryVO.Module module) {
        return llmRuntimeConfig == null || module == null ? module : llmRuntimeConfig.applyEmbedding(module);
    }

    /** 索引签名 = 切分方案版本 + 生效 embedding 模型 + 显式维度；任一变化都必须重嵌 */
    private String indexSignature(StoryVO.Module rawModule) {
        StoryVO.Module module = effectiveEmbeddingModule(rawModule);
        StoryVO.Module.EmbeddingApi api = module == null ? null : module.getEmbeddingApi();
        return MEMORY_SCHEMA_VERSION + "|" + (api == null ? "" : nullToBlank(api.getModel()))
                + "|" + (api == null || api.getDimensions() == null ? "" : api.getDimensions());
    }

    /** 索引元数据：签名 + 切分方案版本 + bible 指纹 + 脏标记；读不出按"缺失"处理（保守重建） */
    private com.alibaba.fastjson2.JSONObject readIndexMeta(Path storyDir) {
        Path file = storyDir.resolve("memory").resolve(INDEX_META_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return com.alibaba.fastjson2.JSON.parseObject(Files.readString(file));
        } catch (Exception e) {
            // 元数据坏了按"缺失"处理（保守重建），不让它成为阻断点
            return null;
        }
    }

    private String readIndexSignature(Path storyDir) {
        com.alibaba.fastjson2.JSONObject meta = readIndexMeta(storyDir);
        return meta == null ? null : meta.getString("signature");
    }

    /**
     * 增量维护的两个前置判断（读元数据，不做任何 IO 写）：
     *
     * <p>bible 指纹不一致 = 作者改过设定（bible 是作者所有物，程序只在首建时写）→ 本次要补设定点；
     * 换模型/换切分由 {@link #needsRebuild} 单独负责。
     *
     * <p>脏标记 = 上次索引写入失败了。增量写入只写"本章动过的条目"，若把上次失败缺失的点留在那儿，
     * 没有任何后续动作会补它（旧实现每章全量重写，天然自愈）——所以这里必须显式记住失败，
     * 下一次写入退化为全量补偿。
     */
    private boolean bibleIndexOutdated(Path storyDir) {
        com.alibaba.fastjson2.JSONObject meta = readIndexMeta(storyDir);
        String recorded = meta == null ? null : meta.getString("bibleHash");
        return !Objects.equals(recorded, bibleHash(readBibleContent(storyDir)));
    }

    private boolean indexDirty(Path storyDir) {
        com.alibaba.fastjson2.JSONObject meta = readIndexMeta(storyDir);
        return meta != null && Boolean.TRUE.equals(meta.getBoolean("dirty"));
    }

    /** bible 内容指纹：只用于判断设定点是否要重嵌，非安全用途；算法缺失时退化为长度+hashCode（只会多写不会少写） */
    static String bibleHash(String content) {
        if (StringUtils.isBlank(content)) {
            return "";
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return content.length() + "-" + content.hashCode();
        }
    }

    private void writeIndexMeta(StoryVO.Module module, Path storyDir) {
        writeIndexMeta(module, storyDir, false);
    }

    /** @param dirty 上次写入是否失败：置位后下一次索引写入退化为全量补偿，成功后自动清零 */
    private void writeIndexMeta(StoryVO.Module module, Path storyDir, boolean dirty) {
        try {
            Path dir = storyDir.resolve("memory");
            Files.createDirectories(dir);
            Map<String, Object> meta = new HashMap<>();
            meta.put("signature", indexSignature(module));
            meta.put("schemaVersion", MEMORY_SCHEMA_VERSION);
            meta.put("bibleHash", bibleHash(readBibleContent(storyDir)));
            meta.put("dirty", dirty);
            Files.writeString(dir.resolve(INDEX_META_FILE),
                    com.alibaba.fastjson2.JSON.toJSONString(meta), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("索引元数据写入失败（下次可能触发一次多余的重建）：{}", e.getMessage());
        }
    }

    /** 索引写入失败时的留痕（best-effort）：让下一次写入知道自己缺了块，做一次全量补偿 */
    private void markIndexDirty(StoryVO.Module module, Path storyDir) {
        writeIndexMeta(module, storyDir, true);
    }

    /**
     * 卷蓝图幂等索引入向量记忆（kind=VOLUME）：卷生成时调用一次，作全书长期锚的"方向增益"。
     * 文本 = 卷名/卷主旨/承转合/结束条件/卷级伏笔；供后续弧/卷生成按语义召回早期卷方向。
     * 与检查点索引同集合、同幂等语义（stableId = {storyId}-vol-{volumeNo}）。
     * 失败仅告警——方向是增益通道，不可用不影响生成
     */
    public void indexVolume(StoryVO.Module module, Path storyDir, VolumeBlueprintEntity volume) {
        if (!enabled(module, storyDir) || volume == null) {
            return;
        }
        try {
            String storyId = storyDir.getFileName().toString();
            StringBuilder text = new StringBuilder();
            text.append("第").append(volume.getVolumeNo()).append("卷《")
                    .append(nullToBlank(volume.getTitle())).append("》：")
                    .append(nullToBlank(volume.getThemeShift()));
            if (volume.getBeats() != null && !volume.getBeats().isEmpty()) {
                text.append("\n承转合：").append(String.join("；", volume.getBeats()));
            }
            if (volume.getVolumeExitConditions() != null && !volume.getVolumeExitConditions().isEmpty()) {
                text.append("\n卷结束条件：").append(String.join("；", volume.getVolumeExitConditions()));
            }
            if (volume.getSeeds() != null && !volume.getSeeds().isEmpty()) {
                text.append("\n卷级伏笔：").append(String.join("；", volume.getSeeds()));
            }
            Map<String, String> payload = new HashMap<>();
            payload.put("kind", KIND_VOLUME);
            payload.put("storyId", storyId);
            payload.put("volumeNo", String.valueOf(volume.getVolumeNo()));
            payload.put("title", nullToBlank(volume.getTitle()));
            payload.put("text", text.toString());
            embedAndUpsert(module, List.of(new MemText(storyId + "#volume#" + volume.getVolumeNo(),
                    text.toString(), payload)), collectionOf(storyDir));
        } catch (Exception e) {
            log.warn("卷向量索引失败，已跳过（不阻塞生成）：{}", e.getMessage());
        }
    }

    /**
     * 回滚后的索引对账：让集合内容与回滚后的故事状态一致，**只动该动的点**。
     *
     * <p>退化路径两条，都保留旧语义：签名不符（换模型/换切分）走 {@link #rebuild} 整集合重建（含 world）；
     * 读不回现状（向量库抖动/集合结构异常）走整集合重写。world 集合多故事共享且 bible 不受回滚影响，刻意不动。
     *
     * <p>结果点集与"整集合重写"完全一致（同一批稳定 id + 同一批文本），差别只在省掉了没变的那些重嵌。
     * 写入后按集合实际点数做一次机械核对并留痕，避免"少写/漏删"静默通过。
     */
    public void rebuildIndex(StoryVO.Module module, Path storyDir,
                             List<ChapterSummaryEntity> summaries, String worldId) {
        if (!enabled(module, storyDir) || summaries == null || summaries.isEmpty()) {
            return;
        }
        if (needsRebuild(module, storyDir, summaries)) {
            rebuild(module, storyDir, summaries, worldId);
            return;
        }
        String storyCollection = collectionOf(storyDir);
        List<MemText> desired = storyTexts(storyDir, summaries);
        List<StoredPoint> stored;
        try {
            stored = vectorStore.listPoints(storyCollection);
        } catch (Exception e) {
            log.warn("回滚索引对账失败（读不回集合现状），退化为整集合重写：{}", e.getMessage());
            fullRewrite(module, storyDir, storyCollection, desired);
            return;
        }
        Map<String, String> storedText = new HashMap<>();
        for (StoredPoint point : stored) {
            storedText.put(point.getId(), point.getText());
        }
        Set<String> desiredIds = new HashSet<>();
        List<MemText> toWrite = new ArrayList<>();
        for (MemText text : desired) {
            String id = stableId(text.stableKey());
            desiredIds.add(id);
            if (!text.text().equals(storedText.get(id))) {
                toWrite.add(text);
            }
        }
        // 只删"本链路管理的点"：同集合里还有卷方向点（kind=volume），那是卷生成路径维护的，回滚不该抹掉它
        List<String> toDelete = stored.stream()
                .filter(p -> MANAGED_KINDS.contains(p.getKind()) && !desiredIds.contains(p.getId()))
                .map(StoredPoint::getId)
                .toList();
        try {
            if (!toDelete.isEmpty()) {
                vectorStore.delete(storyCollection, toDelete);
            }
            embedAndUpsert(module, toWrite, storyCollection);
            writeIndexMeta(module, storyDir);
            // 核对口径必须与"应有"对齐：**只数本链路管理的点**（chapter/ledger/bible）。
            // 此前用集合总数（vectorStore.count）与 desiredIds.size() 比对，而同一集合里
            // 还有卷方向点（kind=volume，由卷生成路径维护、回滚刻意保留）——
            // 两个口径撞在一起必然差一个卷点数，每次回滚都报一条"可能残留多余点"的误报
            // （实测 145 章回滚：应 453 实际 454，差值 1 正是一个卷方向点）。
            // 必现的误报会训练人忽略这条 WARN，以后真出问题也会被当成噪声。
            //
            // 第二个口径坑（2026-10-19 修正）：toWrite 里**多数是已存在点的改写**（id 相同、文本变了），
            // 它们不增加点数。旧式 `managedStored - toDelete + toWrite` 把改写也当成新增，
            // 于是每次回滚都多算「改写条数」个点（实测 165 章回滚：应 518 实际报 524，差值 6 正是改写数），
            // 而集合实际受管点恰好 518。这里只加"原本不存在、这次才写入"的点。
            long managedStored = stored.stream()
                    .filter(p -> MANAGED_KINDS.contains(p.getKind()))
                    .count();
            long newlyInserted = toWrite.stream()
                    .filter(t -> !storedText.containsKey(stableId(t.stableKey())))
                    .count();
            long actualManaged = managedStored - toDelete.size() + newlyInserted;
            long totalInCollection = vectorStore.count(storyCollection);
            log.info("回滚索引对账完成：{}（{} 章，应有受管点 {}，实际受管点 {}，集合总点数 {}，改写 {}，删除 {}）",
                    storyCollection, summaries.size(), desiredIds.size(), actualManaged,
                    totalInCollection, toWrite.size(), toDelete.size());
            if (actualManaged != desiredIds.size()) {
                log.warn("回滚索引对账后受管点数不符（应 {} 实际 {}）：{} 可能有漏写/漏删，建议核对",
                        desiredIds.size(), actualManaged, storyCollection);
            }
        } catch (Exception e) {
            // 对账是"部分写入"语义：失败时集合内容可能滞后于回滚边界，必须说清，别留下"索引可用"的错觉
            log.warn("回滚索引对账失败（集合可能仍含回滚掉的点，记忆召回暂不可信）：{}", e.getMessage());
        }
    }

    /** 整集合重写：清空 + 全量写入 + 落元数据（对账不可用时的退化路径，语义与旧实现一致） */
    private void fullRewrite(StoryVO.Module module, Path storyDir, String collection, List<MemText> texts) {
        try {
            vectorStore.clear(collection);
        } catch (Exception e) {
            log.warn("回滚向量清空失败，跳过重建（vestigial 索引可能召回已回滚内容）：{}", e.getMessage());
            return;
        }
        try {
            embedAndUpsert(module, texts, collection);
            writeIndexMeta(module, storyDir);
            log.info("回滚向量整集合重写完成：{}（{} 点）", collection, texts.size());
        } catch (Exception e) {
            log.warn("回滚向量整集合重写失败（集合已清空，记忆召回暂空，随下次章节写入逐步恢复）：{}", e.getMessage());
        }
    }

    /**
     * **删除该故事独占的记忆集合**（故事库删除操作的一部分）：{@code novel-memory-<故事目录名>}。
     *
     * <p><b>为什么必须删</b>：向量集合不随故事目录一起消失——它是 Qdrant 里的独立存储。
     * 只删目录会留下孤儿集合长期占用向量库空间（每故事数百点），这是"删除故事释放内存"漏掉的一半。
     *
     * <p><b>为什么不删世界观集合</b>：{@code novel-world-<worldId>} 是**同世界多故事共享**的
     * （同一世界观的设定点以 {@code worldId#storyId#bible} 为稳定键写入），整集合清空会误伤
     * 该世界观下其它故事的召回。清本故事的 bible 点需要按 payload 过滤删点，属另一件事。
     *
     * <p><b>fail-soft</b>：向量库不可用（Qdrant 未启动）/清理失败只 WARN 不抛出——删目录是主操作，
     * 向量残留不该阻断它（与 {@code CheckpointService} 回滚后重建索引的"不阻塞"约定一致）。
     * 集合不存在时 {@code clear} 是静默 no-op，重复删除安全。
     */
    public void deleteStoryMemory(Path storyDir) {
        if (storyDir == null) {
            return;
        }
        String collection = collectionOf(storyDir);
        try {
            vectorStore.clear(collection);
            log.info("故事记忆集合已删除：{}", collection);
        } catch (Exception e) {
            log.warn("故事记忆集合删除失败（已跳过，不影响目录删除；集合 {} 可能残留）：{}",
                    collection, e.getMessage());
        }
    }

    /** 当前故事状态对应的完整点集：全部章节点 + 全量账本 + 设定（对账的"应有"侧） */
    private List<MemText> storyTexts(Path storyDir, List<ChapterSummaryEntity> summaries) {
        List<MemText> texts = new ArrayList<>();
        for (ChapterSummaryEntity s : summaries) {
            if (s != null && s.getChapterNo() != null) {
                texts.addAll(chapterTexts(storyDir, s));
            }
        }
        appendLedgerTexts(storyDir, texts, summaries, null);
        texts.addAll(bibleTexts(storyDir));
        return texts;
    }

    /**
     * 检索"卷方向增益"：专用入口，只召回 kind=VOLUME 的记忆点。
     * 用于弧/卷生成时按当前弧意图语义召回早期卷的卷主旨/承转合/承诺，补足长期方向
     */
    public List<RecallHit> retrieveDirections(StoryVO.Module module, Path storyDir,
                                              String queryText, String worldId) {
        return retrieveWithOutcome(module, storyDir, queryText, null, worldId, KIND_VOLUME).hits();
    }

    /**
     * 写新章 / 规划前调用：按相关性检索久远记忆。失败或关闭时返回空列表（调用方跳过渲染）。
     * worldId 合法时额外检索世界集合，两路合并按 score 降序 + text 去重，共用 maxRecallChars 预算。
     *
     * @param minChapterNo 章节类命中仅保留 chapterNo < minChapterNo 的点（近期窗口章节已在
     *                     【前章剧情摘要】直给，防重复注入）；null 或非章节类命中不过滤
     */
    public List<RecallHit> retrieve(StoryVO.Module module, Path storyDir,
                                    String queryText, Integer minChapterNo, String worldId) {
        return retrieveWithOutcome(module, storyDir, queryText, minChapterNo, worldId).hits();
    }

    /**
     * 带**降级状态**的检索重载（2026-09-29）。
     *
     * <p>存在的理由：降级与"真无命中"返回的命中表**完全一样**（都是空表），调用方据此区分不了，
     * 于是"本批有几章是在无记忆前缀下裸跑的"只能靠人翻日志。调用方（ChapterWorker）把 degraded
     * 记到本章摘要的机械标记上，由体检汇总成指标——质量债是回灌给写手的"你上章犯的错"，
     * 基础设施抖动塞进去等于给模型下错误指令，故不走那条通道。
     */
    public RecallOutcome retrieveWithOutcome(StoryVO.Module module, Path storyDir,
                                             String queryText, Integer minChapterNo, String worldId) {
        return retrieveWithOutcome(module, storyDir, queryText, minChapterNo, worldId, null);
    }

    /** 检索结果 + 是否发生降级：{@code hits} 为空且 {@code degraded=true} 表示"这次没检索到"，而非"确实没有" */
    public record RecallOutcome(List<RecallHit> hits, boolean degraded) {
        /** 正常返回（含"关闭/空查询"与"确实无命中"）。工厂名不与组件访问器同名，避免与 {@code degraded()} 撞签名 */
        public static RecallOutcome of(List<RecallHit> hits) {
            return new RecallOutcome(hits, false);
        }

        /** 降级返回：链路失败，本次没有任何检索结果 */
        public static RecallOutcome degradedOutcome() {
            return new RecallOutcome(List.of(), true);
        }
    }

    /**
     * 检索（可指定 onlyKind 只召回某类记忆点：null=全部，VOLUME=卷方向）。
     * 其余语义与五参重载一致。
     *
     * <p>失败语义（2026-09-29 统一为"降级留痕"）：瞬时失败先做有界重试（判定见
     * {@link #isRetryableRetrievalFailure}），
     * 重试耗尽或确定性失败（欠费/鉴权/参数/未配置）时**降级为空召回并 WARN 留痕**，不终止作业——
     * 记忆唤醒是增强件；主链路 LLM 调用的欠费/鉴权失败会自行暴露，检索层无需代劳终止。
     * 降级可 grep RECALL_DEGRADED 归因，与"降级必留痕"的既有口径一致（非静默）。
     * 作业取消（线程中断）不降级也不重试，立即上抛交由上层收敛
     */
    public RecallOutcome retrieveWithOutcome(StoryVO.Module module, Path storyDir,
                                             String queryText, Integer minChapterNo, String worldId, String onlyKind) {
        if (!enabled(module, storyDir) || StringUtils.isBlank(queryText)) {
            return RecallOutcome.of(List.of());
        }
        int attempts = Math.max(1, properties.getRetrieveMaxAttempts());
        Exception last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                // 入口统一截断兜底：超长查询会被供应商拒绝（智谱 1210），且检索不需要全文保真
                String boundedQuery = StringUtils.abbreviate(queryText.trim(), MAX_QUERY_CHARS);
                float[] queryVector = embeddingGateway.embed(module, List.of(boundedQuery)).get(0);

                List<ScoredVectorPoint> storyHits = vectorStore.search(
                        collectionOf(storyDir), queryVector, properties.getTopK());

                List<ScoredVectorPoint> worldHits = List.of();
                if (isValidWorldId(worldId)) {
                    try {
                        worldHits = vectorStore.search(
                                worldCollectionOf(worldId), queryVector, properties.getTopK());
                    } catch (Exception e) {
                        log.warn("世界集合检索失败，已跳过：{}", e.getMessage());
                    }
                }

                double effectiveWorldMinScore = properties.getWorldMinScore() != null
                        ? properties.getWorldMinScore() : properties.getMinScore();

                List<ScoredVectorPoint> merged = new ArrayList<>();
                for (ScoredVectorPoint hit : storyHits) {
                    if (hit.getScore() >= properties.getMinScore()) {
                        merged.add(hit);
                    }
                }
                for (ScoredVectorPoint hit : worldHits) {
                    if (hit.getScore() >= effectiveWorldMinScore) {
                        merged.add(hit);
                    }
                }
                merged.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

                List<RecallHit> result = new ArrayList<>();
                Set<String> seenTexts = new HashSet<>();
                int budget = 0;
                int maxChars = properties.getMaxRecallChars();
                // 单条上限 = 一个切块：正常记忆点本就 ≈MEM_CHUNK_CHARS 切块），
                // 只有非切块点（卷方向/存量点）会触发截断——防一条长命中独占整个唤醒预算
                int perHitChars = Math.min(MEM_CHUNK_CHARS, maxChars);
                for (ScoredVectorPoint hit : merged) {
                    Map<String, String> payload = hit.getPayload();
                    String text = payload == null ? null : payload.get("text");
                    if (StringUtils.isBlank(text)) {
                        continue;
                    }
                    if (onlyKind != null && !onlyKind.equals(payload.get("kind"))) {
                        continue;
                    }
                    if (!seenTexts.add(text)) {
                        continue;
                    }
                    if (minChapterNo != null && KIND_CHAPTER.equals(payload.get("kind"))
                            && isChapterNoAtLeast(payload, minChapterNo)) {
                        continue;
                    }
                    // 单条封顶后再试入预算：因 perHitChars <= maxChars，最高分那条必然入选——
                    // 有命中时前缀不会为空 起首条不再"整条放行"，防长命中撑爆预算）
                    String admitted = text.length() > perHitChars ? truncateTo(text, perHitChars) : text;
                    if (budget + admitted.length() > maxChars) {
                        // 装不下：跳过本条继续看后续候选（贪心填满预算）。
                        // 旧实现在此处 break——一条大命中会把其后所有候选一并截断（尾部丢失）；
                        // 命中已按分数降序，跳过只是让位给"更小但仍在门控内"的高分候选
                        continue;
                    }
                    budget += admitted.length();
                    result.add(new RecallHit(admitted, hit.getScore()));
                }
                if (result.isEmpty()) {
                    log.info("故事记忆检索无命中（topK={}, minScore={}）", properties.getTopK(), properties.getMinScore());
                } else {
                    double min = result.stream().mapToDouble(RecallHit::score).min().orElse(0);
                    double max = result.stream().mapToDouble(RecallHit::score).max().orElse(0);
                    log.info("故事记忆检索命中 {} 点（分数 {}~{}，共 {} 字{}）", result.size(),
                            String.format("%.3f", min), String.format("%.3f", max), budget,
                            worldHits.isEmpty() ? "" : "，含世界集合");
                }
                return RecallOutcome.of(result);
            } catch (Exception e) {
                last = e;
                // 作业取消（线程中断）不重试：break 后按取消信号上抛，交由上层收敛
                if (attempt >= attempts || !isRetryableRetrievalFailure(e)
                        || Thread.currentThread().isInterrupted()) {
                    break;
                }
                long backoff = RETRY_BACKOFF_MS * attempt;
                log.warn("故事记忆检索瞬时失败（第 {}/{} 次尝试），{}ms 后重试：{}",
                        attempt, attempts, backoff, e.getMessage());
                sleepBeforeRetry(backoff);
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
            }
        }
        // 取消信号：不降级继续生成——挂起的作业该停就停
        if (Thread.currentThread().isInterrupted()) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(),
                    "故事记忆检索被取消（作业中断，不降级继续）", last);
        }
        // 重试耗尽或确定性失败：降级留痕继续生成 统一，原语义为终止作业）。
        // 记忆唤醒是增强件——主链路（正文 LLM 调用）的欠费/鉴权失败会自行暴露，检索层代劳终止
        // 只会放大故障面；但降级必须可归因，grep RECALL_DEGRADED 即可定位本批哪些环节无检索
        log.warn("RECALL_DEGRADED 故事记忆检索失败，本批降级为无检索唤醒继续"
                        + "（瞬时重试 {} 次耗尽或确定性失败）：{}",
                attempts, last == null ? "未知原因" : last.getMessage(), last);
        // 降级状态随结果上抛，由调用方落到本章摘要的机械标记 → 体检汇总（见 RecallOutcome 说明）
        return RecallOutcome.degradedOutcome();
    }

    /**
     * 可自愈失败判定：与 embedding 网关同一套瞬时语义（限流/5xx、网络 IO），
     * 外加向量库侧——Qdrant 适配把所有异步失败统一经 {@code .get()} 抛出，
     * 异常链上必然出现 {@link ExecutionException}。其中含"集合不存在"等确定性项，
     * 但重试次数有界、失败终态不变，多两次往返的代价可接受（包级可见供单测）
     */
    static boolean isRetryableRetrievalFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof TransientAiException || t instanceof ResourceAccessException
                    || t instanceof ExecutionException || t instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    /** 重试等待：退避基数 × 已尝试次数（包级可见供单测桩替换，避免测试真实睡眠） */
    void sleepBeforeRetry(long backoffMs) {
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException ie) {
            // 中断（作业取消）只回填标志位：重试循环据此停止，不再发起调用
            Thread.currentThread().interrupt();
        }
    }

    /** 检索瞬时失败的重试退避基数：第 n 次失败后等待 n × 1s 再试（与 embedding 网关同节奏） */
    private static final long RETRY_BACKOFF_MS = 1000L;

    /** 正文查询串：题材/风格 + 本章计划（目标/关键事件/类型） */
    public String buildContentQuery(String theme, String style, ChapterPlanItemEntity item) {
        StringBuilder sb = new StringBuilder("写作场景：撰写章节正文");
        if (StringUtils.isNotBlank(theme)) {
            sb.append("；题材：").append(theme);
        }
        if (StringUtils.isNotBlank(style)) {
            sb.append("；风格：").append(style);
        }
        if (item != null) {
            sb.append("；本章目标：").append(nullToBlank(item.getGoal()));
            if (item.getKeyEvents() != null && !item.getKeyEvents().isEmpty()) {
                sb.append("；关键事件：").append(String.join("、", item.getKeyEvents()));
            }
            if (item.getChapterType() != null) {
                sb.append("；章节类型：").append(item.getChapterType().getDesc());
            }
        }
        return sb.toString();
    }

    /** 规划查询串：故事背景 + 阶段蓝图方向（各贡献段有界截断，防整段 storyContext 超出供应商输入上限） */
    public String buildPlanQuery(String storyContext, StageBlueprintEntity blueprint) {
        StringBuilder sb = new StringBuilder("写作场景：规划章节大纲");
        if (StringUtils.isNotBlank(storyContext)) {
            sb.append("；故事背景：")
                    .append(StringUtils.abbreviate(storyContext.replace('\n', '，').trim(), PLAN_QUERY_CONTEXT_CHARS));
        }
        if (blueprint != null) {
            sb.append("；阶段目标：").append(nullToBlank(blueprint.getStageGoal()));
            if (blueprint.getTasks() != null && !blueprint.getTasks().isEmpty()) {
                sb.append("；阶段任务：").append(StringUtils.abbreviate(
                        String.join("、", blueprint.getTasks()), PLAN_QUERY_TASKS_CHARS));
            }
        }
        return sb.toString();
    }

    private void embedAndUpsert(StoryVO.Module module, List<MemText> texts, String collection) {
        if (texts.isEmpty()) {
            return;
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += EMBED_BATCH) {
            List<MemText> batch = texts.subList(i, Math.min(i + EMBED_BATCH, texts.size()));
            vectors.addAll(embeddingGateway.embed(module, batch.stream().map(MemText::text).toList()));
        }
        List<VectorPoint> points = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            MemText text = texts.get(i);
            points.add(VectorPoint.builder()
                    .id(stableId(text.stableKey()))
                    .vector(vectors.get(i))
                    .payload(text.payload())
                    .build());
        }
        vectorStore.ensureCollection(collection, vectors.get(0).length);
        vectorStore.upsert(collection, points);
        log.info("故事记忆索引完成：{} 点 → {}（维度 {}）", points.size(), collection, vectors.get(0).length);
    }

    private List<MemText> chapterTexts(Path storyDir, ChapterSummaryEntity summary) {
        String storyId = storyDir.getFileName().toString();
        StringBuilder text = new StringBuilder();
        text.append("第").append(summary.getChapterNo()).append("章《").append(nullToBlank(summary.getTitle()))
                .append("》：").append(nullToBlank(summary.getSummary()));
        if (StringUtils.isNotBlank(summary.getTimePoint())) {
            text.append("\n时点：").append(summary.getTimePoint());
        }
        if (summary.getForeshadowSeeds() != null && !summary.getForeshadowSeeds().isEmpty()) {
            String seeds = summary.getForeshadowSeeds().stream()
                    .map(ChapterSummaryEntity.SeedEntry::getContent)
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.joining("；"));
            if (StringUtils.isNotBlank(seeds)) {
                text.append("\n伏笔埋设：").append(seeds);
            }
        }
        Map<String, String> payload = new HashMap<>();
        payload.put("kind", KIND_CHAPTER);
        payload.put("storyId", storyId);
        payload.put("chapterNo", String.valueOf(summary.getChapterNo()));
        payload.put("title", nullToBlank(summary.getTitle()));
        return chunk(storyId + "#chapter#" + summary.getChapterNo(), text.toString(), payload);
    }

    /**
     * 把一段长文本按句边界切成多个记忆点；**不足一块时保持原键**——
     * 短文本不引入无谓的键变化，切块前后行为一致。
     * payload 的 text 逐块替换，其余字段（kind/storyId/chapterNo/...）原样复制，
     * 因此检索侧的去重（按 text）与近章窗口过滤（按 chapterNo）口径完全不变。
     */
    private List<MemText> chunk(String baseKey, String text, Map<String, String> basePayload) {
        List<String> pieces = splitByBoundary(text, MEM_CHUNK_CHARS);
        List<MemText> chunks = new ArrayList<>(Math.max(1, pieces.size()));
        for (int i = 0; i < pieces.size(); i++) {
            Map<String, String> payload = new HashMap<>(basePayload);
            payload.put("text", pieces.get(i));
            String key = pieces.size() == 1 ? baseKey : baseKey + "#" + i;
            chunks.add(new MemText(key, pieces.get(i), payload));
        }
        return chunks;
    }

    /**
     * 按句边界累积切分：尽量在 {@link #CHUNK_SEPARATORS} 处断开，单句超长时硬切。
     * 硬切兜底是必需的——没有它，遇到没有标点的长文本会退化成"一整块"，切块形同虚设。
     */
    static List<String> splitByBoundary(String text, int maxChars) {
        List<String> out = new ArrayList<>();
        if (StringUtils.isBlank(text)) {
            return out;
        }
        if (maxChars <= 0 || text.length() <= maxChars) {
            out.add(text);
            return out;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + maxChars);
            if (end < text.length()) {
                for (int i = end; i > start; i--) {
                    if (CHUNK_SEPARATORS.indexOf(text.charAt(i - 1)) >= 0) {
                        end = i;
                        break;
                    }
                }
            }
            out.add(text.substring(start, end));
            start = end;
        }
        return out;
    }

    /**
     * @param onlyChapterNo 非空时只写 {@code lastChapterNo == 该章} 的条目（每章增量维护：本章新出现或
     *                      状态变更的条目必在其中）；null = 全量（整集合重建、回滚对账、失败后的补偿写入）
     */
    private void appendLedgerTexts(Path storyDir, List<MemText> texts, List<ChapterSummaryEntity> summaries,
                                   Integer onlyChapterNo) {
        String storyId = storyDir.getFileName().toString();
        appendLedgerKind(storyId, texts, "角色",
                touchedOnly(chapterMemoryService.buildCharacterLedger(summaries), onlyChapterNo));
        appendLedgerKind(storyId, texts, "物品",
                touchedOnly(chapterMemoryService.buildLedger(summaries, ChapterSummaryEntity::getItemStates), onlyChapterNo));
        appendLedgerKind(storyId, texts, "势力",
                touchedOnly(chapterMemoryService.buildLedger(summaries, ChapterSummaryEntity::getFactionStates), onlyChapterNo));
    }

    /**
     * "本章提及过的条目"过滤：{@code lastChapterNo} 每次提及都刷新，因此"状态在本章变过"必然是它的子集；
     * 多写的那部分（只提及、未变化）文本与已存点相同，纯粹是省事的余量，不会造成内容偏差。
     */
    private static List<LedgerEntry> touchedOnly(List<LedgerEntry> entries, Integer onlyChapterNo) {
        if (onlyChapterNo == null || entries == null || entries.isEmpty()) {
            return entries;
        }
        return entries.stream()
                .filter(e -> onlyChapterNo.equals(e.getLastChapterNo()))
                .toList();
    }

    private void appendLedgerKind(String storyId, List<MemText> texts, String ledgerType, List<LedgerEntry> entries) {
        for (LedgerEntry entry : entries) {
            if (StringUtils.isBlank(entry.getName())) {
                continue;
            }
            String text = ledgerType + "：" + entry.getName() + "。状态：" + nullToBlank(entry.getStatus());
            Map<String, String> payload = new HashMap<>();
            payload.put("kind", KIND_LEDGER);
            payload.put("storyId", storyId);
            payload.put("ledgerType", ledgerType);
            payload.put("name", entry.getName());
            payload.put("text", text);
            texts.add(new MemText(storyId + "#ledger#" + ledgerType + "#" + entry.getName(), text, payload));
        }
    }

    private List<MemText> bibleTexts(Path storyDir) {
        String content = readBibleContent(storyDir);
        if (content == null) {
            return List.of();
        }
        String storyId = storyDir.getFileName().toString();
        Map<String, String> payload = new HashMap<>();
        payload.put("kind", KIND_BIBLE);
        payload.put("storyId", storyId);
        return chunk(storyId + "#bible", content, payload);
    }

    private List<MemText> worldBibleTexts(Path storyDir, String worldId) {
        String content = readBibleContent(storyDir);
        if (content == null) {
            return List.of();
        }
        String storyId = storyDir.getFileName().toString();
        Map<String, String> payload = new HashMap<>();
        payload.put("kind", KIND_BIBLE);
        payload.put("storyId", storyId);
        payload.put("worldId", worldId);
        return chunk(worldId + "#" + storyId + "#bible", content, payload);
    }

    private String readBibleContent(Path storyDir) {
        Path bibleFile = storyDir.resolve("story-bible.txt");
        if (!Files.exists(bibleFile)) {
            return null;
        }
        try {
            String content = Files.readString(bibleFile).trim();
            return StringUtils.isBlank(content) ? null : content;
        } catch (Exception e) {
            log.warn("story-bible.txt 读取失败，跳过设定点：{}", e.getMessage());
            return null;
        }
    }

    private boolean isChapterNoAtLeast(Map<String, String> payload, int minChapterNo) {
        String raw = payload.get("chapterNo");
        if (raw == null) {
            return false;
        }
        try {
            return Integer.parseInt(raw) >= minChapterNo;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String collectionOf(Path storyDir) {
        return properties.getCollectionPrefix() + "-" + storyDir.getFileName();
    }

    private String worldCollectionOf(String worldId) {
        return properties.getWorldCollectionPrefix() + "-" + worldId;
    }

    static boolean isValidWorldId(String worldId) {
        return worldId != null && !worldId.isBlank() && WORLD_ID_PATTERN.matcher(worldId.trim()).matches();
    }

    private boolean enabled(StoryVO.Module module, Path storyDir) {
        return properties.isEnabled() && module != null && storyDir != null;
    }

    private String stableId(String stableKey) {
        // 显式 UTF-8：getBytes() 用平台默认字符集（Windows GBK / IDE UTF-8），
        // 跨运行环境会导致同键不同 UUID，幂等覆盖退化为重复入点
        return UUID.nameUUIDFromBytes(stableKey.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    /** 截断标记：既让读者知道被截过，也让前缀长度可预期 */
    private static final String TRUNCATE_MARK = "……（本条命中超唤醒预算，已截断）";

    /**
     * 按字符预算截断单条命中，**含标记在内的总长不超过 maxChars**。
     * 用于单条命中的预算收敛（单条上限/首条兜底）：截断点落在字符边界，不做语义切分——
     * 记忆点本身是句子级文本，硬切最多损失末句，远优于整条 3000 字灌进前缀。
     */
    private static String truncateTo(String text, int maxChars) {
        if (text == null || maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        if (maxChars <= TRUNCATE_MARK.length()) {
            // 预算比标记本身还小（极端配置）：只截正文，不加标记，保证长度上界成立
            return text.substring(0, maxChars);
        }
        int keep = maxChars - TRUNCATE_MARK.length();
        return text.substring(0, keep) + TRUNCATE_MARK;
    }

}
