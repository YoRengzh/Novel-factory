package cn.novel.yonren.infrastructure.gateway;

import cn.novel.yonren.domain.novel.adapter.llm.ChatGptGateway;
import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.LlmRuntimeConfig;
import cn.novel.yonren.domain.novel.service.armory.llm.LlmErrorClassifier;
import cn.novel.yonren.domain.novel.service.armory.llm.ModelFallbackChain;
import cn.novel.yonren.domain.novel.service.job.LlmBudgetFuse;
import cn.novel.yonren.types.enums.ModelScene;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LLM 网关的 Spring AI 适配实现：OpenAI 客户端构建、消息组装、参数覆盖
 * 等供应商细节全部收敛于此，领域层只依赖 LlmGateway 端口。
 * 模型客户端按供应商配置缓存复用（单例化），usage 以 JSONL 记账（覆盖全部调用方）。
 */
@Component
public class SpringAiLlmGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLlmGateway.class);

    /** usage 记账文件，与 logback 的 CWD 相对日志目录约定一致 */
    private static final Path USAGE_LOG_PATH = Path.of("data", "log", "llm-usage.jsonl");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Object USAGE_LOCK = new Object();

    /** 传输层最大尝试次数（默认 1，即不重试；5xx 重试收权到业务层，避免 token 白烧） */
    private final int maxAttempts;
    private final RetryTemplate retryTemplate;

    /** 模型客户端缓存：baseUrl|apiKey|model|maxTokens -> OpenAiChatModel */
    private final ConcurrentHashMap<String, OpenAiChatModel> chatModelCache = new ConcurrentHashMap<>();

    /**
     * 出网请求工厂（连接/读取超时）：RestClient.builder() 与 JDK HttpClient 默认均<b>不设超时</b>，
     * 供应商静默不回包时调用线程会永久阻塞（表现为作业卡死且无任何异常/重试）。
     * 这里统一注入超时，把"永久挂住"转成"超时异常 → 走既有瞬时失败重试"
     */
    private final ClientHttpRequestFactory requestFactory;

    /** 前端设置面板写入的运行时覆盖（api 地址/key/model/maxTokens），未设置时原样透传 */
    private final LlmRuntimeConfig llmRuntimeConfig;

    /** 预算熔断：每次 usage 记账顺路上报 token，超阈值置位 job 停机信号（不反噬调用链） */
    private final LlmBudgetFuse budgetFuse;

    /** Continue with ChatGPT：封装 OAuth 凭据与 Responses API，主网关不接触令牌。 */
    private final ChatGptGateway chatGptGateway;

    /** 保留离线校准工具的旧构造签名；这些工具只走 API Key 模式。 */
    public SpringAiLlmGateway(int maxAttempts, int connectTimeoutSeconds, int readTimeoutSeconds,
                              LlmRuntimeConfig llmRuntimeConfig, LlmBudgetFuse budgetFuse) {
        this(maxAttempts, connectTimeoutSeconds, readTimeoutSeconds, llmRuntimeConfig, budgetFuse,
                new UnavailableChatGptGateway());
    }

    @Autowired
    public SpringAiLlmGateway(@Value("${spring.ai.retry.max-attempts:1}") int maxAttempts,
                              @Value("${novel.llm.connect-timeout-seconds:20}") int connectTimeoutSeconds,
                              @Value("${novel.llm.read-timeout-seconds:600}") int readTimeoutSeconds,
                              LlmRuntimeConfig llmRuntimeConfig,
                              LlmBudgetFuse budgetFuse,
                              ChatGptGateway chatGptGateway) {
        this.maxAttempts = maxAttempts;
        this.llmRuntimeConfig = llmRuntimeConfig;
        this.budgetFuse = budgetFuse;
        this.chatGptGateway = chatGptGateway;
        // 超时是"卡死"的唯一刹车：无超时则供应商静默不回包会永久阻塞 worker 线程
        this.requestFactory = HttpTimeouts.requestFactory(connectTimeoutSeconds, readTimeoutSeconds);
        this.retryTemplate = RetryTemplate.builder()
                .maxAttempts(maxAttempts)
                .retryOn(java.util.List.of(TransientAiException.class, ResourceAccessException.class,
                        java.util.concurrent.CancellationException.class,
                        java.net.http.HttpTimeoutException.class))
                .build();
        log.info("LLM 网关初始化：传输层最大尝试次数 = {}，连接超时 = {}s，读取超时 = {}s",
                maxAttempts, connectTimeoutSeconds, readTimeoutSeconds);
    }

    private static final class UnavailableChatGptGateway implements ChatGptGateway {
        private IllegalStateException unavailable() { return new IllegalStateException("Continue with ChatGPT 未装配"); }
        public AuthStatus status() { return new AuthStatus(false, null, null); }
        public Authorization beginAuthorization(String redirectUri) { throw unavailable(); }
        public AuthStatus finishAuthorization(String code, String state, String clientId, String redirectUri) { throw unavailable(); }
        public void logout() { }
        public List<ModelOption> models() { return List.of(); }
        public Completion complete(String model, String instructions, String input) { throw unavailable(); }
    }

    @Override
    public String complete(StoryVO.Module module, LlmCall call) {
        StoryVO.Module effective = llmRuntimeConfig.applyApi(module);
        // 场景感知覆盖：场景覆盖 > 全局覆盖 > yml 静态（场景条目缺项已由 resolveChatModel 回落统一模型）
        StoryVO.Module.ChatModel primary = llmRuntimeConfig.applyModel(
                call.getScene(), resolveChatModel(effective, call.getScene()));
        if (llmRuntimeConfig.usesChatGptAuth()) {
            return completeWithChatGpt(effective, primary, call);
        }
        // 场景级降级链：主模型 → 该场景备选 → default 备选（阶段六，见 ModelFallbackChain）
        List<StoryVO.Module.ChatModel> chain = ModelFallbackChain.build(effective, call.getScene(), primary);
        if (chain.isEmpty()) {
            IllegalStateException missing = new IllegalStateException(
                    "LLM 调用缺少可用模型配置：scene=" + call.getScene());
            recordUsage(effective, null, call, null, 0L, null, missing);
            throw missing;
        }
        for (int i = 0; i < chain.size(); i++) {
            StoryVO.Module.ChatModel selected = chain.get(i);
            long start = System.currentTimeMillis();
            try {
                ChatResponse response = callOnce(effective, selected, call);
                String content = response.getResult().getOutput().getText();
                recordUsage(effective, selected, call, content, System.currentTimeMillis() - start,
                        response.getMetadata().getUsage(), null);
                return content;
            } catch (Exception e) {
                recordUsage(effective, selected, call, null, System.currentTimeMillis() - start, null, e);
                boolean hasNext = i + 1 < chain.size();
                if (!hasNext || !shouldFallback(e)) {
                    throw e;
                }
                log.warn("场景 {} 主模型 {} 调用失败（{}），降级到备选模型 {}；异常消息：{}；供应商响应：{}",
                        call.getScene(), selected.getModel(),
                        LlmErrorClassifier.classify(e).getDesc(), chain.get(i + 1).getModel(),
                        StringUtils.abbreviate(String.valueOf(e.getMessage()), 300),
                        responseBodyOf(e));
            }
        }
        // 循环内必然 return 或 throw，此处不可达；保留以满足编译器
        throw new IllegalStateException("降级链耗尽但未捕获到异常：scene=" + call.getScene());
    }

    private String completeWithChatGpt(StoryVO.Module module, StoryVO.Module.ChatModel selected, LlmCall call) {
        if (selected == null || StringUtils.isBlank(selected.getModel())) {
            IllegalStateException missing = new IllegalStateException("ChatGPT 模式缺少模型配置");
            recordUsage(module, selected, call, null, 0L, null, missing);
            throw missing;
        }
        long start = System.currentTimeMillis();
        try {
            ChatGptGateway.Completion response = chatGptGateway.complete(
                    selected.getModel(), call.getSystemPrompt(), call.getUserPrompt());
            recordUsageValues(selected, call, response.content(), System.currentTimeMillis() - start,
                    response.promptTokens(), response.completionTokens(), response.totalTokens(), null);
            return response.content();
        } catch (Exception e) {
            recordUsage(module, selected, call, null, System.currentTimeMillis() - start, null, e);
            throw e;
        }
    }

    /** 单次真实调用：消息组装 + 每调用级 runtime options 合并 */
    private ChatResponse callOnce(StoryVO.Module effective, StoryVO.Module.ChatModel selected, LlmCall call) {
        OpenAiChatModel chatModel = chatModel(effective, selected);
        List<Message> messages = new ArrayList<>();
        if (StringUtils.isNotBlank(call.getSystemPrompt())) {
            messages.add(new SystemMessage(call.getSystemPrompt()));
        }
        messages.add(new UserMessage(call.getUserPrompt()));
        // 每调用级覆盖项走 Prompt 的 runtime options，与模型默认参数合并
        return chatModel.call(new Prompt(messages, callOptions(call)));
    }

    /**
     * 按场景解析本次调用实际使用的模型配置：
     * 总开关开（unifiedModelEnabled=true）、场景为 null、或 scene-models 未配置该场景 → 回退统一 chatModel；
     * 否则使用 scene-models 中对应场景的独立配置。
     */
    private StoryVO.Module.ChatModel resolveChatModel(StoryVO.Module module, ModelScene scene) {
        if (module == null || module.getChatModel() == null) {
            return null;
        }
        if (Boolean.FALSE.equals(module.getUnifiedModelEnabled())
                && scene != null && module.getSceneModels() != null) {
            StoryVO.Module.ChatModel sceneModel = module.getSceneModels().get(scene.getConfigKey());
            if (sceneModel != null) {
                return sceneModel;
            }
        }
        return module.getChatModel();
    }

    /** 异常记账路径的模型解析已并入降级链循环（selected 恒非空），此处不再需要单独兜底 */

    private OpenAiChatModel chatModel(StoryVO.Module module, StoryVO.Module.ChatModel selected) {
        StoryVO.Module.AiApi api = effectiveApi(module, selected);
        return chatModelCache.computeIfAbsent(chatModelCacheKey(api, selected), k -> buildChatModel(api, selected));
    }


    static String chatModelCacheKey(StoryVO.Module.AiApi api, StoryVO.Module.ChatModel selected) {
        return api.getBaseUrl() + "|" + api.getApiKey() + "|" + api.getZeroDataRetention()
                + "|" + selected.getModel() + "|" + selected.getMaxTokens()
                + "|" + selected.getEnableThinking() + "|" + selected.getTemperature();
    }

    private static StoryVO.Module.AiApi effectiveApi(StoryVO.Module module, StoryVO.Module.ChatModel selected) {
        StoryVO.Module.AiApi fallback = module.getAiApi();
        boolean hasSceneApi = selected != null
                && (StringUtils.isNotBlank(selected.getBaseUrl()) || StringUtils.isNotBlank(selected.getApiKey()));
        boolean hasScenePath = selected != null && StringUtils.isNotBlank(selected.getCompletionsPath());
        if (fallback == null || (!hasSceneApi && !hasScenePath)) {
            return fallback;
        }
        StoryVO.Module.AiApi api = new StoryVO.Module.AiApi();
        api.setBaseUrl(hasSceneApi && StringUtils.isNotBlank(selected.getBaseUrl())
                ? selected.getBaseUrl() : fallback.getBaseUrl());
        api.setApiKey(hasSceneApi && StringUtils.isNotBlank(selected.getApiKey())
                ? selected.getApiKey() : fallback.getApiKey());
        api.setCompletionsPath(hasScenePath ? selected.getCompletionsPath() : fallback.getCompletionsPath());
        api.setZeroDataRetention(fallback.getZeroDataRetention());
        return api;
    }

    private OpenAiChatModel buildChatModel(StoryVO.Module.AiApi api, StoryVO.Module.ChatModel selected) {
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .baseUrl(api.getBaseUrl())
                .apiKey(api.getApiKey());
        // 供应商路径不同：智谱无 /v1 前缀，标准 OpenAI 兼容代理留空即默认 /v1/chat/completions
        if (StringUtils.isNotBlank(api.getCompletionsPath())) {
            apiBuilder.completionsPath(api.getCompletionsPath());
        }
        boolean dashscopeEndpoint = StringUtils.contains(
                StringUtils.defaultString(selected.getBaseUrl(), api.getBaseUrl()), "dashscope");
        if (Boolean.FALSE.equals(selected.getEnableThinking()) && dashscopeEndpoint) {
            apiBuilder.restClientBuilder(chatRestClientBuilder(api, true));
        } else {
            // 未关闭思考的场景也必须挂超时，避免无限等待拖死 worker
            apiBuilder.restClientBuilder(chatRestClientBuilder(api, false));
        }
        OpenAiChatOptions.Builder optionBuilder = OpenAiChatOptions.builder()
                .model(selected.getModel())
                .maxTokens(selected.getMaxTokens().intValue())
                // JSON Mode：强制模型输出合法 JSON，治正文/摘要等场景的"输出截断/不闭合"格式翻车（零 token 成本）
                .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
        // 采样温度：配置显式给定时写入默认选项；null 则走供应商默认
        if (selected.getTemperature() != null) {
            optionBuilder.temperature(selected.getTemperature());
        }
        OpenAiChatOptions options = optionBuilder.build();
        return OpenAiChatModel.builder()
                .openAiApi(apiBuilder.build())
                .defaultOptions(options)
                .retryTemplate(retryTemplate)
                .build();
    }

    private static boolean shouldFallback(Throwable e) {
        return LlmErrorClassifier.classify(e) != LlmErrorClassifier.Kind.CONTENT_POLICY;
    }

    /** 从异常链提取 HTTP 4xx/5xx 的响应体（供应商原始报错），供确定性失败的日志归因；提取不到返回 "-" */
    private static String responseBodyOf(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.springframework.web.client.RestClientResponseException rest) {
                return StringUtils.abbreviate(rest.getResponseBodyAsString(), 500);
            }
            // Spring AI 自家异常：HTTP 4xx/5xx 的响应体在 message 里（RestClientResponseException 之外的通道）
            if (t instanceof org.springframework.ai.retry.NonTransientAiException nonTransient) {
                return StringUtils.abbreviate(String.valueOf(nonTransient.getMessage()), 500);
            }
            if (t instanceof org.springframework.web.client.HttpStatusCodeException http) {
                return StringUtils.abbreviate(http.getResponseBodyAsString(), 500);
            }
        }
        return "-";
    }


    private RestClient.Builder chatRestClientBuilder(StoryVO.Module.AiApi api, boolean disableThinking) {
        // 本模型实例是否已确认拒绝 enable_thinking（强制思考模型如 glm-5.3/kimi-k3：注入即 400）。
        // 记住后本实例后续请求直接跳过注入，省一次 400 往返；chatModel 缓存按配置建实例，状态随实例存活
        AtomicBoolean injectionRejected = new AtomicBoolean(false);
        ClientHttpRequestInterceptor interceptor = (request, body, execution) -> {
            if (Boolean.TRUE.equals(api.getZeroDataRetention())) {
                request.getHeaders().set("x-cmd-zdr", "1");
            }
            boolean injectable = disableThinking && !injectionRejected.get();
            if (!injectable || body == null || body.length == 0) {
                return execution.execute(request, body);
            }
            String path = request.getURI().getPath();
            if (path == null || !path.endsWith("/chat/completions")) {
                return execution.execute(request, body);
            }
            byte[] payload = body;
            try {
                ObjectNode node = (ObjectNode) JSON.readTree(body);
                node.put("enable_thinking", false);
                payload = JSON.writeValueAsBytes(node);
            } catch (Exception e) {
                log.warn("注入 enable_thinking=false 失败，按原请求发送：{}", e.getMessage());
            }
            ClientHttpResponse response = execution.execute(request, payload);
            // 自愈：强制思考模型对 enable_thinking=false 返回 4xx——去掉该参数
            // 原样重试一次，并记住本实例后续跳过注入。重试走的是余下执行链，不会重新进入本拦截器成环
            if (response.getStatusCode().is4xxClientError()) {
                injectionRejected.set(true);
                log.warn("enable_thinking 注入被供应商拒绝（HTTP {}），已去除该参数重试一次，"
                        + "本模型实例后续请求不再注入（强制思考模型按供应商默认开启思考）",
                        response.getStatusCode().value());
                response.close();
                ObjectNode stripped = (ObjectNode) JSON.readTree(payload);
                stripped.remove("enable_thinking");
                return execution.execute(request, JSON.writeValueAsBytes(stripped));
            }
            return response;
        };
        return baseRestClientBuilder().requestInterceptor(interceptor);
    }

    private RestClient.Builder baseRestClientBuilder() {
        return RestClient.builder().requestFactory(requestFactory);
    }

    /** LlmCall 中非空字段作为每调用级覆盖（选择器/摘要等小调用用小参数） */
    private OpenAiChatOptions callOptions(LlmCall call) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
        if (call.getMaxTokens() != null) {
            builder.maxTokens(call.getMaxTokens());
        }
        if (call.getTemperature() != null) {
            builder.temperature(call.getTemperature());
        }
        return builder.build();
    }

    private void recordUsage(StoryVO.Module module, StoryVO.Module.ChatModel selected, LlmCall call,
                             String content, long durationMs, Usage usage, Exception error) {
        recordUsageValues(selected, call, content, durationMs,
                usage == null ? null : Long.valueOf(usage.getPromptTokens()),
                usage == null ? null : Long.valueOf(usage.getCompletionTokens()),
                usage == null ? null : Long.valueOf(usage.getTotalTokens()), error);
    }

    private void recordUsageValues(StoryVO.Module.ChatModel selected, LlmCall call,
                                   String content, long durationMs, Long promptTokens,
                                   Long completionTokens, Long totalTokens, Exception error) {
        try {
            ObjectNode line = JSON.createObjectNode();
            line.put("ts", Instant.now().toString());
            if (call != null) {
                line.put("label", call.getLabel());
            }
            String jobId = MDC.get("trace-id");
            if (jobId != null) {
                line.put("jobId", jobId);
            }
            line.put("model", selected != null ? selected.getModel() : null);
            line.put("promptChars", call == null ? 0
                    : StringUtils.length(call.getSystemPrompt()) + StringUtils.length(call.getUserPrompt()));
            line.put("responseChars", content == null ? 0 : content.length());
            line.put("durationMs", durationMs);
            line.put("success", error == null);
            if (totalTokens != null) {
                line.put("promptTokens", promptTokens);
                line.put("completionTokens", completionTokens);
                line.put("totalTokens", totalTokens);
            }
            if (error != null) {
                String message = error.getClass().getSimpleName() + ": " + error.getMessage();
                line.put("error", StringUtils.abbreviate(message, 500));
            }
            synchronized (USAGE_LOCK) {
                Files.createDirectories(USAGE_LOG_PATH.getParent());
                Files.writeString(USAGE_LOG_PATH, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            // 预算熔断上报：usage 缺失（部分供应商异常响应）时记 0 不影响累计语义；
            // fuse 内部自吞异常，绝不反噬调用链
            budgetFuse.record(jobId, totalTokens);
        } catch (Exception e) {
            log.warn("usage 记账失败（不影响生成）：{}", e.getMessage());
        }
    }

}
