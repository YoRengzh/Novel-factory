package cn.novel.yonren.infrastructure.adapter.repository;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.valobj.plan.ScenePlan;
import cn.novel.yonren.domain.novel.model.valobj.quality.GateResult;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import cn.novel.yonren.domain.novel.service.armory.quality.DeliveryReviewPolicy;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryRecordRepositoryTest {
    @TempDir
    Path workspace;

    private final StoryRepository repository = new StoryRepository();

    @Test
    void uncommittedDraftRoundTripsWithoutAdvancingOfficialChapterNumbersOrRecovery() throws Exception {
        Path story = story();
        Path run = repository.createRunDirectory(story, null);
        var first = new ChapterManuscript(1, "第一章", "已提交的第一章。");
        repository.writeChapters(story, List.of(first.toChapter()));
        repository.writeChapterSummaries(story, List.of(ChapterSummaryEntity.builder().chapterNo(1).build()));
        String firstBytes = Files.readString(story.resolve(StorageKeys.CHAPTER_DIR).resolve("chapter-0001.txt"));
        var draft = new ChapterManuscript(2, "待核验章", "交接已经完成。");
        var plan = plan(2);
        var gate = unknown(plan, draft);

        Path saved = repository.saveUncommittedChapter(story, run, draft, plan, gate, "交付未验证");

        assertEquals(run.resolve("uncommitted").toRealPath(), saved.getParent().toRealPath());
        assertTrue(saved.getFileName().toString().contains(draft.hash()));
        var record = JSON.parseObject(Files.readString(saved));
        assertEquals("UNCOMMITTED_CHAPTER_V1", record.getString("schema"));
        assertFalse(record.getBooleanValue("committed"));
        assertEquals(draft.hash(), record.getString("manuscriptHash"));
        assertEquals(draft.content(), record.getJSONObject("manuscript").getString("content"));
        assertEquals(2, record.getJSONObject("plan").getIntValue("schemaVersion"));
        assertEquals("handoff", record.getJSONObject("plan").getJSONArray("scenes").getJSONObject(0).getString("id"));
        assertEquals("UNKNOWN", record.getJSONObject("deliveryReview").getString("status"));
        assertFalse(Files.exists(story.resolve(StorageKeys.CHAPTER_COMMIT_FILE)));
        assertEquals(List.of(1), repository.readChapterNumbers(story));
        repository.recoverChapterCommit(story);
        assertEquals(List.of(1), repository.readChapterNumbers(story));
        assertEquals(2, repository.readChapterNumbers(story).stream().mapToInt(Integer::intValue).max().orElse(0) + 1);
        assertEquals(1, repository.readChapterSummaries(story).size());
        assertEquals(firstBytes, Files.readString(story.resolve(StorageKeys.CHAPTER_DIR).resolve("chapter-0001.txt")));
        assertNoTemporaryFiles(run);
    }

    @Test
    void sameManuscriptKeepsBothFailureDiagnosticsAndObservationStaysSeparate() throws Exception {
        Path story = story();
        Path run = repository.createRunDirectory(story, "test-diagnostics");
        var manuscript = new ChapterManuscript(1, "交接", "交接结果。");
        var plan = plan(1);
        var gate = unknown(plan, manuscript);
        Path first = repository.saveUncommittedChapter(story, run, manuscript, plan, gate, "第一次核验超时");
        Path second = repository.saveUncommittedChapter(story, run, manuscript, plan, gate, "第二次核验漏项");
        repository.recordDeliveryReview(story, run, manuscript, plan, gate);

        assertNotEquals(first, second);
        assertTrue(Files.readString(first).contains("第一次核验超时"));
        assertTrue(Files.readString(second).contains("第二次核验漏项"));
        try (var files = Files.list(run.resolve("delivery-reviews"))) {
            List<Path> observations = files.toList();
            assertEquals(1, observations.size());
            assertEquals("DELIVERY_REVIEW_V1", JSON.parseObject(Files.readString(observations.get(0))).getString("schema"));
        }
        assertTrue(repository.readChapterNumbers(story).isEmpty());
        assertFalse(Files.exists(story.resolve(StorageKeys.MEMORY_DIR).resolve(StorageKeys.AUDIT_SAMPLES_FILE)));
    }

    @Test
    void firstChapterCheckpointRetainsRecoverySettingsAndNeverOverwritesAuthorFiles() throws Exception {
        Path story = story();
        Path run = repository.createRunDirectory(story, null);
        repository.ensureGenerationCheckpoint(story, run, command("原有设定"), new IStoryRepository.StoryMeta(80));
        String bible = repository.readStoryBible(story);
        assertTrue(bible.contains("原有设定"));
        assertEquals(80, repository.readStoryMeta(story).maxChapterCount());
        assertTrue(repository.readChapterNumbers(story).isEmpty());

        repository.ensureGenerationCheckpoint(story, run, command("不应覆盖"), new IStoryRepository.StoryMeta(120));

        assertEquals(bible, repository.readStoryBible(story));
        assertEquals(80, repository.readStoryMeta(story).maxChapterCount());
        assertNoTemporaryFiles(story);
    }

    @Test
    void absentChapterLimitDoesNotOccupyTheStickyCapBeforeItIsEstablished() throws Exception {
        Path story = story();
        Path run = repository.createRunDirectory(story, null);
        repository.ensureGenerationCheckpoint(story, run, command("暂未定总章数"), null);
        assertNull(repository.readStoryMeta(story));
        assertFalse(repository.readStoryBible(story).isBlank());

        repository.ensureGenerationCheckpoint(story, run, command("不能覆盖基础设定"), new IStoryRepository.StoryMeta(90));
        assertEquals(90, repository.readStoryMeta(story).maxChapterCount());
    }

    @Test
    void corruptExistingCheckpointIsNotOverwrittenToPretendRecoveryIsAvailable() throws Exception {
        Path story = story();
        Path run = repository.createRunDirectory(story, null);
        Path meta = story.resolve(StorageKeys.STORY_META_FILE);
        Files.writeString(meta, "not-json");
        assertThrows(Exception.class, () -> repository.ensureGenerationCheckpoint(story, run,
                command("设定"), new IStoryRepository.StoryMeta(80)));
        assertEquals("not-json", Files.readString(meta));
    }

    @Test
    void wrongRunAndWrongChapterCannotEscapeOrBecomeOfficialDrafts() throws Exception {
        Path story = story();
        Path run = repository.createRunDirectory(story, null);
        Path otherStory = workspace.resolve("20261116-story-0002");
        Files.createDirectories(otherStory);
        Path otherRun = repository.createRunDirectory(otherStory, null);
        var manuscript = new ChapterManuscript(1, "交接", "正文");
        var plan = plan(1);
        var gate = unknown(plan, manuscript);
        assertThrows(IOException.class, () -> repository.saveUncommittedChapter(story, otherRun,
                manuscript, plan, gate, "越界"));
        assertThrows(IllegalArgumentException.class, () -> repository.saveUncommittedChapter(story, run,
                manuscript, plan(2), gate, "错章"));
        assertTrue(repository.readChapterNumbers(story).isEmpty());
        assertFalse(Files.exists(otherRun.resolve("uncommitted")));
    }

    private Path story() throws IOException {
        Path story = workspace.resolve("20261116-story-0001");
        Files.createDirectories(story);
        return story;
    }

    private static ArmoryCommandEntity command(String title) {
        return ArmoryCommandEntity.builder().storyContextEntity(StoryContextEntity.builder().novel_title(title).build()).build();
    }

    private static ChapterPlanItemEntity plan(int no) {
        var plan = new ChapterPlanItemEntity();
        plan.setChapterNo(no);
        plan.setSchemaVersion(2);
        plan.setScenes(List.of(new ScenePlan("handoff", List.of("林岚"), "交付药签", "核验印鉴", List.of(),
                "收到回执", "交接", List.of(), "柜台", "午后")));
        return plan;
    }

    private static GateResult unknown(ChapterPlanItemEntity plan, ChapterManuscript manuscript) {
        return GateResult.of(List.of(), 0, 0).withDeliveryReview(DeliveryReviewPolicy.unavailable(
                new DeliveryReviewPolicy.Request(DeliveryReviewPolicy.contract(plan), manuscript, true), "核验未完成"));
    }

    private static void assertNoTemporaryFiles(Path path) throws IOException {
        try (var files = Files.walk(path)) {
            assertFalse(files.anyMatch(file -> file.getFileName().toString().endsWith(".tmp")));
        }
    }
}
