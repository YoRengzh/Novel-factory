package cn.novel.yonren.domain.novel.service.armory.prompt.strategy;

import cn.novel.yonren.domain.novel.service.armory.PromptRuleStrategy;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptRule;
import cn.novel.yonren.types.enums.GenreType;
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
        // 题材范例择一（见类注释）：GenreMaterialStrategy 会为非 DEFAULT 题材注入 exemplars.md，
        // 此时本库让位；DEFAULT（无题材范例）时才由本库兜底，保证正文始终有一份可模仿的示例
        if (ctx != null && GenreType.match(ctx.getTheme(), ctx.getStyle()) != GenreType.DEFAULT) {
            return List.of();
        }
        String content = fileLoader.load(FILE);
        if (content == null) {
            return List.of();
        }
        return List.of(new PromptRule("contrast-examples", content, PromptRule.InjectPosition.USER_TAIL));
    }
}
