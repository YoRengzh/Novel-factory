package cn.novel.yonren.infrastructure.adapter.repository;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.ConsistencyIndexEntity;
import cn.novel.yonren.domain.novel.model.entity.CheckpointEntity;
import cn.novel.yonren.types.enums.CheckpointType;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowScheduleEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowSettlementEntity;
import cn.novel.yonren.domain.novel.model.entity.StyleStatEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.QualityDebtEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.entity.VolumeBlueprintEntity;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.domain.novel.service.job.GenerationJob;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import cn.novel.yonren.types.utils.StoryBibleParser;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;


/**
 * 故事持久化仓储实现。
 * 落盘约定：所有写操作先写同目录临时文件再原子替换（崩溃不会留下半截文件）；
 * 同一故事目录的写操作由每故事锁串行化；记忆类 JSON 读取损坏时硬失败
 */
@Repository
@Slf4j
public class StoryRepository implements IStoryRepository {

    // 故事目录名契约：yyyyMMdd-story-XXXX（createStoryDirectory 生成、续写请求回传）
    private static final Pattern STORY_DIR_PATTERN = Pattern.compile("\\d{8}-story-\\d{4}");
    private static final Pattern CHAPTER_HEADER_PATTERN = Pattern.compile("^第(\\d+)章\\s?(.*)$");

    /** 序号竞争重试上限：连续被抢占 50 次视为异常（单机单用户场景几乎不可能触发） */
    private static final int MAX_SEQUENCE_ATTEMPTS = 50;

    /** 每故事写锁：并发批次写同一故事目录时串行化落盘，配合原子替换保证文件永远是完整快照 */
    private final ConcurrentHashMap<String, ReentrantLock> storyLocks = new ConcurrentHashMap<>();

    /** 检查点环形保留上限：仅保留最近 N 个，更旧的自动回收 */
    private static final int CHECKPOINT_RETENTION = 8;

    @Override
    public Path createStoryDirectory() throws IOException {
        return createStoryDirectory(Paths.get(StorageKeys.WORKSPACE_DIR));
    }

    /**
     * 包级可见供单测注入临时 workspace。序号分配用 CREATE_NEW 语义（Files.createDirectory）：
     * 并发下仅一方创建成功，失败方重扫重试，杜绝两个批次共用同一故事目录
     */
    static Path createStoryDirectory(Path workspace) throws IOException {
        String date = LocalDate.now().format(StorageKeys.DATE_FORMATTER);
        Files.createDirectories(workspace);

        for (int attempt = 0; ; attempt++) {
            Path storyDir = workspace.resolve(String.format("%s-story-%04d", date, nextSequence(workspace, date)));
            try {
                Files.createDirectory(storyDir);
            } catch (FileAlreadyExistsException e) {
                if (attempt >= MAX_SEQUENCE_ATTEMPTS) {
                    throw new IOException("创建故事目录失败：序号连续被并发抢占 " + MAX_SEQUENCE_ATTEMPTS + " 次", e);
                }
                continue;
            }
            Files.createDirectories(storyDir.resolve(StorageKeys.CHAPTER_DIR));
            Files.createDirectories(storyDir.resolve(StorageKeys.RECORD));
            return storyDir;
        }
    }

