package cn.novel.yonren.domain.novel.adapter.llm;

import java.util.List;

/**
 * 供应商模型目录端口：按地址密钥拉取可用模型名列表。
 * 设置页"中转站 → 获取模型"按钮的唯一依赖；实现层负责地址路径适配与鉴权头。
 * openai/responses 协议走 Bearer + /v1/models；anthropic 协议走 x-api-key + anthropic-version
 */
public interface ModelCatalogPort {

    /**
     * 拉取模型 id 列表（已排序）。地址带或不带 /v1 均可。
     *
     * @param baseUrl OpenAI 兼容地址
     * @param apiKey  密钥；null/空 = 匿名请求（个别本地中转允许无鉴权）
     * @param protocol 接口协议（LlmProtocol.code）；null/空 = openai
     * @throws IllegalStateException 地址不可达 / 鉴权失败 / 响应无模型条目（消息面向用户展示）
     */
    List<String> fetchModels(String baseUrl, String apiKey, String protocol);

    /** 兼容旧调用（openai 协议）：等价 fetchModels(baseUrl, apiKey, null) */
    default List<String> fetchModels(String baseUrl, String apiKey) {
        return fetchModels(baseUrl, apiKey, null);
    }
}
