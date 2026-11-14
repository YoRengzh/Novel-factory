package cn.novel.yonren.domain.novel.service.armory.llm;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.config.ReferenceSelectorProperties;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.domain.novel.model.valobj.prompt.ReferenceIndexEntry;
import cn.novel.yonren.domain.novel.service.armory.LlmRuntimeConfig;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptBuilder;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import cn.novel.yonren.domain.novel.service.armory.prompt.reference.*;
import cn.novel.yonren.types.enums.PromptScene;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RealReferenceWritingRulesTest {
    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path temp;

    @Test
    void realQualityChecklistReachesFinalRequestWithoutObsoleteQuotas() {
        var index = mock(ReferenceIndexLoader.class);
        var selector = mock(LlmReferenceSelector.class);
        var retriever = mock(VectorReferenceRetriever.class);
        var runtime = mock(LlmRuntimeConfig.class);
        var properties = new ReferenceSelectorProperties();
        properties.setMode("llm");
        properties.setCacheEnabled(false);
        properties.setContentSceneRecallChars(20000);
        properties.setMaxRecallChars(20000);
        var entry = new ReferenceIndexEntry("quality-checklist", "质量检查清单", "逐场检查阅读质量", "references/quality-checklist.md");
        when(index.getIndex()).thenReturn(List.of(entry));
        when(runtime.effectiveReferenceMode(anyString())).thenReturn("llm");
        when(selector.select(any(), any(), any(), anyList())).thenReturn(List.of("quality-checklist"));
        var loader = new PromptRuleFileLoader();
        org.springframework.test.util.ReflectionTestUtils.setField(loader, "userAssetRoot", temp);
        var references = new DynamicReferenceService(index, selector, retriever, loader,
                properties, runtime, null, new ReferenceUsagePolicy(), null);
        var builder = mock(PromptBuilder.class);
        when(builder.collectRules(any(), any())).thenReturn(List.of());
        var gateway = mock(LlmGateway.class);
        when(gateway.complete(any(), any())).thenReturn("{}");
        var service = new LlmInvokeService(builder, gateway, references);
        var story = new StoryVO();
        story.setModule(new StoryVO.Module());
        service.invoke(story, PromptScene.CHAPTER_CONTENT, PromptContext.builder().chapterNo(3).theme("玄幻修真").build(),
                "已建立事实优先，按schemaVersion=2的scenes交付，正文输出JSON。", null);
        var call = ArgumentCaptor.forClass(LlmCall.class);
        verify(gateway).complete(any(), call.capture());
        String prompt = call.getValue().getUserPrompt();
        assertTrue(prompt.contains("质量检查清单"), "真实资源必须经动态资料装配进入请求，而非只检查磁盘文件");
        assertTrue(prompt.contains("人物改变决定的依据"));
        assertTrue(prompt.contains("schemaVersion=2"));
        for (String obsolete : List.of("全篇至多2次", "全篇至多3次", "至少一种悬念", "默认至少写 25", "不得低于全局 1500")) {
            assertFalse(prompt.contains(obsolete), obsolete);
        }
        assertFalse(prompt.contains("以下为写作规则，创作时必须遵守"));
        verify(retriever, never()).prepare(any());
    }
}
