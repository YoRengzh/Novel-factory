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
 * 反注水固定注入策略测试：仅正文场景命中、注入内容含注水形态清单与注水检测节、
 * 不含被截断的后续章节（锚点边界正确）
 */
class AntiPaddingStrategyTest {

    private final AntiPaddingStrategy strategy = new AntiPaddingStrategy(new PromptRuleFileLoader());

    @Test
    void supports_chapterContentOnly() {
        assertTrue(strategy.supports(PromptScene.CHAPTER_CONTENT, null));
        assertFalse(strategy.supports(PromptScene.CHAPTER_PLAN, null));
        assertFalse(strategy.supports(PromptScene.CHAPTER_AUDIT, null));
        assertFalse(strategy.supports(PromptScene.STAGE_BLUEPRINT, null));
    }

    @Test
    void load_injectsPaddingSections() {
        List<PromptRule> rules = strategy.load(PromptScene.CHAPTER_CONTENT, null);

        assertEquals(1, rules.size());
        String content = rules.get(0).getContent();
        assertTrue(content.contains("灌水的定义"), "应含 pacing-control 的灌水定义");
        assertTrue(content.contains("水文的高发形态"), "应含注水形态清单");
        assertTrue(content.contains("注水检测"), "应含 quality-checklist 的注水检测节");
        assertTrue(content.contains("防注水纪律"));
    }

    @Test
    void load_sectionBoundaryExcludesFollowingSections() {
        String content = strategy.load(PromptScene.CHAPTER_CONTENT, null).get(0).getContent();

        assertFalse(content.contains("矛盾冲突急救法"), "灌水节后的急救法小节不应被带入");
        assertFalse(content.contains("人物检查"), "注水检测节后的人物检查不应被带入");
    }
}
