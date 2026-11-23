package cn.novel.yonren.domain.novel.service.armory.writer;

import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.valobj.LedgerEntry;
import cn.novel.yonren.domain.novel.service.armory.memory.ChapterMemoryService;
import cn.novel.yonren.domain.novel.service.armory.memory.StoryMemoryService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WriterContextSupportTest {
    @Test
    void suspiciousResurrectionIsNotAnEstablishedOpeningFact() {
        var memory = Mockito.mock(ChapterMemoryService.class);
        Mockito.when(memory.buildCharacterLedger(Mockito.anyList())).thenReturn(List.of(
                new LedgerEntry("沈砚", "恢复行动并走进药铺", 1, 2, "已经死亡", null, true)));
        var item = ChapterPlanItemEntity.builder().chapterNo(3).characters(List.of("沈砚")).build();

        String text = WriterContextSupport.castStates(memory, List.of(), item);

        assertTrue(text.contains("复活尚未确认"));
        assertFalse(text.contains("恢复行动并走进药铺"));
    }

    @Test
    void keepsOnlyCurrentCastLatestState() {
        var memory = Mockito.mock(ChapterMemoryService.class);
        Mockito.when(memory.buildCharacterLedger(Mockito.anyList())).thenReturn(List.of(
                new LedgerEntry("沈砚", "左臂有伤，人在药铺外间", 1, 2, null, null, false),
                new LedgerEntry("周芸", "远在县城另一端", 1, 2, null, null, false)));
        var item = ChapterPlanItemEntity.builder().chapterNo(3).characters(List.of("沈砚")).build();

        String text = WriterContextSupport.castStates(memory, List.of(), item);

        assertTrue(text.contains("左臂有伤"));
        assertFalse(text.contains("县城另一端"));
        assertFalse(text.contains("第2章"));
        assertFalse(text.contains("hash"));
    }

    @Test
    void initialFactsClipsLongSettingsAtSentenceBoundaryAndSkipsAfterChapterOne() {
        String longWorld = "修真界灵气渐枯，各大宗门争夺残存灵脉。".repeat(20);
        var story = cn.novel.yonren.domain.novel.model.entity.StoryContextEntity.builder()
                .worldSetting(longWorld)
                .protagonist("苏尘是个外门杂役。")
                .build();

        String ch1 = WriterContextSupport.initialFacts(story, List.of());
        assertTrue(ch1.contains("【开篇必要设定】"));
        assertTrue(ch1.length() < longWorld.length(), "超长开篇设定应做温和裁剪，避免首章注意力分散");
        assertTrue(ch1.contains("世界与处境：修真界灵气渐枯，各大宗门争夺残存灵脉。"));
        assertTrue(ch1.contains("主人公：苏尘是个外门杂役。"));

        String ch2 = WriterContextSupport.initialFacts(story, List.of(
                cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity.builder().chapterNo(1).summary("已写").build()));
        assertEquals("", ch2);
    }

    @Test
    void filtersOutVolumeSpoilersBibleAndRecentChapterFromMemories() {
        List<StoryMemoryService.RecallHit> hits = List.of(
                new StoryMemoryService.RecallHit("终局叩关飞升天门", 0.95, StoryMemoryService.KIND_VOLUME, 1),
                new StoryMemoryService.RecallHit("修真界五大势力设定", 0.90, StoryMemoryService.KIND_BIBLE, 0),
                new StoryMemoryService.RecallHit("第2章救活青纹藤", 0.85, StoryMemoryService.KIND_CHAPTER, 2),
                new StoryMemoryService.RecallHit("第1章药匣底部残阵线", 0.80, StoryMemoryService.KIND_CHAPTER, 1)
        );

        String memories = WriterContextSupport.memories(hits, 3);

        assertFalse(memories.contains("终局叩关飞升天门"));
        assertFalse(memories.contains("修真界五大势力设定"));
        assertFalse(memories.contains("第2章救活青纹藤"));
        assertTrue(memories.contains("第1章药匣底部残阵线"));
    }
}
