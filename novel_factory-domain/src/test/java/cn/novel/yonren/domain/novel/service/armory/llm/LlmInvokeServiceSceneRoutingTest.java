package cn.novel.yonren.domain.novel.service.armory.llm;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptBuilder;
import cn.novel.yonren.domain.novel.service.armory.prompt.reference.DynamicReferenceService;
import cn.novel.yonren.domain.novel.service.job.JobRegistry;
import org.springframework.test.util.ReflectionTestUtils;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.types.enums.PromptScene;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmInvokeServiceSceneRoutingTest {

    @Test
    void modelFailureStillReleasesGlobalReferenceUse() {
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        LlmGateway gateway = mock(LlmGateway.class);
        DynamicReferenceService references = mock(DynamicReferenceService.class);
        JobRegistry registry = new JobRegistry();
        when(promptBuilder.collectRules(any(), any())).thenReturn(List.of());
        when(references.selectAsRules(any(), any(), any())).thenReturn(List.of());
        when(gateway.complete(any(), any())).thenAnswer(call -> {
            org.junit.jupiter.api.Assertions.assertFalse(registry.tryBeginReferenceOperation());
            throw new IllegalStateException("test model failure");
        });
        LlmInvokeService service = new LlmInvokeService(promptBuilder, gateway, references);
        ReflectionTestUtils.setField(service, "jobRegistry", registry);
        StoryVO story = new StoryVO();
        story.setModule(new StoryVO.Module());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                service.invoke(story, PromptScene.SETTING_DRAFT, PromptContext.builder().build(), "draft", null));
        org.junit.jupiter.api.Assertions.assertTrue(registry.tryBeginReferenceOperation());
        registry.endReferenceOperation();
    }

    @Test
    void maintenanceRejectsSynchronousDraftBeforeLoadingRules() {
        JobRegistry registry = new JobRegistry();
        org.junit.jupiter.api.Assertions.assertTrue(registry.tryBeginReferenceOperation());
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        LlmGateway gateway = mock(LlmGateway.class);
        DynamicReferenceService references = mock(DynamicReferenceService.class);
        LlmInvokeService service = new LlmInvokeService(promptBuilder, gateway, references);
        ReflectionTestUtils.setField(service, "jobRegistry", registry);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                service.invoke(new StoryVO(), PromptScene.SETTING_DRAFT, PromptContext.builder().build(), "draft", null));
        org.mockito.Mockito.verifyNoInteractions(promptBuilder, gateway, references);
        registry.endReferenceOperation();
    }

    @Test
    void duplicateDynamicCopyIsSkippedButPartialFixedSectionKeepsRemainder() {
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        LlmGateway gateway = mock(LlmGateway.class);
        DynamicReferenceService references = mock(DynamicReferenceService.class);
        when(promptBuilder.collectRules(any(), any())).thenReturn(List.of(
                new cn.novel.yonren.domain.novel.model.valobj.prompt.PromptRule(
                        "anti-padding", "完整章节：删掉重复的判断。", PromptRulePosition.SYSTEM)));
        when(references.selectAsRules(any(), any(), any())).thenReturn(List.of(
                new cn.novel.yonren.domain.novel.model.valobj.prompt.PromptRule(
                        "ref-anti-padding", "完整章节：删掉重复的判断。", PromptRulePosition.USER_TAIL),
                new cn.novel.yonren.domain.novel.model.valobj.prompt.PromptRule(
                        "ref-longer", "完整章节：删掉重复的判断。\n另一节：保留有阅读功能的心理描写。", PromptRulePosition.USER_TAIL)));
        when(gateway.complete(any(), any())).thenReturn("{}");

        new LlmInvokeService(promptBuilder, gateway, references).invoke(new StoryVO(),
                PromptScene.SETTING_DRAFT, PromptContext.builder().theme("都市").build(), "业务prompt", null);

        ArgumentCaptor<LlmCall> captor = ArgumentCaptor.forClass(LlmCall.class);
        verify(gateway).complete(any(), captor.capture());
        String sent = captor.getValue().getUserPrompt();
        org.junit.jupiter.api.Assertions.assertFalse(sent.contains("【ref-anti-padding】"),
                "与固定资料完全相同的动态副本不得重复注入");
        org.junit.jupiter.api.Assertions.assertTrue(sent.contains("【ref-longer】"),
                "含新增小节的动态资料不能按文件名误删");
    }

    private static final class PromptRulePosition {
        static final cn.novel.yonren.domain.novel.model.valobj.prompt.PromptRule.InjectPosition SYSTEM =
                cn.novel.yonren.domain.novel.model.valobj.prompt.PromptRule.InjectPosition.SYSTEM;
        static final cn.novel.yonren.domain.novel.model.valobj.prompt.PromptRule.InjectPosition USER_TAIL =
                cn.novel.yonren.domain.novel.model.valobj.prompt.PromptRule.InjectPosition.USER_TAIL;
    }

    @Test
    void settingDraftRoutesToGlobalModel() {
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
        assertNull(captor.getValue().getScene(), "设定集应使用全局聊天模型");
        assertEquals("setting-draft", captor.getValue().getLabel(), "用量仍按设定集记录");
    }

    @Test
    void directWriterModeSkipsDynamicReferenceSelectionForChapterContent() {
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        LlmGateway gateway = mock(LlmGateway.class);
        DynamicReferenceService references = mock(DynamicReferenceService.class);
        when(promptBuilder.collectRules(any(), any())).thenReturn(List.of(
                new cn.novel.yonren.domain.novel.model.valobj.prompt.PromptRule(
                        "style-anchor-fantasy", "语感切片正文", PromptRulePosition.USER_TAIL)));
        when(gateway.complete(any(), any())).thenReturn("第一章 标题\n\n正文。");

        LlmInvokeService service = new LlmInvokeService(promptBuilder, gateway, references);
        StoryVO storyVO = new StoryVO();
        storyVO.setModule(new StoryVO.Module());

        service.invoke(storyVO, PromptScene.CHAPTER_CONTENT,
                PromptContext.builder().theme("玄幻").chapterNo(1).directWriterMode(true).build(),
                "局面提示", null);

        org.mockito.Mockito.verify(references, org.mockito.Mockito.never()).selectAsRules(any(), any(), any());
        ArgumentCaptor<LlmCall> captor = ArgumentCaptor.forClass(LlmCall.class);
        verify(gateway).complete(any(), captor.capture());
        String sent = captor.getValue().getUserPrompt();
        org.junit.jupiter.api.Assertions.assertTrue(sent.contains("语感与文风参考"));
        org.junit.jupiter.api.Assertions.assertTrue(sent.contains("语感切片正文"));
        org.junit.jupiter.api.Assertions.assertFalse(sent.contains("不新增剧情任务或数量配额"));
    }
}
