package cn.novel.yonren.infrastructure.gateway;

import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;

/**
 * OpenAI Responses 协议（POST /v1/responses）的 API Key 通道实现，网关按 protocol=responses 分派到此类。
 *
 * <p>不用 Spring AI 升级（1.1.x 才有官方 Responses 支持）：本项目在 1.0.0 上钉了大量拦截器行为
 * （reasoning_content 提取、enable_thinking 自愈注入），升级重验成本远高于这~两百行。
 * 网关的烧穿判定/降级链/记账只依赖 ChatResponse/Usage 抽象——本类把
 * Responses 响应翻译成同构的 ChatResponse，全部机制原样复用：
 * <ul>
 *   <li>usage.output_tokens_details.reasoning_tokens 直接喂 {@link SpringAiLlmGateway.ReasoningCapture}
 *       ——判据④（思考占预算 80%）原生支持，比 chat/completions 路径还干净；</li>
 *   <li>maxTokens → max_output_tokens；temperature 仅显式配置时下发
 *       （gpt-5 系拒绝 temperature≠1，与既有"null=不干预"语义天然兼容）；</li>
 *   <li>store:false（中转站场景不需要服务端留存）；非流式（stream:false 是标准行为，
 *       OAuth 通道的 SSE 是 ChatGPT 后端的要求，API Key 通道无此约束）。</li>
 * </ul>
 */
final class ResponsesApiChatModel implements ChatModel {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(ResponsesApiChatModel.class);

    private final RestClient restClient;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final Integer maxTokens;
    private final Double temperature;
    private final RetryTemplate retryTemplate;

