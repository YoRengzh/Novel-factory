package cn.novel.yonren.domain.novel.service.armory.node;

import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.domain.novel.service.armory.llm.LlmInvokeService;
import cn.novel.yonren.domain.novel.service.armory.memory.RollingOutlineService;
import cn.novel.yonren.domain.novel.service.armory.plan.ChapterPlanSegmentPlanner;
import cn.novel.yonren.domain.novel.service.armory.prompt.valobj.PromptContext;
import cn.novel.yonren.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 章节计划调用节点测试：三级降级行为（直接解析/修复/重试/硬失败）；
 * 跨阶段批次逐段调用、段内编号校正与合并。规划闭环经真实 ChapterPlanSegmentPlanner 执行，
 * 仅 LLM 调用与分支推演打桩
 */
class CallChapterPlanLlmNodeTest {

    /**
     * 合规计划夹具：双人名事件 + dialogueScenes 逐字声明——对话承载闸门（2026-10-10）上线后，
     * 缺声明的计划会被打回重规划，单次调用断言的用例必须默认过全部闸门。
     * 事件文本以章号开头、模板片段 ≤8 字：两章间共享段不触"跨章场景重演"10 字判定线
     */
    private static final String GOOD_PLAN =
            "{\"chapters\":[{\"chapterNo\":1,\"title\":\"第一章\",\"goal\":\"目标\","
                    + "\"characters\":[\"甲\",\"乙\"],"
                    + "\"keyEvents\":[\"第1幕：甲与乙交锋\",\"第1场：乙向甲让步\"],"
                    + "\"dialogueScenes\":[\"第1幕：甲与乙交锋\",\"第1场：乙向甲让步\"],"
                    + "\"timeAdvance\":\"推进3天，至2002年7月20日\",\"endingHook\":\"悬念\",\"chapterType\":\"normal\"}]}";
    private static final String DANGLING_COMMA_PLAN =
            "{\"chapters\":[{\"chapterNo\":1,\"title\":\"第一章\",\"goal\":\"目标\","
                    + "\"characters\":[\"甲\",\"乙\"],"
                    + "\"keyEvents\":[\"第1幕：甲与乙交锋\",\"第1场：乙向甲让步\"],"
                    + "\"dialogueScenes\":[\"第1幕：甲与乙交锋\",\"第1场：乙向甲让步\"],"
                    + "\"timeAdvance\":\"推进3天，至2002年7月20日\",\"endingHook\":\"悬念\",\"chapterType\":\"normal\",}]}";

    @Mock
    private LlmInvokeService llmInvokeService;

    @Mock
    private cn.novel.yonren.domain.novel.service.armory.llm.PlanBranchService planBranchService;

    @Mock
    private cn.novel.yonren.domain.novel.service.armory.memory.ChapterMemoryService chapterMemoryService;

    private CallChapterPlanLlmNode node;

