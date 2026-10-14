package cn.novel.yonren.domain.novel.service.armory.quality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 悬念档位推进规则测试（2026-09-22）。
 *
 * <p>反例直接用真实病症：某批 6 章每章都是"发现线索 → 自我否定 → 回到原点"，
 * 档位始终趴在"一方起疑"上，而规划层还把它写成了要"确立"的叙事范式。
 * 档位表内容换成任何题材都不影响规则——这里用的是通用五档。
 */
class SuspenseLadderPolicyTest {

    private static final List<String> LADDER = List.of(
            "双方都还不知道对方身份",
            "一方起疑并留下物证",
            "双方各持证据但都未摊牌",
            "一方主动试探对方反应",
            "身份被公开摊牌");

    private static SuspenseLadderPolicy.Beat beat(int no, String raw, boolean transition) {
        return new SuspenseLadderPolicy.Beat(no, raw, transition);
    }

    @Test
    @DisplayName("逐级推进：无违规")
    void advancingLadderPasses() {
        List<SuspenseLadderPolicy.Beat> beats = List.of(
                beat(1, LADDER.get(0), false),
                beat(2, LADDER.get(1), false),
                beat(3, LADDER.get(2), false),
                beat(4, LADDER.get(3), false),
                beat(5, LADDER.get(4), false));

        assertTrue(SuspenseLadderPolicy.violations(beats, LADDER).isEmpty());
    }

    @Test
    @DisplayName("整批卡在同一档（真实病症复现）：判违规，且定位到首次达限的章")
    void stuckOnSameRungIsFlagged() {
        List<SuspenseLadderPolicy.Beat> beats = List.of(
                beat(1, LADDER.get(1), false),
                beat(2, LADDER.get(1), false),
                beat(3, LADDER.get(1), false),
                beat(4, LADDER.get(1), false),
                beat(5, LADDER.get(1), false),
                beat(6, LADDER.get(1), false));

        List<String> issues = SuspenseLadderPolicy.violations(beats, LADDER);

        assertEquals(1, issues.size(), "同一停留段只报一次，避免稀释回注信息");
        assertTrue(issues.get(0).contains("主线原地"));
        assertTrue(issues.get(0).contains("第 3 章"), "定位到首次达限的那一章");
    }

    @Test
    @DisplayName("档位倒退（自我否定圆回）：判违规并点明这不是推进")
    void regressIsFlagged() {
        List<SuspenseLadderPolicy.Beat> beats = List.of(
                beat(1, LADDER.get(0), false),
                beat(2, LADDER.get(1), false),
                beat(3, LADDER.get(0), false));

        List<String> issues = SuspenseLadderPolicy.violations(beats, LADDER);

        assertEquals(1, issues.size());
        assertTrue(issues.get(0).contains("档位倒退"));
        assertTrue(issues.get(0).contains("不是推进"));
    }

    @Test
    @DisplayName("停留段以过渡章收尾：不算违规（过渡章本就是蓄势）")
    void transitionTailRunIsExempt() {
        List<SuspenseLadderPolicy.Beat> beats = List.of(
                beat(1, LADDER.get(0), false),
                beat(2, LADDER.get(0), false),
                beat(3, LADDER.get(0), true));

        assertTrue(SuspenseLadderPolicy.violations(beats, LADDER).isEmpty());
    }

    @Test
    @DisplayName("过渡章也计入停留链：不能用 transition 洗掉停滞")
    void transitionCountsTowardRun() {
        List<SuspenseLadderPolicy.Beat> beats = List.of(
                beat(1, LADDER.get(0), false),
                beat(2, LADDER.get(0), true),
                beat(3, LADDER.get(0), false));

        List<String> issues = SuspenseLadderPolicy.violations(beats, LADDER);

        assertEquals(1, issues.size(), "中间夹一个过渡章不改变'三章没动'这个事实");
        assertTrue(issues.get(0).contains("主线原地"));
    }

