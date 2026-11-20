package cn.novel.yonren.domain.novel.service.armory.prompt.strategy;

import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptBuilder;
import cn.novel.yonren.domain.novel.service.armory.prompt.reference.ReferenceUsagePolicy;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptRule;
import cn.novel.yonren.types.enums.PromptScene;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Optional;
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
        strategy = new GenreMaterialStrategy(fileLoader, new ReferenceUsagePolicy());
        when(fileLoader.load(anyString())).thenReturn(null);
    }

    @Test
    void fantasyPlanLoadsStyleAndArc() {
        stubFantasy();

        List<PromptRule> rules = strategy.load(PromptScene.CHAPTER_PLAN, ctx("玄幻"));

        assertEquals(List.of("style-fantasy", "genre-style-fantasy", "genre-arc-fantasy"), names(rules));
        rules.forEach(rule -> assertEquals(PromptRule.InjectPosition.USER_TAIL, rule.getInjectPosition()));
    }

    @Test
    void fantasyContentSceneSkipsArcTemplates() {
        stubFantasy();

        List<PromptRule> rules = strategy.load(PromptScene.CHAPTER_CONTENT,
                PromptContext.builder().theme("玄幻").chapterBrief("排队领例钱").build());

        // 弧线模板是规划素材，正文场景不注入；范例双场景注入
        assertEquals(List.of("style-fantasy", "genre-style-fantasy", "genre-exemplar-fantasy"), names(rules));
    }

    @ParameterizedTest
    @CsvSource({
            "现代言情, 她第一次主动向对方发出约会邀请。, 桂花酒酿, 股东授权书",
            "现代言情, 他的身份被揭穿，两人在店门口重新谈条件。, 股东授权书, 桂花酒酿",
            "悬疑刑侦, 程野回访死者家，想弄清谁来过。, 男式拖鞋, 监控截图",
            "悬疑刑侦, 刑警拿着监控逐项核对口供。, 监控截图, 男式拖鞋",
            "悬疑刑侦, 本章暂不核对口供，先回访死者家。, 男式拖鞋, 监控截图"
    })
    void rendersOnlyTheProseForThePlannedScene(String theme, String brief, String included, String excluded) {
        String content = fixedExemplar(theme, brief).orElseThrow();
        assertEquals(1, content.lines().filter(line -> line.startsWith("## ")).count());
        assertTrue(content.contains(included));
        assertFalse(content.contains(excluded));
    }

    @Test
    void multipleMatchingScenesKeepSourceOrder() {
        String content = fixedExemplar("悬疑", "本章先核对口供，下午回访死者家。").orElseThrow();
        assertEquals(List.of("## 范例一：日常违和感（线索自然埋设）", "## 范例二：审讯对峙（信息控制）"),
                content.lines().filter(line -> line.startsWith("## ")).toList());
        assertTrue(content.contains("男式拖鞋"));
        assertTrue(content.contains("监控截图"));
    }

    @ParameterizedTest
    @CsvSource({
            "现代言情, 她花了一下午整理发票。",
            "悬疑刑侦, ",
            "悬疑刑侦, 本章暂不核对口供。",
            "现代言情, 本章暂不揭穿身份。",
            "玄幻修仙, 沈砚领取两块竹牌，随后回屋整理床铺。"
    })
    void ordinaryMissingOrDeferredTasksSkipTheGenreExemplar(String theme, String brief) {
        assertTrue(fixedExemplar(theme, brief).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "玄幻修仙, 出门领例钱, 领例钱的队伍",
            "异能, 显露异能, 能力展示",
            "末世, 收留幸存者, 收人的决定",
            "无限流, 规则冲突, 矛盾规则",
            "科幻, 融资谈判, 商业化谈判",
            "都市重生, 全家凑钱, 年代对白"
    })
    void otherGenresLoadTheirRelevantExample(String theme, String brief, String expectedHeading) {
        List<String> headings = fixedExemplar(theme, brief).orElseThrow().lines()
                .filter(line -> line.startsWith("## ")).toList();
        assertEquals(1, headings.size());
        assertTrue(headings.get(0).contains(expectedHeading));
    }

    private Optional<String> fixedExemplar(String theme, String brief) {
        GenreMaterialStrategy registered = new GenreMaterialStrategy(new PromptRuleFileLoader(), new ReferenceUsagePolicy());
        PromptContext context = PromptContext.builder().theme(theme).chapterBrief(brief).build();
        return registered.load(PromptScene.CHAPTER_CONTENT, context).stream()
                .filter(rule -> rule.getName().startsWith("genre-exemplar-"))
                .map(PromptRule::getContent)
                .findFirst();
    }

    @Test
    void writingAndRevisionUseProseWhileAuditKeepsTeachingNotes() {
        GenreMaterialStrategy registered = new GenreMaterialStrategy(new PromptRuleFileLoader(), new ReferenceUsagePolicy());
        PromptContext context = PromptContext.builder().theme("悬疑").chapterBrief("程野回访死者家").build();
        for (PromptScene scene : List.of(PromptScene.CHAPTER_CONTENT, PromptScene.CHAPTER_REVISE, PromptScene.CHAPTER_AUDIT)) {
            String content = registered.load(scene, context).stream()
                    .filter(rule -> rule.getName().equals("genre-exemplar-suspense"))
                    .findFirst().orElseThrow().getContent();
            assertTrue(content.contains("水池里放着两只饭碗"));
            assertEquals(scene == PromptScene.CHAPTER_AUDIT, content.contains("【场景指令】"));
            assertEquals(scene == PromptScene.CHAPTER_AUDIT, content.contains("【思维链拆解】"));
        }
        assertTrue(fixedExemplar("科幻", "深空通信").orElseThrow().contains("她就地坐下"));
    }

    @Test
    void missingGenreExemplarsAreSkippedWithoutChangingStyleOrFallingBackToSharedProse() {
        var registered = new GenreMaterialStrategy(new PromptRuleFileLoader(), new ReferenceUsagePolicy());

        for (String theme : List.of("架空历史", "武侠江湖", "剑与魔法", "战争群像", "网游",
                "体育竞技", "种田", "青春校园", "娱乐圈", "职业文", "航海探险", "校园电竞")) {
            PromptContext context = PromptContext.builder().theme(theme)
                    .chapterBrief("人物处理本章任务并面对后果。").build();
            List<PromptRule> rules = registered.load(PromptScene.CHAPTER_CONTENT, context);
            assertTrue(rules.stream().noneMatch(rule -> rule.getName().startsWith("genre-exemplar-")), theme);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "星际科幻, 两边只能靠深空通信联系。, 远处的回答",
            "民俗怪谈, 调查者开始追查传闻。, 传闻从哪一家开始",
            "科幻末世, 为整栋楼采购物资。, 囤货写过程感"
    })
    void existingGenreExemplarsRemainAvailable(String theme, String brief, String heading) {
        String excerpt = fixedExemplar(theme, brief).orElseThrow();
        List<String> headings = excerpt.lines().filter(line -> line.startsWith("## ")).toList();
        assertEquals(1, headings.size());
        assertTrue(headings.get(0).contains(heading));
    }

    @Test
    void defaultGenreOnlyFallsBackToDefaultStyle() {
        when(fileLoader.load("styles/default.md")).thenReturn("默认风格内容");

        List<PromptRule> rules = strategy.load(PromptScene.CHAPTER_PLAN, ctx("信件体小品"));

        assertEquals(List.of("style-default"), names(rules));
        assertEquals("默认风格内容", rules.get(0).getContent());
    }

    @ParameterizedTest
    @CsvSource({
            "CHAPTER_CONTENT, 武侠, 传统武侠, register-traditional-wuxia, 文体语域：传统古典评书风",
            "CHAPTER_REVISE, 玄幻, 语域：冷峻白描古风，叙事克制, register-crisp-classical, 文体语域：冷峻白描古风",
            "CHAPTER_PLAN, 武侠, 本书采用传统武侠风格, register-traditional-wuxia, 文体语域：传统古典评书风"
    })
    void explicitRegisterReplacesGenreStyleInTheAssembledPrompt(PromptScene scene, String theme, String style,
                                                              String card, String cardTitle) {
        var registered = new GenreMaterialStrategy(new PromptRuleFileLoader(), new ReferenceUsagePolicy());
        var builder = new PromptBuilder(List.of(registered));
        var context = PromptContext.builder().theme(theme).style(style).build();
        String prompt = builder.buildUserTail(scene, context);

        assertTrue(prompt.contains("【style-" + card + "】"));
        assertTrue(prompt.contains(cardTitle));
        assertFalse(prompt.contains("【style-wuxia】"));
        assertFalse(prompt.contains("【style-fantasy】"));
        assertFalse(prompt.contains("【style-default】"));
    }

    @ParameterizedTest
    @CsvSource(value = {"武侠江湖|", "传统武侠|避免传统武侠", "武侠|传统武侠，冷峻白描古风"}, delimiter = '|')
    void genreAloneOrUnclearStyleDoesNotSelectARegister(String theme, String style) {
        when(fileLoader.load("styles/wuxia.md")).thenReturn("作者保存的武侠题材卡");
        var context = PromptContext.builder().theme(theme).style(style).build();
        List<PromptRule> rules = strategy.load(PromptScene.CHAPTER_CONTENT, context);

        assertEquals(List.of("style-wuxia"), names(rules));
        assertEquals("作者保存的武侠题材卡", rules.get(0).getContent());
    }

    @Test
    void explicitRegisterKeepsMatchingGenreExemplar() {
        var registered = new GenreMaterialStrategy(new PromptRuleFileLoader(), new ReferenceUsagePolicy());
        var context = PromptContext.builder().theme("玄幻").style("冷峻白描古风").chapterBrief("排队领例钱").build();
        List<PromptRule> rules = registered.load(PromptScene.CHAPTER_CONTENT, context);

        assertEquals(List.of("style-register-crisp-classical", "genre-style-fantasy", "genre-exemplar-fantasy"),
                names(rules));
    }

    @Test
    void scifiGenreLoadsGenrePack() {
        when(fileLoader.load("styles/scifi.md")).thenReturn("科技风格");
        when(fileLoader.load("references/genres/scifi/style-references.md")).thenReturn("科技题材风格");
        when(fileLoader.load("references/genres/scifi/arc-templates.md")).thenReturn("科技弧线");
        when(fileLoader.load("references/genres/scifi/exemplars.md")).thenReturn("科技范例");

        List<PromptRule> rules = strategy.load(PromptScene.CHAPTER_PLAN, ctx("黑科技"));

        assertEquals(List.of("style-scifi", "genre-style-scifi", "genre-arc-scifi"), names(rules));
    }

    @Test
    void urbanGenreLoadsGenrePack() {
        // 都市现实题材包：此前"都市重生"落 DEFAULT 完全没有题材资料
        when(fileLoader.load("styles/urban.md")).thenReturn("都市风格");
        when(fileLoader.load("references/genres/urban/style-references.md")).thenReturn("都市题材风格");
        when(fileLoader.load("references/genres/urban/arc-templates.md")).thenReturn("都市弧线");
        when(fileLoader.load("references/genres/urban/exemplars.md")).thenReturn("都市范例");

        List<PromptRule> rules = strategy.load(PromptScene.CHAPTER_PLAN, ctx("都市重生"));

        assertEquals(List.of("style-urban", "genre-style-urban", "genre-arc-urban"), names(rules));
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
