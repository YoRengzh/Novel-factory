package cn.novel.yonren.infrastructure.adapter.repository;

import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterAcceptance;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterCommitState;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 章节提交底座的磁盘回归：只验证仓储自身的事务与恢复语义，不经过生成流程。
 *
 * <p>覆盖四类风险：日志残留后的重放、外部改动冲突、依据漂移与人工编辑回流标记。
 */
class ChapterCommitRepositoryTest {

    @TempDir
    Path workspace;

    private final StoryRepository repository = new StoryRepository();

    @Test
    void commitChapter_writesBodyAndMemoryWithoutJournalResidue() throws Exception {
        Path storyDir = storyDir("20260902-story-0001");

        repository.commitChapter(storyDir, chapter(1, "开端", "正文一"), List.of(summary(1, "梗概一")), null);

        assertEquals("正文一", repository.readChapter(storyDir, 1).getContent());
        assertEquals(1, repository.readChapterSummaries(storyDir).size());
        assertFalse(Files.exists(journal(storyDir)), "提交完成后必须删除日志");
        assertNoTmpResidue(storyDir);
    }

    @Test
    void commitChapter_rejectsChapterWithoutMatchingSummary() throws Exception {
        Path storyDir = storyDir("20260902-story-0002");

        assertThrows(IllegalArgumentException.class, () -> repository.commitChapter(
                storyDir, chapter(1, "开端", "正文一"), List.of(summary(2, "错章摘要")), null));
        assertFalse(Files.exists(storyDir.resolve(StorageKeys.CHAPTER_DIR).resolve("chapter-0001.txt")));
    }

    @Test
    void recoverChapterCommit_replaysInterruptedCommit() throws Exception {
        Path storyDir = storyDir("20260902-story-0003");
        // 模拟崩溃现场：日志已写，正文与摘要都还没落盘。
        Files.createDirectories(storyDir.resolve(StorageKeys.CHAPTER_DIR));
        String journal = "{\"schema\":\"CHAPTER_COMMIT_V1\",\"chapter\":{\"chapterNo\":1,\"title\":\"开端\","
                + "\"content\":\"正文一\"},\"summaries\":[{\"chapterNo\":1,\"title\":\"开端\",\"summary\":\"梗概一\"}]}";
        Files.writeString(journal(storyDir), journal, StandardCharsets.UTF_8);

        repository.recoverChapterCommit(storyDir);

        assertEquals("正文一", repository.readChapter(storyDir, 1).getContent());
        assertEquals("梗概一", repository.readChapterSummaries(storyDir).get(0).getSummary());
        assertFalse(Files.exists(journal(storyDir)), "重放完成后必须删除日志");
    }

    @Test
    void recoverChapterCommit_isNoOpWhenNoJournal() throws Exception {
        Path storyDir = storyDir("20260902-story-0004");

        repository.recoverChapterCommit(storyDir);

        assertTrue(repository.readChapterSummaries(storyDir).isEmpty());
    }

    @Test
    void recoverChapterCommit_rejectsUnknownSchema() throws Exception {
        Path storyDir = storyDir("20260902-story-0005");
        Files.writeString(journal(storyDir), "{\"schema\":\"FUTURE_V9\",\"chapter\":{\"chapterNo\":1}}",
                StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> repository.recoverChapterCommit(storyDir));
        assertTrue(Files.exists(journal(storyDir)), "拒绝重放时日志必须保留供人工检查");
    }

    @Test
    void recoverChapterCommit_rejectsExternallyModifiedBody() throws Exception {
        Path storyDir = storyDir("20260902-story-0006");
        repository.writeChapters(storyDir, List.of(chapter(1, "开端", "原正文")));

        String journal = "{\"schema\":\"CHAPTER_COMMIT_V1\",\"chapter\":{\"chapterNo\":1,\"title\":\"开端\","
                + "\"content\":\"新正文\"},\"summaries\":[{\"chapterNo\":1,\"title\":\"开端\",\"summary\":\"梗概\"}],"
                + "\"guard\":{\"oldBodyHash\":\"" + ChapterManuscript.of(chapter(1, "开端", "原正文")).hash()
                + "\",\"physicalNumbers\":[1],\"sourceHashes\":{}}}";
        Files.writeString(journal(storyDir), journal, StandardCharsets.UTF_8);
        // 日志写入后有人手改了同一章：重放会静默覆盖这次修改。
        repository.writeChapters(storyDir, List.of(chapter(1, "开端", "外部改稿")));

        assertThrows(IllegalStateException.class, () -> repository.recoverChapterCommit(storyDir));
        assertEquals("外部改稿", repository.readChapter(storyDir, 1).getContent());
    }

    @Test
    void chapterWorkBasis_changesWithBodyMemoryAndFormalNumbers() throws Exception {
        Path storyDir = storyDir("20260902-story-0007");
        String initial = repository.chapterWorkBasis(storyDir);

        repository.commitChapter(storyDir, chapter(1, "开端", "正文一"), List.of(summary(1, "梗概一")), null);
        String afterCommit = repository.chapterWorkBasis(storyDir);
        assertNotEquals(initial, afterCommit, "提交后正式历史与摘要都变了，依据必须跟着变");

        assertEquals(afterCommit, repository.chapterWorkBasis(storyDir), "同一磁盘状态必须得到同一依据");
    }

