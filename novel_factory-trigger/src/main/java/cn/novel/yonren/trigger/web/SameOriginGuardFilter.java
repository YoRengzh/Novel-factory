package cn.novel.yonren.trigger.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;

/**
 * 本机应用的跨站写操作守卫。
 *
 * <p>服务只绑定 127.0.0.1，但这挡不住浏览器：任意网页都可以向
 * {@code http://127.0.0.1:8080/api/stories/<目录名>/delete} 提交一个普通表单 POST
 * （表单 POST 不触发 CORS 预检），而删除/取消/回滚这些端点都没有请求体，
 * 于是"打开一个恶意页面"就能删掉本地稿件。
 *
 * <p>判据：**带 Origin 的写请求，其 host 必须是回环地址**（localhost / 127.0.0.1 / ::1），
 * 与 WebConfig 的 CORS 白名单同一口径——允许本机其它端口的开发工具，
 * 拒绝任何外部站点。无 Origin 的请求放行：命令行、Electron 主进程、MockMvc 都不带该头。
 *
 * <p>这是"本地单用户、无登录态"前提下的最小防护，不引入会话/JWT；
 * 纵深防御仍是让服务只监听回环地址（{@code server.address: 127.0.0.1}）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SameOriginGuardFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (isWriteMethod(request.getMethod()) && !isTrustedOrigin(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "跨站写请求被拒绝");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** 只拦截会改变状态的动词；GET/HEAD/OPTIONS 保持只读语义（CORS 预检也走 OPTIONS） */
    private static boolean isWriteMethod(String method) {
        return "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method);
    }

    /** 无 Origin（非浏览器客户端）放行；有 Origin 时只认回环地址 */
    static boolean isTrustedOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            return true;
        }
        try {
            String host = URI.create(origin.trim()).getHost();
            return "localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host)
                    || "::1".equals(host)
                    || "[::1]".equals(host);
        } catch (RuntimeException e) {
            // Origin 不是合法 URI（含 "null"）：一律按跨站处理
            return false;
        }
    }
}
