package cn.novel.yonren.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StoryRepositoryRebaseTest {
    @TempDir
    Path temp;

    @Test
    void writesCandidateUnderRunWithoutTouchingOriginalPlan() throws Exception {
        Path story = Files.createDirectories(temp.resolve("20261116-story-0001"));
        Path run = Files.createDirectories(story.resolve("generation-records/run-0001"));
        Files.writeString(run.resolve("chapter-plan.yml"), "original-plan");
        var repository = new StoryRepository();

        Path first = repository.writeRebaseRecord(story, run, "3", "PROPOSED", "{\"candidate\":1}");
        Path second = repository.writeRebaseRecord(story, run, "3", "INVALID", "{\"error\":1}");

        assertEquals(run.resolve("rebase"), first.getParent());
        assertNotEquals(first, second, "两次候选/诊断不能互相覆盖");
        assertEquals("{\"candidate\":1}", Files.readString(first));
        assertEquals("original-plan", Files.readString(run.resolve("chapter-plan.yml")));
    }
}