    private static int nextSequence(Path workspace, String date) throws IOException {
        String prefix = date + "-story-";
        int maxSeq;
        try (Stream<Path> stream = Files.list(workspace)) {
            maxSeq = stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(prefix))
                    .mapToInt(name -> {
                        try {
                            return Integer.parseInt(name.substring(prefix.length()));
                        } catch (NumberFormatException e) {
                            return 0;
                        }
                    })
                    .max()
                    .orElse(0);
        }
        return maxSeq + 1;
    }

    @Override
    public void writeStoryBible(Path storyDir, ArmoryCommandEntity command) throws IOException {
        Path file = storyDir.resolve(StorageKeys.STORY_BIBLE_FILE);
        withStoryLock(storyDir, () -> {
            if (Files.exists(file)) {
                log.info("story-bible.txt 已存在，续写不覆盖基础信息");
                return;
            }
            writeStringAtomic(file, buildBibleContent(command));
        });
    }

    @Override
    public String readStoryBible(Path storyDir) throws IOException {
        Path file = storyDir.resolve(StorageKeys.STORY_BIBLE_FILE);
        return Files.exists(file) ? Files.readString(file) : "";
    }

    private String buildBibleContent(ArmoryCommandEntity command) {
        StringBuilder sb = new StringBuilder();
        StoryContextEntity ctx = command.getStoryContextEntity();
        if (ctx != null) {
            appendBibleLine(sb, StoryBibleParser.LABEL_NOVEL_TITLE, ctx.getNovel_title());
            appendBibleLine(sb, StoryBibleParser.LABEL_THEME, ctx.getTheme());
            appendBibleLine(sb, StoryBibleParser.LABEL_STYLE, ctx.getStyle());
            appendBibleLine(sb, StoryBibleParser.LABEL_WORLD_SETTING, ctx.getWorldSetting());
            appendBibleLine(sb, StoryBibleParser.LABEL_PERSPECTIVE, ctx.getPerspective());
            appendBibleLine(sb, StoryBibleParser.LABEL_TARGET_AUDIENCE, ctx.getTargetAudience());
            appendBibleLine(sb, StoryBibleParser.LABEL_TONE, ctx.getTone());
            appendBibleLine(sb, StoryBibleParser.LABEL_PROTAGONIST, ctx.getProtagonist());
            // 人物档案紧跟主人公：两者同属"跨章不变的设定"。值为空时 appendBibleLine 跳过，
            // 老故事（无该字段）的 bible 格式不变
            appendBibleLine(sb, StoryBibleParser.LABEL_CHARACTERS, ctx.getCharacterProfiles());
            appendBibleLine(sb, StoryBibleParser.LABEL_OUTLINE, ctx.getOutline());
            appendBibleLine(sb, StoryBibleParser.LABEL_CHAPTER_COUNT, ctx.getChapterCount());
            appendBibleLine(sb, StoryBibleParser.LABEL_CHAPTER_GOAL, ctx.getChapterGoal());
            if (ctx.getWorldId() != null && !ctx.getWorldId().isBlank()) {
                appendBibleLine(sb, StoryBibleParser.LABEL_WORLD_ID, ctx.getWorldId());
            }
        }

        StoryVO storyVO = command.getStoryVO();
        if (storyVO != null && storyVO.getDefaults() != null) {
            StoryVO.Defaults defaults = storyVO.getDefaults();
            appendBibleLine(sb, StoryBibleParser.LABEL_LANGUAGE, defaults.getLanguage());
            appendBibleLine(sb, StoryBibleParser.LABEL_TOTAL_COUNT, defaults.getTotalCount());
        }
        if (storyVO != null && storyVO.getFeatures() != null
                && storyVO.getFeatures().getHasCheatMechanism() != null) {
            StoryVO.StoryFeatures features = storyVO.getFeatures();
            appendBibleLine(sb, StoryBibleParser.LABEL_HAS_CHEAT,
                    Boolean.TRUE.equals(features.getHasCheatMechanism()));
            if (features.getCheatMechanismName() != null && !features.getCheatMechanismName().isBlank()) {
                appendBibleLine(sb, StoryBibleParser.LABEL_CHEAT_NAME, features.getCheatMechanismName());
            }
            appendBibleLine(sb, StoryBibleParser.LABEL_CHEAT_INTERVAL,
                    features.getCheatUsageInterval() == null ? 3 : features.getCheatUsageInterval());
        }
        return sb.toString();
    }

    /**
     * 写一行「标签: 值」。标签统一取自 {@link StoryBibleParser} 的常量——写入与读取共用一份，
     * 避免"改了写入、忘了读取"造成的静默失配（值读不出来不会报错，只会在续写时表现为设定莫名丢失）
     */
    private static void appendBibleLine(StringBuilder sb, String label, Object value) {
        sb.append(label).append(": ").append(value).append(System.lineSeparator());
    }

    @Override
    public Path createRunDirectory(Path storyDir) throws IOException {
        return createRunDirectory(storyDir, null);
    }

    @Override
    public Path createRunDirectory(Path storyDir, String jobId) throws IOException {
        Path recordRoot = storyDir.resolve(StorageKeys.RECORD);
        Files.createDirectories(recordRoot);
        if (jobId != null && !jobId.isEmpty()) {
            // runId 与 jobId 同源：冲突即异常不重试（jobId 全局唯一，冲突说明状态已异常）
            return Files.createDirectory(recordRoot.resolve(StorageKeys.JOB_RUN_PREFIX + jobId));
        }
        for (int attempt = 0; ; attempt++) {
            Path runDir = recordRoot.resolve(String.format("run-%04d", nextRunNumber(recordRoot)));
            try {
                Files.createDirectory(runDir);
                return runDir;
            } catch (FileAlreadyExistsException e) {
                if (attempt >= MAX_SEQUENCE_ATTEMPTS) {
                    throw new IOException("创建 run 目录失败：序号连续被并发抢占 " + MAX_SEQUENCE_ATTEMPTS + " 次", e);
                }
            }
        }
    }

    private static int nextRunNumber(Path recordRoot) throws IOException {
        int max;
        try (Stream<Path> stream = Files.list(recordRoot)) {
            max = stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    // 只统计顺序编号的 run-XXXX，run-job-* 目录不参与 max+1
                    .filter(name -> name.matches("run-\\d{4}"))
                    .mapToInt(name -> Integer.parseInt(name.substring("run-".length())))
                    .max()
                    .orElse(0);
        }
        return max + 1;
    }

    /**
     * 写章节计划到 runDir/chapter-plan.yml（供人工审阅/裁决）。
     *
     * <p>⚠️ 这里是**逐字段手写**的 YAML，不是对象序列化——**新增计划字段时必须同步加进来**，
     * 否则该字段在留档里静默消失（实测踩过：`suspenseBeat` 有值、闸门也校验了，
     * 但留档文件里查不到，一度被误判成"模型没回填"）。
     */
    @Override
    public void writeChapterPlan(Path runDir, ChapterPlanAggregate chapterPlan) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("storyId: ").append(quote(chapterPlan.getStoryId())).append(System.lineSeparator());
        sb.append("chapters:").append(System.lineSeparator());

        for (ChapterPlanItemEntity item : chapterPlan.getChapters()) {
            sb.append("  - chapterNo: ").append(item.getChapterNo()).append(System.lineSeparator());
            sb.append("    title: ").append(quote(item.getTitle())).append(System.lineSeparator());
            sb.append("    goal: ").append(quote(item.getGoal())).append(System.lineSeparator());
            writeStringList(sb, "characters", item.getCharacters());
            writeStringList(sb, "keyEvents", item.getKeyEvents());
            // 对话承载声明（逐字取自 keyEvents 的子集）：人工裁决时核对"本章有没有戏"就看这几条
            writeStringList(sb, "dialogueScenes", item.getDialogueScenes());
            sb.append("    endingHook: ").append(quote(item.getEndingHook())).append(System.lineSeparator());
            sb.append("    chapterType: ").append(item.getChapterType() == null ? "normal" : item.getChapterType().getCode()).append(System.lineSeparator());
            // 悬念档位（主线推进标尺）：人工裁决时最该看的一项——它直接反映主线有没有在往前走
            sb.append("    suspenseBeat: ").append(quote(item.getSuspenseBeat())).append(System.lineSeparator());
        }

        withStoryLock(runDir, () -> writeStringAtomic(runDir.resolve(StorageKeys.CHAPTER_PLAN_FILE), sb.toString()));
    }

    private void writeStringList(StringBuilder sb, String name, List<String> values) {
        sb.append("    ").append(name).append(":").append(System.lineSeparator());
        if (values == null || values.isEmpty()) {
            sb.setLength(sb.length() - System.lineSeparator().length());
            sb.append(" []").append(System.lineSeparator());
            return;
        }
        for (String value : values) {
            sb.append("      - ").append(quote(value)).append(System.lineSeparator());
        }
    }

    @Override
    public void writeChapters(Path storyDir, List<ChapterContentEntity> chapterContents) throws IOException {
        if (chapterContents == null) return;
        Path chapterDir = storyDir.resolve(StorageKeys.CHAPTER_DIR);
        withStoryLock(storyDir, () -> {
            for (ChapterContentEntity chapter : chapterContents) {
                StringBuilder sb = new StringBuilder();
                sb.append("第").append(chapter.getChapterNo()).append("章 ").append(chapter.getTitle()).append(System.lineSeparator());
                sb.append(System.lineSeparator());
                sb.append(chapter.getContent() == null ? "" : chapter.getContent().replace("\\n", "\n"));
                String fileName = String.format("%s%04d%s", StorageKeys.CHAPTER_FILE_PREFIX, chapter.getChapterNo(), StorageKeys.CHAPTER_FILE_SUFFIX);
                writeStringAtomic(chapterDir.resolve(fileName), sb.toString());
            }
        });
    }

    @Override
    public void writeChapterSummaries(Path storyDir, List<ChapterSummaryEntity> summaries) throws IOException {
        if (summaries == null || summaries.isEmpty()) return;
        Path file = storyDir.resolve(StorageKeys.MEMORY_DIR).resolve(StorageKeys.SUMMARIES_FILE);
        withStoryLock(storyDir, () -> writeStringAtomic(file, JSON.toJSONString(summaries)));
    }

    @Override
    public List<ChapterSummaryEntity> readChapterSummaries(Path storyDir) {
        return readJsonArrayIfExists(storyDir.resolve(StorageKeys.MEMORY_DIR).resolve(StorageKeys.SUMMARIES_FILE),
                ChapterSummaryEntity.class, "章节摘要记忆");
    }

    @Override
    public void writeConsistencyIndex(Path storyDir, ConsistencyIndexEntity index) throws IOException {
        if (index == null) return;
        Path file = storyDir.resolve(StorageKeys.MEMORY_DIR).resolve(StorageKeys.CONSISTENCY_INDEX_FILE);
        withStoryLock(storyDir, () -> writeStringAtomic(file, JSON.toJSONString(index)));
    }

    @Override
    public ConsistencyIndexEntity readConsistencyIndex(Path storyDir) {
        Path file = storyDir.resolve(StorageKeys.MEMORY_DIR).resolve(StorageKeys.CONSISTENCY_INDEX_FILE);
        if (!Files.exists(file)) return ConsistencyIndexEntity.builder().build();
        try {
            return JSON.parseObject(Files.readString(file, StandardCharsets.UTF_8), ConsistencyIndexEntity.class);
        } catch (Exception e) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), "一致性索引读取失败：" + e.getMessage());
        }
    }

    @Override
    public void writeQualityDebts(Path storyDir, List<QualityDebtEntity> debts) throws IOException {
        if (debts == null || debts.isEmpty()) return;
        Path file = storyDir.resolve(StorageKeys.MEMORY_DIR).resolve(StorageKeys.QUALITY_DEBT_FILE);
        withStoryLock(storyDir, () -> writeStringAtomic(file, JSON.toJSONString(debts)));
    }

    @Override
    public List<QualityDebtEntity> readQualityDebts(Path storyDir) {
        return readJsonArrayIfExists(storyDir.resolve(StorageKeys.MEMORY_DIR).resolve(StorageKeys.QUALITY_DEBT_FILE),
                QualityDebtEntity.class, "质量债清单");
    }

    @Override
    public void writeForeshadowSettlements(Path storyDir, List<ForeshadowSettlementEntity> settlements) throws IOException {
        if (settlements == null || settlements.isEmpty()) return;
        Path file = storyDir.resolve(StorageKeys.MEMORY_DIR).resolve(StorageKeys.FORESHADOW_SETTLEMENT_FILE);
        withStoryLock(storyDir, () -> writeStringAtomic(file, JSON.toJSONString(settlements)));
    }

    @Override
    public List<ForeshadowSettlementEntity> readForeshadowSettlements(Path storyDir) {
        return readJsonArrayIfExists(storyDir.resolve(StorageKeys.MEMORY_DIR).resolve(StorageKeys.FORESHADOW_SETTLEMENT_FILE),
                ForeshadowSettlementEntity.class, "伏笔清账结算");
    }

    @Override
    public void writeForeshadowSchedule(Path storyDir, List<ForeshadowScheduleEntity> schedules) throws IOException {
        if (schedules == null || schedules.isEmpty()) return;
        Path file = storyDir.resolve(StorageKeys.MEMORY_DIR).resolve(StorageKeys.FORESHADOW_SCHEDULE_FILE);
        withStoryLock(storyDir, () -> writeStringAtomic(file, JSON.toJSONString(schedules)));
    }

    @Override
    public List<ForeshadowScheduleEntity> readForeshadowSchedule(Path storyDir) {
        return readJsonArrayIfExists(storyDir.resolve(StorageKeys.MEMORY_DIR).resolve(StorageKeys.FORESHADOW_SCHEDULE_FILE),
                ForeshadowScheduleEntity.class, "伏笔兑现排期");
    }

    @Override
    public void writeStyleStat(Path storyDir, StyleStatEntity styleStat) throws IOException {
        if (styleStat == null) return;
        Path file = storyDir.resolve(StorageKeys.MEMORY_DIR).resolve(StorageKeys.STYLE_STAT_FILE);
        withStoryLock(storyDir, () -> writeStringAtomic(file, JSON.toJSONString(styleStat)));
    }

    @Override
    public StyleStatEntity readStyleStat(Path storyDir) {
        Path file = storyDir.resolve(StorageKeys.MEMORY_DIR).resolve(StorageKeys.STYLE_STAT_FILE);
        if (!Files.exists(file)) {
            return null;
        }
        String json = readFileOrThrow(file, "风格统计");
        StyleStatEntity stat;
        try {
            stat = JSON.parseObject(json, StyleStatEntity.class);
        } catch (RuntimeException e) {
            throw corruptMemory(file, "风格统计", e);
        }
        if (stat == null) {
            throw corruptMemory(file, "风格统计", null);
        }
        return stat;
    }

    @Override
    public void writeStageBlueprints(Path storyDir, List<StageBlueprintEntity> blueprints) throws IOException {
        if (blueprints == null || blueprints.isEmpty()) return;
        Path file = storyDir.resolve(StorageKeys.MEMORY_DIR).resolve(StorageKeys.ROLLING_OUTLINE_FILE);
        withStoryLock(storyDir, () -> writeStringAtomic(file, JSON.toJSONString(blueprints)));
    }

    @Override
    public void appendAuditSample(Path storyDir, String jsonlLine) throws IOException {
        Path memoryDir = storyDir.resolve(StorageKeys.MEMORY_DIR);
        Path file = memoryDir.resolve(StorageKeys.AUDIT_SAMPLES_FILE);
        withStoryLock(storyDir, () -> {
            Files.createDirectories(memoryDir);
            // 观测文件走追加语义：单行 JSON，崩溃最多丢半行，无需原子替换
            Files.writeString(file, jsonlLine + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        });
    }

    @Override
    public void appendCandidateSample(Path storyDir, String jsonlLine) throws IOException {
        Path memoryDir = storyDir.resolve(StorageKeys.MEMORY_DIR);
        Path file = memoryDir.resolve(StorageKeys.CANDIDATE_SAMPLES_FILE);
        withStoryLock(storyDir, () -> {
            Files.createDirectories(memoryDir);
            // 观测文件走追加语义：单行 JSON，崩溃最多丢半行，无需原子替换
            Files.writeString(file, jsonlLine + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        });
    }

    @Override
    public void appendQualityTrend(Path storyDir, String jsonlLine) throws IOException {
        Path memoryDir = storyDir.resolve(StorageKeys.MEMORY_DIR);
        Path file = memoryDir.resolve(StorageKeys.QUALITY_TREND_FILE);
        withStoryLock(storyDir, () -> {
            Files.createDirectories(memoryDir);
            // 与候选样本同语义：观测文件、追加写，崩溃最多丢半行
            Files.writeString(file, jsonlLine + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        });
    }

    @Override
    public List<String> readCandidateOutcomes(Path storyDir) {
        Path file = storyDir.resolve(StorageKeys.MEMORY_DIR).resolve(StorageKeys.CANDIDATE_SAMPLES_FILE);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<String> outcomes = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) continue;
                try {
                    String outcome = JSON.parseObject(line).getString("outcome");
                    if (outcome != null) outcomes.add(outcome);
                } catch (RuntimeException ignored) {
                    // 允许尾部半行/旧格式，不影响其余统计
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        return outcomes;
    }

    @Override
    public List<String> readCandidateSampleLines(Path storyDir) {
        Path file = storyDir.resolve(StorageKeys.MEMORY_DIR).resolve(StorageKeys.CANDIDATE_SAMPLES_FILE);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            List<String> lines = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) continue;
                lines.add(line);
            }
            return lines;
        } catch (IOException e) {
            return List.of();
        }
    }

    @Override
    public void writeStyleFingerprints(Path storyDir, List<String> sentences) throws IOException {
        if (sentences == null || sentences.isEmpty()) return;
        Path file = storyDir.resolve(StorageKeys.MEMORY_DIR).resolve(StorageKeys.STYLE_FINGERPRINT_FILE);
        withStoryLock(storyDir, () -> writeStringAtomic(file, JSON.toJSONString(sentences)));
    }

    @Override
    public List<String> readStyleFingerprints(Path storyDir) {
        Path file = storyDir.resolve(StorageKeys.MEMORY_DIR).resolve(StorageKeys.STYLE_FINGERPRINT_FILE);
        if (!Files.exists(file)) {
            return null;
        }
        String json = readFileOrThrow(file, "文风指纹库");
        try {
            return JSON.parseArray(json, String.class);
        } catch (RuntimeException e) {
            throw corruptMemory(file, "文风指纹库", e);
        }
    }

    @Override
    public List<StageBlueprintEntity> readStageBlueprints(Path storyDir) {
        return readJsonArrayIfExists(storyDir.resolve(StorageKeys.MEMORY_DIR).resolve(StorageKeys.ROLLING_OUTLINE_FILE),
                StageBlueprintEntity.class, "阶段蓝图");
    }

    @Override
    public void writeVolumes(Path storyDir, List<VolumeBlueprintEntity> volumes) throws IOException {
        if (volumes == null || volumes.isEmpty()) return;
        Path file = storyDir.resolve(StorageKeys.MEMORY_DIR).resolve(StorageKeys.VOLUME_FILE);
        withStoryLock(storyDir, () -> writeStringAtomic(file, JSON.toJSONString(volumes)));
    }

    @Override
    public List<VolumeBlueprintEntity> readVolumes(Path storyDir) {
        return readJsonArrayIfExists(storyDir.resolve(StorageKeys.MEMORY_DIR).resolve(StorageKeys.VOLUME_FILE),
                VolumeBlueprintEntity.class, "卷蓝图");
    }

    @Override
    public void writeStoryMeta(Path storyDir, IStoryRepository.StoryMeta meta) throws IOException {
        if (meta == null) {
            return;
        }
        Path file = storyDir.resolve(StorageKeys.STORY_META_FILE);
        withStoryLock(storyDir, () -> {
            // sticky cap：首次确立后永不覆盖（续写不可抬高完结上限）
            if (Files.exists(file)) {
                return;
            }
            writeStringAtomic(file, JSON.toJSONString(meta));
        });
    }

    @Override
    public IStoryRepository.StoryMeta readStoryMeta(Path storyDir) {
        Path file = storyDir.resolve(StorageKeys.STORY_META_FILE);
        if (!Files.exists(file)) {
            return null;
        }
        String json = readFileOrThrow(file, "完结上限元数据");
        IStoryRepository.StoryMeta meta;
        try {
            meta = JSON.parseObject(json, IStoryRepository.StoryMeta.class);
        } catch (RuntimeException e) {
            throw corruptMemory(file, "完结上限元数据", e);
        }
        if (meta == null) {
            throw corruptMemory(file, "完结上限元数据", null);
        }
        return meta;
    }

    @Override
    public void writeJobStatus(Path storyDir, GenerationJob job) throws IOException {
        Path file = storyDir.resolve(StorageKeys.JOB_STATUS_FILE);
        withStoryLock(storyDir, () -> writeStringAtomic(file, JSON.toJSONString(job)));
    }

    @Override
    public GenerationJob readJobStatus(Path storyDir) {
        Path file = storyDir.resolve(StorageKeys.JOB_STATUS_FILE);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return JSON.parseObject(readFileOrThrow(file, "作业状态"), GenerationJob.class);
        } catch (Exception e) {
            // 观测性文件而非记忆文件：损坏不阻断续写（记忆类文件的 D4 硬失败不适用）
            log.warn("job-status.json 读取失败，忽略（该文件仅供人工查看崩溃前进度）", e);
            return null;
        }
    }

    @Override
    public Path resolveStoryDirectory(String storyDirName) throws IOException {
        return resolveValidatedStoryDirectory(Paths.get(StorageKeys.WORKSPACE_DIR), storyDirName);
    }

    /**
     * 校验并解析续写目录（包级可见供单测注入临时 workspace）：
     * 1) 目录名仅接受 yyyyMMdd-story-XXXX 单层格式（免疫 ../ 与绝对路径）；
     * 2) 目录必须存在；
     * 3) 真实路径（解析符号链接）必须仍位于 workspace 根下且为其直接子目录。
     * 校验通过后返回该 Path 对象，仓储所有读写都使用它
     */
    static Path resolveValidatedStoryDirectory(Path workspace, String storyDirName) throws IOException {
        if (storyDirName == null || storyDirName.isBlank()) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), "续写失败：resumeStoryDir 为空");
        }
        if (!STORY_DIR_PATTERN.matcher(storyDirName).matches()) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(),
                    "续写失败：resumeStoryDir 格式非法（应为 yyyyMMdd-story-XXXX，如 20260902-story-0001）：" + storyDirName);
        }
        Path storyDir = workspace.resolve(storyDirName).normalize();
        if (!Files.isDirectory(storyDir)) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(),
                    "续写失败：故事目录不存在 " + storyDir.toAbsolutePath() + "，请检查 resumeStoryDir");
        }
        Path workspaceRoot = workspace.toRealPath();
        Path realStoryDir = storyDir.toRealPath();
        if (!realStoryDir.startsWith(workspaceRoot) || !realStoryDir.getParent().equals(workspaceRoot)) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(),
                    "续写失败：故事目录真实路径越出 workspace 边界（疑似符号链接）：" + storyDirName);
        }
        Files.createDirectories(storyDir.resolve(StorageKeys.MEMORY_DIR));
        return storyDir;
    }

    @Override
    public String readLatestChapterContent(Path storyDir) throws IOException {
        Path chapterDir = storyDir.resolve(StorageKeys.CHAPTER_DIR);
        if (!Files.isDirectory(chapterDir)) {
            return null;
        }
        Path latestFile = null;
        int latestNo = 0;
        try (Stream<Path> stream = Files.list(chapterDir)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (!name.startsWith(StorageKeys.CHAPTER_FILE_PREFIX) || !name.endsWith(StorageKeys.CHAPTER_FILE_SUFFIX)) {
                    continue;
                }
                String noPart = name.substring(StorageKeys.CHAPTER_FILE_PREFIX.length(),
                        name.length() - StorageKeys.CHAPTER_FILE_SUFFIX.length());
                try {
                    int no = Integer.parseInt(noPart);
                    if (no > latestNo) {
                        latestNo = no;
                        latestFile = file;
                    }
                } catch (NumberFormatException ignored) {

                }
            }
        }
        return latestFile == null ? null : Files.readString(latestFile);
    }

    @Override
    public List<Integer> readChapterNumbers(Path storyDir) throws IOException {
        Path chapterDir = storyDir.resolve(StorageKeys.CHAPTER_DIR);
        if (!Files.isDirectory(chapterDir)) {
            return List.of();
        }
        List<Integer> numbers = new ArrayList<>();
        try (Stream<Path> stream = Files.list(chapterDir)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (!name.startsWith(StorageKeys.CHAPTER_FILE_PREFIX) || !name.endsWith(StorageKeys.CHAPTER_FILE_SUFFIX)) {
                    continue;
                }
                try {
                    numbers.add(Integer.parseInt(name.substring(StorageKeys.CHAPTER_FILE_PREFIX.length(),
                            name.length() - StorageKeys.CHAPTER_FILE_SUFFIX.length())));
                } catch (NumberFormatException ignored) {

                }
            }
        }
        numbers.sort(Integer::compareTo);
        return numbers;
    }

    @Override
    public void writeGenerationRecord(Path runDir, ArmoryCommandEntity command, DefaultArmoryFactory.DynamicContext dynamicContext) throws IOException {
        // input.json：整体原始数据（输入 + 模型原始返回），供复盘
        JSONObject input = new JSONObject();
        input.put("storyContextEntity", command.getStoryContextEntity());
        input.put("storyVO", command.getStoryVO());
        input.put("prompt", dynamicContext.getPrompt());
        input.put("rawResult", dynamicContext.getRawResult());

        // output-XXXX.json：每章拆开
        ChapterPlanAggregate chapterPlan = dynamicContext.getChapterPlanAggregate();
        withStoryLock(runDir, () -> {
            writeStringAtomic(runDir.resolve(StorageKeys.INPUT_FILE), input.toJSONString());
            if (chapterPlan != null && chapterPlan.getChapters() != null) {
                for (ChapterPlanItemEntity item : chapterPlan.getChapters()) {
                    String fileName = String.format("%s%04d%s", StorageKeys.OUTPUT_FILE_PREFIX, item.getChapterNo(), StorageKeys.OUTPUT_FILE_SUFFIX);
                    writeStringAtomic(runDir.resolve(fileName), JSON.toJSONString(item));
                }
            }
        });
    }

    @Override
    public List<StorySummary> listStories() throws IOException {
        return listStories(Paths.get(StorageKeys.WORKSPACE_DIR));
    }

    static List<StorySummary> listStories(Path workspace) throws IOException {
        if (!Files.isDirectory(workspace)) {
            return List.of();
        }
        List<StorySummary> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(workspace)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                String name = dir.getFileName().toString();
                if (!STORY_DIR_PATTERN.matcher(name).matches()) {
                    continue;
                }
                String title = readBibleTitle(dir);
                int chapterCount = countChapterFiles(dir);
                long lastModified = readLastModifiedMs(dir);
                result.add(new StorySummary(name, title, chapterCount, lastModified));
            }
        }
        result.sort((a, b) -> b.storyDirName().compareTo(a.storyDirName()));
        return result;
    }

    private static String readBibleTitle(Path storyDir) {
        Path bible = storyDir.resolve(StorageKeys.STORY_BIBLE_FILE);
        if (!Files.exists(bible)) {
            return storyDir.getFileName().toString();
        }
        try {
            String firstLine = Files.readString(bible).lines().findFirst().orElse("");
            if (firstLine.startsWith("小说名称:")) {
                String title = firstLine.substring("小说名称:".length()).trim();
                return title.isEmpty() ? storyDir.getFileName().toString() : title;
            }
            if (firstLine.startsWith("小说名称：")) {
                String title = firstLine.substring("小说名称：".length()).trim();
                return title.isEmpty() ? storyDir.getFileName().toString() : title;
            }
            return storyDir.getFileName().toString();
        } catch (IOException e) {
            log.warn("story-bible.txt 读取失败，使用目录名作为标题：{}", e.getMessage());
            return storyDir.getFileName().toString();
        }
    }

    private static int countChapterFiles(Path storyDir) {
        Path chapterDir = storyDir.resolve(StorageKeys.CHAPTER_DIR);
        if (!Files.isDirectory(chapterDir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(chapterDir)) {
            return (int) stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith(StorageKeys.CHAPTER_FILE_PREFIX) && n.endsWith(StorageKeys.CHAPTER_FILE_SUFFIX))
                    .count();
        } catch (IOException e) {
            log.warn("章节目录读取失败：{}", e.getMessage());
            return 0;
        }
    }

    private static long readLastModifiedMs(Path storyDir) {
        try {
            long latest = Files.getLastModifiedTime(storyDir).toMillis();
            try (Stream<Path> stream = Files.walk(storyDir, 3)) {
                for (Path f : stream.filter(Files::isRegularFile).toList()) {
                    long m = Files.getLastModifiedTime(f).toMillis();
                    if (m > latest) {
                        latest = m;
                    }
                }
            }
            return latest;
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public ChapterContentEntity readChapter(Path storyDir, int chapterNo) throws IOException {
        String fileName = String.format("%s%04d%s", StorageKeys.CHAPTER_FILE_PREFIX, chapterNo, StorageKeys.CHAPTER_FILE_SUFFIX);
        Path file = storyDir.resolve(StorageKeys.CHAPTER_DIR).resolve(fileName);
        if (!Files.exists(file)) {
            return null;
        }
        String raw = Files.readString(file);
        String[] lines = raw.split("\n", 2);
        String firstLine = lines[0].trim();
        java.util.regex.Matcher matcher = CHAPTER_HEADER_PATTERN.matcher(firstLine);
        String title = "";
        String content = raw;
        if (matcher.matches()) {
            title = matcher.group(2) == null ? "" : matcher.group(2).trim();
            // 存量文件兼容：标题若以与章节号重复的"第N章"开头则剥除（模型输出标题自带前缀，落盘时又拼了一次）
            title = cn.novel.yonren.types.utils.ChapterTitleNormalizer.stripDuplicatePrefix(title, chapterNo);
            content = lines.length > 1 ? lines[1].stripLeading() : "";
        }
        ChapterContentEntity entity = new ChapterContentEntity();
        entity.setChapterNo(chapterNo);
        entity.setTitle(title);
        entity.setContent(content);
        return entity;
    }

    @Override
    public ChapterPlanItemEntity readChapterPlanItem(Path storyDir, int chapterNo) throws IOException {
        Path recordRoot = storyDir.resolve(StorageKeys.RECORD);
        if (!Files.isDirectory(recordRoot)) {
            return null;
        }
        String targetFileName = String.format("%s%04d%s", StorageKeys.OUTPUT_FILE_PREFIX, chapterNo, StorageKeys.OUTPUT_FILE_SUFFIX);
        Path bestMatch = null;
        String bestDirName = null;
        try (Stream<Path> stream = Files.list(recordRoot)) {
            List<Path> runDirs = stream.filter(Files::isDirectory).sorted().toList();
            for (Path runDir : runDirs) {
                Path candidate = runDir.resolve(targetFileName);
                if (Files.exists(candidate)) {
                    String dirName = runDir.getFileName().toString();
                    if (bestDirName == null || dirName.compareTo(bestDirName) > 0) {
                        bestDirName = dirName;
                        bestMatch = candidate;
                    }
                }
            }
        }
        if (bestMatch == null) {
            return null;
        }
        String json = Files.readString(bestMatch);
        return JSON.parseObject(json, ChapterPlanItemEntity.class);
    }

    /**
     * 读 JSON 数组记忆文件：不存在返回空列表；存在但读取/解析失败抛 AppException 硬失败。
     * 记忆是续写的唯一依据，静默降级为空等于整体丢失账本与伏笔账
     */
    private <T> List<T> readJsonArrayIfExists(Path file, Class<T> elementType, String label) {
        if (!Files.exists(file)) {
            return List.of();
        }
        String json = readFileOrThrow(file, label);
        List<T> parsed;
        try {
            parsed = JSON.parseArray(json, elementType);
        } catch (RuntimeException e) {
            throw corruptMemory(file, label, e);
        }
        if (parsed == null || parsed.stream().anyMatch(Objects::isNull)) {
            throw corruptMemory(file, label, null);
        }
        return parsed;
    }

    private String readFileOrThrow(Path file, String label) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(),
                    label + "文件读取失败（磁盘/权限问题）：" + file, e);
        }
    }

    private static AppException corruptMemory(Path file, String label, Throwable cause) {
        String msg = label + "文件损坏（JSON 解析失败）：" + file + "。请从备份恢复或人工修复后重试，禁止静默丢弃历史记忆";
        return cause == null
                ? new AppException(ResponseCode.UN_ERROR.getCode(), msg)
                : new AppException(ResponseCode.UN_ERROR.getCode(), msg, cause);
    }

    /**
     * 原子写：先写同目录隐藏临时文件再原子替换目标。中途崩溃只会留下可被下次写入覆盖的 .tmp 残片，
     * 目标文件永远不会处于半截状态；文件系统不支持原子移动时降级为普通替换移动
     */
    private void writeStringAtomic(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.getParent().resolve("." + file.getFileName().toString() + ".tmp");
        try {
            Files.writeString(tmp, content);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void withStoryLock(Path storyDir, IoCheckedRunnable action) throws IOException {
        ReentrantLock lock = storyLocks.computeIfAbsent(
                storyDir.toAbsolutePath().normalize().toString(), key -> new ReentrantLock());
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    @FunctionalInterface
    private interface IoCheckedRunnable {
        void run() throws IOException;
    }

    private String quote(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "\\n") + "\"";
    }

    @Override
    public List<CheckpointEntity> listCheckpoints(Path storyDir) throws IOException {
        Path dir = checkpointRoot(storyDir);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<CheckpointEntity> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path cp : stream.filter(Files::isDirectory).toList()) {
                Path meta = cp.resolve(StorageKeys.CHECKPOINT_META_FILE);
                if (!Files.exists(meta)) {
                    continue; // 半成品目录（创建未完成的检查点），跳过而非硬失败
                }
                CheckpointEntity entity = readCheckpointObject(meta);
                if (entity != null) {
                    result.add(entity);
                }
            }
        }
        result.sort((a, b) -> Integer.compare(a.getVersionNo(), b.getVersionNo()));
        return result;
    }

    @Override
    public CheckpointEntity readCheckpoint(Path storyDir, String checkpointId) throws IOException {
        Path meta = checkpointDirOf(storyDir, checkpointId).resolve(StorageKeys.CHECKPOINT_META_FILE);
        if (!Files.exists(meta)) {
            return null;
        }
        return readCheckpointObject(meta);
    }

    @Override
    public String snapshotCheckpoint(Path storyDir, CheckpointType type, String name) throws IOException {
        if (type == CheckpointType.MANUAL && (name == null || name.isBlank())) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), "手动快照必须提供命名词（name）");
        }
        final String[] idHolder = new String[1];
        final int[] countHolder = new int[1];
        withStoryLock(storyDir, () -> {
            List<Integer> chapterNumbers = readChapterNumbers(storyDir);
            int chapterCount = chapterNumbers.isEmpty() ? 0 : chapterNumbers.get(chapterNumbers.size() - 1);
            List<Path> sources = collectSnapshotSources(storyDir, chapterCount);
            List<String> manifest = sources.stream()
                    .map(p -> storyDir.relativize(p).toString().replace('\\', '/'))
                    .sorted().toList();

            int versionNo = nextVersionNo(storyDir);
            String id = "cp-" + System.currentTimeMillis() + "-" + String.format("%04d", versionNo);
            Path cpDir = checkpointDirOf(storyDir, id);
            Path snapshotDir = cpDir.resolve(StorageKeys.CHECKPOINT_SNAPSHOT_DIR);

            CheckpointEntity meta = CheckpointEntity.builder()
                    .checkpointId(id)
                    .versionNo(versionNo)
                    .name(type == CheckpointType.MANUAL ? name : "auto-" + versionNo)
                    .type(type)
                    .chapterCount(chapterCount)
                    .filesManifest(manifest)
                    .createdAtMs(System.currentTimeMillis())
                    .isCurrent(false)
                    .build();

            // 静态拷贝（非原子替换）：快照是独立副本，目录新建无并发覆盖
            Files.createDirectories(snapshotDir);
            for (Path source : sources) {
                Path dst = snapshotDir.resolve(storyDir.relativize(source));
                Files.createDirectories(dst.getParent());
                Files.copy(source, dst, StandardCopyOption.REPLACE_EXISTING);
            }
            writeStringAtomic(cpDir.resolve(StorageKeys.CHECKPOINT_META_FILE), JSON.toJSONString(meta));

            pruneCheckpointsLocked(storyDir);
            idHolder[0] = id;
            countHolder[0] = chapterCount;
        });
        log.info("已创建检查点 {}（{}，至第 {} 章）", idHolder[0], type, countHolder[0]);
        return idHolder[0];
    }

    @Override
    public void restoreCheckpoint(Path storyDir, String checkpointId) throws IOException {
        final int[] restoredCount = new int[1];
        withStoryLock(storyDir, () -> {
            Path metaPath = checkpointDirOf(storyDir, checkpointId).resolve(StorageKeys.CHECKPOINT_META_FILE);
            if (!Files.exists(metaPath)) {
                throw new AppException(ResponseCode.UN_ERROR.getCode(), "检查点不存在：" + checkpointId);
            }
            CheckpointEntity meta = readCheckpointObject(metaPath);
            if (meta == null) {
                throw new AppException(ResponseCode.UN_ERROR.getCode(), "检查点元数据损坏：" + checkpointId);
            }
            Path snapshotRoot = checkpointDirOf(storyDir, checkpointId).resolve(StorageKeys.CHECKPOINT_SNAPSHOT_DIR);

            // 1) 还原 manifest 内全部文件（章节正文 + 摘要/蓝图/质量债/伏笔结算/风格/bible）
            for (String rel : meta.getFilesManifest()) {
                if (rel == null || rel.isBlank()) {
                    continue;
                }
                Path src = snapshotRoot.resolve(rel);
                Path dst = storyDir.resolve(rel);
                if (!Files.isRegularFile(src)) {
                    continue; // 快照后被裁剪的源文件，跳过
                }
                Files.createDirectories(dst.getParent());
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            }
            // 2) 裁剪超出检查点章号的正文（summaries.json 已随上步整份还原为 1..chapterCount）
            truncateChaptersBeyond(storyDir, meta.getChapterCount());
            // 3) 观测标记落点
            markCurrentCheckpoint(storyDir, checkpointId);
            restoredCount[0] = meta.getChapterCount();
        });
        log.info("已回滚到检查点：{}（至第 {} 章）", checkpointId, restoredCount[0]);
    }

    @Override
    public void pruneCheckpoints(Path storyDir) throws IOException {
        withStoryLock(storyDir, () -> pruneCheckpointsLocked(storyDir));
    }

    private void pruneCheckpointsLocked(Path storyDir) throws IOException {
        List<CheckpointEntity> all = listCheckpoints(storyDir);
        if (all.size() <= CHECKPOINT_RETENTION) {
            return;
        }
        for (int i = 0; i + CHECKPOINT_RETENTION < all.size(); i++) {
            CheckpointEntity oldest = all.get(i);
            deleteRecursively(checkpointDirOf(storyDir, oldest.getCheckpointId()));
            log.info("检查点超过保留上限，回收最旧：{}（至第 {} 章）", oldest.getCheckpointId(), oldest.getChapterCount());
        }
    }

    private Path checkpointRoot(Path storyDir) {
        return storyDir.resolve(StorageKeys.MEMORY_DIR).resolve(StorageKeys.CHECKPOINT_DIR);
    }

    private Path checkpointDirOf(Path storyDir, String checkpointId) {
        return checkpointRoot(storyDir).resolve(checkpointId);
    }

    private int nextVersionNo(Path storyDir) throws IOException {
        int max = 0;
        Path root = checkpointRoot(storyDir);
        if (Files.isDirectory(root)) {
            try (Stream<Path> stream = Files.list(root)) {
                for (Path cp : stream.filter(Files::isDirectory).toList()) {
                    Path metaPath = cp.resolve(StorageKeys.CHECKPOINT_META_FILE);
                    if (Files.exists(metaPath)) {
                        CheckpointEntity entity = readCheckpointObject(metaPath);
                        if (entity != null && entity.getVersionNo() > max) {
                            max = entity.getVersionNo();
                        }
                    }
                }
            }
        }
        return max + 1;
    }

    private CheckpointEntity readCheckpointObject(Path metaPath) {
        String json = readFileOrThrow(metaPath, "检查点元数据");
        try {
            return JSON.parseObject(json, CheckpointEntity.class);
        } catch (RuntimeException e) {
            throw corruptMemory(metaPath, "检查点元数据", e);
        }
    }

    /** 采集检查点镜像源：现有正文 ≤chapterCount + memory 记忆文件 + bible（文件存在才收录） */
    private List<Path> collectSnapshotSources(Path storyDir, int chapterCount) throws IOException {
        List<Path> sources = new ArrayList<>();
        Path chapterDir = storyDir.resolve(StorageKeys.CHAPTER_DIR);
        if (Files.isDirectory(chapterDir)) {
            try (Stream<Path> stream = Files.list(chapterDir)) {
                for (Path f : stream.filter(Files::isRegularFile).toList()) {
                    String name = f.getFileName().toString();
                    if (!name.startsWith(StorageKeys.CHAPTER_FILE_PREFIX) || !name.endsWith(StorageKeys.CHAPTER_FILE_SUFFIX)) {
                        continue;
                    }
                    try {
                        int no = Integer.parseInt(name.substring(StorageKeys.CHAPTER_FILE_PREFIX.length(),
                                name.length() - StorageKeys.CHAPTER_FILE_SUFFIX.length()));
                        if (no <= chapterCount) {
                            sources.add(f);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        Path memory = storyDir.resolve(StorageKeys.MEMORY_DIR);
        addIfExists(sources, memory.resolve(StorageKeys.SUMMARIES_FILE));
        addIfExists(sources, memory.resolve(StorageKeys.CONSISTENCY_INDEX_FILE));
        addIfExists(sources, memory.resolve(StorageKeys.ROLLING_OUTLINE_FILE));
        addIfExists(sources, memory.resolve(StorageKeys.VOLUME_FILE));
        addIfExists(sources, memory.resolve(StorageKeys.QUALITY_DEBT_FILE));
        addIfExists(sources, memory.resolve(StorageKeys.FORESHADOW_SETTLEMENT_FILE));
        // 排期表必须进快照：它是种子上 scheduledPayoffChapter 打标的依据，
        // 丢失会让"打标"变成悬空引用（审批挂起/崩溃恢复是最常见触发场景）
        addIfExists(sources, memory.resolve(StorageKeys.FORESHADOW_SCHEDULE_FILE));
        addIfExists(sources, memory.resolve(StorageKeys.STYLE_STAT_FILE));
        addIfExists(sources, storyDir.resolve(StorageKeys.STORY_BIBLE_FILE));
        return sources;
    }

    private static void addIfExists(List<Path> sources, Path candidate) {
        if (Files.isRegularFile(candidate)) {
            sources.add(candidate);
        }
    }

    private void truncateChaptersBeyond(Path storyDir, int chapterCount) throws IOException {
        Path chapterDir = storyDir.resolve(StorageKeys.CHAPTER_DIR);
        if (!Files.isDirectory(chapterDir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(chapterDir)) {
            for (Path f : stream.filter(Files::isRegularFile).toList()) {
                String name = f.getFileName().toString();
                if (!name.startsWith(StorageKeys.CHAPTER_FILE_PREFIX) || !name.endsWith(StorageKeys.CHAPTER_FILE_SUFFIX)) {
                    continue;
                }
                try {
                    int no = Integer.parseInt(name.substring(StorageKeys.CHAPTER_FILE_PREFIX.length(),
                            name.length() - StorageKeys.CHAPTER_FILE_SUFFIX.length()));
                    if (no > chapterCount) {
                        Files.deleteIfExists(f);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    private void markCurrentCheckpoint(Path storyDir, String currentId) throws IOException {
        List<CheckpointEntity> all = listCheckpoints(storyDir);
        for (CheckpointEntity cp : all) {
            boolean current = currentId.equals(cp.getCheckpointId());
            if (cp.isCurrent() == current) {
                continue;
            }
            cp.setCurrent(current);
            Path metaPath = checkpointDirOf(storyDir, cp.getCheckpointId()).resolve(StorageKeys.CHECKPOINT_META_FILE);
            writeStringAtomic(metaPath, JSON.toJSONString(cp));
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            for (Path p : stream.sorted((a, b) -> -a.compareTo(b)).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
