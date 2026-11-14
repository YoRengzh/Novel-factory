package cn.novel.yonren.domain.novel.service.armory.character;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.*;
import cn.novel.yonren.domain.novel.model.valobj.character.*;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CharacterStageProfileServiceTest {
    private CharacterDecisionProfile card(String name, String want, boolean locked) {
        return new CharacterDecisionProfile(name, locked, want, null, null, null, null, null, null);
    }

    @Test
    void newStageAddsOnlyExplicitMissingNamesAndKeepsLockedAuthorCard() throws Exception {
        var current = CharacterDecisionProfileService.adoptAuthor(null, List.of(card("林安", "找药", true)), 1);
        var story = StoryContextEntity.builder().characterDepthSettings(CharacterDepthSettings.defaults("APPLY"))
                .characterDecisionProfiles(current).build();
        var stage = StageBlueprintEntity.builder().castFocus(List.of("林安", "主角", "周成", "周成", "陈苗", "未知/条目")).build();
        var designer = mock(CharacterDecisionDesignService.class);
        var repository = mock(IStoryRepository.class);
        when(designer.design(any(), any(), eq(List.of("周成", "陈苗")))).thenReturn(new CharacterDecisionDesignService.Result(
                List.of(card("周成", "补账", false), card("陈苗", "保住差事", false)), "DESIGNED"));
        var service = new CharacterStageProfileService();
        ReflectionTestUtils.setField(service, "designer", designer);
        ReflectionTestUtils.setField(service, "repository", repository);
        service.ensure(ArmoryCommandEntity.builder().storyContextEntity(story).build(),
                DefaultArmoryFactory.DynamicContext.builder().storyDir(Path.of("synthetic-story")).build(), stage, 6);
        assertEquals(3, story.getCharacterDecisionProfiles().profiles().size());
        assertEquals(current.profiles().get(0), story.getCharacterDecisionProfiles().profiles().get(0));
        assertEquals(6, story.getCharacterDecisionProfiles().effectiveFromChapter());
        verify(repository).writeActiveCharacterExecution(eq(Path.of("synthetic-story")), any());
    }

    @Test
    void offObserveAndLegacyApplyDoNotTriggerStageCalls() throws Exception {
        var designer = mock(CharacterDecisionDesignService.class);
        var repository = mock(IStoryRepository.class);
        var service = new CharacterStageProfileService();
        ReflectionTestUtils.setField(service, "designer", designer);
        ReflectionTestUtils.setField(service, "repository", repository);
        var stage = StageBlueprintEntity.builder().castFocus(List.of("周成")).build();
        for (String mode : List.of("OFF", "OBSERVE", "APPLY")) {
            var story = StoryContextEntity.builder().characterDepthMode(mode).build();
            service.ensure(ArmoryCommandEntity.builder().storyContextEntity(story).build(),
                    DefaultArmoryFactory.DynamicContext.builder().build(), stage, 6);
        }
        verifyNoInteractions(designer, repository);
    }

    @Test
    void failedSupplementKeepsOriginalVersionAndDoesNotOverwriteExecution() throws Exception {
        var current = CharacterDecisionProfileService.adoptAuthor(null, List.of(card("林安", "找药", false)), 1);
        var story = StoryContextEntity.builder().characterDepthSettings(CharacterDepthSettings.defaults("APPLY"))
                .characterDecisionProfiles(current).build();
        var designer = mock(CharacterDecisionDesignService.class);
        when(designer.design(any(), any(), anyList())).thenReturn(new CharacterDecisionDesignService.Result(List.of(), "DESIGN_FORMAT_FAILED"));
        var repository = mock(IStoryRepository.class);
        var service = new CharacterStageProfileService();
        ReflectionTestUtils.setField(service, "designer", designer);
        ReflectionTestUtils.setField(service, "repository", repository);
        service.ensure(ArmoryCommandEntity.builder().storyContextEntity(story).build(),
                DefaultArmoryFactory.DynamicContext.builder().build(), StageBlueprintEntity.builder().castFocus(List.of("周成")).build(), 6);
        assertSame(current, story.getCharacterDecisionProfiles());
        verifyNoInteractions(repository);
    }
}
