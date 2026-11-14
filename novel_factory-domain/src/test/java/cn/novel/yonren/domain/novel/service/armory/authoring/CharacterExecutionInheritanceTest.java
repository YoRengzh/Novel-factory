package cn.novel.yonren.domain.novel.service.armory.authoring;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.valobj.authoring.*;
import cn.novel.yonren.domain.novel.model.valobj.character.*;
import cn.novel.yonren.domain.novel.service.armory.character.CharacterDecisionProfileService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CharacterExecutionInheritanceTest {
    private static final String BIBLE = "小说名称: 合成书\n小说类型: 悬疑\n小说格调: 简练\n世界观: 港口\n叙述视角: 第三人称\n目标读者: 成年\n语气基调: 克制\n主人公: 林安\n故事概述: 找线索\n章节目标: 推进\n";

    private CharacterExecutionContext execution(String want, int chapter) {
        var card = new CharacterDecisionProfile("林安", false, want, null, null, null, null, null, null);
        return new CharacterExecutionContext(2, CharacterDepthSettings.defaults("APPLY"),
                CharacterDecisionProfileService.adoptAuthor(null, List.of(card), chapter));
    }

    private StoryAuthoringSnapshot authoring(CharacterExecutionContext execution) {
        var view = new StoryAuthoringView(StoryAuthoringService.mergedSettings(BIBLE, null), null, "a".repeat(64), null, null, List.of(), 1);
        return StoryAuthoringService.preview(view, new StoryAuthoringPatch(null, view.basis(), null, null, "APPLY",
                execution.settings(), execution.profiles().profiles()), BIBLE, Map.of(), "freeze").candidate();
    }

    @Test
    void ordinaryContinuationKeepsNewIndependentStageCardsInsteadOfOldAuthoringCards() throws Exception {
        var old = execution("找药", 1);
        var newer = execution("保住合作", 6);
        var snapshot = authoring(old);
        var repository = mock(IStoryRepository.class);
        var path = Path.of("synthetic-story");
        when(repository.readActiveStoryAuthoring(path)).thenReturn(snapshot);
        when(repository.readActiveCharacterExecution(path)).thenReturn(newer);
        var requested = new StoryContextEntity();
        StoryAuthoringContext.prepare(requested, repository, path, null);
        assertEquals(newer.settings(), requested.getCharacterDepthSettings());
        assertEquals(newer.profiles(), requested.getCharacterDecisionProfiles());
        assertSame(snapshot, requested.getStoryAuthoring());
    }

    @Test
    void recoveryKeepsFrozenCardsEvenIfLiveIndependentVersionChanged() throws Exception {
        var frozen = execution("原计划", 3);
        var snapshot = authoring(execution("维护计划", 1));
        var pending = StoryContextEntity.builder().storyAuthoring(snapshot).characterDepthSettings(frozen.settings())
                .characterDepthMode("APPLY").characterDecisionProfiles(frozen.profiles()).build();
        var repository = mock(IStoryRepository.class);
        var path = Path.of("synthetic-story");
        when(repository.readActiveStoryAuthoring(path)).thenReturn(snapshot);
        var requested = new StoryContextEntity();
        StoryAuthoringContext.prepare(requested, repository, path, pending);
        assertSame(frozen.profiles(), requested.getCharacterDecisionProfiles());
        verify(repository, never()).readActiveCharacterExecution(any());
    }
}
