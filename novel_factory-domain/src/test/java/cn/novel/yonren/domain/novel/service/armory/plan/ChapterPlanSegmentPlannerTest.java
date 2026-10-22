package cn.novel.yonren.domain.novel.service.armory.plan;

import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.llm.LlmInvokeService;
import cn.novel.yonren.domain.novel.service.armory.llm.PlanBranchService;
import cn.novel.yonren.domain.novel.service.armory.prompt.valobj.PromptContext;
import cn.novel.yonren.domain.novel.service.job.GenerationJob;
import cn.novel.yonren.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 章节计划段规划执行器测试：三条规划路径（链式单段/链式分段/worker 惰性）共用的闭环——
 * 三级降级 + 章数纠错、编号平移、结构校验、主线推进闸门（驳回→带反馈重规划→二次放行）、
 * 分支推演回退。LlmInvokeService / PlanBranchService 打桩，其余全部真实执行
 */
class ChapterPlanSegmentPlannerTest {

    /**
     * 四档文案互不共享字符：夹具事件以档位原文收尾，若档位间共享前缀（如"双方"），
     * 会把事件模板的共享段顶过 10 字判定线、误触跨章场景重演校验（实测踩过）
     */
    private static final List<String> LADDER = List.of("彼此不知", "有人起疑", "互持实证", "摊牌对质");

    @Mock
    private LlmInvokeService llmInvokeService;

    @Mock
    private PlanBranchService planBranchService;

    private ChapterPlanSegmentPlanner planner;

