package cn.novel.yonren.infrastructure.gateway;

import cn.novel.yonren.domain.novel.adapter.llm.ChatGptGateway;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.LlmRuntimeConfig;
import cn.novel.yonren.domain.novel.service.job.GenerationJob;
import cn.novel.yonren.domain.novel.service.job.JobRegistry;
import cn.novel.yonren.domain.novel.service.job.LlmBudgetFuse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LLM 网关测试：模型客户端缓存键必须区分 buildChatModel 固化的全部参数——
 * temperature 曾不在键内，同 model+maxTokens 不同温度的场景复用同一客户端，
 * 先构建者的温度对后者生效（summary 0.2 vs chapter-content 0.5 碰撞）
 */
class SpringAiLlmGatewayTest {

    @Test
    void complete_routesChatGptModeToResponsesGateway() {
        LlmRuntimeConfig runtime = mock(LlmRuntimeConfig.class);
        LlmBudgetFuse budget = mock(LlmBudgetFuse.class);
        ChatGptGateway chatGpt = mock(ChatGptGateway.class);
        StoryVO.Module module = new StoryVO.Module();
        module.setAiApi(api());
        StoryVO.Module.ChatModel model = chatModel("gpt-6.1-sol", 16384L, 0.2);
        module.setChatModel(model);
        LlmCall call = LlmCall.builder().systemPrompt("system").userPrompt("user").build();
        when(runtime.usesChatGptAuth()).thenReturn(true);
        when(runtime.applyApi(module)).thenReturn(module);
        when(runtime.applyModel(null, model)).thenReturn(model);
        when(chatGpt.complete("gpt-6.1-sol", "system", "user"))
                .thenReturn(new ChatGptGateway.Completion("{\"ok\":true}", 10, 5, 15));
        SpringAiLlmGateway gateway = new SpringAiLlmGateway(1, 20, 600, runtime, budget, chatGpt);

        assertEquals("{\"ok\":true}", gateway.complete(module, call));
        verify(chatGpt).complete("gpt-6.1-sol", "system", "user");
    }

    @Test
    void cacheKeyDistinguishesAllBuildParameters() {
        StoryVO.Module.AiApi api = api();

        StoryVO.Module.ChatModel summary = chatModel("qwen3.8-flash", 16384L, 0.2);
        StoryVO.Module.ChatModel content = chatModel("qwen3.8-flash", 16384L, 0.5);
        StoryVO.Module.ChatModel sameAsSummary = chatModel("qwen3.8-flash", 16384L, 0.2);

        // 同 model+maxTokens 不同温度：必须视为不同客户端
        assertNotEquals(SpringAiLlmGateway.chatModelCacheKey(api, summary),
                SpringAiLlmGateway.chatModelCacheKey(api, content));
        // 完全一致的参数：复用同一缓存客户端
        assertEquals(SpringAiLlmGateway.chatModelCacheKey(api, summary),
                SpringAiLlmGateway.chatModelCacheKey(api, sameAsSummary));
        // 模型名/输出上限不同：区分
        assertNotEquals(SpringAiLlmGateway.chatModelCacheKey(api, summary),
                SpringAiLlmGateway.chatModelCacheKey(api, chatModel("deepseek-v4-pro", 16384L, 0.2)));
        assertNotEquals(SpringAiLlmGateway.chatModelCacheKey(api, summary),
                SpringAiLlmGateway.chatModelCacheKey(api, chatModel("qwen3.8-flash", 2048L, 0.2)));

        // ZDR 会改变请求头行为，必须区分缓存客户端，避免开关切换后复用旧拦截器
        StoryVO.Module.AiApi zdrApi = api();
        zdrApi.setZeroDataRetention(true);
        assertNotEquals(SpringAiLlmGateway.chatModelCacheKey(api, summary),
                SpringAiLlmGateway.chatModelCacheKey(zdrApi, summary));
    }

    @Test
    void looksTruncatedJson_rejectsRealAccidentShapes() {
        // 真实事故形态（llm-usage.jsonl 2026-10-19）：deepseek 备选 16383/16384 撞上限，
        // 残 JSON 死在中文引号中间——尾字符判不出来，必须真解析
        assertTrue(SpringAiLlmGateway.looksTruncatedJson(
                "{\"chapters\":[{\"chapterNo\":1,\"goal\":\"…李默辩解：「我"));
        // 围栏包裹的截断（responseFormat 不守约的常见形态）
        assertTrue(SpringAiLlmGateway.looksTruncatedJson(
                "```json\n{\"chapters\":[{\"goal\":\"未闭合"));
        // 反例保护：撞上限但 JSON 完整（26524 token/4296 字符的合法计划）必须放行
        assertFalse(SpringAiLlmGateway.looksTruncatedJson(
                "{\"chapters\":[{\"chapterNo\":1,\"goal\":\"完整的一章计划\"}]}"));
        assertFalse(SpringAiLlmGateway.looksTruncatedJson(
                "```json\n{\"ok\":true}\n```"));
    }

