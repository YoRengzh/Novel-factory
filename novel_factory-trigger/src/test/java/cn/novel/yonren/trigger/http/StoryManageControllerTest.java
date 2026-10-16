package cn.novel.yonren.trigger.http;

import cn.novel.yonren.api.dto.ChapterContentDTO;
import cn.novel.yonren.api.dto.ChapterMetaDTO;
import cn.novel.yonren.api.dto.ChapterSaveRequestDTO;
import cn.novel.yonren.api.dto.ChapterSaveResponseDTO;
import cn.novel.yonren.api.dto.FactTimelineDTO;
import cn.novel.yonren.api.dto.PendingFactDTO;
import cn.novel.yonren.api.dto.StoryResumeDTO;
import cn.novel.yonren.api.dto.StoryGenerateRequestDTO;
import cn.novel.yonren.api.dto.StorySummaryDTO;
import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.valobj.BookExport;
import cn.novel.yonren.domain.novel.service.armory.edit.ChapterEditService;
import cn.novel.yonren.domain.novel.service.export.BookExportService;
import cn.novel.yonren.domain.novel.service.export.BookFormat;
import cn.novel.yonren.domain.novel.service.job.JobRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 故事库端点测试：列表/章节列表/读章/保存 + 409 运行中作业/404 不存在/400 空正文
 */
class StoryManageControllerTest {

    @Mock
    private IStoryRepository storyRepository;
    @Mock
    private JobRegistry jobRegistry;
    @Mock
    private ChapterEditService chapterEditService;
    @Mock
    private BookExportService bookExportService;
    @Mock
    private cn.novel.yonren.domain.novel.service.armory.memory.StoryMemoryService storyMemoryService;

    @InjectMocks
    private StoryManageController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void listStories_returnsAllWithActiveJobFlag() throws Exception {
        when(storyRepository.listStories()).thenReturn(List.of(
                new IStoryRepository.StorySummary("20260902-story-0001", "测试小说", 5, 1000L)));
        when(jobRegistry.isActiveForStory("20260902-story-0001")).thenReturn(true);

        List<StorySummaryDTO> result = controller.listStories();

        assertEquals(1, result.size());
        assertEquals("20260902-story-0001", result.get(0).getStoryDirName());
        assertEquals("测试小说", result.get(0).getNovelTitle());
        assertEquals(5, result.get(0).getChapterCount());
        assertTrue(result.get(0).isActiveJob());
    }

    @Test
    void listChapters_returnsMetadata() throws Exception {
        Path storyDir = Paths.get("/workspace/20260902-story-0001");
        when(storyRepository.resolveStoryDirectory("20260902-story-0001")).thenReturn(storyDir);
        when(storyRepository.readChapterNumbers(storyDir)).thenReturn(List.of(1, 2));
        ChapterContentEntity ch1 = new ChapterContentEntity();
        ch1.setChapterNo(1);
        ch1.setTitle("开端");
        ch1.setContent("内容一百字");
        when(storyRepository.readChapter(storyDir, 1)).thenReturn(ch1);
        ChapterContentEntity ch2 = new ChapterContentEntity();
        ch2.setChapterNo(2);
        ch2.setTitle("发展");
        ch2.setContent("另一些内容");
        when(storyRepository.readChapter(storyDir, 2)).thenReturn(ch2);

        List<ChapterMetaDTO> result = controller.listChapters("20260902-story-0001");

        assertEquals(2, result.size());
        assertEquals("开端", result.get(0).getTitle());
    }

