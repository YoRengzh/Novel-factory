package cn.novel.yonren.infrastructure.gateway.chatgpt;

import cn.novel.yonren.domain.novel.adapter.llm.ChatGptGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Arrays;

/** 官方 Sign in with ChatGPT（OSS 动态客户端）与 Responses API 实现。 */
@Component
public class OpenAiChatGptGateway implements ChatGptGateway {

    private static final String ISSUER = "https://auth.openai.com";
    private static final String AUTHORIZE_ENDPOINT = ISSUER + "/api/accounts/authorize";
    private static final String TOKEN_ENDPOINT = ISSUER + "/api/accounts/oauth/token";
    private static final String JWKS_ENDPOINT = ISSUER + "/.well-known/jwks.json";
    private static final String DISCOVERY_ENDPOINT = ISSUER + "/.well-known/openid-configuration";
    private static final String RESOURCE = "https://api.openai.com/v1";
    private static final String MODELS_ENDPOINT = RESOURCE + "/models";
    private static final String RESPONSES_ENDPOINT = RESOURCE + "/responses";
    private static final String DYNAMIC_CLIENT = "dynamic_agent_client";
    private static final String SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    private static final Path DEFAULT_FILE = Path.of("data", "chatgpt-auth.json");
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path credentialFile;
    private final HttpClient http;
    private final Duration requestTimeout;
    private final Map<String, PendingAuthorization> pending = new ConcurrentHashMap<>();
    private final Object credentialLock = new Object();
    private volatile StoredState stored;

