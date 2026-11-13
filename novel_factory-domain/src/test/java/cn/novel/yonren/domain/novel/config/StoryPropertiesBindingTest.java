package cn.novel.yonren.domain.novel.config;

import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 scene-models（Map<String,ChatModel>）+ unified-model-enabled 能被 Spring 正确绑定。
 * 直接加载真实 novel-generation.yml，走 @ConfigurationProperties 的完整绑定链路。
 */
@EnableConfigurationProperties(StoryProperties.class)
class StoryPropertiesBindingTest {

    @Test
    void defaultReferenceSelectorDoesNotRequireASeparateEmbeddingApiKey() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("novel-gen",
                new FileSystemResource("../novel_factory-app/src/main/resources/novel-generation.yml"));
        Binder binder = new Binder(ConfigurationPropertySources.from(sources));

        ReferenceSelectorProperties selector = binder.bind(
                        "story.prompt.reference-selector", ReferenceSelectorProperties.class)
                .orElseThrow(() -> new AssertionError("story.prompt.reference-selector 绑定失败"));

        // v0.2 起默认 vector：内置向量库成为标配，语义检索全量可用（仅 ChatGPT 订阅授权等
        // 无 embedding 场景才需要用户在设置页把检索方式切到 llm——运行时覆盖开关承接）
        assertEquals("vector", selector.getMode(),
                "默认应为向量语义检索（内置向量库 v0.2 起成为标配）；无嵌入设施的用户经设置页切 llm");
    }

    @Test
    void bindsSceneModelsFromRealYaml() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("novel-gen",
                new FileSystemResource("../novel_factory-app/src/main/resources/novel-generation.yml"));

        Binder binder = new Binder(ConfigurationPropertySources.from(sources));
        StoryVO.Module module = binder.bind("story.module", StoryVO.Module.class)
                .orElseThrow(() -> new AssertionError("story.module 绑定失败"));

        // 总开关
        assertFalse(module.getUnifiedModelEnabled(), "unified-model-enabled 应为 false");

        // scene-models 应绑定成功
        Map<String, StoryVO.Module.ChatModel> sceneModels = module.getSceneModels();
        assertNotNull(sceneModels, "scene-models 不应为 null");
        assertEquals(17, sceneModels.size(),
                "应有 17 个场景配置（16 个既有 + 新增 setting-draft 设定集草稿）");

        // 结构校验：每个场景模型名必须落在可调用模型集合内，提前拦截拼错调用名导致的运行期 404。
        // 具体用哪个模型允许随时手调，不在断言范围内。
        // 带日期后缀的快照档与无后缀别名都有效，一并收录以兼容固定版本配置。
        var callable = java.util.Set.of("deepseek-v4-pro", "deepseek-v4-flash", "deepseek-v4.1-flash",
                "qwen3.7-plus", "qwen3.7-plus-2026-05-26", "qwen3.7-max-2026-05-20",
                "qwen3.7-max-2026-06-08",
                // 兼容 chapter-plan 的快照模型名。
                "qwen3.7-max-2026-05-17",
                // preview 档同样允许用于 audit / audit-verify；白名单只校验可调用性，不评价产出质量。
                "qwen3.7-max-preview",
                // 大小写两种写法都保留，兼容运行时覆盖及供应商规范名；供应商侧不区分大小写。
                "DeepSeek-V4-Pro", "DeepSeek-V4-Flash",
                "qwen-3.7-plus",
                "qwen3.8-flash", "qwen3.8-max", "kimi-k3", "glm-5.2",
                // 兼容 chapter-plan / chapter-rewrite 的模型配置。
                "qwen3.8-27b",
                // 无后缀名与快照档都有效。
                "qwen3.7-max",
                // stage-blueprint 快照档用于固定版本，避免上游静默换权重；无后缀别名也可用。
                "qwen3.8-max-0902",
                // 兼容不同供应商的命名空间模型名，白名单不限制当前供应商选择。
                "deepseek/deepseek-v4.1-flash", "zai-org/GLM-5.3", "google/gemini-3.8-flash",
                "Qwen/Qwen3.8-Max",
        // glm-5.3 的思考在独立 reasoning_content 字段；不同供应商的同名模型需分别配置。
       // 强制思考模型的 enable_thinking 被 4xx 拒绝时，网关拦截器会自愈去除。
        // kimi-k3 强制思考且温度必须为 1.0；其他 temperature 会被 400 拒绝，
        // 场景配置必须显式配 temperature: 1.0。
        "glm-5.3");
        for (Map.Entry<String, StoryVO.Module.ChatModel> entry : sceneModels.entrySet()) {
            StoryVO.Module.ChatModel model = entry.getValue();
            assertTrue(model != null && callable.contains(model.getModel()),
                    "场景 " + entry.getKey() + " 的模型名不在「实弹验证可调用」白名单："
                            + (model == null ? "null" : model.getModel())
                            + "。若这是刚换上的模型，请先实测一次（curl 打 chat/completions 看是否 200），"
                            + "然后把名字加进本测试的 callable 集合；若是拼错了就改 yml。");
        }

        // 抽查：节拍输出上限必须高于思考预算。低于约 8192 时，代理可能分配固定 10000
        // 思考预算并耗尽正文容量；上限 16384 只提供余量，不改变实际输出量。
        StoryVO.Module.ChatModel beats = sceneModels.get("chapter-beats");
        assertEquals(16384L, beats.getMaxTokens().longValue(), "节拍上限需高于思考量，避免烧穿后降级");

        // 抽查：正文温度 0.5（降温压套话随机变体）
        assertEquals(0.5, sceneModels.get("chapter-content").getTemperature(), 0.001, "正文温度应为 0.5");

        // 抽查：摘要温度 0.2（结构化记忆压缩）
        assertEquals(0.2, sceneModels.get("summary").getTemperature(), 0.001, "摘要温度应为 0.2");

        // 配置回归守卫：embedding-api 必须挂在 story.module 之下。
        // YAML 缩进错误可能把 embedding-api 归到 run-plan 等兄弟块，绑定层必须拦住。
        // 否则 module.embeddingApi 会为 null，生成期才会暴露未配置嵌入服务的问题。
        assertNotNull(module.getEmbeddingApi(), "story.module.embedding-api 未绑定成功"
                + "（检查 yml 缩进：它必须位于 story.module 之下，而不是 run-plan 或其他块）");
        // 模型名允许随时手调；守卫目标是绑定成功，而非绑定到某个具体模型。
        assertTrue(module.getEmbeddingApi().getModel() != null && !module.getEmbeddingApi().getModel().isBlank(),
                "embedding 模型名不应为空");
        assertNotNull(module.getEmbeddingApi().getBaseUrl(), "embedding base-url 不应为空");
        assertNotNull(module.getEmbeddingApi().getApiKey(), "embedding api-key 不应为空");
        // 维度必须显式钉死：不依赖供应商默认值，供应商调整默认维度时不会炸集合
        assertEquals(1024, module.getEmbeddingApi().getDimensions(), "embedding dimensions 应为 1024");

        // 抽查：修订温度 0.4（低随机性，指令遵循优先）
        // 注：ZDR 断言已随 Command Code 回退一并移除——DashScope 不发送 x-cmd-zdr 头
        assertEquals(0.4, sceneModels.get("revise").getTemperature(), 0.001, "修订温度应为 0.4");

        // 全局兜底模型（替代按场景 model-fallbacks 表）：绑定失败不会报错，
        // 只会静默不启用兜底——"兜底静默失效"恰恰是挂机时最难发现的那类故障
        var fallback = module.getFallbackModel();
        assertNotNull(fallback, "story.module.fallback-model 未绑定成功（检查 yml 缩进）");
        assertTrue(fallback.getModel() != null && !fallback.getModel().isBlank(),
                "兜底模型名不应为空");
        // 兜底必须自带输出上限：异族模型的上限与主模型常不同，沿用会直接 400
        assertNotNull(fallback.getMaxTokens(), "兜底模型应显式配置 max-tokens");
    }
}
