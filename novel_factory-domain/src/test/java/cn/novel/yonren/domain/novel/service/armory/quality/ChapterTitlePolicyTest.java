package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 章节标题唯一性判据测试。
 *
 * 此前的空白：全书标题唯一性只靠 prompt 请求，无任何机械检查；长篇里"新的开始""风暴前夕"
 * 这类模板标题会反复出现。本判据是 MINOR 档——检测只负责记账，根治靠把已用标题回灌规划层。
 */
class ChapterTitlePolicyTest {

    private static ChapterSummaryEntity chapter(int no, String title) {
        return ChapterSummaryEntity.builder().chapterNo(no).title(title).build();
    }

    // ---------------- 等价键 ----------------

    @Test
    @DisplayName("等价键：剥编号前缀、剔空白与标点，标点写法差异不算不同标题")
    void dedupeKeyNormalizesPrefixSpaceAndPunctuation() {
        String base = ChapterTitlePolicy.dedupeKey("内存泄漏与视野噪点");
        assertEquals(base, ChapterTitlePolicy.dedupeKey("内存泄漏与视野噪点。"));
        assertEquals(base, ChapterTitlePolicy.dedupeKey("内存泄漏 与 视野噪点"));
        assertEquals("风暴前夕", ChapterTitlePolicy.dedupeKey("风暴前夕……"));
        // 带「第N章」前缀的标题：前缀被剥掉后与不带前缀的写法等价
        assertEquals("阵眼推演", ChapterTitlePolicy.dedupeKey("第7章 阵眼推演"));
        assertEquals("阵眼推演", ChapterTitlePolicy.dedupeKey("阵眼推演"));
    }

    @Test
    @DisplayName("剥前缀只剥带分隔符的「第N章」：剧情性标题「第3章的约定」不被误剥")
    void dedupeKeyKeepsPlotTitleIntact() {
        // ChapterTitleNormalizer 的既有口径：「第3章的约定」是剧情标题（章号指代前文），不剥前缀
        assertEquals("第3章的约定", ChapterTitlePolicy.dedupeKey("第3章的约定"));
    }

    @Test
    @DisplayName("纯标点标题不被归成同一个键（避免所有异常标题互相等价）")
    void punctuationOnlyTitlesDoNotCollapse() {
        assertEquals("……", ChapterTitlePolicy.dedupeKey("……"));
        assertEquals("", ChapterTitlePolicy.dedupeKey(null));
        assertEquals("", ChapterTitlePolicy.dedupeKey("   "));
    }

    // ---------------- 重复检测 ----------------

    @Test
    @DisplayName("检出重复并返回最早出现的章号（提示「与第N章重复」用）")
    void findsEarliestDuplicate() {
        List<ChapterSummaryEntity> history = List.of(
                chapter(2, "新的开始"), chapter(9, "风暴前夕"), chapter(31, "新的开始。"));

        assertEquals(2, ChapterTitlePolicy.findDuplicate("新的开始", history));
        assertEquals(9, ChapterTitlePolicy.findDuplicate("风暴前夕。", history));
        assertNull(ChapterTitlePolicy.findDuplicate("阵眼下的推演", history));
    }

    @Test
    @DisplayName("不做模糊匹配：前缀相同但主体不同是不同标题，误报会逼模型改掉好标题")
    void noFuzzyMatching() {
        List<ChapterSummaryEntity> history = List.of(chapter(5, "风暴"));

        assertNull(ChapterTitlePolicy.findDuplicate("风暴前夕", history));
        assertNull(ChapterTitlePolicy.findDuplicate("暴风前夜", history));
    }

    @Test
    @DisplayName("空标题/空历史不产生误报")
    void blankInputsAreSafe() {
        assertNull(ChapterTitlePolicy.findDuplicate("新的开始", List.of()));
        assertNull(ChapterTitlePolicy.findDuplicate("新的开始", null));
        assertNull(ChapterTitlePolicy.findDuplicate("   ", List.of(chapter(1, "新的开始"))));
        assertNull(ChapterTitlePolicy.findDuplicate(null, List.of(chapter(1, "新的开始"))));
    }

    // ---------------- issue ----------------

    @Test
    @DisplayName("重复时产出 MINOR issue（不触发修订）：为改一个标题整章重写不成比例")
    void checkProducesMinorIssue() {
        List<ChapterIssueEntity> issues = ChapterTitlePolicy.check("新的开始", List.of(chapter(7, "新的开始")));

        assertEquals(1, issues.size());
        ChapterIssueEntity issue = issues.get(0);
        assertEquals(StyleViolationPolicy.SEVERITY_MINOR, issue.getSeverity());
        assertEquals("continuity", issue.getDimension());
        assertTrue(issue.getDescription().contains("第 7 章"));
        assertEquals("新的开始", issue.getEvidence());
        assertTrue(issue.getSuggestion().contains("已用章节标题"));
    }

    @Test
    @DisplayName("不重复时零 issue")
    void checkPassesWhenUnique() {
        assertTrue(ChapterTitlePolicy.check("阵眼下的推演", List.of(chapter(7, "新的开始"))).isEmpty());
    }

    // ---------------- 回灌规划层 ----------------

    @Test
    @DisplayName("已用标题块：倒序列出、去重、含禁令说明")
    void renderUsedTitlesDedsupAndWarns() {
        List<ChapterSummaryEntity> history = List.of(
                chapter(1, "内存泄漏与视野噪点"), chapter(2, "新的开始"), chapter(3, "新的开始。"));

        String block = ChapterTitlePolicy.renderUsedTitles(history);

        assertTrue(block != null);
        assertTrue(block.contains("【已用章节标题】"));
        assertTrue(block.contains("第1章内存泄漏与视野噪点"));
        assertTrue(block.contains("严禁与之重复"));
        assertTrue(block.contains("新的开始"));
        // "新的开始" 与 "新的开始。" 等价 → 清单里只出现一次（保留最近的那一章）
        // 注：不能直接数 "新的开始" 的出现次数——禁令说明里也举了这个例子，要数清单条目的写法
        assertEquals(1, block.split("章新的开始", -1).length - 1, "等价标题在清单里只列一次");
        assertTrue(block.contains("第3章新的开始。"), "保留最近一次出现的写法");
    }

    @Test
    @DisplayName("无历史标题时不注入（首段规划拿不到清单）")
    void renderUsedTitlesNullWhenNoHistory() {
        assertNull(ChapterTitlePolicy.renderUsedTitles(List.of()));
        assertNull(ChapterTitlePolicy.renderUsedTitles(null));
        assertNull(ChapterTitlePolicy.renderUsedTitles(List.of(ChapterSummaryEntity.builder().chapterNo(1).build())));
    }

    @Test
    @DisplayName("超量时按上限截断并说明省略了多少")
    void renderUsedTitlesCapsLength() {
        List<ChapterSummaryEntity> many = new ArrayList<>();
        for (int i = 1; i <= ChapterTitlePolicy.RENDER_LIMIT + 20; i++) {
            many.add(chapter(i, "标题" + i));
        }

        String block = ChapterTitlePolicy.renderUsedTitles(many);

        assertTrue(block != null);
        assertTrue(block.contains("标题" + (ChapterTitlePolicy.RENDER_LIMIT + 20)), "应保留最近的一章");
        assertTrue(block.contains("省略"));
    }
}
