package cn.novel.yonren.infrastructure.adapter.repository;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.ConsistencyIndexEntity;
import cn.novel.yonren.domain.novel.model.entity.CheckpointEntity;
import cn.novel.yonren.types.enums.CheckpointType;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.entity.VolumeBlueprintEntity;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.job.GenerationJob;
import cn.novel.yonren.types.enums.JobStatus;
import cn.novel.yonren.types.exception.AppException;
import cn.novel.yonren.types.utils.StoryBibleParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 续写目录解析的越界防护测试：格式白名单、目录存在性、真实路径（符号链接）防线。
 * 另覆盖仓储落盘约定：原子写不留 .tmp 残片、损坏记忆硬失败、序号并发分配、读写往返
 */
class StoryRepositoryTest {

    @TempDir
    Path workspace;

    private final StoryRepository repository = new StoryRepository();

    @Test
    void resolve_rejectsBlankAndMalformedIds() {
        assertThrows(AppException.class, () -> StoryRepository.resolveValidatedStoryDirectory(workspace, null));
        assertThrows(AppException.class, () -> StoryRepository.resolveValidatedStoryDirectory(workspace, "  "));
        assertThrows(AppException.class, () -> StoryRepository.resolveValidatedStoryDirectory(workspace, "../etc"));
        assertThrows(AppException.class, () -> StoryRepository.resolveValidatedStoryDirectory(workspace, "/etc/passwd"));
        assertThrows(AppException.class, () -> StoryRepository.resolveValidatedStoryDirectory(workspace, "C:\\Windows"));
        assertThrows(AppException.class, () -> StoryRepository.resolveValidatedStoryDirectory(workspace, "20260902-story-0001/../x"));
        assertThrows(AppException.class, () -> StoryRepository.resolveValidatedStoryDirectory(workspace, "story-0001"));
        assertThrows(AppException.class, () -> StoryRepository.resolveValidatedStoryDirectory(workspace, "20260902-story-1"));
        assertThrows(AppException.class, () -> StoryRepository.resolveValidatedStoryDirectory(workspace, "20261302-story-0001"));
        assertThrows(AppException.class, () -> StoryRepository.resolveValidatedStoryDirectory(workspace, "20260902-story-00010"));
    }

    @Test
    void resolve_rejectsMissingDirectory() {
        AppException e = assertThrows(AppException.class,
                () -> StoryRepository.resolveValidatedStoryDirectory(workspace, "20260902-story-0099"));
        assertTrue(e.getInfo().contains("不存在"));
    }

    @Test
    void resolve_acceptsValidStoryDirectoryAndEnsuresMemoryDir() throws IOException {
        Path storyDir = workspace.resolve("20260902-story-0001");
        Files.createDirectories(storyDir);

        Path resolved = StoryRepository.resolveValidatedStoryDirectory(workspace, "20260902-story-0001");

        assertEquals(storyDir.toAbsolutePath().normalize(), resolved.toAbsolutePath().normalize());
        assertTrue(Files.isDirectory(resolved.resolve(StorageKeys.MEMORY_DIR)));
    }

    @Test
    void resolve_rejectsSymlinkEscape(@TempDir Path outside) throws IOException, InterruptedException {
        Path link = workspace.resolve("20260902-story-0002");
        assumeTrue(createDirLink(link, outside), "当前环境不支持创建符号链接/junction，跳过");
        // ⚠️ 的前置检查：Windows 上 createSymbolicLink 可能"创建成功"但被判定为
        // **文件**类型（或 junction 回退未真正生效），此时链接根本不是目录，
        // 解析会在"故事目录不存在"这步早退——那条路径不是本用例要验的越界防护。
        // 这种情况属环境语义差异，跳过而不是让断言红着（它曾把后续模块的测试整段 SKIPPED）。
        assumeTrue(Files.isDirectory(link), "链接创建后不是目录（Windows 链接语义差异），跳过");
        try {
            AppException e = assertThrows(AppException.class,
                    () -> StoryRepository.resolveValidatedStoryDirectory(workspace, "20260902-story-0002"));
            // 断言"越界被拒绝"这个**行为**（目录名格式合法、链接目标真实存在，只剩越界一条路），
            // 失败信息里带上实际文案，便于区分"检测失效"与"只是措辞变了"
            assertTrue(e.getInfo() != null && e.getInfo().contains("越出"),
                    "应因越界被拒绝，实际信息=" + e.getInfo());
        } finally {
            // 先删链接本体（仅删重解析点，不动目标），否则 @TempDir 清理会下钻目标目录
            Files.deleteIfExists(link);
        }
    }

