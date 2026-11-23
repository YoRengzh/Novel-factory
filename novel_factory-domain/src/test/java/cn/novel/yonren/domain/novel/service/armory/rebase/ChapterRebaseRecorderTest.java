package cn.novel.yonren.domain.novel.service.armory.rebase;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.valobj.rebase.RebaseRecord;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ChapterRebaseRecorderTest {

    private static RebaseRecord record() {
        return new RebaseRecord(RebaseRecord.SCHEMA, "3", "a", "b", "c", "kimi-k3",
                ChapterRebaseService.REVIEW_VERSION, "PROPOSED", java.util.List.of(), java.util.List.of(),
                false, java.util.Map.of());
    }

    @Test
    void writesIntoRunScopedFile() throws Exception {
        var repository = Mockito.mock(IStoryRepository.class);
        Path saved = Path.of("run-0001/rebase/chapter-3-PROPOSED-x.json");
        Mockito.when(repository.writeRebaseRecord(Mockito.any(), Mockito.any(), Mockito.eq("3"),
                Mockito.eq("PROPOSED"), Mockito.anyString())).thenReturn(saved);

        assertEquals(saved, new ChapterRebaseRecorder(repository).record(Path.of("story"), Path.of("run"), record()));
    }

    @Test
    void persistenceFailureDoesNotEscape() throws Exception {
        var repository = Mockito.mock(IStoryRepository.class);
        Mockito.when(repository.writeRebaseRecord(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenThrow(new java.io.IOException("disk full"));

        assertNull(new ChapterRebaseRecorder(repository).record(Path.of("story"), Path.of("run"), record()));
    }

    @Test
    void missingPathsAreIgnored() {
        var recorder = new ChapterRebaseRecorder(Mockito.mock(IStoryRepository.class));
        assertNull(recorder.record(null, Path.of("run"), record()));
        assertNull(recorder.record(Path.of("story"), null, record()));
        assertNull(recorder.record(Path.of("story"), Path.of("run"), null));
    }
}
