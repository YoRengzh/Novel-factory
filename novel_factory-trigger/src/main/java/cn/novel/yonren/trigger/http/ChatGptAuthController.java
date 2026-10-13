package cn.novel.yonren.trigger.http;

import cn.novel.yonren.domain.novel.adapter.llm.ChatGptGateway;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Continue with ChatGPT 的本机 OAuth 入口；令牌从不返回浏览器。 */
@RestController
@RequestMapping("/api/auth/chatgpt")
public class ChatGptAuthController {

    private final ChatGptGateway gateway;

    public ChatGptAuthController(ChatGptGateway gateway) {
        this.gateway = gateway;
    }

    @GetMapping("/status")
    public ChatGptGateway.AuthStatus status() {
        return gateway.status();
    }

    @PostMapping("/start")
    public ChatGptGateway.Authorization start(HttpServletRequest request) {
        return gateway.beginAuthorization(callbackUri(request));
    }

    @GetMapping("/models")
    public List<ChatGptGateway.ModelOption> models() {
        return gateway.models();
    }

    @DeleteMapping
    public ChatGptGateway.AuthStatus logout() {
        gateway.logout();
        return gateway.status();
    }

    @GetMapping(value = "/callback", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> callback(@RequestParam(name = "code", required = false) String code,
                                           @RequestParam(name = "state", required = false) String state,
                                           @RequestParam(name = "client_id", required = false) String clientId,
                                           @RequestParam(name = "error", required = false) String error,
                                           HttpServletRequest request) {
        if (error != null) {
            return html(callbackPage(false, "授权未完成，请关闭窗口后重试"));
        }
        gateway.finishAuthorization(code, state, clientId, callbackUri(request));
        return html(callbackPage(true, "登录成功，可以关闭此窗口"));
    }

    private static String callbackUri(HttpServletRequest request) {
        return "http://127.0.0.1:" + request.getLocalPort() + "/api/auth/chatgpt/callback";
    }

    private static ResponseEntity<String> html(String body) {
        return ResponseEntity.ok().contentType(new MediaType("text", "html", StandardCharsets.UTF_8)).body(body);
    }

    private static String callbackPage(boolean success, String message) {
        return "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><title>ChatGPT 登录</title>"
                + "<style>body{font:16px system-ui;margin:48px;color:#17211b}button{padding:10px 18px}</style>"
                + "<body><h2>" + message + "</h2><button onclick=\"window.close()\">关闭窗口</button>"
                + "<script>if(window.opener){window.opener.postMessage({type:'chatgpt-auth-complete',success:"
                + success + "},'*')}if(" + success + "){setTimeout(()=>window.close(),900)}</script></body></html>";
    }
}
