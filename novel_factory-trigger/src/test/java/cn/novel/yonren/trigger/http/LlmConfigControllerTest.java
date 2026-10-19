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
}
