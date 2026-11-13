package cn.novel.yonren.domain.novel.service.manage;

import cn.novel.yonren.api.dto.BatchHealthDTO;
import cn.novel.yonren.api.dto.ChapterContentDTO;
import cn.novel.yonren.api.dto.ChapterMetaDTO;
import cn.novel.yonren.api.dto.ChapterSaveRequestDTO;
import cn.novel.yonren.api.dto.ChapterSaveResponseDTO;
import cn.novel.yonren.api.dto.CheckpointDTO;
import cn.novel.yonren.api.dto.DeleteStoryResponseDTO;
import cn.novel.yonren.api.dto.FactTimelineDTO;
import cn.novel.yonren.api.dto.PendingFactDTO;
import cn.novel.yonren.api.dto.StoryResumeDTO;
import cn.novel.yonren.api.dto.StoryGenerateRequestDTO;
import cn.novel.yonren.api.dto.StorySummaryDTO;
import cn.novel.yonren.api.dto.StoryWorkbenchDTO;
import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.CheckpointEntity;
import cn.novel.yonren.domain.novel.model.entity.QualityDebtEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.valobj.BookExport;
import cn.novel.yonren.domain.novel.model.valobj.BookFormat;
import cn.novel.yonren.domain.novel.model.valobj.LedgerEntry;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.domain.novel.model.valobj.quality.BatchHealthReport;
import cn.novel.yonren.domain.novel.service.armory.CheckpointService;
import cn.novel.yonren.domain.novel.service.armory.edit.ChapterEditService;
import cn.novel.yonren.domain.novel.service.armory.edit.SegmentEditService;
import cn.novel.yonren.domain.novel.service.armory.memory.ChapterMemoryService;
import cn.novel.yonren.domain.novel.service.armory.memory.StoryMemoryService;
import cn.novel.yonren.domain.novel.service.armory.quality.BatchHealthService;
import cn.novel.yonren.domain.novel.service.armory.quality.ChapterLengthPolicy;
import cn.novel.yonren.domain.novel.service.armory.candidate.CandidateSampleService;
import cn.novel.yonren.domain.novel.service.export.BookExportService;
import cn.novel.yonren.domain.novel.service.job.JobRegistry;
import cn.novel.yonren.types.exception.AppException;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.utils.StoryBibleParser;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 故事库应用服务：负责读写编排；controller 只留 HTTP 语义，实体操作归本服务。
 *
 * 沿用仓内既有模式：domain 服务直接吞吐 api DTO（SegmentEditService / UserAssetStore 同款），
 * 配置经构造期注入（CheckpointService / ChapterEditService 内部 toStoryVO 同款）。
 *
 * 异常约定：业务校验失败抛 {@link AppException}（带 {@link ResponseCode}），
 * 由 controller 映射为对应 HTTP 状态；其余异常按 500 处理。
 */
@Slf4j
@Service
public class StoryManageService {

    /** 续写时的默认批次大小："这次写几章"与历史无关，给个保守默认由用户自己改 */
    private static final int DEFAULT_RESUME_BATCH_SIZE = 5;

    @Resource
    private IStoryRepository storyRepository;

    @Resource
    private JobRegistry jobRegistry;

    @Resource
    private ChapterEditService chapterEditService;

    /** 段落级编辑（润色/扩写/续写）：只改选中片段，不落盘 */
    @Resource
    private SegmentEditService segmentEditService;

    @Resource
    private StoryProperties storyProperties;

    @Resource
    private ChapterMemoryService chapterMemoryService;

    /** 删除故事时清该故事独占的记忆向量集合（共享世界观集合不动） */
    @Resource
    private StoryMemoryService storyMemoryService;

    @Resource
    private BatchHealthService batchHealthService;

    @Resource
    private CandidateSampleService candidateSampleService;

    @Resource
    private CheckpointService checkpointService;

    @Resource
    private BookExportService bookExportService;

    // ------------------------------------------------Story 生命周期

    public List<StorySummaryDTO> listStories() {
        List<IStoryRepository.StorySummary> summaries = repo(() -> storyRepository.listStories());
        List<StorySummaryDTO> result = new ArrayList<>(summaries.size());
        for (IStoryRepository.StorySummary s : summaries) {
            StorySummaryDTO dto = new StorySummaryDTO();
            dto.setStoryDirName(s.storyDirName());
            dto.setNovelTitle(s.novelTitle());
            dto.setChapterCount(s.chapterCount());
            dto.setLastModifiedMs(s.lastModifiedMs());
            dto.setActiveJob(jobRegistry.isActiveForStory(s.storyDirName()));
            result.add(dto);
        }
        return result;
    }

