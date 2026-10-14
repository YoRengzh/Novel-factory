package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 阶段退出条件核验测试：证据机械校验（子串命中）、编造判未达成、条数不齐/异常 fail-soft
 */
class StageExitReviewServiceTest {

    private LlmGateway llmGateway;
    private StageExitReviewService service;

    @BeforeEach
    void setUp() {
        llmGateway = mock(LlmGateway.class);
        service = new StageExitReviewService(llmGateway);
    }

    @Test
    void review_acceptsVerifiableEvidenceAndAlignsConditions() {
        StageBlueprintEntity stage = stage();
        stubModel("{\"results\":["
                + "{\"condition\":\"主角突破至炼气九层\",\"met\":true,\"chapterNo\":5,"
                + "\"evidence\":\"丹田气旋一涨，炼气九层的门槛被他一脚跨过\"},"
                + "{\"condition\":\"幽冥谷与外门公开敌对\",\"met\":true,\"chapterNo\":9,"
                + "\"evidence\":\"幽冥谷\"}]}");

        List<StageBlueprintEntity.ExitConditionResult> results =
                service.review(module(), stage, summaries(), null);

        assertEquals(2, results.size());
        // 条件原文机械回填（不信任模型回显）
        assertEquals("主角突破至炼气九层", results.get(0).getCondition());
        assertTrue(results.get(0).getMet());
        assertEquals(5, results.get(0).getChapterNo());
        // 证据可来自账本状态串（幽冥谷：公开敌对）
        assertTrue(results.get(1).getMet());
        assertEquals("幽冥谷", results.get(1).getEvidence());
    }

    @Test
    void review_fabricatedEvidenceJudgedUnmet() {
        StageBlueprintEntity stage = stage();
        stubModel("{\"results\":["
                + "{\"condition\":\"主角突破至炼气九层\",\"met\":true,\"chapterNo\":5,"
                + "\"evidence\":\"这句话在摘要里根本不存在\"},"
                + "{\"condition\":\"幽冥谷与外门公开敌对\",\"met\":true,\"chapterNo\":9,"
                + "\"evidence\":\"幽冥谷\"}]}");

        List<StageBlueprintEntity.ExitConditionResult> results =
                service.review(module(), stage, summaries(), null);

        // 宁严勿松：引用非该章记忆原文 → 按未达成处理，证据置空
        assertFalse(results.get(0).getMet());
        assertNull(results.get(0).getEvidence());
        assertTrue(results.get(0).getNote().contains("证据校验失败"));
        assertTrue(results.get(1).getMet());
    }

    @Test
    void review_chapterNoOutOfRangeJudgedUnmet() {
        StageBlueprintEntity stage = stage();
        stubModel("{\"results\":["
                + "{\"condition\":\"主角突破至炼气九层\",\"met\":true,\"chapterNo\":99,"
                + "\"evidence\":\"丹田气旋一涨\"},"
                + "{\"condition\":\"幽冥谷与外门公开敌对\",\"met\":true,\"chapterNo\":9,"
                + "\"evidence\":\"幽冥谷\"}]}");

        List<StageBlueprintEntity.ExitConditionResult> results =
                service.review(module(), stage, summaries(), null);

        assertFalse(results.get(0).getMet());
        assertTrue(results.get(0).getNote().contains("越界"));
        assertTrue(results.get(1).getMet());
    }

    @Test
    void review_countMismatchReturnsNull() {
        StageBlueprintEntity stage = stage();
        stubModel("{\"results\":[{\"condition\":\"只回了一条\",\"met\":true,\"chapterNo\":9,\"evidence\":\"气旋\"}]}");

        assertNull(service.review(module(), stage, summaries(), null));
    }

    @Test
    void review_llmFailureReturnsNull() {
        StageBlueprintEntity stage = stage();
        when(llmGateway.complete(any(), any(LlmCall.class))).thenThrow(new RuntimeException("timeout"));

        assertNull(service.review(module(), stage, summaries(), null));
    }

    @Test
    void review_noConditionsReturnsNull() {
        StageBlueprintEntity stage = StageBlueprintEntity.builder()
                .stageNo(1).startChapter(1).endChapter(10).build();

        assertNull(service.review(module(), stage, summaries(), null));
    }

