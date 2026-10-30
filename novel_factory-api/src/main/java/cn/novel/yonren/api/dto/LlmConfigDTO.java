package cn.novel.yonren.api.dto;

import lombok.Data;

import java.util.List;

/**
 * LLM 运行时配置视图（GET /api/config/llm 响应）：
 * 前端设置面板读取当前生效的运行时覆盖 + yml 静态配置合成出的场景模型矩阵；
 * apiKey 不回传原文，只给掩码与是否存在标记。
 * configured=false 表示未设置全局覆盖，实际调用仍走 novel-generation.yml 静态配置
 */
@Data
public class LlmConfigDTO {
    /** 主聊天模型认证方式：api-key（默认）或 chatgpt（Continue with ChatGPT） */
    private String authMode;
    /** 接口地址（非机密，原文返回便于表单回显） */
    private String baseUrl;
    /**
     * 手动模式的接口协议覆盖（openai/anthropic/responses）；null=未设置（沿用 yml 静态值）。
     * 全局选中中转站时以该站协议优先，此字段不参与
     */
    private String protocol;
    /** 是否存在 apiKey 覆盖（区别于空值） */
    private boolean hasKey;
    /** apiKey 掩码（如 sk-***abcd），未设置时为 null */
    private String apiKeyMasked;
    /** 模型名（覆盖字段，未设置时为 null） */
    private String model;
    /** 最大输出 token（覆盖字段，未设置时为 null） */
    private Long maxTokens;
    /** 是否存在运行时覆盖（false = 全部走静态配置） */
    private boolean configured;

    /** 分场景路由总开关（yml module.unified-model-enabled）：true=全场景统一走 chat-model */
    private boolean unifiedModelEnabled;
    /**
     * 场景级 API 总开关（运行时）：false=所有场景强制走全局 ai-api 的地址与密钥，
     * 场景自带 base-url/api-key（yml 与场景覆盖）一律不生效；true/缺省=场景可自带地址密钥
     */
    private Boolean sceneApiEnabled;
    /** 统一 chat-model 静态配置（unified-model-enabled=true 或场景未配置时的生效来源） */
    private ModelView unified;
    /** 场景模型矩阵（按 ModelScene 枚举序，含静态生效值与运行时覆盖） */
    private List<SceneView> scenes;

    /**
     * 全局兜底模型（2026-10-23）：所有场景共用一份，主模型与场景配置都不可用时启用。
     * 自带地址密钥与输出上限——兜底的意义是换供应商，配置必须能跟着变
     */
    private FallbackView fallback;

    /** 全局兜底模型视图：静态值 + 运行时覆盖 + 生效值 */
    @Data
    public static class FallbackView {
        /** yml 静态兜底模型名（null=未配置静态兜底） */
        private String staticModel;
        /** yml 静态兜底最大输出 token */
        private Long staticMaxTokens;
        /** yml 静态兜底接口地址 */
        private String staticBaseUrl;
        /** yml 静态兜底是否已配置 apiKey（原文不回传） */
        private boolean staticHasKey;
        /** yml 静态兜底 apiKey 掩码 */
        private String staticApiKeyMasked;
        /** 运行时覆盖模型名（未覆盖为 null） */
        private String overrideModel;
        /** 运行时覆盖最大输出 token（未覆盖为 null） */
        private Long overrideMaxTokens;
        /** 运行时覆盖采样温度（未覆盖为 null；不回显则用户看不见也清不掉已存的覆盖） */
        private Double overrideTemperature;
        /** 运行时覆盖的中转站引用（未覆盖为 null）：前端据此回显选中的站 */
        private String overrideProviderId;
        /** 运行时覆盖接口地址（未覆盖为 null） */
        private String overrideBaseUrl;
        /** 运行时覆盖是否已配置 apiKey */
        private boolean overrideHasKey;
        /** 运行时覆盖 apiKey 掩码 */
        private String overrideApiKeyMasked;
        /** 是否存在运行时覆盖 */
        private boolean overridden;
        /** 最终生效的模型名（覆盖 ?? 静态；null=不启用兜底） */
        private String effectiveModel;
    }

    /** Embedding 向量分区（只读展示：嵌入模型不走运行时覆盖，改 yml 需重启生效） */
    private EmbeddingView embedding;

    /**
     * 动态资料选择器模式（运行时覆盖）：llm = LLM 索引选择；vector = 向量语义检索；
     * null=未设置（当前生效值 = yml story.prompt.reference-selector.mode，前端展示该事实）
     */
    private String referenceMode;

    /** 资料检索方式当前生效值（覆盖优先，未覆盖= yml 静态值）：前端下拉直接回显它 */
    private String effectiveReferenceMode;

    /** 全局中转站引用（null=手动地址密钥模式）；选中时全局 baseUrl/apiKey 优先取该供应商的值 */
    private String providerId;

    /** 中转站注册表视图（密钥只给掩码） */
    private List<ProviderView> providers;

