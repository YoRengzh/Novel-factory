package cn.novel.yonren.api.dto;

import lombok.Data;

/**
 * 模型列表拉取请求（POST /api/config/llm/models）：
 * 三种用法——① providerId：取已保存中转站的地址密钥；② 显式 baseUrl/apiKey：
 * 保存前先试连（填错地址立刻知道）；③ 两者同给：地址用显式值、密钥缺省回中转站存的
 */
@Data
public class ModelFetchRequestDTO {
    /** 已保存中转站 id；null=用显式地址 */
    private String providerId;
    /** 显式 Base URL（保存前试连用）；null 时回退 providerId 对应地址 */
    private String baseUrl;
    /** 显式 API Key；null 时回退 providerId 对应密钥 */
    private String apiKey;
    /** 接口协议（openai/anthropic/responses）；null 时回退 providerId 对应协议，最终缺省 openai */
    private String protocol;
}
