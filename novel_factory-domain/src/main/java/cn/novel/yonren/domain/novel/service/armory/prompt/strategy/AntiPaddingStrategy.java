package cn.novel.yonren.domain.novel.service.armory.prompt.strategy;

import cn.novel.yonren.domain.novel.service.armory.PromptRuleStrategy;
import cn.novel.yonren.domain.novel.service.armory.prompt.CriticalPromptRule;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import cn.novel.yonren.domain.novel.service.armory.prompt.reference.ReferenceSourcePolicy;
import jakarta.annotation.Resource;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptRule;
import cn.novel.yonren.types.enums.PromptScene;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class AntiPaddingStrategy implements PromptRuleStrategy, CriticalPromptRule {

    @Override
    public String assetDescription() {
        return "references/pacing-control.md 与 references/quality-checklist.md 的锚点小节";
    }

    private final PromptRuleFileLoader fileLoader;

    @Resource
    private ReferenceSourcePolicy sourcePolicy;

    private boolean enabled(String id) {
        return sourcePolicy == null || sourcePolicy.isEnabled(id);
    }

    @Override
    public boolean supports(PromptScene scene, PromptContext ctx) {
        return scene == PromptScene.CHAPTER_CONTENT
                && (enabled("pacing-control") || enabled("quality-checklist"));
    }

    @Override
    public List<PromptRule> load(PromptScene scene, PromptContext ctx) {
        String pacing = enabled("pacing-control") ? fileLoader.load("references/pacing-control.md") : null;
        String checklist = enabled("quality-checklist") ? fileLoader.load("references/quality-checklist.md") : null;

        StringBuilder sb = new StringBuilder();
        appendSection(sb, pacing, "## 灌水的定义", "## 矛盾冲突急救法");
        appendSection(sb, pacing, "## 章末自检（防灌水清单）", "## 开篇节奏防崩盘");
        appendSection(sb, checklist, "### 注水检测（长篇小说必查）", "---");

        if (sb.length() == 0) {
            return List.of();
        }
        return List.of(new PromptRule("anti-padding",
                "按场景判断有效内容与冗余，不以此追加逐章任务或数量配额：\n\n" + sb,
                PromptRule.InjectPosition.USER_TAIL));
    }

    /** 按标题锚点抽取小节：[startAnchor, endAnchor)，endAnchor 为空则取到文末；锚点缺失静默跳过 */
    private void appendSection(StringBuilder sb, String content, String startAnchor, String endAnchor) {
        if (StringUtils.isBlank(content)) {
            return;
        }
        int start = content.indexOf(startAnchor);
        if (start < 0) {
            return;
        }
        int end = endAnchor == null ? -1 : content.indexOf(endAnchor, start + startAnchor.length());
        String section = (end < 0 ? content.substring(start) : content.substring(start, end)).trim();
        if (sb.length() > 0) {
            sb.append("\n\n");
        }
        sb.append(section);
    }
}