    private final Map<String, String> usedPromptMap = new HashMap<>();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        planner = new ChapterPlanSegmentPlanner(llmInvokeService, planBranchService);
        when(planBranchService.enabled()).thenReturn(false);
    }

    /** 档位 compliant 的 3 章计划（逐档前进） */
    private static String compliantPlan() {
        return planJson("1", LADDER.get(0), "2", LADDER.get(1), "3", LADDER.get(2));
    }

    /** 档位原地踏步的 3 章计划（全部停在第二档，非 transition） */
    private static String stagnantPlan() {
        return planJson("1", LADDER.get(1), "2", LADDER.get(1), "3", LADDER.get(1));
    }

    /** 档位逐章不同：既满足停留规则，也满足"相邻章不得逐字相同" */
    private static String distinctPlan() {
        return planJson("1", LADDER.get(0), "2", LADDER.get(1), "3", LADDER.get(2));
    }

    /** 与 {@link #planJson} 同构但**缺 dialogueScenes**：专测对话承载闸门的"缺声明"分支 */
    private static String planJsonMissingDialogue(String... noAndBeat) {
        return planJson(false, noAndBeat);
    }

    private static String planJson(String... noAndBeat) {
        return planJson(true, noAndBeat);
    }

    /**
     * 计划 JSON 夹具。事件文本以**档位原文收尾**：档位逐章不同时，跨章关键事件的共享模板
     * 片段均 &lt;10 字，不会误触「跨章场景重演」校验（stagnant 夹具全章同档，属预期失败用例，
     * 多报一条重演不影响断言）。dialogueScenes 为合规声明（≥2 条逐字复制 + 双人名 + 单人事件
     * 不过半），使夹具默认通过对话承载闸门，各用例只针对自己要测的闸门。
     */
    private static String planJson(boolean withDialogueScenes, String... noAndBeat) {
        StringBuilder sb = new StringBuilder("{\"chapters\":[");
        for (int i = 0; i < noAndBeat.length; i += 2) {
            if (i > 0) {
                sb.append(",");
            }
            String beat = noAndBeat[i + 1];
            String scene1 = "角色甲与角色乙商议" + beat;
            String scene2 = "角色乙向角色甲求证" + beat;
            sb.append("{\"chapterNo\":").append(noAndBeat[i])
                    .append(",\"title\":\"第").append(noAndBeat[i]).append("章\",\"goal\":\"目标\",")
                    .append("\"characters\":[\"角色甲\",\"角色乙\"],")
                    .append("\"keyEvents\":[\"").append(scene1).append("\",\"").append(scene2)
                    .append("\",\"角色甲独自复盘").append(beat).append("\"],");
            if (withDialogueScenes) {
                sb.append("\"dialogueScenes\":[\"").append(scene1).append("\",\"").append(scene2).append("\"],");
            }
            sb.append("\"timeAdvance\":\"推进3天，至2003年1月").append(noAndBeat[i]).append("日\",")
                    .append("\"endingHook\":\"悬念\",")
                    .append("\"chapterType\":\"normal\",\"suspenseBeat\":\"").append(beat).append("\"}");
        }
        return sb.append("]}").toString();
    }

    private StageBlueprintEntity blueprintWithLadder() {
        return StageBlueprintEntity.builder()
                .stageNo(1).startChapter(1).endChapter(3)
                .coreSuspense("双方身份")
                .suspenseLadder(LADDER)
                .build();
    }

    /** 带章级主线推进的蓝图：与档位表是两个维度（档位答"走到第几格"，本项答"这章做了什么"） */
    private StageBlueprintEntity blueprintWithMainLine() {
        return StageBlueprintEntity.builder()
                .stageNo(1).startChapter(1).endChapter(3)
                .coreSuspense("双方身份")
                .suspenseLadder(LADDER)
                .mainLineByChapter(List.of(
                        new StageBlueprintEntity.MainLineBeat(1, "角色戊首次听到投资提议"),
                        new StageBlueprintEntity.MainLineBeat(2, "角色戊翻看凭证起疑"),
                        new StageBlueprintEntity.MainLineBeat(3, "夫妻夜谈定下对策")))
                .build();
    }

    /**
     * 2026-10-02：章级主线推进闸门必须接进规划器——章计划未回填 mainLineAdvance 时要打回重规划，
     * 且回注文本必须**点名问题**（否则"重规划"只是原样重生成一遍）。
     */
    @Test
    void planSegment_mainLineGateRejectsAndReplansWithFeedback() {
        // 计划 JSON 不含 mainLineAdvance ⇒ 必然命中"未声明"
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), any()))
                .thenReturn(distinctPlan(), distinctPlan());

        ChapterPlanSegmentPlanner.PlannedSegment planned = planner.planSegment(
                new StoryVO(), PromptContext.builder().build(), "prompt",
                1, 3, blueprintWithMainLine(), null, usedPromptMap);

        assertEquals(3, planned.plan().getChapters().size());
        verify(llmInvokeService, times(2)).invoke(any(), any(), any(), anyString(), any());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmInvokeService, times(2)).invoke(any(), any(), any(), promptCaptor.capture(), any());
        assertTrue(promptCaptor.getAllValues().get(1).contains("章级主线推进被机械驳回"),
                "回注重规划必须带上章级推进的差异化修正指令：" + promptCaptor.getAllValues().get(1));
        assertTrue(promptCaptor.getAllValues().get(1).contains("逐字取自"),
                "修正指令必须说清正确做法（逐字取自蓝图）");
    }

    @Test
    void planSegment_gateRejects_thenReplansWithFeedbackAndPasses() {
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), any()))
                .thenReturn(stagnantPlan(), compliantPlan());

        ChapterPlanSegmentPlanner.PlannedSegment planned = planner.planSegment(
                new StoryVO(), PromptContext.builder().build(), "prompt",
                1, 3, blueprintWithLadder(), null, usedPromptMap);

        assertEquals(3, planned.plan().getChapters().size());
        verify(llmInvokeService, times(2)).invoke(any(), any(), any(), anyString(), any());
        // 第二次调用必须携带机械驳回的回注文本
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmInvokeService, times(2)).invoke(any(), any(), any(), promptCaptor.capture(), any());
        assertTrue(promptCaptor.getAllValues().get(1).contains("【上一版计划被机械驳回】"),
                "回注重规划必须带上违规信息：" + promptCaptor.getAllValues());
    }

    @Test
    void planSegment_gateFailsTwice_warnsAndReleases() {
        // 两轮都原地踏步：重规划一次后告警放行（不抛异常——规划僵持不该让整批失败）
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), any()))
                .thenReturn(stagnantPlan(), stagnantPlan());

        ChapterPlanSegmentPlanner.PlannedSegment planned = planner.planSegment(
                new StoryVO(), PromptContext.builder().build(), "prompt",
                1, 3, blueprintWithLadder(), null, usedPromptMap);

        assertNotNull(planned);
        assertEquals(3, planned.plan().getChapters().size());
        verify(llmInvokeService, times(2)).invoke(any(), any(), any(), anyString(), any());
    }

    /**
     * 2026-10-10：对话承载闸门必须接进规划器——计划未声明 dialogueScenes 时打回重规划，
     * 回注文本必须点名问题，并给出"里程碑是落点不是事件本体"的修正方向
     * （解开蓝图逐字锁与"要有戏"的死结，否则模型只会原样重生成一遍）。
     */
    @Test
    void planSegment_dialogueGateRejectsAndReplansWithFeedback() {
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), any()))
                .thenReturn(planJsonMissingDialogue("1", LADDER.get(0), "2", LADDER.get(1), "3", LADDER.get(2)),
                        compliantPlan());

        ChapterPlanSegmentPlanner.PlannedSegment planned = planner.planSegment(
                new StoryVO(), PromptContext.builder().build(), "prompt",
                1, 3, blueprintWithLadder(), List.of("角色甲", "角色乙"), usedPromptMap);

        assertEquals(3, planned.plan().getChapters().size());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmInvokeService, times(2)).invoke(any(), any(), any(), promptCaptor.capture(), any());
        assertTrue(promptCaptor.getAllValues().get(1).contains("对话承载被机械驳回"),
                "回注重规划必须带上对话承载的差异化修正指令：" + promptCaptor.getAllValues().get(1));
        assertTrue(promptCaptor.getAllValues().get(1).contains("不是**事件本体**"),
                "修正指令必须解开'蓝图逐字锁'死结：" + promptCaptor.getAllValues().get(1));
    }

    @Test
    void planSegment_dialogueGateFailsTwice_warnsAndReleases() {
        // 两轮都缺声明：重规划一次后告警放行（与其它推进校验同款软闸门，不因规划僵持终止整批）
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), any()))
                .thenReturn(planJsonMissingDialogue("1", LADDER.get(0), "2", LADDER.get(1), "3", LADDER.get(2)),
                        planJsonMissingDialogue("1", LADDER.get(0), "2", LADDER.get(1), "3", LADDER.get(2)));

        ChapterPlanSegmentPlanner.PlannedSegment planned = planner.planSegment(
                new StoryVO(), PromptContext.builder().build(), "prompt",
                1, 3, blueprintWithLadder(), List.of("角色甲", "角色乙"), usedPromptMap);

        assertNotNull(planned);
        assertEquals(3, planned.plan().getChapters().size());
        verify(llmInvokeService, times(2)).invoke(any(), any(), any(), anyString(), any());
    }

    @Test
    void planSegment_skipsHoldGateWithoutUsableLadder() {
        // 无蓝图模式：**档位序比较**跳过（留痕日志），计划照常产出、不重规划
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), any()))
                .thenReturn(distinctPlan());

        ChapterPlanSegmentPlanner.PlannedSegment planned = planner.planSegment(
                new StoryVO(), PromptContext.builder().build(), "prompt",
                1, 3, null, null, usedPromptMap);

        assertEquals(3, planned.plan().getChapters().size());
        verify(llmInvokeService, times(1)).invoke(any(), any(), any(), anyString(), any());
    }

    /**
     * 2026-10-01 新增能力：**档位描述去重不依赖档位表**——无蓝图模式下同样要拦。
     *
     * <p>旧行为是"无档位表 ⇒ 整个闸门直接 return"，于是模型把同一句 suspenseBeat 复制 5 遍
     * 也无人过问（实测第 11–15 章正是如此）。去重只做逐字比较、不需要档位表，没有理由跟着跳过。
     */
    @Test
    void planSegment_duplicateBeatsStillReplannedWithoutLadder() {
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), any()))
                .thenReturn(stagnantPlan(), distinctPlan());

        ChapterPlanSegmentPlanner.PlannedSegment planned = planner.planSegment(
                new StoryVO(), PromptContext.builder().build(), "prompt",
                1, 3, null, null, usedPromptMap);

        assertEquals(3, planned.plan().getChapters().size());
        verify(llmInvokeService, times(2)).invoke(any(), any(), any(), anyString(), any());
    }

    @Test
    void planSegment_shiftsBatchLocalNumbersAndValidatesStructure() {
        // 模型输出段内编号 1..3，段起点 71：平移为全局编号后结构校验通过
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), any()))
                .thenReturn(planJson("1", LADDER.get(0), "2", LADDER.get(1), "3", LADDER.get(2)));

        ChapterPlanSegmentPlanner.PlannedSegment planned = planner.planSegment(
                new StoryVO(), PromptContext.builder().build(), "prompt",
                71, 73, blueprintWithLadder(), null, usedPromptMap);

        assertEquals(List.of(71, 72, 73),
                planned.plan().getChapters().stream().map(c -> c.getChapterNo()).toList());
    }

    @Test
    void planSegment_structureFailure_terminatesBatch() {
        // 可解析、章数正确，但编号不连续：结构校验直接判死（不重试——这是结构性故障）
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), any()))
                .thenReturn(planJson("5", LADDER.get(0)));

        assertThrows(AppException.class, () -> planner.planSegment(
                new StoryVO(), PromptContext.builder().build(), "prompt",
                1, 1, blueprintWithLadder(), null, usedPromptMap));
    }

    @Test
    void planSegment_countMismatch_retriesWithCorrection() {
        // 章数不符（预期 3 实际 1）：计入失败并带纠错指令重试
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), any()))
                .thenReturn(planJson("1", LADDER.get(0)), compliantPlan());

        ChapterPlanSegmentPlanner.PlannedSegment planned = planner.planSegment(
                new StoryVO(), PromptContext.builder().build(), "prompt",
                1, 3, blueprintWithLadder(), null, usedPromptMap);

        assertEquals(3, planned.plan().getChapters().size());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmInvokeService, times(2)).invoke(any(), any(), any(), promptCaptor.capture(), any());
        assertTrue(promptCaptor.getAllValues().get(1).contains("数量纠错"),
                "第二次调用应注入数量纠错指令");
    }

    @Test
    void planSegment_branchExplorationFeedsDirectionIntoPlanCall() {
        when(planBranchService.enabled()).thenReturn(true);
        when(planBranchService.exploreAndPick(any(), any(), anyString(), anyString(), any(), any()))
                .thenReturn("{\"structure\":\"双线并进\",\"direction\":\"先抑后扬\",\"risks\":[\"节奏压力\"]}");
        when(llmInvokeService.invoke(any(), any(), any(), contains("【已选规划方向】"), any()))
                .thenReturn(compliantPlan());

        ChapterPlanSegmentPlanner.PlannedSegment planned = planner.planSegment(
                new StoryVO(), PromptContext.builder().build(), "prompt",
                1, 3, blueprintWithLadder(), null, usedPromptMap);

        assertNotNull(planned);
        assertEquals(3, planned.plan().getChapters().size());
        // 正式计划调用必须携带胜出方向；分支方向调用发生（2 稿 + 评审均走 mock）
        verify(llmInvokeService, times(1)).invoke(any(), any(), any(), contains("【已选规划方向】"), any());
    }

    @Test
    void planSegment_branchFails_fallsBackToSingleDraft() {
        // 分支推演未产出可用方向：静默回退单稿路径，不失败
        when(planBranchService.enabled()).thenReturn(true);
        when(planBranchService.exploreAndPick(any(), any(), anyString(), anyString(), any(), any()))
                .thenReturn(null);
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), any()))
                .thenReturn(compliantPlan());

        ChapterPlanSegmentPlanner.PlannedSegment planned = planner.planSegment(
                new StoryVO(), PromptContext.builder().build(), "prompt",
                1, 3, blueprintWithLadder(), null, usedPromptMap);

        assertEquals(3, planned.plan().getChapters().size());
        verify(llmInvokeService, times(1)).invoke(any(), any(), any(), anyString(), any());
    }

    /**
     * 取消在重试轮次头部生效：第一轮输出章数不符 → 置位取消 → 第二轮**不得发出**，
     * 且以取消语义（AppException）终止而非"计划彻底失败"。
     */
    @Test
    void planSegment_cancelRequestedBetweenAttempts_stopsBeforeNextCall() {
        GenerationJob job = new GenerationJob("job-cancel-1");
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), any()))
                .thenAnswer(invocation -> {
                    // 第一次调用已发出，模拟用户在响应返回前点了取消
                    job.requestCancel();
                    return planJson("1", LADDER.get(0));
                });

        assertThrows(AppException.class, () -> planner.planSegment(
                new StoryVO(), PromptContext.builder().build(), "prompt",
                1, 3, blueprintWithLadder(), null, usedPromptMap, null, null, job));
        // 取消后不再重试：总共只发生一次模型调用
        verify(llmInvokeService, times(1)).invoke(any(), any(), any(), anyString(), any());
    }

    /** 取消信号在首次调用前已置位：一次模型调用都不应发出 */
    @Test
    void planSegment_alreadyCancelled_makesNoCall() {
        GenerationJob job = new GenerationJob("job-cancel-2");
        job.requestCancel();

        assertThrows(AppException.class, () -> planner.planSegment(
                new StoryVO(), PromptContext.builder().build(), "prompt",
                1, 3, blueprintWithLadder(), null, usedPromptMap, null, null, job));
        verify(llmInvokeService, never()).invoke(any(), any(), any(), anyString(), any());
    }
}
