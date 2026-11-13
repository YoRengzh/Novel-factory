package cn.novel.yonren.domain.novel.service.armory.prompt.strategy;

import cn.novel.yonren.domain.novel.service.armory.PromptRuleStrategy;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptRule;
import cn.novel.yonren.types.enums.GenreType;
import cn.novel.yonren.types.enums.PromptScene;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 题材资料策略：按 theme/style 关键词路由出题材后，将该题材的资料包整包装配——
 * ① styles/{genre}.md（通用风格，DEFAULT 兜底 default.md）
 * ② genres/{genre}/style-references.md（题材风格补充，双场景）
 * ③ genres/{genre}/arc-templates.md（题材弧线模板，仅大纲场景）
 * ④ genres/{genre}/exemplars.md（题材正文范例，双场景）
 * 未命中题材（DEFAULT）时仅注入兜底风格，不注入 genres 资料
 */
@Component
@RequiredArgsConstructor
public class GenreMaterialStrategy implements PromptRuleStrategy {

    private static final MaterialEntry STYLE = new MaterialEntry("style-%s", "styles/%s.md", false, true);
    private static final MaterialEntry GENRE_STYLE = new MaterialEntry("genre-style-%s", "references/genres/%s/style-references.md", false, false);
    private static final MaterialEntry GENRE_ARC = new MaterialEntry("genre-arc-%s", "references/genres/%s/arc-templates.md", true, false);
    private static final MaterialEntry GENRE_EXEMPLAR = new MaterialEntry("genre-exemplar-%s", "references/genres/%s/exemplars.md", false, false);

    private static final List<MaterialEntry> ENTRIES = List.of(STYLE, GENRE_STYLE, GENRE_ARC, GENRE_EXEMPLAR);

    private final PromptRuleFileLoader fileLoader;

    /**
     * 用途资格：固定题材/风格资源与动态资料共用同一张表。
     * 为 null 时保持既有行为（单测直接构造），生产由 Spring 注入。
     */
    private cn.novel.yonren.domain.novel.service.armory.prompt.reference.ReferenceUsagePolicy usagePolicy;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setUsagePolicy(cn.novel.yonren.domain.novel.service.armory.prompt.reference.ReferenceUsagePolicy usagePolicy) {
        this.usagePolicy = usagePolicy;
    }

    @Override
    public boolean supports(PromptScene scene, PromptContext ctx) {
        // 题材资料服务的是"写作与评判"：大纲定方向、正文/修订写出来、审校按题材规范评价。
        // 但**结构化输出场景（阶段蓝图/卷蓝图）不注入** 收窄）——那里要的是 JSON
        // 结构，题材风格补充与正文范例（exemplars）对蓝图没有适用面，只稀释业务约束、干扰结构化输出。
        // 具体条目的场景限制（如 arc-templates 仅大纲）在 load 内按条目取舍
        if (scene == PromptScene.STAGE_BLUEPRINT || scene == PromptScene.VOLUME_BLUEPRINT) {
            return false;
        }
        return ctx != null;
    }

    @Override
    public List<PromptRule> load(PromptScene scene, PromptContext ctx) {
        GenreType genre = GenreType.match(ctx.getTheme(), ctx.getStyle());
        List<PromptRule> rules = new ArrayList<>();
        for (MaterialEntry entry : ENTRIES) {
            if (entry.planOnly() && scene != PromptScene.CHAPTER_PLAN) {
                continue;
            }
            // 无兜底的条目（genres 资料）在 DEFAULT 时不适用，直接跳过
            if (!entry.fallbackDefault() && genre == GenreType.DEFAULT) {
                continue;
            }
            String content = fileLoader.load(String.format(entry.pathPattern(), genre.getCode()));
            if (content == null && entry.fallbackDefault()) {
                content = fileLoader.load("styles/default.md");
            }
            if (content == null || content.isBlank()) {
                continue;
            }
            String ruleName = String.format(entry.namePattern(), genre.getCode());
            // 固定资源同样受用途资格约束；未登记或与场景不匹配时不注入。
            if (usagePolicy != null && !usagePolicy.allowsRule(ruleName, scene, ctx)) {
                continue;
            }
            rules.add(new PromptRule(
                    ruleName,
                    content,
                    PromptRule.InjectPosition.USER_TAIL));
        }
        return rules;
    }

    /**
     * 资料条目定义：名称与路径按题材 code 格式化，差异仅在场景限制与 DEFAULT 兜底行为
     */
    private record MaterialEntry(String namePattern, String pathPattern, boolean planOnly, boolean fallbackDefault) {
    }

}
