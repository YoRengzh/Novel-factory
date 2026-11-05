package cn.novel.yonren.trigger.http;

import cn.novel.yonren.api.dto.ChapterContentDTO;
import cn.novel.yonren.api.dto.ChapterMetaDTO;
import cn.novel.yonren.api.dto.ChapterSaveRequestDTO;
import cn.novel.yonren.api.dto.ChapterSaveResponseDTO;
import cn.novel.yonren.api.dto.CheckpointCreateRequestDTO;
import cn.novel.yonren.api.dto.CheckpointDTO;
import cn.novel.yonren.api.dto.FactTimelineDTO;
import cn.novel.yonren.api.dto.PendingFactDTO;
import cn.novel.yonren.api.dto.StoryResumeDTO;
import cn.novel.yonren.api.dto.StoryGenerateRequestDTO;
import cn.novel.yonren.api.dto.StorySummaryDTO;
import cn.novel.yonren.api.dto.StoryWorkbenchDTO;
import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.CheckpointEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.QualityDebtEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.valobj.BookExport;
import cn.novel.yonren.domain.novel.model.valobj.LedgerEntry;
import cn.novel.yonren.domain.novel.service.armory.CheckpointService;
import cn.novel.yonren.domain.novel.service.armory.prompt.valobj.PromptContext;
import cn.novel.yonren.domain.novel.service.armory.memory.ChapterMemoryService;
import cn.novel.yonren.domain.novel.service.armory.edit.ChapterEditService;
import cn.novel.yonren.domain.novel.service.armory.quality.ChapterLengthPolicy;
import cn.novel.yonren.domain.novel.service.export.BookExportService;
import cn.novel.yonren.domain.novel.service.export.BookFormat;
import cn.novel.yonren.domain.novel.service.job.JobRegistry;
import cn.novel.yonren.types.utils.StoryBibleParser;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 故事库端点：列表 / 章节浏览 / 章节编辑（编辑触发摘要回流）。
 * 无鉴权（localhost 个人使用）
 *
 * <p>⚠️ 所有映射注解必须显式写参数名（如 {@code @PathVariable("storyDirName")}）：
 * 根 pom 钉的 maven-compiler-plugin 3.0 未开 {@code -parameters}，Spring 拿不到反射参数名，
 * 省略名字会在运行期抛 IllegalArgumentException（编译期无任何征兆）。
 */
@Slf4j
@RestController
@RequestMapping("/api/stories")
public class StoryManageController {

    @Resource
    private IStoryRepository storyRepository;

    @Resource
    private JobRegistry jobRegistry;

    @Resource
    private ChapterEditService chapterEditService;

    /** 段落级编辑（润色/扩写/续写）：只改选中片段，不落盘 */
    @Resource
    private cn.novel.yonren.domain.novel.service.armory.edit.SegmentEditService segmentEditService;

    /** 段落编辑需要 toStoryVO() 取模型配置（与设定集草稿同款入口） */
    @Resource
    private cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties storyProperties;

    @Resource
    private ChapterMemoryService chapterMemoryService;

    /** 删除故事时清该故事独占的记忆向量集合（共享世界观集合不动） */
    @Resource
    private cn.novel.yonren.domain.novel.service.armory.memory.StoryMemoryService storyMemoryService;

    @Resource
    private cn.novel.yonren.domain.novel.service.armory.quality.BatchHealthService batchHealthService;

    @Resource
    private cn.novel.yonren.domain.novel.service.armory.candidate.CandidateSampleService candidateSampleService;

    @Resource
    private CheckpointService checkpointService;

    @Resource
    private BookExportService bookExportService;

    @GetMapping
    public List<StorySummaryDTO> listStories() {
        try {
            List<IStoryRepository.StorySummary> summaries = storyRepository.listStories();
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
        } catch (Exception e) {
            log.error("故事列表查询失败", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "故事列表查询失败：" + e.getMessage());
        }
    }

    /** 续写时的默认批次大小："这次写几章"与历史无关，给个保守默认由用户自己改 */
    private static final int DEFAULT_RESUME_BATCH_SIZE = 5;

