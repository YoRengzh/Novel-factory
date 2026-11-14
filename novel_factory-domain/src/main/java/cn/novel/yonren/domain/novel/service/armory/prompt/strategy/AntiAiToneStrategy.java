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
        return "rules/anti-ai-tone.md 的按场景小节（计划场景需 ## 一、，正文/修订需 ## 一~六、八）";
    }

    private static final String FILE = "rules/anti-ai-tone.md";

    /** 结构 AI 味小节（计划场景唯一需要的一节） */
    private static final String STRUCTURE_SECTION = "## 一、";

    private static final String[] WRITER_SECTIONS = {
            "## 一、", "## 二、", "## 三、", "## 四、", "## 五、", "## 六、", "## 八、", "## 九、", "## 十、"};

    /** 结构子集的作用域提示：说明为何这里只有一小节，避免模型误以为规则缺失 */
    private static final String PLAN_SCOPE_NOTE =
            "（以上是去 AI 味判据中**适用于计划文本**的结构条款：计划的 goal/keyEvents 措辞不得模板化。"
                    + "完整的正文文风判据在写正文与修订时注入，此处不必考虑描写与对话层面的要求。）";

    private final PromptRuleFileLoader fileLoader;

    @Override
    public boolean supports(PromptScene scene, PromptContext ctx) {
        return sectionsOf(scene) != null;
    }

    /** 场景 → 需要注入的小节标题前缀；返回 null 表示该场景不注入本规则 */
    private static String[] sectionsOf(PromptScene scene) {
        if (scene == null) {
            return null;
        }
        return switch (scene) {
            // writer 拿除第七节外的全部；editor（审校）拿全文做逐项对照
            case CHAPTER_CONTENT, CHAPTER_REVISE -> WRITER_SECTIONS;
            case CHAPTER_AUDIT -> new String[]{"## "};
            case CHAPTER_PLAN -> new String[]{STRUCTURE_SECTION};
            // 设定集草稿同为结构化输出且不是"写文案"：反 AI 味的文风判据对产出 JSON 字段无适用面，
            // 只会挤占设定约束（同蓝图场景的收窄理由）
            case STAGE_BLUEPRINT, VOLUME_BLUEPRINT, SETTING_DRAFT -> null;
        };
    }

    /** 审校场景拿整份文件（含第七节速查），其余场景按节抽取 */
    private static boolean isFullText(PromptScene scene) {
        return scene == PromptScene.CHAPTER_AUDIT;
    }

    @Override
    public List<PromptRule> load(PromptScene scene, PromptContext ctx) {
        String[] sections = sectionsOf(scene);
        if (sections == null) {
            return List.of();
        }
        String content;
        if (isFullText(scene)) {
            content = fileLoader.load(FILE);
        } else {
            content = fileLoader.loadSections(FILE, sections);
            if (content != null && scene == PromptScene.CHAPTER_PLAN) {
                content = content + "\n" + PLAN_SCOPE_NOTE;
            }
        }
        if (content == null) {
            return List.of();
        }
        return List.of(new PromptRule("anti-ai-tone", content, PromptRule.InjectPosition.SYSTEM));
    }

}
