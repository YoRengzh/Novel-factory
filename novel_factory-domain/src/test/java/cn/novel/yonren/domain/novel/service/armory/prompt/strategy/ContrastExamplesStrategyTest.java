package cn.novel.yonren.domain.novel.service.armory.prompt.strategy;

import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptRule;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.types.enums.PromptScene;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 正文注入表达对照，兼顾压缩重复与保留有用信息。
 */
class ContrastExamplesStrategyTest {

    private final ContrastExamplesStrategy strategy = new ContrastExamplesStrategy(new PromptRuleFileLoader());

    @Test
    void supports_chapterContentOnly() {
        assertTrue(strategy.supports(PromptScene.CHAPTER_CONTENT, null));
        assertFalse(strategy.supports(PromptScene.CHAPTER_PLAN, null));
        assertFalse(strategy.supports(PromptScene.CHAPTER_REVISE, null));
    }

    @Test
    void load_injectsContrastExamples() {
        List<PromptRule> rules = strategy.load(PromptScene.CHAPTER_CONTENT, null);

        assertEquals(1, rules.size());
        String content = rules.get(0).getContent();
        assertTrue(content.contains("动作和对白已经交代了用意"));
        assertTrue(content.contains("人物的判断提供了新信息"));
        assertTrue(content.contains("改写"));
        assertTrue(content.contains("保留"));
        assertEquals(PromptRule.InjectPosition.USER_TAIL, rules.get(0).getInjectPosition());
    }

    @Test
    void deletionOfSharedPackDoesNotSuppressIndependentContrastExamples() {
        var customGenre = PromptContext.builder().theme("书信体").chapterBrief("登门道歉。").build();

        assertEquals(1, strategy.load(PromptScene.CHAPTER_CONTENT, customGenre).size());
    }
}
