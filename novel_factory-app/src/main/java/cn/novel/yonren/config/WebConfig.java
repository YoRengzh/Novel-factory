package cn.novel.yonren.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;


@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    /**
     * 静态资源缓存策略：入口文件 app.js / style.css / index.html 必须每次协商，
     * 否则升级后浏览器（以及 Electron 内置 Chromium）可能继续用旧缓存——
     * 表现为"代码明明改了、界面上还是旧行为"，是前端改动最容易被误判成没生效的坑。
     *
     * 只对这三个入口文件禁用缓存；图片/图标等无逻辑的静态资源仍走默认策略。
     *
     * 为什么不用"给 URL 加版本号"：index.html 是纯静态文件，没有模板引擎插值，
     * 加版本号要么引入 Thymeleaf 渲染路径，要么在启动时改写安装目录里的文件
     * （可能只读）。用响应头控制缓存更直接，且对 Electron 打包同样生效。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        CacheControl noCache = CacheControl.noCache().mustRevalidate();
        // 资源位置必须是"目录"，让 handler 用 URL 里的文件名去目录里找：
        // 写 classpath:/static/app.js 会被当成目录处理（找不到文件，回落到默认 handler）
        for (String entry : new String[]{"app.js", "style.css", "index.html"}) {
            registry.addResourceHandler("/" + entry)
                    .addResourceLocations("classpath:/static/")
                    .setCacheControl(noCache);
        }
    }
}
