package cn.novel.yonren.domain.novel.service.armory.plan;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.valobj.plan.FactReference;
import cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality;
import cn.novel.yonren.domain.novel.model.valobj.plan.ScenePlan;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FactReferenceSourceTest {
    private final Path story = Path.of("synthetic-story");

    private ChapterSummaryEntity summary(int no, String hash, String state, String evidence) {
        return ChapterSummaryEntity.builder().chapterNo(no).manuscriptHash(hash)
                .characterStates(List.of(new ChapterSummaryEntity.StateEntry("甲", state, evidence))).build();
    }

    @Test
    void suppliesExactFormalReferenceWithoutModelOrFutureFacts() throws Exception {
        var repository = mock(IStoryRepository.class);
        var chapter = ChapterContentEntity.builder().chapterNo(1).title("信件").content("甲收到旧信。").build();
        var hash = ChapterManuscript.of(chapter).hash();
        when(repository.readChapter(story, 1)).thenReturn(chapter);
        var references = FactReferenceSource.select(List.of(summary(1, hash, "已收信", "甲收到旧信"),
                summary(3, "future", "已毁信", "甲毁信")), 3, repository, story, 12);
        assertEquals(1, references.size());
        assertEquals(hash, references.get(0).manuscriptHash());
        assertEquals("已收信", references.get(0).status());
        assertTrue(FactReferenceSource.render(references).contains(hash));
        verify(repository, never()).readChapter(story, 3);
    }

    @Test
    void latestUnknownVersionDoesNotFallBackToObsoleteState() throws Exception {
        var repository = mock(IStoryRepository.class);
        var chapter = ChapterContentEntity.builder().chapterNo(1).title("信件").content("甲收到旧信。").build();
        var references = FactReferenceSource.select(List.of(
                summary(1, ChapterManuscript.of(chapter).hash(), "已收信", "甲收到旧信"),
                summary(2, null, "已毁信", "甲毁信")), 3, repository, story, 12);
        assertTrue(references.isEmpty());
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsChangedBodyOrInventedEvidenceAndRespectsBudgetCount() throws Exception {
        var repository = mock(IStoryRepository.class);
        var chapter = ChapterContentEntity.builder().chapterNo(1).title("信件").content("甲收到旧信。").build();
        when(repository.readChapter(story, 1)).thenReturn(chapter);
        assertTrue(FactReferenceSource.select(List.of(summary(1, "old-hash", "已收信", "甲收到旧信")),
                2, repository, story, 12).isEmpty());
        assertTrue(FactReferenceSource.select(List.of(summary(1, ChapterManuscript.of(chapter).hash(),
                "已收信", "正文无此引文")), 2, repository, story, 12).isEmpty());
        assertTrue(FactReferenceSource.select(List.of(summary(1, "h", "状态", "依据")),
                2, repository, story, 0).isEmpty());
    }

    @Test
    void writerVerificationRequiresExactCurrentLedgerAndManuscriptAndReadsOnlyLinkedChapters() throws Exception {
        var repository = mock(IStoryRepository.class);
        var chapter = ChapterContentEntity.builder().chapterNo(1).title("信件").content("甲收到旧信。").build();
        var hash = ChapterManuscript.of(chapter).hash();
        when(repository.readChapter(story, 1)).thenReturn(chapter);
        FactReference exact = new FactReference("CHARACTER", 1, hash, "甲", "已收信", "甲收到旧信");
        FactReference mismatch = new FactReference("CHARACTER", 1, hash, "甲", "已读过旧信", "甲收到旧信");
        FactReference bare = FactReference.fromText("甲读过旧信");
        var scene = new ScenePlan("1", List.of("甲"), "查看旧信", null, List.of(mismatch, bare), "辨识旧信",
                "静场", null, null, null, new SceneCausality(null, null, null, List.of(exact)));
        var plan = ChapterPlanItemEntity.builder().schemaVersion(2).scenes(List.of(scene)).build();
        var unrelated = ChapterSummaryEntity.builder().chapterNo(2).manuscriptHash(hash)
                .itemStates(List.of(new ChapterSummaryEntity.StateEntry("钱袋", "已封存", "封存钱袋"))).build();

        assertEquals(Set.of(exact), FactReferenceSource.verifyForWriting(plan,
                List.of(summary(1, hash, "已收信", "甲收到旧信"), unrelated), 3, repository, story));
        verify(repository).readChapter(story, 1);
        verifyNoMoreInteractions(repository);
        clearInvocations(repository);

        assertTrue(FactReferenceSource.verifyForWriting(plan, List.of(summary(1, hash, "已收信", "甲收到旧信"),
                summary(2, null, "已毁信", "甲毁信")), 3, repository, story).isEmpty(),
                "最新账本版本仍待核验，不能回退到已过时的状态");
        verifyNoInteractions(repository);
    }
}
