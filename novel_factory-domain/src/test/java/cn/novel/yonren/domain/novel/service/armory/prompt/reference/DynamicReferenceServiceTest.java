package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import cn.novel.yonren.domain.novel.model.valobj.prompt.ReferenceIndexEntry;

import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.config.ReferenceSelectorProperties;
import cn.novel.yonren.domain.novel.service.armory.LlmRuntimeConfig;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptRule;
import cn.novel.yonren.types.enums.ChapterType;
import cn.novel.yonren.types.enums.PromptScene;
import cn.novel.yonren.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 动态资料编排测试：开关短路、缓存命中、截断上限与缺文件跳过
 */
class DynamicReferenceServiceTest {

    @TempDir
    Path temp;
    private ReferenceIndexLoader referenceIndexLoader;
    private LlmReferenceSelector selector;
    private VectorReferenceRetriever retriever;
    private VectorReferenceRetriever.Prepared legacyPrepared;
    private PromptRuleFileLoader fileLoader;
    private ReferenceSelectorProperties properties;
    private LlmRuntimeConfig llmRuntimeConfig;
    private DynamicReferenceService service;

    @BeforeEach
    void setUp() {
        referenceIndexLoader = mock(ReferenceIndexLoader.class);
        selector = mock(LlmReferenceSelector.class);
        retriever = mock(VectorReferenceRetriever.class);
        legacyPrepared = mock(VectorReferenceRetriever.Prepared.class);
        when(retriever.prepare(any())).thenReturn(legacyPrepared);
        when(legacyPrepared.versionKey()).thenReturn("frozen-space-v1");
        fileLoader = mock(PromptRuleFileLoader.class);
        properties = new ReferenceSelectorProperties();
        llmRuntimeConfig = mock(LlmRuntimeConfig.class);
        when(llmRuntimeConfig.effectiveReferenceMode(anyString()))
                .thenAnswer(inv -> inv.getArgument(0));   // 默认透传 yml 模式（无覆盖）
        // 第 7 个参数是 FeatureToggles：传 null 表示不启用分组开关（按全开处理，等价旧行为）
        service = new DynamicReferenceService(referenceIndexLoader, selector, retriever, fileLoader,
                properties, llmRuntimeConfig, null, new ReferenceUsagePolicy(), null);

        when(referenceIndexLoader.getIndex()).thenReturn(List.of(entry("dialogue-writing")));
        when(fileLoader.load(anyString())).thenReturn("资料内容");
    }

    @Test
    void blockModeSearchesEligibleSectionsOfAnIneligibleWholeDocument() {
        properties.setMode("vector");
        properties.getContentBlocks().setEnabled(true);
        var policy = new ReferenceUsagePolicy();
        var blockRetriever = mock(ReferenceBlockRetriever.class);
        service = new DynamicReferenceService(referenceIndexLoader, selector, retriever, fileLoader,
                properties, llmRuntimeConfig, null, policy, blockRetriever);
        var document = entry("character-building");
        var eligible = new ReferenceAtom(document.getFileName(), "eligible", "人物塑造", "人物深度塑造技巧",
                0, "角色需要独立动机", "hash", null, List.of(), true);
        var ineligible = new ReferenceAtom(document.getFileName(), "ineligible", "人物塑造", "人物档案模板",
                1, "人物档案模板", "hash", null, List.of(), true);
        var snapshot = new ReferenceBlockSnapshot(null, List.of(eligible, ineligible),
                Map.of("eligible", eligible, "ineligible", ineligible), "corpus", "signature");
        var prepared = mock(ReferenceBlockRetriever.Prepared.class);
        var module = new StoryVO.Module();
        var context = PromptContext.builder().build();
        when(referenceIndexLoader.getIndex()).thenReturn(List.of(document));
        when(blockRetriever.prepare(module)).thenReturn(prepared);
        when(prepared.snapshot()).thenReturn(snapshot);
        when(blockRetriever.retrieve(eq(prepared), anyString(), eq(Set.of(document.getFileName())),
                eq(Set.of("eligible")), eq(12), eq(0.0)))
                .thenReturn(new ReferenceBlockRetriever.Retrieval(snapshot, List.of()));

        assertFalse(policy.allowsDocument(document.getFileName(), PromptScene.CHAPTER_CONTENT, context));
        service.selectAsRules(module, PromptScene.CHAPTER_CONTENT, context);

        verify(blockRetriever).retrieve(eq(prepared), anyString(), eq(Set.of(document.getFileName())),
                eq(Set.of("eligible")), eq(12), eq(0.0));
    }

