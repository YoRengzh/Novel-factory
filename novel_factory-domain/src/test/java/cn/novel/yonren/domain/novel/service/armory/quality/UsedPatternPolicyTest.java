package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 已用情节模式判据测试：窗口过滤、舞台复用聚合、重复点名。
 *
 * <p>背景（2026-09-29）：原黑名单只在正文层、且是"内容复述"，规划层完全没有对应块，
 * 而重复的源头在规划层（实测"老陆五金铺"在第 8–16 章反复出现、"校长测试"连占三章）。
 * 本类补的是一层（舞台 × 章型）指纹统计，并把同一实现同时供规划层与正文层使用。
 */
class UsedPatternPolicyTest {

    private static ChapterSummaryEntity chapter(int no, String place, String type, String summary) {
        return ChapterSummaryEntity.builder()
                .chapterNo(no)
                .placePoint(place)
                .chapterType(type)
                .summary(summary)
                .build();
    }

    @Test
    @DisplayName("同一舞台反复出现：聚合出次数并点名")
    void repeatedPlaceIsCalledOut() {
        List<ChapterSummaryEntity> summaries = List.of(
                chapter(1, "老陆五金铺", "normal", "开张第一天"),
                chapter(2, "老陆五金铺", "normal", "又来一批螺丝"),
                chapter(3, "老陆五金铺", "normal", "父亲盘账"),
                chapter(4, "某宅客厅", "transition", "吃饭"));

        String block = UsedPatternPolicy.render(summaries, 5, 10);

        assertNotNull(block);
        assertTrue(block.contains("老陆五金铺 ×3"), "应聚合出舞台复用次数：" + block);
        assertTrue(block.contains("重复点名"), "达阈值应点名：" + block);
        assertTrue(block.contains("第1/2/3章"), "点名要能定位到具体章号：" + block);
    }

    @Test
    @DisplayName("同舞台同章型连用：单独点名（这是'换一批人重演同一件事'的直接指纹）")
    void sameComboRepeatedIsCalledOut() {
        List<ChapterSummaryEntity> summaries = List.of(
                chapter(1, "五金铺", "normal", "一"),
                chapter(2, "五金铺", "normal", "二"),
                chapter(3, "五金铺", "normal", "三"));

        String block = UsedPatternPolicy.render(summaries, 4, 10);

        assertTrue(block.contains("这一组合已连用 3 次"), "同舞台同章型应单独点名：" + block);
    }

    @Test
    @DisplayName("只统计目标章之前的章节：本章与后续不能被算作'已用'")
    void onlyChaptersBeforeTargetAreCounted() {
        List<ChapterSummaryEntity> summaries = List.of(
                chapter(1, "甲地", "normal", "一"),
                chapter(2, "乙地", "normal", "二"));

        String block = UsedPatternPolicy.render(summaries, 2, 10);

        assertNotNull(block);
        assertTrue(block.contains("第1章"), block);
        assertFalse(block.contains("乙地"), "第 2 章及之后不得进入'已用'统计：" + block);
    }

    @Test
    @DisplayName("回看窗口生效：窗口外的章节不参与统计")
    void lookbackWindowIsRespected() {
        List<ChapterSummaryEntity> summaries = List.of(
                chapter(1, "早期甲地", "normal", "一"),
                chapter(2, "乙地", "normal", "二"),
                chapter(3, "丙地", "normal", "三"));

        // 只看最近 2 章（第2、3章）→ 第 1 章的"早期甲地"应消失
        String block = UsedPatternPolicy.render(summaries, 4, 2);

        assertNotNull(block);
        assertFalse(block.contains("早期甲地"), "窗口外章节不该出现：" + block);
        assertTrue(block.contains("丙地"), block);
    }

    @Test
    @DisplayName("无前置章节时不注入（首发）")
    void emptyWhenNoPriorChapter() {
        assertNull(UsedPatternPolicy.render(List.of(chapter(1, "甲地", "normal", "一")), 1, 10));
        assertNull(UsedPatternPolicy.render(List.of(), 1, 10));
        assertNull(UsedPatternPolicy.render(null, 1, 10));
    }

    @Test
    @DisplayName("角色冲突发起频次：同一角色反复当麻烦制造者要点名（小胖模式的机械指纹）")
    void repeatedConflictRoleIsCalledOut() {
        // 实测：小胖在第 3/13/19 章反复"抢夺 → 被物证揭穿 → 长辈介入 → 逃离"。
        // ⚠️ 该套路用**文本相似度抓不到**——三次节拍文本两两 3-gram Jaccard ≤0.09（第3章 vs 第19章仅 0.02），
        // 因为重复的是抽象结构而非措辞。故本指标走"角色 + 冲突动词"的结构化标签，本用例守住这条路线。
        List<ChapterSummaryEntity> summaries = List.of(
                withBeats(chapter(1, "巷口", "normal", "一"), beat("小胖", "抢到角色庚兜里的点心吃")),
                withBeats(chapter(2, "水井旁", "normal", "二"), beat("王阿婆", "倒煤灰，查证谁偷了她晒的梅干菜")),
                withBeats(chapter(3, "巷拐", "climax", "三"), beat("小胖", "报复角色丙，抢走头绳泄愤")),
                withBeats(chapter(4, "老陆五金铺", "normal", "四"), beat("角色戊", "核对本月账目")),
                withBeats(chapter(5, "院门口", "normal", "五"), beat("小胖", "抢走角色庚的糖")));

        String block = UsedPatternPolicy.render(summaries, 6, 10);

        assertNotNull(block);
        assertTrue(block.contains("角色冲突发起频次"), "应给出角色冲突统计：" + block);
        assertTrue(block.contains("小胖 ×3"), block);
        assertTrue(block.contains("第1/3/5章"), "要点名到具体章号：" + block);
        assertTrue(block.contains("角色「小胖」"), "达阈值应进重复点名：" + block);
        assertTrue(block.contains("暂时退出冲突线"), "要给出可执行的处置方式：" + block);
        // 无冲突动词的角色不应被计数
        assertFalse(block.contains("角色戊 ×"), "「核对账目」不含冲突动词，不该被计入：" + block);
    }