    @Test
    void writeReadSummaries_roundTrip_leaveNoTmpResidue() throws Exception {
        Path storyDir = workspace.resolve("20260902-story-0003");
        Files.createDirectories(storyDir);

        ChapterSummaryEntity summary = new ChapterSummaryEntity();
        summary.setChapterNo(1);
        summary.setTitle("第一章");
        repository.writeChapterSummaries(storyDir, List.of(summary));

        List<ChapterSummaryEntity> read = repository.readChapterSummaries(storyDir);
        assertEquals(1, read.size());
        assertEquals(1, read.get(0).getChapterNo());
        assertNoTmpResidue(storyDir.resolve(StorageKeys.MEMORY_DIR));
    }

    @Test
    void read_corruptMemoryFiles_hardFail() throws IOException {
        Path memoryDir = workspace.resolve("20260902-story-0004").resolve(StorageKeys.MEMORY_DIR);
        Files.createDirectories(memoryDir);
        Path storyDir = memoryDir.getParent();

        Files.writeString(memoryDir.resolve(StorageKeys.SUMMARIES_FILE), "{not json at all");
        Files.writeString(memoryDir.resolve(StorageKeys.QUALITY_DEBT_FILE), "[]garbage");
        Files.writeString(memoryDir.resolve(StorageKeys.ROLLING_OUTLINE_FILE), "[{\"x\":]");
        Files.writeString(memoryDir.resolve(StorageKeys.STYLE_STAT_FILE), "42");

        assertThrowsWithCorruptMessage(() -> repository.readChapterSummaries(storyDir));
        assertThrowsWithCorruptMessage(() -> repository.readQualityDebts(storyDir));
        assertThrowsWithCorruptMessage(() -> repository.readStageBlueprints(storyDir));
        assertThrowsWithCorruptMessage(() -> repository.readStyleStat(storyDir));
    }

    @Test
    void read_missingMemoryFiles_returnEmptyOrNull() throws Exception {
        Path storyDir = workspace.resolve("20260902-story-0005");
        Files.createDirectories(storyDir);

        assertTrue(repository.readChapterSummaries(storyDir).isEmpty());
        assertTrue(repository.readQualityDebts(storyDir).isEmpty());
        assertTrue(repository.readStageBlueprints(storyDir).isEmpty());
        assertNull(repository.readStyleStat(storyDir));
    }

    @Test
    void writeChapters_atomicWrites_latestReadable_noTmpResidue() throws Exception {
        Path storyDir = workspace.resolve("20260902-story-0006");
        Files.createDirectories(storyDir);

        ChapterContentEntity first = new ChapterContentEntity();
        first.setChapterNo(1);
        first.setTitle("开端");
        first.setContent("第一段\\n第二段");
        ChapterContentEntity second = new ChapterContentEntity();
        second.setChapterNo(2);
        second.setTitle("推进");
        second.setContent("续章内容");

        repository.writeChapters(storyDir, List.of(first, second));

        assertTrue(repository.readLatestChapterContent(storyDir).contains("续章内容"));
        assertNoTmpResidue(storyDir.resolve(StorageKeys.CHAPTER_DIR));
    }

