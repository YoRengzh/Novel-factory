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
public class AntiAiToneStrategy implements PromptRuleStrategy, CriticalPromptRule {

    @Override
    public String assetDescription() {
        return "rules/writer-prose-guide.md（正文/修订）与 rules/anti-ai-tone.md（审校/计划）";
    }

    private static final String FILE = "rules/anti-ai-tone.md";
    private static final String WRITER_FILE = "rules/writer-prose-guide.md";

    /** 结构 AI 味小节（计划场景唯一需要的一节） */
    private static final String STRUCTURE_SECTION = "## 一、";

    private static final String PLAN_SCOPE_NOTE =
            "（以上是适用于计划文本的结构条款。计划的 goal/keyEvents 写清本章实际发生的变化。）";

    private final PromptRuleFileLoader fileLoader;

    @Override
    public boolean supports(PromptScene scene, PromptContext ctx) {
        return scene == PromptScene.CHAPTER_CONTENT || scene == PromptScene.CHAPTER_REVISE
                || scene == PromptScene.CHAPTER_AUDIT || scene == PromptScene.CHAPTER_PLAN;
    }

    @Override
    public List<PromptRule> load(PromptScene scene, PromptContext ctx) {
        if (!supports(scene, ctx)) {
            return List.of();
        }
        String content = switch (scene) {
            case CHAPTER_CONTENT, CHAPTER_REVISE -> fileLoader.load(WRITER_FILE);
            case CHAPTER_AUDIT -> fileLoader.load(FILE);
            case CHAPTER_PLAN -> fileLoader.loadSections(FILE, STRUCTURE_SECTION);
            default -> null;
        };
        if (content == null) {
            return List.of();
        }
        if (scene == PromptScene.CHAPTER_PLAN) {
            content += "\n" + PLAN_SCOPE_NOTE;
        }
        return List.of(new PromptRule("anti-ai-tone", content, PromptRule.InjectPosition.SYSTEM));
    }
}
