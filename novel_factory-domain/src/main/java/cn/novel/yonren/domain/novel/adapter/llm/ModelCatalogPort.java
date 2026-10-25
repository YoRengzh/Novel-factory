package cn.novel.yonren.domain.novel.adapter.llm;

import java.util.List;

/**
 * 供应商模型目录端口：按地址密钥拉取可用模型名列表（OpenAI 兼容 GET /v1/models）。
 * 设置页"中转站 → 获取模型"按钮的唯一依赖；实现层负责地址路径适配与鉴权头
 */
public interface ModelCatalogPort {

    /**
     * 拉取模型 id 列表（已排序）。地址带或不带 /v1 均可。
     *
     * @param baseUrl OpenAI 兼容地址
     * @param apiKey  Bearer 密钥；null/空 = 匿名请求
     * @throws IllegalStateException 地址不可达 / 鉴权失败 / 响应无模型条目（消息面向用户展示）
     */
    List<String> fetchModels(String baseUrl, String apiKey);
}
