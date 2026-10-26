package cn.novel.yonren.types.enums;

import org.apache.commons.lang3.StringUtils;

/**
 * LLM 接入协议：同一个网关按协议选择不同的传输实现（Chat Completions / Messages / Responses）。
 * 协议是 API 层属性（挂在 ai-api / 中转站上），与模型名、场景路由无关——
 * 同一份烧穿判定/降级链/记账对三种协议通用。
 */
public enum LlmProtocol {

    /** OpenAI Chat Completions（/v1/chat/completions）：绝大多数中转站与兼容供应商 */
    OPENAI("openai"),

    /** Anthropic Messages（/v1/messages）：Claude 官方与 Anthropic 协议中转站（x-api-key 鉴权） */
    ANTHROPIC("anthropic"),

    /** OpenAI Responses（/v1/responses）：官方 Responses API 与其兼容中转站（gpt-5 系等） */
    RESPONSES("responses");

    private final String code;

    LlmProtocol(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * 解析配置值：null/空白回 {@link #OPENAI}（既有配置无该字段，缺省必须等于升级前行为）；
     * 未知值抛 IllegalArgumentException（保存路径校验用，防静默落到错误协议）。
     */
    public static LlmProtocol parse(String value) {
        if (StringUtils.isBlank(value)) {
            return OPENAI;
        }
        for (LlmProtocol protocol : values()) {
            if (protocol.code.equalsIgnoreCase(value.trim())) {
                return protocol;
            }
        }
        throw new IllegalArgumentException("不支持的 LLM 接口协议：" + value + "（仅 openai / anthropic / responses）");
    }

    /**
     * 宽松解析：无法识别时返回 null（不抛）。供"单条丢弃优于整表拒绝"的批量合并路径用
     * （中转站注册表整表提交，一条坏数据不应殃及全表）；单字段输入路径仍用 {@link #parse}
     * 拿精确报错。
     */
    public static LlmProtocol parseOrNull(String value) {
        if (StringUtils.isBlank(value)) {
            return OPENAI;
        }
        for (LlmProtocol protocol : values()) {
            if (protocol.code.equalsIgnoreCase(value.trim())) {
                return protocol;
            }
        }
        return null;
    }
}
