package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 机械文风门禁测试。
 *
 * 覆盖两层：
 *
 *   严重度分层：程度性问题（副词密度/眼神套话/身体套话/章末升华）必须是 MINOR
 *       ——不触发修订也不触发候选；只有章节编号元信息泄露是 BLOCKING
 *   判据缺陷修复：章末升华的 AND 判定与词表收窄、章节编号的对白豁免
 *
 */
class StyleViolationPolicyTest {

    private static List<String> descriptions(List<ChapterIssueEntity> issues) {
        return issues.stream().map(ChapterIssueEntity::getDescription).toList();
    }

    private static boolean hasDesc(List<ChapterIssueEntity> issues, String keyword) {
        return issues.stream().anyMatch(i -> i.getDescription().contains(keyword));
    }

    private static ChapterIssueEntity pick(List<ChapterIssueEntity> issues, String keyword) {
        return issues.stream().filter(i -> i.getDescription().contains(keyword)).findFirst().orElseThrow();
    }

    @Test
    void blankContent_passes() {
        assertTrue(StyleViolationPolicy.check(null).isEmpty());
        assertTrue(StyleViolationPolicy.check("").isEmpty());
        assertTrue(StyleViolationPolicy.check("   ").isEmpty());
    }

    @Test
    void cleanContent_passes() {
        String content = "他推开门，看见桌上的信。信封没有落款，只有一枚烧焦的蜡印。";
        assertTrue(StyleViolationPolicy.check(content).isEmpty());
    }

    // ---------- 严重度分层 ----------

    @Test
    void degreeProblemsAreMinorNotBlocking() {
        // 四类程度问题同时命中，必须全部是 MINOR（不触发修订链路）
        String content = "他不禁停下脚步。她缓缓抬头，微微皱眉，轻轻叹气，顿时无语。\n\n"
                + "他喉结滚动，没接话。她转身要走，他又喉结滚动了一下。\n\n"
                + "归根结底，他的命运早已被写定，新的篇章却要从废墟里重新拾起。";
        List<ChapterIssueEntity> issues = StyleViolationPolicy.check(content);

        assertTrue(hasDesc(issues, "万能副词密度"), "应命中副词密度");
        assertTrue(hasDesc(issues, "身体反应套话复读"), "应命中身体套话复读");
        assertTrue(hasDesc(issues, "章末升华"), "应命中章末升华");
        for (ChapterIssueEntity issue : issues) {
            assertEquals(StyleViolationPolicy.SEVERITY_MINOR, issue.getSeverity(),
                    "程度性问题必须降为 MINOR：" + issue.getDescription());
        }
        assertTrue(issues.stream().noneMatch(i -> "BLOCKING".equals(i.getSeverity())),
                "程度性问题不得产出 BLOCKING");
    }

    @Test
    void chapterReferenceInNarration_isBlocking() {
        String content = "他在第一章里写下了那段推导，随后便把它忘了。";
        List<ChapterIssueEntity> issues = StyleViolationPolicy.check(content);

        ChapterIssueEntity issue = pick(issues, "章节编号元信息泄露");
        assertEquals(StyleViolationPolicy.SEVERITY_BLOCKING, issue.getSeverity(),
                "作者层面元信息是真硬伤，保留 BLOCKING");
        assertEquals("aesthetic", issue.getDimension());
    }

    // ---------- 万能副词密度 ----------

    @Test
    void adverbDensityOverThreshold_isMinor() {
        // 4 个万能副词 / 约 22 有效字 = 180 次/千字，远超 5 次/千字上限
        String content = "他不禁停下脚步。她缓缓抬头，微微皱眉，轻轻叹气。";
        List<ChapterIssueEntity> issues = StyleViolationPolicy.check(content);

        ChapterIssueEntity issue = pick(issues, "万能副词密度超标");
        assertEquals(StyleViolationPolicy.SEVERITY_MINOR, issue.getSeverity());
        assertEquals("aesthetic", issue.getDimension());
        assertTrue(issue.getEvidence().contains("缓缓×1"));
        assertTrue(issue.getEvidence().contains("不禁×1"));
    }

    @Test
    void adverbDensityAtCalibratedBoundary_passes() {
        // 5 个副词 / 1000 有效字 = 恰好 5 次/千字；判定是严格大于，故恰好踩线不触发
        String content = "不禁" + "字".repeat(990) + "缓缓" + "微微" + "顿时" + "瞬间";
        assertFalse(hasDesc(StyleViolationPolicy.check(content), "万能副词"));
    }

    @Test
    void adverbDensityJustOverOldThreshold_noLongerFires() {
        // 回归：旧阈值 3.0 时代大量「贴线误报」——本用例实测 4 次 / 1025 字 ≈ 3.9 次/千字，
        // 旧口径报 BLOCKING（历史 41 条即此形态，中位 3.92），新口径（5.0）应放行
        String content = "仿佛" + "字".repeat(1017) + "瞬间" + "随即" + "隐隐";
        assertFalse(hasDesc(StyleViolationPolicy.check(content), "万能副词"),
                "约 3.9 次/千字属该模型常态水位，不应再报");
    }