    /**
     * 续写准备：把已有故事的整套设定读回来（数据源是 story-bible.txt），并附上"已经写到哪儿了"。
     *
     * 为什么必须由服务端给：续写要走和首发完全相同的入参校验
     * （ValidateUserInputNode 对 11 个字段全非空），而设定字段在服务端只存在于 bible 里——
     * 这是它们唯一被持久化的地方（story-meta 只存总章数）。
     * 取不到的字段通过 {@code missingFields} 明确点名，让用户不用猜"到底缺哪个"。
     *
     * 旧版纯文本没有转义，重复标签或段内同名小标题会让字段边界不可判定。
     * 此时拒绝恢复而不是静默挑一个值：错误只带标签和行号，不回显用户设定正文。
     */
    public StoryResumeDTO resume(String storyDirName) {
        Path storyDir = resolveStoryDir(storyDirName);
        StoryBibleParser.ParseResult parsed = StoryBibleParser.parseDiagnostic(
                repo(() -> storyRepository.readStoryBible(storyDir)));
        if (parsed.hasAmbiguities()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "STORY_BIBLE_AMBIGUOUS: 设定字段边界存在歧义 " + parsed.diagnostics());
        }
        StoryBibleParser.Snapshot bible = parsed.snapshot();

        List<Integer> numbers = repo(() -> storyRepository.readChapterNumbers(storyDir));
        // 不依赖返回顺序：取最大值当末章章号——中间有缺号或乱序都不影响"下一章从哪写"
        int latest = numbers.stream().mapToInt(Integer::intValue).max().orElse(0);

        StoryGenerateRequestDTO setting = new StoryGenerateRequestDTO();
        setting.setNovel_title(bible.novelTitle());
        setting.setTheme(bible.theme());
        setting.setStyle(bible.style());
        setting.setWorldSetting(bible.worldSetting());
        setting.setPerspective(bible.perspective());
        setting.setTargetAudience(bible.targetAudience());
        setting.setTone(bible.tone());
        setting.setProtagonist(bible.protagonist());
        setting.setOutline(bible.outline());
        setting.setChapterGoal(bible.chapterGoal());
        setting.setChapterCount(DEFAULT_RESUME_BATCH_SIZE);
        setting.setResumeStoryDir(storyDirName);
        setting.setWorldId(bible.worldId());
        setting.setMaxChapterCount(readMaxChapterCount(storyDir, storyDirName));
        setting.setHasCheatMechanism(bible.hasCheatMechanism());
        setting.setCheatMechanismName(bible.mechanismIdentifier());
        setting.setCheatUsageInterval(bible.cheatUsageInterval());

