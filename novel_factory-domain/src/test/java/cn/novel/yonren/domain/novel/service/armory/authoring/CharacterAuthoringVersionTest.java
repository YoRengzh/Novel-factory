package cn.novel.yonren.domain.novel.service.armory.authoring;

import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.valobj.authoring.*;
import cn.novel.yonren.domain.novel.model.valobj.character.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CharacterAuthoringVersionTest {
    private static final String BIBLE = "小说名称: 合成书\n小说类型: 悬疑\n小说格调: 简练\n世界观: 港口\n"
            + "叙述视角: 第三人称\n目标读者: 成年\n语气基调: 克制\n主人公: 林安\n故事概述: 找线索\n章节目标: 推进\n";
    private static final String BASIS = "a".repeat(64);

    private StoryAuthoringView empty() {
        return new StoryAuthoringView(StoryAuthoringService.mergedSettings(BIBLE, null), null, BASIS, null, null, List.of(), 2);
    }
    private CharacterDecisionProfile card(String want, boolean locked) {
        return new CharacterDecisionProfile("林安", locked, want, "接受帮助", null, null, null, null, null);
    }

    @Test
    void repeatedPreviewsKeepTheSameCharacterCandidateAndBindSourceOnServer() {
        var view = empty();
        var patch = new StoryAuthoringPatch(null, BASIS, null, null, "APPLY", CharacterDepthSettings.defaults("APPLY"), List.of(card("找药", false)));
        var first = StoryAuthoringService.preview(view, patch, BIBLE, Map.of(), "freeze");
        var second = StoryAuthoringService.preview(view, patch, BIBLE, Map.of(), "freeze");
        assertEquals(first, second);
        assertEquals(2, first.candidate().schemaVersion());
        var execution = first.candidate().characterExecution();
        assertEquals("APPLY", execution.settings().mode());
        assertEquals(CharacterDecisionProfile.Source.AUTHOR, execution.profiles().profiles().get(0).source());
        assertEquals(CharacterDecisionProfile.Adoption.AUTHOR_CONFIRMED, execution.profiles().profiles().get(0).adoption());
        assertEquals(2, execution.profiles().effectiveFromChapter());
    }

    @Test
    void settingsOnlyMaintenanceKeepsCharacterVersionAndLockedCardsCannotBeOverwritten() {
        var first = StoryAuthoringService.preview(empty(), new StoryAuthoringPatch(null, BASIS, null, null, "APPLY",
                CharacterDepthSettings.defaults("APPLY"), List.of(card("找药", true))), BIBLE, Map.of(), "freeze").candidate();
        var view = new StoryAuthoringView(StoryAuthoringService.mergedSettings(BIBLE, first), first.revision(), BASIS, first, first, List.of(), 3);
        var next = StoryAuthoringService.preview(view, new StoryAuthoringPatch(first.revision(), BASIS, Map.of("tone", "坚定"), null, null),
                BIBLE, Map.of(), "freeze").candidate();
        assertEquals(first.characterExecution(), next.characterExecution());
        assertThrows(IllegalArgumentException.class, () -> StoryAuthoringService.preview(view,
                new StoryAuthoringPatch(first.revision(), BASIS, null, null, null, null, List.of(card("夺药", true))), BIBLE, Map.of(), "freeze"));
    }

    @Test
    void legacyMaintenanceKeepsItsOriginalSchemaAndNoCharacterExecution() {
        var legacy = StoryAuthoringService.preview(empty(), new StoryAuthoringPatch(null, BASIS, Map.of("tone", "坚定"), null, null),
                BIBLE, Map.of(), "freeze").candidate();
        assertEquals(1, legacy.schemaVersion());
        assertNull(legacy.characterExecution());
        StoryAuthoringService.validatePersisted(legacy);
        assertFalse(com.alibaba.fastjson2.JSON.toJSONString(legacy).contains("characterExecution"));
    }

    @Test
    void savedExecutionRestoresCharacterDesignWithoutTurningItIntoHardFacts() {
        var candidate = StoryAuthoringService.preview(empty(), new StoryAuthoringPatch(null, BASIS, null, null, "APPLY", null, List.of(card("找药", false))),
                BIBLE, Map.of(), "freeze").candidate();
        var story = StoryContextEntity.builder().characterProfiles("原有硬事实").build();
        StoryAuthoringService.applySettings(story, candidate);
        assertEquals("原有硬事实", story.getCharacterProfiles());
        assertEquals(candidate.characterExecution().profiles(), story.getCharacterDecisionProfiles());
        assertEquals("APPLY", CharacterDepthSettings.mode(story));
    }
}
