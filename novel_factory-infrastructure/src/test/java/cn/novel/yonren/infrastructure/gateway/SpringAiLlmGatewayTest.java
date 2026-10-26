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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
        // 全局兜底重构后，网关在解析主模型前会先应用一次 applyFallback；
        // mock 默认返回 null 会把 module 变成空设置（生产实现无覆盖时原样返回）
        when(runtime.applyFallback(any())).thenAnswer(inv -> inv.getArgument(0));
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

        // 协议决定走哪套传输实现（Chat Completions / Messages / Responses），
        // 同地址同模型不同协议必须是不同客户端——否则切协议后仍复用旧协议实例
        StoryVO.Module.AiApi anthropicApi = api();
        anthropicApi.setProtocol("anthropic");
        assertNotEquals(SpringAiLlmGateway.chatModelCacheKey(api, summary),
                SpringAiLlmGateway.chatModelCacheKey(anthropicApi, summary));
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

    // ===== Anthropic 每调用级 options：思考/温度按本次生效上限整体裁决 =====
    // 回归锚点：runtime options 合并规则是"非空字段压过 default"，思考若固化在默认里，
    // 小参数调用会拼出 max_tokens < budget_tokens 的非法组合被 400（见 anthropicCallOptions 注释）

    @Test
    void anthropicCallOptions_thinkingBudgetFollowsEffectiveMax() {
        StoryVO.Module.ChatModel thinking = chatModel("claude-4-5-sonnet", 16384L, 0.7);
        thinking.setEnableThinking(true);

        org.springframework.ai.anthropic.AnthropicChatOptions normal =
                SpringAiLlmGateway.anthropicCallOptions(thinking, null);
        assertEquals(16384, normal.getMaxTokens().intValue(), "max_tokens 必填，每次都带");
        assertNotNull(normal.getThinking(), "预算充足时按配置开思考");
        assertEquals(8192, normal.getThinking().budgetTokens().intValue(), "budget=上限一半");
        assertTrue(normal.getThinking().budgetTokens() < normal.getMaxTokens(),
                "不变式：budget 必须 < max_tokens（否则 API 400）");
        assertEquals(1.0, normal.getTemperature(), "思考态唯一允许温度，压过配置的 0.7");
    }

    @Test
    void anthropicCallOptions_smallCallDropsThinkingInsteadOfIllegalBudget() {
        StoryVO.Module.ChatModel thinking = chatModel("claude-4-5-sonnet", 16384L, 0.7);
        thinking.setEnableThinking(true);

        // 选择器类小调用 maxTokens=1024：开思考不可能合法（budget≥1024 需 max>2048）→ 本次不开
        LlmCall small = LlmCall.builder().maxTokens(1024).build();
        org.springframework.ai.anthropic.AnthropicChatOptions options =
                SpringAiLlmGateway.anthropicCallOptions(thinking, small);
        assertEquals(1024, options.getMaxTokens().intValue());
        assertNull(options.getThinking(), "预算不足的调用不开思考，而不是拼非法 budget");
        assertEquals(0.7, options.getTemperature(), "无思考时温度按配置");
    }

    @Test
    void anthropicCallOptions_thinkingBeatsCallLevelTemperature() {
        StoryVO.Module.ChatModel thinking = chatModel("claude-4-5-sonnet", 16384L, null);
        thinking.setEnableThinking(true);

        // 调用级温度 0.3 + 思考开启：0.3 与 thinking 组合必被 400，思考约束优先
        LlmCall temp = LlmCall.builder().maxTokens(4096).temperature(0.3).build();
        org.springframework.ai.anthropic.AnthropicChatOptions options =
                SpringAiLlmGateway.anthropicCallOptions(thinking, temp);
        assertEquals(1.0, options.getTemperature());
        assertNotNull(options.getThinking());
        assertEquals(2048, options.getThinking().budgetTokens().intValue(), "budget 跟随本次上限 4096");
    }

    @Test
    void anthropicCallOptions_plainModelDefaults() {
        // 非思考模型：maxTokens 兜底必填值，温度 调用级 ?? 配置级，无思考
        StoryVO.Module.ChatModel plain = chatModel("claude-4-5-haiku", null, null);
        org.springframework.ai.anthropic.AnthropicChatOptions options =
                SpringAiLlmGateway.anthropicCallOptions(plain, null);
        assertEquals(8192, options.getMaxTokens().intValue(), "配置留空给 Messages API 必填兜底");
        assertNull(options.getThinking());
        assertNull(options.getTemperature());

        LlmCall temp = LlmCall.builder().temperature(0.3).build();
        assertEquals(0.3, SpringAiLlmGateway.anthropicCallOptions(plain, temp).getTemperature().doubleValue());
    }

    // ===== Anthropic 思考块剥离（真机实证：Spring AI 1.0.0 把 thinking 块也包装成 Generation =====
    // 且排在首位，网关 getResult() 会把思考文本当正文——必须在拦截器里重写 content

    @Test
    void stripAnthropicThinking_removesThinkingAndKeepsCleanText() throws Exception {
        String body = "{\"id\":\"m1\",\"content\":["
                + "{\"type\":\"thinking\",\"thinking\":\"先想想怎么答\",\"signature\":\"sig\"},"
                + "{\"type\":\"text\",\"text\":\"正文回答\"}"
                + "],\"usage\":{\"input_tokens\":10,\"output_tokens\":20}}";
        SpringAiLlmGateway.ReasoningCapture.clearAll();
        byte[] stripped = SpringAiLlmGateway.stripAnthropicThinking(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        String json = new String(stripped, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json.contains("正文回答"), "正文保留");
        assertFalse(json.contains("先想想怎么答"), "思考文本必须剥离，否则 getResult() 拿到的是思考");
        assertFalse(json.contains("thinking"), "thinking 块整体删除");
        var snapshot = SpringAiLlmGateway.ReasoningCapture.take();
        assertEquals(6, snapshot.chars(), "思考字符数计入观测信号");
        assertNull(snapshot.tokens(), "Anthropic 不上报思考 token");
        SpringAiLlmGateway.ReasoningCapture.clearAll();
    }

    @Test
    void stripAnthropicThinking_pureThinkingBecomesEmptyContent() throws Exception {
        // 思考吃光预算（烧穿形态）：无 text 块 → content 置空，网关按"空回复"换站/fail-soft
        String body = "{\"content\":[{\"type\":\"thinking\",\"thinking\":\"想了很久\"}]}";
        byte[] stripped = SpringAiLlmGateway.stripAnthropicThinking(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(stripped);
        assertTrue(root.get("content").isArray() && root.get("content").isEmpty(),
                "纯思考响应的 content 必须置空数组");
    }

    @Test
    void stripAnthropicThinking_noThinkingReturnsBodyUnchanged() throws Exception {
        String body = "{\"content\":[{\"type\":\"text\",\"text\":\"普通回答\"}],\"usage\":{\"input_tokens\":1,\"output_tokens\":2}}";
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertSame(bytes, SpringAiLlmGateway.stripAnthropicThinking(bytes),
                "无思考块零改写原样返回（绝大多数调用）");
    }

    @Test
    void stripAnthropicThinking_mergesMultipleTextBlocks() throws Exception {
        // Spring AI 每个 text 块一个 Generation，网关只取首个——多块必须合并否则静默丢正文
        String body = "{\"content\":["
                + "{\"type\":\"thinking\",\"thinking\":\"t\"},"
                + "{\"type\":\"text\",\"text\":\"上半段\"},{\"type\":\"text\",\"text\":\"下半段\"}]}";
        byte[] stripped = SpringAiLlmGateway.stripAnthropicThinking(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(stripped);
        assertEquals(1, root.get("content").size(), "多 text 块合并为单块");
        assertEquals("上半段下半段", root.get("content").get(0).get("text").asText());
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
