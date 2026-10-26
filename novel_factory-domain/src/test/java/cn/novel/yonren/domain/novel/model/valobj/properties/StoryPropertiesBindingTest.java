package cn.novel.yonren.domain.novel.model.valobj.properties;

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

        // 结构校验：每个场景模型名必须落在"本账号实弹验证可调用"集合内——
        // 拼错调用名（如把 deepseek-v4-flash 写成 deepseek-flash）会在运行期 404，这里提前拦截。
        // 具体用哪个模型允许随时手调，不在断言范围内。
        // 带日期后缀的快照档（qwen3.7-max-/ qwen3.7-plus-）已确认可用且在
        // 免费额度内，故一并收录；它们的无后缀名与快照名都有效，但 yml 用的是快照档。
        // qwen3.7-max-为 chapter-plan 的现行快照档：三章实测中 chapter-plan
        // 三次调用全部成功，属"实弹验证可调用"（此前白名单停留在 05-20 档，属过期而非拼错）
        var callable = java.util.Set.of("deepseek-v4-pro", "deepseek-v4-flash", "deepseek-v4.1-flash",
                "qwen3.7-plus", "qwen3.7-plus-2026-05-26", "qwen3.7-max-2026-05-20",
                "qwen3.7-max-2026-06-08",
                // chapter-plan 换用 05-17 快照档，job-44746101051367424
                // 第 16-20 章批次实测 4 次 chapter-plan 调用全部成功（0 失败），属实弹验证可调用。
                // 同日 glm-5.3 因 reasoning_effort 默认 max 吃满输出预算、正文只剩 1-2 字而弃用。
                "qwen3.7-max-2026-05-17",
                // audit / audit-verify 现行用档（preview 档），job-45441319799103488 +
                // job-45456511509471232 两批 audit+audit-verify 共 32 次调用全部成功（0 失败），
                // 且实际抓出 ch9 禁泄泄露——属实弹验证可调用。
                // 注意：同批 chapter-plan / chapter-rewrite 虽调用成功，但产出系统性偏薄（计划 3 事件/章、
                // 挑战者稿全线比原稿短），属**质量淘汰**，已回退 05-17 档——白名单只拦"调不通用"，不拦"用不好"。
                "qwen3.7-max-preview",
                // 模型名切换为当前供应商的大写规范名，36-40 章批次
                //（job-45753881946234880）全场景实弹调用成功——stage-blueprint/summary/
                // paragraph-audit/ledger-adjudicate 全部 0 失败；qwen-3.7-plus 在 31-35 批
                //（job-45730329780035584）rewrite/audit/patch 共 13 次调用成功（但其 rewrite
                // 输出 3 次解析失败属质量问题，chapter-rewrite 已改配 qwen3.8-max）。
                // 大小写两种写法都保留：yml 现已统一小写，但运行时覆盖/历史配置里仍可能出现
                // 供应商规范大写名（同一模型，供应商侧不区分大小写）
                "DeepSeek-V4-Pro", "DeepSeek-V4-Flash",
                "qwen-3.7-plus",
                "qwen3.8-flash", "qwen3.8-max", "kimi-k3", "glm-5.2",
                // chapter-plan / chapter-rewrite 现行用档，已实测可调用
                //（此前白名单未同步 → 测试长期红，进而把 infrastructure 的测试整段 SKIPPED 掉）
                "qwen3.8-27b",
                // 再补：chapter-plan 换成它，已实测可调用（无后缀名与快照档都有效）
                "qwen3.7-max",
                // stage-blueprint 换用 0902 快照档，DashScope 实测 0.7s 正常返回
                //（快照档与无后缀别名都可用；用快照是为了固定版本，避免上游静默换权重）
                "qwen3.8-max-0902",
                // Command Code Provider 实弹验证通过的模型。**当前配置已回退到
                // DashScope 免费档，不再使用这些付费档模型**，此处保留白名单以免日后切回时又要重新实测：
                //   deepseek/deepseek-v4.1-flash  11.7k 字提示词 18.6s / 1915 字 / 推理仅 16%
                //   Qwen/Qwen3.8-Max              同提示词 37.7s 返回合法 JSON（推理 82%）
                //   google/gemini-3.8-flash        同提示词 10.9s（最快）
                //   zai-org/GLM-5.3                大输出场景 12.9 分钟后 524，**不可用于规划类**
                //   z-ai/glm-5.3-flash 输出 0 字；z-ai/glm-5.3-flashx 与 Qwen/Qwen3.8-Flash 为 422
                "deepseek/deepseek-v4.1-flash", "zai-org/GLM-5.3", "google/gemini-3.8-flash",
                "Qwen/Qwen3.8-Max",
        // glm-5.3（DashScope 无后缀名）实弹验证 200——最小请求秒回、content 干净、
       // 思考在独立 reasoning_content 字段。与 zai-org/GLM-5.3（OpenRouter 通道，大输出 524）区分；
       // 强制思考模型，enable_thinking 注入被 4xx 拒时网关已自愈去除（SpringAiLlmGateway 拦截器）
        // （kimi-k3 场景实弹）：强制思考 + 温度钉死 1.0——任何非 1.0 的
        // temperature 都会被 400 拒绝（InternalError.Algo.InvalidParameter），
        // kimi-k3 场景必须显式配 temperature: 1.0
        "glm-5.3");
        for (Map.Entry<String, StoryVO.Module.ChatModel> entry : sceneModels.entrySet()) {
            StoryVO.Module.ChatModel model = entry.getValue();
            assertTrue(model != null && callable.contains(model.getModel()),
                    "场景 " + entry.getKey() + " 的模型名不在「实弹验证可调用」白名单："
                            + (model == null ? "null" : model.getModel())
                            + "。若这是刚换上的模型，请先实测一次（curl 打 chat/completions 看是否 200），"
                            + "然后把名字加进本测试的 callable 集合；若是拼错了就改 yml。");
        }

        // 抽查：节拍上限必须高于思考量。原为 2048（"小输出任务"），但 2026-10-26 实测：上限低于
        // 约 8192 时代理会给固定 10000 预算，思考吃光后正文为 0（completionTokens=7188/2048），
        // 每次都靠降级救回。上限只是天花板、不改变实际输出量，故抬到 16384。
        StoryVO.Module.ChatModel beats = sceneModels.get("chapter-beats");
        assertEquals(16384L, beats.getMaxTokens().longValue(), "节拍上限需高于思考量，避免烧穿后降级");

        // 抽查：正文温度 0.5（降温压套话随机变体）
        assertEquals(0.5, sceneModels.get("chapter-content").getTemperature(), 0.001, "正文温度应为 0.5");

        // 抽查：摘要温度 0.2（结构化记忆压缩）
        assertEquals(0.2, sceneModels.get("summary").getTemperature(), 0.001, "摘要温度应为 0.2");

        // 配置回归守卫：embedding-api 必须挂在 story.module 之下。
        // 此前插入 run-plan 块时把缩进搞错，embedding-api 被 YAML 吞进 run-plan，
        // module.embeddingApi 变成 null，故事记忆向量检索在生成期才爆（"未配置 embedding-api"）——
        // 这类缩进错误不报错、只在运行期以最难排查的方式现形，所以必须在绑定层拦住
        assertNotNull(module.getEmbeddingApi(), "story.module.embedding-api 未绑定成功"
                + "（检查 yml 缩进：它必须位于 story.module 之下，而不是 run-plan 或其他块）");
        // 模型名允许随时手调（本断言曾写死 embedding-3，换 qwen3.7-text-embedding-flash 即红）——
        // 守卫的目标是"绑定成功"，不是"绑定到哪个具体模型"
        assertTrue(module.getEmbeddingApi().getModel() != null && !module.getEmbeddingApi().getModel().isBlank(),
                "embedding 模型名不应为空");
        assertNotNull(module.getEmbeddingApi().getBaseUrl(), "embedding base-url 不应为空");
        assertNotNull(module.getEmbeddingApi().getApiKey(), "embedding api-key 不应为空");
        // 维度必须显式钉死：不依赖供应商默认值，供应商调整默认维度时不会炸集合
        assertEquals(1024, module.getEmbeddingApi().getDimensions(), "embedding dimensions 应为 1024");

        // 抽查：修订温度 0.4（低随机性，指令遵循优先）
        // 注：ZDR 断言已随 Command Code 回退一并移除——DashScope 不发送 x-cmd-zdr 头
        assertEquals(0.4, sceneModels.get("revise").getTemperature(), 0.001, "修订温度应为 0.4");

        // 全局兜底模型（2026-10-23，替代按场景 model-fallbacks 表）：绑定失败不会报错，
        // 只会静默不启用兜底——"兜底静默失效"恰恰是挂机时最难发现的那类故障
        var fallback = module.getFallbackModel();
        assertNotNull(fallback, "story.module.fallback-model 未绑定成功（检查 yml 缩进）");
        assertTrue(fallback.getModel() != null && !fallback.getModel().isBlank(),
                "兜底模型名不应为空");
        // 兜底必须自带输出上限：异族模型的上限与主模型常不同，沿用会直接 400
        assertNotNull(fallback.getMaxTokens(), "兜底模型应显式配置 max-tokens");
    }
}
