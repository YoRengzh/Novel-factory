package cn.novel.yonren.api.dto;

import lombok.Data;

import java.util.Map;

/**
 * LLM 运行时配置保存请求（PUT /api/config/llm）：
 * 全局字段仅非空生效覆盖；apiKey 留空/空白表示保留现有值（不回显原文，避免被清空）；
 * reset=true 清空全部运行时覆盖（含场景覆盖），回退 novel-generation.yml 静态配置。
 * scenes：按场景覆盖（key=ModelScene.configKey），前端场景矩阵全量提交、后端整体替换——
 * 条目内空字段=清除该字段覆盖（区别于全局字段的"留空保留"）；scenes=null 表示本次不动场景覆盖；
 * resetScenes=true 仅清空场景覆盖（不动全局覆盖），可与全局字段同请求
 */
@Data
public class LlmConfigSaveRequestDTO {
    /** 主聊天模型认证方式：api-key 或 chatgpt；null=保持现状 */
    private String authMode;
    /** 接口地址覆盖；留空/空白=保留现有值 */
    private String baseUrl;
    /** 新密钥；留空/空白=保留现有值（原文不回显，避免被误清空） */
    private String apiKey;
    /** 模型名覆盖；留空/空白=保留现有值 */
    private String model;
    /** 最大输出 token 覆盖；null=保留现有值 */
    private Long maxTokens;
    /** true=清空全部运行时覆盖（含场景覆盖），回退 novel-generation.yml 静态配置 */
    private boolean reset;
    /** 仅清空分场景覆盖（保留全局覆盖与全局字段语义） */
    private boolean resetScenes;
    /**
     * 场景级 API 总开关（null=本次不改动）：false=所有场景强制走全局 ai-api 的 base-url/api-key，
     * 场景自带地址密钥（yml 与场景覆盖）一律不生效；true=场景可自带地址密钥。
     * 缺省（从未设置过）=true，保持"场景可自带 API"的既有行为
     */
    private Boolean sceneApiEnabled;
    /** 分场景覆盖全量替换表；null=本次不触碰场景覆盖（区别于空表=清空） */
    private Map<String, SceneOverride> scenes;
    /** 嵌入分区覆盖；null=本次不触碰嵌入覆盖；字段级：null=保留现状、空串=清除该字段覆盖 */
    private EmbeddingOverride embedding;
    /** true=仅清空嵌入覆盖（不动全局/场景覆盖），回退 yml 静态嵌入配置 */
    private boolean resetEmbedding;
    /**
     * 动态资料选择器模式（null=本次不改动）：llm = LLM 索引选择；vector = 向量语义检索；
     * 空串=清除覆盖回退 yml 静态值。设置页「资料检索方式」开关读写它。
     */
    private String referenceMode;

    /**
     * 功能开关覆盖（key = FeatureToggles 常量名，value = 是否开启）。
     * null = 本次不改动开关覆盖（区别于空表 = 清空全部开关覆盖、全部回退 yml 静态值）。
     */
    private Map<String, Boolean> toggles;

    /** 嵌入分区覆盖条目：与全局字段不同，显式空串=清除该字段覆盖（嵌入分区按字段精细控制） */
    @Data
    public static class EmbeddingOverride {
        /** 嵌入接口地址覆盖；null=保留现状，空串=清除覆盖回退静态 */
        private String baseUrl;
        /** 新嵌入密钥；null=保留现状，空串=清除覆盖回退静态 */
        private String apiKey;
        /** 嵌入模型名覆盖；null=保留现状，空串=清除覆盖回退静态 */
        private String model;
        /** 向量维度覆盖；null=保留现状。⚠️ 与 Qdrant 集合维度绑死，改动需重建索引 */
        private Integer dimensions;
    }

    /** 单场景覆盖条目：任一字段为空=该字段清除覆盖，回退全局覆盖/静态配置 */
    @Data
    public static class SceneOverride {
        /** 场景模型名覆盖；null=清除该字段覆盖（回退全局覆盖/静态配置） */
        private String model;
        /** 场景最大输出 token 覆盖；null=清除该字段覆盖 */
        private Long maxTokens;
        /** 场景采样温度覆盖；null=清除该字段覆盖 */
        private Double temperature;
        /** 场景级接口地址覆盖；null/空=清除该字段覆盖（回退场景 yml / 全局地址） */
        private String baseUrl;
        /** 场景级新密钥；null=保留该场景现有密钥覆盖（密钥不回显，避免被静默清空），空串=显式清除 */
        private String apiKey;

        public boolean isEmpty() {
            return model == null && maxTokens == null && temperature == null
                    && baseUrl == null && apiKey == null;
        }
    }
}