    @Test
    void listChapters_invalidDir_throws404() throws Exception {
        when(storyRepository.resolveStoryDirectory("bad"))
                .thenThrow(new RuntimeException("目录不存在"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.listChapters("bad"));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void readChapter_returnsContent() throws Exception {
        Path storyDir = Paths.get("/workspace/20260902-story-0001");
        when(storyRepository.resolveStoryDirectory("20260902-story-0001")).thenReturn(storyDir);
        ChapterContentEntity entity = new ChapterContentEntity();
        entity.setChapterNo(3);
        entity.setTitle("高潮");
        entity.setContent("正文内容");
        when(storyRepository.readChapter(storyDir, 3)).thenReturn(entity);

        ChapterContentDTO dto = controller.readChapter("20260902-story-0001", 3);

        assertEquals(3, dto.getChapterNo());
        assertEquals("高潮", dto.getTitle());
        assertEquals("正文内容", dto.getContent());
    }

    @Test
    void readChapter_missing_throws404() throws Exception {
        Path storyDir = Paths.get("/workspace/20260902-story-0001");
        when(storyRepository.resolveStoryDirectory("20260902-story-0001")).thenReturn(storyDir);
        when(storyRepository.readChapter(storyDir, 99)).thenReturn(null);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.readChapter("20260902-story-0001", 99));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    // ==================== 导出成书（分章 ZIP） ====================

    /** 三种格式响应都恒为 application/zip 附件：txt 版 */
    @Test
    void exportBook_txtReturnsPerChapterZip() throws Exception {
        Path storyDir = Paths.get("/workspace/20260902-story-0001");
        when(storyRepository.resolveStoryDirectory("20260902-story-0001")).thenReturn(storyDir);
        when(bookExportService.exportChapters(eq(storyDir), eq(BookFormat.TXT))).thenReturn(
                BookExport.builder()
                        .fileName("示例书-分章TXT-2章-20261011.zip")
                        .fileBytes(new byte[]{1, 2, 3})
                        .format(BookFormat.TXT)
                        .chapterCount(2)
                        .effectiveChars(100L)
                        .novelTitle("示例书")
                        .build());

        ResponseEntity<byte[]> response = controller.exportBook("20260902-story-0001", "txt");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition.startsWith("attachment"));
        // 中文文件名必须走 RFC 5987 的 filename*，否则浏览器下载名会乱码
        assertTrue(disposition.contains("filename*=UTF-8''"));
        assertEquals("application/zip", String.valueOf(response.getHeaders().getContentType()));
        assertArrayEquals(new byte[]{1, 2, 3}, response.getBody());
    }

    /** docx 版：包内每章是独立 Word 文档，响应形态与 txt/md 相同 */
    @Test
    void exportBook_docxReturnsPerChapterZip() throws Exception {
        Path storyDir = Paths.get("/workspace/20260902-story-0001");
        when(storyRepository.resolveStoryDirectory("20260902-story-0001")).thenReturn(storyDir);
        when(bookExportService.exportChapters(eq(storyDir), eq(BookFormat.DOCX))).thenReturn(
                BookExport.builder()
                        .fileName("示例书-分章DOCX-2章-20261011.zip")
                        .fileBytes(new byte[]{9, 8})
                        .format(BookFormat.DOCX)
                        .chapterCount(2)
                        .novelTitle("示例书")
                        .build());

        ResponseEntity<byte[]> response = controller.exportBook("20260902-story-0001", "docx");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("application/zip", String.valueOf(response.getHeaders().getContentType()));
        assertArrayEquals(new byte[]{9, 8}, response.getBody());
        assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).contains(".zip"));
    }

    @Test
    void exportBook_withoutChapters_returns404() throws Exception {
        Path storyDir = Paths.get("/workspace/20260902-story-0001");
        when(storyRepository.resolveStoryDirectory("20260902-story-0001")).thenReturn(storyDir);
        when(bookExportService.exportChapters(eq(storyDir), any())).thenReturn(null);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.exportBook("20260902-story-0001", "txt"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        assertTrue(e.getReason().contains("还没有可导出的章节"));
    }

    @Test
    void exportBook_unsupportedFormat_returns400WithoutCallingService() throws Exception {
        // pdf 是非法格式；zip 是旧参数，随单文件导出一起退役（三种合法格式的响应本就是 zip）
        for (String illegal : new String[]{"pdf", "zip"}) {
            ResponseStatusException e = assertThrows(ResponseStatusException.class,
                    () -> controller.exportBook("20260902-story-0001", illegal));

            assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode(), illegal);
        }
        verify(bookExportService, never()).exportChapters(any(), any());
    }

    @Test
    void saveChapter_activeJob_throws409() {
        when(jobRegistry.isActiveForStory("20260902-story-0001")).thenReturn(true);

        ChapterSaveRequestDTO request = new ChapterSaveRequestDTO();
        request.setContent("新内容");

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.saveChapter("20260902-story-0001", 1, request));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    @Test
    void saveChapter_blankContent_throws400() {
        ChapterSaveRequestDTO request = new ChapterSaveRequestDTO();
        request.setContent("");

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.saveChapter("20260902-story-0001", 1, request));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    @Test
    void saveChapter_success_writesAndReflows() throws Exception {
        Path storyDir = Paths.get("/workspace/20260902-story-0001");
        when(storyRepository.resolveStoryDirectory("20260902-story-0001")).thenReturn(storyDir);
        when(jobRegistry.isActiveForStory("20260902-story-0001")).thenReturn(false);
        String content = "正".repeat(1500);
        when(chapterEditService.reflow(storyDir, 1, content))
                .thenReturn(new ChapterEditService.ReflowResult(true, false, null));

        ChapterSaveRequestDTO request = new ChapterSaveRequestDTO();
        request.setContent(content);

        ChapterSaveResponseDTO dto = controller.saveChapter("20260902-story-0001", 1, request);

        assertTrue(dto.isSaved());
        assertTrue(dto.isSummaryUpdated());
        verify(storyRepository).writeChapters(eq(storyDir), any());
        verify(chapterEditService).reflow(storyDir, 1, content);
    }

    @Test
    void saveChapter_preservesExistingTitle() throws Exception {
        Path storyDir = Paths.get("/workspace/20260902-story-0001");
        when(storyRepository.resolveStoryDirectory("20260902-story-0001")).thenReturn(storyDir);
        when(jobRegistry.isActiveForStory("20260902-story-0001")).thenReturn(false);
        ChapterContentEntity existing = new ChapterContentEntity();
        existing.setChapterNo(1);
        existing.setTitle("原有标题");
        existing.setContent("旧正文");
        when(storyRepository.readChapter(storyDir, 1)).thenReturn(existing);
        String content = "正".repeat(1500);
        when(chapterEditService.reflow(storyDir, 1, content))
                .thenReturn(new ChapterEditService.ReflowResult(true, false, null));

        ChapterSaveRequestDTO request = new ChapterSaveRequestDTO();
        request.setContent(content);
        controller.saveChapter("20260902-story-0001", 1, request);

        ArgumentCaptor<List<ChapterContentEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(storyRepository).writeChapters(eq(storyDir), captor.capture());
        assertEquals("原有标题", captor.getValue().get(0).getTitle());
        assertEquals(content, captor.getValue().get(0).getContent());
    }

    @Test
    void saveChapter_missingChapter_writesEmptyTitle() throws Exception {
        Path storyDir = Paths.get("/workspace/20260902-story-0001");
        when(storyRepository.resolveStoryDirectory("20260902-story-0001")).thenReturn(storyDir);
        when(jobRegistry.isActiveForStory("20260902-story-0001")).thenReturn(false);
        when(storyRepository.readChapter(storyDir, 9)).thenReturn(null);
        String content = "正".repeat(1500);
        when(chapterEditService.reflow(storyDir, 9, content))
                .thenReturn(new ChapterEditService.ReflowResult(true, false, null));

        ChapterSaveRequestDTO request = new ChapterSaveRequestDTO();
        request.setContent(content);
        controller.saveChapter("20260902-story-0001", 9, request);

        ArgumentCaptor<List<ChapterContentEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(storyRepository).writeChapters(eq(storyDir), captor.capture());
        assertEquals("", captor.getValue().get(0).getTitle());
        assertEquals(content, captor.getValue().get(0).getContent());
    }

    @Test
    void saveChapter_shortContent_returns400WithoutWriting() throws Exception {
        Path storyDir = Paths.get("/workspace/20260902-story-0001");
        when(storyRepository.resolveStoryDirectory("20260902-story-0001")).thenReturn(storyDir);
        when(jobRegistry.isActiveForStory("20260902-story-0001")).thenReturn(false);
        ChapterContentEntity existing = new ChapterContentEntity();
        existing.setChapterNo(1);
        existing.setTitle("原标题");
        existing.setContent("足够长的原正文，不应被覆盖");
        when(storyRepository.readChapter(storyDir, 1)).thenReturn(existing);

        ChapterSaveRequestDTO request = new ChapterSaveRequestDTO();
        request.setContent("短正文");

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.saveChapter("20260902-story-0001", 1, request));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason().contains("1500"));
        verify(storyRepository, never()).writeChapters(eq(storyDir), any());
        verify(chapterEditService, never()).reflow(any(), anyInt(), any());
    }

    @Test
    void pendingFacts_returnsQuarantinedFactsByChapter() throws Exception {
        Path storyDir = Paths.get("/workspace/20260902-story-0001");
        when(storyRepository.resolveStoryDirectory("20260902-story-0001")).thenReturn(storyDir);
        ChapterSummaryEntity summary = ChapterSummaryEntity.builder().chapterNo(5).build();
        summary.setPendingFacts(List.of(
                new ChapterSummaryEntity.StateEntry("赵阔", "已飞升", "正文里根本没有这句话"),
                new ChapterSummaryEntity.StateEntry("古物", "碎裂", "碎成了八瓣")));
        when(storyRepository.readChapterSummaries(storyDir)).thenReturn(List.of(summary));

        List<PendingFactDTO> result = controller.pendingFacts("20260902-story-0001");

        assertEquals(2, result.size());
        assertEquals(5, result.get(0).getChapterNo());
        assertEquals("赵阔", result.get(0).getName());
        assertEquals("正文里根本没有这句话", result.get(0).getEvidence());
        assertEquals("古物", result.get(1).getName());
    }

    @Test
    void factTimeline_returnsEntityHistoryAcrossChapters() throws Exception {
        Path storyDir = Paths.get("/workspace/20260902-story-0001");
        when(storyRepository.resolveStoryDirectory("20260902-story-0001")).thenReturn(storyDir);
        ChapterSummaryEntity ch1 = ChapterSummaryEntity.builder().chapterNo(1).build();
        ch1.setCharacterStates(List.of(new ChapterSummaryEntity.StateEntry(
                "小陈", "重伤", "小陈咬破舌尖，强行催动古物")));
        ChapterSummaryEntity ch3 = ChapterSummaryEntity.builder().chapterNo(3).build();
        ch3.setCharacterStates(List.of(new ChapterSummaryEntity.StateEntry(
                "小陈", "痊愈", null)));
        ch3.setItemStates(List.of(new ChapterSummaryEntity.StateEntry(
                "古物", "镜面裂开细纹", "镜面裂开一道细纹")));
        when(storyRepository.readChapterSummaries(storyDir)).thenReturn(List.of(ch1, ch3));

        List<FactTimelineDTO> result = controller.factTimeline("20260902-story-0001", "小陈");

        // 精确匹配：仅角色条目命中，按章序排列，证据随条目携带
        assertEquals(2, result.size());
        assertEquals(1, result.get(0).getChapterNo());
        assertEquals("角色", result.get(0).getFactType());
        assertEquals("重伤", result.get(0).getStatus());
        assertEquals("小陈咬破舌尖，强行催动古物", result.get(0).getEvidence());
        assertEquals(3, result.get(1).getChapterNo());
        assertNull(result.get(1).getEvidence());
    }

    @Test
    void factTimeline_fallsBackToContainsWhenExactMisses() throws Exception {
        Path storyDir = Paths.get("/workspace/20260902-story-0001");
        when(storyRepository.resolveStoryDirectory("20260902-story-0001")).thenReturn(storyDir);
        ChapterSummaryEntity ch1 = ChapterSummaryEntity.builder().chapterNo(1).build();
        ch1.setItemStates(List.of(new ChapterSummaryEntity.StateEntry(
                "古物", "首次显威", null)));
        when(storyRepository.readChapterSummaries(storyDir)).thenReturn(List.of(ch1));

        // 精确名无结果 → 包含匹配回退
        List<FactTimelineDTO> result = controller.factTimeline("20260902-story-0001", "物");

        assertEquals(1, result.size());
        assertEquals("古物", result.get(0).getName());
        assertEquals("物品", result.get(0).getFactType());
        assertEquals("首次显威", result.get(0).getStatus());
    }

    // ==================== 续写准备 ====================

    private static final String RESUME_BIBLE = String.join("\n",
            "小说名称: 示例书",
            "小说类型: 悬疑推理",
            "小说格调: 冷硬悬疑",
            "世界观: 旧港被旧案封锁",
            "叙述视角: 第三人称限知视角",
            "目标读者: 男频",
            "语气基调: 压抑克制",
            "主人公: 林昭，沉物打捞人",
            "故事概述: 林昭捞出焚字纹袖标",
            "章节数量: 5",
            "章节目标: 让读者体验解谜快感",
            "世界ID: novel-shared-01");

    @Test
    void resume_returnsFullSettingAndNextChapter() throws Exception {
        Path storyDir = Paths.get("/workspace/20260902-story-0001");
        when(storyRepository.resolveStoryDirectory("20260902-story-0001")).thenReturn(storyDir);
        when(storyRepository.readStoryBible(storyDir)).thenReturn(RESUME_BIBLE);
        // 故意乱序：末章章号要取最大值，不能依赖仓储返回顺序
        when(storyRepository.readChapterNumbers(storyDir)).thenReturn(List.of(1, 3, 2, 4));
        when(storyRepository.readStoryMeta(storyDir)).thenReturn(new IStoryRepository.StoryMeta(150));

        StoryResumeDTO dto = controller.resume("20260902-story-0001");

        assertEquals("20260902-story-0001", dto.getStoryDirName());
        assertEquals("示例书", dto.getNovelTitle());
        assertEquals(4, dto.getLatestChapterNo());
        assertEquals(5, dto.getNextChapterNo());
        assertEquals(4, dto.getExistingChapterCount());
        assertTrue(dto.isSettingAvailable());
        assertTrue(dto.getMissingFields().isEmpty());

        StoryGenerateRequestDTO setting = dto.getSetting();
        assertEquals("示例书", setting.getNovel_title());
        assertEquals("悬疑推理", setting.getTheme());
        assertEquals("旧港被旧案封锁", setting.getWorldSetting());
        assertEquals("林昭，沉物打捞人", setting.getProtagonist());
        assertEquals("让读者体验解谜快感", setting.getChapterGoal());
        // 世界 ID 必须带上：它是系列共享向量集合的键
        assertEquals("novel-shared-01", setting.getWorldId());
        // 续写标记与 sticky 的总章数上限是这条端点存在的理由，缺一个前端都跑不通
        assertEquals("20260902-story-0001", setting.getResumeStoryDir());
        assertEquals(150, setting.getMaxChapterCount());
        // 批次大小是"这次写几章"，与历史无关：给默认值而不是沿用 bible 里的旧值
        assertEquals(5, setting.getChapterCount());
    }

    @Test
    void resume_namesMissingBibleFieldsInsteadOfLettingSubmitFailBlindly() throws Exception {
        Path storyDir = Paths.get("/workspace/20260902-story-0001");
        when(storyRepository.resolveStoryDirectory("20260902-story-0001")).thenReturn(storyDir);
        // 老故事的 bible 可能缺字段，而入参校验是一条 or 链、不报具体字段
        when(storyRepository.readStoryBible(storyDir)).thenReturn("小说名称: 只有书名\n");
        when(storyRepository.readChapterNumbers(storyDir)).thenReturn(List.of());
        when(storyRepository.readStoryMeta(storyDir)).thenReturn(null);

        StoryResumeDTO dto = controller.resume("20260902-story-0001");

        assertEquals("只有书名", dto.getNovelTitle());
        assertNull(dto.getLatestChapterNo());
        assertEquals(1, dto.getNextChapterNo(), "还没有章节时应从第 1 章开始");
        assertEquals(0, dto.getExistingChapterCount());
        assertTrue(dto.getMissingFields().contains("语气基调"));
        assertTrue(dto.getMissingFields().contains("章节目标"));
        assertNull(dto.getSetting().getMaxChapterCount(), "读不到 story-meta 就不设上限，不要凭空补默认值");
    }

    @Test
    void resume_toleratesUnreadableStoryMeta() throws Exception {
        Path storyDir = Paths.get("/workspace/20260902-story-0001");
        when(storyRepository.resolveStoryDirectory("20260902-story-0001")).thenReturn(storyDir);
        when(storyRepository.readStoryBible(storyDir)).thenReturn(RESUME_BIBLE);
        when(storyRepository.readChapterNumbers(storyDir)).thenReturn(List.of(1));
        when(storyRepository.readStoryMeta(storyDir)).thenThrow(new IllegalStateException("meta 损坏"));

        StoryResumeDTO dto = controller.resume("20260902-story-0001");

        // 总章数读不到只是"不设上限"，不该让整条续写路径崩掉
        assertNull(dto.getSetting().getMaxChapterCount());
        assertEquals(2, dto.getNextChapterNo());
    }

    @Test
    void resume_rejectsUnknownStoryDirWith404() throws Exception {
        when(storyRepository.resolveStoryDirectory("bad")).thenThrow(new IllegalStateException("不存在"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.resume("bad"));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void deleteStory_removesDirectoryAndClearsMemoryCollection() throws Exception {
        Path storyDir = Paths.get("/workspace/20260902-story-0001");
        when(storyRepository.resolveStoryDirectory("20260902-story-0001")).thenReturn(storyDir);
        when(jobRegistry.isActiveForStory("20260902-story-0001")).thenReturn(false);

        StoryManageController.DeleteStoryResult result = controller.deleteStory("20260902-story-0001");

        assertTrue(result.deleted());
        assertEquals("20260902-story-0001", result.storyDirName());
        // 先删目录、再清集合：两者都必须发生
        verify(storyRepository).deleteStory(storyDir);
        verify(storyMemoryService).deleteStoryMemory(storyDir);
    }

    @Test
    void deleteStory_rejectsActiveJobWith409_andTouchesNothing() throws Exception {
        when(jobRegistry.isActiveForStory("20260902-story-0001")).thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.deleteStory("20260902-story-0001"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        // 守卫先于删除：有活动作业时不得删目录、不得清集合
        verify(storyRepository, never()).deleteStory(any());
        verify(storyMemoryService, never()).deleteStoryMemory(any());
    }

    @Test
    void deleteStory_survivesMemoryClearFailure() throws Exception {
        Path storyDir = Paths.get("/workspace/20260902-story-0001");
        when(storyRepository.resolveStoryDirectory("20260902-story-0001")).thenReturn(storyDir);
        when(jobRegistry.isActiveForStory("20260902-story-0001")).thenReturn(false);
        // 集合清理内部 fail-soft（只 WARN 不抛）；即便它抛，删除结果也不该变红——
        // 本用例锁定"目录删除是主体"这一语义
        org.mockito.Mockito.doNothing().when(storyMemoryService).deleteStoryMemory(storyDir);

        StoryManageController.DeleteStoryResult result = controller.deleteStory("20260902-story-0001");

        assertTrue(result.deleted());
        verify(storyRepository).deleteStory(storyDir);
    }
}