    @Test
    void review_promptCarriesConditionsAndChapterMemory() {
        StageBlueprintEntity stage = stage();
        stubModel("{\"results\":[{\"condition\":\"主角突破至炼气九层\",\"met\":false}]}");
        Map<String, String> sink = new HashMap<>();

        service.review(module(), stage, summaries(), sink);

        // prompt 经 sink 回流供复盘
        assertTrue(sink.containsKey("system"));
        assertTrue(sink.get("user").contains("1. 主角突破至炼气九层"));
        assertTrue(sink.get("user").contains("2. 幽冥谷与外门公开敌对"));
        assertTrue(sink.get("user").contains("宁严勿松"));
        // 可核验文本 = 摘要 + 账本状态串，与机械校验同源
        assertTrue(sink.get("user").contains("丹田气旋一涨，炼气九层的门槛被他一脚跨过"));
        assertTrue(sink.get("user").contains("（幽冥谷：公开敌对）"));
    }

    @Test
    void review_compoundConditionPartialProgressIsVisible() {
        // 原子化：复合条件拆成 2 个分句独立核验。只有 1 个落地 → 整条仍未达成，
        // 但"已达成 1/2 + 还差哪个分句"必须可见——原实现只给一句"未达成"，模型无从知道补什么
        StageBlueprintEntity stage = StageBlueprintEntity.builder()
                .stageNo(1).startChapter(1).endChapter(10)
                .exitConditions(List.of("文本中出现幽冥谷使者夜袭的描写，且幽冥谷与外门公开敌对"))
                .build();
        stubModel("{\"results\":["
                + "{\"met\":true,\"chapterNo\":9,\"evidence\":\"幽冥谷使者夜袭外门\"},"
                + "{\"met\":false,\"note\":\"正文未提及公开敌对\"}]}");

        List<StageBlueprintEntity.ExitConditionResult> results =
                service.review(module(), stage, summaries(), null);

        assertEquals(1, results.size());
        assertFalse(results.get(0).getMet(), "分句未全部达成时整条不算达成");
        assertEquals(1, results.get(0).getMetAtoms());
        assertEquals(2, results.get(0).getTotalAtoms());
        assertNull(results.get(0).getEvidence(), "整条未达成的条目不留引用");
        assertTrue(results.get(0).getNote().contains("1 个未达成"));
        assertTrue(results.get(0).getNote().contains("公开敌对"), "缺口须精确指到未达成的那个分句");
    }

    @Test
    void review_compoundConditionAllAtomsMet_countsAsMet() {
        StageBlueprintEntity stage = StageBlueprintEntity.builder()
                .stageNo(1).startChapter(1).endChapter(10)
                .exitConditions(List.of("文本中出现幽冥谷使者夜袭的描写，且幽冥谷与外门公开敌对"))
                .build();
        stubModel("{\"results\":["
                + "{\"met\":true,\"chapterNo\":9,\"evidence\":\"幽冥谷使者夜袭外门\"},"
                + "{\"met\":true,\"chapterNo\":9,\"evidence\":\"幽冥谷\"}]}");

        List<StageBlueprintEntity.ExitConditionResult> results =
                service.review(module(), stage, summaries(), null);

        assertTrue(results.get(0).getMet());
        assertEquals(2, results.get(0).getMetAtoms());
        assertEquals(2, results.get(0).getTotalAtoms());
        assertEquals(9, results.get(0).getChapterNo());
        assertNull(results.get(0).getNote());
    }

    @Test
    void review_promptNumbersAtomsAndAnnotatesParentCondition() {
        StageBlueprintEntity stage = StageBlueprintEntity.builder()
                .stageNo(1).startChapter(1).endChapter(10)
                .stageGoal("站稳外门")
                .exitConditions(List.of("主角突破至炼气九层", "出现夜袭描写，且公开敌对"))
                .build();
        stubModel("{\"results\":[{\"met\":false},{\"met\":false},{\"met\":false}]}");
        Map<String, String> sink = new HashMap<>();

        service.review(module(), stage, summaries(), sink);

        String prompt = sink.get("user");
        // 条数 = 原子数（1 + 2），单原子条件不带父条件注解
        assertTrue(prompt.contains("共 3 条"));
        assertTrue(prompt.contains("1. 主角突破至炼气九层"));
        assertTrue(prompt.contains("2. 出现夜袭描写　←　任务「出现夜袭描写，且公开敌对」的第 1/2 个分句"));
        assertTrue(prompt.contains("3. 公开敌对　←　任务「出现夜袭描写，且公开敌对」的第 2/2 个分句"));
        assertTrue(prompt.contains("逐分句独立判定"));
    }

    // ---- 口径收编（裸 indexOf → EvidenceMatch）后的边界测试 ----

