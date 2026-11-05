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
 * 视角纪律规则：约束第三人称有限视角的信息边界，
 * 防止主角内心出现其无渠道获知的势力名/情报（哪怕大纲概要中包含）。
 * 仅正文场景注入（越权发生在叙事文本），System Message 位置，确定性注入不进动态选择
 */
@Component
@RequiredArgsConstructor
public class PerspectiveDisciplineStrategy implements PromptRuleStrategy, CriticalPromptRule {

    @Override
    public String assetDescription() {
        return "rules/perspective-discipline.md";
    }

    /** 放 rules/ 目录避开 references 索引扫描，防止与动态资料选择重复注入 */
    private static final String FILE = "rules/perspective-discipline.md";

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
        return List.of(new PromptRule("perspective-discipline", content, PromptRule.InjectPosition.SYSTEM));
    }

}