    ResponsesApiChatModel(StoryVO.Module.AiApi api, StoryVO.Module.ChatModel selected,
                          ClientHttpRequestFactory requestFactory, RetryTemplate retryTemplate) {
        this.endpoint = responsesEndpoint(api.getBaseUrl(), api.getCompletionsPath());
        this.apiKey = api.getApiKey();
        this.model = selected.getModel();
        this.maxTokens = selected.getMaxTokens() == null ? null : selected.getMaxTokens().intValue();
        this.temperature = selected.getTemperature();
        this.retryTemplate = retryTemplate;
        // 超时与错误形态与 OpenAI 路径同源：RestClient + ClientHttpRequestFactory（HttpTimeouts 注入），
        // 4xx/5xx 抛 RestClientResponseException，网关的 responseBodyOf/LlmErrorClassifier 原样可用
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * 端点推导：completionsPath 显式给定时按用户写法（逃生口），否则按 base 是否带 /v1 自适应。
     * completionsPath 是 OpenAI 语义字段（智谱用户 yml 里的 /chat/completions）——切到 responses
     * 协议后原样沿用会拼错端点，只接受含 responses 的路径，其余告警忽略
     */
    static String responsesEndpoint(String baseUrl, String completionsPath) {
        String base = trimTrailingSlashes(baseUrl);
        if (StringUtils.isNotBlank(completionsPath)
                && completionsPath.toLowerCase().contains("responses")) {
            String path = completionsPath.trim();
            return join(base, path.startsWith("/") ? path : "/" + path);
        }
        if (StringUtils.isNotBlank(completionsPath)) {
            log.warn("completionsPath={} 不像 Responses 端点路径，已忽略（按默认 /v1/responses 推导）",
                    completionsPath);
        }
        return base.endsWith("/v1") ? base + "/responses" : base + "/v1/responses";
    }

    private static String trimTrailingSlashes(String value) {
        String base = StringUtils.defaultString(value);
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private static String join(String base, String path) {
        return path.startsWith("/") ? base + path : base + "/" + path;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        // 与 OpenAI/Anthropic 客户端同构：瞬时失败重试收在传输层（默认 maxAttempts=1 不重试）
        return retryTemplate.execute(ctx -> doCall(prompt));
    }

    private ChatResponse doCall(Prompt prompt) {
        // 消息装配前提：本项目全部调用都是"一条 system + 一条 user"（LLM 调用是单轮的，
        // 无对话历史）。多条 system 取最后一条；多条 user 会被 input 覆盖为最后一条——
        // 若将来引入多轮消息，此处必须改为 input 数组逐条映射，静默丢上下文比崩溃更难排查
        String instructions = null;
        String input = null;
        for (Message message : prompt.getInstructions()) {
            if (message instanceof SystemMessage) {
                instructions = message.getText();
            } else {
                input = message.getText();
            }
        }
        // 每调用级覆盖（选择器/摘要等小调用）：Prompt runtime options 按接口读取，缺省回落模型默认
        Integer maxOutputTokens = this.maxTokens;
        Double effectiveTemperature = this.temperature;
        ChatOptions runtime = prompt.getOptions();
        if (runtime != null) {
            if (runtime.getMaxTokens() != null) {
                maxOutputTokens = runtime.getMaxTokens();
            }
            if (runtime.getTemperature() != null) {
                effectiveTemperature = runtime.getTemperature();
            }
        }
        ObjectNode body = JSON.createObjectNode();
        body.put("model", model);
        if (StringUtils.isNotBlank(instructions)) {
            body.put("instructions", instructions);
        }
        ObjectNode user = body.putArray("input").addObject();
        user.put("role", "user");
        user.put("content", StringUtils.defaultString(input));
        if (maxOutputTokens != null) {
            body.put("max_output_tokens", maxOutputTokens);
        }
        if (effectiveTemperature != null) {
            body.put("temperature", effectiveTemperature);
        }
        body.put("store", false);
        body.put("stream", false);

        String responseJson = restClient.post()
                .uri(URI.create(endpoint))
                .header("Authorization", "Bearer " + StringUtils.defaultString(apiKey))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .retrieve()
                .body(String.class);
        return parseResponse(responseJson, model);
    }

    /** 解析 Responses 响应为 ChatResponse；思考用量写入线程级捕获槽（同 OpenAI 拦截器的口径） */
    static ChatResponse parseResponse(String responseJson, String modelName) {
        JsonNode root;
        try {
            root = JSON.readTree(StringUtils.defaultString(responseJson));
        } catch (Exception e) {
            throw new IllegalStateException("Responses API 返回非 JSON 响应："
                    + StringUtils.abbreviate(responseJson, 200), e);
        }
        if (root.has("error")) {
            // status=failed 时错误对象在顶层 error：直接抛给网关按普通失败记账/降级
            String message = root.path("error").path("message").asText(root.path("error").toString());
            throw new IllegalStateException("Responses API 返回失败状态："
                    + StringUtils.abbreviate(message, 500));
        }
        StringBuilder content = new StringBuilder();
        long reasoningChars = 0;
        for (JsonNode item : root.path("output")) {
            String type = item.path("type").asText();
            if ("message".equals(type)) {
                for (JsonNode part : item.path("content")) {
                    if ("output_text".equals(part.path("type").asText())) {
                        content.append(part.path("text").asText(""));
                    } else if ("refusal".equals(part.path("type").asText())) {
                        content.append(part.path("refusal").asText(""));
                    }
                }
            } else if ("reasoning".equals(type)) {
                for (JsonNode summary : item.path("summary")) {
                    reasoningChars += summary.path("text").asText("").length();
                }
            }
        }
        JsonNode usage = root.path("usage");
        Integer inputTokens = usage.has("input_tokens") ? usage.path("input_tokens").asInt() : null;
        Integer outputTokens = usage.has("output_tokens") ? usage.path("output_tokens").asInt() : null;
        Integer totalTokens = usage.has("total_tokens") ? usage.path("total_tokens").asInt() : null;
        // usage 缺失的站统一记 0：DefaultUsage 不接受 null（网关记账 Long.valueOf 会 NPE），
        // 烧穿判定对"用量未知"的调用退回判据③（正文为空）
        int in = inputTokens == null ? 0 : inputTokens;
        int out = outputTokens == null ? 0 : outputTokens;
        int total = totalTokens != null ? totalTokens : in + out;
        // 思考 token：Responses API 原生上报（usage.output_tokens_details.reasoning_tokens），
        // 是判据④（思考占预算 ≥80%）最干净的数据源
        Long reasoningTokens = null;
        JsonNode details = usage.path("output_tokens_details");
        if (details.has("reasoning_tokens")) {
            reasoningTokens = details.path("reasoning_tokens").asLong();
        }
        if (reasoningChars > 0 || reasoningTokens != null) {
            SpringAiLlmGateway.ReasoningCapture.set(reasoningChars, reasoningTokens);
        }
        String finishReason = root.path("status").asText(null);
        AssistantMessage assistant = new AssistantMessage(content.toString());
        Generation generation = new Generation(assistant, ChatGenerationMetadata.builder()
                .finishReason(finishReason).build());
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .id(root.path("id").asText(null))
                .model(modelName)
                .usage(new DefaultUsage(in, out, total))
                .build();
        return new ChatResponse(List.of(generation), metadata);
    }
}
