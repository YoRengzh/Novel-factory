package cn.novel.yonren.infrastructure.adapter.repository;

import cn.novel.yonren.domain.novel.model.valobj.authoring.*;
import cn.novel.yonren.domain.novel.model.valobj.character.*;
import cn.novel.yonren.domain.novel.service.armory.authoring.StoryAuthoringService;
import cn.novel.yonren.types.enums.CheckpointType;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CharacterExecutionRepositoryTest {
    @TempDir Path directory;
    private final StoryRepository repository = new StoryRepository();
    private static final String BIBLE = "小说名称: 合成书\n小说类型: 悬疑\n小说格调: 简练\n世界观: 港口\n"
            + "叙述视角: 第三人称\n目标读者: 成年\n语气基调: 克制\n主人公: 林安\n故事概述: 找线索\n章节目标: 推进\n";

    private Path story() throws Exception {
        Path story = directory.resolve("20261114-story-0001");
        Files.createDirectories(story.resolve("memory"));
        Files.writeString(story.resolve("story-bible.txt"), BIBLE);
        return story;
    }
    private StoryAuthoringPreview preview(Path story) throws Exception {
        var view = repository.readStoryAuthoringView(story);
        var card = new CharacterDecisionProfile("林安", false, "找药", "接受帮助", null, null, null, null, null);
        return repository.previewStoryAuthoring(story, new StoryAuthoringPatch(view.revision(), view.basis(), null, null,
                "APPLY", CharacterDepthSettings.defaults("APPLY"), List.of(card)));
    }

    @Test
    void absentExecutionDoesNotChangeLegacyBasisAndMaintenanceWritesAllPointers() throws Exception {
        Path story = story();
        String legacy = repository.chapterWorkBasis(story);
        assertNull(repository.readActiveCharacterExecution(story));
        assertEquals(legacy, repository.chapterWorkBasis(story));
        var view = repository.readStoryAuthoringView(story);
        var card = new CharacterDecisionProfile("林安", false, "找药", "接受帮助", null, null, null, null, null);
        var patch = new StoryAuthoringPatch(view.revision(), view.basis(), null, null, "APPLY", null, List.of(card));
        var first = repository.previewStoryAuthoring(story, patch);
        var saved = repository.safeApplyStoryAuthoringPreview(story, patch, first.previewHash());
        assertEquals(saved.snapshot(), saved.activeSnapshot());
        assertEquals(saved.snapshot().characterExecution(), repository.readActiveCharacterExecution(story));
        assertNotEquals(legacy, repository.chapterWorkBasis(story));
        assertFalse(Files.exists(story.resolve(".authoring-commit.json")));
        assertEquals(repository.chapterWorkBasis(story), saved.basis());
        var followUp = new StoryAuthoringPatch(saved.revision(), saved.basis(), Map.of("tone", "坚定"), null, null);
        assertDoesNotThrow(() -> repository.previewStoryAuthoring(story, followUp));
    }

    @Test
    void partialMaintenanceCommitIsNotExposedAndRecoveryUsesFrozenCandidate() throws Exception {
        Path story = story();
        var candidate = preview(story).candidate();
        Files.writeString(story.resolve("story-authoring.json"), JSON.toJSONString(candidate));
        var journal = Map.of("schemaVersion", 1, "candidate", candidate);
        Files.writeString(story.resolve(".authoring-commit.json"), JSON.toJSONString(journal));
        assertThrows(IllegalStateException.class, () -> repository.readStoryAuthoringView(story));
        assertThrows(IllegalStateException.class, () -> repository.readActiveCharacterExecution(story));
        assertThrows(IllegalStateException.class, () -> repository.readActiveStoryAuthoring(story));
        assertThrows(IllegalStateException.class, () -> repository.chapterWorkBasis(story));
        assertThrows(IllegalStateException.class, () -> repository.snapshotCheckpoint(story, CheckpointType.AUTO, null));
        repository.recoverStoryAuthoringCommit(story);
        assertEquals(candidate, repository.readActiveStoryAuthoring(story));
        assertEquals(candidate.characterExecution(), repository.readActiveCharacterExecution(story));
        assertFalse(Files.exists(story.resolve(".authoring-commit.json")));
    }

    @Test
    void rollbackToLegacyCheckpointClearsFutureActiveButKeepsAuthorWorkCopy() throws Exception {
        Path story = story();
        String checkpoint = repository.snapshotCheckpoint(story, CheckpointType.AUTO, null);
        var candidate = preview(story).candidate();
        Files.writeString(story.resolve("story-authoring.json"), JSON.toJSONString(candidate));
        Files.writeString(story.resolve("memory/authoring-context.json"), JSON.toJSONString(candidate));
        repository.writeActiveCharacterExecution(story, candidate.characterExecution());
        repository.restoreCheckpoint(story, checkpoint);
        assertNull(repository.readActiveCharacterExecution(story));
        assertNull(repository.readActiveStoryAuthoring(story));
        assertTrue(Files.exists(story.resolve("story-authoring.json")));
    }

    @Test
    void snapshotRestoresTheOriginalExecutionInsteadOfLatestDesign() throws Exception {
        Path story = story();
        var execution = preview(story).candidate().characterExecution();
        repository.writeActiveCharacterExecution(story, execution);
        String checkpoint = repository.snapshotCheckpoint(story, CheckpointType.AUTO, null);
        repository.writeActiveCharacterExecution(story, new CharacterExecutionContext(2, CharacterDepthSettings.defaults("OBSERVE"), execution.profiles()));
        repository.restoreCheckpoint(story, checkpoint);
        assertEquals(execution, repository.readActiveCharacterExecution(story));
    }
}
