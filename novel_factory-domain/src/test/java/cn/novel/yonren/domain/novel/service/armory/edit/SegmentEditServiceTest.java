package cn.novel.yonren.domain.novel.service.armory.edit;

import cn.novel.yonren.api.dto.SegmentEditRequestDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 段落级编辑测试：三类动作的 prompt 差异、上下文截断方向、模型输出清洗。
 *
 * <p>只测纯函数（buildPrompt / cleanOutput）——真正的 LLM 调用在端到端冒烟里验证，
 * 单测里打桩模型只会测到 mock 本身。
 */
class SegmentEditServiceTest {

    private static SegmentEditRequestDTO request(String action, String selected, String instruction,
                                                 String before, String after) {
        SegmentEditRequestDTO r = new SegmentEditRequestDTO();
        r.setAction(action);
        r.setSelectedText(selected);
        r.setInstruction(instruction);
        r.setContextBefore(before);
        r.setContextAfter(after);
        return r;
    }

    @Test
    @DisplayName("润色：要求不改变篇幅量级、不新增情节事实")
    void polishPromptKeepsFactsAndScale() {
        String prompt = SegmentEditService.buildPrompt(
                SegmentEditService.ACTION_POLISH, "他推开门，屋里很暗。", request("polish", "", null, null, null));

        assertTrue(prompt.contains("润色"));
        assertTrue(prompt.contains("不改变篇幅量级"), "润色不得大幅改变长度");
        assertTrue(prompt.contains("不新增情节事实"), "润色不得编造剧情");
        assertTrue(prompt.contains("他推开门，屋里很暗。"), "选中原文必须出现在 prompt 里");
        assertTrue(prompt.contains("只输出改写后的这一段文字本身"), "输出契约必须明确");
    }

    @Test
    @DisplayName("扩写：给出 1.5–3 倍篇幅目标，且禁止靠形容词堆砌")
    void expandPromptSpecifiesLengthAndAntiPadding() {
        String prompt = SegmentEditService.buildPrompt(
                SegmentEditService.ACTION_EXPAND, "他推开门。", request("expand", "", null, null, null));

        assertTrue(prompt.contains("扩写"));
        assertTrue(prompt.contains("1.5–3 倍"), "必须给出可执行的长度目标");
        assertTrue(prompt.contains("不要靠形容词堆砌"), "防注水");
        assertTrue(prompt.contains("不新增情节转折"), "扩写不得改变剧情走向");
    }

    @Test
    @DisplayName("续写：承接上文、不提前解决矛盾、不引入无关新人物")
    void continuePromptKeepsContinuity() {
        String prompt = SegmentEditService.buildPrompt(
                SegmentEditService.ACTION_CONTINUE, "他停在门口。", request("continue", "", null, null, null));

        assertTrue(prompt.contains("续写"));
        assertTrue(prompt.contains("承接上文的动作或话题"));
        assertTrue(prompt.contains("不要把矛盾提前解决"));
        assertTrue(prompt.contains("不要引入与上文无关的新人物"));
    }

    @Test
    @DisplayName("上下文：前文保留尾部、后文保留头部，且明确声明不要改写上下文")
    void contextTruncationKeepsNearSide() {
        String longBefore = "甲".repeat(1000) + "紧邻前文";
        String longAfter = "紧邻后文" + "乙".repeat(1000);
        String prompt = SegmentEditService.buildPrompt("polish", "选中的句子",
                request("polish", "", null, longBefore, longAfter));

        assertTrue(prompt.contains("不要改写或输出这部分"), "必须声明上下文只供理解");
        // 前文截断保留尾部：紧邻选中片段的那一侧不能丢
        assertTrue(prompt.contains("紧邻前文"), "前文应保留靠近选中的尾部");
        assertTrue(prompt.contains("紧邻后文"), "后文应保留靠近选中的头部");
        assertFalse(prompt.contains("甲".repeat(700)), "超长前文应被截断");
        assertFalse(prompt.contains("乙".repeat(700)), "超长后文应被截断");
    }

    @Test
    @DisplayName("用户附加要求出现在 prompt 且声明优先级高于默认倾向")
    void instructionOverridesDefaultTendency() {
        String prompt = SegmentEditService.buildPrompt("polish", "句子",
                request("polish", "", "再冷峻一点，少用形容词", null, null));

        assertTrue(prompt.contains("再冷峻一点，少用形容词"));
        assertTrue(prompt.contains("优先于上面的默认倾向"));
    }

    @Test
    @DisplayName("清洗：去掉代码围栏、包裹引号、引导语前缀")
    void cleanOutputStripsWrappingNoise() {
        assertEquals("他推开门。", SegmentEditService.cleanOutput("```\n他推开门。\n```"));
        assertEquals("他推开门。", SegmentEditService.cleanOutput("「他推开门。」"));
        assertEquals("他推开门。", SegmentEditService.cleanOutput("\"他推开门。\""));
        assertEquals("他推开门。", SegmentEditService.cleanOutput("改写后：\n他推开门。"));
    }

    @Test
    @DisplayName("清洗：正常正文不被误删（长首行不当作引导语）")
    void cleanOutputKeepsLegitimateText() {
        String body = "他推开门，屋里的煤油灯已经灭了，只有窗缝透进一点冷光。";
        assertEquals(body, SegmentEditService.cleanOutput(body));
        // 正文里出现的冒号不该被当成引导语前缀（首行超长）
        String withColon = "他说：这不是我的错，是这间屋子先动的手。";
        assertEquals(withColon, SegmentEditService.cleanOutput(withColon));
    }

    @Test
    @DisplayName("清洗：空白输入返回 null，交给调用方走 fallback")
    void cleanOutputBlankReturnsNull() {
        assertNull(SegmentEditService.cleanOutput(null));
        assertNull(SegmentEditService.cleanOutput(""));
        assertNull(SegmentEditService.cleanOutput("   \n  "));
    }
}
