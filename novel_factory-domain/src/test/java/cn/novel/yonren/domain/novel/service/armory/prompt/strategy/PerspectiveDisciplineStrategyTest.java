package cn.novel.yonren.domain.novel.service.armory.prompt.strategy;

import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptRule;
import cn.novel.yonren.types.enums.PromptScene;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 视角纪律策略测试：仅正文场景、System 位置、文件缺失降级
 */
class PerspectiveDisciplineStrategyTest {

    private PromptRuleFileLoader fileLoader;
    private PerspectiveDisciplineStrategy strategy;

    @BeforeEach
    void setUp() {
        fileLoader = mock(PromptRuleFileLoader.class);
        strategy = new PerspectiveDisciplineStrategy(fileLoader);
    }

    @Test
    void contentSceneInjectsAsSystemRule() {
        when(fileLoader.load("rules/perspective-discipline.md")).thenReturn("# 视角纪律");

        List<PromptRule> rules = strategy.load(PromptScene.CHAPTER_CONTENT, PromptContext.builder().build());

        assertEquals(1, rules.size());
        assertEquals("perspective-discipline", rules.get(0).getName());
        assertEquals(PromptRule.InjectPosition.SYSTEM, rules.get(0).getInjectPosition());
    }

    @Test
    void planSceneNotSupported() {
        // 越权发生在叙事文本，大纲场景不注入
        assertFalse(strategy.supports(PromptScene.CHAPTER_PLAN, PromptContext.builder().build()));
        assertTrue(strategy.supports(PromptScene.CHAPTER_CONTENT, PromptContext.builder().build()));
    }

    @Test
    void missingFileFallsBackToEmpty() {
        when(fileLoader.load("rules/perspective-discipline.md")).thenReturn(null);

        assertTrue(strategy.load(PromptScene.CHAPTER_CONTENT, PromptContext.builder().build()).isEmpty());
    }

}
