package cn.novel.yonren.trigger.http;

import cn.novel.yonren.api.dto.LlmConfigDTO;
import cn.novel.yonren.api.dto.LlmConfigSaveRequestDTO;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.LlmRuntimeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LLM 配置端点测试：GET 返回含场景矩阵的全量视图；PUT 保存后回全量视图；
 * reset/resetScenes 语义透传（校验在 LlmRuntimeConfig）
 */
class LlmConfigControllerTest {

    @Mock
    private LlmRuntimeConfig llmRuntimeConfig;

    @Mock
    private StoryProperties storyProperties;

    @Mock
    private cn.novel.yonren.domain.novel.model.valobj.properties.ReferenceSelectorProperties referenceSelectorProperties;

    @InjectMocks
    private LlmConfigController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // controller 的两个 view 调用点都会读 selector 静态模式——默认 stub 掉，个别用例再覆盖
        when(referenceSelectorProperties.getMode()).thenReturn("vector");
    }

    @Test
    void get_returnsFullViewWithSceneMatrix() {
        LlmConfigDTO dto = new LlmConfigDTO();
        dto.setConfigured(true);
        dto.setBaseUrl("https://runtime.example.com");
        dto.setModel("runtime-model");
        dto.setHasKey(true);
        dto.setApiKeyMasked("sk-***key1");
        when(referenceSelectorProperties.getMode()).thenReturn("vector");
        when(llmRuntimeConfig.view(same(storyProperties), eq("vector"))).thenReturn(dto);

        LlmConfigDTO result = controller.get();

        assertNotNull(result);
        assertTrue(result.isConfigured());
        assertEquals("https://runtime.example.com", result.getBaseUrl());
        assertEquals("sk-***key1", result.getApiKeyMasked());
        verify(llmRuntimeConfig).view(same(storyProperties), same("vector"));
    }

    @Test
    void save_persistsThenEchoesFullView() {
        LlmConfigSaveRequestDTO request = new LlmConfigSaveRequestDTO();
        request.setBaseUrl("https://new.example.com");
        request.setApiKey("sk-new-1");
        request.setModel("new-model");
        request.setMaxTokens(2048L);
        LlmConfigDTO fullView = new LlmConfigDTO();
        fullView.setConfigured(true);
        fullView.setBaseUrl("https://new.example.com");
        fullView.setModel("new-model");
        when(llmRuntimeConfig.save(any(LlmConfigSaveRequestDTO.class))).thenReturn(new LlmConfigDTO());
        when(llmRuntimeConfig.view(same(storyProperties), eq("vector"))).thenReturn(fullView);

        LlmConfigDTO result = controller.save(request);

        assertEquals("https://new.example.com", result.getBaseUrl());
        assertEquals("new-model", result.getModel());
        verify(llmRuntimeConfig).save(request);
        // 保存后回全量视图（含场景矩阵），前端整页刷新当前生效状态
        verify(llmRuntimeConfig).view(same(storyProperties), same("vector"));
    }

    @Test
    void save_resetFlagForwardedToConfig() {
        LlmConfigSaveRequestDTO reset = new LlmConfigSaveRequestDTO();
        reset.setReset(true);
        LlmConfigDTO cleared = new LlmConfigDTO();
        cleared.setConfigured(false);
        when(llmRuntimeConfig.save(any(LlmConfigSaveRequestDTO.class))).thenReturn(cleared);
        LlmConfigDTO fullView = new LlmConfigDTO();
        fullView.setConfigured(false);
        when(llmRuntimeConfig.view(same(storyProperties), eq("vector"))).thenReturn(fullView);

        LlmConfigDTO result = controller.save(reset);

        assertFalse(result.isConfigured());
        verify(llmRuntimeConfig).save(reset);
    }

    @Test
    void get_storyPropertiesPassedThroughUnchanged() {
        LlmConfigDTO dto = new LlmConfigDTO();
        when(llmRuntimeConfig.view(same(storyProperties), eq("vector"))).thenReturn(dto);

        assertSame(dto, controller.get());
    }

    // ===== 模型列表拉取端点（providers/models）=====

    @Mock
    private cn.novel.yonren.domain.novel.adapter.llm.ModelCatalogPort modelCatalogPort;

    @Test
    void fetchModels_byProviderId_usesStoredCredentials() {
        LlmRuntimeConfig.ProviderOverlay provider = new LlmRuntimeConfig.ProviderOverlay();
        provider.setBaseUrl("https://relay.example.com");
        provider.setApiKey("sk-relay");
        when(llmRuntimeConfig.providerOf("relay-a")).thenReturn(provider);
        when(modelCatalogPort.fetchModels("https://relay.example.com", "sk-relay"))
                .thenReturn(java.util.List.of("m1", "m2"));

        java.util.List<String> result = controller.fetchModels(fetchRequest("relay-a", null, null));

        assertEquals(2, result.size());
        assertEquals("m1", result.get(0));
    }

    @Test
    void fetchModels_explicitBaseUrlWinsWithProviderKey() {
        LlmRuntimeConfig.ProviderOverlay provider = new LlmRuntimeConfig.ProviderOverlay();
        provider.setBaseUrl("https://relay.example.com");
        provider.setApiKey("sk-relay");
        when(llmRuntimeConfig.providerOf("relay-a")).thenReturn(provider);
        when(modelCatalogPort.fetchModels("https://manual.example.com", "sk-relay"))
                .thenReturn(java.util.List.of("m"));

        // 试连语义：显式地址 + 中转站存的密钥（保存前用表单当前值探测）
        java.util.List<String> result = controller.fetchModels(fetchRequest("relay-a", "https://manual.example.com", null));

        assertEquals(1, result.size());
    }

    @Test
    void fetchModels_unknownProvider_404() {
        when(llmRuntimeConfig.providerOf("missing")).thenReturn(null);

        org.springframework.web.server.ResponseStatusException ex = assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> controller.fetchModels(fetchRequest("missing", null, null)));
        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void fetchModels_withoutBaseUrl_400() {
        org.springframework.web.server.ResponseStatusException ex = assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> controller.fetchModels(fetchRequest(null, "  ", null)));
        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    private static cn.novel.yonren.api.dto.ModelFetchRequestDTO fetchRequest(
            String providerId, String baseUrl, String apiKey) {
        cn.novel.yonren.api.dto.ModelFetchRequestDTO req = new cn.novel.yonren.api.dto.ModelFetchRequestDTO();
        req.setProviderId(providerId);
        req.setBaseUrl(baseUrl);
        req.setApiKey(apiKey);
        return req;
    }
}
