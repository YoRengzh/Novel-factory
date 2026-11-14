package cn.novel.yonren.domain.novel.service.armory.character;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.*;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.character.*;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CharacterDecisionRuntimeTest {
    private CharacterDecisionProfile card(String want) {
        return new CharacterDecisionProfile("林安", false, want, "接受帮助", null, null, null, null, null);
    }
    @Test
    void offAndObserveDoNotCallDesignerOrWriteExecution() throws Exception {
        for (String mode : List.of("OFF", "OBSERVE")) {
            var service = new CharacterProfilePlanningService();
            var repository = mock(IStoryRepository.class);
            var designer = mock(CharacterDecisionDesignService.class);
            ReflectionTestUtils.setField(service, "repository", repository);
            ReflectionTestUtils.setField(service, "designer", designer);
            var story = StoryContextEntity.builder().protagonist("林安").characterDepthMode(mode).build();
            service.ensure(ArmoryCommandEntity.builder().storyContextEntity(story).build(), DefaultArmoryFactory.DynamicContext.builder().build());
            verifyNoInteractions(designer);
            verify(repository, never()).writeActiveCharacterExecution(any(), any());
        }
    }
    @Test
    void applyDesignsBeforePlanningAndPersistsItsVersion() throws Exception {
        var service = new CharacterProfilePlanningService();
        var repository = mock(IStoryRepository.class);
        var designer = mock(CharacterDecisionDesignService.class);
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "designer", designer);
        when(repository.createStoryDirectory()).thenReturn(Path.of("synthetic-story"));
        when(designer.design(any(), any(), eq(List.of("林安")))).thenReturn(new CharacterDecisionDesignService.Result(List.of(card("找药")), "DESIGNED"));
        var story = StoryContextEntity.builder().protagonist("林安").characterDepthMode("APPLY").build();
        var command = ArmoryCommandEntity.builder().storyContextEntity(story).build();
        var context = DefaultArmoryFactory.DynamicContext.builder().storyContext("主人公：林安").build();
        service.ensure(command, context);
        assertEquals("找药", story.getCharacterDecisionProfiles().profiles().get(0).want());
        verify(repository).writeActiveCharacterExecution(eq(Path.of("synthetic-story")), any());
        verify(repository).writeStoryBible(eq(Path.of("synthetic-story")), same(command));
        String text = new CharacterContextAssembler().assemble(story, List.of("林安"), 1).text();
        assertTrue(text.contains("找药"));
        assertTrue(text.contains(story.getCharacterDecisionProfiles().revision()));
    }
    @Test
    void savedExecutionIsInheritedWithoutRedesign() throws Exception {
        var service = new CharacterProfilePlanningService();
        var repository = mock(IStoryRepository.class);
        var designer = mock(CharacterDecisionDesignService.class);
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "designer", designer);
        var set = CharacterDecisionProfileService.adoptAuthor(null, List.of(card("找药")), 1);
        var execution = new CharacterExecutionContext(2, CharacterDepthSettings.defaults("APPLY"), set);
        when(repository.readActiveCharacterExecution(any())).thenReturn(execution);
        var story = StoryContextEntity.builder().protagonist("林安").build();
        service.ensure(ArmoryCommandEntity.builder().storyContextEntity(story).build(),
                DefaultArmoryFactory.DynamicContext.builder().storyDir(Path.of("synthetic-story")).chapterOffset(2).build());
        assertEquals(set, story.getCharacterDecisionProfiles());
        verifyNoInteractions(designer);
        verify(repository, never()).writeActiveCharacterExecution(any(), any());
    }
    @Test
    void boundedContextNeverCutsAConditionInHalf() {
        var set = CharacterDecisionProfileService.adoptAuthor(null, List.of(card("条件前提。".repeat(200))), 1);
        var settings = new CharacterDepthSettings("APPLY", 800, 600, 4, CharacterDepthSettings.POLICY_VERSION);
        var story = StoryContextEntity.builder().characterDepthSettings(settings).characterDecisionProfiles(set).build();
        var assembly = new CharacterContextAssembler().assemble(story, List.of("林安"), 1);
        assertTrue(assembly.text().length() <= 800);
        assertFalse(assembly.text().contains("条件前提"));
        assertTrue(assembly.diagnostics().stream().anyMatch(value -> value.contains("FIELD_OMITTED")));
    }
    @Test
    void designerDoesAtMostOneFormatRepairAndRejectsUnauthorizedNames() {
        var gateway = mock(LlmGateway.class);
        when(gateway.complete(any(), any())).thenReturn("{bad", "{\"profiles\":[{\"displayName\":\"旁人\",\"want\":\"找药\"}]}");
        var designer = new CharacterDecisionDesignService();
        ReflectionTestUtils.setField(designer, "gateway", gateway);
        var story = new StoryVO(); story.setModule(new StoryVO.Module());
        var result = designer.design(ArmoryCommandEntity.builder().storyVO(story).build(),
                DefaultArmoryFactory.DynamicContext.builder().storyContext("林安").build(), List.of("林安"));
        assertTrue(result.profiles().isEmpty());
        assertEquals("DESIGN_FORMAT_FAILED", result.diagnostic());
        verify(gateway, times(2)).complete(any(), any());
    }
}
