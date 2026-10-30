package cn.novel.yonren.domain.novel.service.armory;

import cn.novel.yonren.api.dto.LlmConfigDTO;
import cn.novel.yonren.api.dto.LlmConfigSaveRequestDTO;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.types.enums.ModelScene;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中转站（供应商注册表）测试：全量替换与密钥保留语义、全局引用校验与悬空收敛、
 * applyApi 字段优先级（中转站 > 手动覆盖 > yml 静态）
 */
class LlmRuntimeConfigProviderTest {

    @TempDir
    Path tmp;

    private static LlmConfigSaveRequestDTO.ProviderSave provider(String baseUrl, String apiKey) {
        LlmConfigSaveRequestDTO.ProviderSave p = new LlmConfigSaveRequestDTO.ProviderSave();
        p.setName("测试中转");
        p.setBaseUrl(baseUrl);
        p.setApiKey(apiKey);
        return p;
    }

    private static LlmConfigSaveRequestDTO saveWith(Map<String, LlmConfigSaveRequestDTO.ProviderSave> providers,
                                                     String providerId) {
        LlmConfigSaveRequestDTO request = new LlmConfigSaveRequestDTO();
        request.setProviders(providers);
        request.setProviderId(providerId);
        return request;
    }

    private StoryVO.Module staticModule() {
        StoryVO.Module.AiApi aiApi = new StoryVO.Module.AiApi();
        aiApi.setBaseUrl("https://static.example.com/compatible-mode");
        aiApi.setApiKey("static-key");
        StoryVO.Module module = new StoryVO.Module();
        module.setAiApi(aiApi);
        module.setChatModel(new StoryVO.Module.ChatModel());
        module.getChatModel().setModel("static-model");
        return module;
    }

    @Test
    void merge_providers_fullReplace_andApiKeyKeptOnNull() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        Map<String, LlmConfigSaveRequestDTO.ProviderSave> first = new LinkedHashMap<>();
        first.put("relay-a", provider("https://a.example.com", "sk-aaaa"));
        first.put("relay-b", provider("https://b.example.com/v1", "sk-bbbb"));
        config.save(saveWith(first, null));

        // 第二次全量替换只留 relay-a 且不带 apiKey：密钥按"null=保留"沿用旧值
        Map<String, LlmConfigSaveRequestDTO.ProviderSave> second = new LinkedHashMap<>();
        LlmConfigSaveRequestDTO.ProviderSave kept = provider("https://a2.example.com", null);
        second.put("relay-a", kept);
        LlmConfigDTO view = config.save(saveWith(second, null));

