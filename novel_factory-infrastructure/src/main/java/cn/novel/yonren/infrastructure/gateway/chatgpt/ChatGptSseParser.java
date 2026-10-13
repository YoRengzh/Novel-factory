package cn.novel.yonren.infrastructure.gateway.chatgpt;

import cn.novel.yonren.domain.novel.adapter.llm.ChatGptGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Responses API SSE 解析器：只有收到 response.completed 才认定成功。 */
final class ChatGptSseParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ChatGptSseParser() { }

    static ChatGptGateway.Completion parse(InputStream input) throws IOException {
        StringBuilder content = new StringBuilder();
        StringBuilder data = new StringBuilder();
        long promptTokens = 0L;
        long completionTokens = 0L;
        long totalTokens = 0L;
        boolean completed = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        Parsed parsed = consume(data.toString(), content);
                        if (parsed.completed()) {
                            completed = true;
                            promptTokens = parsed.promptTokens();
                            completionTokens = parsed.completionTokens();
                            totalTokens = parsed.totalTokens();
                        }
                        data.setLength(0);
                    }
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).trim());
                }
            }
        }
        if (!data.isEmpty()) {
            Parsed parsed = consume(data.toString(), content);
            if (parsed.completed()) {
                completed = true;
                promptTokens = parsed.promptTokens();
                completionTokens = parsed.completionTokens();
                totalTokens = parsed.totalTokens();
            }
        }
        if (!completed) {
            throw new IllegalStateException("ChatGPT Responses API 流在 response.completed 前中断");
        }
        return new ChatGptGateway.Completion(content.toString(), promptTokens, completionTokens, totalTokens);
    }

    private static Parsed consume(String payload, StringBuilder content) throws IOException {
        if ("[DONE]".equals(payload)) {
            return Parsed.EMPTY;
        }
        JsonNode event = JSON.readTree(payload);
        String type = event.path("type").asText();
        if ("response.output_text.delta".equals(type)) {
            content.append(event.path("delta").asText());
            return Parsed.EMPTY;
        }
        if ("response.completed".equals(type)) {
            JsonNode usage = event.path("response").path("usage");
            return new Parsed(true, usage.path("input_tokens").asLong(),
                    usage.path("output_tokens").asLong(), usage.path("total_tokens").asLong());
        }
        if ("response.failed".equals(type) || "response.incomplete".equals(type) || "error".equals(type)) {
            JsonNode error = "error".equals(type) ? event.path("error") : event.path("response").path("error");
            String code = error.path("code").asText("unknown_error");
            String message = error.path("message").asText("unknown error");
            throw new IllegalStateException("ChatGPT Responses API 失败：" + code + " - " + message);
        }
        return Parsed.EMPTY;
    }

    private record Parsed(boolean completed, long promptTokens, long completionTokens, long totalTokens) {
        private static final Parsed EMPTY = new Parsed(false, 0L, 0L, 0L);
    }
}
