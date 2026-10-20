package cn.novel.yonren.trigger.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 应用元信息端点：供前端「通知」面板显示当前版本。
 * 只回版本号与更新检查所需的仓库坐标——最新版本由前端直接查 GitHub Releases
 * （api.github.com 支持跨域，且发布源就是公开仓库，无需经过后端中转）。
 */
@RestController
@RequestMapping("/api/app")
public class AppInfoController {

    /** 与 electron/package.json 的 version 保持一致；build-release.sh 会校验两者相同 */
    @Value("${app.version:1.0.0}")
    private String version;

    @GetMapping("/version")
    public Map<String, String> version() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("repo", "YoRengzh/Novel-factory");
        body.put("releasesUrl", "https://github.com/YoRengzh/Novel-factory/releases/latest");
        return body;
    }
}