    @Test
    @DisplayName("段末连续两章过渡不再豁免：那是停摆不是蓄势（第 14/15/16 章实测病症）")
    void consecutiveTransitionTailIsFlagged() {
        // 实测：第14章(normal) + 第15章(transition) + 第16章(transition) 连续三章同为档位4，
        // 旧规则因"以过渡章收尾"整段豁免，正是"三章讲同一件事"绕过校验的通道。
        // 豁免本意是单章过渡替下一章蓄势；连续两章过渡已经不是蓄势。
        List<SuspenseLadderPolicy.Beat> beats = List.of(
                beat(1, LADDER.get(0), false),
                beat(2, LADDER.get(0), true),
                beat(3, LADDER.get(0), true));

        List<String> issues = SuspenseLadderPolicy.violations(beats, LADDER);

        assertEquals(1, issues.size(), "连续两章过渡收尾的三连同档必须报出来");
        assertTrue(issues.get(0).contains("主线原地"));
        assertTrue(issues.get(0).contains("不是单章蓄势"),
                "违规描述要说明豁免为何不成立，否则规划模型不知道该改什么：" + issues.get(0));
    }

    @Test
    @DisplayName("maxHold 与 violations 同口径：连续过渡收尾的停留段照样计入")
    void maxHoldCountsConsecutiveTransitionTail() {
        List<SuspenseLadderPolicy.Beat> beats = List.of(
                beat(14, LADDER.get(2), false),
                beat(15, LADDER.get(2), true),
                beat(16, LADDER.get(2), true));

        SuspenseLadderPolicy.HoldRun hold = SuspenseLadderPolicy.maxHold(beats, LADDER);

        assertEquals(3, hold.length(), "旧实现只统计非过渡章收尾的段，这里会误报为 1");
        assertEquals(14, hold.startChapterNo());
        assertEquals(16, hold.endChapterNo());
        assertTrue(hold.violated());
    }

    @Test
    @DisplayName("档位表缺失或不足两档：跳过校验，不凭空判定")
    void unusableLadderSkips() {
        List<SuspenseLadderPolicy.Beat> beats = List.of(
                beat(1, "随手写的一句", false),
                beat(2, "随手写的一句", false),
                beat(3, "随手写的一句", false));

        assertTrue(SuspenseLadderPolicy.violations(beats, null).isEmpty());
        assertTrue(SuspenseLadderPolicy.violations(beats, List.of("唯一档位")).isEmpty());
        assertTrue(SuspenseLadderPolicy.violations(List.of(), LADDER).isEmpty());

        assertFalse(SuspenseLadderPolicy.usable(null));
        assertFalse(SuspenseLadderPolicy.usable(List.of("一档")));
        assertTrue(SuspenseLadderPolicy.usable(LADDER));
    }

    @Test
    @DisplayName("容忍模型写法：序号前缀、档位原文后补说明")
    void toleratesModelFormatting() {
        List<SuspenseLadderPolicy.Beat> beats = List.of(
                beat(1, "1. " + LADDER.get(0), false),
                beat(2, LADDER.get(1) + "（江燃在课桌上敲出了节奏）", false));

        assertTrue(SuspenseLadderPolicy.violations(beats, LADDER).isEmpty());
    }

    @Test
    @DisplayName("填了档位表外的内容：报'未声明/非原文'，供回注重规划")
    void unknownBeatIsReported() {
        List<String> issues = SuspenseLadderPolicy.violations(
                List.of(beat(1, "完全不在档位表里的一句话", false)), LADDER);

        assertEquals(1, issues.size());
        assertTrue(issues.get(0).contains("suspenseBeat"));
        assertTrue(issues.get(0).contains("第 1 章"));
    }

