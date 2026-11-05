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
 * 悬念钩子规则：仅在写正文、且当前章为卷末(finale)或高潮(climax)时注入
 */
@Component
@RequiredArgsConstructor
public class HookTechniqueStrategy implements PromptRuleStrategy, CriticalPromptRule {

    @Override
    public String assetDescription() {
        return "rules/hook-techniques.md";
    }

    private static final String FILE = "rules/hook-techniques.md";

    private final PromptRuleFileLoader fileLoader;

    @Override
    public boolean supports(PromptScene scene, PromptContext ctx) {
        if (scene != PromptScene.CHAPTER_CONTENT) {
            return false;
        }
        return ctx != null && ctx.getChapterType() != null && ctx.getChapterType().isHookChapter();
    }

    @Override
    public List<PromptRule> load(PromptScene scene, PromptContext ctx) {
        String content = fileLoader.load(FILE);
        if (content == null) {
            return List.of();
        }
        return List.of(new PromptRule("hook-techniques", content, PromptRule.InjectPosition.USER_TAIL));
    }

}
