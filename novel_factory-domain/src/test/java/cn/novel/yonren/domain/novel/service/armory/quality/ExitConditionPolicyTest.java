package cn.novel.yonren.domain.novel.service.armory.quality;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退出条件原子化判据测试。
 *
 * <p>背景（162 章实测）：96 条退出条件里 75 条（78%）含"且/以及/同时"，整条判定让部分推进不可见，
 * 是"同一条件被反复结转、单调不可达"的直接原因。
 */
class ExitConditionPolicyTest {

    @Test
    void singleClauseIsReturnedAsSingleAtom() {
        // 单原子条件必须原样返回：保证既有单句条件的行为零变化
        assertEquals(List.of("主角突破至炼气九层"),
                ExitConditionPolicy.splitAtoms("主角突破至炼气九层"));
        assertFalse(ExitConditionPolicy.isCompound("主角突破至炼气九层"));
    }

    @Test
    void compoundClauseIsSplitOnConjunction() {
        List<String> atoms = ExitConditionPolicy.splitAtoms(
                "文本中出现幽冥谷使者夜袭的描写，且幽冥谷与外门公开敌对");
        assertEquals(2, atoms.size());
        assertEquals("文本中出现幽冥谷使者夜袭的描写", atoms.get(0));
        assertEquals("幽冥谷与外门公开敌对", atoms.get(1));
        assertTrue(ExitConditionPolicy.isCompound("文本中出现幽冥谷使者夜袭的描写，且幽冥谷与外门公开敌对"));
    }

    @Test
    void splitsOnAllConjunctionForms() {
        assertEquals(3, ExitConditionPolicy.splitAtoms("出现A，以及B，同时C").size());
        assertEquals(3, ExitConditionPolicy.splitAtoms("出现A，并且B；C").size());
        // 分号单独成界
        assertEquals(2, ExitConditionPolicy.splitAtoms("出现A；出现B").size());
    }

    @Test
    void longConnectorWinsOverShortOne() {
        // 并且 必须先于 且 命中，否则会切出「并」这种残片
        List<String> atoms = ExitConditionPolicy.splitAtoms("出现A，并且出现B");
        assertEquals(List.of("出现A", "出现B"), atoms);
    }

    @Test
    void enumerationCommaIsNotASplitPoint() {
        // 顿号是同一分句内的列举（尸体、神魂消散、生机断绝），切开会把一个语义单元碎成不可核验的碎片
        List<String> atoms = ExitConditionPolicy.splitAtoms("出现明确死亡状态描写（尸体、神魂消散、生机断绝等）");
        assertEquals(1, atoms.size());
        assertTrue(atoms.get(0).contains("尸体、神魂消散、生机断绝"));
    }

    @Test
    void bareBingIsNotASplitPoint() {
        // 裸「并」会误伤「合并」「并列」，不切
        assertEquals(1, ExitConditionPolicy.splitAtoms("完成模块合并与并列结构重构").size());
    }

    @Test
    void edgePunctuationIsStripped() {
        assertEquals(List.of("出现A", "出现B"),
                ExitConditionPolicy.splitAtoms("　出现A，，；　出现B。 "));
    }

    @Test
    void blankOrUnsplitInputNeverReturnsEmpty() {
        assertTrue(ExitConditionPolicy.splitAtoms(null).isEmpty());
        assertTrue(ExitConditionPolicy.splitAtoms("   ").isEmpty());
        // 拆不出有效原子时原样返回，绝不返回空表（否则聚合会丢条件）
        assertEquals(1, ExitConditionPolicy.splitAtoms("且且").size());
    }

    @Test
    void excessiveAtomsAreMergedToStayWithinBudget() {
        String condition = String.join("且", "原子一", "原子二", "原子三", "原子四",
                "原子五", "原子六", "原子七", "原子八");
        List<String> atoms = ExitConditionPolicy.splitAtoms(condition);
        assertEquals(ExitConditionPolicy.MAX_ATOMS_PER_CONDITION, atoms.size());
        // 尾部被合并，但信息不丢（最后一个原子包含原尾部全部内容）
        assertTrue(atoms.get(atoms.size() - 1).contains("原子七"));
        assertTrue(atoms.get(atoms.size() - 1).contains("原子八"));
    }

    @Test
    void renderAtomsJoinsForNote() {
        assertEquals("A；B", ExitConditionPolicy.renderAtoms(List.of("A", "B")));
        assertEquals("", ExitConditionPolicy.renderAtoms(List.of()));
    }

    // ---- 多点取证形态识别----
    // 核验侧只接受「单个 chapterNo + 该章内一段连续原文」，而这类条件的证据天然分散在多章/多场景，
    // 因此结构性无法通过。识别结果**只在已判未达成时用于归因**，不在判定路径上。

    @Test
    void multiPointFormFlagsContrastConditions() {
        assertEquals("对比/并列型", ExitConditionPolicy.multiPointForm(
                "文本中呈现了角色壬在线下清冷强硬与在线上撒娇吹捧的两种行为模式对比"));
        assertEquals("对比/并列型", ExitConditionPolicy.multiPointForm("两种形象的反差"));
    }

    @Test
    void multiPointFormFlagsAlternativeConditions() {
        assertEquals("二选一型", ExitConditionPolicy.multiPointForm(
                "文本中至少自然带出了敲键盘节奏或冷门BGM中的一项作为未解释的细节"));
    }

    @Test
    void multiPointFormFlagsEvolutionConditions() {
        assertEquals("跨章演化型", ExitConditionPolicy.multiPointForm("主角从筑基初期转变为金丹期"));
    }

    @Test
    void multiPointFormReturnsNullForSingleSceneCondition() {
        // 单场景条件没有结构性问题，未达成就是内容问题 —— 不该被盖上"结构性缺口"的帽子
        assertNull(ExitConditionPolicy.multiPointForm(
                "文本中明确描写了角色癸与角色壬在实验室选拔赛上互相否决对方方案的完整冲突过程"));
        assertNull(ExitConditionPolicy.multiPointForm("主角突破至炼气九层"));
        assertNull(ExitConditionPolicy.multiPointForm(""));
        assertNull(ExitConditionPolicy.multiPointForm(null));
    }

    @Test
    void multiPointFormDoesNotSplitOnConjunctionAlone() {
        // 「与」作主语连接词时（角色癸与角色壬）条件仍是单场景，不得被判为多点取证
        assertNull(ExitConditionPolicy.multiPointForm("文本中描写了陆沉与苏清平共同破阵的过程"));
    }
}
