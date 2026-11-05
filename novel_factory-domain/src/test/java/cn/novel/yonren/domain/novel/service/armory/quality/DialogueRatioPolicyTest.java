package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对白两维判据测试：行级占比 + 轮次密度，阈值按题材切换。
 * 起因：都市校园恋爱那本占比 25%（勉强合格）但轮次密度只有 7.4/千字（同批 10.0-10.9），
 * 读者反馈"对话太少、张力不足"——单看占比抓不到。
 */
class DialogueRatioPolicyTest {

    @Test
    @DisplayName("轮次计数：一对引号算 1 次，含中文引号与直角引号")
    void countsUtterances() {
        assertEquals(2, DialogueRatioPolicy.utteranceCount("\u201c我来了\u201d他说。\u201c嗯。\u201d"));
        assertEquals(1, DialogueRatioPolicy.utteranceCount("\u300c走吧\u300d"));
        assertEquals(2, DialogueRatioPolicy.utteranceCount("\"Go.\" he said. \"Now.\""));
        assertEquals(0, DialogueRatioPolicy.utteranceCount("他没有说话，只是看着窗外。"));
        assertEquals(0, DialogueRatioPolicy.utteranceCount(null));
    }

    @Test
    @DisplayName("轮次密度 = 轮次 / 千有效字；有效字缺失返回 0（观测层跳过）")
    void computesDensity() {
        assertEquals(10.0, DialogueRatioPolicy.utteranceDensity(15, 1500), 0.001);
        assertEquals(0.0, DialogueRatioPolicy.utteranceDensity(5, null), 0.001);
        assertEquals(0.0, DialogueRatioPolicy.utteranceDensity(5, 0), 0.001);
    }

    @Test
    @DisplayName("题材感知：对话驱动题材（romance）阈值更严")
    void thresholdsSwitchByGenre() {
        assertTrue(DialogueRatioPolicy.isDialogueDriven("romance"));
        assertFalse(DialogueRatioPolicy.isDialogueDriven("fantasy"));
        assertFalse(DialogueRatioPolicy.isDialogueDriven(null));

        // 占比不分题材（实测两题材差异不足）；密度分题材
        assertEquals(0.28, DialogueRatioPolicy.ratioOk(), 0.001);
        assertEquals(0.16, DialogueRatioPolicy.ratioDegraded(), 0.001);
        assertEquals(9.0, DialogueRatioPolicy.densityOk("fantasy"), 0.001);
        assertEquals(11.5, DialogueRatioPolicy.densityOk("romance"), 0.001);
        assertEquals(9.0, DialogueRatioPolicy.densityDegraded("romance"), 0.001);
        // 被评那本实测 8.4：基础阈值下只是 WATCH，恋爱阈值下应判劣化
        assertTrue(8.4 < DialogueRatioPolicy.densityDegraded("romance"), "恋爱题材应把 8.4 判为不足");
        assertTrue(8.4 > DialogueRatioPolicy.densityDegraded("fantasy"), "同数值在玄幻题材下不劣化");
    }

    @Test
    @DisplayName("行级占比照旧：占比高但轮次稀疏是另一维的事")
    void ratioStillWorks() {
        String content = "\u201c很长的一段对白占满了整行而且只有这一次发言\u201d\n他沉默。\n他看着窗外。\n他低下头。";
        assertTrue(DialogueRatioPolicy.ratioOf(content) > 0);
        assertEquals(1, DialogueRatioPolicy.utteranceCount(content), "占比不低但只有 1 轮");
        assertEquals(0.5, DialogueRatioPolicy.ratioOf("\"Wait.\"\nHe stopped."), 0.001,
                "ASCII 双引号行也应计入对白占比");
    }

    @Test
    void formatCollapse_zeroQuotesLongChapter_flags() {
        String content = "他沿着长廊一直走，没有说话。".repeat(120);
        List<ChapterIssueEntity> issues = DialogueRatioPolicy.checkFormatCollapse(content, "normal");
        assertEquals(1, issues.size());
        assertEquals("aesthetic", issues.get(0).getDimension());
        assertTrue(issues.get(0).getDescription().contains("对白格式坍缩"));
    }

    @Test
    void formatCollapse_hasQuotes_passes() {
        String content = ("她对她说：「走吧。」\n她摇了摇头。").repeat(60);
        assertTrue(DialogueRatioPolicy.checkFormatCollapse(content, "normal").isEmpty());
    }

    @Test
    void formatCollapse_transitionChapter_exempt() {
        String content = "他沿着长廊一直走。".repeat(120);
        assertTrue(DialogueRatioPolicy.checkFormatCollapse(content, "transition").isEmpty());
    }

    @Test
    void formatCollapse_shortChapter_exempt() {
        assertTrue(DialogueRatioPolicy.checkFormatCollapse("太短的一段。", "normal").isEmpty());
    }

    @Test
    void bracketDialogue_multiLineSpeech_flags() {
        String content = ("他把碗放下。\n"
                + "（吃饭了，别凉了。）\n"
                + "她没动。\n"
                + "（我跟你说个事，你先坐下。）\n"
                + "窗外有人走过。\n").repeat(20);

        List<ChapterIssueEntity> issues = DialogueRatioPolicy.checkBracketDialogue(content, "normal");

        assertEquals(1, issues.size());
        assertEquals("aesthetic", issues.get(0).getDimension());
        assertEquals("MINOR", issues.get(0).getSeverity());
        assertTrue(issues.get(0).getDescription().contains("括号"));
    }

    @Test
    void bracketDialogue_parentheticalAside_passes() {
        // 旁注不含人称语气线索、长度也短——不得误伤
        String content = ("他推开门。（此时天还没亮。）\n"
                + "屋里很静。（见第3章。）\n"
                + "他坐下来。\n").repeat(40);

        assertTrue(DialogueRatioPolicy.checkBracketDialogue(content, "normal").isEmpty(),
                "说明性旁注不是台词，不应判为括号包对话");
    }

    @Test
    void bracketDialogue_quotedSpeech_passes() {
        String content = ("他把碗放下：「吃饭了。」\n她没动。\n").repeat(60);

        assertTrue(DialogueRatioPolicy.checkBracketDialogue(content, "normal").isEmpty());
    }

    @Test
    void bracketDialogue_singleOccurrence_passes() {
        // 单行可能是排版偶然，不足以判系统性写法
        String content = "他把碗放下。\n（吃饭了，先坐下。）\n"
                + "她没动。\n他也没再说话。\n".repeat(80);

        assertTrue(DialogueRatioPolicy.checkBracketDialogue(content, "normal").isEmpty());
    }

    @Test
    void bracketDialogue_shortChapter_exempt() {
        assertTrue(DialogueRatioPolicy.checkBracketDialogue("（走吧。）", "normal").isEmpty());
    }
}
