package cn.novel.yonren.domain.novel.service.armory.edit;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.memory.ChapterMemoryService;
import cn.novel.yonren.domain.novel.service.armory.memory.ChapterSummaryService;
import cn.novel.yonren.domain.novel.service.armory.memory.StoryMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 章节编辑回流测试：成功替换摘要 / 越界返回 warning / 摘要异常转 warning
 */
class ChapterEditServiceTest {

    @Mock
    private IStoryRepository storyRepository;
    @Mock
    private ChapterSummaryService chapterSummaryService;
    @Mock
    private ChapterMemoryService chapterMemoryService;
    @Mock
    private StoryMemoryService storyMemoryService;

    private StoryProperties storyProperties;

    @InjectMocks
    private ChapterEditService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        storyProperties = new StoryProperties();
        storyProperties.setDefaults(new StoryVO.Defaults());
        storyProperties.setModule(new StoryVO.Module());
        storyProperties.setConstraints(new StoryVO.Constraints());
        // re-inject since @InjectMocks ran before storyProperties was set
        service = new ChapterEditService(storyRepository, chapterSummaryService,
                chapterMemoryService, storyMemoryService, storyProperties,
                new cn.novel.yonren.domain.novel.service.armory.memory.ConsistencyIndexService());
    }

    @Test
    void reflow_success_replacesSummaryAndIndexes(@TempDir Path storyDir) throws Exception {
        List<ChapterSummaryEntity> summaries = new ArrayList<>(List.of(
                ChapterSummaryEntity.builder().chapterNo(1).title("第一章").summary("旧摘要").build()
        ));
        when(storyRepository.readChapterSummaries(storyDir)).thenReturn(summaries);
        when(storyRepository.readChapterPlanItem(storyDir, 1))
                .thenReturn(ChapterPlanItemEntity.builder().chapterNo(1).title("第一章").build());
        when(chapterMemoryService.renderLedgerPrompt(anyList())).thenReturn("角色：小唐。");
        when(chapterMemoryService.buildForeshadowContextList(anyList(), anyInt())).thenReturn(List.of());

        ChapterSummaryEntity newSummary = ChapterSummaryEntity.builder()
                .chapterNo(1).title("新标题").summary("新摘要").build();
        String content = "新".repeat(1500);
        when(chapterSummaryService.summarize(any(), any(), eq(content), eq(1), eq("角色：小唐。"), eq(List.of())))
                .thenReturn(newSummary);

        ChapterEditService.ReflowResult result = service.reflow(storyDir, 1, content);

        assertTrue(result.summaryUpdated());
        assertFalse(result.partial());
        assertEquals(null, result.warning());
        verify(storyRepository).writeChapterSummaries(eq(storyDir), any());
        verify(storyMemoryService).indexChapter(any(), eq(storyDir), any(), eq(1), eq(null));
    }

    @Test
    void reflow_clearsPendingEditMarkerForThisChapter(@TempDir Path storyDir) throws Exception {
        List<ChapterSummaryEntity> summaries = new ArrayList<>(List.of(
                ChapterSummaryEntity.builder().chapterNo(1).title("第一章").summary("旧摘要").build()));
        when(storyRepository.readChapterSummaries(storyDir)).thenReturn(summaries);
        when(storyRepository.readChapterPlanItem(storyDir, 1))
                .thenReturn(ChapterPlanItemEntity.builder().chapterNo(1).title("第一章").build());
        when(chapterMemoryService.renderLedgerPrompt(anyList())).thenReturn("账本");
        when(storyRepository.readPendingChapterEdit(storyDir))
                .thenReturn(new IStoryRepository.PendingChapterEdit(1, false));
        String content = "新".repeat(1500);
        when(chapterSummaryService.summarize(any(), any(), eq(content), eq(1), eq("账本"), anyList()))
                .thenReturn(ChapterSummaryEntity.builder().chapterNo(1).summary("新摘要").build());

        service.reflow(storyDir, 1, content);

        // 摘要已跟上正文，待回流标记必须清除，否则后续生成会一直认为记忆落后。
        verify(storyRepository).writePendingChapterEdit(storyDir, null);
    }

    @Test
    void reflow_keepsEarlierPendingEditWhenReflowingLaterChapter(@TempDir Path storyDir) throws Exception {
        List<ChapterSummaryEntity> summaries = new ArrayList<>(List.of(
                ChapterSummaryEntity.builder().chapterNo(1).title("第一章").summary("一").build(),
                ChapterSummaryEntity.builder().chapterNo(2).title("第二章").summary("二").build()));
        when(storyRepository.readChapterSummaries(storyDir)).thenReturn(summaries);
        when(storyRepository.readChapterPlanItem(storyDir, 2))
                .thenReturn(ChapterPlanItemEntity.builder().chapterNo(2).title("第二章").build());
        when(chapterMemoryService.renderLedgerPrompt(anyList())).thenReturn("账本");
        when(storyRepository.readPendingChapterEdit(storyDir))
                .thenReturn(new IStoryRepository.PendingChapterEdit(1, false));
        String content = "新".repeat(1500);
        when(chapterSummaryService.summarize(any(), any(), eq(content), eq(2), eq("账本"), anyList()))
                .thenReturn(ChapterSummaryEntity.builder().chapterNo(2).summary("新二").build());

        service.reflow(storyDir, 2, content);

        // 第 1 章的待回流仍然存在，不能因为重建了第 2 章就当作全部同步。
        verify(storyRepository, never()).writePendingChapterEdit(storyDir, null);
    }

    @Test
    void reflow_outOfBounds_returnsWarning(@TempDir Path storyDir) throws Exception {
        when(storyRepository.readChapterSummaries(storyDir))
                .thenReturn(new ArrayList<>(List.of(
                        ChapterSummaryEntity.builder().chapterNo(1).build())));

        ChapterEditService.ReflowResult result = service.reflow(storyDir, 5, "内容");

        assertFalse(result.summaryUpdated());
        assertNotNull(result.warning());
        assertTrue(result.warning().contains("越界"));
        verify(storyRepository, never()).writeChapterSummaries(any(), any());
    }

    @Test
    void reflow_summaryFailure_returnsWarning(@TempDir Path storyDir) throws Exception {
        when(storyRepository.readChapterSummaries(storyDir))
                .thenReturn(new ArrayList<>(List.of(
                        ChapterSummaryEntity.builder().chapterNo(1).build())));
        when(storyRepository.readChapterPlanItem(storyDir, 1)).thenReturn(null);
        when(chapterMemoryService.renderLedgerPrompt(anyList())).thenReturn(null);
        when(chapterMemoryService.buildForeshadowContextList(anyList(), anyInt())).thenReturn(List.of());
        when(chapterSummaryService.summarize(any(), any(), any(), anyInt(), any(), any()))
                .thenThrow(new RuntimeException("LLM 调用失败"));

        ChapterEditService.ReflowResult result = service.reflow(storyDir, 1, "正".repeat(1500));

        assertFalse(result.summaryUpdated());
        assertNotNull(result.warning());
        assertTrue(result.warning().contains("LLM 调用失败"));
    }

    @Test
    void reflow_missingPlanItem_constructsMinimal(@TempDir Path storyDir) throws Exception {
        when(storyRepository.readChapterSummaries(storyDir))
                .thenReturn(new ArrayList<>(List.of(
                        ChapterSummaryEntity.builder().chapterNo(1).build())));
        when(storyRepository.readChapterPlanItem(storyDir, 1)).thenReturn(null);
        when(chapterMemoryService.renderLedgerPrompt(anyList())).thenReturn(null);
        when(chapterMemoryService.buildForeshadowContextList(anyList(), anyInt())).thenReturn(List.of());
        when(chapterSummaryService.summarize(any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(ChapterSummaryEntity.builder().chapterNo(1).summary("ok").build());

        ChapterEditService.ReflowResult result = service.reflow(storyDir, 1, "正".repeat(1500));

        assertTrue(result.summaryUpdated());
    }

    /**
     * 空正文在回流前即拒绝（不烧一次模型调用）。
     *
     * 语义已变更：旧实现用 1500 字质量线拦回流，但那同时卡住了人工编辑保存
     * （整章不足 1500 字就改不了）。现在保存与回流只要求非空，1500 仍是生成路径的门禁。
     */
    @Test
    void reflow_blankContent_rejectedBeforeSummary(@TempDir Path storyDir) throws Exception {
        when(storyRepository.readChapterSummaries(storyDir)).thenReturn(new ArrayList<>(List.of(
                ChapterSummaryEntity.builder().chapterNo(1).build())));

        ChapterEditService.ReflowResult result = service.reflow(storyDir, 1, "   ");

        assertFalse(result.summaryUpdated());
        assertTrue(result.warning().contains("为空"), "空正文才拒绝，短正文不再拦");
        verify(chapterSummaryService, never()).summarize(any(), any(), any(), anyInt(), any(), any());
        verify(storyRepository, never()).writeChapterSummaries(any(), any());
    }

    /** 短正文（不足 1500 字）现在允许回流：人工编辑不该被生成质量线驳回 */
    @Test
    void reflow_shortContent_nowAllowed(@TempDir Path storyDir) throws Exception {
        when(storyRepository.readChapterSummaries(storyDir)).thenReturn(new ArrayList<>(List.of(
                ChapterSummaryEntity.builder().chapterNo(1).build())));
        when(chapterMemoryService.renderLedgerPrompt(anyList())).thenReturn("");
        when(chapterMemoryService.buildForeshadowContextListWithIds(anyList(), anyInt())).thenReturn(List.of());
        when(chapterSummaryService.summarize(any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(ChapterSummaryEntity.builder().chapterNo(1).summary("ok").build());

        ChapterEditService.ReflowResult result = service.reflow(storyDir, 1, "短正文也能回流");

        assertTrue(result.summaryUpdated(), "短正文不再被拒绝");
    }
}