    @Test
    void createStoryDirectory_concurrent_allocatesDistinctDirectories() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Path>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return StoryRepository.createStoryDirectory(workspace);
            }));
        }
        start.countDown();

        Set<Path> allocated = new HashSet<>();
        for (Future<Path> future : futures) {
            Path storyDir = future.get(10, TimeUnit.SECONDS);
            assertTrue(Files.isDirectory(storyDir));
            assertTrue(Files.isDirectory(storyDir.resolve(StorageKeys.CHAPTER_DIR)));
            assertTrue(allocated.add(storyDir), "故事目录被并发重复分配：" + storyDir);
        }
        pool.shutdown();
    }

    @Test
    void writeStoryBible_onlyFirstWriteEffective() throws Exception {
        Path storyDir = workspace.resolve("20260902-story-0007");
        Files.createDirectories(storyDir);

        repository.writeStoryBible(storyDir, ArmoryCommandEntity.builder()
                .storyContextEntity(StoryContextEntity.builder().novel_title("甲").build())
                .build());
        repository.writeStoryBible(storyDir, ArmoryCommandEntity.builder()
                .storyContextEntity(StoryContextEntity.builder().novel_title("乙").build())
                .build());

        String content = Files.readString(storyDir.resolve(StorageKeys.STORY_BIBLE_FILE));
        assertTrue(content.contains("甲"));
        assertTrue(!content.contains("乙"));
        assertNoTmpResidue(storyDir);
    }

    /**
     * bible「写入 → 解析」往返：标签常量由 {@link StoryBibleParser} 单点持有，本测试是
     * "改了写入忘了改读取"（或反之）的**唯一防线**——值解析不出来不会报错，
     * 只会在前端点"继续写"时表现为"设定莫名其妙丢了"。
     *
     * <p>书名故意含全角冒号，用来钉住"只按第一个 ASCII 冒号切分"这条口径。
     */
    @Test
    void storyBible_roundTripsThroughParser() throws Exception {
        Path storyDir = workspace.resolve("20260902-story-0011");
        Files.createDirectories(storyDir);

        StoryVO.StoryFeatures features = new StoryVO.StoryFeatures();
        features.setHasCheatMechanism(true);
        features.setCheatMechanismName("九渊剑匣");
        features.setCheatUsageInterval(3);
        StoryVO storyVO = new StoryVO();
        storyVO.setFeatures(features);

        ArmoryCommandEntity command = ArmoryCommandEntity.builder()
                .storyContextEntity(StoryContextEntity.builder()
                        .novel_title("《道诡：理智尽头是疯狂》")
                        .theme("玄幻/仙侠")
                        .style("硬核战斗")
                        .worldSetting("九州大陆正值末法时代")
                        .perspective("第三人称限知视角")
                        .targetAudience("男频")
                        .tone("苍凉悲壮")
                        .protagonist("顾青山，22岁")
                        .outline("顾青山穿越至九州大陆")
                        .chapterCount(180)
                        .chapterGoal("1-30章 剑启篇")
                        .worldId("novel-shared-01")
                        .build())
                .build();
        command.setStoryVO(storyVO);
        repository.writeStoryBible(storyDir, command);

        StoryBibleParser.Snapshot bible = StoryBibleParser.parse(repository.readStoryBible(storyDir));

        // 11 个必需设定字段，缺一个都会让续写撞上"全字段非空"校验
        assertEquals("《道诡：理智尽头是疯狂》", bible.novelTitle());
        assertEquals("玄幻/仙侠", bible.theme());
        assertEquals("硬核战斗", bible.style());
        assertEquals("九州大陆正值末法时代", bible.worldSetting());
        assertEquals("第三人称限知视角", bible.perspective());
        assertEquals("男频", bible.targetAudience());
        assertEquals("苍凉悲壮", bible.tone());
        assertEquals("顾青山，22岁", bible.protagonist());
        assertEquals("顾青山穿越至九州大陆", bible.outline());
        assertEquals("1-30章 剑启篇", bible.chapterGoal());
        // 世界 ID 必须续上：它是共享向量集合 novel-world-{worldId} 的键，丢了会切断系列共享
        assertEquals("novel-shared-01", bible.worldId());
        // 金手指三态：true 与"没写"语义不同，不能被压成同一个值
        assertEquals(Boolean.TRUE, bible.hasCheatMechanism());
        assertEquals("九渊剑匣", bible.cheatMechanismName());
        assertEquals(3, bible.cheatUsageInterval());
    }

    @Test
    void createRunDirectory_allocatesSequentialRunDirs() throws Exception {
        Path storyDir = workspace.resolve("20260902-story-0008");
        Files.createDirectories(storyDir);

        Path first = repository.createRunDirectory(storyDir);
        Path second = repository.createRunDirectory(storyDir);

        assertEquals("run-0001", first.getFileName().toString());
        assertEquals("run-0002", second.getFileName().toString());
        assertTrue(Files.isDirectory(first));
        assertTrue(Files.isDirectory(second));
    }

    @Test
    void writeChapterPlan_landsInRunDir() throws Exception {
        Path storyDir = workspace.resolve("20260902-story-0009");
        Files.createDirectories(storyDir);
        Path runDir = repository.createRunDirectory(storyDir);

        ChapterPlanItemEntity item = new ChapterPlanItemEntity();
        item.setChapterNo(1);
        item.setTitle("第一章");
        ChapterPlanAggregate plan = ChapterPlanAggregate.builder()
                .storyId("story-1")
                .chapters(List.of(item))
                .build();

        repository.writeChapterPlan(runDir, plan);

        assertTrue(Files.exists(runDir.resolve(StorageKeys.CHAPTER_PLAN_FILE)));
        assertTrue(!Files.exists(storyDir.resolve(StorageKeys.CHAPTER_PLAN_FILE)), "计划文件不应再落在故事根目录");
        assertNoTmpResidue(runDir);
    }

    @Test
    void readChapterNumbers_returnsSortedNumbers_ignoresOtherFiles() throws Exception {
        Path storyDir = workspace.resolve("20260902-story-0010");
        Path chapterDir = storyDir.resolve(StorageKeys.CHAPTER_DIR);
        Files.createDirectories(chapterDir);
        Files.writeString(chapterDir.resolve("chapter-0003.txt"), "3");
        Files.writeString(chapterDir.resolve("chapter-0001.txt"), "1");
        Files.writeString(chapterDir.resolve("notes.txt"), "x");
        Files.writeString(chapterDir.resolve("chapter-0002.txt"), "2");

        assertEquals(List.of(1, 2, 3), repository.readChapterNumbers(storyDir));

        Path emptyStory = workspace.resolve("20260902-story-0011");
        Files.createDirectories(emptyStory);
        assertTrue(repository.readChapterNumbers(emptyStory).isEmpty());
    }

    @Test
    void createRunDirectory_withJobId_namesRunJobDir_conflictFails() throws Exception {
        Path storyDir = workspace.resolve("20260902-story-0012");
        Files.createDirectories(storyDir);

        Path runDir = repository.createRunDirectory(storyDir, "job-123");

        assertEquals("run-job-job-123", runDir.getFileName().toString());
        assertTrue(Files.isDirectory(runDir));
        // jobId 全局唯一，重复创建视为状态异常直接抛错（不重试）
        assertThrows(IOException.class, () -> repository.createRunDirectory(storyDir, "job-123"));
    }

    @Test
    void createRunDirectory_jobDirs_doNotPolluteSequentialNumbering() throws Exception {
        Path storyDir = workspace.resolve("20260902-story-0013");
        Files.createDirectories(storyDir);

        repository.createRunDirectory(storyDir, "job-a");
        assertEquals("run-0001", repository.createRunDirectory(storyDir).getFileName().toString());
        repository.createRunDirectory(storyDir, "job-b");
        assertEquals("run-0002", repository.createRunDirectory(storyDir).getFileName().toString());
    }

    @Test
    void writeReadJobStatus_roundTrip_noTmpResidue() throws Exception {
        Path storyDir = workspace.resolve("20260902-story-0014");
        Files.createDirectories(storyDir);

        GenerationJob job = new GenerationJob("job-42");
        job.markRunning();
        job.updateProgress("CHAPTER_GENERATION", 3, 10, "20260902-story-0014");
        job.recordChapterDuration(3, 1234L);
        repository.writeJobStatus(storyDir, job);

        GenerationJob read = repository.readJobStatus(storyDir);
        assertEquals("job-42", read.getJobId());
        assertEquals(JobStatus.RUNNING, read.getStatus());
        assertEquals(Integer.valueOf(3), read.getCurrentChapter());
        assertEquals(Integer.valueOf(10), read.getTotalChapters());
        assertEquals("20260902-story-0014", read.getStoryDirName());
        assertEquals(Long.valueOf(1234L), read.getChapterDurations().get("3"));
        assertNoTmpResidue(storyDir);

        // 落盘物必须是**严格合法 JSON** 实测缺陷：fastjson2 把 Integer 键写成
        // 不带引号的 {3:1234}，Jackson / python json.load / jq 都会解析失败）。
        // 这里直接对磁盘文件做第三方严格解析，而不是回读一遍——回读走 fastjson2，它太宽松，自证不了。
        var strict = new com.fasterxml.jackson.databind.ObjectMapper();
        var node = strict.readTree(Files.readString(storyDir.resolve("job-status.json")));
        assertEquals(1234L, node.get("chapterDurations").get("3").asLong());
    }

    @Test
    void readJobStatus_missingOrCorruptFile_returnsNull() throws Exception {
        Path storyDir = workspace.resolve("20260902-story-0015");
        Files.createDirectories(storyDir);

        assertNull(repository.readJobStatus(storyDir));

        Files.writeString(storyDir.resolve(StorageKeys.JOB_STATUS_FILE), "{broken json");
        // 观测性文件：损坏软失败返回 null（与记忆文件的硬失败约定不同）
        assertNull(repository.readJobStatus(storyDir));
    }

    @Test
    void listStories_scansWorkspaceAndReadsTitle() throws Exception {
        Path storyDir = workspace.resolve("20260902-story-0020");
        Files.createDirectories(storyDir.resolve(StorageKeys.CHAPTER_DIR));
        Files.createDirectories(storyDir.resolve(StorageKeys.RECORD));
        Files.writeString(storyDir.resolve(StorageKeys.STORY_BIBLE_FILE), "小说名称: 灵气复苏\n世界观：现代");
        Files.writeString(storyDir.resolve(StorageKeys.CHAPTER_DIR).resolve("chapter-0001.txt"), "第1章 开端\n\n内容");
        Files.writeString(storyDir.resolve(StorageKeys.CHAPTER_DIR).resolve("chapter-0002.txt"), "第2章 发展\n\n内容");

        List<IStoryRepository.StorySummary> stories = StoryRepository.listStories(workspace);

        assertEquals(1, stories.size());
        IStoryRepository.StorySummary s = stories.get(0);
        assertEquals("20260902-story-0020", s.storyDirName());
        assertEquals("灵气复苏", s.novelTitle());
        assertEquals(2, s.chapterCount());
        assertTrue(s.lastModifiedMs() > 0);
    }

    @Test
    void listStories_emptyWorkspace_returnsEmpty() throws Exception {
        assertTrue(StoryRepository.listStories(workspace).isEmpty());
    }

    @Test
    void readChapter_parsesHeaderAndContent() throws Exception {
        Path storyDir = workspace.resolve("20260902-story-0021");
        Path chapterDir = storyDir.resolve(StorageKeys.CHAPTER_DIR);
        Files.createDirectories(chapterDir);
        Files.writeString(chapterDir.resolve("chapter-0003.txt"), "第3章 高潮\n\n这是正文内容\n第二段");

        ChapterContentEntity entity = repository.readChapter(storyDir, 3);

        assertEquals(3, entity.getChapterNo());
        assertEquals("高潮", entity.getTitle());
        assertTrue(entity.getContent().contains("这是正文内容"));
    }

    @Test
    void readChapter_missingFile_returnsNull() throws Exception {
        Path storyDir = workspace.resolve("20260902-story-0022");
        Files.createDirectories(storyDir.resolve(StorageKeys.CHAPTER_DIR));

        assertNull(repository.readChapter(storyDir, 1));
    }

    @Test
    void readChapterPlanItem_readsLatestRunDir() throws Exception {
        Path storyDir = workspace.resolve("20260902-story-0023");
        Path recordRoot = storyDir.resolve(StorageKeys.RECORD);
        Files.createDirectories(recordRoot);

        Path run1 = recordRoot.resolve("run-0001");
        Files.createDirectories(run1);
        Files.writeString(run1.resolve("output-0001.json"),
                "{\"chapterNo\":1,\"title\":\"旧标题\",\"goal\":\"旧目标\"}");

        Path run2 = recordRoot.resolve("run-0002");
        Files.createDirectories(run2);
        Files.writeString(run2.resolve("output-0001.json"),
                "{\"chapterNo\":1,\"title\":\"新标题\",\"goal\":\"新目标\"}");

        ChapterPlanItemEntity item = repository.readChapterPlanItem(storyDir, 1);

        assertEquals("新标题", item.getTitle());
        assertEquals("新目标", item.getGoal());
    }

    @Test
    void readChapterPlanItem_noMatch_returnsNull() throws Exception {
        Path storyDir = workspace.resolve("20260902-story-0024");
        Files.createDirectories(storyDir.resolve(StorageKeys.RECORD));

        assertNull(repository.readChapterPlanItem(storyDir, 1));
    }

    @Test
    void appendAuditSample_appendsJsonlLine() throws Exception {
        Path storyDir = workspace.resolve("20260902-story-0025");
        Files.createDirectories(storyDir);

        repository.appendAuditSample(storyDir, "{\"chapterNo\":1,\"issues\":[]}");
        repository.appendAuditSample(storyDir, "{\"chapterNo\":2,\"issues\":[\"x\"]}");

        Path file = storyDir.resolve(StorageKeys.MEMORY_DIR).resolve(StorageKeys.AUDIT_SAMPLES_FILE);
        assertTrue(Files.exists(file));
        String content = Files.readString(file);
        String[] lines = content.strip().split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].contains("\"chapterNo\":1"));
        assertTrue(lines[1].contains("\"chapterNo\":2"));
    }

    @Test
    void writeReadVolumes_roundTrip_noTmpResidue_noFilesWhenEmpty() throws Exception {
        Path storyDir = workspace.resolve("20260902-story-0026");
        Files.createDirectories(storyDir);

        VolumeBlueprintEntity volume = VolumeBlueprintEntity.builder()
                .volumeNo(1).startChapter(1).endChapter(300)
                .title("外门篇").themeShift("站稳外门")
                .arcPlan(List.of(VolumeBlueprintEntity.ArcPlan.builder().arcNo(1).oneLineGoal("封内门").build()))
                .build();
        repository.writeVolumes(storyDir, List.of(volume));

        List<VolumeBlueprintEntity> read = repository.readVolumes(storyDir);
        assertEquals(1, read.size());
        assertEquals(300, read.get(0).getEndChapter());
        assertEquals("站稳外门", read.get(0).getThemeShift());
        assertEquals("封内门", read.get(0).getArcPlan().get(0).getOneLineGoal());
        assertNoTmpResidue(storyDir.resolve(StorageKeys.MEMORY_DIR));

        // 空集合写入早退：不覆盖、不清空已落盘卷（fail-soft，避免瞬时空列表清库）
        repository.writeVolumes(storyDir, List.of());
        assertEquals(1, repository.readVolumes(storyDir).size());

        // 全新目录无卷文件：读取返回空
        Path freshStory = workspace.resolve("20260902-story-0027");
        Files.createDirectories(freshStory);
        assertTrue(repository.readVolumes(freshStory).isEmpty());

        // corrupt 时硬失败（记忆文件约定）
        Path corruptStory = workspace.resolve("20260902-story-0028");
        Files.createDirectories(corruptStory.resolve(StorageKeys.MEMORY_DIR));
        Files.writeString(corruptStory.resolve(StorageKeys.MEMORY_DIR).resolve(StorageKeys.VOLUME_FILE), "[{\"x\":]");
        assertThrowsWithCorruptMessage(() -> repository.readVolumes(corruptStory));
    }

    @Test
    void writeReadConsistencyIndex_roundTrip() throws Exception {
        Path storyDir = workspace.resolve("20260902-story-0011");
        Files.createDirectories(storyDir);
        ConsistencyIndexEntity index = ConsistencyIndexEntity.builder()
                .timeline(List.of(new ConsistencyIndexEntity.TimelineEntry("抵达北境", 3, "寒潮第三日", "原文")))
                .numbers(List.of(new ConsistencyIndexEntity.NumberEntry("北境守军", "三千人", 3, "北境")))
                .build();

        repository.writeConsistencyIndex(storyDir, index);
        ConsistencyIndexEntity restored = repository.readConsistencyIndex(storyDir);

        assertEquals("寒潮第三日", restored.getTimeline().get(0).getStoryTime());
        assertEquals("三千人", restored.getNumbers().get(0).getValue());
        assertNoTmpResidue(storyDir.resolve(StorageKeys.MEMORY_DIR));
    }

    @Test
    void snapshot_createsMetaAndMirror_readsBack_noTmpResidue() throws Exception {
        Path storyDir = workspace.resolve("20260902-story-0029");
        writeChapters(storyDir, 1, "开端", "开场正文");
        writeChapters(storyDir, 2, "推进", "后续正文");
        writeSummaries(storyDir, 1, 2);

        String checkpointId = repository.snapshotCheckpoint(storyDir, CheckpointType.MANUAL, "里程碑");

        CheckpointEntity meta = repository.readCheckpoint(storyDir, checkpointId);
        assertEquals("里程碑", meta.getName());
        assertEquals(CheckpointType.MANUAL, meta.getType());
        assertEquals(2, meta.getChapterCount());
        assertTrue(meta.getFilesManifest().contains("chapters/chapter-0002.txt"));
        assertTrue(meta.getFilesManifest().contains("memory/summaries.json"));

        // 快照目录与 meta 落盘可见
        assertTrue(Files.isRegularFile(storyDir.resolve(StorageKeys.MEMORY_DIR)
                .resolve(StorageKeys.CHECKPOINT_DIR).resolve(checkpointId)
                .resolve(StorageKeys.CHECKPOINT_SNAPSHOT_DIR).resolve("chapters/chapter-0002.txt")));
        assertNoTmpResidue(storyDir.resolve(StorageKeys.MEMORY_DIR));
    }

    @Test
    void snapshot_manualRequiresName() throws Exception {
        Path storyDir = workspace.resolve("20260902-story-0030");
        Files.createDirectories(storyDir);

        AppException e = assertThrows(AppException.class,
                () -> repository.snapshotCheckpoint(storyDir, CheckpointType.MANUAL, null));
        assertTrue(e.getInfo().contains("命名词"));
    }

    @Test
    void restore_restoresChaptersAndSummaries_truncatesBeyondSnapshot() throws Exception {
        Path storyDir = workspace.resolve("20260902-story-0031");
        writeChapters(storyDir, 1, "开端", "开场正文");
        writeChapters(storyDir, 2, "推进", "后续正文");
        writeSummaries(storyDir, 1, 2);
        String first = repository.snapshotCheckpoint(storyDir, CheckpointType.AUTO, null);

        // 续写到 4 章后再拍一个检查点
        writeChapters(storyDir, 3, "转折", "转折正文");
        writeChapters(storyDir, 4, "高潮", "高潮正文");
        writeSummaries(storyDir, 3, 4);
        String second = repository.snapshotCheckpoint(storyDir, CheckpointType.AUTO, null);
        assertEquals(4, repository.readChapterNumbers(storyDir).size());

        // 回滚到第一个检查点：章节裁剪 + 摘要整份还原
        repository.restoreCheckpoint(storyDir, first);

        assertEquals(List.of(1, 2), repository.readChapterNumbers(storyDir));
        assertEquals(2, repository.readChapterSummaries(storyDir).size());
        assertTrue(Files.notExists(storyDir.resolve(StorageKeys.CHAPTER_DIR).resolve("chapter-0003.txt")));
        assertTrue(Files.notExists(storyDir.resolve(StorageKeys.CHAPTER_DIR).resolve("chapter-0004.txt")));
        // sticky cap 之外的 memory 大文件不因回滚丢失第二检查点元数据
        assertTrue(repository.readCheckpoint(storyDir, second) != null);
    }

    @Test
    void prune_keepsLatestRetention_whenExceeding() throws Exception {
        Path storyDir = workspace.resolve("20260902-story-0032");
        Files.createDirectories(storyDir);
        // 连拍 10 个检查点（> 保留上限 8），回滚无必要，仅验证回收最旧
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            writeChapters(storyDir, i + 1, "第" + (i + 1) + "章", "内容");
            ids.add(repository.snapshotCheckpoint(storyDir, CheckpointType.AUTO, null));
        }

        List<CheckpointEntity> remaining = repository.listCheckpoints(storyDir);

        assertEquals(8, remaining.size());
        assertEquals(ids.get(2), remaining.get(0).getCheckpointId(), "应回收最早的 10-8=2 个");
        assertTrue(Files.notExists(storyDir.resolve(StorageKeys.MEMORY_DIR)
                .resolve(StorageKeys.CHECKPOINT_DIR).resolve(ids.get(0))));
    }

    @Test
    void list_ordersByVersionNoAndSkipsHalfBuiltDirs() throws Exception {
        Path storyDir = workspace.resolve("20260902-story-0033");
        Files.createDirectories(storyDir);
        String first = repository.snapshotCheckpoint(storyDir, CheckpointType.AUTO, null);
        // 留一个无 meta 的半成品目录，应被跳过
        Path halfBuilt = storyDir.resolve(StorageKeys.MEMORY_DIR).resolve(StorageKeys.CHECKPOINT_DIR)
                .resolve("cp-9999-half");
        Files.createDirectories(halfBuilt);
        String second = repository.snapshotCheckpoint(storyDir, CheckpointType.AUTO, null);

        List<CheckpointEntity> all = repository.listCheckpoints(storyDir);

        assertEquals(2, all.size());
        assertEquals(first, all.get(0).getCheckpointId());
        assertEquals(second, all.get(1).getCheckpointId());
        assertEquals(1, all.get(0).getVersionNo());
        assertEquals(2, all.get(1).getVersionNo());
    }

    @Test
    void restore_missingCheckpoint_throws() throws Exception {
        Path storyDir = workspace.resolve("20260902-story-0034");
        Files.createDirectories(storyDir);

        AppException e = assertThrows(AppException.class,
                () -> repository.restoreCheckpoint(storyDir, "cp-nonexistent"));
        assertTrue(e.getInfo().contains("不存在"));
    }

    private void writeChapters(Path storyDir, int chapterNo, String title, String content) throws IOException {
        ChapterContentEntity c = new ChapterContentEntity();
        c.setChapterNo(chapterNo);
        c.setTitle(title);
        c.setContent(content);
        repository.writeChapters(storyDir, List.of(c));
    }

    private void writeSummaries(Path storyDir, int... chapterNos) throws IOException {
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        for (int no : chapterNos) {
            ChapterSummaryEntity s = new ChapterSummaryEntity();
            s.setChapterNo(no);
            s.setTitle("第" + no + "章");
            s.setSummary("摘要" + no);
            summaries.add(s);
        }
        repository.writeChapterSummaries(storyDir, summaries);
    }

    private void assertThrowsWithCorruptMessage(RunnableThrows action) {
        AppException e = assertThrows(AppException.class, action::run);
        assertTrue(e.getInfo().contains("损坏"), "异常信息未标记损坏：" + e.getInfo());
    }

    private static void assertNoTmpResidue(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "目录残留 .tmp 临时文件");
        }
    }

    @FunctionalInterface
    private interface RunnableThrows {
        void run() throws Exception;
    }

    /**
     * 优先 POSIX 符号链接；Windows 无特权时回退到免特权的目录 junction（mklink /J）。
     * 两者都会被 toRealPath 解析，同样触发越界防线
     */
    private static boolean createDirLink(Path link, Path target) throws IOException, InterruptedException {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | IOException ignored) {
            // 回退到 junction
        }
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            return false;
        }
        Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                link.toAbsolutePath().toString(), target.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
        int exit = process.waitFor();
        return exit == 0 && Files.isDirectory(link);
    }

}