        StoryResumeDTO dto = new StoryResumeDTO();
        dto.setStoryDirName(storyDirName);
        dto.setNovelTitle(bible.novelTitle());
        dto.setSetting(setting);
        dto.setExistingChapterCount(numbers.size());
        dto.setLatestChapterNo(latest == 0 ? null : latest);
        dto.setNextChapterNo(latest + 1);
        dto.setSettingAvailable(bible.novelTitle() != null && !bible.novelTitle().isBlank());
        dto.setMissingFields(missingSettingFields(bible));
        return dto;
    }

    /**
     * 总章数上限是 sticky 固化值；读不到就留空（等于不设上限）。
     * 不要在这里补一个默认值——那等于用一个凭空数字覆盖历史约定
     */
    private Integer readMaxChapterCount(Path storyDir, String storyDirName) {
        try {
            IStoryRepository.StoryMeta meta = repo(() -> storyRepository.readStoryMeta(storyDir));
            return meta == null ? null : meta.maxChapterCount();
        } catch (Exception e) {
            log.warn("story-meta 读取失败，本次续写不设总章数上限：{}（{}）", storyDirName, e.getMessage());
            return null;
        }
    }

    /** bible 里取不到的必需字段（中文名），供前端提示用户补齐 */
    private static List<String> missingSettingFields(StoryBibleParser.Snapshot bible) {
        List<String> missing = new ArrayList<>();
        addIfBlank(missing, StoryBibleParser.LABEL_NOVEL_TITLE, bible.novelTitle());
        addIfBlank(missing, StoryBibleParser.LABEL_THEME, bible.theme());
        addIfBlank(missing, StoryBibleParser.LABEL_STYLE, bible.style());
        addIfBlank(missing, StoryBibleParser.LABEL_WORLD_SETTING, bible.worldSetting());
        addIfBlank(missing, StoryBibleParser.LABEL_PERSPECTIVE, bible.perspective());
        addIfBlank(missing, StoryBibleParser.LABEL_TARGET_AUDIENCE, bible.targetAudience());
        addIfBlank(missing, StoryBibleParser.LABEL_TONE, bible.tone());
        addIfBlank(missing, StoryBibleParser.LABEL_PROTAGONIST, bible.protagonist());
        addIfBlank(missing, StoryBibleParser.LABEL_OUTLINE, bible.outline());
        addIfBlank(missing, StoryBibleParser.LABEL_CHAPTER_GOAL, bible.chapterGoal());
        return missing;
    }

    private static void addIfBlank(List<String> target, String label, String value) {
        if (value == null || value.isBlank()) {
            target.add(label);
        }
    }

    // ------------------------------------------------章节浏览与编辑

    public List<ChapterMetaDTO> listChapters(String storyDirName) {
        Path storyDir = resolveStoryDir(storyDirName);
        List<Integer> numbers = repo(() -> storyRepository.readChapterNumbers(storyDir));
        List<ChapterMetaDTO> result = new ArrayList<>(numbers.size());
        for (int no : numbers) {
            ChapterContentEntity entity = repo(() -> storyRepository.readChapter(storyDir, no));
            if (entity == null) {
                continue;
            }
            ChapterMetaDTO dto = new ChapterMetaDTO();
            dto.setChapterNo(no);
            dto.setTitle(entity.getTitle());
            dto.setContentLength(entity.getContent() == null ? 0 : entity.getContent().length());
            result.add(dto);
        }
        return result;
    }

    public ChapterContentDTO readChapter(String storyDirName, int chapterNo) {
        Path storyDir = resolveStoryDir(storyDirName);
        ChapterContentEntity entity = repo(() -> storyRepository.readChapter(storyDir, chapterNo));
        if (entity == null) {
            throw new AppException(ResponseCode.CHAPTER_NOT_FOUND.getCode(), "章节不存在：" + storyDirName + " 第 " + chapterNo + " 章");
        }
        ChapterContentDTO dto = new ChapterContentDTO();
        dto.setChapterNo(entity.getChapterNo());
        dto.setTitle(entity.getTitle());
        dto.setContent(entity.getContent());
        return dto;
    }

    /**
     * 人工保存正文：保留既有标题，正文写盘后按需触发记忆回流（重跑摘要 + 重建向量索引）。
     *
     * 人工保存只要求非空：生成路径的质量门禁不应阻止用户保存已提交短章的手改。
     */
    public ChapterSaveResponseDTO saveChapter(String storyDirName, int chapterNo, ChapterSaveRequestDTO request) {
        if (request.getContent() == null || request.getContent().isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "正文不能为空");
        }
        if (jobRegistry.isActiveForStory(storyDirName)) {
            throw new AppException(ResponseCode.ACTIVE_JOB_CONFLICT.getCode(), "该故事有运行中的作业，请等待完成后再编辑");
        }
        if (!ChapterLengthPolicy.meetsEditMinimum(request.getContent())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "正文不能为空");
        }
        Path storyDir = resolveStoryDir(storyDirName);
        ChapterContentEntity existing = repo(() -> storyRepository.readChapter(storyDir, chapterNo));
        ChapterContentEntity entity = new ChapterContentEntity();
        entity.setChapterNo(chapterNo);
        entity.setTitle(existing == null || existing.getTitle() == null ? "" : existing.getTitle());
        entity.setContent(request.getContent());
        // 走正式编辑事务：正文、接受记录与待回流标记一次落盘。
        // 先写正文再补摘要的两步写法，一旦在中间失败就会留下"正文已改、摘要仍是旧版且无任何标记"的静默不一致。
        repo(() -> storyRepository.saveHumanEdit(storyDir, entity,
                new IStoryRepository.PendingChapterEdit(chapterNo, false)));

        // 记忆回流是整章级的 LLM 调用。段落级润色/扩写不改情节事实，
        // 重算摘要是纯浪费——前端传 reflow=false 跳过；续写引入了新内容，必须回流，否则账本与正文脱节。
        // 跳过回流时待回流标记保留，下次显式回流或继续生成时会补上。
        ChapterSaveResponseDTO dto = new ChapterSaveResponseDTO();
        dto.setSaved(true);
        if (Boolean.FALSE.equals(request.getReflow())) {
            dto.setSummaryUpdated(false);
            dto.setPartial(false);
            log.info("正文已保存（第{}章），按请求跳过记忆回流（段落级润色/扩写不改事实）", chapterNo);
            return dto;
        }

        ChapterEditService.ReflowResult reflow = chapterEditService.reflow(storyDir, chapterNo, request.getContent());
        dto.setSummaryUpdated(reflow.summaryUpdated());
        dto.setPartial(reflow.partial());
        dto.setWarning(reflow.warning());
        return dto;
    }

    /**
     * 段落级文本编辑：选中一段正文，让 AI 只改这一段（润色 / 扩写 / 续写）。
     *
     * 为什么不落盘：返回的是"建议片段"，前端做对照让用户确认后再走既有的
     * 保存路径——那条路径已含摘要回流与运行中作业互斥检查，这里另开写盘通道会让两套一致性机制分叉。
     *
     * 为什么校验 selectedText 是否在正文里：接口在本机无鉴权，不校验会让它
     * 退化成"任意文本改写代理"，消耗额度却不产生写作价值。
     */
    public cn.novel.yonren.api.dto.SegmentEditResponseDTO segmentEdit(String storyDirName, int chapterNo,
                                                                      cn.novel.yonren.api.dto.SegmentEditRequestDTO request) {
        Path storyDir = resolveStoryDir(storyDirName);
        ChapterContentEntity chapter = repo(() -> storyRepository.readChapter(storyDir, chapterNo));
        if (chapter == null) {
            throw new AppException(ResponseCode.CHAPTER_NOT_FOUND.getCode(), "章节不存在：" + storyDirName + " 第 " + chapterNo + " 章");
        }
        if (request.getSelectedText() == null || request.getSelectedText().isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "选中文本为空");
        }
        if (chapter.getContent() == null || !chapter.getContent().contains(request.getSelectedText())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "选中文本不在该章正文中（可能章节已被修改，请刷新后重试）");
        }

        PromptContext ctx = buildSegmentPromptContext(storyDir, chapterNo);
        try {
            return segmentEditService.edit(storyProperties.toStoryVO(), chapterNo, request, ctx);
        } catch (IllegalArgumentException e) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), e.getMessage());
        }
    }

    /** 段落编辑的 prompt 上下文：只取题材/风格，不读账本与伏笔（轻量诉求不该付整章的上下文成本） */
    private PromptContext buildSegmentPromptContext(Path storyDir, int chapterNo) {
        PromptContext ctx = PromptContext.builder().chapterNo(chapterNo).build();
        try {
            StoryBibleParser.Snapshot bible = StoryBibleParser.parse(repo(() -> storyRepository.readStoryBible(storyDir)));
            ctx.setTheme(bible.theme());
            ctx.setStyle(bible.style());
        } catch (Exception e) {
            // 题材/风格只影响资料检索的选材，缺失时降级为不检索而不是失败
            log.warn("段落编辑读取 story-bible 失败，题材/风格留空：{}", e.getMessage());
        }
        return ctx;
    }

    // ------------------------------------------------工作台与体检

    /**
     * 工作台概览：仅聚合已有章节、摘要和质量文件，供本地写作界面一次加载。
     * 不另存账本，三类账本仍由摘要确定性重建，确保展示和生成上下文一致。
     */
    public StoryWorkbenchDTO workbench(String storyDirName) {
        Path storyDir = resolveStoryDir(storyDirName);
        List<Integer> chapterNumbers = repo(() -> storyRepository.readChapterNumbers(storyDir));
        List<ChapterSummaryEntity> summaries = repo(() -> storyRepository.readChapterSummaries(storyDir));
        List<QualityDebtEntity> debts = repo(() -> storyRepository.readQualityDebts(storyDir));
        List<StageBlueprintEntity> blueprints = repo(() -> storyRepository.readStageBlueprints(storyDir));

        String storyBible = repo(() -> storyRepository.readStoryBible(storyDir));
        StoryWorkbenchDTO dto = new StoryWorkbenchDTO();
        dto.setStoryDirName(storyDirName);
        dto.setNovelTitle(resolveNovelTitle(storyDirName));
        dto.setStoryOverview(extractBibleField(storyBible, "故事概述"));
        dto.setChapterCount(chapterNumbers.size());
        StageBlueprintEntity latestBlueprint = blueprints == null || blueprints.isEmpty()
                ? null : blueprints.get(blueprints.size() - 1);
        if (latestBlueprint != null) {
            dto.setStoryPhase(latestBlueprint.getStoryPhase());
            dto.setFinalVolumeDeclared(Boolean.TRUE.equals(latestBlueprint.getFinalVolumeDeclared()));
            dto.setEstimatedTotalChapters(latestBlueprint.getEstimatedTotalChapters());
            dto.setEstimatedRemainingChaptersMin(latestBlueprint.getEstimatedRemainingChaptersMin());
            dto.setEstimatedRemainingChaptersMax(latestBlueprint.getEstimatedRemainingChaptersMax());
            if (latestBlueprint.getRemainingFinaleBeats() != null) {
                dto.setRemainingFinaleBeats(new ArrayList<>(latestBlueprint.getRemainingFinaleBeats()));
            }
            if (latestBlueprint.getCompletedFinaleBeats() != null) {
                dto.setCompletedFinaleBeats(new ArrayList<>(latestBlueprint.getCompletedFinaleBeats()));
            }
        }
        // 兼容尚未生成新版蓝图的旧故事：资料中的章节数量只作为初始估算展示。
        if (dto.getEstimatedTotalChapters() == null) {
            dto.setEstimatedTotalChapters(parseInteger(extractBibleField(storyBible, "章节数量")));
        }
        if (dto.getStoryPhase() == null || dto.getStoryPhase().isBlank()) {
            dto.setStoryPhase("NORMAL");
        }
        if (dto.getEstimatedTotalChapters() != null
                && dto.getEstimatedRemainingChaptersMin() == null
                && dto.getEstimatedRemainingChaptersMax() == null) {
            int remaining = Math.max(0, dto.getEstimatedTotalChapters() - chapterNumbers.size());
            dto.setEstimatedRemainingChaptersMin(Math.max(0, (int) Math.floor(remaining * 0.85)));
            dto.setEstimatedRemainingChaptersMax((int) Math.ceil(remaining * 1.15));
        }
        dto.setActiveJob(jobRegistry.isActiveForStory(storyDirName));
        dto.setCharacters(toLedgerDTO(chapterMemoryService.buildCharacterLedger(summaries)));
        dto.setItems(toLedgerDTO(chapterMemoryService.buildLedger(summaries, ChapterSummaryEntity::getItemStates)));
        dto.setFactions(toLedgerDTO(chapterMemoryService.buildLedger(summaries, ChapterSummaryEntity::getFactionStates)));
        dto.setQualityDebts(toQualityDebtDTO(debts));

        StoryWorkbenchDTO.WorkbenchMetrics metrics = new StoryWorkbenchDTO.WorkbenchMetrics();
        int totalWords = 0;
        for (int chapterNo : chapterNumbers) {
            ChapterContentEntity chapter = repo(() -> storyRepository.readChapter(storyDir, chapterNo));
            int length = chapter == null || chapter.getContent() == null ? 0 : chapter.getContent().length();
            totalWords += length;
            if (dto.getLatestChapterNo() == null || chapterNo > dto.getLatestChapterNo()) {
                dto.setLatestChapterNo(chapterNo);
                dto.setLatestChapterLength(length);
            }
        }
        metrics.setTotalCharacters(totalWords);
        metrics.setTotalWords(totalWords);
        metrics.setAverageChapterWords(chapterNumbers.isEmpty() ? 0 : totalWords / chapterNumbers.size());

        for (ChapterSummaryEntity summary : summaries) {
            if (summary.getContinuityConflicts() != null) {
                metrics.setContinuityConflictCount(metrics.getContinuityConflictCount() + summary.getContinuityConflicts().size());
            }
            collectPendingFacts(dto.getPendingFacts(), summary);
        }
        metrics.setPendingFactCount(dto.getPendingFacts().size());
        metrics.setUnresolvedQualityDebtCount((int) debts.stream().filter(debt -> debt != null && !debt.isResolved()).count());

        for (ChapterMemoryService.PendingForeshadow pending : chapterMemoryService.buildPendingForeshadowDetailed(summaries)) {
            StoryWorkbenchDTO.ForeshadowDTO item = new StoryWorkbenchDTO.ForeshadowDTO();
            item.setChapterNo(pending.chapterNo());
            item.setContent(pending.content());
            item.setStatus("待回收");
            item.setImportance(pending.importance());
            dto.getForeshadowing().add(item);
        }
        metrics.setUnresolvedForeshadowCount(dto.getForeshadowing().size());
        dto.setMetrics(metrics);
        return dto;
    }

    /**
     * 批末体检（只读、零 LLM 成本）：把阶段一~四建立的机械指标横着看一遍，输出可分级、可行动的
     * 健康清单。与批末日志是同一份结论，供人工按需复算——调整阈值后无需重跑生成即可立即复核。
     */
    public BatchHealthDTO health(String storyDirName) {
        Path storyDir = resolveStoryDir(storyDirName);
        BatchHealthReport report = batchHealthService.assess(
                repo(() -> storyRepository.readChapterSummaries(storyDir)),
                repo(() -> storyRepository.readQualityDebts(storyDir)),
                repo(() -> storyRepository.readStageBlueprints(storyDir)),
                candidateSampleService.readStats(storyDir));
        return toBatchHealthDTO(report);
    }

    /** 领域报告 → DTO：组件名逐一对齐，JSON 形状不变 */
    private BatchHealthDTO toBatchHealthDTO(BatchHealthReport report) {
        List<BatchHealthDTO.MetricDTO> metrics = new ArrayList<>(report.metrics().size());
        for (BatchHealthReport.Metric m : report.metrics()) {
            metrics.add(new BatchHealthDTO.MetricDTO(
                    m.key(), m.label(), m.value(), m.unit(), m.healthyLine(),
                    BatchHealthDTO.Direction.valueOf(m.direction().name()),
                    BatchHealthDTO.Level.valueOf(m.level().name()), m.detail()));
        }
        return new BatchHealthDTO(report.chapterCount(), metrics,
                BatchHealthDTO.Level.valueOf(report.overall().name()), report.recommendations());
    }

    // ------------------------------------------------检查点

    /** 列出故事全部检查点（按版本升序） */
    public List<CheckpointDTO> listCheckpoints(String storyDirName) {
        Path storyDir = resolveStoryDir(storyDirName);
        List<CheckpointEntity> checkpoints = repo(() -> checkpointService.list(storyDir));
        List<CheckpointDTO> result = new ArrayList<>(checkpoints.size());
        for (CheckpointEntity cp : checkpoints) {
            result.add(toCheckpointDTO(cp));
        }
        return result;
    }

    /** 手动命名快照；name 必填 */
    public CheckpointDTO createCheckpoint(String storyDirName, String name) {
        if (name == null || name.isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "手动快照必须提供命名词（name）");
        }
        if (jobRegistry.isActiveForStory(storyDirName)) {
            throw new AppException(ResponseCode.ACTIVE_JOB_CONFLICT.getCode(), "该故事有运行中的作业，请等待完成后再打检查点");
        }
        Path storyDir = resolveStoryDir(storyDirName);
        String id = repo(() -> checkpointService.manualSnapshot(storyDir, name.trim()));
        CheckpointEntity cp = repo(() -> storyRepository.readCheckpoint(storyDir, id));
        CheckpointDTO dto = toCheckpointDTO(cp);
        dto.setCurrent(false);
        return dto;
    }

    /**
     * 回滚到指定检查点：章节/摘要/蓝图/账本原子还原 + 向量索引重建。
     * 返回还原后的检查点（current）——**必须带 JSON 体**：前端 fetchJson 统一按 Content-Type 判别响应，
     * void + 空体会被误报为「服务暂不可用（200）」。
     */
    public CheckpointDTO restoreCheckpoint(String storyDirName, String checkpointId) {
        if (jobRegistry.isActiveForStory(storyDirName)) {
            throw new AppException(ResponseCode.ACTIVE_JOB_CONFLICT.getCode(), "该故事有运行中的作业，请等待完成后再回滚");
        }
        Path storyDir = resolveStoryDir(storyDirName);
        repo(() -> checkpointService.restore(storyDir, checkpointId));
        CheckpointDTO dto = toCheckpointDTO(repo(() -> storyRepository.readCheckpoint(storyDir, checkpointId)));
        if (dto == null) {
            dto = new CheckpointDTO();
            dto.setCheckpointId(checkpointId);
        }
        dto.setCurrent(true);
        return dto;
    }

    // ------------------------------------------------导出

    /**
     * 导出成书：分章 ZIP——每章一个独立文件（TXT / Markdown / Word），解压即「书名文件夹 + 分章文件」。
     * 只读，不加「作业运行中」守卫——章节文件是原子写（tmp+move），导出的是发起时刻的快照。
     * 返回 HTTP 载荷（文件名 + ZIP 字节）；章数/字数等元信息留在领域产物 BookExport 内部。
     */
    public cn.novel.yonren.api.dto.ExportFilePayloadDTO exportBook(String storyDirName, String format) {
        BookFormat bookFormat = BookFormat.fromParam(format);
        if (bookFormat == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "不支持的导出格式：" + format + "（可选 txt / md / docx）");
        }
        Path storyDir = resolveStoryDir(storyDirName);
        BookExport book = repo(() -> bookExportService.exportChapters(storyDir, bookFormat));
        if (book == null) {
            throw new AppException(ResponseCode.NO_STORY_DATA.getCode(), "该故事还没有可导出的章节");
        }
        return new cn.novel.yonren.api.dto.ExportFilePayloadDTO(book.getFileName(), book.getFileBytes());
    }

    // ------------------------------------------------待确认事实 / 时间线

    /**
     * 待人工确认事实查询：摘要证据校验未通过的状态事实（疑似编造/漂移，不入账本），
     * 按章节聚合透出供人工裁决。数据源为 stories/<dir>/memory/summaries.json 的 pendingFacts
     */
    public List<PendingFactDTO> pendingFacts(String storyDirName) {
        Path storyDir = resolveStoryDir(storyDirName);
        List<ChapterSummaryEntity> summaries = repo(() -> storyRepository.readChapterSummaries(storyDir));
        List<PendingFactDTO> result = new ArrayList<>();
        for (ChapterSummaryEntity summary : summaries) {
            collectPendingFacts(result, summary);
        }
        return result;
    }

    /**
     * 实体事实时间线追溯：按实体名检索其在全书各章的状态轨迹（章节 + 账本类型 + 状态 + 证据原文）。
     * 排障用途——模型产出错误状态时，一次调用定位"该状态出自第几章、哪段正文"。
     * 精确匹配无结果时回退包含匹配；证据引用为摘要侧机械校验通过的原句片段
     */
    public List<FactTimelineDTO> factTimeline(String storyDirName, String name) {
        if (name == null || name.isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "实体名不能为空");
        }
        Path storyDir = resolveStoryDir(storyDirName);
        List<ChapterSummaryEntity> summaries = repo(() -> storyRepository.readChapterSummaries(storyDir));
        String target = name.trim();
        List<FactTimelineDTO> exact = new ArrayList<>();
        List<FactTimelineDTO> partial = new ArrayList<>();
        for (ChapterSummaryEntity summary : summaries) {
            int chapterNo = summary.getChapterNo() == null ? 0 : summary.getChapterNo();
            collectMatchingFacts(summary.getCharacterStates(), "角色", chapterNo, target, exact, partial);
            collectMatchingFacts(summary.getItemStates(), "物品", chapterNo, target, exact, partial);
            collectMatchingFacts(summary.getFactionStates(), "势力", chapterNo, target, exact, partial);
        }
        // 精确匹配无结果时回退包含匹配（实体名带称号/全名差异的容错）
        return exact.isEmpty() ? partial : exact;
    }

    // ------------------------------------------------删除

    /**
     * **删除故事**（故事库的删除操作，不可恢复）：删故事目录 + 清该故事独占的记忆向量集合。
     *
     * 为什么先守卫作业：运行中/审批挂起的作业持有该故事的路径与上下文，
     * 删目录后它会继续往不存在的目录写（甚至重建出半个目录）。有活动作业时直接 409，
     * 让用户先等完成或取消——这与回滚端点的守卫同款。
     *
     * 删除顺序：**先删目录、再清集合**。目录是用户可见的主体，先做；
     * 集合清理是 fail-soft（deleteStoryMemory 内部只 WARN），失败不影响删除结果。
     * 共享的世界观集合 novel-world-<worldId> 不动——同世界观下可能有其它故事在引用。
     */
    public DeleteStoryResponseDTO deleteStory(String storyDirName) {
        if (jobRegistry.isActiveForStory(storyDirName)) {
            throw new AppException(ResponseCode.ACTIVE_JOB_CONFLICT.getCode(), "该故事有运行中的作业，请等待完成或取消后再删除");
        }
        Path storyDir = resolveStoryDir(storyDirName);
        repo(() -> storyRepository.deleteStory(storyDir));
        try {
            storyMemoryService.deleteStoryMemory(storyDir);
        } catch (Exception me) {
            // 集合清理是 fail-soft（deleteStoryMemory 内部只 WARN），这里兜底不让它影响删除结果
            log.warn("故事记忆向量集合清理失败（不影响删除结果）：{}", me.getMessage());
        }
        log.info("故事已删除：{}", storyDirName);
        return new DeleteStoryResponseDTO(true, storyDirName);
    }

    // ------------------------------------------------内部工具

    /** 从单章的状态条目中收集目标实体：命中精确桶；否则进包含桶（仅精确无结果时使用） */
    private void collectMatchingFacts(List<ChapterSummaryEntity.StateEntry> states, String factType,
                                      int chapterNo, String target,
                                      List<FactTimelineDTO> exact, List<FactTimelineDTO> partial) {
        if (states == null) {
            return;
        }
        for (ChapterSummaryEntity.StateEntry state : states) {
            if (state == null || state.getName() == null) {
                continue;
            }
            String entryName = state.getName().trim();
            if (entryName.equals(target)) {
                exact.add(new FactTimelineDTO(chapterNo, entryName, factType, state.getStatus(), state.getEvidence()));
            } else if (entryName.contains(target) || target.contains(entryName)) {
                partial.add(new FactTimelineDTO(chapterNo, entryName, factType, state.getStatus(), state.getEvidence()));
            }
        }
    }

    private void collectPendingFacts(List<PendingFactDTO> target, ChapterSummaryEntity summary) {
        if (summary == null) return;
        if (summary.getPendingFacts() != null) {
            for (ChapterSummaryEntity.StateEntry fact : summary.getPendingFacts()) {
                if (fact != null) {
                    target.add(new PendingFactDTO(summary.getChapterNo() == null ? 0 : summary.getChapterNo(),
                            fact.getName(), fact.getStatus(), fact.getEvidence()));
                }
            }
        }
        if (summary.getPendingConsistencyFacts() != null) {
            for (ChapterSummaryEntity.ConsistencyFact fact : summary.getPendingConsistencyFacts()) {
                if (fact != null) {
                    String status = fact.getType() + "：" + fact.getValue()
                            + (fact.getScope() == null || fact.getScope().isBlank() ? "" : "（" + fact.getScope() + "）");
                    target.add(new PendingFactDTO(summary.getChapterNo() == null ? 0 : summary.getChapterNo(),
                            fact.getSubject(), status, fact.getEvidence()));
                }
            }
        }
    }

    private CheckpointDTO toCheckpointDTO(CheckpointEntity cp) {
        if (cp == null) {
            return null;
        }
        CheckpointDTO dto = new CheckpointDTO();
        dto.setCheckpointId(cp.getCheckpointId());
        dto.setVersionNo(cp.getVersionNo());
        dto.setName(cp.getName());
        dto.setType(cp.getType() == null ? null : cp.getType().name());
        dto.setChapterCount(cp.getChapterCount());
        dto.setFileCount(cp.getFilesManifest() == null ? 0 : cp.getFilesManifest().size());
        dto.setCreatedAtMs(cp.getCreatedAtMs());
        dto.setCurrent(cp.isCurrent());
        return dto;
    }

    private List<StoryWorkbenchDTO.LedgerDTO> toLedgerDTO(List<LedgerEntry> entries) {
        List<StoryWorkbenchDTO.LedgerDTO> result = new ArrayList<>();
        for (LedgerEntry entry : entries) {
            if (entry == null) continue;
            StoryWorkbenchDTO.LedgerDTO dto = new StoryWorkbenchDTO.LedgerDTO();
            dto.setName(entry.getName());
            dto.setStatus(entry.getStatus());
            dto.setFirstChapterNo(entry.getFirstChapterNo());
            dto.setLastChapterNo(entry.getLastChapterNo());
            dto.setPrevStatus(entry.getPrevStatus());
            dto.setEvidence(entry.getEvidence());
            dto.setReversalSuspect(entry.isReversalSuspect());
            result.add(dto);
        }
        return result;
    }

    private List<StoryWorkbenchDTO.QualityDebtDTO> toQualityDebtDTO(List<QualityDebtEntity> debts) {
        List<StoryWorkbenchDTO.QualityDebtDTO> result = new ArrayList<>();
        for (QualityDebtEntity debt : debts) {
            if (debt == null) continue;
            StoryWorkbenchDTO.QualityDebtDTO dto = new StoryWorkbenchDTO.QualityDebtDTO();
            dto.setChapterNo(debt.getChapterNo());
            dto.setIssues(debt.getIssues());
            dto.setResolved(debt.isResolved());
            dto.setCleanStreak(debt.getCleanStreak());
            result.add(dto);
        }
        return result;
    }

    private String resolveNovelTitle(String storyDirName) {
        try {
            return storyRepository.listStories().stream()
                    .filter(s -> storyDirName.equals(s.storyDirName()))
                    .map(IStoryRepository.StorySummary::novelTitle)
                    .findFirst().orElse(storyDirName);
        } catch (Exception e) {
            return storyDirName;
        }
    }

    private String extractBibleField(String bible, String field) {
        if (bible == null || bible.isBlank()) return "";
        for (String line : bible.split("\\R")) {
            String normalized = line.trim();
            String asciiPrefix = field + ":";
            String fullWidthPrefix = field + "：";
            if (normalized.startsWith(asciiPrefix)) return normalized.substring(asciiPrefix.length()).trim();
            if (normalized.startsWith(fullWidthPrefix)) return normalized.substring(fullWidthPrefix.length()).trim();
        }
        return "";
    }

    private Integer parseInteger(String value) {
        if (value == null || value.isBlank()) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+").matcher(value);
        return matcher.find() ? Integer.valueOf(matcher.group()) : null;
    }

    /**
     * 解析故事目录：目录名空 → 参数非法；仓储解析失败 → 目录不存在。
     */
    private Path resolveStoryDir(String storyDirName) {
        if (storyDirName == null || storyDirName.isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "故事目录名不能为空");
        }
        try {
            return storyRepository.resolveStoryDirectory(storyDirName);
        } catch (Exception e) {
            throw new AppException(ResponseCode.NO_STORY_DATA.getCode(), "故事目录不存在或格式非法：" + storyDirName + "（" + e.getMessage() + "）");
        }
    }
    /** 仓储/检查点调用包装：受检异常统一转 AppException(UN_ERROR)，由 trigger 层映射为 500 */
    private <T> T repo(RepoCall<T> call) {
        try {
            return call.get();
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), e.getMessage(), e);
        }
    }

    /** repo 的 void 版本（写盘/删除类调用） */
    private void repo(RepoRun call) {
        try {
            call.run();
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface RepoCall<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    private interface RepoRun {
        void run() throws Exception;
    }
}
