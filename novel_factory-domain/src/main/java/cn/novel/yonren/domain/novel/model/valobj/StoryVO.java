package cn.novel.yonren.domain.novel.model.valobj;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class StoryVO {
    private Defaults defaults;
    private Module module;
    private Constraints constraints;
    /** 故事级一致性开关；由 story-bible/请求明确声明，未声明时不启用条件规则。 */
    private StoryFeatures features;

    @Data
    public static class StoryFeatures {
        /** 是否存在金手指、系统、外挂或其他核心辅助机制。null 表示未声明。 */
        private Boolean hasCheatMechanism;
        /** 已确认的金手指/系统名称，便于审计和 Prompt 渲染。 */
        private String cheatMechanismName;
        /** 金手指使用/有意义提及的最大间隔，默认 3 章。 */
        private Integer cheatUsageInterval = 3;
    }

    @Data
    public static class Defaults {
        private String language;
        private Long totalCount;
    }

    @Data
    public static class Module {
        private AiApi aiApi;
        private ChatModel chatModel;
        private EmbeddingApi embeddingApi;
        // 总开关：true=所有场景统一用 chatModel；false=按 scene-models 分场景路由（未配置的场景回退 chatModel）
        private Boolean unifiedModelEnabled;
        // 分场景模型：key 为 ModelScene.configKey（如 "chapter-plan"），value 为独立模型/思考模式配置
        private Map<String, ChatModel> sceneModels;
        /**
         * 全局兜底模型（2026-10-23，替代此前的按场景 {@code model-fallbacks} 表）。
         *
         * <p>所有场景共用这一个兜底：主模型与场景配置都不可用时才启用。之所以做成
         * "单个 + 自带完整配置"而不是"每场景一条链"：
         * <ul>
         *   <li><b>兜底的语义就是"原路走不通"</b>——额度耗尽/模型下线常按供应商计量，
         *       同供应商的另一个模型往往一起挂，所以兜底必须能指向异供应商；</li>
         *   <li>为此它自带 base-url / api-key（缺省时回落主模型），并能单独设 max-tokens
         *       （异族模型的上限与主模型常不同，沿用会直接 400）。</li>
         * </ul>
         * 17 个场景各配一条链既繁琐又几乎总是填同样的值，维护成本远高于收益。
         * 留空（model 为空）= 不启用兜底，行为与引入前一致。
         */
        private ChatModel fallbackModel;


        @Data
        public static class AiApi {
            private String baseUrl;
            private String apiKey;
            // OpenAI 兼容补全路径（如智谱为 /chat/completions）；留空走 Spring AI 默认 /v1/chat/completions
            private String completionsPath;
            // Command Code Provider 零数据保留：true 时发送 x-cmd-zdr: 1；无 ZDR 上游时服务端明确失败，不静默降级
            private Boolean zeroDataRetention;
            /**
             * 接口协议（LlmProtocol.code）：openai（默认，缺省即升级前行为）/ anthropic / responses。
             * 协议属 API 层属性——同一地址族下全部场景共用；场景级/兜底模型的独立 baseUrl 仍走本协议
             */
            private String protocol;
        }

        @Data
        public static class EmbeddingApi {
            private String baseUrl;
            private String apiKey;
            // OpenAI 兼容 embeddings 路径（如智谱为 /embeddings）；留空走 Spring AI 默认 /v1/embeddings
            private String embeddingsPath;
            private String model;
            /**
             * 单次请求的**输入条数**上限（2026-09-22 新增）：不同供应商限制不同，
             * 实测 {@code text-embedding-v3} 上限为 10，一次多发直接 400
             * （{@code batch size is invalid, it should not be larger than 10}）并终止作业。
             * 留空走 {@code SpringAiEmbeddingGateway.DEFAULT_EMBED_BATCH_SIZE}。
             */
            private Integer batchSize;
            /**
             * 输出向量维度（2026-09-25 新增）：显式钉死，不依赖供应商默认值——
             * DashScope text-embedding-v4 实测默认 1024 维且支持该参数（2048/768 亦可），
             * 但默认值若被供应商调整，Qdrant 集合维度会静默不匹配。留空则不传（走供应商默认）。
             * 注意：**维度相同不代表向量空间兼容**，换模型仍需重嵌。
             */
            private Integer dimensions;
        }

        @Data
        public static class ChatModel {
            private String model;
            // 最大输出 token 数（单次生成上限）
            private Long maxTokens;
            // 采样温度：越高越有创造性、越随机，越低越稳定、确定（0~2）。
            // null=不干预，走供应商默认（DashScope 默认约 1.0，对写作偏随机，建议显式配置）
            private Double temperature;
            // 是否开启思考模式（DashScope 等供应商特有）；null=不干预走供应商默认，false=注入 enable_thinking=false 关闭思考
            private Boolean enableThinking;
            // 场景级 API 覆盖（第二模型族）：非空时优先于 module 级 ai-api；留空则沿用 module 级 base-url/api-key。
            // 仅 scene-models 条目可配（chapter-judge 指向异供应商如 DeepSeek 时使用）
            private String baseUrl;
            private String apiKey;
            // 场景级补全路径覆盖：智谱 GLM 等供应商路径为 /chat/completions（base-url 已含 /v4），
            // 留空沿用 module 级 completionsPath
            private String completionsPath;
        }
    }

    @Data
    public static class Constraints {
        /** 总开关：true 时强制全书总章数上限（maxChapterCount），达到后不再生成 */
        private Boolean enforceChapterLimit;
        /** 全书总章数上限（达到后视为完结，续写将被拒绝） */
        private Integer maxChapterCount;
    }

}