    @Test
    void filterBurned_removesBurnedStations_allBurnedFailsFastOrFailSoft() {
        // 事故核心路径：glm-5.3 烧穿后，黑名单必须把它从后续调用的降级链里滤掉；
        // 全链烧穿时 fail 模式快速失败（不重烧）、skip 模式回退原链走既有 fail-soft 出口
        JobRegistry registry = new JobRegistry();
        SpringAiLlmGateway skipGateway = new SpringAiLlmGateway(1, 20, 600, false,
                new LlmRuntimeConfig(), mock(LlmBudgetFuse.class),
                mock(ChatGptGateway.class), registry);
        GenerationJob job = new GenerationJob("job-burn");
        registry.register(job);
        job.recordBurnedModel("chapter-plan", "glm-5.3");

        List<StoryVO.Module.ChatModel> chain = List.of(
                chatModel("glm-5.3", 16384L, 0.7), chatModel("deepseek-v4-pro", 16384L, 0.7));
        List<StoryVO.Module.ChatModel> live = skipGateway.filterBurned(chain, job, "chapter-plan");
        assertEquals(1, live.size(), "烧穿站必须被滤掉");
        assertEquals("deepseek-v4-pro", live.get(0).getModel());

        // 全链烧穿：skip 模式回退原链（长度不变，由链上模型自己再失败一次走既有出口）
        job.recordBurnedModel("chapter-plan", "deepseek-v4-pro");
        List<StoryVO.Module.ChatModel> fellBack = skipGateway.filterBurned(chain, job, "chapter-plan");
        assertEquals(2, fellBack.size(), "skip 模式全链烧穿回退原链");

        // fail 模式全链烧穿：直接快速失败，黑名单内的模型不再重烧
        SpringAiLlmGateway failGateway = new SpringAiLlmGateway(1, 20, 600, true,
                new LlmRuntimeConfig(), mock(LlmBudgetFuse.class),
                mock(ChatGptGateway.class), registry);
        SpringAiLlmGateway.BudgetBurnException e = assertThrows(
                SpringAiLlmGateway.BudgetBurnException.class,
                () -> failGateway.filterBurned(chain, job, "chapter-plan"));
        assertTrue(e.getMessage().contains("chapter-plan"), "必须指明烧穿场景");
        assertTrue(e.getMessage().contains("deepseek-v4-pro"), "必须点名烧穿模型");

        // 无作业语境（离线工具）：原链透传，不启用黑名单
        assertEquals(2, skipGateway.filterBurned(chain, null, "chapter-plan").size());
    }

    @Test
    void budgetBurnException_carriesAttributionEvidence() {
        // fail 模式的终止异常必须自带归因证据：场景/模型/用量/处置指引，
        // 作业层 fail-fast 后用户看错误消息就能定位该改哪个场景的模型
        SpringAiLlmGateway.BudgetBurnException e = new SpringAiLlmGateway.BudgetBurnException(
                cn.novel.yonren.types.enums.ModelScene.CHAPTER_PLAN, "glm-5.3", 32000, 16384L, 0);
        assertTrue(e.getMessage().contains("on-budget-burn=fail"));
        assertTrue(e.getMessage().contains("scene=CHAPTER_PLAN"));
        assertTrue(e.getMessage().contains("glm-5.3"));
        assertTrue(e.getMessage().contains("32000/16384"));
        assertTrue(e.getMessage().contains("resume"), "必须提示已落盘章节可续写");
    }

    private StoryVO.Module.AiApi api() {
        StoryVO.Module.AiApi api = new StoryVO.Module.AiApi();
        api.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode");
        api.setApiKey("sk-test");
        return api;
    }

    private StoryVO.Module.ChatModel chatModel(String model, Long maxTokens, Double temperature) {
        StoryVO.Module.ChatModel chat = new StoryVO.Module.ChatModel();
        chat.setModel(model);
        chat.setMaxTokens(maxTokens);
        chat.setTemperature(temperature);
        chat.setEnableThinking(false);
        return chat;
    }
}
