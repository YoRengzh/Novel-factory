package cn.novel.yonren.domain.novel.service.armory.llm;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptBuilder;
import cn.novel.yonren.domain.novel.service.armory.prompt.reference.DynamicReferenceService;
import cn.novel.yonren.domain.novel.service.armory.prompt.valobj.PromptContext;
import cn.novel.yonren.types.enums.ModelScene;
import cn.novel.yonren.types.enums.PromptScene;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 场景 → 模型路由映射测试：SETTING_DRAFT 曾映射为 null（走统一 chat-model 的 flash 档），
 * 直接用最便宜档位生成千字级设定集，是"一键生成质量低下"的直接原因。
 */
class LlmInvokeServiceSceneRoutingTest {

    @Test
    void settingDraftRoutesToItsOwnSceneModel() {
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        LlmGateway gateway = mock(LlmGateway.class);
        DynamicReferenceService references = mock(DynamicReferenceService.class);
        when(promptBuilder.collectRules(any(), any())).thenReturn(List.of());
        when(references.selectAsRules(any(), any(), any())).thenReturn(List.of());
        when(gateway.complete(any(), any())).thenReturn("{}");

        LlmInvokeService service = new LlmInvokeService(promptBuilder, gateway, references);
        StoryVO storyVO = new StoryVO();
        storyVO.setModule(new StoryVO.Module());

        service.invoke(storyVO, PromptScene.SETTING_DRAFT, PromptContext.builder().theme("都市异能").build(),
                "prompt", null);

        ArgumentCaptor<LlmCall> captor = ArgumentCaptor.forClass(LlmCall.class);
        verify(gateway).complete(any(), captor.capture());
        assertEquals(ModelScene.SETTING_DRAFT, captor.getValue().getScene(),
                "设定集必须路由到独立场景，才能配到 pro 档而不被统一 flash 档拉低质量");
        assertEquals("setting-draft", ModelScene.SETTING_DRAFT.getConfigKey(),
                "configKey 必须与 yml scene-models 的键一致");
    }
}
