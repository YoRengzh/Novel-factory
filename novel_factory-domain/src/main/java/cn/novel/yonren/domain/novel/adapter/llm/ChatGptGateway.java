package cn.novel.yonren.domain.novel.adapter.llm;

import java.time.Instant;
import java.util.List;

/**
 * OpenAI "Continue with ChatGPT" 端口。实现层负责 OAuth/OIDC、令牌轮换及
 * Responses API 的强制流式协议；领域层不接触或保存访问令牌。
 */
public interface ChatGptGateway {

    AuthStatus status();

    Authorization beginAuthorization(String redirectUri);

    AuthStatus finishAuthorization(String code, String state, String issuedClientId, String redirectUri);

    void logout();

    List<ModelOption> models();

    Completion complete(String model, String instructions, String input);

    record AuthStatus(boolean authorized, String email, Instant expiresAt) { }

    record Authorization(String url) { }

    record ModelOption(String slug, String displayName) { }

    record Completion(String content, long promptTokens, long completionTokens, long totalTokens) { }
}
