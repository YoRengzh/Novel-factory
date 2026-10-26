package cn.novel.yonren.infrastructure.gateway;

import cn.novel.yonren.domain.novel.adapter.llm.ModelCatalogPort;
import cn.novel.yonren.types.enums.LlmProtocol;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 供应商的模型列表拉取（GET /v1/models）：设置页添加中转站后一键取回模型名，
 * 供模型下拉框使用。地址适配两种习惯：带 /v1 与不带 /v1 的 Base URL 都能命中
 * （先按用户写法推断主路径，404 时再试另一路径——one-api/new-api 类中转两种挂法都常见）。
 * 鉴权头按协议分派：openai/responses 走 Authorization Bearer；anthropic 走
 * x-api-key + anthropic-version（路径与响应格式同构，无需单独解析器）
 */
@Slf4j
@Component
public class ModelListClient implements ModelCatalogPort {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * 拉取模型 id 列表（已排序）。失败抛 IllegalStateException，消息面向设置页展示（含 HTTP 状态）。
     *
     * @param baseUrl  供应商地址（带或不带 /v1 均可）
     * @param apiKey   密钥；null/空 = 匿名请求（个别本地中转允许无鉴权）
     * @param protocol 接口协议（LlmProtocol.code）；null/空 = openai
     */
    @Override
    public List<String> fetchModels(String baseUrl, String apiKey, String protocol) {
        if (StringUtils.isBlank(baseUrl)) {
            throw new IllegalArgumentException("Base URL 为空，无法拉取模型列表");
        }
        boolean anthropic = LlmProtocol.parse(protocol) == LlmProtocol.ANTHROPIC;
        String base = baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        // 主路径按用户写法推断：已带 /v1 就直接 /models，否则补 /v1/models。
        // 备用路径统一是「去掉 /v1 的根 + /models」：无论用户怎么填，挂法 B（列表挂根路径）
        // 的终点都一样——one-api/new-api 类中转两种挂法都覆盖
        String root = base.endsWith("/v1") ? base.substring(0, base.length() - 3) : base;
        String primary = base.endsWith("/v1") ? base + "/models" : base + "/v1/models";
        String alternate = root + "/models";

        IllegalStateException last = null;
        for (String endpoint : new String[]{primary, alternate}) {
            try {
                return get(endpoint, apiKey, anthropic);
            } catch (IllegalStateException e) {
                last = e;
                log.info("模型列表路径未命中（{}）：{}", endpoint, e.getMessage());
            }
        }
        throw last == null ? new IllegalStateException("模型列表拉取失败") : last;
    }

    private List<String> get(String endpoint, String apiKey, boolean anthropic) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET();
        if (StringUtils.isNotBlank(apiKey)) {
            if (anthropic) {
                // Anthropic 官方与协议中转站的鉴权头：x-api-key + 版本号（Bearer 也被官方接受，
                // 但中转站实现参差，按协议规范头最稳）
                builder.header("x-api-key", apiKey.trim());
                builder.header("anthropic-version", ANTHROPIC_VERSION);
            } else {
                builder.header("Authorization", "Bearer " + apiKey.trim());
            }
        }
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("请求失败：" + e.getMessage(), e);
        }
        if (response.statusCode() == 404 || response.statusCode() == 405) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + "："
                    + excerpt(response.body()) + "（" + endpoint + "）");
        }
        List<String> models = parseIds(response.body());
        if (models.isEmpty()) {
            throw new IllegalStateException("响应里没有模型条目：" + excerpt(response.body()));
        }
        return models;
    }

    /** OpenAI 格式 {"data":[{"id":...}]}；个别中转直接给数组 [{"id":...}]，两种都收 */
    private static List<String> parseIds(String body) {
        List<String> ids = new ArrayList<>();
        try {
            Object parsed = JSON.parse(body);
            if (parsed instanceof JSONObject obj) {
                JSONArray data = obj.getJSONArray("data");
                if (data != null) {
                    collect(data, ids);
                }
            } else if (parsed instanceof JSONArray array) {
                collect(array, ids);
            }
        } catch (Exception ignored) {
            // 非 JSON 响应按"无模型条目"处理，由调用方带摘要报错
        }
        return ids;
    }

    private static void collect(JSONArray array, List<String> ids) {
        for (int i = 0; i < array.size(); i++) {
            String id = array.getJSONObject(i) == null ? null : array.getJSONObject(i).getString("id");
            if (StringUtils.isNotBlank(id)) {
                ids.add(id.trim());
            }
        }
        ids.sort(String.CASE_INSENSITIVE_ORDER);
    }

    private static String excerpt(String body) {
        if (body == null) {
            return "(空响应)";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() > 160 ? flat.substring(0, 160) + "…" : flat;
    }
}
