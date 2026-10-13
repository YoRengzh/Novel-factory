package cn.novel.yonren.infrastructure.gateway.chatgpt;

import cn.novel.yonren.domain.novel.adapter.llm.ChatGptGateway;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatGptSseParserTest {

    @Test
    void parse_collectsDeltasAndRequiresCompletedEvent() throws Exception {
        String stream = event("{\"type\":\"response.output_text.delta\",\"delta\":\"你\"}")
                + event("{\"type\":\"response.output_text.delta\",\"delta\":\"好\"}")
                + event("{\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":12,\"output_tokens\":3,\"total_tokens\":15}}}");

        ChatGptGateway.Completion result = ChatGptSseParser.parse(input(stream));

        assertEquals("你好", result.content());
        assertEquals(12L, result.promptTokens());
        assertEquals(3L, result.completionTokens());
        assertEquals(15L, result.totalTokens());
    }

    @Test
    void parse_throwsForFailedEvent() {
        String stream = event("{\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\",\"message\":\"limit\"}}}");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ChatGptSseParser.parse(input(stream)));

        assertEquals("ChatGPT Responses API 失败：subscription_sharing_usage_limit_exceeded - limit", error.getMessage());
    }

    @Test
    void parse_rejectsInterruptedStreamWithoutCompletedEvent() {
        String stream = event("{\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ChatGptSseParser.parse(input(stream)));

        assertEquals("ChatGPT Responses API 流在 response.completed 前中断", error.getMessage());
    }

    private static ByteArrayInputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String event(String json) {
        return "data: " + json + "\n\n";
    }
}