    /**
     * 续写准备：把已有故事的整套设定读回来（数据源是 story-bible.txt），并附上"已经写到哪儿了"。
     *
     * <p>为什么必须由服务端给：续写要走和首发完全相同的入参校验
     * （{@code ValidateUserInputNode} 对 11 个字段全非空），而设定字段在服务端只存在于 bible 里——
     * 这是它们唯一被持久化的地方（story-meta 只存总章数）。
     *
     * <p>取不到的字段通过 {@code missingFields} 明确点名：那条校验是一条 or 链、不定位具体字段，
     * 让用户自己猜"到底缺哪个"是折磨。
     */
    @GetMapping("/{storyDirName}/resume")
    public StoryResumeDTO resume(@PathVariable("storyDirName") String storyDirName) {
        Path storyDir = resolveStoryDir(storyDirName);
        try {
            StoryBibleParser.Snapshot bible = StoryBibleParser.parse(storyRepository.readStoryBible(storyDir));

            List<Integer> numbers = storyRepository.readChapterNumbers(storyDir);
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
            setting.setCheatMechanismName(bible.cheatMechanismName());
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
        } catch (Exception e) {
            log.error("续写信息读取失败：{}", storyDirName, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "续写信息读取失败：" + e.getMessage());
        }
    }

    /**
     * 总章数上限是 sticky 固化值；读不到就留空（等于不设上限）。
     * 不要在这里补一个默认值——那等于用一个凭空数字覆盖历史约定
     */
    private Integer readMaxChapterCount(Path storyDir, String storyDirName) {
        try {
            IStoryRepository.StoryMeta meta = storyRepository.readStoryMeta(storyDir);
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

    @GetMapping("/{storyDirName}/chapters")
    public List<ChapterMetaDTO> listChapters(@PathVariable("storyDirName") String storyDirName) {
        Path storyDir = resolveStoryDir(storyDirName);
        try {
            List<Integer> numbers = storyRepository.readChapterNumbers(storyDir);
            List<ChapterMetaDTO> result = new ArrayList<>(numbers.size());
            for (int no : numbers) {
                ChapterContentEntity entity = storyRepository.readChapter(storyDir, no);
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
        } catch (Exception e) {
            log.error("章节列表查询失败：{}", storyDirName, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "章节列表查询失败：" + e.getMessage());
        }
    }

    /**
     * 工作台概览：仅聚合已有章节、摘要和质量文件，供本地写作界面一次加载。
     * 不另存账本，三类账本仍由摘要确定性重建，确保展示和生成上下文一致。
     */
    @GetMapping("/{storyDirName}/workbench")
    public StoryWorkbenchDTO workbench(@PathVariable("storyDirName") String storyDirName) {
        Path storyDir = resolveStoryDir(storyDirName);
        try {
            List<Integer> chapterNumbers = storyRepository.readChapterNumbers(storyDir);
            List<ChapterSummaryEntity> summaries = storyRepository.readChapterSummaries(storyDir);
            List<QualityDebtEntity> debts = storyRepository.readQualityDebts(storyDir);
            List<StageBlueprintEntity> blueprints = storyRepository.readStageBlueprints(storyDir);

            String storyBible = storyRepository.readStoryBible(storyDir);
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
                ChapterContentEntity chapter = storyRepository.readChapter(storyDir, chapterNo);
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
        } catch (Exception e) {
            log.error("工作台概览查询失败：{}", storyDirName, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "工作台概览查询失败：" + e.getMessage());
        }
    }

    /** 列出故事全部检查点（按版本升序） */
    @GetMapping("/{storyDirName}/checkpoints")
    public List<CheckpointDTO> listCheckpoints(@PathVariable("storyDirName") String storyDirName) {
        Path storyDir = resolveStoryDir(storyDirName);
        try {
            List<CheckpointEntity> checkpoints = checkpointService.list(storyDir);
            List<CheckpointDTO> result = new ArrayList<>(checkpoints.size());
            for (CheckpointEntity cp : checkpoints) {
                result.add(toCheckpointDTO(cp));
            }
            return result;
        } catch (Exception e) {
            log.error("检查点列表查询失败：{}", storyDirName, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "检查点列表查询失败：" + e.getMessage());
        }
    }

    /** 手动命名快照；name 必填 */
    @PostMapping("/{storyDirName}/checkpoints")
    public CheckpointDTO createCheckpoint(@PathVariable("storyDirName") String storyDirName,
                                          @RequestBody CheckpointCreateRequestDTO request) {
        if (request == null || request.getName() == null || request.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "手动快照必须提供命名词（name）");
        }
        if (jobRegistry.isActiveForStory(storyDirName)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该故事有运行中的作业，请等待完成后再打检查点");
        }
        Path storyDir = resolveStoryDir(storyDirName);
        try {
            String id = checkpointService.manualSnapshot(storyDir, request.getName().trim());
            CheckpointEntity cp = storyRepository.readCheckpoint(storyDir, id);
            CheckpointDTO dto = toCheckpointDTO(cp);
            dto.setCurrent(false);
            return dto;
        } catch (Exception e) {
            log.error("手动快照失败：{}", storyDirName, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "手动快照失败：" + e.getMessage());
        }
    }

    /**
     * 回滚到指定检查点：章节/摘要/蓝图/账本原子还原 + 向量索引重建。
     * 返回还原后的检查点（current）——**必须带 JSON 体**：前端 fetchJson 统一按 Content-Type 判别响应，
     * void + 空体会被误报为「服务暂不可用（200）」（2026-10-04 实测：回滚成功但前端报失败）。
     */
    @PostMapping("/{storyDirName}/checkpoints/{checkpointId}/restore")
    public CheckpointDTO restoreCheckpoint(@PathVariable("storyDirName") String storyDirName,
                                          @PathVariable("checkpointId") String checkpointId) {
        if (jobRegistry.isActiveForStory(storyDirName)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该故事有运行中的作业，请等待完成后再回滚");
        }
        Path storyDir = resolveStoryDir(storyDirName);
        try {
            checkpointService.restore(storyDir, checkpointId);
            CheckpointDTO dto = toCheckpointDTO(storyRepository.readCheckpoint(storyDir, checkpointId));
            if (dto == null) {
                dto = new CheckpointDTO();
                dto.setCheckpointId(checkpointId);
            }
            dto.setCurrent(true);
            return dto;
        } catch (Exception e) {
            log.error("回滚失败：{} 检查点 {}", storyDirName, checkpointId, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "回滚失败：" + e.getMessage());
        }
    }

    @GetMapping("/{storyDirName}/chapters/{chapterNo}")
    public ChapterContentDTO readChapter(@PathVariable("storyDirName") String storyDirName,
                                         @PathVariable("chapterNo") int chapterNo) {
        Path storyDir = resolveStoryDir(storyDirName);
        try {
            ChapterContentEntity entity = storyRepository.readChapter(storyDir, chapterNo);
            if (entity == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "章节不存在：" + storyDirName + " 第 " + chapterNo + " 章");
            }
            ChapterContentDTO dto = new ChapterContentDTO();
            dto.setChapterNo(entity.getChapterNo());
            dto.setTitle(entity.getTitle());
            dto.setContent(entity.getContent());
            return dto;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("章节读取失败：{} 第{}章", storyDirName, chapterNo, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "章节读取失败：" + e.getMessage());
        }
    }

    @PutMapping("/{storyDirName}/chapters/{chapterNo}")
    public ChapterSaveResponseDTO saveChapter(@PathVariable("storyDirName") String storyDirName,
                                              @PathVariable("chapterNo") int chapterNo,
                                              @RequestBody ChapterSaveRequestDTO request) {
        if (request.getContent() == null || request.getContent().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "正文不能为空");
        }
        if (jobRegistry.isActiveForStory(storyDirName)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该故事有运行中的作业，请等待完成后再编辑");
        }
        // 人工保存只要求非空：1500 字是生成路径的质量门禁，不该驳回用户的手改
        // （实测段落级润色后保存被这条 400 挡掉，整章不足 1500 字就改不了）
        if (!ChapterLengthPolicy.meetsEditMinimum(request.getContent())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "正文不能为空");
        }
        Path storyDir = resolveStoryDir(storyDirName);
        try {
            ChapterContentEntity existing = storyRepository.readChapter(storyDir, chapterNo);
            ChapterContentEntity entity = new ChapterContentEntity();
            entity.setChapterNo(chapterNo);
            entity.setTitle(existing == null || existing.getTitle() == null ? "" : existing.getTitle());
            entity.setContent(request.getContent());
            storyRepository.writeChapters(storyDir, List.of(entity));
        } catch (Exception e) {
            log.error("正文保存失败：{} 第{}章", storyDirName, chapterNo, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "正文保存失败：" + e.getMessage());
        }

