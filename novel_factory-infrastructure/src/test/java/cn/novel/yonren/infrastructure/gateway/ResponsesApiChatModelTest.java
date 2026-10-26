package cn.novel.yonren.infrastructure.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Responses 协议客户端测试：端点推导（/v1 自适应 + completionsPath 逃生口）与
 * 响应解析（正文拼接 / usage 映射 / 思考 token 喂 ReasoningCapture / failed 状态抛错）
 */
class ResponsesApiChatModelTest {

    @Test
    void endpoint_adaptsToV1AndHonorsCompletionsPath() {
        assertEquals("https://api.example.com/v1/responses",
                ResponsesApiChatModel.responsesEndpoint("https://api.example.com", null));
        assertEquals("https://api.example.com/v1/responses",
                ResponsesApiChatModel.responsesEndpoint("https://api.example.com/v1/", null));
        assertEquals("https://api.example.com/v1/responses",
                ResponsesApiChatModel.responsesEndpoint("https://api.example.com/v1", null));
        // completionsPath 显式给定：按用户写法原样拼（中转站自定义挂载的逃生口）
        assertEquals("https://api.example.com/custom/responses",
                ResponsesApiChatModel.responsesEndpoint("https://api.example.com", "/custom/responses"));
        assertEquals("https://api.example.com/custom/responses",
                ResponsesApiChatModel.responsesEndpoint("https://api.example.com", "custom/responses"));
    }

    @Test
    void endpoint_ignoresCrossProtocolCompletionsPath() {
        // completionsPath 是 OpenAI 语义字段（智谱用户 yml 的 /chat/completions）：
        // 切到 responses 协议后原样沿用会拼错端点——必须忽略并按默认推导
        assertEquals("https://api.example.com/v1/responses",
                ResponsesApiChatModel.responsesEndpoint("https://api.example.com", "/chat/completions"));
        assertEquals("https://api.example.com/v1/responses",
                ResponsesApiChatModel.responsesEndpoint("https://api.example.com", "/v4/chat/completions"));
    }

    @Test
    void parse_extractsTextUsageAndReasoningTokens() {
        String body = """
                {
                  "id": "resp_123", "status": "completed",
                  "output": [
                    {"type":"reasoning","summary":[{"type":"summary_text","text":"想想章节结构"}]},
                    {"type":"message","content":[
                      {"type":"output_text","text":"第一段"},
                      {"type":"output_text","text":"第二段"}
                    ]}
                  ],
                  "usage": {
                    "input_tokens": 100,
                    "output_tokens": 8192,
                    "total_tokens": 8292,
                    "output_tokens_details": {"reasoning_tokens": 7500}
                  }
                }
                """;
        SpringAiLlmGateway.ReasoningCapture.clearAll();
        ChatResponse response = ResponsesApiChatModel.parseResponse(body, "gpt-5.1");

        assertEquals("第一段第二段", response.getResult().getOutput().getText(), "output_text 块按序拼接");
        Usage usage = response.getMetadata().getUsage();
        assertEquals(100, usage.getPromptTokens().longValue());
        assertEquals(8192, usage.getCompletionTokens().longValue(), "output_tokens 是烧穿判据①④的基准");
        assertEquals(8292, usage.getTotalTokens().longValue());
        // 思考 token 必须喂线程级捕获槽：判据④（思考占预算 ≥80%）的唯一数据源
        var snapshot = SpringAiLlmGateway.ReasoningCapture.take();
        assertEquals(7500L, snapshot.tokens().longValue());
        assertTrue(snapshot.chars() > 0, "reasoning summary 文本长度也应计入观测信号");
        assertEquals("completed", response.getResult().getMetadata().getFinishReason());
        SpringAiLlmGateway.ReasoningCapture.clearAll();
    }

    @Test
    void parse_failedStatus_throwsWithError() {
        String body = "{\"status\":\"failed\",\"error\":{\"code\":\"server_error\",\"message\":\"upstream blew up\"}}";
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ResponsesApiChatModel.parseResponse(body, "gpt-5.1"));
        assertTrue(ex.getMessage().contains("upstream blew up"), "错误消息面向网关记账与归因：" + ex.getMessage());
    }

    @Test
    void parse_missingUsageRecordsZero() {
        // 个别中转站不回 usage：解析不得崩，用量记 0，烧穿判定退回"正文为空"判据③
        String body = "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}";
        ChatResponse response = ResponsesApiChatModel.parseResponse(body, "m");
        assertEquals("ok", response.getResult().getOutput().getText());
        assertEquals(0L, response.getMetadata().getUsage().getTotalTokens().longValue());
        assertEquals(0L, response.getMetadata().getUsage().getCompletionTokens().longValue());
    }

    @Test
    void parse_nonJsonResponseThrows() {
        assertThrows(IllegalStateException.class,
                () -> ResponsesApiChatModel.parseResponse("<html>gateway 502</html>", "m"));
    }
}