    @Test
    void review_toleratesQuoteFormDifferenceInEvidence() {
        // 记忆原文用全角 “固定搭档已生效”，模型引用改用半角双引号 —— 只差引号形态，事实同一
        StageBlueprintEntity stage = quotedStage();
        stubModel("{\"results\":["
                + "{\"condition\":\"无月与软软结为固定队伍搭档\",\"met\":true,\"chapterNo\":6,"
                + "\"evidence\":\"系统提示\\\"固定搭档已生效\\\"\"},"
                + unmet(1) + "," + unmet(2) + "]}");

        List<StageBlueprintEntity.ExitConditionResult> results =
                service.review(module(), stage, quotedSummaries(), null);

        assertTrue(results.get(0).getMet(), "引号形态差异不应让证据作废（半角双引号）");
    }

    @Test
    void review_toleratesAsciiSingleQuoteInEvidence() {
        // 模型改用半角单引号引用同一句 —— 同属引号形态差异
        StageBlueprintEntity stage = quotedStage();
        stubModel("{\"results\":["
                + "{\"condition\":\"无月与软软结为固定队伍搭档\",\"met\":true,\"chapterNo\":6,"
                + "\"evidence\":\"系统提示'固定搭档已生效'\"},"
                + unmet(1) + "," + unmet(2) + "]}");

        List<StageBlueprintEntity.ExitConditionResult> results =
                service.review(module(), stage, quotedSummaries(), null);

        assertTrue(results.get(0).getMet(), "引号形态差异不应让证据作废（半角单引号）");
    }

    @Test
    void review_toleratesEllipsisJoinedSegmentsWithinSameChapter() {
        // 模型用省略号把同一章内相距较远的两段原文压缩成一条引用 —— 各段都能在该章定位
        StageBlueprintEntity stage = quotedStage();
        stubModel("{\"results\":["
                + "{\"condition\":\"无月与软软结为固定队伍搭档\",\"met\":true,\"chapterNo\":6,"
                + "\"evidence\":\"江燃上线与软软在犹豫三秒后几乎同时确认绑定成功……固定搭档已生效\"},"
                + unmet(1) + "," + unmet(2) + "]}");

        List<StageBlueprintEntity.ExitConditionResult> results =
                service.review(module(), stage, quotedSummaries(), null);

        assertTrue(results.get(0).getMet(), "同章内的省略号分段引用应放行");
    }

    @Test
    void review_crossChapterStitchedEvidenceStillUnmet() {
        // 两段都是**逐字真实**的原文（第一段在 ch6、第二段在 ch2），但用省略号拼成了一条。
        // 放宽的是「表述差异」，不是「事实有无」—— 跨章拼接必须仍然拒收：
        // 校验要求每一段都能在**你所填章号那一章**定位，第二段在 ch6 里找不到，故判未达成。
        StageBlueprintEntity stage = quotedStage();
        stubModel("{\"results\":["
                + "{\"condition\":\"无月与软软结为固定队伍搭档\",\"met\":true,\"chapterNo\":6,"
                + "\"evidence\":\"江燃上线与软软在犹豫三秒后几乎同时确认绑定成功……两人组队进入地狱难度熔岩洞穴副本\"},"
                + unmet(1) + "," + unmet(2) + "]}");

        List<StageBlueprintEntity.ExitConditionResult> results =
                service.review(module(), stage, quotedSummaries(), null);

        assertFalse(results.get(0).getMet(), "跨章拼接的引文无法在任何单章内定位，必须判未达成");
        assertTrue(results.get(0).getNote().contains("证据校验失败"));
    }

    @Test
    void review_unmetEvidenceStillRejectedWhenNotInMemoryAtAll() {
        // 反编造门不放弃：完全不在记忆里的引用仍判未达成
        StageBlueprintEntity stage = quotedStage();
        stubModel("{\"results\":["
                + "{\"condition\":\"无月与软软结为固定队伍搭档\",\"met\":true,\"chapterNo\":6,"
                + "\"evidence\":\"这句话在两个章节的记忆里都根本不存在\"},"
                + unmet(1) + "," + unmet(2) + "]}");

        List<StageBlueprintEntity.ExitConditionResult> results =
                service.review(module(), stage, quotedSummaries(), null);

        assertFalse(results.get(0).getMet());
        assertNull(results.get(0).getEvidence());
    }

    @Test
    void review_unmetMultiPointConditionCarriesStructuralHint() {
        // 对比型条件的证据天然分散在多章，核验只受单章一段连续原文 —— 未达成注记要点出这层原因
        StageBlueprintEntity stage = quotedStage();
        stubModel("{\"results\":["
                + unmet(0) + ","
                + unmet(1) + ","
                + "{\"condition\":\"许知意线下清冷与线上撒娇的两种行为模式对比\",\"met\":true,\"chapterNo\":6,"
                + "\"evidence\":\"编造的对比证据\"}]}");

        List<StageBlueprintEntity.ExitConditionResult> results =
                service.review(module(), stage, quotedSummaries(), null);

        assertFalse(results.get(2).getMet());
        assertTrue(results.get(2).getNote().contains("疑似结构性缺口"),
                "多点取证型条件的未达成注记应带结构性提示，便于归因");
        assertTrue(results.get(2).getNote().contains("对比/并列型"));
    }

