package cn.novel.yonren.domain.novel.service.armory.prompt.strategy;

import cn.novel.yonren.domain.novel.service.armory.PromptRuleStrategy;
import cn.novel.yonren.domain.novel.service.armory.prompt.CriticalPromptRule;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptRule;
import cn.novel.yonren.types.enums.PromptScene;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 剧情结构规则：仅在写大纲（章节计划）场景注入
 */
@Component
@RequiredArgsConstructor
public class PlotStructureStrategy implements PromptRuleStrategy, CriticalPromptRule {

    @Override
    public String assetDescription() {
        return "rules/plot-structures.md";
    }

    private static final String FILE = "rules/plot-structures.md";

    private final PromptRuleFileLoader fileLoader;

    @Override
    public boolean supports(PromptScene scene, PromptContext ctx) {
        return scene == PromptScene.CHAPTER_PLAN;
    }

    @Override
    public List<PromptRule> load(PromptScene scene, PromptContext ctx) {
        String content = fileLoader.load(FILE);
        if (content == null) {
            return List.of();
        }
        return List.of(new PromptRule("plot-structures", content, PromptRule.InjectPosition.USER_TAIL));
    }

}
