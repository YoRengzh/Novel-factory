package cn.novel.yonren.domain.novel.service.armory.prompt.strategy;

import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptRule;
import cn.novel.yonren.types.enums.PromptScene;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 去 AI 味规则的**注入范围**测试。
 *
 * 此前的空白：{@code supports()} 恒真，7637 字符的正文文风判据进了所有 6 个场景（含两份蓝图），
 * 却没有任何测试断言"哪些场景该拿到哪些小节"。本次收窄正是因为缺少这层断言而长期未被发现。
 */
class AntiAiToneStrategyTest {

    private final AntiAiToneStrategy strategy = new AntiAiToneStrategy(new PromptRuleFileLoader());

    private static PromptContext ctx() {
        return PromptContext.builder().build();
    }

    private String contentOf(PromptScene scene) {
        List<PromptRule> rules = strategy.load(scene, ctx());
        assertEquals(1, rules.size(), scene + " 应注入一条规则");
        return rules.get(0).getContent();
    }

    @Test
    @DisplayName("writer（正文/修订）拿除第七节外的全文；editor（审校）拿全文做逐项对照")
    void writerAndEditorScenesGetFullRules() {
        for (PromptScene scene : List.of(PromptScene.CHAPTER_CONTENT, PromptScene.CHAPTER_REVISE,
                PromptScene.CHAPTER_AUDIT)) {
            assertTrue(strategy.supports(scene, ctx()), scene + " 应注入");
            String content = contentOf(scene);
            assertTrue(content.length() > 2500, scene + " 应拿主体规则，实际 " + content.length() + " 字符");
            assertTrue(content.contains("## 二、用词 AI 味"));
            assertTrue(content.contains("## 六、模式化循环"));
        }
        // 第七节自标「editor 逐项对照」且条目与二/三/五节重复：只有 editor 需要，writer 不注入（省 846 字符）
        assertTrue(contentOf(PromptScene.CHAPTER_AUDIT).contains("## 七、模板句式速查"));
        assertFalse(contentOf(PromptScene.CHAPTER_CONTENT).contains("## 七、模板句式速查"));
        assertFalse(contentOf(PromptScene.CHAPTER_REVISE).contains("## 七、模板句式速查"));
    }

    @Test
    @DisplayName("章节计划只拿结构节：计划措辞会决定正文是否模板化，但描写/对话/节奏条款对规划不成立")
    void planSceneGetsStructureSectionOnly() {
        assertTrue(strategy.supports(PromptScene.CHAPTER_PLAN, ctx()));
        String content = contentOf(PromptScene.CHAPTER_PLAN);

        assertTrue(content.contains("## 一、结构 AI 味"));
        assertTrue(content.contains("三段式 / 排比三连"));
        assertTrue(content.contains("适用于计划文本"), "应附作用域说明，避免模型误以为规则缺失");
        // 只对正文成立的节不得进计划层
        assertFalse(content.contains("## 三、描写 AI 味"));
        assertFalse(content.contains("## 四、对话 AI 味"));
        assertFalse(content.contains("## 六、模式化循环"));
        // 第七节交叉引用第二/三/五节，只取其一会造成悬空引用，故刻意不连带
        assertFalse(content.contains("详见第二节"));
        assertTrue(content.length() < 800, "计划场景的注入量应大幅低于全文，实际 " + content.length() + " 字符");
    }

    @Test
    @DisplayName("蓝图场景不注入：结构化 JSON 输出，正文文风规则无适用面")
    void blueprintScenesAreExcluded() {
        for (PromptScene scene : List.of(PromptScene.STAGE_BLUEPRINT, PromptScene.VOLUME_BLUEPRINT)) {
            assertFalse(strategy.supports(scene, ctx()), scene + " 不应注入");
            assertTrue(strategy.load(scene, ctx()).isEmpty());
        }
    }

    @Test
    @DisplayName("注入位置为 SYSTEM，规则名稳定（供复盘定位）")
    void injectsAsSystemRule() {
        PromptRule rule = strategy.load(PromptScene.CHAPTER_CONTENT, ctx()).get(0);

        assertEquals("anti-ai-tone", rule.getName());
        assertEquals(PromptRule.InjectPosition.SYSTEM, rule.getInjectPosition());
        assertNotNull(rule.getContent());
    }

    @Test
    @DisplayName("loadSections 抽节：命中多节、不夹带文件引言、未命中返回 null")
    void loadSectionsExtractsOnlyMatchingBlocks() {
        PromptRuleFileLoader loader = new PromptRuleFileLoader();

        String one = loader.loadSections("rules/anti-ai-tone.md", "## 一、");
        assertTrue(one != null);
        assertTrue(one.startsWith("## 一、"));
        assertFalse(one.contains("# 去 AI 味判据"), "不应带上文件一级标题与引言（引言交叉引用别节）");

        String two = loader.loadSections("rules/anti-ai-tone.md", "## 一、", "## 四、");
        assertTrue(two != null);
        assertTrue(two.contains("## 一、"));
        assertTrue(two.contains("## 四、"));
        assertTrue(two.indexOf("## 一、") < two.indexOf("## 四、"), "按传入顺序输出");

        assertEquals(null, loader.loadSections("rules/anti-ai-tone.md", "## 不存在的节"));
        assertEquals(null, loader.loadSections("rules/not-exist.md", "## 一、"));
        assertEquals(null, loader.loadSections("rules/anti-ai-tone.md"));
    }
}