    @Test
    void runtimeOverrideDecidesMode() {
        // 运行时覆盖 = llm：即使 yml 是 vector，也走 LLM 选择器（不触发向量检索）
        properties.setMode("vector");
        when(llmRuntimeConfig.effectiveReferenceMode("vector")).thenReturn("llm");
        when(selector.select(any(), any(), any(), anyList()))
                .thenReturn(List.of("chapter-guide"));
        when(referenceIndexLoader.getIndex()).thenReturn(List.of(
                entry("chapter-guide"), entry("opening-design")));

        List<PromptRule> rules = service.selectAsRules(null, PromptScene.CHAPTER_PLAN, planCtx());

        verify(retriever, never()).retrieve(any(VectorReferenceRetriever.Prepared.class), any(), any());
        verify(selector, times(1)).select(any(), any(), any(), anyList());
        assertEquals(1, rules.size());
    }

    @Test
    void disabledSwitchShortCircuitsWithoutSelection() {
        properties.setEnabled(false);

        List<PromptRule> rules = service.selectAsRules(null, PromptScene.CHAPTER_PLAN, planCtx());

        assertTrue(rules.isEmpty());
        verify(selector, never()).select(any(), any(), any(), anyList());
    }

    @Test
    void unregisteredOrIneligibleSourcesNeverReachTheSelector() {
        when(referenceIndexLoader.getIndex()).thenReturn(List.of(
                entry("worldbuilding"), entry("unregistered-new-doc"), entry("exemplar-dialogue")));
        when(selector.select(any(), any(), any(), anyList())).thenReturn(List.of());

        service.selectAsRules(null, PromptScene.SETTING_DRAFT,
                PromptContext.builder().theme("玄幻").build());

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<ReferenceIndexEntry>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(selector).select(any(), any(), any(), captor.capture());
        assertEquals(List.of("worldbuilding"), captor.getValue().stream()
                .map(ReferenceIndexEntry::getFileName).toList(),
                "未登记来源和正文范例不能进入设定草稿候选");
    }

    @Test
    void selectedIneligibleSourceIsDroppedBeforePromptRuleCreation() {
        when(referenceIndexLoader.getIndex()).thenReturn(List.of(entry("worldbuilding")));
        when(selector.select(any(), any(), any(), anyList())).thenReturn(List.of("exemplar-battle"));

        List<PromptRule> rules = service.selectAsRules(null, PromptScene.SETTING_DRAFT,
                PromptContext.builder().theme("玄幻").build());

        assertTrue(rules.isEmpty(), "选择器越界返回的正文范例不得进入提示词");
        verify(fileLoader, never()).load("references/exemplar-battle.md");
    }

    @Test
    void targetFieldsNarrowCandidatesForSettingDraft() {
        when(referenceIndexLoader.getIndex()).thenReturn(List.of(
                entry("worldbuilding"), entry("villain-design")));
        when(selector.select(any(), any(), any(), anyList())).thenReturn(List.of());

        assertTrue(service.selectAsRules(null, PromptScene.SETTING_DRAFT,
                PromptContext.builder().theme("玄幻").targetFields(List.of("tone")).build()).isEmpty());

        verify(selector, never()).select(any(), any(), any(), anyList());
    }

    private static ReferenceIndexEntry entry(String fileName) {
        return new ReferenceIndexEntry(fileName, fileName, "", "references/" + fileName + ".md");
    }