    @Test
    void commitChapter_stateVariantWritesBlueprintsAndStaysReplayable() throws Exception {
        Path storyDir = storyDir("20260902-story-0008");
        StageBlueprintEntity blueprint = new StageBlueprintEntity();
        blueprint.setStageNo(1);
        blueprint.setStartChapter(1);
        blueprint.setEndChapter(10);
        ChapterCommitState state = new ChapterCommitState(List.of(summary(1, "梗概一")), null, null, null, null,
                null, null, List.of(blueprint), null);

        repository.commitChapter(storyDir, chapter(1, "开端", "正文一"), state);

        assertEquals(1, repository.readStageBlueprints(storyDir).size());
        assertFalse(Files.exists(journal(storyDir)));
        assertEquals(List.of(1), repository.readCommittedChapterNumbers(storyDir));
    }

    @Test
    void commitChapter_stateVariantRejectsDiscontinuousSummaries() throws Exception {
        Path storyDir = storyDir("20260902-story-0009");
        ChapterCommitState state = new ChapterCommitState(List.of(summary(1, "梗概一"), summary(3, "跳章")),
                null, null, null, null, null, null, null, null);

        assertThrows(IllegalArgumentException.class,
                () -> repository.commitChapter(storyDir, chapter(1, "开端", "正文一"), state));
    }

    @Test
    void saveHumanEdit_marksPendingReflowAndRecordsAcceptance() throws Exception {
        Path storyDir = storyDir("20260902-story-0010");
        repository.commitChapter(storyDir, chapter(1, "开端", "正文一"), List.of(summary(1, "梗概一")), null);

        repository.saveHumanEdit(storyDir, chapter(1, "开端", "手改正文"),
                new cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository.PendingChapterEdit(1, false));

        assertEquals("手改正文", repository.readChapter(storyDir, 1).getContent());
        var pending = repository.readPendingChapterEdit(storyDir);
        assertNotNull(pending);
        assertEquals(1, pending.fromChapter());
        assertFalse(pending.summariesReady(), "编辑后摘要尚未重建");
        var acceptance = repository.readChapterAcceptance(storyDir, 1);
        assertNotNull(acceptance);
        assertEquals(ChapterAcceptance.Method.HUMAN_EDIT, acceptance.method());
        assertEquals(ChapterManuscript.of(chapter(1, "开端", "手改正文")).hash(), acceptance.manuscriptHash());
        assertFalse(Files.exists(journal(storyDir)));
    }

    @Test
    void saveHumanEdit_keepsEarliestAffectedChapterAcrossRepeatedEdits() throws Exception {
        Path storyDir = storyDir("20260902-story-0011");
        repository.commitChapter(storyDir, chapter(1, "开端", "正文一"), List.of(summary(1, "梗概一")), null);
        repository.commitChapter(storyDir, chapter(2, "推进", "正文二"), List.of(summary(1, "梗概一"), summary(2, "梗概二")), null);

        repository.saveHumanEdit(storyDir, chapter(2, "推进", "手改二"),
                new cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository.PendingChapterEdit(2, false));
        repository.saveHumanEdit(storyDir, chapter(1, "开端", "手改一"),
                new cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository.PendingChapterEdit(1, false));

        assertEquals(1, repository.readPendingChapterEdit(storyDir).fromChapter(), "重复编辑必须收敛到最早受影响章");
    }

    @Test
    void saveHumanEdit_rejectsOutOfRangePendingEdit() throws Exception {
        Path storyDir = storyDir("20260902-story-0012");
        repository.commitChapter(storyDir, chapter(1, "开端", "正文一"), List.of(summary(1, "梗概一")), null);

        assertThrows(IllegalArgumentException.class, () -> repository.saveHumanEdit(storyDir,
                chapter(1, "开端", "手改"),
                new cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository.PendingChapterEdit(2, false)));
    }