    @Test
    void review_singleSceneConditionHasNoStructuralHint() {
        // 单场景条件的未达成就是内容问题，不该被盖上"结构性缺口"的帽子
        StageBlueprintEntity stage = quotedStage();
        stubModel("{\"results\":["
                + "{\"condition\":\"无月与软软结为固定队伍搭档\",\"met\":true,\"chapterNo\":6,"
                + "\"evidence\":\"编造\"},"
                + unmet(1) + "," + unmet(2) + "]}");

        List<StageBlueprintEntity.ExitConditionResult> results =
                service.review(module(), stage, quotedSummaries(), null);

        assertFalse(results.get(0).getMet());
        assertFalse(results.get(0).getNote().contains("疑似结构性缺口"));
    }

    /** 未达成的模型输出条目（不带证据） */
    private String unmet(int index) {
        String[] conditions = {
                "无月与软软结为固定队伍搭档",
                "敲键盘节奏与冷门BGM带出未解释细节",
                "许知意线下清冷与线上撒娇的两种行为模式对比"};
        return "{\"condition\":\"" + conditions[index] + "\",\"met\":false,\"note\":\"未达成\"}";
    }

    private StageBlueprintEntity quotedStage() {
        return StageBlueprintEntity.builder()
                .stageNo(1)
                .startChapter(1)
                .endChapter(6)
                .stageGoal("确立线上固定搭档关系")
                .exitConditions(List.of(
                        "无月与软软结为固定队伍搭档",
                        "敲键盘节奏与冷门BGM带出未解释细节",
                        "许知意线下清冷与线上撒娇的两种行为模式对比"))
                .build();
    }

    /** 第 6 章原文含全角引号；第 2 章另有一段，用于验证"跨章拼接必须被拒" */
    private List<ChapterSummaryEntity> quotedSummaries() {
        return List.of(
                ChapterSummaryEntity.builder()
                        .chapterNo(2).title("第2章")
                        .summary("两人组队进入地狱难度熔岩洞穴副本，二人成功首通。")
                        .build(),
                ChapterSummaryEntity.builder()
                        .chapterNo(6).title("第6章")
                        .summary("中午十二点，江燃上线与软软在犹豫三秒后几乎同时确认绑定成功，"
                                + "系统提示“固定搭档已生效”。")
                        .build());
    }

    // ---- 二阶段：正文复核----
    // 一阶段的核验文本是「摘要 + 三账本」，而摘要只记剧情主干、不记动作细节；
    // 于是会出现"正文写了、摘要没记、核验判未达成"的假阴性。复核用正文补这一层，但**门槛不变**。

    @Test
    void recheck_recoversUnmetConditionUsingChapterText() {
        StageBlueprintEntity stage = stage();
        // 一阶段：两条都判未达成（模型在两稿里都没找到证据）
        stubModel("{\"results\":["
                + "{\"condition\":\"主角突破至炼气九层\",\"met\":false,\"note\":\"记忆中未找到\"},"
                + "{\"condition\":\"幽冥谷与外门公开敌对\",\"met\":false,\"note\":\"记忆中未找到\"}]}");
        List<StageBlueprintEntity.ExitConditionResult> firstPass =
                service.review(module(), stage, summaries(), null);
        assertFalse(firstPass.get(0).getMet());

        // 二阶段：正文里有逐字原文 → 应改判达成；第二条正文里确实没有 → 维持未达成
        stubModel("{\"results\":["
                + "{\"condition\":\"主角突破至炼气九层\",\"met\":true,\"chapterNo\":5,"
                + "\"evidence\":\"炼气九层的门槛被他一脚跨过\"},"
                + "{\"condition\":\"幽冥谷与外门公开敌对\",\"met\":false,\"note\":\"正文也没有\"}]}");
        Map<Integer, String> chapterText = Map.of(
                5, "小陈夜探废矿，丹田气旋一涨，炼气九层的门槛被他一脚跨过。");

        List<StageBlueprintEntity.ExitConditionResult> out = service.recheckAgainstChapterText(
                module(), stage, firstPass, chapterText, null);

        assertTrue(out.get(0).getMet(), "正文里能找到逐字证据，应改判达成");
        assertTrue(out.get(0).getNote().contains("正文复核通过"), "改判理由要留痕，便于区分口径修正与质量改善");
        assertFalse(out.get(1).getMet(), "正文里确实没有的内容，维持未达成");
    }

