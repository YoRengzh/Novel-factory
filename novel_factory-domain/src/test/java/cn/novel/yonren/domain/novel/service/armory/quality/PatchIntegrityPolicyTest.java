package cn.novel.yonren.domain.novel.service.armory.quality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 补丁语义完整性判据测试（2026-10-10）：补丁只许改写表达，**不许丢骨架**。
 *
 * <p>背景：两条补丁路径（修订链路、段落密度挤水）的 replacement 都是模型自由生成的，
 * 而段落密度那条此前只查"机械文风违规不增加"——一次套用就能把只出现一次的实体或数字删掉，
 * 且不会在任何指标上现形。
 */
class PatchIntegrityPolicyTest {

    @Test
    @DisplayName("关键事件：原稿覆盖、补丁后不覆盖 → 计损失")
    void lostEvents_countsOnlyEventsCoveredBeforeButNotAfter() {
        String event = "角色己的名字出现在补充名单上";
        String original = "马老师把那张补充名单压在笔记本底下，角色己的名字在第二个。";
        String patched = "马老师把笔记本合上，屋里很静。";

        assertEquals(List.of(event), PatchIntegrityPolicy.lostEvents(original, patched, List.of(event)));
    }

    /**
     * 夹具陷阱（写给后来人）：覆盖率判据是 LCS ≥ 4，**4 个字偶然重合就算仍被覆盖**。
     * 第一版夹具里原稿与补丁稿都含"角色乙把"，于是"事件已丢失"被判成"仍覆盖"——
     * 这个阈值是有意的（正文会把关键事件转写成剧情，判据太严会把已覆盖的误判为缺失），
     * 但写夹具时必须避开这种偶然重合，否则用例会静默放行。
     */
    @Test
    @DisplayName("关键事件：原稿本就没覆盖 → 不算损失（修订无从恢复从未存在的内容）")
    void lostEvents_ignoresEventsNeverCovered() {
        String event = "角色庚在校门口宣布报考一班";

        assertTrue(PatchIntegrityPolicy.lostEvents("完全不相干的正文", "另一段不相干的正文", List.of(event)).isEmpty());
    }

    @Test
    @DisplayName("关键锚：原本只出现一次的实体整段消失才算损失；仍有一处保留则放行")
    void lostAnchors_requiresTotalDisappearance() {
        List<String> anchors = List.of("角色己");

        assertEquals(List.of("角色己"),
                PatchIntegrityPolicy.lostAnchors("角色己站在窗边，手插在校服兜里", "角色丙站在窗边，手插在校服兜里", anchors),
                "全章再无该名 → 真损失");
        assertTrue(PatchIntegrityPolicy.lostAnchors(
                        "角色己站在窗边。后来角色己没说话", "角色己站在窗边", anchors).isEmpty(),
                "还剩一处提及 → 不是损失（刻意不比出现次数：挤水的正当目的就是删段落）");
    }

    @Test
    @DisplayName("数字锚：只认两位以上数字串，且只提取不判断")
    void numberAnchors_extractsOnlyMultiDigitRuns() {
        List<String> anchors = PatchIntegrityPolicy.numberAnchors("第三考场17号座，1987年的单子，票面八十分，1 张。");

        assertTrue(anchors.contains("17"), anchors.toString());
        assertTrue(anchors.contains("1987"), anchors.toString());
        assertFalse(anchors.contains("1"), "单字符数字噪声大，不作锚：" + anchors);
    }

    @Test
    @DisplayName("综合校验：事件额度再宽也吞不掉锚损失——两个额度必须分开计")
    void check_keepsEventAndAnchorBudgetsSeparate() {
        String original = "角色丙在17号座交卷，角色己在16号座交卷。";
        String patched = "角色丙交了卷。";
        List<String> anchors = List.of("角色己", "17", "16");

        // 事件额度放到 5 条（远超需要），锚额度 0：仍必须拒绝——否则等于把事件额度当万能额度用
        PatchIntegrityPolicy.Verdict byEventBudget = PatchIntegrityPolicy.check(
                original, patched, List.of(), anchors, 5, 0);
        assertFalse(byEventBudget.acceptable(), "事件额度不得吞掉锚损失");
        assertEquals(3, byEventBudget.lostAnchors().size(), byEventBudget.toString());

        assertTrue(PatchIntegrityPolicy.check(original, patched, List.of(), anchors, 5, 3).acceptable(),
                "锚额度给足时才接受");
        assertTrue(PatchIntegrityPolicy.check(original, patched, List.of(), anchors, 5, 3)
                .reason().isEmpty(), "通过时不应给出拒绝理由");
    }
}