    @Autowired
    public OpenAiChatGptGateway(@Value("${novel.llm.connect-timeout-seconds:20}") int connectTimeoutSeconds,
                                @Value("${novel.llm.read-timeout-seconds:600}") int readTimeoutSeconds) {
        this(DEFAULT_FILE, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, connectTimeoutSeconds))).build(),
                Duration.ofSeconds(Math.max(1, readTimeoutSeconds)));
    }

    OpenAiChatGptGateway(Path credentialFile, HttpClient http) {
        this(credentialFile, http, Duration.ofSeconds(600));
    }

    private OpenAiChatGptGateway(Path credentialFile, HttpClient http, Duration requestTimeout) {
        this.credentialFile = credentialFile;
        this.http = http;
        this.requestTimeout = requestTimeout;
        this.stored = load();
    }

    @Override
    public AuthStatus status() {
        StoredState state = stored;
        Credentials credentials = state.credentials;
        boolean authorized = credentials != null && StringUtils.isNotBlank(credentials.refreshToken)
                && StringUtils.isNotBlank(credentials.accessToken);
        return new AuthStatus(authorized, credentials == null ? null : credentials.email,
                credentials == null ? null : credentials.expiresAt);
    }

    @Override
    public Authorization beginAuthorization(String redirectUri) {
        validateRedirectUri(redirectUri);
        ensureHostIdPersisted();
        Credentials credentials = stored.credentials;
        String clientId = credentials != null && StringUtils.isNotBlank(credentials.clientId)
                ? credentials.clientId : DYNAMIC_CLIENT;
        String state = randomUrlToken(32);
        String nonce = randomUrlToken(32);
        String verifier = randomUrlToken(64);
        String challenge = base64Url(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
        pending.put(state, new PendingAuthorization(state, nonce, verifier, redirectUri, clientId, Instant.now()));

        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("client_id", clientId);
        params.put("response_type", "code");
        params.put("redirect_uri", redirectUri);
        params.put("scope", SCOPES);
        params.put("resource", RESOURCE);
        params.put("state", state);
        params.put("nonce", nonce);
        params.put("code_challenge_method", "S256");
        params.put("code_challenge", challenge);
        params.put("ext_agent_host_id", stored.extAgentHostId);
        params.put("agent_name_hint", "Novel Factory");
        if (credentials != null && StringUtils.isNotBlank(credentials.idToken)) {
            params.put("id_token_hint", credentials.idToken);
        }
        if (credentials != null && StringUtils.isNotBlank(credentials.email)) {
            params.put("login_hint", credentials.email);
        }
        return new Authorization(AUTHORIZE_ENDPOINT + "?" + form(params));
    }

    @Override
    public AuthStatus finishAuthorization(String code, String state, String issuedClientId, String redirectUri) {
        PendingAuthorization tx = pending.remove(state);
        if (tx == null || tx.createdAt.plusSeconds(600).isBefore(Instant.now())) {
            throw new IllegalArgumentException("ChatGPT 登录状态已失效，请重新登录");
        }
        if (!tx.redirectUri.equals(redirectUri)) {
            throw new IllegalArgumentException("ChatGPT OAuth 回调地址不匹配");
        }
        String clientId = DYNAMIC_CLIENT.equals(tx.clientId) ? issuedClientId : tx.clientId;
        if (StringUtils.isBlank(clientId) || DYNAMIC_CLIENT.equals(clientId)) {
            throw new IllegalArgumentException("ChatGPT 首次授权未返回已签发的 client_id");
        }
        JsonNode token = postForm(TOKEN_ENDPOINT, Map.of(
                "grant_type", "authorization_code",
                "client_id", clientId,
                "code", require(code, "缺少授权码"),
                "code_verifier", tx.verifier,
                "redirect_uri", redirectUri,
                "resource", RESOURCE));
        String scope = token.path("scope").asText();
        boolean planScope = Arrays.stream(scope.split("\\s+"))
                .anyMatch("chatgpt.tokens.use.direct"::equals);
        if (!planScope) {
            throw new IllegalStateException("ChatGPT 未授予使用订阅计划调用模型的权限");
        }
        String idToken = require(token.path("id_token").asText(null), "令牌响应缺少 id_token");
        JsonNode identity = validateIdToken(idToken, clientId, tx.nonce);
        Credentials credentials = new Credentials();
        credentials.clientId = clientId;
        credentials.subject = identity.path("sub").asText();
        credentials.email = identity.path("email").asText(null);
        credentials.idToken = idToken;
        credentials.accessToken = require(token.path("access_token").asText(null), "令牌响应缺少 access_token");
        credentials.refreshToken = require(token.path("refresh_token").asText(null), "令牌响应缺少 refresh_token");
        credentials.scope = scope;
        credentials.expiresAt = Instant.now().plusSeconds(token.path("expires_in").asLong(3600));
        synchronized (credentialLock) {
            stored.credentials = credentials;
            persist(stored);
        }
        return status();
    }

    @Override
    public void logout() {
        synchronized (credentialLock) {
            Credentials credentials = stored.credentials;
            if (credentials == null) {
                return;
            }
            if (StringUtils.isNotBlank(credentials.refreshToken)) {
                try {
                    JsonNode discovery = getJson(DISCOVERY_ENDPOINT, null);
                    String endpoint = discovery.path("revocation_endpoint").asText();
                    if (StringUtils.isNotBlank(endpoint)) {
                        postFormAllowEmpty(endpoint, Map.of("token", credentials.refreshToken,
                                "token_type_hint", "refresh_token", "client_id", credentials.clientId));
                    }
                } catch (RuntimeException e) {
                    // 本地登出仍继续；远端可在 ChatGPT Settings 中断开。
                }
            }
            Credentials retained = new Credentials();
            retained.clientId = credentials.clientId;
            retained.subject = credentials.subject;
            retained.email = credentials.email;
            stored.credentials = retained;
            persist(stored);
        }
    }

    @Override
    public List<ModelOption> models() {
        String accessToken = accessToken();
        JsonNode response = getJson(MODELS_ENDPOINT, accessToken);
        List<ModelOption> result = new ArrayList<>();
        for (JsonNode model : response.path("models")) {
            if ("list".equals(model.path("visibility").asText())) {
                String slug = model.path("slug").asText();
                if (StringUtils.isNotBlank(slug)) {
                    result.add(new ModelOption(slug, model.path("display_name").asText(slug)));
                }
            }
        }
        return result;
    }

    @Override
    public Completion complete(String model, String instructions, String input) {
        String selected = require(model, "ChatGPT 模式尚未选择模型");
        ObjectNode body = JSON.createObjectNode();
        body.put("model", selected);
        if (StringUtils.isNotBlank(instructions)) {
            body.put("instructions", instructions);
        }
        ArrayNode messages = body.putArray("input");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", StringUtils.defaultString(input));
        body.put("store", false);
        body.put("stream", true);
        HttpRequest request = HttpRequest.newBuilder(URI.create(RESPONSES_ENDPOINT))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + accessToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        try {
            HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                // 错误体也要显式关闭：不关的响应流会占住连接，反复失败时连接池被耗尽
                String error;
                try (java.io.InputStream responseBody = response.body()) {
                    error = new String(responseBody.readAllBytes(), StandardCharsets.UTF_8);
                }
                throw new IllegalStateException("ChatGPT Responses API HTTP " + response.statusCode() + "："
                        + StringUtils.abbreviate(error, 500));
            }
            try (java.io.InputStream responseBody = response.body()) {
                return ChatGptSseParser.parse(responseBody);
            }
        } catch (IOException e) {
            throw new IllegalStateException("ChatGPT Responses API 读取失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ChatGPT Responses API 调用被中断", e);
        }
    }

    private String accessToken() {
        synchronized (credentialLock) {
            Credentials credentials = stored.credentials;
            if (credentials == null || StringUtils.isBlank(credentials.refreshToken)) {
                throw new IllegalStateException("尚未通过 Continue with ChatGPT 登录");
            }
            if (StringUtils.isNotBlank(credentials.accessToken) && credentials.expiresAt != null
                    && credentials.expiresAt.isAfter(Instant.now().plusSeconds(120))) {
                return credentials.accessToken;
            }
            JsonNode token = postForm(TOKEN_ENDPOINT, Map.of(
                    "grant_type", "refresh_token",
                    "client_id", credentials.clientId,
                    "refresh_token", credentials.refreshToken,
                    "resource", RESOURCE));
            credentials.accessToken = require(token.path("access_token").asText(null), "刷新响应缺少 access_token");
            credentials.refreshToken = require(token.path("refresh_token").asText(null), "刷新响应缺少 refresh_token");
            credentials.scope = token.path("scope").asText(credentials.scope);
            credentials.expiresAt = Instant.now().plusSeconds(token.path("expires_in").asLong(3600));
            persist(stored);
            return credentials.accessToken;
        }
    }

    private JsonNode validateIdToken(String token, String clientId, String expectedNonce) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new IllegalStateException("id_token 格式无效");
            }
            JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
            JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if (!"RS256".equals(header.path("alg").asText())) {
                throw new IllegalStateException("id_token 签名算法不是 RS256");
            }
            JsonNode jwks = getJson(JWKS_ENDPOINT, null);
            JsonNode jwk = null;
            for (JsonNode candidate : jwks.path("keys")) {
                if (header.path("kid").asText().equals(candidate.path("kid").asText())) {
                    jwk = candidate;
                    break;
                }
            }
            if (jwk == null) {
                throw new IllegalStateException("id_token 的签名密钥不存在");
            }
            BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("n").asText()));
            BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("e").asText()));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent)));
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                throw new IllegalStateException("id_token 签名校验失败");
            }
            if (!ISSUER.equals(claims.path("iss").asText()) || claims.path("exp").asLong() <= Instant.now().getEpochSecond()
                    || !expectedNonce.equals(claims.path("nonce").asText()) || !audienceContains(claims.path("aud"), clientId)) {
                throw new IllegalStateException("id_token 的 issuer/audience/expiry/nonce 校验失败");
            }
            return claims;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("id_token 校验失败：" + e.getMessage(), e);
        }
    }

    private static boolean audienceContains(JsonNode aud, String clientId) {
        if (aud.isTextual()) {
            return clientId.equals(aud.asText());
        }
        if (aud.isArray()) {
            for (JsonNode value : aud) {
                if (clientId.equals(value.asText())) return true;
            }
        }
        return false;
    }

    private JsonNode getJson(String endpoint, String accessToken) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint)).timeout(requestTimeout).GET();
        if (accessToken != null) builder.header("Authorization", "Bearer " + accessToken);
        return sendJson(builder.build());
    }

    private JsonNode postForm(String endpoint, Map<String, String> values) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(requestTimeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(values), StandardCharsets.UTF_8)).build();
        return sendJson(request);
    }

    private void postFormAllowEmpty(String endpoint, Map<String, String> values) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(requestTimeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(values), StandardCharsets.UTF_8)).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) throw httpError(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new IllegalStateException("ChatGPT OAuth 网络失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ChatGPT OAuth 请求被中断", e);
        }
    }

    private JsonNode sendJson(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) throw httpError(response.statusCode(), response.body());
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("ChatGPT OAuth 网络失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ChatGPT OAuth 请求被中断", e);
        }
    }

    private static IllegalStateException httpError(int status, String body) {
        return new IllegalStateException("ChatGPT OAuth HTTP " + status + "：" + StringUtils.abbreviate(body, 500));
    }

    private void validateRedirectUri(String redirectUri) {
        URI uri = URI.create(redirectUri);
        if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getPort() < 1
                || !"/api/auth/chatgpt/callback".equals(uri.getPath())) {
            throw new IllegalArgumentException("ChatGPT OAuth 回调必须使用 http://127.0.0.1:<port>/api/auth/chatgpt/callback");
        }
    }

    private void ensureHostIdPersisted() {
        synchronized (credentialLock) {
            if (StringUtils.isBlank(stored.extAgentHostId)) {
                stored.extAgentHostId = "urn:uuid:" + UUID.randomUUID();
            }
            persist(stored);
        }
    }

    private StoredState load() {
        try {
            if (Files.isRegularFile(credentialFile)) {
                StoredState state = JSON.readValue(Files.readString(credentialFile, StandardCharsets.UTF_8), StoredState.class);
                if (state != null) return state;
            }
        } catch (Exception ignored) {
            // 损坏或旧版凭据不阻止应用启动；重新登录会覆盖。
        }
        StoredState state = new StoredState();
        state.extAgentHostId = "urn:uuid:" + UUID.randomUUID();
        return state;
    }

    private void persist(StoredState state) {
        try {
            Path parent = credentialFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temp = credentialFile.resolveSibling(credentialFile.getFileName() + ".tmp");
            Files.writeString(temp, JSON.writeValueAsString(state), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(temp, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // Windows 上文件位于当前用户的本地工作目录，由该用户 ACL 保护。
            }
            try {
                Files.move(temp, credentialFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, credentialFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("ChatGPT 凭据保存失败：" + e.getMessage(), e);
        }
    }

    private static String form(Map<String, String> values) {
        List<String> parts = new ArrayList<>();
        values.forEach((key, value) -> parts.add(encode(key) + "=" + encode(value)));
        return String.join("&", parts);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String randomUrlToken(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return base64Url(value);
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String require(String value, String message) {
        if (StringUtils.isBlank(value)) throw new IllegalArgumentException(message);
        return value;
    }

    public static final class StoredState {
        public String extAgentHostId;
        public Credentials credentials;
    }

    public static final class Credentials {
        public String clientId;
        public String subject;
        public String email;
        public String idToken;
        public String accessToken;
        public String refreshToken;
        public String scope;
        public Instant expiresAt;
    }

    private record PendingAuthorization(String state, String nonce, String verifier, String redirectUri,
                                        String clientId, Instant createdAt) { }
}
