package cn.novel.yonren.trigger.http;

import cn.novel.yonren.domain.novel.adapter.llm.ChatGptGateway;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

class ChatGptAuthControllerTest {

    @Test
    void start_usesExact127LoopbackCallbackForCurrentPort() {
        ChatGptGateway gateway = mock(ChatGptGateway.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getLocalPort()).thenReturn(8081);
        when(gateway.beginAuthorization("http://127.0.0.1:8081/api/auth/chatgpt/callback"))
                .thenReturn(new ChatGptGateway.Authorization("https://auth.openai.com/authorize"));

        ChatGptGateway.Authorization result = new ChatGptAuthController(gateway).start(request);

        assertEquals("https://auth.openai.com/authorize", result.url());
    }

    @Test
    void callback_exchangesCodeAndReturnsClosableSuccessPage() {
        ChatGptGateway gateway = mock(ChatGptGateway.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getLocalPort()).thenReturn(8081);
        when(gateway.finishAuthorization("code", "state", "oaiapp_123",
                "http://127.0.0.1:8081/api/auth/chatgpt/callback"))
                .thenReturn(new ChatGptGateway.AuthStatus(true, "reader@example.com", Instant.parse("2099-01-01T00:00:00Z")));

        String html = new ChatGptAuthController(gateway)
                .callback("code", "state", "oaiapp_123", null, request).getBody();

        assertTrue(html.contains("登录成功"));
        assertTrue(html.contains("chatgpt-auth-complete"));
        verify(gateway).finishAuthorization("code", "state", "oaiapp_123",
                "http://127.0.0.1:8081/api/auth/chatgpt/callback");
    }

    @Test
    void callback_bindsOAuthQueryParametersThroughSpringMvc() throws Exception {
        ChatGptGateway gateway = mock(ChatGptGateway.class);
        when(gateway.finishAuthorization("code", "state", "oaiapp_123",
                "http://127.0.0.1:8081/api/auth/chatgpt/callback"))
                .thenReturn(new ChatGptGateway.AuthStatus(true, "reader@example.com",
                        Instant.parse("2099-01-01T00:00:00Z")));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ChatGptAuthController(gateway)).build();

        mvc.perform(get("/api/auth/chatgpt/callback")
                        .param("code", "code")
                        .param("state", "state")
                        .param("client_id", "oaiapp_123")
                        .with(request -> {
                            request.setLocalPort(8081);
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("登录成功")));

        verify(gateway).finishAuthorization("code", "state", "oaiapp_123",
                "http://127.0.0.1:8081/api/auth/chatgpt/callback");
    }
}
