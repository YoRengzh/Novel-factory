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
    void explicitOutlineProtagonistResolvesIdentityOnlySettingsWithoutGuessing() throws Exception {
        for (String outline : List.of("主角苏尘是青云宗外门弟子。", "主角：苏尘，是青云宗外门弟子。")) {
            var repository = mock(IStoryRepository.class);
            var gateway = mock(LlmGateway.class);
            when(repository.createStoryDirectory()).thenReturn(Path.of("synthetic-story"));
            when(gateway.complete(any(), any())).thenReturn("{\"profiles\":[{\"displayName\":\"苏尘\",\"want\":\"取得入门资格\"}]}");
            var designer = new CharacterDecisionDesignService();
            ReflectionTestUtils.setField(designer, "gateway", gateway);
            var service = planning(repository, designer);
            var config = new StoryVO(); config.setModule(new StoryVO.Module());
            var story = StoryContextEntity.builder().protagonist("身份：青云宗外门弟子")
                    .outline(outline).characterDepthMode("APPLY").build();
            service.ensure(ArmoryCommandEntity.builder().storyVO(config).storyContextEntity(story).build(),
                    DefaultArmoryFactory.DynamicContext.builder().storyContext(outline).build());
            var adopted = story.getCharacterDecisionProfiles().profiles().get(0);
            assertEquals("苏尘", adopted.displayName());
            assertEquals(CharacterDecisionProfile.Source.MODEL_DESIGN, adopted.source());
            assertEquals(CharacterDecisionProfile.Adoption.AUTO_APPLIED, adopted.adoption());
            verify(gateway, times(1)).complete(any(), any());
        }
        assertEquals(List.of("Alex Stone"), CharacterProfilePlanningService.coreNames("Identity: an apprentice", null,
                "The protagonist Alex Stone is an apprentice.", 4));
        assertEquals(List.of("Alex Stone"), CharacterProfilePlanningService.coreNames("Identity: an apprentice", null,
                "Protagonist: Alex Stone, an apprentice.", 4));
    }

    @Test
    void newApplyRejectsMissingAndConflictingProtagonistsBeforeDesignOrPersistence() {
        for (String outline : List.of("苏尘加入青云宗，林舟帮助他。", "主角：苏尘。主角：林舟。", "主角苏尘是弟子。主角林舟是医师。")) {
            var repository = mock(IStoryRepository.class);
            var designer = mock(CharacterDecisionDesignService.class);
            var story = StoryContextEntity.builder().protagonist("身份：青云宗外门弟子")
                    .outline(outline).characterProfiles("林舟：医师").characterDepthMode("APPLY").build();
            var failure = assertThrows(IllegalArgumentException.class, () -> planning(repository, designer).ensure(
                    ArmoryCommandEntity.builder().storyContextEntity(story).build(),
                    DefaultArmoryFactory.DynamicContext.builder().build()));
            assertTrue(failure.getMessage().contains("主角"));
            verifyNoInteractions(designer, repository);
        }
        assertThrows(IllegalArgumentException.class, () -> CharacterProfilePlanningService.coreNames(
                "姓名：苏尘", null, "主角：林舟。", 1));
        assertThrows(IllegalArgumentException.class, () -> CharacterProfilePlanningService.coreNames(
                "姓名：苏尘；姓名：林舟", null, null, 1));
    }

    @Test
    void newApplyCannotContinueWithEmptyOrBlankDesignedCards() {
        for (List<CharacterDecisionProfile> cards : List.<List<CharacterDecisionProfile>>of(List.of(),
                List.of(new CharacterDecisionProfile("林安", null, " \t", null, "", null, null, null, null)))) {
            var repository = mock(IStoryRepository.class);
            var designer = mock(CharacterDecisionDesignService.class);
            when(designer.design(any(), any(), anyList())).thenReturn(new CharacterDecisionDesignService.Result(cards, "DESIGN_EMPTY"));
            var story = StoryContextEntity.builder().protagonist("姓名：林安").characterDepthMode("APPLY").build();
            var failure = assertThrows(IllegalStateException.class, () -> planning(repository, designer).ensure(
                    ArmoryCommandEntity.builder().storyContextEntity(story).build(),
                    DefaultArmoryFactory.DynamicContext.builder().build()));
            assertTrue(failure.getMessage().contains("APPLY"));
            assertNull(story.getCharacterDecisionProfiles());
            verifyNoInteractions(repository);
        }
    }

    @Test
    void newApplyRejectsEmptyBlankDraftAndWrongIdentitySnapshotsWithoutRedesign() {
        var blank = CharacterDecisionProfileService.adoptAuthor(null,
                List.of(new CharacterDecisionProfile("林安", false, " ", null, null, null, null, null, null)), 1);
        var empty = CharacterDecisionProfileService.adoptAuthor(null, List.of(), 1);
        var drafts = CharacterDecisionProfileService.draft(List.of(card("找药")));
        var unsigned = new CharacterDecisionProfileSet(CharacterDecisionProfileSet.SCHEMA_VERSION,
                blank.revision(), null, 1, drafts);
        var draft = new CharacterDecisionProfileSet(unsigned.schemaVersion(), unsigned.revision(),
                CharacterDecisionProfileService.contentHash(unsigned), 1, drafts);
        var wrong = new CharacterDecisionProfile("forged-id", "林安", null, "找药", null, null, null,
                null, null, null, CharacterDecisionProfile.Source.AUTHOR, CharacterDecisionProfile.Adoption.AUTHOR_CONFIRMED);
        var wrongUnsigned = new CharacterDecisionProfileSet(unsigned.schemaVersion(), unsigned.revision(), null, 1, List.of(wrong));
        var wrongIdentity = new CharacterDecisionProfileSet(unsigned.schemaVersion(), unsigned.revision(),
                CharacterDecisionProfileService.contentHash(wrongUnsigned), 1, List.of(wrong));
        for (var set : List.of(blank, empty, draft, wrongIdentity)) {
            var repository = mock(IStoryRepository.class);
            var designer = mock(CharacterDecisionDesignService.class);
            var story = StoryContextEntity.builder().protagonist("林安").characterDepthMode("APPLY")
                    .characterDecisionProfiles(set).build();
            assertThrows(RuntimeException.class, () -> planning(repository, designer).ensure(
                    ArmoryCommandEntity.builder().storyContextEntity(story).build(),
                    DefaultArmoryFactory.DynamicContext.builder().build()));
            assertSame(set, story.getCharacterDecisionProfiles());
            verifyNoInteractions(designer, repository);
        }
    }

    @Test
    void inheritedFrozenApplyWithNoCardsOrBlankCardsDoesNotRebuild() throws Exception {
        for (var profiles : java.util.Arrays.asList(null, CharacterDecisionProfileService.adoptAuthor(null,
                List.of(new CharacterDecisionProfile("林安", false, "", null, null, null, null, null, null)), 1))) {
            var repository = mock(IStoryRepository.class);
            var designer = mock(CharacterDecisionDesignService.class);
            var execution = new CharacterExecutionContext(2, CharacterDepthSettings.defaults("APPLY"), profiles);
            when(repository.readActiveCharacterExecution(any())).thenReturn(execution);
            var story = StoryContextEntity.builder().protagonist("身份：旧档案").build();
            planning(repository, designer).ensure(ArmoryCommandEntity.builder().storyContextEntity(story).build(),
                    DefaultArmoryFactory.DynamicContext.builder().storyDir(Path.of("synthetic-story")).chapterOffset(2).build());
            assertSame(profiles, story.getCharacterDecisionProfiles());
            verifyNoInteractions(designer);
            verify(repository, never()).writeActiveCharacterExecution(any(), any());
            verify(repository, never()).writeStoryBible(any(), any());
        }
    }

    @Test
    void designerRejectsEmptyAndBlankOutputWithAtMostOneRepairButAcceptsOneField() {
        for (String output : List.of("{\"profiles\":[]}", "{\"profiles\":[{\"displayName\":\"林安\",\"want\":\"  \"}]}")) {
            var gateway = mock(LlmGateway.class);
            when(gateway.complete(any(), any())).thenReturn(output);
            var designer = new CharacterDecisionDesignService();
            ReflectionTestUtils.setField(designer, "gateway", gateway);
            var config = new StoryVO(); config.setModule(new StoryVO.Module());
            var result = designer.design(ArmoryCommandEntity.builder().storyVO(config).build(),
                    DefaultArmoryFactory.DynamicContext.builder().build(), List.of("林安"));
            assertTrue(result.profiles().isEmpty());
            assertNotEquals("DESIGNED", result.diagnostic());
            verify(gateway, times(2)).complete(any(), any());
        }
    }

    @Test
    void designerNeverDowngradesWrappedCancellationBudgetOrAuthentication() {
        for (RuntimeException failure : List.of(new java.util.concurrent.CancellationException("cancel"),
                new RuntimeException("wrapped", new java.util.concurrent.CancellationException("cancel")),
                new RuntimeException("wrapped", new cn.novel.yonren.domain.novel.service.armory.llm.LlmLocalRejectionException("budget")),
                new RuntimeException("wrapped", new IllegalStateException("403 forbidden")),
                new IllegalStateException("invalid_api_key"))) {
            var gateway = mock(LlmGateway.class);
            when(gateway.complete(any(), any())).thenThrow(failure);
            var designer = new CharacterDecisionDesignService();
            ReflectionTestUtils.setField(designer, "gateway", gateway);
            var config = new StoryVO(); config.setModule(new StoryVO.Module());
            assertThrows(RuntimeException.class, () -> designer.design(ArmoryCommandEntity.builder().storyVO(config).build(),
                    DefaultArmoryFactory.DynamicContext.builder().build(), List.of("林安")));
            verify(gateway, times(1)).complete(any(), any());
        }
    }

    private static CharacterProfilePlanningService planning(IStoryRepository repository, CharacterDecisionDesignService designer) {
        var service = new CharacterProfilePlanningService();
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "designer", designer);
        return service;
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
