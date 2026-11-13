package cn.novel.yonren.domain.novel.service.armory.authoring;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.valobj.authoring.*;
import cn.novel.yonren.domain.novel.model.valobj.authoring.StoryAuthoringSnapshot.*;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StoryAuthoringServiceTest {
    private static final String BIBLE = "小说名称: 合成书\n小说类型: 悬疑\n小说格调: 简练\n世界观: 港口\n"
            + "叙述视角: 第三人称\n目标读者: 成年\n语气基调: 克制\n主人公: 林安\n故事概述: 寻找线索\n章节目标: 推进\n";
    private static final String BASIS = "1".repeat(64);

    @Test
    void immutableSnapshotsAndPatchDefensivelyCopyInput() {
        Map<String, String> overrides = new LinkedHashMap<>(Map.of("tone", "明快"));
        List<Entry> entries = new ArrayList<>();
        var unsigned = new StoryAuthoringSnapshot(1, "00000000-0000-0000-0000-000000000001", null, 1, overrides, entries, null);
        overrides.put("apiKey", "never");
        entries.add(intent(3));
        assertEquals(Map.of("tone", "明快"), unsigned.settingsOverrides());
        assertTrue(unsigned.entries().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> unsigned.settingsOverrides().put("tone", "另一值"));
        assertThrows(UnsupportedOperationException.class, () -> unsigned.entries().add(intent(2)));
        var patch = new StoryAuthoringPatch(null, BASIS, overrides, entries, null);
        overrides.clear();
        entries.clear();
        assertEquals(2, patch.settings().size());
        assertEquals(1, patch.entries().size());
    }

    @Test
    void settingsMergeDoesNotChangeBibleOrGenerationCounters() {
        var preview = preview(null, Map.of("tone", "明快", "characterProfiles", ""), null, 1);
        var target = StoryContextEntity.builder().novel_title("合成书").tone("克制").chapterCount(12)
                .worldId("world-kept").characterProfiles("旧人物").build();
        StoryAuthoringService.applySettings(target, preview.candidate());
        assertEquals("明快", target.getTone());
        assertEquals("", target.getCharacterProfiles());
        assertEquals(12, target.getChapterCount());
        assertEquals("world-kept", target.getWorldId());
        var merged = StoryAuthoringService.mergedSettings(BIBLE, preview.candidate());
        assertEquals("港口", merged.get("worldSetting"));
        assertEquals("明快", merged.get("tone"));
        assertEquals(11, merged.size());
    }

    @Test
    void futureIntentDateIsHonoredAndNullPatchDoesNotRetimestampExistingIntent() {
        var first = preview(null, null, List.of(intent(8)), 2);
        assertEquals(2, first.candidate().effectiveFromChapter());
        assertEquals(8, first.candidate().entries().get(0).effectiveFromChapter());
        var nextView = new StoryAuthoringView(StoryAuthoringService.mergedSettings(BIBLE, first.candidate()),
                first.candidate().revision(), BASIS, first.candidate(), first.candidate(), List.of(), 10);
        var unchanged = StoryAuthoringService.preview(nextView,
                new StoryAuthoringPatch(nextView.revision(), BASIS, Map.of("tone", "坚定"), null, null), BIBLE, Map.of(), "freeze");
        assertEquals(8, unchanged.candidate().entries().get(0).effectiveFromChapter());
        assertThrows(IllegalArgumentException.class, () -> preview(null, null, List.of(intent(1)), 2));
        assertEquals(2, preview(null, null, List.of(intent(0)), 2).candidate().entries().get(0).effectiveFromChapter());
        var stored = first.candidate().entries().get(0);
        assertEquals(Status.INTENT, stored.status());
        assertNull(stored.sourceHash());
        assertNull(stored.before());
    }

    @Test
    void fullListMayRetainUnchangedPastIntentButCannotRebaseEditedIntent() {
        var first = preview(null, null, List.of(intent(2)), 2).candidate();
        Entry original = first.entries().get(0);
        var view = new StoryAuthoringView(StoryAuthoringService.mergedSettings(BIBLE, first), first.revision(), BASIS,
                first, first, List.of(), 5);
        var retained = StoryAuthoringService.preview(view, new StoryAuthoringPatch(first.revision(), BASIS,
                Map.of("tone", "冷静"), List.of(original), null), BIBLE, Map.of(), "freeze").candidate();
        assertEquals(original, retained.entries().get(0), "全量回传不把已存在意图重新设为第5章生效");
        var edited = new Entry(original.id(), original.kind(), original.entityName(), original.operation(), null,
                "修改后的下一步行动", original.reason(), null, null, null, 2, original.status());
        assertThrows(IllegalArgumentException.class, () -> StoryAuthoringService.preview(view,
                new StoryAuthoringPatch(first.revision(), BASIS, null, List.of(edited), null), BIBLE, Map.of(), "freeze"));
        var future = new Entry(edited.id(), edited.kind(), edited.entityName(), edited.operation(), null, edited.after(),
                edited.reason(), null, null, null, 5, edited.status());
        assertEquals(5, StoryAuthoringService.preview(view,
                new StoryAuthoringPatch(first.revision(), BASIS, null, List.of(future), null), BIBLE, Map.of(), "freeze")
                .candidate().entries().get(0).effectiveFromChapter());
    }

    @Test
    void correctionProjectionBecomesStaleWhenNewerSourceOrDuplicateNameAppears() {
        var body = new ChapterManuscript(1, "合成", "林安握住了铜钥匙。");
        var summary = ChapterSummaryEntity.builder().chapterNo(1).manuscriptHash(body.hash())
                .characterStates(List.of(new ChapterSummaryEntity.StateEntry("林安", "林安两手空空。", body.content()))).build();
        var sources = StoryAuthoringService.sources(List.of(summary), Map.of(1, body));
        var raw = new Entry(null, Kind.CHARACTER, "林安", Operation.EXTRACTION_CORRECTION, "林安两手空空。",
                body.content(), "纠正提取", 1, body.hash(), body.content(), 0, null);
        var view = new StoryAuthoringView(StoryAuthoringService.mergedSettings(BIBLE, null), null, BASIS,
                null, null, sources, 2);
        var approved = StoryAuthoringService.preview(view, new StoryAuthoringPatch(null, BASIS, null, List.of(raw), null),
                BIBLE, Map.of(1, body), "freeze").candidate().entries().get(0);
        assertTrue(StoryAuthoringService.isCurrentCorrection(approved, List.of(summary)));
        var newer = ChapterSummaryEntity.builder().chapterNo(2).manuscriptHash("2".repeat(64))
                .characterStates(List.of(new ChapterSummaryEntity.StateEntry("林安", "离开港口", null))).build();
        assertFalse(StoryAuthoringService.isCurrentCorrection(approved, List.of(summary, newer)));
        summary.setCharacterStates(List.of(summary.getCharacterStates().get(0), summary.getCharacterStates().get(0)));
        assertFalse(StoryAuthoringService.isCurrentCorrection(approved, List.of(summary)));
        assertNull(StoryAuthoringService.sources(List.of(summary), Map.of(1, body)).get(0).sourceHash());
    }

    @Test
    void invalidSettingsAndTamperedHashesFailClosed() {
        for (String field : List.of("chapterCount", "model", "apiKey", "baseUrl", "maxChapterCount")) {
            assertThrows(IllegalArgumentException.class, () -> preview(null, Map.of(field, "123"), null, 1));
        }
        assertThrows(IllegalArgumentException.class, () -> preview(null, Map.of("worldSetting", ""), null, 1));
        var valid = preview(null, Map.of("tone", "明快"), null, 1).candidate();
        var bad = new StoryAuthoringSnapshot(1, valid.revision(), "0".repeat(64), 1, valid.settingsOverrides(), List.of(), null);
        assertThrows(IllegalArgumentException.class, () -> StoryAuthoringService.validatePersisted(bad));
        assertThrows(IllegalStateException.class, () -> StoryAuthoringService.mergedSettings(BIBLE + "语气基调: 重复\n", null));
    }

    private static StoryAuthoringPreview preview(StoryAuthoringSnapshot current, Map<String, String> settings,
                                                 List<Entry> entries, int next) {
        var view = new StoryAuthoringView(StoryAuthoringService.mergedSettings(BIBLE, current),
                current == null ? null : current.revision(), BASIS, current, current, List.of(), next);
        return StoryAuthoringService.preview(view, new StoryAuthoringPatch(view.revision(), BASIS, settings, entries, null),
                BIBLE, Map.of(), "freeze");
    }

    private static Entry intent(int chapter) {
        return new Entry("forged", Kind.CHARACTER, "林安", Operation.FUTURE_INTENT, "伪当前状态", "下次行动前告别同伴",
                "作者安排", 1, "forged", "伪证据", chapter, Status.AUTHOR_CORRECTED);
    }
}
