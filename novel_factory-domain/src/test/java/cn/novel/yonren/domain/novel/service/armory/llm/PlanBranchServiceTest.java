package cn.novel.yonren.domain.novel.service.armory.llm;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.plan.ChapterPlanPromptService;
import cn.novel.yonren.domain.novel.service.armory.prompt.valobj.PromptContext;
import cn.novel.yonren.domain.novel.service.job.GenerationJob;
import cn.novel.yonren.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 分支方向推演测试：方向稿只校验小 JSON（structure/direction/risks）可解析；
 * 双稿全败返回 null、单稿可析取单稿、评审选择胜点方向
 */
class PlanBranchServiceTest {

    private static final String DIRECTION_A =
            "{\"structure\":\"起承转合\",\"direction\":\"稳健线：因果链稳妥推进\",\"risks\":[\"节奏偏平\"]}";
    private static final String DIRECTION_B =
            "{\"structure\":\"倒叙收束\",\"direction\":\"进取线：冲突前置\",\"risks\":[\"跟读成本高\"]}";

    @Mock
    private LlmInvokeService llmInvokeService;
    @Mock
    private LlmGateway llmGateway;
    @Mock
    private StoryProperties storyProperties;

    private PlanBranchService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        StoryProperties.PlanProperties plan = new StoryProperties.PlanProperties();
        plan.setBranches(2);
        when(storyProperties.getPlan()).thenReturn(plan);
        service = new PlanBranchService(llmInvokeService, llmGateway, storyProperties);
    }

    @Test
    void exploreAndPick_bothDirectionsValid_judgeReturnsWinnerRaw() {
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), anyMap()))
                .thenReturn(DIRECTION_A, DIRECTION_B);
        when(llmGateway.complete(any(), any(LlmCall.class)))
                .thenReturn("{\"winner\":\"B\",\"reason\":\"节奏更利落\"}");

        String winner = service.exploreAndPick(storyVO(), PromptContext.builder().build(),
                "规划 prompt", "chapter-plan", new HashMap<>());

        assertEquals(DIRECTION_B, winner);
        // 评审输入应包含两版方向原始 JSON，而非完整计划
        ArgumentCaptor<LlmCall> captor = ArgumentCaptor.forClass(LlmCall.class);
        org.mockito.Mockito.verify(llmGateway).complete(any(), captor.capture());
        String judgePrompt = captor.getValue().getUserPrompt();
        assertNotNull(judgePrompt);
        assertEquals(-1, judgePrompt.indexOf("chapters"), "评审输入不应携带完整章节计划");
    }

    @Test
    void exploreAndPick_allDirectionUnparseable_returnsNull() {
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), anyMap()))
                .thenReturn("垃圾输出一", "垃圾输出二");

        assertNull(service.exploreAndPick(storyVO(), PromptContext.builder().build(),
                "规划 prompt", "chapter-plan", new HashMap<>()));
    }

    @Test
    void exploreAndPick_singleDirectionParsable_returnsThatOne() {
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), anyMap()))
                .thenReturn(DIRECTION_A, "垃圾输出");

        String winner = service.exploreAndPick(storyVO(), PromptContext.builder().build(),
                "规划 prompt", "chapter-plan", new HashMap<>());

        assertEquals(DIRECTION_A, winner);
    }

    @Test
    void exploreAndPick_stripsChaptersSchemaFromBranchPrompts() {
        // 规划 prompt 尾部携带 chapters schema 指令块时，分支调用必须剥离它——
        // 否则显式 schema 是最强格式信号，模型必然无视"只输出方向"而输出完整章节计划
        String planPrompt = "规划正文若干要求\n\n" + ChapterPlanPromptService.PLAN_SCHEMA_MARKER
                + ":\n{\"chapters\":[...]}";
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), anyMap()))
                .thenReturn(DIRECTION_A, "垃圾输出");

        service.exploreAndPick(storyVO(), PromptContext.builder().build(), planPrompt, "chapter-plan", new HashMap<>());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmInvokeService, org.mockito.Mockito.times(2))
                .invoke(any(), any(), any(), promptCaptor.capture(), anyMap());
        for (String branchPrompt : promptCaptor.getAllValues()) {
            assertEquals(-1, branchPrompt.indexOf("chapters"), "分支 prompt 不应携带 chapters schema 指令块");
        }
    }

    private StoryVO storyVO() {
        StoryVO storyVO = new StoryVO();
        StoryVO.Module module = new StoryVO.Module();
        StoryVO.Module.ChatModel chatModel = new StoryVO.Module.ChatModel();
        chatModel.setMaxTokens(1024L);
        module.setChatModel(chatModel);
        storyVO.setModule(module);
        return storyVO;
    }

    // ==================== 规划期取消 ====================

    /** 稳健线返回前已置位取消：进取线不得再发出（省一次调用），且以取消语义抛出 */
    @Test
    void exploreAndPick_cancelledAfterFirstDraft_skipsSecondDraft() {
        GenerationJob job = new GenerationJob("job-branch-1");
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), anyMap()))
                .thenAnswer(invocation -> {
                    job.requestCancel();
                    return DIRECTION_A;
                });

        assertThrows(AppException.class, () -> service.exploreAndPick(
                storyVO(), PromptContext.builder().build(), "规划 prompt", "chapter-plan",
                new HashMap<>(), job));
        org.mockito.Mockito.verify(llmInvokeService, org.mockito.Mockito.times(1))
                .invoke(any(), any(), any(), anyString(), anyMap());
    }

    /** 取消已置位：一次分支调用都不发，也不触发评审 */
    @Test
    void exploreAndPick_alreadyCancelled_makesNoCall() {
        GenerationJob job = new GenerationJob("job-branch-2");
        job.requestCancel();

        assertThrows(AppException.class, () -> service.exploreAndPick(
                storyVO(), PromptContext.builder().build(), "规划 prompt", "chapter-plan",
                new HashMap<>(), job));
        org.mockito.Mockito.verify(llmInvokeService, org.mockito.Mockito.never())
                .invoke(any(), any(), any(), anyString(), anyMap());
        org.mockito.Mockito.verify(llmGateway, org.mockito.Mockito.never()).complete(any(), any(LlmCall.class));
    }

    /**
     * **回归**：P1 上线后分支推演连续两个作业"进取线解析失败"。
     *
     * <p>根因：`stripPlanSchema` 原先只从 schema 标记截尾，而「8.2 章级主线推进要求」与
     * 「【章级主线推进】块」都在 schema **之前**，于是残留。那块是"第16章：… / 第17章：…"的
     * 逐章清单——比 schema 更具体——模型于是交回**章节计划数组**而不是分支推演要的方向对象
     * （日志里 4 条 {@code BeanOutputConverter} 解析失败的原始输出正是带 mainLineAdvance 的章节计划）。
     */
    @Test
    void stripPlanSchema_removesMainlineBlocksButKeepsUsefulSections() {
        String prompt = "要求列表\n"
                + ChapterPlanPromptService.MAINLINE_REQUIREMENT_MARKER
                + "——**章级主线推进**：每章必须回填……\n"
                + "9. 不要输出 Markdown\n"
                + "\n\n【悬念推进锚】档位表原文一二三\n"
                + "\n\n" + ChapterPlanPromptService.MAINLINE_BLOCK_MARKER
                + "（第 16-20 章——每章必须回填 mainLineAdvance）：\n  第16章：某事\n  第17章：另一事\n"
                + "\n\n【伏笔长度反馈】不要埋了就收\n"
                + "\n\n" + ChapterPlanPromptService.PLAN_SCHEMA_MARKER + ":\n{\"chapters\":[]}";

        String out = PlanBranchService.stripPlanSchema(prompt);

        assertFalse(out.contains("章级主线推进"), "逐章清单必须剥离，否则模型会改答章节计划：" + out);
        assertFalse(out.contains("mainLineAdvance"), "8.2 要求行也必须剥离，否则留下悬空引用：" + out);
        assertFalse(out.contains(ChapterPlanPromptService.PLAN_SCHEMA_MARKER), "schema 仍应截尾：" + out);
        // 仍然有用的块要保住——精确删除而非"从最早标记一截了之"
        assertTrue(out.contains("【悬念推进锚】"), "档位锚对分支方向仍有用，不该被切掉：" + out);
        assertTrue(out.contains("【伏笔长度反馈】"), "反馈块对分支方向仍有用，不该被切掉：" + out);
        assertTrue(out.contains("9. 不要输出 Markdown"), "编号要求其余项应保留：" + out);
    }

    /** 无任何标记（如单测的简化 prompt）时原样返回，不得抛异常或截断 */
    @Test
    void stripPlanSchema_returnsInputWhenNoMarker() {
        assertEquals("裸 prompt", PlanBranchService.stripPlanSchema("裸 prompt"));
        assertNull(PlanBranchService.stripPlanSchema(null));
    }
}