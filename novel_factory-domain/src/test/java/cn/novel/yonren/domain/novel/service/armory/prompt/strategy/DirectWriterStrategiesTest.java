package cn.novel.yonren.domain.novel.service.armory.prompt.strategy;

import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import cn.novel.yonren.types.enums.ChapterType;
import cn.novel.yonren.types.enums.PromptScene;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DirectWriterStrategiesTest {

    @Test
    void heavyProseRulesSkipDirectChapterContentWithoutBreakingLegacy() {
        PromptRuleFileLoader loader = mock(PromptRuleFileLoader.class);
        var antiPadding = new AntiPaddingStrategy(loader);
        var contrast = new ContrastExamplesStrategy(loader);
        var hook = new HookTechniqueStrategy(loader);
        var perspective = new PerspectiveDisciplineStrategy(loader);

        PromptContext legacy = PromptContext.builder().chapterType(ChapterType.CLIMAX).directWriterMode(false).build();
        PromptContext direct = PromptContext.builder().chapterType(ChapterType.CLIMAX).directWriterMode(true).build();

        assertTrue(antiPadding.supports(PromptScene.CHAPTER_CONTENT, legacy));
        assertTrue(contrast.supports(PromptScene.CHAPTER_CONTENT, legacy));
        assertTrue(hook.supports(PromptScene.CHAPTER_CONTENT, legacy));
        assertTrue(perspective.supports(PromptScene.CHAPTER_CONTENT, legacy));

        assertFalse(antiPadding.supports(PromptScene.CHAPTER_CONTENT, direct));
        assertFalse(contrast.supports(PromptScene.CHAPTER_CONTENT, direct));
        assertFalse(hook.supports(PromptScene.CHAPTER_CONTENT, direct));
        assertFalse(perspective.supports(PromptScene.CHAPTER_CONTENT, direct));
    }
}
