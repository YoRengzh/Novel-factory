package cn.novel.yonren.infrastructure.gateway.chatgpt;

import cn.novel.yonren.domain.novel.adapter.llm.ChatGptGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class OpenAiChatGptGatewayTest {

    @TempDir
    Path tmp;

    @Test
    void beginAuthorization_buildsOfficialPkceFlowAndKeepsStableHostId() {
        Path credentials = tmp.resolve("chatgpt-auth.json");
        String callback = "http://127.0.0.1:8081/api/auth/chatgpt/callback";
        OpenAiChatGptGateway first = new OpenAiChatGptGateway(credentials, mock(HttpClient.class));

        ChatGptGateway.Authorization authorization = first.beginAuthorization(callback);
        Map<String, String> query = query(URI.create(authorization.url()));

        assertEquals("https", URI.create(authorization.url()).getScheme());
        assertEquals("auth.openai.com", URI.create(authorization.url()).getHost());
        assertEquals("dynamic_agent_client", query.get("client_id"));
        assertEquals(callback, query.get("redirect_uri"));
        assertEquals("S256", query.get("code_challenge_method"));
        assertEquals("https://api.openai.com/v1", query.get("resource"));
        assertTrue(query.get("scope").contains("chatgpt.tokens.use.direct"));
        assertTrue(query.get("ext_agent_host_id").startsWith("urn:uuid:"));
        assertTrue(query.get("state").length() >= 32);
        assertTrue(query.get("nonce").length() >= 32);

        OpenAiChatGptGateway relaunched = new OpenAiChatGptGateway(credentials, mock(HttpClient.class));
        Map<String, String> second = query(URI.create(relaunched.beginAuthorization(callback).url()));
        assertEquals(query.get("ext_agent_host_id"), second.get("ext_agent_host_id"));
    }

    @Test
    void beginAuthorization_rejectsNonLoopbackCallback() {
        OpenAiChatGptGateway gateway = new OpenAiChatGptGateway(
                tmp.resolve("chatgpt-auth.json"), mock(HttpClient.class));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> gateway.beginAuthorization("http://localhost:8081/api/auth/chatgpt/callback"));

        assertEquals("ChatGPT OAuth 回调必须使用 http://127.0.0.1:<port>/api/auth/chatgpt/callback", error.getMessage());
    }

    @Test
    void status_restoresPersistedTokenExpiryAcrossRelaunch() throws Exception {
        Path credentials = tmp.resolve("chatgpt-auth.json");
        Files.writeString(credentials, """
                {"extAgentHostId":"urn:uuid:11111111-1111-1111-1111-111111111111","credentials":{
                  "clientId":"oaiapp_test","email":"reader@example.com","accessToken":"access",
                  "refreshToken":"refresh","expiresAt":"2099-01-02T03:04:05Z"
                }}
                """, StandardCharsets.UTF_8);

        ChatGptGateway.AuthStatus status = new OpenAiChatGptGateway(
                credentials, mock(HttpClient.class)).status();

        assertTrue(status.authorized());
        assertEquals("reader@example.com", status.email());
        assertEquals("2099-01-02T03:04:05Z", status.expiresAt().toString());
    }

    private static Map<String, String> query(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&"))
                .map(part -> part.split("=", 2))
                .collect(Collectors.toMap(parts -> decode(parts[0]), parts -> decode(parts[1])));
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
