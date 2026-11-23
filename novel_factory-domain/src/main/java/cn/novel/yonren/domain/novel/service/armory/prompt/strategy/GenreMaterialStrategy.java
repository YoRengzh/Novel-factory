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
import java.util.Locale;

/** 文风优先采用作者明确指定的语域；题材资料按主类及本章任务装配。 */
@Component
@RequiredArgsConstructor
public class GenreMaterialStrategy implements PromptRuleStrategy {

    private static final List<MaterialEntry> MATERIALS = List.of(
            new MaterialEntry("genre-style-%s", "references/genres/%s/style-references.md", false),
            new MaterialEntry("genre-arc-%s", "references/genres/%s/arc-templates.md", true));

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
        if (PromptContext.isDirectWriter(scene, ctx)) {
            return loadDirectStyleOnly(scene, ctx, genres, primary);
        }
        List<PromptRule> rules = new ArrayList<>();
        PromptRule styleRule = loadStyle(scene, ctx, primary);
        if (styleRule != null) {
            rules.add(styleRule);
        }
        for (MaterialEntry entry : MATERIALS) {
            if (entry.planOnly() && scene != PromptScene.CHAPTER_PLAN) {
                continue;
            }
            String ruleName = entry.namePattern().formatted(primary.getCode());
            if (!usagePolicy.allowsRule(ruleName, scene, ctx)) {
                continue;
            }
            String content = fileLoader.load(entry.pathPattern().formatted(primary.getCode()));
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
            if (scene == PromptScene.CHAPTER_CONTENT || scene == PromptScene.CHAPTER_REVISE) {
                content = stripTeachingCommentary(content);
            }
            if (!content.isBlank()) {
                rules.add(new PromptRule("genre-exemplar-" + genre.getCode(), content, PromptRule.InjectPosition.USER_TAIL));
                break;
            }
        }
        return rules;
    }

    /** 轻装正文模式按匹配题材顺次寻找纯正文语感切片（或显式语域卡），跳过题材风格论文与旧版对账范例。 */
    private List<PromptRule> loadDirectStyleOnly(PromptScene scene, PromptContext ctx,
                                                 List<GenreType> genres, GenreType primary) {
        if (explicitRegister(ctx.getStyle()) == null) {
            for (GenreType genre : genres) {
                String anchor = fileLoader.load("styles/anchors/" + genre.getCode() + ".md");
                if (anchor != null && !anchor.isBlank()) {
                    return List.of(new PromptRule("style-anchor-" + genre.getCode(),
                            anchor, PromptRule.InjectPosition.USER_TAIL));
                }
            }
        }
        PromptRule styleRule = loadStyle(scene, ctx, primary);
        return styleRule == null ? List.of() : List.of(styleRule);
    }

    private PromptRule loadStyle(PromptScene scene, PromptContext ctx, GenreType primary) {
        String register = explicitRegister(ctx.getStyle());
        if (register != null) {
            String path = "styles/" + register + ".md";
            if (usagePolicy.allowsFixed(path, scene, ctx)) {
                String content = fileLoader.load(path);
                if (content != null && !content.isBlank()) {
                    return new PromptRule("style-" + register, content, PromptRule.InjectPosition.USER_TAIL);
                }
            }
        }

        String ruleName = "style-" + primary.getCode();
        if (!usagePolicy.allowsRule(ruleName, scene, ctx)) {
            return null;
        }
        String content = fileLoader.load("styles/" + primary.getCode() + ".md");
        if (content == null && primary != GenreType.DEFAULT) {
            content = fileLoader.load("styles/default.md");
        }
        return content == null || content.isBlank()
                ? null : new PromptRule(ruleName, content, PromptRule.InjectPosition.USER_TAIL);
    }

    /** 风格是自由文本，只用明确写出的名称选卡。提及题材、否定某种写法或并列两张卡时沿用原路径。 */
    private static String explicitRegister(String style) {
        if (style == null) {
            return null;
        }
        String selected = null;
        for (String clause : style.split("[,，、;；。\\r\\n]+")) {
            String name = clause.strip().toLowerCase(Locale.ROOT)
                    .replaceFirst("^(?:(?:文风|语域)\\s*[:：]|(?:本书)?(?:采用|使用))\\s*", "");
            String register = switch (name) {
                case "冷峻白描古风", "register-crisp-classical" -> "register-crisp-classical";
                case "传统武侠", "传统武侠风", "传统武侠风格", "传统古典评书风", "古典评书风",
                        "register-traditional-wuxia" -> "register-traditional-wuxia";
                default -> null;
            };
            if (register != null) {
                if (selected != null && !selected.equals(register)) {
                    return null;
                }
                selected = register;
            }
        }
        return selected;
    }

    /** 保留已选中的正文小节，有标签的长范例与无标签的短篇使用同一路径。 */
    static String stripTeachingCommentary(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        boolean skipping = content.lines().anyMatch(line -> line.stripLeading().startsWith("## "));
        for (String line : content.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("## ")) {
                skipping = false;
            }
            if (trimmed.startsWith("【思维链拆解】") || trimmed.startsWith("（要点：")) {
                skipping = true;
            }
            if (!skipping && !trimmed.startsWith("【场景指令】") && !trimmed.equals("【正文片段】")) {
                out.append(line).append("\n");
            }
        }
        return out.toString().trim();
    }

    private record MaterialEntry(String namePattern, String pathPattern, boolean planOnly) { }
}
