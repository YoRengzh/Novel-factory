package cn.novel.yonren.domain.novel.service.armory.prompt.strategy;

import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptRule;
import cn.novel.yonren.types.enums.PromptScene;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 题材资料策略测试：整包装配、场景过滤、DEFAULT 兜底与空内容跳过
 */
class GenreMaterialStrategyTest {

    private PromptRuleFileLoader fileLoader;
    private GenreMaterialStrategy strategy;

    @BeforeEach
    void setUp() {
        fileLoader = mock(PromptRuleFileLoader.class);
        strategy = new GenreMaterialStrategy(fileLoader);
        when(fileLoader.load(anyString())).thenReturn(null);
    }

    @Test
    void fantasyPlanSceneLoadsFullPack() {
        stubFantasy();

        List<PromptRule> rules = strategy.load(PromptScene.CHAPTER_PLAN, ctx("玄幻"));

        // 是玄幻就把四个玄幻相关文件一次装配
        assertEquals(List.of("style-fantasy", "genre-style-fantasy", "genre-arc-fantasy", "genre-exemplar-fantasy"), names(rules));
        rules.forEach(rule -> assertEquals(PromptRule.InjectPosition.USER_TAIL, rule.getInjectPosition()));
    }

    @Test
    void fantasyContentSceneSkipsArcTemplates() {
        stubFantasy();

        List<PromptRule> rules = strategy.load(PromptScene.CHAPTER_CONTENT, ctx("玄幻"));

        // 弧线模板是规划素材，正文场景不注入；范例双场景注入
        assertEquals(List.of("style-fantasy", "genre-style-fantasy", "genre-exemplar-fantasy"), names(rules));
    }

    @Test
    void defaultGenreOnlyFallsBackToDefaultStyle() {
        when(fileLoader.load("styles/default.md")).thenReturn("默认风格内容");

        // "科幻太空"自 起路由 SCIFI（不再落 DEFAULT），default 用例改用不可路由题材
        List<PromptRule> rules = strategy.load(PromptScene.CHAPTER_PLAN, ctx("美食探店"));

        assertEquals(List.of("style-default"), names(rules));
        assertEquals("默认风格内容", rules.get(0).getContent());
    }

    @Test
    void scifiGenreLoadsGenrePack() {
        // 科技流题材包：大纲场景四件全装（风格 x2 + 弧线 + 范例）
        when(fileLoader.load("styles/scifi.md")).thenReturn("科技风格");
        when(fileLoader.load("references/genres/scifi/style-references.md")).thenReturn("科技题材风格");
        when(fileLoader.load("references/genres/scifi/arc-templates.md")).thenReturn("科技弧线");
        when(fileLoader.load("references/genres/scifi/exemplars.md")).thenReturn("科技范例");

        List<PromptRule> rules = strategy.load(PromptScene.CHAPTER_PLAN, ctx("黑科技"));

        assertEquals(List.of("style-scifi", "genre-style-scifi", "genre-arc-scifi", "genre-exemplar-scifi"), names(rules));
    }

    @Test
    void urbanGenreLoadsGenrePack() {
        // 都市现实题材包：此前"都市重生"落 DEFAULT 完全没有题材资料
        when(fileLoader.load("styles/urban.md")).thenReturn("都市风格");
        when(fileLoader.load("references/genres/urban/style-references.md")).thenReturn("都市题材风格");
        when(fileLoader.load("references/genres/urban/arc-templates.md")).thenReturn("都市弧线");
        when(fileLoader.load("references/genres/urban/exemplars.md")).thenReturn("都市范例");

        List<PromptRule> rules = strategy.load(PromptScene.CHAPTER_PLAN, ctx("都市重生"));

        assertEquals(List.of("style-urban", "genre-style-urban", "genre-arc-urban", "genre-exemplar-urban"), names(rules));
    }

    @Test
    void styleFileMissingFallsBackToDefault() {
        when(fileLoader.load("styles/fantasy.md")).thenReturn(null);
        when(fileLoader.load("styles/default.md")).thenReturn("默认风格内容");
        when(fileLoader.load("references/genres/fantasy/style-references.md")).thenReturn("题材风格");
        when(fileLoader.load("references/genres/fantasy/arc-templates.md")).thenReturn("弧线模板");

        List<PromptRule> rules = strategy.load(PromptScene.CHAPTER_PLAN, ctx("玄幻修仙"));

        assertEquals("默认风格内容", byName(rules, "style-fantasy").getContent());
    }

    @Test
    void blankContentEntrySkipped() {
        when(fileLoader.load("styles/fantasy.md")).thenReturn("   ");
        when(fileLoader.load("references/genres/fantasy/style-references.md")).thenReturn("题材风格");

        List<PromptRule> rules = strategy.load(PromptScene.CHAPTER_CONTENT, ctx("玄幻"));

        // 空内容条目不产出规则
        assertEquals(List.of("genre-style-fantasy"), names(rules));
    }

    @Test
    void supportsRequiresContext() {
        assertFalse(strategy.supports(PromptScene.CHAPTER_PLAN, null));
        assertTrue(strategy.supports(PromptScene.CHAPTER_PLAN, ctx("悬疑")));
    }

    private void stubFantasy() {
        when(fileLoader.load("styles/fantasy.md")).thenReturn("风格内容");
        when(fileLoader.load("references/genres/fantasy/style-references.md")).thenReturn("题材风格");
        when(fileLoader.load("references/genres/fantasy/arc-templates.md")).thenReturn("弧线模板");
        when(fileLoader.load("references/genres/fantasy/exemplars.md")).thenReturn("正文范例");
    }

    private PromptContext ctx(String theme) {
        return PromptContext.builder().theme(theme).style(null).build();
    }

    private List<String> names(List<PromptRule> rules) {
        return rules.stream().map(PromptRule::getName).collect(Collectors.toList());
    }

    private PromptRule byName(List<PromptRule> rules, String name) {
        return rules.stream().filter(r -> r.getName().equals(name)).findFirst().orElseThrow();
    }

}