    private static ChapterSummaryEntity withBeats(ChapterSummaryEntity summary,
                                                  ChapterSummaryEntity.CharacterBeat... beats) {
        summary.setCharacterBeats(new java.util.ArrayList<>(List.of(beats)));
        return summary;
    }

    private static ChapterSummaryEntity.CharacterBeat beat(String name, String goal) {
        // CharacterBeat 只有 @NoArgsConstructor/@AllArgsConstructor（无 @Builder），
        // 用 setter 构造可避免依赖字段声明顺序
        ChapterSummaryEntity.CharacterBeat beat = new ChapterSummaryEntity.CharacterBeat();
        beat.setName(name);
        beat.setGoal(goal);
        return beat;
    }

    @Test
    @DisplayName("能力展示频次：窗口内 ≥2 章标注展示即告警，并点名已用形态")
    void repeatedAbilityShowcaseIsCalledOut() {
        // 实测：5 章全部是「微动作展示 → 旁人评价不像孩子」，读者第 3 次即可预判
        List<ChapterSummaryEntity> summaries = List.of(
                showcase(6, "手指蘸水画圈引人注意"),
                showcase(7, "旁人评价不像四岁"),
                chapter(8, "某宅客厅", "normal", "日常吃饭，无展示"));

        String block = UsedPatternPolicy.render(summaries, 11, 5);

        assertTrue(block.contains("能力/早慧展示频次"), "应渲染展示频次统计：" + block);
        assertTrue(block.contains("第6章（手指蘸水画圈引人注意）"), "要点名章号与形态：" + block);
        assertTrue(block.contains("不得再连续安排"), "达阈值应给出处置指令：" + block);
        assertTrue(block.contains("重复点名"), "告警应进入汇总点名：" + block);
    }

    @Test
    @DisplayName("无标注（老数据）或单次展示：不渲染告警，只列事实")
    void noShowcaseAlertWithoutEnoughSamples() {
        List<ChapterSummaryEntity> oldData = List.of(
                chapter(1, "老陆五金铺", "normal", "一"),
                chapter(2, "某宅客厅", "normal", "二"));
        String block = UsedPatternPolicy.render(oldData, 3, 5);
        assertFalse(block.contains("能力/早慧展示频次"), "老数据无标注不得渲染该节：" + block);

        List<ChapterSummaryEntity> single = List.of(
                showcase(1, "对棋谱多看了一眼"),
                chapter(2, "某宅客厅", "normal", "二"),
                chapter(3, "院门口", "normal", "三"));
        String singleBlock = UsedPatternPolicy.render(single, 4, 5);
        assertTrue(singleBlock.contains("能力/早慧展示频次"), "有标注就列事实：" + singleBlock);
        assertFalse(singleBlock.contains("不得再连续安排"), "单次展示未达阈值不告警：" + singleBlock);
    }

    private static ChapterSummaryEntity showcase(int no, String form) {
        return ChapterSummaryEntity.builder()
                .chapterNo(no)
                .placePoint("巷口")
                .chapterType("normal")
                .summary("主角展示早慧。")
                .abilityShowcased(Boolean.TRUE)
                .abilityDisplayForm(form)
                .build();
    }

    /**
     * 本块篇幅必须与"摘要写得多详细 / 节拍写得多长"解耦（内容复述节已移除）。
     *
     * <p>它原先逐章渲染"摘要前 80 字 + 目标→结果"，而"目标→结果"处**不设长度上限**。
     * 实测后果：本块在前缀预算里装不下 → 被部分截断 → 连带把排在它后面的【审校反馈】整块挤掉
     * （一次截断会把剩余预算清零，见 {@code PromptBudgetGuard.assemble} 的修正说明）。
     * 故此处用"极长摘要 + 极长节拍"锁住这条性质：只留聚合统计，不复述内容。
     */
    @Test
    @DisplayName("篇幅与内容详略解耦：内容复述节已移除，不再随摘要/节拍长度膨胀")
    void blockSizeDoesNotScaleWithSummaryVerbosity() {
        String longText = "冗".repeat(600);
        List<ChapterSummaryEntity> terse = List.of(
                chapter(1, "老陆五金铺", "normal", "一句话。"),
                chapter(2, "某宅客厅", "normal", "一句话。"),
                chapter(3, "院门口", "normal", "一句话。"));
        List<ChapterSummaryEntity> verbose = List.of(
                withBeats(chapter(1, "老陆五金铺", "normal", longText), beat("小胖", longText)),
                withBeats(chapter(2, "某宅客厅", "normal", longText), beat("小胖", longText)),
                withBeats(chapter(3, "院门口", "normal", longText), beat("小胖", longText)));

        String terseBlock = UsedPatternPolicy.render(terse, 4, 5);
        String verboseBlock = UsedPatternPolicy.render(verbose, 4, 5);

        assertFalse(verboseBlock.contains("各章核心情节"), "内容复述节必须已被移除：" + verboseBlock);
        assertFalse(verboseBlock.contains(longText.substring(0, 50)), "不得再复述摘要/节拍正文：" + verboseBlock);
        int delta = Math.abs(verboseBlock.length() - terseBlock.length());
        assertTrue(delta < 200, "篇幅应与内容详略解耦，实际相差 " + delta + " 字：" + verboseBlock);
    }
}
