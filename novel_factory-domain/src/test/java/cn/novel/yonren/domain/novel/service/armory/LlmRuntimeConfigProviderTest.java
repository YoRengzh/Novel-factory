package cn.novel.yonren.domain.novel.service.armory;

import cn.novel.yonren.api.dto.LlmConfigDTO;
import cn.novel.yonren.api.dto.LlmConfigSaveRequestDTO;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
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
}
