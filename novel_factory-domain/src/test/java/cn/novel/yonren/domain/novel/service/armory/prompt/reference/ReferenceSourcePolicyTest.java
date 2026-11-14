package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ReferenceSourcePolicyTest {

    @TempDir
    Path temp;

    @Test
    void disabledSourcesAndRevisionSurviveRestartIncludingDeletedSource() throws Exception {
        Path file = temp.resolve("state/source-state.json");
        var policy = new ReferenceSourcePolicy(file);
        assertTrue(policy.isEnabled("my-source"));
        assertEquals(0, policy.revision());
        assertFalse(Files.exists(file), "只读默认策略不落盘");

        policy.setEnabled("my-source", false);
        assertEquals(Set.of("my-source"), policy.disabledSources());
        assertEquals(1, policy.revision());
        policy.setEnabled("my-source", false);
        assertEquals(1, policy.revision(), "幂等停用不制造新版本");
        assertThrows(UnsupportedOperationException.class, () -> policy.disabledSources().clear());

        var restarted = new ReferenceSourcePolicy(file);
        assertFalse(restarted.isEnabled("my-source"), "来源不在目录中也保留停用 tombstone");
        assertEquals(1, restarted.revision());
        restarted.setEnabled("my-source", true);
        assertEquals(2, restarted.revision());
        assertTrue(new ReferenceSourcePolicy(file).isEnabled("my-source"));
        try (var files = Files.list(file.getParent())) {
            assertEquals(1, files.count(), "原子替换不遗留临时文件");
        }
    }

    @Test
    void invalidSourceNamesNeverWriteState() {
        Path file = temp.resolve("state.json");
        var policy = new ReferenceSourcePolicy(file);
        for (String id : new String[]{null, "", "../escape", "a/b", "A-name", "x.md", " design-x", "design-x", "a".repeat(61)}) {
            assertThrows(IllegalArgumentException.class, () -> policy.setEnabled(id, false));
            assertThrows(IllegalArgumentException.class, () -> policy.isEnabled(id));
        }
        assertFalse(Files.exists(file));
        assertEquals(0, policy.revision());
    }

    @Test
    void corruptStateFailsClosedInsteadOfSilentlyEnablingEverything() throws Exception {
        Path file = temp.resolve("bad.json");
        Files.writeString(file, "{\"disabledSources\":[\"a\"],\"revision\":-1}");
        assertThrows(IllegalStateException.class, () -> new ReferenceSourcePolicy(file).isEnabled("a"));
        Files.writeString(file, "{\"disabledSources\":[\"a\",\"a\"],\"revision\":1}");
        assertThrows(IllegalStateException.class, () -> new ReferenceSourcePolicy(file).revision());
    }

    @Test
    void persistenceFailureDoesNotPublishTheNewRevision() throws Exception {
        Path parentFile = temp.resolve("not-a-directory");
        Files.writeString(parentFile, "keep");
        var policy = new ReferenceSourcePolicy(parentFile.resolve("state.json"));
        assertEquals(0, policy.revision());
        assertThrows(IllegalStateException.class, () -> policy.setEnabled("source", false));
        assertTrue(policy.isEnabled("source"));
        assertEquals(0, policy.revision());
        assertEquals("keep", Files.readString(parentFile));
    }
}
