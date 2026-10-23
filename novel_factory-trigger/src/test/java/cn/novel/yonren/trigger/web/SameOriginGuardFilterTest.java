package cn.novel.yonren.trigger.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 跨站写操作守卫：有 Origin 的写请求必须与 Host 同源。
 * 关注点是"浏览器表单 POST 不触发预检"这条真实攻击路径——删除/取消/回滚都没有请求体。
 */
class SameOriginGuardFilterTest {

    private final SameOriginGuardFilter filter = new SameOriginGuardFilter();

    @Test
    void allowsWriteWithoutOriginHeader() throws Exception {
        // 命令行 / Electron 主进程 / MockMvc 都不带 Origin：按非浏览器客户端放行
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/stories/x/delete");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    @Test
    void allowsSameOriginWrite() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/jobs/job-1/cancel");
        request.addHeader("Host", "127.0.0.1:8080");
        request.addHeader("Origin", "http://127.0.0.1:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    @Test
    void allowsLoopbackOriginOnAnotherPort() throws Exception {
        // 与 WebConfig 的 CORS 白名单同口径：本机其它端口的开发工具可用，外部站点不行
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/config/llm");
        request.addHeader("Host", "127.0.0.1:8080");
        request.addHeader("Origin", "http://localhost:3000");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    @Test
    void rejectsCrossSiteWrite() throws Exception {
        // 恶意页面用普通表单 POST 打本机端点：Origin 是外部源，必须拒绝
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/stories/20261022-story-0001/delete");
        request.addHeader("Host", "127.0.0.1:8080");
        request.addHeader("Origin", "https://evil.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
    }

    @Test
    void rejectsOpaqueOrigin() throws Exception {
        // sandbox iframe 的 Origin 字面量是 "null"：同样不是同源
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/auth/chatgpt");
        request.addHeader("Host", "127.0.0.1:8080");
        request.addHeader("Origin", "null");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
    }
}