    private DefaultArmoryFactory.DynamicContext dynamicContext;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        org.mockito.Mockito.when(planBranchService.enabled()).thenReturn(false);
        node = new CallChapterPlanLlmNode(new ChapterPlanSegmentPlanner(llmInvokeService, planBranchService));
        // 节点经 @Resource 注入记忆服务（对话承载闸门的人名来源）：单测用反射接线，名册给空表走降级口径
        java.lang.reflect.Field memoryField = CallChapterPlanLlmNode.class.getDeclaredField("chapterMemoryService");
        memoryField.setAccessible(true);
        memoryField.set(node, chapterMemoryService);
        org.mockito.Mockito.when(chapterMemoryService.characterRoster(any())).thenReturn(List.of());
        dynamicContext = new DefaultArmoryFactory.DynamicContext();
        dynamicContext.setPrompt("测试 prompt");
    }

    @Test
    void parsesValidOutputOnFirstAttempt() {
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), any())).thenReturn(GOOD_PLAN);

        ChapterPlanAggregate plan = node.invokePlanWithFallback(command(), dynamicContext, promptContext());

        assertNotNull(plan);
        assertEquals(1, plan.getChapters().size());
        assertEquals(GOOD_PLAN, dynamicContext.getRawResult());
        assertNotNull(dynamicContext.getChapterPlanAggregate());
        verify(llmInvokeService, times(1)).invoke(any(), any(), any(), anyString(), any());
    }

    @Test
    void repairsDanglingCommaWithoutRetry() {
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), any())).thenReturn(DANGLING_COMMA_PLAN);

        ChapterPlanAggregate plan = node.invokePlanWithFallback(command(), dynamicContext, promptContext());

        assertNotNull(plan);
        assertEquals(1, plan.getChapters().size());
        verify(llmInvokeService, times(1)).invoke(any(), any(), any(), anyString(), any());
    }

    @Test
    void retriesAndRecoversOnSecondCall() {
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), any()))
                .thenReturn("抱歉，我无法按要求输出", GOOD_PLAN);

        ChapterPlanAggregate plan = node.invokePlanWithFallback(command(), dynamicContext, promptContext());

        assertNotNull(plan);
        assertEquals(1, plan.getChapters().size());
        verify(llmInvokeService, times(2)).invoke(any(), any(), any(), anyString(), any());
    }

    @Test
    void retriesWhenParsableButChapterCountMismatch() {
        // 模型输出 JSON 可解析但章数≠预期（少章/多章）：必须计入失败重试，而不是放行到校验节点整批终止
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), any()))
                .thenReturn(planJson(1, 2), GOOD_PLAN);

        ChapterPlanAggregate plan = node.invokePlanWithFallback(command(), dynamicContext, promptContext());

        assertNotNull(plan);
        assertEquals(1, plan.getChapters().size());
        // 第二次调用必须携带上轮章数纠错指令，把"预期 1 章 vs 上轮 2 章"钉给模型
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmInvokeService, times(2)).invoke(any(), any(), any(), promptCaptor.capture(), any());
        assertTrue(promptCaptor.getAllValues().get(1).contains("数量纠错"),
                "第二次调用应注入数量纠错指令：" + promptCaptor.getAllValues());
    }

    @Test
    void throwsWhenBothAttemptsChapterCountMismatch() {
        // 两轮都少章（1 章预期，输出 2 章）：重试耗尽后按整批失败抛异常
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), any()))
                .thenReturn(planJson(1, 2), planJson(1, 2, 3));

        assertThrows(AppException.class,
                () -> node.invokePlanWithFallback(command(), dynamicContext, promptContext()));

        verify(llmInvokeService, times(2)).invoke(any(), any(), any(), anyString(), any());
    }

    @Test
    void throwsWhenBothAttemptsUnparseable() {
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), any()))
                .thenReturn("垃圾输出一", "垃圾输出二");

        assertThrows(AppException.class,
                () -> node.invokePlanWithFallback(command(), dynamicContext, promptContext()));

        verify(llmInvokeService, times(2)).invoke(any(), any(), any(), anyString(), any());
    }

    @Test
    void segmentedBatchMergesPerStagePlansWithInternalNumberShift() {
        dynamicContext.setPlanSegments(List.of(
                new RollingOutlineService.StageSegment(1, 2, null),
                new RollingOutlineService.StageSegment(3, 4, null)));
        dynamicContext.setPlanSegmentPrompts(List.of("段一 prompt", "段二 prompt"));
        // 模型惯性：每段都输出段内编号（1,2）
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), any()))
                .thenReturn(planJson(1, 2), planJson(1, 2));

        ChapterPlanAggregate plan = node.invokePlanWithFallback(command(), dynamicContext, promptContext());

        assertNotNull(plan);
        assertEquals(4, plan.getChapters().size());
        assertEquals(List.of(1, 2, 3, 4),
                plan.getChapters().stream().map(ChapterPlanItemEntity::getChapterNo).toList());
        assertTrue(dynamicContext.getRawResult().contains("分段计划原始输出"));
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmInvokeService, times(2)).invoke(any(), any(), any(), promptCaptor.capture(), any());
        assertEquals(List.of("段一 prompt", "段二 prompt"), promptCaptor.getAllValues());
    }

    @Test
    void segmentedBatchKeepsGlobalNumbersUntouched() {
        dynamicContext.setPlanSegments(List.of(
                new RollingOutlineService.StageSegment(1, 2, null),
                new RollingOutlineService.StageSegment(3, 4, null)));
        dynamicContext.setPlanSegmentPrompts(List.of("段一 prompt", "段二 prompt"));
        // 模型已输出全局编号：段内校正不得平移
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), any()))
                .thenReturn(planJson(1, 2), planJson(3, 4));

        ChapterPlanAggregate plan = node.invokePlanWithFallback(command(), dynamicContext, promptContext());

        assertEquals(List.of(1, 2, 3, 4),
                plan.getChapters().stream().map(ChapterPlanItemEntity::getChapterNo).toList());
    }

    @Test
    void segmentedBatchSegmentFailureTerminatesBatch() {
        dynamicContext.setPlanSegments(List.of(
                new RollingOutlineService.StageSegment(1, 2, null),
                new RollingOutlineService.StageSegment(3, 4, null)));
        dynamicContext.setPlanSegmentPrompts(List.of("段一 prompt", "段二 prompt"));
        // 第一段两次全败：本批终止，第二段不再调用
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), any()))
                .thenReturn("垃圾输出一", "垃圾输出二");

        assertThrows(AppException.class,
                () -> node.invokePlanWithFallback(command(), dynamicContext, promptContext()));

        verify(llmInvokeService, times(2)).invoke(any(), any(), any(), anyString(), any());
    }

    private String planJson(int... chapterNos) {
        StringBuilder sb = new StringBuilder("{\"chapters\":[");
        for (int i = 0; i < chapterNos.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            int no = chapterNos[i];
            String scene1 = "第" + no + "幕：甲与乙交锋";
            String scene2 = "第" + no + "场：乙向甲让步";
            sb.append("{\"chapterNo\":").append(no)
                    .append(",\"title\":\"第").append(no).append("章\",\"goal\":\"目标\",")
                    .append("\"characters\":[\"甲\",\"乙\"],")
                    .append("\"keyEvents\":[\"").append(scene1).append("\",\"").append(scene2).append("\"],")
                    .append("\"dialogueScenes\":[\"").append(scene1).append("\",\"").append(scene2).append("\"],")
                    .append("\"timeAdvance\":\"推进3天，至2002年7月20日\",\"endingHook\":\"悬念\",\"chapterType\":\"normal\"}");
        }
        return sb.append("]}").toString();
    }

    private ArmoryCommandEntity command() {
        StoryContextEntity storyContext = new StoryContextEntity();
        storyContext.setTheme("测试题材");
        storyContext.setStyle("测试风格");
        storyContext.setChapterCount(1);
        ArmoryCommandEntity command = new ArmoryCommandEntity();
        command.setStoryContextEntity(storyContext);
        command.setStoryVO(new StoryVO());
        return command;
    }

    private PromptContext promptContext() {
        return PromptContext.builder().totalChapters(1).build();
    }

}