    @Test
    void readPendingChapterEdit_rejectsCorruptFile() throws Exception {
        Path storyDir = storyDir("20260902-story-0013");
        Path file = storyDir.resolve(StorageKeys.MEMORY_DIR).resolve(StorageKeys.PENDING_CHAPTER_EDIT_FILE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{not json", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> repository.readPendingChapterEdit(storyDir));
    }

    @Test
    void writePendingChapterEdit_nullClearsMarker() throws Exception {
        Path storyDir = storyDir("20260902-story-0014");
        repository.writePendingChapterEdit(storyDir,
                new cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository.PendingChapterEdit(3, true));
        assertNotNull(repository.readPendingChapterEdit(storyDir));

        repository.writePendingChapterEdit(storyDir, null);

        assertNull(repository.readPendingChapterEdit(storyDir));
    }

    @Test
    void readChapterAcceptance_flagsBasisChangedAfterEarlierHumanEdit() throws Exception {
        Path storyDir = storyDir("20260902-story-0015");
        repository.commitChapter(storyDir, chapter(1, "开端", "正文一"), List.of(summary(1, "梗概一")), null);
        repository.commitChapter(storyDir, chapter(2, "推进", "正文二"), List.of(summary(1, "梗概一"), summary(2, "梗概二")), null);
        assertNull(repository.readChapterAcceptance(storyDir, 2), "未记录验收的章节返回 null");

        repository.saveHumanEdit(storyDir, chapter(1, "开端", "手改一"),
                new cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository.PendingChapterEdit(1, false));

        var earlier = repository.readChapterAcceptance(storyDir, 1);
        assertNotNull(earlier);
        assertFalse(earlier.basisChanged());
    }

    @Test
    void recoverChapterCommit_replaysInterruptedHumanEdit() throws Exception {
        Path storyDir = storyDir("20260902-story-0017");
        repository.commitChapter(storyDir, chapter(1, "开端", "正文一"), List.of(summary(1, "梗概一")), null);
        String oldEpoch = Files.exists(storyDir.resolve(StorageKeys.MEMORY_DIR)
                .resolve(StorageKeys.CHAPTER_WORK_EPOCH_FILE))
                ? Files.readString(storyDir.resolve(StorageKeys.MEMORY_DIR).resolve(StorageKeys.CHAPTER_WORK_EPOCH_FILE))
                : null;
        String hash = ChapterManuscript.of(chapter(1, "开端", "手改一")).hash();

        // 模拟编辑事务在写日志后中断：正文、待回流标记、验收记录都还没落盘。
        String journal = "{\"schema\":\"CHAPTER_COMMIT_V1\",\"chapter\":{\"chapterNo\":1,\"title\":\"开端\","
                + "\"content\":\"手改一\"},\"acceptance\":{\"method\":\"HUMAN_EDIT\",\"manuscriptHash\":\"" + hash
                + "\",\"actor\":\"local-author\",\"reason\":\"正式章节手工保存\",\"acceptedAtMs\":1},"
                + "\"edit\":{\"fromChapter\":1,\"summariesReady\":false},\"epoch\":\"epoch-after-edit\"}";
        Files.writeString(journal(storyDir), journal, StandardCharsets.UTF_8);

        repository.recoverChapterCommit(storyDir);

        assertEquals("手改一", repository.readChapter(storyDir, 1).getContent());
        assertEquals(1, repository.readPendingChapterEdit(storyDir).fromChapter());
        assertNotNull(repository.readChapterAcceptance(storyDir, 1));
        assertEquals("epoch-after-edit", Files.readString(
                storyDir.resolve(StorageKeys.MEMORY_DIR).resolve(StorageKeys.CHAPTER_WORK_EPOCH_FILE)));
        assertFalse(Files.exists(journal(storyDir)));
        assertNotEquals(oldEpoch, "epoch-after-edit", "编辑纪元必须推进，旧稿不能跨编辑提交");
        // 编辑只改正文，不伪造摘要：重放后摘要仍是提交时的版本，等待回流。
        assertEquals("梗概一", repository.readChapterSummaries(storyDir).get(0).getSummary());
    }

    @Test
    void recoverChapterCommit_rejectsEditJournalWithoutAcceptance() throws Exception {
        Path storyDir = storyDir("20260902-story-0018");
        String journal = "{\"schema\":\"CHAPTER_COMMIT_V1\",\"chapter\":{\"chapterNo\":1,\"title\":\"开端\","
                + "\"content\":\"手改一\"},\"edit\":{\"fromChapter\":1,\"summariesReady\":false},\"epoch\":\"e\"}";
        Files.writeString(journal(storyDir), journal, StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> repository.recoverChapterCommit(storyDir));
    }

    @Test
    void commitChapter_leavesNoTmpResidueAnywhere() throws Exception {
        Path storyDir = storyDir("20260902-story-0016");
        repository.commitChapter(storyDir, chapter(1, "开端", "正文一"), List.of(summary(1, "梗概一")), null);

        assertNoTmpResidue(storyDir);
    }

    private Path storyDir(String name) throws IOException {
        Path dir = workspace.resolve(name);
        Files.createDirectories(dir.resolve(StorageKeys.MEMORY_DIR));
        return dir;
    }

    private static Path journal(Path storyDir) {
        return storyDir.resolve(StorageKeys.CHAPTER_COMMIT_FILE);
    }

    private static ChapterContentEntity chapter(int no, String title, String content) {
        ChapterContentEntity entity = new ChapterContentEntity();
        entity.setChapterNo(no);
        entity.setTitle(title);
        entity.setContent(content);
        return entity;
    }

    private static ChapterSummaryEntity summary(int no, String text) {
        ChapterSummaryEntity entity = new ChapterSummaryEntity();
        entity.setChapterNo(no);
        entity.setTitle("第" + no + "章");
        entity.setSummary(text);
        return entity;
    }

    private static void assertNoTmpResidue(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            List<Path> residues = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".tmp")).toList();
            assertTrue(residues.isEmpty(), "残留临时文件：" + residues);
        }
    }
}
