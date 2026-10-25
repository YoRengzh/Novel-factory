package cn.novel.yonren.infrastructure.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型列表客户端测试（本地 HttpServer）：/v1 路径适配与 404 回退、
 * OpenAI data[] 解析与排序、鉴权头透传、无模型条目报错
 */
class ModelListClientTest {

    private HttpServer server;
    private ModelListClient client;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicReference<String> authHeader = new AtomicReference<>();
    /** handler 按 path 返回：null = 404 */
    private volatile java.util.function.Function<String, String> responder;

    @BeforeEach
    void start() throws IOException {
        client = new ModelListClient();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String body = responder.apply(exchange.getRequestURI().getPath());
            int status = body == null ? 404 : 200;
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String base() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        return url;
    }

    @Test
    void baseUrlWithoutV1_hitsV1ModelsAndSortsIds() {
        responder = path -> "/v1/models".equals(path)
                ? "{\"data\":[{\"id\":\"zeta\"},{\"id\":\"alpha\"},{\"id\":\"mid\"}]}" : null;

        List<String> models = client.fetchModels(base(), "sk-test");

        assertEquals(List.of("alpha", "mid", "zeta"), models, "模型名按字典序排序");
        assertEquals("Bearer sk-test", authHeader.get(), "密钥走 Authorization Bearer 头");
        assertEquals(1, hits.get(), "主路径命中后不应再试备用路径");
    }

    @Test
    void baseUrlWithV1_fallsBackWhenPrimary404() {
        // 用户地址已带 /v1：主路径 /v1/models 404（有些中转把列表挂在根 /models），应自动换路
        responder = path -> "/models".equals(path)
                ? "{\"data\":[{\"id\":\"only-one\"}]}" : null;

        List<String> models = client.fetchModels(base() + "/v1", null);

        assertEquals(List.of("only-one"), models);
        assertEquals(2, hits.get(), "主路径 404 后应试备用路径");
        assertEquals(null, authHeader.get(), "无密钥时不发 Authorization 头");
    }

    @Test
    void plainArrayBody_accepted() {
        responder = path -> "[{\"id\":\"arr-model\"}]";

        List<String> models = client.fetchModels(base(), "sk");

        assertEquals(List.of("arr-model"), models, "个别中转直接给数组，两种格式都收");
    }

    @Test
    void emptyCatalog_reportsFailureWithExcerpt() {
        responder = path -> "{\"data\":[]}";

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> client.fetchModels(base(), null));
        assertTrue(ex.getMessage().contains("没有模型条目"), "消息面向设置页展示：" + ex.getMessage());
    }

    @Test
    void unreachableUrl_throwsWithReason() {
        assertThrows(IllegalStateException.class,
                () -> client.fetchModels("http://127.0.0.1:1", null));
    }
}