    /** 单个中转站视图 */
    @Data
    public static class ProviderView {
        /** 供应商 id（前端生成，提交时原样回传） */
        private String id;
        /** 显示名 */
        private String name;
        /** OpenAI 兼容 Base URL（非机密，原文返回便于核对指向） */
        private String baseUrl;
        /** 接口协议（openai/anthropic/responses）；null=未设置按 openai 处理 */
        private String protocol;
        /** 是否已配置 apiKey（原文不回传） */
        private boolean hasKey;
        /** apiKey 掩码，未设置为 null */
        private String apiKeyMasked;
        /** 最近一次「获取模型」缓存到的模型名（空表=还没获取过）；场景矩阵据此反查模型归属 */
        private java.util.List<String> models;
    }

    /**
     * 功能开关（质量/速度档）：每条的静态值（yml）、运行时覆盖（null=未覆盖）与生效值。
     * 这些开关决定每章额外跑几次模型调用，是单章耗时的主要变量
     */
    private List<ToggleView> toggles;

    /** 单个功能开关的视图 */
    @Data
    public static class ToggleView {
        /** 开关键（FeatureToggles 常量名，前端提交时原样回传） */
        private String key;
        /** 中文显示名 */
        private String label;
        /** 说明：关掉能省什么、什么时候该关 */
        private String description;
        /** 每章额外模型调用量提示（用于前端显示"关掉能省 N 次调用"） */
        private String costHint;
        /** yml 静态值 */
        private boolean staticValue;
        /** 运行时覆盖值；null=未覆盖（生效值 = 静态值） */
        private Boolean overrideValue;
        /** 实际生效值 */
        private boolean effectiveValue;
    }

    /** 单个模型的只读视图（统一 chat-model 用） */
    @Data
    public static class ModelView {
        /** 模型名 */
        private String model;
        /** 最大输出 token（单次生成上限） */
        private Long maxTokens;
        /** 采样温度（0~2，越高越有创造性、越随机；null=走供应商默认） */
        private Double temperature;
        /** 是否开启思考模式（DashScope 等供应商特有；null=不干预走供应商默认） */
        private Boolean enableThinking;
    }

    /**
     * 单个场景的矩阵行：静态生效值（yml scene-models 条目，未配置时回落统一 chat-model）、
     * 运行时覆盖值（data/llm-config-override.json scenes 条目）、合成后的最终生效值。
     * 优先级（按字段）：场景覆盖 > 全局覆盖 > yml 静态（场景条目缺项回落统一 chat-model）
     */
    @Data
    public static class SceneView {
        /** yml scene-models 的键（ModelScene.configKey） */
        private String key;
        /** 中文显示名 */
        private String label;
        /** yml 是否为该场景配置了独立条目（false=静态回落统一 chat-model） */
        private boolean sceneConfigured;
        /** yml 场景条目是否自带 base-url/api-key（第二模型族，如 chapter-judge 指向异供应商） */
        private boolean independentApi;
        /** 静态生效模型（scene 条目 ?? 统一 chat-model） */
        private String staticModel;
        /** 静态生效的最大输出 token（回落规则同 staticModel） */
        private Long staticMaxTokens;
        /** 静态生效采样温度（回落规则同 staticModel） */
        private Double staticTemperature;
        /** 运行时覆盖（未覆盖字段为 null） */
        private String overrideModel;
        /** 运行时覆盖的最大输出 token（未覆盖为 null） */
        private Long overrideMaxTokens;
        /** 运行时覆盖的采样温度（未覆盖为 null） */
        private Double overrideTemperature;
        /** 运行时覆盖的场景级中转站引用（未覆盖为 null）：前端据此回显选中的站 */
        private String overrideProviderId;
        /** 运行时覆盖的场景级接口地址（未覆盖为 null；密钥类字段只回掩码） */
        private String overrideBaseUrl;
        /** 该场景是否已覆盖 apiKey（原文不回传） */
        private boolean overrideHasKey;
        /** 覆盖密钥掩码（未设置为 null） */
        private String overrideApiKeyMasked;
        /** 该场景是否存在运行时覆盖 */
        private boolean overridden;
    }

    /**
     * Embedding 接入视图：静态生效值 + 运行时覆盖（独立分区，覆盖经 applyEmbedding 落到向量调用链，
     * 客户端按 地址|密钥|模型 缓存、变化即换新客户端，保存即时生效）。apiKey 任何一侧都只回掩码
     */
    @Data
    public static class EmbeddingView {
        /** 是否已配置静态 embedding-api（false=向量召回不可用，生成主链路不受影响） */
        private boolean configured;
        /** 静态生效嵌入模型名 */
        private String model;
        /** 静态接口地址（非机密，原文返回便于核对指向） */
        private String baseUrl;
        /** 静态是否已配置 apiKey */
        private boolean hasKey;
        /** 静态 apiKey 掩码，未设置时为 null */
        private String apiKeyMasked;
        /** 静态向量维度（null=走供应商默认） */
        private Integer dimensions;
        /** 是否存在嵌入运行时覆盖 */
        private boolean overridden;
        /** 覆盖后的接口地址（未覆盖为 null） */
        private String overrideBaseUrl;
        /** 覆盖密钥是否存在（原文不回传） */
        private boolean overrideHasKey;
        /** 覆盖密钥掩码（未设置时为 null） */
        private String overrideApiKeyMasked;
        /** 覆盖后的模型名（未覆盖为 null） */
        private String overrideModel;
        /** 覆盖后的向量维度（未覆盖为 null） */
        private Integer overrideDimensions;
    }
}