        // 记忆回流（重跑摘要 + 重建向量索引）是整章级的 LLM 调用，实测要几分钟。
        // 段落级润色/扩写不改情节事实，重算摘要是纯浪费——前端传 reflow=false 跳过；
        // 续写引入了新内容，必须回流，否则账本与正文脱节。
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
     * <p><b>为什么不落盘</b>：返回的是"建议片段"，前端做对照让用户确认后再走既有的
     * {@code PUT /chapters/{no}} 保存——那条路径已含摘要回流与运行中作业互斥检查，
     * 这里另开写盘通道会让两套一致性机制分叉。
     *
     * <p><b>为什么校验 selectedText 是否在正文里</b>：接口在本机无鉴权，不校验会让它
     * 退化成"任意文本改写代理"，消耗额度却不产生写作价值。
     */
    @PostMapping("/{storyDirName}/chapters/{chapterNo}/segment-edit")
    public cn.novel.yonren.api.dto.SegmentEditResponseDTO segmentEdit(
            @PathVariable("storyDirName") String storyDirName,
            @PathVariable("chapterNo") int chapterNo,
            @RequestBody cn.novel.yonren.api.dto.SegmentEditRequestDTO request) {
        Path storyDir = resolveStoryDir(storyDirName);
        ChapterContentEntity chapter;
        try {
            chapter = storyRepository.readChapter(storyDir, chapterNo);
        } catch (Exception e) {
            log.error("段落编辑读取章节失败：{} 第{}章", storyDirName, chapterNo, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "章节读取失败：" + e.getMessage());
        }
        if (chapter == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "章节不存在：" + storyDirName + " 第 " + chapterNo + " 章");
        }
        if (request.getSelectedText() == null || request.getSelectedText().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "选中文本为空");
        }
        if (chapter.getContent() == null || !chapter.getContent().contains(request.getSelectedText())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "选中文本不在该章正文中（可能章节已被修改，请刷新后重试）");
        }

        PromptContext ctx = buildSegmentPromptContext(storyDir, chapterNo);
        try {
            return segmentEditService.edit(storyProperties.toStoryVO(), chapterNo, request, ctx);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("段落编辑失败：{} 第{}章", storyDirName, chapterNo, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "段落编辑失败：" + e.getMessage());
        }
    }

    /** 段落编辑的 prompt 上下文：只取题材/风格，不读账本与伏笔（轻量诉求不该付整章的上下文成本） */
    private PromptContext buildSegmentPromptContext(Path storyDir, int chapterNo) {
        PromptContext ctx = PromptContext.builder().chapterNo(chapterNo).build();
        try {
            StoryBibleParser.Snapshot bible = StoryBibleParser.parse(storyRepository.readStoryBible(storyDir));
            ctx.setTheme(bible.theme());
            ctx.setStyle(bible.style());
        } catch (Exception e) {
            // 题材/风格只影响资料检索的选材，缺失时降级为不检索而不是失败
            log.warn("段落编辑读取 story-bible 失败，题材/风格留空：{}", e.getMessage());
        }
        return ctx;
    }

    /**
     * 批末体检（只读、零 LLM 成本）：把阶段一~四建立的机械指标横着看一遍，输出可分级、可行动的
     * 健康清单（账本完整度 / 每章新地点率 / 过渡章占比 / 低密度章占比 / 未核销债 /
     * 出口条件达成率 / 放宽档入账占比）。
     *
     * <p>与批末日志是同一份结论，供人工按需复算——调整阈值后无需重跑生成即可立即复核。
     * 直接返回领域记录：它是纯值对象（无行为、无仓储依赖），再加一层 DTO 只是搬运字段。
     */
    @GetMapping("/{storyDirName}/health")
    public cn.novel.yonren.domain.novel.service.armory.quality.BatchHealthReport health(
            @PathVariable("storyDirName") String storyDirName) {
        Path storyDir = resolveStoryDir(storyDirName);
        try {
            return batchHealthService.assess(
                    storyRepository.readChapterSummaries(storyDir),
                    storyRepository.readQualityDebts(storyDir),
                    storyRepository.readStageBlueprints(storyDir),
                    candidateSampleService.readStats(storyDir));
        } catch (Exception e) {
            log.error("批末体检失败：{}", storyDirName, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "批末体检失败：" + e.getMessage());
        }
    }

    /**
     * 导出成书：分章 ZIP 下载——每章一个独立文件（TXT / Markdown / Word），解压即「书名文件夹 +
     * 分章文件」。只读，不加「作业运行中」守卫——章节文件是原子写（tmp+move），
     * 导出的是发起时刻的快照；响应头用 ContentDisposition 的 UTF-8 filename*（RFC 5987），中文书名不乱码。
     */
    @GetMapping("/{storyDirName}/export")
    public ResponseEntity<byte[]> exportBook(@PathVariable("storyDirName") String storyDirName,
                                             @RequestParam(name = "format", defaultValue = "txt") String format) {
        BookFormat bookFormat = BookFormat.fromParam(format);
        if (bookFormat == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "不支持的导出格式：" + format + "（可选 txt / md / docx）");
        }
        Path storyDir = resolveStoryDir(storyDirName);
        try {
            BookExport book = bookExportService.exportChapters(storyDir, bookFormat);
            if (book == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该故事还没有可导出的章节");
            }
            ContentDisposition disposition = ContentDisposition.attachment()
                    .filename(book.getFileName(), StandardCharsets.UTF_8)
                    .build();
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .body(book.getFileBytes());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("导出成书失败：{}", storyDirName, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "导出成书失败：" + e.getMessage());
        }
    }

    /**
     * 待人工确认事实查询：摘要证据校验未通过的状态事实（疑似编造/漂移，不入账本），
     * 按章节聚合透出供人工裁决。数据源为 stories/<dir>/memory/summaries.json 的 pendingFacts
     */
    @GetMapping("/{storyDirName}/pending-facts")
    public List<PendingFactDTO> pendingFacts(@PathVariable("storyDirName") String storyDirName) {
        Path storyDir = resolveStoryDir(storyDirName);
        try {
            List<ChapterSummaryEntity> summaries = storyRepository.readChapterSummaries(storyDir);
            List<PendingFactDTO> result = new ArrayList<>();
            for (ChapterSummaryEntity summary : summaries) {
                collectPendingFacts(result, summary);
            }
            return result;
        } catch (Exception e) {
            log.error("待确认事实查询失败：{}", storyDirName, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "待确认事实查询失败：" + e.getMessage());
        }
    }

    /**
     * 实体事实时间线追溯：按实体名检索其在全书各章的状态轨迹（章节 + 账本类型 + 状态 + 证据原文）。
     * 排障用途——模型产出错误状态时，一次调用定位"该状态出自第几章、哪段正文"。
     * 精确匹配无结果时回退包含匹配；证据引用为摘要侧机械校验通过的原句片段
     */
    @GetMapping("/{storyDirName}/facts/{name}")
    public List<FactTimelineDTO> factTimeline(@PathVariable("storyDirName") String storyDirName,
                                              @PathVariable("name") String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "实体名不能为空");
        }
        Path storyDir = resolveStoryDir(storyDirName);
        try {
            List<ChapterSummaryEntity> summaries = storyRepository.readChapterSummaries(storyDir);
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
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("事实时间线查询失败：{} 实体 {}", storyDirName, name, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "事实时间线查询失败：" + e.getMessage());
        }
    }

    /** 删除故事的结果：必须带 JSON 体（前端 fetchJson 按 Content-Type 判别，void 空体会被误报为服务不可用） */
    public record DeleteStoryResult(boolean deleted, String storyDirName) {
    }

    /**
     * **删除故事**（故事库的删除操作，不可恢复）：删故事目录 + 清该故事独占的记忆向量集合。
     *
     * <p><b>为什么先守卫作业</b>：运行中/审批挂起的作业持有该故事的路径与上下文，
     * 删目录后它会继续往不存在的目录写（甚至重建出半个目录）。有活动作业时直接 409，
     * 让用户先等完成或取消——这与回滚端点的守卫同款。
     *
     * <p>删除顺序：**先删目录、再清集合**。目录是用户可见的主体，先做；
     * 集合清理是 fail-soft（{@code deleteStoryMemory} 内部只 WARN），失败不影响删除结果。
     *
     * <p>共享的世界观集合 {@code novel-world-<worldId>} **不动**——同世界观下可能有其它故事在引用。
     */
    @PostMapping("/{storyDirName}/delete")
    public DeleteStoryResult deleteStory(@PathVariable("storyDirName") String storyDirName) {
        if (jobRegistry.isActiveForStory(storyDirName)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该故事有运行中的作业，请等待完成或取消后再删除");
        }
        Path storyDir = resolveStoryDir(storyDirName);
        try {
            storyRepository.deleteStory(storyDir);
            storyMemoryService.deleteStoryMemory(storyDir);
            log.info("故事已删除：{}", storyDirName);
            return new DeleteStoryResult(true, storyDirName);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("删除故事失败：{}", storyDirName, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "删除故事失败：" + e.getMessage());
        }
    }

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

    private Path resolveStoryDir(String storyDirName) {
        if (storyDirName == null || storyDirName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "故事目录名不能为空");
        }
        try {
            return storyRepository.resolveStoryDirectory(storyDirName);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "故事目录不存在或格式非法：" + storyDirName + "（" + e.getMessage() + "）");
        }
    }
}
