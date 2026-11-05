package cn.novel.yonren.domain.novel.service.armory.prompt.strategy;

import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptRule;
import cn.novel.yonren.types.enums.PromptScene;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对照片例库固定注入策略测试：仅正文场景命中、内容含坏→好对照
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
        assertTrue(content.contains("对照片例库"));
        assertTrue(content.contains("✗"), "应含坏例");
        assertTrue(content.contains("✓"), "应含好例");
        assertEquals(PromptRule.InjectPosition.USER_TAIL, rules.get(0).getInjectPosition());
    }
}