    @Test
    void cacheReusesSelectionForSameSceneThemeChapterType() {
        when(selector.select(any(), any(), any(), anyList()))
                .thenReturn(List.of("dialogue-writing"));

        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterType.NORMAL));
        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterType.NORMAL));

        // 同 场景+题材+风格+章节类型+模型+索引 只调一次选择器
        verify(selector, times(1)).select(any(), any(), any(), anyList());
    }

    @Test
    void differentChapterTypeSelectsIndependently() {
        when(selector.select(any(), any(), any(), anyList()))
                .thenReturn(List.of("dialogue-writing"));

        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterType.NORMAL));
        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterType.CLIMAX));

        verify(selector, times(2)).select(any(), any(), any(), anyList());
    }

    @Test
    void differentThemeSelectsIndependently() {
        when(selector.select(any(), any(), any(), anyList()))
                .thenReturn(List.of("dialogue-writing"));

        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterType.NORMAL));
        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, PromptContext.builder()
                .theme("都市异能").style("刑侦")
                .chapterNo(3).totalChapters(10).chapterType(ChapterType.NORMAL).build());

        verify(selector, times(2)).select(any(), any(), any(), anyList());
    }

    @Test
    void differentChapterBriefSelectsIndependently() {
        when(selector.select(any(), any(), any(), anyList()))
                .thenReturn(List.of("dialogue-writing"));

        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterType.NORMAL, null));
        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterType.NORMAL, "揭开古物来历"));
        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterType.NORMAL, "揭开古物来历"));

        // 章节要点参与缓存键：不同本章要点各选一次，同要点命中缓存
        verify(selector, times(2)).select(any(), any(), any(), anyList());
    }

    @Test
    void differentModelSelectsIndependently() {
        when(selector.select(any(), any(), any(), anyList()))
                .thenReturn(List.of("dialogue-writing"));

        service.selectAsRules(module("https://api-a", "model-1"), PromptScene.CHAPTER_CONTENT, contentCtx(ChapterType.NORMAL));
        service.selectAsRules(module("https://api-a", "model-2"), PromptScene.CHAPTER_CONTENT, contentCtx(ChapterType.NORMAL));

        verify(selector, times(2)).select(any(), any(), any(), anyList());
    }

    @Test
    void indexChangeInvalidatesCache() {
        when(selector.select(any(), any(), any(), anyList()))
                .thenReturn(List.of("chapter-guide"));
        when(referenceIndexLoader.getIndex())
                .thenReturn(List.of(entry("chapter-guide")))
                .thenReturn(List.of(entry("chapter-guide"), entry("opening-design")));

        service.selectAsRules(null, PromptScene.CHAPTER_PLAN, planCtx());
        service.selectAsRules(null, PromptScene.CHAPTER_PLAN, planCtx());

        // 索引内容变化（资料增删/改写）→ 缓存失效重新选择
        verify(selector, times(2)).select(any(), any(), any(), anyList());
    }

    @Test
    void cacheCapacityEvictsLeastRecentlyUsed() {
        properties.setCacheMaxEntries(1);
        when(selector.select(any(), any(), any(), anyList()))
                .thenReturn(List.of("dialogue-writing"));

        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterType.NORMAL));
        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterType.CLIMAX));
        // 容量 1：NORMAL 键已被淘汰，重新请求需再次选择
        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterType.NORMAL));

        verify(selector, times(3)).select(any(), any(), any(), anyList());
    }

    @Test
    void rulesWrappedAsUserTailWithRefPrefix() {
        when(selector.select(any(), any(), any(), anyList()))
                .thenReturn(List.of("dialogue-writing"));

        List<PromptRule> rules = service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterType.NORMAL));

        assertEquals(1, rules.size());
        assertEquals("ref-dialogue-writing", rules.get(0).getName());
        assertEquals(PromptRule.InjectPosition.USER_TAIL, rules.get(0).getInjectPosition());
    }

    @Test
    void maxSelectedTruncatesRules() {
        properties.setMaxSelected(2);
        when(selector.select(any(), any(), any(), anyList()))
                .thenReturn(List.of("chapter-guide", "opening-design", "pacing-control"));
        when(referenceIndexLoader.getIndex()).thenReturn(List.of(
                entry("chapter-guide"), entry("opening-design"), entry("pacing-control")));

        List<PromptRule> rules = service.selectAsRules(null, PromptScene.CHAPTER_PLAN, planCtx());

        assertEquals(2, rules.size());
        verify(fileLoader, never()).load(eq("references/pacing-control.md"));
    }

    @Test
    void maxRecallCharsBoundsInjectedContent() {
        properties.setMaxRecallChars(12);
        when(selector.select(any(), any(), any(), anyList()))
                .thenReturn(List.of("chapter-guide"));
        when(referenceIndexLoader.getIndex()).thenReturn(List.of(entry("chapter-guide")));
        when(fileLoader.load("references/chapter-guide.md"))
                .thenReturn("一二三四五六七八九十十一十二十三十四");

        List<PromptRule> rules = service.selectAsRules(null, PromptScene.CHAPTER_PLAN, planCtx());

        assertEquals(1, rules.size());
        assertTrue(rules.get(0).getContent().length() <= 12);
    }

    @Test
    void missingFileSkippedWithoutBreaking() {
        when(selector.select(any(), any(), any(), anyList()))
                .thenReturn(List.of("chapter-guide", "opening-design"));
        when(referenceIndexLoader.getIndex()).thenReturn(List.of(
                entry("chapter-guide"), entry("opening-design")));
        when(fileLoader.load("references/chapter-guide.md")).thenReturn(null);

        List<PromptRule> rules = service.selectAsRules(null, PromptScene.CHAPTER_PLAN, planCtx());

        assertEquals(1, rules.size());
        assertEquals("ref-opening-design", rules.get(0).getName());
    }

    @Test
    void vectorModeRoutesToRetrieverWithoutLlmCall() {
        properties.setMode("vector");
        when(retriever.retrieve(any(VectorReferenceRetriever.Prepared.class), any(), any()))
                .thenReturn(List.of("dialogue-writing"));

        List<PromptRule> rules = service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterType.NORMAL));

        assertEquals(1, rules.size());
        assertEquals("ref-dialogue-writing", rules.get(0).getName());
        verify(selector, never()).select(any(), any(), any(), anyList());
    }

    @Test
    void vectorFailureTerminatesWithoutLlmFallback() {
        properties.setMode("vector");
        when(retriever.retrieve(any(VectorReferenceRetriever.Prepared.class), any(), any()))
                .thenThrow(new IllegalStateException("qdrant down"));

        // 快速失败：向量链路任何失败（欠费/向量库/网络）都终止作业，绝不降级 LLM 选择
        AppException ex = assertThrows(AppException.class,
                () -> service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterType.NORMAL)));

        assertTrue(ex.getInfo().contains("不降级"));
        verify(selector, never()).select(any(), any(), any(), anyList());
        verify(retriever, times(1)).retrieve(any(VectorReferenceRetriever.Prepared.class), any(), any());
    }

    @Test
    void corpusVersionInvalidatesLoadedRulesWhenTitleAndDigestStayUnchanged() {
        when(referenceIndexLoader.corpusVersion()).thenReturn("body-v1", "body-v2", "body-v2");
        when(selector.select(any(), any(), any(), anyList())).thenReturn(List.of("dialogue-writing"));
        when(fileLoader.load("references/dialogue-writing.md")).thenReturn("旧正文", "新正文");

        var first = service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterType.NORMAL));
        var second = service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterType.NORMAL));
        var third = service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterType.NORMAL));

        assertEquals("旧正文", first.get(0).getContent());
        assertEquals("新正文", second.get(0).getContent());
        assertEquals("新正文", third.get(0).getContent());
        verify(selector, times(2)).select(any(), any(), any(), anyList());
    }

    @Test
    void disabledSourceCannotLeakFromOldCacheOrOutOfBoundsSelector() {
        var policy = new ReferenceSourcePolicy(temp.resolve("state.json"));
        when(referenceIndexLoader.sourcePolicy()).thenReturn(policy);
        when(referenceIndexLoader.corpusVersion()).thenAnswer(inv -> "corpus-r" + policy.revision());
        when(selector.select(any(), any(), any(), anyList())).thenReturn(List.of("dialogue-writing"));
        assertEquals(1, service.selectAsRules(null, PromptScene.CHAPTER_CONTENT,
                contentCtx(ChapterType.NORMAL)).size());
        policy.setEnabled("dialogue-writing", false);
        assertTrue(service.selectAsRules(null, PromptScene.CHAPTER_CONTENT,
                contentCtx(ChapterType.NORMAL)).isEmpty());
        policy.setEnabled("dialogue-writing", true);
        assertEquals(1, service.selectAsRules(null, PromptScene.CHAPTER_CONTENT,
                contentCtx(ChapterType.NORMAL)).size());
        verify(selector, times(2)).select(any(), any(), any(), anyList());
    }

    @Test
    void emptyEligibleCorpusShortCircuitsBothLegacyAndBlockPaths() {
        when(referenceIndexLoader.getIndex()).thenReturn(List.of());
        properties.setMode("vector");
        var block = mock(ReferenceBlockRetriever.class);
        properties.getContentBlocks().setEnabled(true);
        service = new DynamicReferenceService(referenceIndexLoader, selector, retriever, fileLoader,
                properties, llmRuntimeConfig, null, new ReferenceUsagePolicy(), block);
        assertTrue(service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterType.NORMAL)).isEmpty());
        verify(block, never()).prepare(any());
        verify(retriever, never()).retrieve(any(VectorReferenceRetriever.Prepared.class), any(), any());
        verify(selector, never()).select(any(), any(), any(), anyList());
    }

    @Test
    void legacyAndLlmSelectionsRecheckDisabledSourcesBeforeLoading() {
        var policy = new ReferenceSourcePolicy(temp.resolve("state.json"));
        policy.setEnabled("dialogue-writing", false);
        when(referenceIndexLoader.sourcePolicy()).thenReturn(policy);
        when(referenceIndexLoader.getIndex()).thenReturn(List.of(entry("dialogue-writing"), entry("pacing-control")));
        when(selector.select(any(), any(), any(), anyList())).thenReturn(List.of("dialogue-writing"));
        assertTrue(service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterType.NORMAL)).isEmpty());
        properties.setMode("vector");
        when(retriever.retrieve(any(VectorReferenceRetriever.Prepared.class), any(), any())).thenReturn(List.of("dialogue-writing"));
        assertTrue(service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterType.NORMAL)).isEmpty());
        verify(fileLoader, never()).load("references/dialogue-writing.md");
    }

    @Test
    void legacyCacheIncludesTheFrozenEffectiveEmbeddingVersion() {
        properties.setMode("vector");
        when(legacyPrepared.versionKey()).thenReturn("space-v1", "space-v2", "space-v2");
        when(retriever.retrieve(eq(legacyPrepared), any(), any())).thenReturn(List.of("dialogue-writing"));
        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterType.NORMAL));
        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterType.NORMAL));
        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterType.NORMAL));
        verify(retriever, times(3)).prepare(any());
        verify(retriever, times(2)).retrieve(eq(legacyPrepared), any(), any());
    }

    @Test
    void cachedLegacySelectionCannotHideAnEmbeddingSpaceChange() {
        properties.setMode("vector");
        when(retriever.retrieve(eq(legacyPrepared), any(), any())).thenReturn(List.of("dialogue-writing"));
        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterType.NORMAL));
        when(retriever.prepare(any())).thenThrow(new IllegalStateException("空间已变化，请显式重建"));
        assertThrows(AppException.class,
                () -> service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterType.NORMAL)));
        verify(retriever, times(1)).retrieve(eq(legacyPrepared), any(), any());
    }

    @Test
    void publicInvalidateForcesSelectionAgainWithoutChangingCorpus() {
        when(selector.select(any(), any(), any(), anyList())).thenReturn(List.of("dialogue-writing"));
        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterType.NORMAL));
        service.invalidate();
        service.selectAsRules(null, PromptScene.CHAPTER_CONTENT, contentCtx(ChapterType.NORMAL));
        verify(selector, times(2)).select(any(), any(), any(), anyList());
    }

    private PromptContext planCtx() {
        return PromptContext.builder().theme("悬疑推理").style("刑侦").totalChapters(10).build();
    }

    private PromptContext contentCtx(ChapterType chapterType) {
        return contentCtx(chapterType, null);
    }

    private PromptContext contentCtx(ChapterType chapterType, String chapterBrief) {
        return PromptContext.builder().theme("悬疑推理").style("刑侦")
                .chapterNo(3).totalChapters(10).chapterType(chapterType).chapterBrief(chapterBrief).build();
    }

    private StoryVO.Module module(String baseUrl, String model) {
        StoryVO.Module module = new StoryVO.Module();
        StoryVO.Module.AiApi aiApi = new StoryVO.Module.AiApi();
        aiApi.setBaseUrl(baseUrl);
        module.setAiApi(aiApi);
        StoryVO.Module.ChatModel chatModel = new StoryVO.Module.ChatModel();
        chatModel.setModel(model);
        module.setChatModel(chatModel);
        return module;
    }

}