    @Test
    void recheck_doesNotRelaxTheEvidenceBar() {
        // 模型声称在正文里找到，但引文其实不在正文 → 不得改判。
        // 复核放宽的是"去哪找证据"，不是"要不要证据"。
        StageBlueprintEntity stage = stage();
        stubModel("{\"results\":["
                + "{\"condition\":\"主角突破至炼气九层\",\"met\":false,\"note\":\"未找到\"},"
                + "{\"condition\":\"幽冥谷与外门公开敌对\",\"met\":false,\"note\":\"未找到\"}]}");
        List<StageBlueprintEntity.ExitConditionResult> firstPass =
                service.review(module(), stage, summaries(), null);

        stubModel("{\"results\":["
                + "{\"condition\":\"主角突破至炼气九层\",\"met\":true,\"chapterNo\":5,"
                + "\"evidence\":\"这句概括正文里根本没有\"},"
                + "{\"condition\":\"幽冥谷与外门公开敌对\",\"met\":false,\"note\":\"x\"}]}");

        List<StageBlueprintEntity.ExitConditionResult> out = service.recheckAgainstChapterText(
                module(), stage, firstPass, Map.of(5, "小陈夜探废矿。"), null);

        assertFalse(out.get(0).getMet(), "引文不在正文里 → 不得改判");
    }

    @Test
    void recheck_softFailsWithoutChapterText() {
        // 取不到任何正文（内存为空且磁盘回读失败）→ 保留一阶段结论，绝不误判为达成
        StageBlueprintEntity stage = stage();
        stubModel("{\"results\":["
                + "{\"condition\":\"主角突破至炼气九层\",\"met\":false,\"note\":\"未找到\"},"
                + "{\"condition\":\"幽冥谷与外门公开敌对\",\"met\":false,\"note\":\"未找到\"}]}");
        List<StageBlueprintEntity.ExitConditionResult> firstPass =
                service.review(module(), stage, summaries(), null);

        List<StageBlueprintEntity.ExitConditionResult> out = service.recheckAgainstChapterText(
                module(), stage, firstPass, Map.of(), null);

        assertFalse(out.get(0).getMet(), "无正文可核时保留一阶段结论");
    }

    @Test
    void recheck_isNoOpWhenNothingUnmet() {
        // 全部达成 → 不该发起任何复核调用（省一次 LLM 调用）
        StageBlueprintEntity stage = stage();
        stubModel("{\"results\":["
                + "{\"condition\":\"主角突破至炼气九层\",\"met\":true,\"chapterNo\":5,"
                + "\"evidence\":\"炼气九层的门槛被他一脚跨过\"},"
                + "{\"condition\":\"幽冥谷与外门公开敌对\",\"met\":true,\"chapterNo\":9,"
                + "\"evidence\":\"幽冥谷\"}]}");
        List<StageBlueprintEntity.ExitConditionResult> firstPass =
                service.review(module(), stage, summaries(), null);
        assertTrue(firstPass.get(0).getMet());

        org.mockito.Mockito.clearInvocations(llmGateway);
        List<StageBlueprintEntity.ExitConditionResult> out = service.recheckAgainstChapterText(
                module(), stage, firstPass, Map.of(5, "正文"), null);

        assertEquals(firstPass, out);
        org.mockito.Mockito.verify(llmGateway, org.mockito.Mockito.never()).complete(any(), any());
    }

    private StageBlueprintEntity stage() {
        return StageBlueprintEntity.builder()
                .stageNo(1)
                .startChapter(1)
                .endChapter(10)
                .stageGoal("主角站稳外门")
                .exitConditions(List.of("主角突破至炼气九层", "幽冥谷与外门公开敌对"))
                .build();
    }

    private List<ChapterSummaryEntity> summaries() {
        return List.of(
                ChapterSummaryEntity.builder()
                        .chapterNo(5).title("第5章")
                        .summary("小陈夜探废矿，丹田气旋一涨，炼气九层的门槛被他一脚跨过。")
                        .build(),
                ChapterSummaryEntity.builder()
                        .chapterNo(9).title("第9章")
                        .summary("幽冥谷使者夜袭外门。")
                        .characterStates(List.of(new ChapterSummaryEntity.StateEntry(
                                "幽冥谷", "公开敌对", "幽冥谷使者夜袭外门")))
                        .build());
    }

    private StoryVO.Module module() {
        return new StoryVO.Module();
    }

    private void stubModel(String content) {
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(content);
    }
}
