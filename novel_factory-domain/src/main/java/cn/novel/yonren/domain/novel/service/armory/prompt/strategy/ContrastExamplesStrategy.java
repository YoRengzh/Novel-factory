package cn.novel.yonren.domain.novel.service.armory.prompt.strategy;

import cn.novel.yonren.domain.novel.service.armory.PromptRuleStrategy;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptRule;
import cn.novel.yonren.types.enums.PromptScene;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ContrastExamplesStrategy implements PromptRuleStrategy {

    private static final String FILE = "rules/contrast-examples.md";

    private final PromptRuleFileLoader fileLoader;

    @Override
    public boolean supports(PromptScene scene, PromptContext ctx) {
        return scene == PromptScene.CHAPTER_CONTENT;
    }

    @Override
    public List<PromptRule> load(PromptScene scene, PromptContext ctx) {
        String content = fileLoader.load(FILE);
        if (content == null) {
            return List.of();
        }
        return List.of(new PromptRule("contrast-examples", content, PromptRule.InjectPosition.USER_TAIL));
    }
}