    // ---------- 眼神套话 ----------

    @Test
    void eyeClichesTwice_noLongerFires() {
        // 回归：旧口径「合计 >= 2 次」使 4/6 条历史命中恰好踩在 2 次上；新阈值 3 次
        String content = "他眼神复杂地看着她。她别过脸，目光如刀。";
        assertFalse(hasDesc(StyleViolationPolicy.check(content), "眼神套话"),
                "合计 2 次不再触发（阈值已上调至 3）");
    }

    @Test
    void eyeClichesThrice_isMinor() {
        String content = "他眼神复杂地看着她。她别过脸，目光如刀。远处那人眼中闪过一丝讥诮。";
        ChapterIssueEntity issue = pick(StyleViolationPolicy.check(content), "眼神套话");
        assertEquals(StyleViolationPolicy.SEVERITY_MINOR, issue.getSeverity());
        assertTrue(issue.getEvidence().contains("眼神复杂"));
    }

    @Test
    void eyeClicheOnce_passes() {
        String content = "他眼神复杂地看着她，没有说话。";
        assertFalse(hasDesc(StyleViolationPolicy.check(content), "眼神套话"));
    }

    // ---------- 身体反应套话 ----------

    @Test
    void bodyClicheRepeated_isMinor() {
        String content = "他喉结滚动，没接话。她转身要走，他又喉结滚动了一下。";
        ChapterIssueEntity issue = pick(StyleViolationPolicy.check(content), "身体反应套话复读");
        assertEquals(StyleViolationPolicy.SEVERITY_MINOR, issue.getSeverity());
        assertTrue(issue.getEvidence().contains("喉结滚动×2"));
    }

    @Test
    void bodyClicheOnceEach_passes() {
        String content = "他喉结滚动，没接话。她呼吸一滞，转身离开。";
        assertFalse(hasDesc(StyleViolationPolicy.check(content), "身体反应套话"));
    }

    // ---------- 章末升华：词表收窄 + AND 判定 ----------

    @Test
    void endingElevation_narrationWithoutActionOrDialogue_fires() {
        String content = "归根结底，他的命运早已被写定，新的篇章却要从废墟里重新拾起。";
        ChapterIssueEntity issue = pick(StyleViolationPolicy.check(content), "章末升华");
        assertEquals(StyleViolationPolicy.SEVERITY_MINOR, issue.getSeverity());
    }

    @Test
    void endingWithDialogue_noLongerFires() {
        // 回归：第 110 章实测——末段是对白（有中文引号），旧口径因「缺动作动词」被 OR 条件误判
        String content = "归根结底，命运早已被写定。“这是新的篇章。”";
        assertFalse(hasDesc(StyleViolationPolicy.check(content), "章末升华"),
                "有对白的结尾不是旁白总结（判定式已由 OR 改为 AND）");
    }

    @Test
    void endingWithNarrativeNoiseWord_noLongerFires() {
        // 回归：旧词表含「正是/这一刻/这一切」等叙述高频词，实测 4/5 误报由此而来
        String content = "陆沉的意识在这一刻彻底溃散，黑暗吞噬了一切。正是那道痕迹在暗处骤然亮起。";
        assertFalse(hasDesc(StyleViolationPolicy.check(content), "章末升华"),
                "「这一刻/这一切/正是」是叙述高频词，不应作为升华信号");
    }

    // ---------- 章节编号元信息：对白豁免 ----------

    @Test
    void chapterReferenceInsideDialogue_isExempt() {
        // 回归：第 39 章实测——「第一章」在角色台词里，指《模块化功法导论》的章节，非作者元信息
        String content = "“这就是《模块化功法导论》的第一章。”他说完，合上玉简。";
        assertFalse(hasDesc(StyleViolationPolicy.check(content), "章节编号元信息泄露"),
                "对白内的章节指代不算作者层面元信息");
        assertFalse(StyleViolationPolicy.hasChapterReference(content));
    }

    @Test
    void chapterRefGuardMatchesIssueScope() {
        // 加审预警（hasChapterReference）必须与 issue 判定同口径，否则两者互相打架
        String narration = "他在上一章交代过这件事，此刻却只字不提。";
        String dialogue = "“上一章不是写过了吗？”他反问。";
        assertTrue(StyleViolationPolicy.hasChapterReference(narration));
        assertTrue(hasDesc(StyleViolationPolicy.check(narration), "章节编号元信息泄露"));
        assertFalse(StyleViolationPolicy.hasChapterReference(dialogue));
        assertFalse(hasDesc(StyleViolationPolicy.check(dialogue), "章节编号元信息泄露"));
    }
}
