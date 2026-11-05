package cn.novel.yonren.domain.novel.service.armory.prompt.strategy;

import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptRule;
import cn.novel.yonren.types.enums.PromptScene;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 写作硬约束注入策略测试（约束归层后新增）。
 *
 * 背景：篇幅/对话的**数量目标**此前直接写在正文 prompt 里，模型为满足数量而注水；
 * 归层后数量目标移入 {@code rules/plan-targets.md} 并**只注入计划场景**，
 * 正文与修订只拿得到定性的 {@code hard-constraints.md}。
 * 这个分界必须锁住——一旦串了，等于绕一圈又回到"用数量驱动正文"。
 */
class HardConstraintStrategyTest {

    private static final String HARD = "rules/hard-constraints.md";
    private static final String TARGETS = "rules/plan-targets.md";

    @Test
    void planSceneGetsQuantityTargets_butWritingScenesDoNot() {
        HardConstraintStrategy strategy = strategyWithBothFiles();

        List<PromptRule> planRules = strategy.load(PromptScene.CHAPTER_PLAN, null);
        assertEquals(2, planRules.size(), "计划场景：硬约束 + 数量目标");
        assertTrue(planRules.stream().anyMatch(r -> TARGETS_FILE_NAME.equals(r.getName())),
                "计划场景必须拿到数量目标");

        List<PromptRule> contentRules = strategy.load(PromptScene.CHAPTER_CONTENT, null);
        assertEquals(1, contentRules.size(), "正文场景只给硬约束");
        assertFalse(contentRules.stream().anyMatch(r -> TARGETS_FILE_NAME.equals(r.getName())),
                "**数量目标不得进正文**——那正是注水的诱因");
        assertTrue(contentRules.get(0).getContent().contains("硬约束正文"));

        List<PromptRule> reviseRules = strategy.load(PromptScene.CHAPTER_REVISE, null);
        assertEquals(1, reviseRules.size(), "修订场景同样只给硬约束");
    }

    @Test
    void supportsOnlyWritingScenes() {
        HardConstraintStrategy strategy = strategyWithBothFiles();

        assertTrue(strategy.supports(PromptScene.CHAPTER_PLAN, null));
        assertTrue(strategy.supports(PromptScene.CHAPTER_CONTENT, null));
        assertTrue(strategy.supports(PromptScene.CHAPTER_REVISE, null));
        assertFalse(strategy.supports(PromptScene.STAGE_BLUEPRINT, null), "蓝图场景不注入写作硬约束");
        assertFalse(strategy.supports(PromptScene.CHAPTER_AUDIT, null), "审校场景不注入写作硬约束");
    }

    @Test
    void missingRuleFilesDegradeSilently() {
        // 资源缺失只应少注入，不该阻断生成
        PromptRuleFileLoader loader = mock(PromptRuleFileLoader.class);
        when(loader.load(anyString())).thenReturn(null);

        assertTrue(new HardConstraintStrategy(loader).load(PromptScene.CHAPTER_PLAN, null).isEmpty());
    }

    @Test
    void missingQuantityTargetsStillKeepsHardConstraints() {
        // plan-targets 缺失时，计划场景仍应拿到 hard-constraints（两者互不牵连）
        PromptRuleFileLoader loader = mock(PromptRuleFileLoader.class);
        when(loader.load(HARD)).thenReturn("硬约束正文");
        when(loader.load(TARGETS)).thenReturn(null);

        List<PromptRule> rules = new HardConstraintStrategy(loader).load(PromptScene.CHAPTER_PLAN, null);

        assertEquals(1, rules.size());
        assertTrue(rules.get(0).getContent().contains("硬约束正文"));
    }

    private static final String TARGETS_FILE_NAME = "plan-targets";

    private HardConstraintStrategy strategyWithBothFiles() {
        PromptRuleFileLoader loader = mock(PromptRuleFileLoader.class);
        when(loader.load(HARD)).thenReturn("硬约束正文");
        when(loader.load(TARGETS)).thenReturn("数量目标正文");
        return new HardConstraintStrategy(loader);
    }
}
