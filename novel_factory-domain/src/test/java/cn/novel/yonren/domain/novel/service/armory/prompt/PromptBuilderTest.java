package cn.novel.yonren.domain.novel.service.armory.prompt;

import cn.novel.yonren.domain.novel.service.armory.PromptRuleStrategy;
import cn.novel.yonren.domain.novel.service.armory.prompt.strategy.AntiAiToneStrategy;
import cn.novel.yonren.domain.novel.service.armory.prompt.strategy.AntiPaddingStrategy;
import cn.novel.yonren.domain.novel.service.armory.prompt.strategy.HardConstraintStrategy;
import cn.novel.yonren.domain.novel.service.armory.prompt.strategy.HookTechniqueStrategy;
import cn.novel.yonren.domain.novel.service.armory.prompt.strategy.PerspectiveDisciplineStrategy;
import cn.novel.yonren.domain.novel.service.armory.prompt.strategy.PlotStructureStrategy;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptRule;
import cn.novel.yonren.types.enums.ChapterType;
import cn.novel.yonren.types.enums.PromptScene;
import cn.novel.yonren.types.exception.AppException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示词装配器测试：关键规则（{@link CriticalPromptRule}）的缺失必须终止作业，
 * 普通规则的缺失/异常只跳过；并守卫 6 份关键规则资产确实随包可加载（改名/漏打包在 CI 即红）
 */
class PromptBuilderTest {

    /** 假策略：可控是否命中/是否空表/是否抛异常，用于验证装配器的失败语义分级 */
    private static class FakeStrategy implements PromptRuleStrategy {
        private final String label;
        private final boolean supports;
        private final String rule;
        private final boolean throwOnLoad;

        FakeStrategy(String label, boolean supports, String rule, boolean throwOnLoad) {
            this.label = label;
            this.supports = supports;
            this.rule = rule;
            this.throwOnLoad = throwOnLoad;
        }

        @Override
        public boolean supports(PromptScene scene, PromptContext ctx) {
            return supports;
        }

        @Override
        public List<PromptRule> load(PromptScene scene, PromptContext ctx) {
            if (throwOnLoad) {
                throw new IllegalStateException("读盘失败");
            }
            return rule == null ? List.of()
                    : List.of(new PromptRule(label, rule, PromptRule.InjectPosition.SYSTEM));
        }
    }

    private static final class FakeCriticalStrategy extends FakeStrategy implements CriticalPromptRule {
        private final String asset;

        FakeCriticalStrategy(String label, String asset, boolean supports, String rule, boolean throwOnLoad) {
            super(label, supports, rule, throwOnLoad);
            this.asset = asset;
        }

        @Override
        public String assetDescription() {
            return asset;
        }
    }

    private static PromptContext ctx() {
        return PromptContext.builder().theme("都市重生").style("年代成长").build();
    }

    @Test
    void criticalStrategyWithNoRuleTerminatesJob() {
        // 加载器对缺失文件返回 null 而不抛异常 → 策略返回空表：这必须被识别为资产丢失并终止
        PromptBuilder builder = new PromptBuilder(List.of(
                new FakeCriticalStrategy("hard-constraints", "rules/hard-constraints.md", true, null, false)));

        AppException ex = assertThrows(AppException.class,
                () -> builder.buildUserTail(PromptScene.CHAPTER_CONTENT, ctx()));

        assertTrue(ex.getInfo().contains("rules/hard-constraints.md"), "失败信息必须点名缺失的资产：" + ex.getInfo());
        assertTrue(ex.getInfo().contains("作业终止"));
    }

    @Test
    void criticalStrategyLoadFailureTerminatesJob() {
        PromptBuilder builder = new PromptBuilder(List.of(
                new FakeCriticalStrategy("anti-padding", "references/pacing-control.md", true, "内容", true)));

        AppException ex = assertThrows(AppException.class,
                () -> builder.buildUserTail(PromptScene.CHAPTER_CONTENT, ctx()));

        assertTrue(ex.getInfo().contains("references/pacing-control.md"));
    }

    @Test
    void criticalityAppliesOnlyToScenesTheStrategyClaims() {
        // 未命中场景不判缺失：否则"该场景本就不注入"会被误判为资产丢失
        PromptBuilder builder = new PromptBuilder(List.of(
                new FakeCriticalStrategy("plot-structures", "rules/plot-structures.md", false, null, false)));

        assertEquals("", builder.buildUserTail(PromptScene.CHAPTER_PLAN, ctx()));
    }

    @Test
    void nonCriticalStrategyEmptyOrFailingIsSkippedSilently() {
        PromptBuilder builder = new PromptBuilder(List.of(
                new FakeStrategy("genre-material", true, null, false),
                new FakeStrategy("contrast-examples", true, "示例内容", false),
                new FakeStrategy("broken", true, "内容", true)));

        String system = builder.buildSystemMessage(PromptScene.CHAPTER_CONTENT, ctx());

        assertTrue(system.contains("示例内容"), "健康规则仍应注入");
        assertFalse(system.contains("broken"), "失败规则被跳过");
    }

    /**
     * 资产守卫：6 份关键规则全部来自包内 md 文件，文件被改名/标题锚点被改写/打包遗漏都会在此红，
     * 而不是等到运行期生成时才以"缺约束静默跑完"的形式暴露
     */
    @Test
    void plotStructureReferenceDoesNotRequireRepeatedTemplates() {
        PromptBuilder builder = new PromptBuilder(List.of(new PlotStructureStrategy(new PromptRuleFileLoader())));
        String rules = builder.buildUserTail(PromptScene.CHAPTER_PLAN, ctx());
        assertTrue(rules.contains("不强制选一种模板贯穿全书"));
        assertTrue(rules.contains("不固定比例"));
        assertTrue(rules.contains("发现问题→主角轻松解决→旁人震惊→再来一个问题"));
        assertFalse(rules.contains("必须**选定一种**"));
        assertFalse(rules.contains("发展（60%）"));
    }

    @Test
    void realCriticalStrategiesLoadFromPackagedAssets() {
        PromptRuleFileLoader loader = new PromptRuleFileLoader();
        PromptBuilder builder = new PromptBuilder(List.of(
                new HardConstraintStrategy(loader),
                new AntiPaddingStrategy(loader),
                new AntiAiToneStrategy(loader),
                new PerspectiveDisciplineStrategy(loader),
                new PlotStructureStrategy(loader),
                new HookTechniqueStrategy(loader)));

        PromptContext planCtx = PromptContext.builder().theme("都市重生").style("年代成长").build();
        assertFalse(builder.buildUserTail(PromptScene.CHAPTER_PLAN, planCtx).isBlank(),
                "计划场景：硬约束/数量目标/结构规则必须加载到");

        PromptContext hookCtx = PromptContext.builder().theme("都市重生").style("年代成长")
                .chapterType(ChapterType.CLIMAX).build();
        assertFalse(builder.buildSystemMessage(PromptScene.CHAPTER_CONTENT, hookCtx).isBlank(),
                "正文场景：去 AI 味与视角纪律（System 位）必须加载到");
        assertFalse(builder.buildUserTail(PromptScene.CHAPTER_CONTENT, hookCtx).isBlank(),
                "正文场景：硬约束/反注水/钩子技法（User 尾）必须加载到");
    }

}