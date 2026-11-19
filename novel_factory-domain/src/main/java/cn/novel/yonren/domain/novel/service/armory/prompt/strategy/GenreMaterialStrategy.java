package cn.novel.yonren.domain.novel.service.armory.prompt.strategy;

import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptRule;
import cn.novel.yonren.domain.novel.service.armory.PromptRuleStrategy;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import cn.novel.yonren.domain.novel.service.armory.prompt.reference.ReferenceUsagePolicy;
import cn.novel.yonren.types.enums.GenreType;
import cn.novel.yonren.types.enums.PromptScene;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 主文风与弧线取主类；原有题材的正文范例仅在登记且本章匹配时装配。 */
@Component
@RequiredArgsConstructor
public class GenreMaterialStrategy implements PromptRuleStrategy {

    private static final List<MaterialEntry> MATERIALS = List.of(
            new MaterialEntry("style-%s", "styles/%s.md", false, true),
            new MaterialEntry("genre-style-%s", "references/genres/%s/style-references.md", false, false),
            new MaterialEntry("genre-arc-%s", "references/genres/%s/arc-templates.md", true, false));

    static String exemplarPath(String code) {
        return "references/genres/" + code + "/exemplars.md";
    }

    private final PromptRuleFileLoader fileLoader;
    private final ReferenceUsagePolicy usagePolicy;

    @Override
    public boolean supports(PromptScene scene, PromptContext ctx) {
        return ctx != null && scene != PromptScene.STAGE_BLUEPRINT && scene != PromptScene.VOLUME_BLUEPRINT;
    }

    @Override
    public List<PromptRule> load(PromptScene scene, PromptContext ctx) {
        List<GenreType> genres = GenreType.matching(ctx.getTheme(), ctx.getStyle());
        GenreType primary = genres.isEmpty() ? GenreType.DEFAULT : genres.get(0);
        List<PromptRule> rules = new ArrayList<>();
        for (MaterialEntry entry : MATERIALS) {
            if (entry.planOnly() && scene != PromptScene.CHAPTER_PLAN) {
                continue;
            }
            String ruleName = entry.namePattern().formatted(primary.getCode());
            if (!usagePolicy.allowsRule(ruleName, scene, ctx)) {
                continue;
            }
            String content = fileLoader.load(entry.pathPattern().formatted(primary.getCode()));
            if (content == null && entry.fallbackDefault()) {
                content = fileLoader.load("styles/default.md");
            }
            if (content != null && !content.isBlank()) {
                rules.add(new PromptRule(ruleName, content, PromptRule.InjectPosition.USER_TAIL));
            }
        }

        // 只加载已登记的原有题材范例；没有范例资料的题材保持主文风，不注入通用桥段。
        for (GenreType genre : genres) {
            String path = exemplarPath(genre.getCode());
            if (!usagePolicy.allowsFixed(path, scene, ctx)) {
                continue;
            }
            String content = usagePolicy.fixedContent(path, fileLoader.load(path), scene, ctx);
            if (!content.isBlank()) {
                rules.add(new PromptRule("genre-exemplar-" + genre.getCode(), content, PromptRule.InjectPosition.USER_TAIL));
                break;
            }
        }
        return rules;
    }

    private record MaterialEntry(String namePattern, String pathPattern, boolean planOnly, boolean fallbackDefault) { }
}
