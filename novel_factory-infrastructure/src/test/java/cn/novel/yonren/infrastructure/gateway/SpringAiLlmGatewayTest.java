package cn.novel.yonren.infrastructure.gateway;

import cn.novel.yonren.domain.novel.adapter.llm.ChatGptGateway;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.LlmRuntimeConfig;
import cn.novel.yonren.domain.novel.service.job.LlmBudgetFuse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