        assertEquals(1, view.getProviders().size());
        assertEquals("https://a2.example.com", view.getProviders().get(0).getBaseUrl());
        assertTrue(view.getProviders().get(0).isHasKey(), "apiKey=null 应保留旧密钥");
    }

    @Test
    void merge_dropsInvalidEntries() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        Map<String, LlmConfigSaveRequestDTO.ProviderSave> bad = new LinkedHashMap<>();
        bad.put("Bad_Id!", provider("https://x.example.com", null));   // id 字符不合法
        bad.put("no-url", provider("  ", null));                        // 缺 Base URL
        LlmConfigDTO view = config.save(saveWith(bad, null));

        assertNull(view.getProviders(), "全部条目被丢弃时注册表为空");
    }

    @Test
    void providerId_unknownReference_rejected() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        Map<String, LlmConfigSaveRequestDTO.ProviderSave> providers = new LinkedHashMap<>();
        providers.put("relay-a", provider("https://a.example.com", null));
        LlmConfigSaveRequestDTO request = saveWith(providers, "relay-missing");

        assertThrows(IllegalArgumentException.class, () -> config.save(request),
                "引用不存在的 id 必须拒绝，静默清除会让用户以为已选中");
    }

    @Test
    void resetProviders_clearsDanglingReference() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        Map<String, LlmConfigSaveRequestDTO.ProviderSave> providers = new LinkedHashMap<>();
        providers.put("relay-a", provider("https://a.example.com", "sk-aa"));
        config.save(saveWith(providers, "relay-a"));

        LlmConfigSaveRequestDTO reset = new LlmConfigSaveRequestDTO();
        reset.setResetProviders(true);
        LlmConfigDTO view = config.save(reset);

        assertNull(view.getProviders());
        assertNull(view.getProviderId(), "注册表清空后悬空引用收敛为手动模式");
    }

    @Test
    void applyApi_providerWinsOverManualAndStatic() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        LlmConfigSaveRequestDTO request = new LlmConfigSaveRequestDTO();
        request.setBaseUrl("https://manual.example.com/v1");
        request.setApiKey("manual-key");
        Map<String, LlmConfigSaveRequestDTO.ProviderSave> providers = new LinkedHashMap<>();
        providers.put("relay-a", provider("https://relay.example.com", "sk-relay"));
        request.setProviders(providers);
        request.setProviderId("relay-a");
        config.save(request);

        StoryVO.Module effective = config.applyApi(staticModule());
        assertEquals("https://relay.example.com", effective.getAiApi().getBaseUrl(), "中转站地址优先");
        assertEquals("sk-relay", effective.getAiApi().getApiKey(), "中转站密钥优先");
    }

    @Test
    void applyApi_providerFieldFallsBackToManual() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        LlmConfigSaveRequestDTO request = new LlmConfigSaveRequestDTO();
        request.setApiKey("manual-key");   // 中转站没配密钥 → 手动字段补位
        Map<String, LlmConfigSaveRequestDTO.ProviderSave> providers = new LinkedHashMap<>();
        providers.put("relay-a", provider("https://relay.example.com", null));
        request.setProviders(providers);
        request.setProviderId("relay-a");
        config.save(request);

        StoryVO.Module effective = config.applyApi(staticModule());
        assertEquals("https://relay.example.com", effective.getAiApi().getBaseUrl());
        assertEquals("manual-key", effective.getAiApi().getApiKey(), "中转站密钥缺位时手动覆盖补上");
    }

    @Test
    void applyApi_manualMode_keepsExistingBehavior() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        LlmConfigSaveRequestDTO request = new LlmConfigSaveRequestDTO();
        request.setBaseUrl("https://manual.example.com");
        Map<String, LlmConfigSaveRequestDTO.ProviderSave> providers = new LinkedHashMap<>();
        providers.put("relay-a", provider("https://relay.example.com", "sk-relay"));
        request.setProviders(providers);   // 注册表存在但未引用：手动模式
        config.save(request);

        LlmConfigDTO view = config.save(new LlmConfigSaveRequestDTO());  // 空保存=保持现状
        assertNull(view.getProviderId());

        StoryVO.Module effective = config.applyApi(staticModule());
        assertEquals("https://manual.example.com", effective.getAiApi().getBaseUrl());
        assertEquals("static-key", effective.getAiApi().getApiKey(), "无任何密钥覆盖时沿用 yml 静态");
    }

    @Test
    void view_providerKeyMasked() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        Map<String, LlmConfigSaveRequestDTO.ProviderSave> providers = new LinkedHashMap<>();
        providers.put("relay-a", provider("https://relay.example.com", "sk-relay-secret-key"));
        LlmConfigDTO view = config.save(saveWith(providers, "relay-a"));

        assertEquals(1, view.getProviders().size());
        assertEquals("relay-a", view.getProviderId());
        assertTrue(view.getProviders().get(0).isHasKey());
        assertFalse(String.valueOf(view.getProviders().get(0).getApiKeyMasked()).contains("secret"),
                "密钥掩码不得泄露中段");
    }

    @Test
    void cacheProviderModels_persistedAndKeptAcrossRegistrySave() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        Map<String, LlmConfigSaveRequestDTO.ProviderSave> providers = new LinkedHashMap<>();
        providers.put("relay-a", provider("https://relay.example.com", null));
        config.save(saveWith(providers, null));

        config.cacheProviderModels("relay-a", java.util.List.of("glm-5.2", "qwen3.8-max"));
        assertEquals(java.util.List.of("glm-5.2", "qwen3.8-max"),
                config.view().getProviders().get(0).getModels(), "拉取后缓存立即可见");

        // 再保存注册表（前端不带 models）：模型缓存必须保留，否则标注与重启下拉都丢
        Map<String, LlmConfigSaveRequestDTO.ProviderSave> again = new LinkedHashMap<>();
        LlmConfigSaveRequestDTO.ProviderSave updated = provider("https://relay2.example.com", "sk-new");
        again.put("relay-a", updated);
        LlmConfigDTO view = config.save(saveWith(again, null));

        assertEquals("https://relay2.example.com", view.getProviders().get(0).getBaseUrl());
        assertEquals(java.util.List.of("glm-5.2", "qwen3.8-max"),
                view.getProviders().get(0).getModels(), "保存注册表不得清掉模型缓存");

        // 重建实例（模拟重启）：缓存从磁盘恢复
        LlmRuntimeConfig reloaded = new LlmRuntimeConfig(tmp.resolve("override.json"));
        assertEquals(java.util.List.of("glm-5.2", "qwen3.8-max"),
                reloaded.view().getProviders().get(0).getModels(), "模型缓存随覆盖文件持久化");

        // 未知 id 静默忽略（防御：不得注册出幽灵条目）
        config.cacheProviderModels("ghost", java.util.List.of("m"));
        assertEquals(1, config.view().getProviders().size());
    }

    // ===== 接口协议（protocol）=====

    private static LlmConfigSaveRequestDTO.ProviderSave providerWithProtocol(
            String baseUrl, String apiKey, String protocol) {
        LlmConfigSaveRequestDTO.ProviderSave p = provider(baseUrl, apiKey);
        p.setProtocol(protocol);
        return p;
    }

    @Test
    void providers_protocolRoundTrip_andUnknownRejected() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        Map<String, LlmConfigSaveRequestDTO.ProviderSave> providers = new LinkedHashMap<>();
        providers.put("claude-relay", providerWithProtocol("https://c.example.com", "sk-c", "anthropic"));
        providers.put("gpt-relay", providerWithProtocol("https://g.example.com", "sk-g", "responses"));
        providers.put("plain", providerWithProtocol("https://p.example.com", "sk-p", null));
        LlmConfigDTO view = config.save(saveWith(providers, null));

        assertEquals("anthropic", view.getProviders().get(0).getProtocol());
        assertEquals("responses", view.getProviders().get(1).getProtocol());
        assertNull(view.getProviders().get(2).getProtocol(), "未填协议 = null（网关按 openai 处理）");

        // 未知协议值丢弃该条而非炸整次保存：注册表是整表提交（一条坏数据不得殃及全表，
        // 否则用户其他字段的修改一起丢失）；好条目必须照常落库
        Map<String, LlmConfigSaveRequestDTO.ProviderSave> mixed = new LinkedHashMap<>();
        mixed.put("good", providerWithProtocol("https://ok.example.com", null, "anthropic"));
        mixed.put("bad", providerWithProtocol("https://x.example.com", null, "websocket"));
        LlmConfigDTO mixedView = config.save(saveWith(mixed, null));
        assertEquals(1, mixedView.getProviders().size(), "未知协议条目被丢弃");
        assertEquals("anthropic", mixedView.getProviders().get(0).getProtocol(), "合法条目不受坏数据影响");
    }

    @Test
    void applyApi_protocolResolution_providerOverManualOverStatic() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        LlmConfigSaveRequestDTO request = new LlmConfigSaveRequestDTO();
        request.setBaseUrl("https://manual.example.com");
        request.setProtocol("responses");
        Map<String, LlmConfigSaveRequestDTO.ProviderSave> providers = new LinkedHashMap<>();
        providers.put("claude-relay", providerWithProtocol("https://c.example.com", "sk-c", "anthropic"));
        request.setProviders(providers);
        request.setProviderId("claude-relay");
        config.save(request);

        StoryVO.Module module = staticModule();
        module.getAiApi().setProtocol("openai");
        StoryVO.Module effective = config.applyApi(module);
        assertEquals("anthropic", effective.getAiApi().getProtocol(), "选中中转站：协议跟着站走");

        // 切回手动模式：手动覆盖（responses）压过 yml 静态（openai）
        LlmConfigSaveRequestDTO backToManual = new LlmConfigSaveRequestDTO();
        backToManual.setProviderId("");
        config.save(backToManual);
        assertEquals("responses", config.applyApi(module).getAiApi().getProtocol());

        // 清除手动协议覆盖：回退 yml 静态
        LlmConfigSaveRequestDTO clear = new LlmConfigSaveRequestDTO();
        clear.setProtocol("");
        config.save(clear);
        assertEquals("openai", config.applyApi(module).getAiApi().getProtocol());
    }

    @Test
    void applyApi_protocolOnlyOverride_appliesWithoutAddress() {
        // 只有协议覆盖（无地址密钥覆盖）也必须生效：yml 地址不动、只换协议是合法用法
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        LlmConfigSaveRequestDTO request = new LlmConfigSaveRequestDTO();
        request.setProtocol("anthropic");
        config.save(request);

        StoryVO.Module module = staticModule();
        module.getAiApi().setProtocol("openai");
        StoryVO.Module effective = config.applyApi(module);
        assertEquals("anthropic", effective.getAiApi().getProtocol(), "协议覆盖独立于地址覆盖生效");
        assertEquals("https://static.example.com/compatible-mode", effective.getAiApi().getBaseUrl(),
                "无地址覆盖时沿用 yml 静态地址");
    }

    @Test
    void save_manualProtocol_mergeSemantics() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        LlmConfigSaveRequestDTO set = new LlmConfigSaveRequestDTO();
        set.setProtocol("anthropic");
        assertEquals("anthropic", config.save(set).getProtocol());

        // 其他保存请求不带 protocol：不误伤已存协议
        LlmConfigSaveRequestDTO untouched = new LlmConfigSaveRequestDTO();
        untouched.setModel("some-model");
        assertEquals("anthropic", config.save(untouched).getProtocol(), "null=保持现状");

        // 空串=显式清除
        LlmConfigSaveRequestDTO clear = new LlmConfigSaveRequestDTO();
        clear.setProtocol("");
        assertNull(config.save(clear).getProtocol());
    }

    // ===== 场景级 / 兜底级中转站引用（选站即接管地址密钥协议）=====

    @Test
    void sceneProvider_takesOverBaseUrlAndApiKey() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        Map<String, LlmConfigSaveRequestDTO.ProviderSave> providers = new LinkedHashMap<>();
        providers.put("main-relay", provider("https://main.example.com", "sk-main"));
        providers.put("judge-relay", provider("https://judge.example.com", "sk-judge"));
        LlmConfigSaveRequestDTO request = saveWith(providers, "main-relay");
        LlmConfigSaveRequestDTO.SceneOverride judge = new LlmConfigSaveRequestDTO.SceneOverride();
        judge.setModel("claude-sonnet");
        judge.setProviderId("judge-relay");
        request.setScenes(Map.of("chapter-judge", judge));
        config.save(request);

        StoryVO.Module.ChatModel sceneModel = config.applyModel(ModelScene.CHAPTER_JUDGE, staticChatModel());
        assertEquals("claude-sonnet", sceneModel.getModel());
        assertEquals("https://judge.example.com", sceneModel.getBaseUrl(), "场景选中转站后地址由站提供");
        assertEquals("sk-judge", sceneModel.getApiKey(), "密钥也由站提供，无需手填");

        // 未配站的场景：applyModel(scene,…) 只叠加场景层，全局地址由网关侧 applyApi 提供，
        // 故这里断言"场景层没有额外地址"（回退全局）
        StoryVO.Module.ChatModel planModel = config.applyModel(ModelScene.CHAPTER_PLAN, staticChatModel());
        assertNull(planModel.getBaseUrl(), "未配站的场景不注入场景级地址，回退全局 aiApi");
    }

    @Test
    void sceneProvider_viewExposesOverrideProviderId() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        Map<String, LlmConfigSaveRequestDTO.ProviderSave> providers = new LinkedHashMap<>();
        providers.put("relay-a", provider("https://a.example.com", "sk-a"));
        LlmConfigSaveRequestDTO request = saveWith(providers, null);
        LlmConfigSaveRequestDTO.SceneOverride judge = new LlmConfigSaveRequestDTO.SceneOverride();
        judge.setProviderId("relay-a");
        request.setScenes(Map.of("chapter-judge", judge));
        config.save(request);

        // 场景矩阵行由 view(StoryProperties) 产出，需带静态配置才有 rows
        StoryVO.Module.ChatModel judgeStatic = staticChatModel();
        StoryVO.Module module = new StoryVO.Module();
        module.setChatModel(staticChatModel());
        module.setSceneModels(Map.of("chapter-judge", judgeStatic));
        cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties props =
                new cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties();
        props.setModule(module);

        LlmConfigDTO.SceneView row = config.view(props).getScenes().stream()
                .filter(s -> "chapter-judge".equals(s.getKey())).findFirst().orElseThrow();
        assertEquals("relay-a", row.getOverrideProviderId(), "前端据此回显选中的站");
    }

    @Test
    void fallbackProvider_takesOverAddress() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        Map<String, LlmConfigSaveRequestDTO.ProviderSave> providers = new LinkedHashMap<>();
        providers.put("fb-relay", provider("https://fb.example.com", "sk-fb"));
        LlmConfigSaveRequestDTO request = saveWith(providers, null);
        LlmConfigSaveRequestDTO.SceneOverride fallback = new LlmConfigSaveRequestDTO.SceneOverride();
        fallback.setModel("backup-model");
        fallback.setProviderId("fb-relay");
        request.setFallback(fallback);
        config.save(request);

        StoryVO.Module module = config.applyFallback(staticModule());
        assertEquals("https://fb.example.com", module.getFallbackModel().getBaseUrl(), "兜底选中转站即换供应商");
        assertEquals("sk-fb", module.getFallbackModel().getApiKey());
    }

    @Test
    void sceneProvider_respectsSceneApiOffSwitch() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        Map<String, LlmConfigSaveRequestDTO.ProviderSave> providers = new LinkedHashMap<>();
        providers.put("relay-a", provider("https://a.example.com", "sk-a"));
        LlmConfigSaveRequestDTO request = saveWith(providers, null);
        request.setSceneApiEnabled(false);   // 总闸关闭：全部强制走全局
        LlmConfigSaveRequestDTO.SceneOverride judge = new LlmConfigSaveRequestDTO.SceneOverride();
        judge.setProviderId("relay-a");
        request.setScenes(Map.of("chapter-judge", judge));
        config.save(request);

        StoryVO.Module.ChatModel sceneModel = config.applyModel(ModelScene.CHAPTER_JUDGE, staticChatModel());
        assertNull(sceneModel.getBaseUrl(), "总闸关闭时场景站引用不生效（回落全局 aiApi）");
        assertNull(sceneModel.getApiKey());
    }

    // ===== 回归：评审中实测到的三个缺陷（2026-10-27）=====

    /**
     * 回归 ①：旧版手动配置"只改模型"保存时，baseUrl 曾被静默清空
     * （apiKey 有 currentApiKeyOf 保护、baseUrl 没有），留下"密钥在、地址丢"的半残覆盖。
     */
    @Test
    void legacySceneOverride_keepBaseUrlWhenOnlyModelChanges() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        LlmConfigSaveRequestDTO.SceneOverride legacy = new LlmConfigSaveRequestDTO.SceneOverride();
        legacy.setModel("old-model");
        legacy.setBaseUrl("https://OLD.example.com");
        legacy.setApiKey("sk-OLD");
        LlmConfigSaveRequestDTO first = new LlmConfigSaveRequestDTO();
        first.setScenes(Map.of("chapter-judge", legacy));
        config.save(first);

        // 旧版前端只提交 model（地址密钥不回显）
        LlmConfigSaveRequestDTO.SceneOverride onlyModel = new LlmConfigSaveRequestDTO.SceneOverride();
        onlyModel.setModel("new-model");
        LlmConfigSaveRequestDTO second = new LlmConfigSaveRequestDTO();
        second.setScenes(Map.of("chapter-judge", onlyModel));
        config.save(second);

        LlmConfigDTO.SceneView row = sceneRow(config, "chapter-judge");
        assertEquals("new-model", row.getOverrideModel());
        assertEquals("https://OLD.example.com", row.getOverrideBaseUrl(), "只改模型不得清掉已配地址");
        assertTrue(row.isOverrideHasKey(), "密钥同样保留");
    }

    /**
     * 回归 ②：选中转站后，残留的旧手动字段曾在生效链路里反压站配置
     * （applyModel 赋值顺序错误），实测请求打到了 OLD 地址。
     */
    @Test
    void sceneProvider_winsOverLeftoverManualFields() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        Map<String, LlmConfigSaveRequestDTO.ProviderSave> providers = new LinkedHashMap<>();
        providers.put("relay", provider("https://RELAY.example.com", "sk-RELAY"));
        LlmConfigSaveRequestDTO request = saveWith(providers, null);
        LlmConfigSaveRequestDTO.SceneOverride pick = new LlmConfigSaveRequestDTO.SceneOverride();
        pick.setModel("judge-model");
        pick.setProviderId("relay");
        pick.setBaseUrl("https://OLD.example.com");   // 模拟残留旧手动字段
        pick.setApiKey("sk-OLD");
        request.setScenes(Map.of("chapter-judge", pick));
        config.save(request);

        StoryVO.Module.ChatModel out = config.applyModel(ModelScene.CHAPTER_JUDGE, staticChatModel());
        assertEquals("https://RELAY.example.com", out.getBaseUrl(), "站地址必须压过残留手动地址");
        assertEquals("sk-RELAY", out.getApiKey(), "站密钥必须压过残留手动密钥");
    }

    /**
     * 回归 ③：场景选中转站时协议必须随站切换——全局 OpenAI + 评审站 Anthropic，
     * 旧实现只搬地址密钥、协议固定继承全局，跨协议供应商必然 401。
     */
    @Test
    void sceneProvider_switchesProtocolWithProvider() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        Map<String, LlmConfigSaveRequestDTO.ProviderSave> providers = new LinkedHashMap<>();
        LlmConfigSaveRequestDTO.ProviderSave main = provider("https://main.example.com", "sk-main");
        main.setProtocol("openai");
        LlmConfigSaveRequestDTO.ProviderSave anthropic = provider("https://anth.example.com", "sk-anth");
        anthropic.setProtocol("anthropic");
        providers.put("main", main);
        providers.put("anthropic", anthropic);
        LlmConfigSaveRequestDTO request = saveWith(providers, "main");
        LlmConfigSaveRequestDTO.SceneOverride judge = new LlmConfigSaveRequestDTO.SceneOverride();
        judge.setModel("claude-x");
        judge.setProviderId("anthropic");
        request.setScenes(Map.of("chapter-judge", judge));
        config.save(request);

        StoryVO.Module.ChatModel judgeModel = config.applyModel(ModelScene.CHAPTER_JUDGE, staticChatModel());
        assertEquals("anthropic", judgeModel.getProtocol(), "评审场景随站切到 Anthropic 协议");

        // 未配站的场景不声明协议 → 网关继承全局
        StoryVO.Module.ChatModel planModel = config.applyModel(ModelScene.CHAPTER_PLAN, staticChatModel());
        assertNull(planModel.getProtocol(), "未配站场景不注入协议，由网关继承全局");
    }

    /** 回归 ③ 延伸：兜底选中转站时协议同样随站走 */
    @Test
    void fallbackProvider_switchesProtocol() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        Map<String, LlmConfigSaveRequestDTO.ProviderSave> providers = new LinkedHashMap<>();
        LlmConfigSaveRequestDTO.ProviderSave anthropic = provider("https://anth.example.com", "sk-anth");
        anthropic.setProtocol("anthropic");
        providers.put("anthropic", anthropic);
        LlmConfigSaveRequestDTO request = saveWith(providers, null);
        LlmConfigSaveRequestDTO.SceneOverride fallback = new LlmConfigSaveRequestDTO.SceneOverride();
        fallback.setModel("claude-backup");
        fallback.setProviderId("anthropic");
        request.setFallback(fallback);
        config.save(request);

        StoryVO.Module module = config.applyFallback(staticModule());
        assertEquals("anthropic", module.getFallbackModel().getProtocol(), "兜底协议随站切换");
        assertEquals("https://anth.example.com", module.getFallbackModel().getBaseUrl());
    }

    // ===== 第二轮评审新增的两个缺陷（2026-10-27）=====

    /**
     * 回归 ④：兜底与主模型同名但指向另一中转站时，旧守卫按"同名=无效"整条作废，
     * 把模型换成静态兜底却仍保留用户选的中转站地址——拼出"静态模型 + 异站供应商"的错配。
     * 同名不同端点应当放行。
     */
    @Test
    void fallbackSameNameDifferentProvider_notDropped() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        Map<String, LlmConfigSaveRequestDTO.ProviderSave> providers = new LinkedHashMap<>();
        LlmConfigSaveRequestDTO.ProviderSave anth = provider("https://anth.example.com", "sk-anth");
        anth.setProtocol("anthropic");
        providers.put("anth", anth);

        LlmConfigSaveRequestDTO request = saveWith(providers, null);
        request.setModel("same-model");          // 全局主模型
        LlmConfigSaveRequestDTO.SceneOverride fb = new LlmConfigSaveRequestDTO.SceneOverride();
        fb.setModel("same-model");               // 兜底同名，但换了中转站
        fb.setProviderId("anth");
        request.setFallback(fb);
        config.save(request);

        StoryVO.Module module = new StoryVO.Module();
        StoryVO.Module.ChatModel primary = new StoryVO.Module.ChatModel();
        primary.setModel("same-model");
        module.setChatModel(primary);
        StoryVO.Module.ChatModel staticFb = new StoryVO.Module.ChatModel();
        staticFb.setModel("different-static");
        staticFb.setBaseUrl("https://static-fb.example.com");
        module.setFallbackModel(staticFb);

        StoryVO.Module.ChatModel result = config.applyFallback(module).getFallbackModel();
        assertEquals("same-model", result.getModel(), "同名异站不是无效兜底，不得换成静态模型");
        assertEquals("https://anth.example.com", result.getBaseUrl(), "地址与模型必须同源");
        assertEquals("anthropic", result.getProtocol(), "协议同样来自所选站");
    }

    /** 回归 ④ 对照：同名且同端点（无站、无手动地址差异）仍按无效兜底处理 */
    @Test
    void fallbackSameNameSameEndpoint_stillDropped() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        LlmConfigSaveRequestDTO request = new LlmConfigSaveRequestDTO();
        request.setModel("same-model");
        LlmConfigSaveRequestDTO.SceneOverride fb = new LlmConfigSaveRequestDTO.SceneOverride();
        fb.setModel("same-model");
        request.setFallback(fb);
        config.save(request);

        StoryVO.Module module = new StoryVO.Module();
        StoryVO.Module.ChatModel primary = new StoryVO.Module.ChatModel();
        primary.setModel("same-model");
        module.setChatModel(primary);
        StoryVO.Module.ChatModel staticFb = new StoryVO.Module.ChatModel();
        staticFb.setModel("different-static");
        module.setFallbackModel(staticFb);

        assertEquals("different-static", config.applyFallback(module).getFallbackModel().getModel(),
                "同名同端点仍应回落静态兜底（避免降级链塌缩为单站）");
    }

    /**
     * 回归 ⑤：场景/兜底引用了不存在的中转站时必须拒绝保存。
     * 静默忽略会让界面显示"已选站"、实际走另一套地址——最难排查的一类配置错位。
     */
    @Test
    void sceneProvider_danglingReference_rejected() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        LlmConfigSaveRequestDTO request = new LlmConfigSaveRequestDTO();
        LlmConfigSaveRequestDTO.SceneOverride judge = new LlmConfigSaveRequestDTO.SceneOverride();
        judge.setModel("x");
        judge.setProviderId("ghost");
        request.setScenes(Map.of("chapter-judge", judge));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> config.save(request));
        assertTrue(ex.getMessage().contains("ghost"), "报错须点名不存在的站：" + ex.getMessage());
        assertNull(config.view().getProviderId(), "失败的保存不得留下半成品状态");
    }

    @Test
    void fallbackProvider_danglingReference_rejected() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        LlmConfigSaveRequestDTO request = new LlmConfigSaveRequestDTO();
        LlmConfigSaveRequestDTO.SceneOverride fb = new LlmConfigSaveRequestDTO.SceneOverride();
        fb.setModel("fb");
        fb.setProviderId("ghost");
        request.setFallback(fb);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> config.save(request));
        assertTrue(ex.getMessage().contains("兜底"), "报错须指明是兜底：" + ex.getMessage());
    }

    /** 回归 ⑤ 配套：删除站点后，带空 providerId 的场景覆盖能成功清掉引用（前端删除路径） */
    @Test
    void sceneProvider_clearedByEmptyString_afterProviderRemoved() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        Map<String, LlmConfigSaveRequestDTO.ProviderSave> providers = new LinkedHashMap<>();
        providers.put("relay", provider("https://relay.example.com", "sk-relay"));
        LlmConfigSaveRequestDTO first = saveWith(providers, "relay");
        LlmConfigSaveRequestDTO.SceneOverride judge = new LlmConfigSaveRequestDTO.SceneOverride();
        judge.setModel("judge-model");
        judge.setProviderId("relay");
        first.setScenes(Map.of("chapter-judge", judge));
        config.save(first);

        // 旧式 API 兼容：注册表去掉该条 + 场景覆盖只保留模型、providerId 置空
        LlmConfigSaveRequestDTO.SceneOverride cleared = new LlmConfigSaveRequestDTO.SceneOverride();
        cleared.setModel("judge-model");
        cleared.setProviderId("");
        LlmConfigSaveRequestDTO second = new LlmConfigSaveRequestDTO();
        second.setProviders(new LinkedHashMap<>());   // 空表 = 清空注册表
        second.setScenes(Map.of("chapter-judge", cleared));
        LlmConfigDTO view = config.save(second);

        assertNull(view.getProviders(), "注册表已清空");
        LlmConfigDTO.SceneView row = sceneRow(config, "chapter-judge");
        assertNull(row.getOverrideProviderId(), "场景引用已清除");
        assertEquals("judge-model", row.getOverrideModel(), "其余覆盖保留");
    }

    /**
     * 回归 ⑥：删除站点时只清该站引用，不能连带误清其它场景覆盖。
     * 旧前端把受影响行当整表提交，mergeScenes 的全量替换会把未出现的场景全部删掉。
     * 现在由后端按 detachProviderId 定点解除，绝不重放覆盖表。
     */
    @Test
    void detachProvider_preservesUnrelatedSceneAndFallbackOverrides() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        Map<String, LlmConfigSaveRequestDTO.ProviderSave> providers = new LinkedHashMap<>();
        providers.put("relay", provider("https://relay.example.com", "sk-relay"));
        LlmConfigSaveRequestDTO first = saveWith(providers, "relay");
        Map<String, LlmConfigSaveRequestDTO.SceneOverride> scenes = new LinkedHashMap<>();
        LlmConfigSaveRequestDTO.SceneOverride judge = new LlmConfigSaveRequestDTO.SceneOverride();
        judge.setModel("judge-model");
        judge.setMaxTokens(4096L);
        judge.setProviderId("relay");
        scenes.put("chapter-judge", judge);
        LlmConfigSaveRequestDTO.SceneOverride plan = new LlmConfigSaveRequestDTO.SceneOverride();
        plan.setModel("qwen3.8-max");
        plan.setMaxTokens(32768L);
        plan.setTemperature(1.0);
        scenes.put("chapter-plan", plan);
        LlmConfigSaveRequestDTO.SceneOverride audit = new LlmConfigSaveRequestDTO.SceneOverride();
        audit.setModel("qwen3.7-plus");
        audit.setBaseUrl("https://manual.example.com");
        audit.setApiKey("sk-manual");
        scenes.put("audit", audit);
        first.setScenes(scenes);
        LlmConfigSaveRequestDTO.SceneOverride fb = new LlmConfigSaveRequestDTO.SceneOverride();
        fb.setModel("fb-model");
        fb.setMaxTokens(8192L);
        fb.setProviderId("relay");
        first.setFallback(fb);
        config.save(first);

        // 删除站点：注册表剔除该条 + 定点解除引用（不提交 scenes/fallback 覆盖表）
        LlmConfigSaveRequestDTO second = new LlmConfigSaveRequestDTO();
        second.setProviders(new LinkedHashMap<>());
        second.setDetachProviderId("relay");
        config.save(second);

        LlmConfigDTO.SceneView judgeRow = sceneRow(config, "chapter-judge");
        assertNull(judgeRow.getOverrideProviderId());
        assertEquals("judge-model", judgeRow.getOverrideModel(), "被解绑场景其余字段保留");
        assertEquals(4096L, judgeRow.getOverrideMaxTokens());

        LlmConfigDTO.SceneView planRow = sceneRow(config, "chapter-plan");
        assertEquals("qwen3.8-max", planRow.getOverrideModel(), "未涉及场景模型不得被清掉");
        assertEquals(32768L, planRow.getOverrideMaxTokens(), "未涉及场景 Tokens 不得被清掉");
        assertEquals(1.0, planRow.getOverrideTemperature(), "未涉及场景温度不得被清掉");

        LlmConfigDTO.SceneView auditRow = sceneRow(config, "audit");
        assertEquals("https://manual.example.com", auditRow.getOverrideBaseUrl(), "旧手动场景地址仍保留");
        assertTrue(auditRow.isOverrideHasKey(), "旧手动场景密钥仍保留");

        StoryVO.Module module = config.applyFallback(staticModule());
        assertEquals("fb-model", module.getFallbackModel().getModel(), "兜底模型保留");
        assertEquals(8192L, module.getFallbackModel().getMaxTokens(), "兜底 Tokens 保留");
        assertEquals(null, module.getFallbackModel().getBaseUrl(), "兜底解除后回退静态/全局端点");
    }

    /** 回归 ⑥ 失败路径保护：删除站点校验失败时，不得修改 current 里的场景引用 */
    @Test
    void detachProvider_failureDoesNotMutatePreviousOverlay() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        Map<String, LlmConfigSaveRequestDTO.ProviderSave> providers = new LinkedHashMap<>();
        providers.put("relay", provider("https://relay.example.com", "sk-relay"));
        providers.put("other", provider("https://other.example.com", "sk-other"));
        LlmConfigSaveRequestDTO first = saveWith(providers, null);
        LlmConfigSaveRequestDTO.SceneOverride judge = new LlmConfigSaveRequestDTO.SceneOverride();
        judge.setProviderId("relay");
        first.setScenes(Map.of("chapter-judge", judge));
        config.save(first);

        // 解除 relay 的引用，但请求里另外给 audit 绑定不存在的站：校验必须失败，
        // 而且不能把 current 共享的旧 SceneOverlay 提前改掉
        Map<String, LlmConfigSaveRequestDTO.SceneOverride> scenes = new LinkedHashMap<>();
        LlmConfigSaveRequestDTO.SceneOverride judgeInput = new LlmConfigSaveRequestDTO.SceneOverride();
        judgeInput.setProviderId("relay");
        scenes.put("chapter-judge", judgeInput);
        LlmConfigSaveRequestDTO.SceneOverride badAudit = new LlmConfigSaveRequestDTO.SceneOverride();
        badAudit.setProviderId("ghost");
        scenes.put("audit", badAudit);
        LlmConfigSaveRequestDTO bad = new LlmConfigSaveRequestDTO();
        bad.setScenes(scenes);
        bad.setDetachProviderId("relay");
        assertThrows(IllegalArgumentException.class, () -> config.save(bad));

        LlmConfigDTO.SceneView row = sceneRow(config, "chapter-judge");
        assertEquals("relay", row.getOverrideProviderId(), "失败的保存不得污染原覆盖状态");
    }

    private LlmConfigDTO.SceneView sceneRow(LlmRuntimeConfig config, String sceneKey) {
        StoryVO.Module module = new StoryVO.Module();
        module.setChatModel(staticChatModel());
        module.setSceneModels(new LinkedHashMap<>());
        cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties props =
                new cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties();
        props.setModule(module);
        return config.view(props).getScenes().stream()
                .filter(s -> sceneKey.equals(s.getKey())).findFirst().orElseThrow();
    }

    private StoryVO.Module.ChatModel staticChatModel() {
        StoryVO.Module.ChatModel chat = new StoryVO.Module.ChatModel();
        chat.setModel("static-model");
        return chat;
    }
}
