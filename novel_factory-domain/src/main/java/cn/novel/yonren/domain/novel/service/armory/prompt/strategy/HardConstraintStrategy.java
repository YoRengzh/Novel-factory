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


@Component
@RequiredArgsConstructor
public class HardConstraintStrategy implements PromptRuleStrategy, CriticalPromptRule {

    @Override
    public String assetDescription() {
        return "rules/hard-constraints.md（计划场景另需 rules/plan-targets.md）";
    }

    /** 放 rules/ 目录避开 references 索引扫描，防止与动态资料选择重复注入 */
    private static final String FILE = "rules/hard-constraints.md";

    private static final String PLAN_TARGETS_FILE = "rules/plan-targets.md";

    private final PromptRuleFileLoader fileLoader;

    @Override
    public boolean supports(PromptScene scene, PromptContext ctx) {
        // 三类"要落笔"的场景：计划（把约束落成每章安排）、正文（逐条执行）、修订（不得改坏）
        return scene == PromptScene.CHAPTER_PLAN
                || scene == PromptScene.CHAPTER_CONTENT
                || scene == PromptScene.CHAPTER_REVISE;
    }

    @Override
    public List<PromptRule> load(PromptScene scene, PromptContext ctx) {
        List<PromptRule> rules = new java.util.ArrayList<>();
        String content = fileLoader.load(FILE);
        if (content != null) {
            rules.add(new PromptRule("hard-constraints", content, PromptRule.InjectPosition.USER_TAIL));
        }
        if (scene == PromptScene.CHAPTER_PLAN) {
            String targets = fileLoader.load(PLAN_TARGETS_FILE);
            if (targets != null) {
                rules.add(new PromptRule("plan-targets", targets, PromptRule.InjectPosition.USER_TAIL));
            }
        }
        return rules;
    }

}