    @Test
    @DisplayName("maxHold：返回最长停留段（含起止章号）")
    void maxHoldReportsLongestRun() {
        List<SuspenseLadderPolicy.Beat> beats = List.of(
                beat(1, LADDER.get(0), false),
                beat(2, LADDER.get(0), false),
                beat(3, LADDER.get(0), false),
                beat(4, LADDER.get(1), false),
                beat(5, LADDER.get(1), false));

        SuspenseLadderPolicy.HoldRun run = SuspenseLadderPolicy.maxHold(beats, LADDER);

        assertEquals(3, run.length());
        assertEquals(1, run.startChapterNo());
        assertEquals(3, run.endChapterNo());
        assertTrue(run.violated());
    }

    @Test
    @DisplayName("maxHold：无数据返回 0 且不算违规")
    void maxHoldWithoutData() {
        SuspenseLadderPolicy.HoldRun empty = SuspenseLadderPolicy.maxHold(List.of(), LADDER);

        assertEquals(0, empty.length());
        assertFalse(empty.violated());
        assertFalse(SuspenseLadderPolicy.maxHold(
                List.of(beat(1, LADDER.get(0), false)), LADDER).violated());
    }

    // ==================== 相邻章档位描述去重 ====================

    /**
     * 回归：第 11–15 章真实病症——五章 suspenseBeat 逐字相同，连"（第11-15章）"的括注都一样。
     * 旧判据只比档位下标，报一次后便因去重而沉默；这里要求它被明确点名。
     */
    @Test
    @DisplayName("duplicateBeat：五章逐字相同必须报违规")
    void duplicateBeat_realCase() {
        String same = "档位2：角色丙以「早慧幼儿」身份首次展示识字能力（第11-15章）";
        List<SuspenseLadderPolicy.Beat> beats = List.of(
                beat(11, same, false), beat(12, same, false), beat(13, same, false),
                beat(14, same, false), beat(15, same, false));

        List<String> issues = SuspenseLadderPolicy.duplicateBeatViolations(beats);

        // 同一重复段只报一次（回注信息要可读）
        assertEquals(1, issues.size(), "重复段应只报一次，实际：" + issues);
        assertTrue(issues.get(0).contains("逐字相同"), "违规描述须点明「逐字相同」：" + issues.get(0));
    }

    /** 括注与序号是格式噪声，去掉后若仍相同同样算重复 */
    @Test
    @DisplayName("duplicateBeat：仅括注/序号不同视为重复")
    void duplicateBeat_ignoresOrdinalAndAnnotation() {
        List<SuspenseLadderPolicy.Beat> beats = List.of(
                beat(11, "1. 一方起疑并留下物证（第11-15章）", false),
                beat(12, "2. 一方起疑并留下物证", false));

        assertEquals(1, SuspenseLadderPolicy.duplicateBeatViolations(beats).size());
    }

    /** 同档位但写出了各章独有推进 —— 不违规（这是修复后期望的形态） */
    @Test
    @DisplayName("duplicateBeat：同档但描述不同不违规")
    void duplicateBeat_sameRungDifferentTextPasses() {
        List<SuspenseLadderPolicy.Beat> beats = List.of(
                beat(11, "一方起疑并留下物证——角色戊注意到凭证被翻动（第11章）", false),
                beat(12, "一方起疑并留下物证——角色辰开始记录孩子的异常（第12章）", false));

        assertTrue(SuspenseLadderPolicy.duplicateBeatViolations(beats).isEmpty(),
                "同档位下写清各章差异的不应被判重复");
    }

    /** 空数组/单章/全空描述不得抛异常 */
    @Test
    @DisplayName("duplicateBeat：边界输入不抛异常")
    void duplicateBeat_boundarySafe() {
        assertTrue(SuspenseLadderPolicy.duplicateBeatViolations(List.of()).isEmpty());
        assertTrue(SuspenseLadderPolicy.duplicateBeatViolations(
                List.of(beat(1, LADDER.get(0), false))).isEmpty());
        assertTrue(SuspenseLadderPolicy.duplicateBeatViolations(
                List.of(beat(1, "", false), beat(2, "  ", false))).isEmpty(),
                "空白描述由既有规则报「未声明」，不在此处重复报");
    }
}
