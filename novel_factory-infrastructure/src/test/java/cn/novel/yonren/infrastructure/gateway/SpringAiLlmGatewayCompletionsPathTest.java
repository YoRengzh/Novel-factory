package cn.novel.yonren.infrastructure.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OpenAI 协议 completionsPath 推导测试：baseUrl 带 /v1 时必须去掉重复的 /v1，
 * 否则会拼出 /v1/v1/chat/completions（实测 404）。
 *
 * 与 {@link ResponsesApiChatModelTest#endpoint_adaptsToV1AndHonorsCompletionsPath}
 * 同一适配承诺：三协议 + 模型列表都要"带不带 /v1 均可"。
 */
class SpringAiLlmGatewayCompletionsPathTest {

    @Test
    void baseUrlWithV1_yieldsChatCompletionsToAvoidDoubleV1() {
        assertEquals("/chat/completions",
                SpringAiLlmGateway.openAiCompletionsPath("https://api.example.com/v1"));
        // 尾斜杠同样要处理：否则 /v1/ 不被识别，仍会拼出 /v1/v1/chat/completions
        assertEquals("/chat/completions",
                SpringAiLlmGateway.openAiCompletionsPath("https://api.example.com/v1/"));
        assertEquals("/chat/completions",
                SpringAiLlmGateway.openAiCompletionsPath("  https://api.example.com/v1  "));
    }

    @Test
    void baseUrlWithoutV1_leavesDefaultPathToOpenAiApi() {
        // 返回空串 = 不覆盖 OpenAiApi 的默认 /v1/chat/completions（单一来源，避免两条分支分歧）
        assertEquals("", SpringAiLlmGateway.openAiCompletionsPath("https://api.example.com"));
        assertEquals("", SpringAiLlmGateway.openAiCompletionsPath("https://api.example.com/compatible-mode"));
        // 智谱形态：base 已含 /v4，默认路径仍由 OpenAiApi 决定
        assertEquals("", SpringAiLlmGateway.openAiCompletionsPath("https://open.bigmodel.cn/api/paas/v4"));
    }

    @Test
    void blankOrNullBaseUrl_doesNotThrow() {
        assertEquals("", SpringAiLlmGateway.openAiCompletionsPath(null));
        assertEquals("", SpringAiLlmGateway.openAiCompletionsPath(""));
        assertEquals("", SpringAiLlmGateway.openAiCompletionsPath("   "));
    }
}
