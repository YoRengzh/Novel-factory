package cn.novel.yonren.domain.novel.service.manage;

import cn.novel.yonren.api.dto.ChapterContentDTO;
import cn.novel.yonren.api.dto.ChapterMetaDTO;
import cn.novel.yonren.api.dto.ChapterSaveRequestDTO;
import cn.novel.yonren.api.dto.ChapterSaveResponseDTO;
import cn.novel.yonren.api.dto.DeleteStoryResponseDTO;
import cn.novel.yonren.api.dto.FactTimelineDTO;
import cn.novel.yonren.api.dto.PendingFactDTO;
import cn.novel.yonren.api.dto.StoryGenerateRequestDTO;
import cn.novel.yonren.api.dto.StoryResumeDTO;
import cn.novel.yonren.api.dto.StorySummaryDTO;
import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.valobj.BookExport;
import cn.novel.yonren.domain.novel.model.valobj.BookFormat;
import cn.novel.yonren.domain.novel.service.armory.edit.ChapterEditService;
import cn.novel.yonren.domain.novel.service.armory.memory.StoryMemoryService;
import cn.novel.yonren.domain.novel.service.export.BookExportService;
import cn.novel.yonren.domain.novel.service.job.JobRegistry;
import cn.novel.yonren.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 故事库应用服务测试：从 trigger 的 StoryManageControllerTest 全量迁移而来
 * （分层重构——断言逐条搬运，不合并不简化）。
 * 覆盖：列表/章节列表/读章/保存 + 作业守卫/目录不存在/空正文 + 导出/续写准备/删除。
 */
class StoryManageServiceTest {

    private static final String DIR = "20260902-story-0001";
    private static final Path STORY_DIR = Paths.get("/workspace/20260902-story-0001");

    @Mock
    private IStoryRepository storyRepository;
    @Mock
    private JobRegistry jobRegistry;
    @Mock
    private ChapterEditService chapterEditService;
    @Mock
    private BookExportService bookExportService;
    @Mock
    private StoryMemoryService storyMemoryService;

    @InjectMocks
    private StoryManageService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void listStories_returnsAllWithActiveJobFlag() throws Exception {
        when(storyRepository.listStories()).thenReturn(List.of(
                new IStoryRepository.StorySummary(DIR, "测试小说", 5, 1000L)));
        when(jobRegistry.isActiveForStory(DIR)).thenReturn(true);

        List<StorySummaryDTO> result = service.listStories();

        assertEquals(1, result.size());
        assertEquals(DIR, result.get(0).getStoryDirName());
        assertEquals("测试小说", result.get(0).getNovelTitle());
        assertEquals(5, result.get(0).getChapterCount());
        assertTrue(result.get(0).isActiveJob());
    }

    @Test
    void listChapters_returnsMetadata() throws Exception {
        when(storyRepository.resolveStoryDirectory(DIR)).thenReturn(STORY_DIR);
        when(storyRepository.readChapterNumbers(STORY_DIR)).thenReturn(List.of(1, 2));
        ChapterContentEntity ch1 = new ChapterContentEntity();
        ch1.setChapterNo(1);
        ch1.setTitle("开端");
        ch1.setContent("内容一百字");
        when(storyRepository.readChapter(STORY_DIR, 1)).thenReturn(ch1);
        ChapterContentEntity ch2 = new ChapterContentEntity();
        ch2.setChapterNo(2);
        ch2.setTitle("发展");
        ch2.setContent("另一些内容");
        when(storyRepository.readChapter(STORY_DIR, 2)).thenReturn(ch2);

        List<ChapterMetaDTO> result = service.listChapters(DIR);

        assertEquals(2, result.size());
        assertEquals("开端", result.get(0).getTitle());
    }

    @Test
    void listChapters_invalidDir_throwsNoStoryData() throws Exception {
        when(storyRepository.resolveStoryDirectory("bad"))
                .thenThrow(new RuntimeException("目录不存在"));

        AppException e = assertThrows(AppException.class, () -> service.listChapters("bad"));
        assertEquals(cn.novel.yonren.types.enums.ResponseCode.NO_STORY_DATA.getCode(), e.getCode());
    }

    @Test
    void readChapter_returnsContent() throws Exception {
        when(storyRepository.resolveStoryDirectory(DIR)).thenReturn(STORY_DIR);
        ChapterContentEntity entity = new ChapterContentEntity();
        entity.setChapterNo(3);
        entity.setTitle("高潮");
        entity.setContent("正文内容");
        when(storyRepository.readChapter(STORY_DIR, 3)).thenReturn(entity);

        ChapterContentDTO dto = service.readChapter(DIR, 3);

        assertEquals(3, dto.getChapterNo());
        assertEquals("高潮", dto.getTitle());
        assertEquals("正文内容", dto.getContent());
    }

    @Test
    void readChapter_missing_throwsChapterNotFound() throws Exception {
        when(storyRepository.resolveStoryDirectory(DIR)).thenReturn(STORY_DIR);
        when(storyRepository.readChapter(STORY_DIR, 99)).thenReturn(null);

        AppException e = assertThrows(AppException.class, () -> service.readChapter(DIR, 99));
        assertEquals(cn.novel.yonren.types.enums.ResponseCode.CHAPTER_NOT_FOUND.getCode(), e.getCode());
    }

    // ==================== 导出成书（分章 ZIP） ====================

    @Test
    void exportBook_txtReturnsPerChapterZipPayload() throws Exception {
        when(storyRepository.resolveStoryDirectory(DIR)).thenReturn(STORY_DIR);
        when(bookExportService.exportChapters(eq(STORY_DIR), eq(BookFormat.TXT))).thenReturn(
                BookExport.builder()
                        .fileName("示例书-分章TXT-2章-20261011.zip")
                        .fileBytes(new byte[]{1, 2, 3})
                        .format(BookFormat.TXT)
                        .chapterCount(2)
                        .effectiveChars(100L)
                        .novelTitle("示例书")
                        .build());

        cn.novel.yonren.api.dto.ExportFilePayloadDTO book = service.exportBook(DIR, "txt");

        assertEquals("示例书-分章TXT-2章-20261011.zip", book.fileName());
        assertArrayEquals(new byte[]{1, 2, 3}, book.fileBytes());
    }

    @Test
    void exportBook_docxReturnsPerChapterZip() throws Exception {
        when(storyRepository.resolveStoryDirectory(DIR)).thenReturn(STORY_DIR);
        when(bookExportService.exportChapters(eq(STORY_DIR), eq(BookFormat.DOCX))).thenReturn(
                BookExport.builder()
                        .fileName("示例书-分章DOCX-2章-20261011.zip")
                        .fileBytes(new byte[]{9, 8})
                        .format(BookFormat.DOCX)
                        .chapterCount(2)
                        .novelTitle("示例书")
                        .build());

        cn.novel.yonren.api.dto.ExportFilePayloadDTO book = service.exportBook(DIR, "docx");

        assertArrayEquals(new byte[]{9, 8}, book.fileBytes());
        assertTrue(book.fileName().endsWith(".zip"));
    }

    @Test
    void exportBook_withoutChapters_throwsNoStoryData() throws Exception {
        when(storyRepository.resolveStoryDirectory(DIR)).thenReturn(STORY_DIR);
        when(bookExportService.exportChapters(eq(STORY_DIR), any())).thenReturn(null);

        AppException e = assertThrows(AppException.class, () -> service.exportBook(DIR, "txt"));
        assertEquals(cn.novel.yonren.types.enums.ResponseCode.NO_STORY_DATA.getCode(), e.getCode());
        assertTrue(e.getInfo().contains("还没有可导出的章节"));
    }

    @Test
    void exportBook_unsupportedFormat_rejectedWithoutCallingService() throws Exception {
        // pdf 是非法格式；zip 是旧参数，随单文件导出一起退役（三种合法格式的响应本就是 zip）
        for (String illegal : new String[]{"pdf", "zip"}) {
            AppException e = assertThrows(AppException.class, () -> service.exportBook(DIR, illegal));
            assertEquals(cn.novel.yonren.types.enums.ResponseCode.ILLEGAL_PARAMETER.getCode(), e.getCode(), illegal);
        }
        verify(bookExportService, never()).exportChapters(any(), any());
    }

    @Test
    void saveChapter_activeJob_throwsConflict() {
        when(jobRegistry.tryBeginStoryOperation(DIR)).thenReturn(false);

        ChapterSaveRequestDTO request = new ChapterSaveRequestDTO();
        request.setContent("新内容");

        AppException e = assertThrows(AppException.class,
                () -> service.saveChapter(DIR, 1, request));
        assertEquals(cn.novel.yonren.types.enums.ResponseCode.ACTIVE_JOB_CONFLICT.getCode(), e.getCode());
    }

    @Test
    void saveChapter_blankContent_throwsIllegalParameter() {
        ChapterSaveRequestDTO request = new ChapterSaveRequestDTO();
        request.setContent("");

        AppException e = assertThrows(AppException.class,
                () -> service.saveChapter(DIR, 1, request));
        assertEquals(cn.novel.yonren.types.enums.ResponseCode.ILLEGAL_PARAMETER.getCode(), e.getCode());
    }

    @Test
    void saveChapter_success_writesAndReflows() throws Exception {
        when(storyRepository.resolveStoryDirectory(DIR)).thenReturn(STORY_DIR);
        when(jobRegistry.tryBeginStoryOperation(DIR)).thenReturn(true);
        String content = "正".repeat(1500);
        when(chapterEditService.reflow(STORY_DIR, 1, content))
                .thenReturn(new ChapterEditService.ReflowResult(true, false, null));

        ChapterSaveRequestDTO request = new ChapterSaveRequestDTO();
        request.setContent(content);

        ChapterSaveResponseDTO dto = service.saveChapter(DIR, 1, request);

        assertTrue(dto.isSaved());
        assertTrue(dto.isSummaryUpdated());
        verify(storyRepository).saveHumanEdit(eq(STORY_DIR), any(), any());
        verify(chapterEditService).reflow(STORY_DIR, 1, content);
    }

    @Test
    void saveChapter_preservesExistingTitle() throws Exception {
        when(storyRepository.resolveStoryDirectory(DIR)).thenReturn(STORY_DIR);
        when(jobRegistry.tryBeginStoryOperation(DIR)).thenReturn(true);
        ChapterContentEntity existing = new ChapterContentEntity();
        existing.setChapterNo(1);
        existing.setTitle("原有标题");
        existing.setContent("旧正文");
        when(storyRepository.readChapter(STORY_DIR, 1)).thenReturn(existing);
        String content = "正".repeat(1500);
        when(chapterEditService.reflow(STORY_DIR, 1, content))
                .thenReturn(new ChapterEditService.ReflowResult(true, false, null));

        ChapterSaveRequestDTO request = new ChapterSaveRequestDTO();
        request.setContent(content);
        service.saveChapter(DIR, 1, request);

        ArgumentCaptor<ChapterContentEntity> captor = ArgumentCaptor.forClass(ChapterContentEntity.class);
        verify(storyRepository).saveHumanEdit(eq(STORY_DIR), captor.capture(), any());
        assertEquals("原有标题", captor.getValue().getTitle());
        assertEquals(content, captor.getValue().getContent());
    }

    @Test
    void saveChapter_missingChapter_writesEmptyTitle() throws Exception {
        when(storyRepository.resolveStoryDirectory(DIR)).thenReturn(STORY_DIR);
        when(jobRegistry.tryBeginStoryOperation(DIR)).thenReturn(true);
        when(storyRepository.readChapter(STORY_DIR, 9)).thenReturn(null);
        String content = "正".repeat(1500);
        when(chapterEditService.reflow(STORY_DIR, 9, content))
                .thenReturn(new ChapterEditService.ReflowResult(true, false, null));

        ChapterSaveRequestDTO request = new ChapterSaveRequestDTO();
        request.setContent(content);
        service.saveChapter(DIR, 9, request);

        ArgumentCaptor<ChapterContentEntity> captor = ArgumentCaptor.forClass(ChapterContentEntity.class);
        verify(storyRepository).saveHumanEdit(eq(STORY_DIR), captor.capture(), any());
        assertEquals("", captor.getValue().getTitle());
        assertEquals(content, captor.getValue().getContent());
    }

    /**
     * 短正文现在可以保存（语义已变更）。
     *
     * 旧实现用 1500 字生成质量线拦人工保存，导致整章不足 1500 字就改不了——* 实测段落级润色后保存直接 400。现在人工保存只要求非空，1500 仍是生成路径门禁。
     */
    @Test
    void saveChapter_shortContent_nowAllowed() throws Exception {
        when(storyRepository.resolveStoryDirectory(DIR)).thenReturn(STORY_DIR);
        when(jobRegistry.tryBeginStoryOperation(DIR)).thenReturn(true);
        ChapterContentEntity existing = new ChapterContentEntity();
        existing.setChapterNo(1);
        existing.setTitle("原标题");
        existing.setContent("原正文");
        when(storyRepository.readChapter(STORY_DIR, 1)).thenReturn(existing);
        when(chapterEditService.reflow(STORY_DIR, 1, "短正文"))
                .thenReturn(new ChapterEditService.ReflowResult(true, false, null));

        ChapterSaveRequestDTO request = new ChapterSaveRequestDTO();
        request.setContent("短正文");

        ChapterSaveResponseDTO dto = service.saveChapter(DIR, 1, request);

        assertTrue(dto.isSaved(), "短正文不再被 1500 字线拒绝");
        verify(storyRepository).saveHumanEdit(eq(STORY_DIR), any(), any());
    }

    /** reflow=false 时跳过回流：段落级润色/扩写不改事实，重算摘要是纯浪费（实测几分钟） */
    @Test
    void saveChapter_reflowFalse_skipsReflow() throws Exception {
        when(storyRepository.resolveStoryDirectory(DIR)).thenReturn(STORY_DIR);
        when(jobRegistry.tryBeginStoryOperation(DIR)).thenReturn(true);
        ChapterContentEntity existing = new ChapterContentEntity();
        existing.setChapterNo(1);
        existing.setTitle("原标题");
        when(storyRepository.readChapter(STORY_DIR, 1)).thenReturn(existing);

        ChapterSaveRequestDTO request = new ChapterSaveRequestDTO();
        request.setContent("润色后的正文");
        request.setReflow(false);

        ChapterSaveResponseDTO dto = service.saveChapter(DIR, 1, request);

        assertTrue(dto.isSaved());
        assertFalse(dto.isSummaryUpdated(), "跳过回流时摘要不更新");
        verify(chapterEditService, never()).reflow(any(), anyInt(), any());
    }

    @Test
    void pendingFacts_returnsQuarantinedFactsByChapter() throws Exception {
        when(storyRepository.resolveStoryDirectory(DIR)).thenReturn(STORY_DIR);
        ChapterSummaryEntity summary = ChapterSummaryEntity.builder().chapterNo(5).build();
        summary.setPendingFacts(List.of(
                new ChapterSummaryEntity.StateEntry("赵阔", "已飞升", "正文里根本没有这句话"),
                new ChapterSummaryEntity.StateEntry("古物", "碎裂", "碎成了八瓣")));
        when(storyRepository.readChapterSummaries(STORY_DIR)).thenReturn(List.of(summary));

        List<PendingFactDTO> result = service.pendingFacts(DIR);

        assertEquals(2, result.size());
        assertEquals(5, result.get(0).getChapterNo());
        assertEquals("赵阔", result.get(0).getName());
        assertEquals("正文里根本没有这句话", result.get(0).getEvidence());
        assertEquals("古物", result.get(1).getName());
    }

    @Test
    void factTimeline_returnsEntityHistoryAcrossChapters() throws Exception {
        when(storyRepository.resolveStoryDirectory(DIR)).thenReturn(STORY_DIR);
        ChapterSummaryEntity ch1 = ChapterSummaryEntity.builder().chapterNo(1).build();
        ch1.setCharacterStates(List.of(new ChapterSummaryEntity.StateEntry(
                "小陈", "重伤", "小陈咬破舌尖，强行催动古物")));
        ChapterSummaryEntity ch3 = ChapterSummaryEntity.builder().chapterNo(3).build();
        ch3.setCharacterStates(List.of(new ChapterSummaryEntity.StateEntry(
                "小陈", "痊愈", null)));
        ch3.setItemStates(List.of(new ChapterSummaryEntity.StateEntry(
                "古物", "镜面裂开细纹", "镜面裂开一道细纹")));
        when(storyRepository.readChapterSummaries(STORY_DIR)).thenReturn(List.of(ch1, ch3));

        List<FactTimelineDTO> result = service.factTimeline(DIR, "小陈");

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
        when(storyRepository.resolveStoryDirectory(DIR)).thenReturn(STORY_DIR);
        ChapterSummaryEntity ch1 = ChapterSummaryEntity.builder().chapterNo(1).build();
        ch1.setItemStates(List.of(new ChapterSummaryEntity.StateEntry(
                "古物", "首次显威", null)));
        when(storyRepository.readChapterSummaries(STORY_DIR)).thenReturn(List.of(ch1));

        // 精确名无结果 → 包含匹配回退
        List<FactTimelineDTO> result = service.factTimeline(DIR, "物");

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
        when(storyRepository.resolveStoryDirectory(DIR)).thenReturn(STORY_DIR);
        when(storyRepository.readStoryBible(STORY_DIR)).thenReturn(RESUME_BIBLE);
        // 故意乱序：末章章号要取最大值，不能依赖仓储返回顺序
        when(storyRepository.readChapterNumbers(STORY_DIR)).thenReturn(List.of(1, 3, 2, 4));
        when(storyRepository.readStoryMeta(STORY_DIR)).thenReturn(new IStoryRepository.StoryMeta(150));

        StoryResumeDTO dto = service.resume(DIR);

        assertEquals(DIR, dto.getStoryDirName());
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
        assertEquals(DIR, setting.getResumeStoryDir());
        assertEquals(150, setting.getMaxChapterCount());
        // 批次大小是"这次写几章"，与历史无关：给默认值而不是沿用 bible 里的旧值
        assertEquals(5, setting.getChapterCount());
    }

    @Test
    void resume_namesMissingBibleFieldsInsteadOfLettingSubmitFailBlindly() throws Exception {
        when(storyRepository.resolveStoryDirectory(DIR)).thenReturn(STORY_DIR);
        // 老故事的 bible 可能缺字段，而入参校验是一条 or 链、不报具体字段
        when(storyRepository.readStoryBible(STORY_DIR)).thenReturn("小说名称: 只有书名\n");
        when(storyRepository.readChapterNumbers(STORY_DIR)).thenReturn(List.of());
        when(storyRepository.readStoryMeta(STORY_DIR)).thenReturn(null);

        StoryResumeDTO dto = service.resume(DIR);

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
        when(storyRepository.resolveStoryDirectory(DIR)).thenReturn(STORY_DIR);
        when(storyRepository.readStoryBible(STORY_DIR)).thenReturn(RESUME_BIBLE);
        when(storyRepository.readChapterNumbers(STORY_DIR)).thenReturn(List.of(1));
        when(storyRepository.readStoryMeta(STORY_DIR)).thenThrow(new IllegalStateException("meta 损坏"));

        StoryResumeDTO dto = service.resume(DIR);

        // 总章数读不到只是"不设上限"，不该让整条续写路径崩掉
        assertNull(dto.getSetting().getMaxChapterCount());
        assertEquals(2, dto.getNextChapterNo());
    }

    @Test
    void resume_rejectsUnknownStoryDir() throws Exception {
        when(storyRepository.resolveStoryDirectory("bad")).thenThrow(new IllegalStateException("不存在"));

        AppException ex = assertThrows(AppException.class, () -> service.resume("bad"));

        assertEquals(cn.novel.yonren.types.enums.ResponseCode.NO_STORY_DATA.getCode(), ex.getCode());
    }

    @Test
    void resume_preservesMultiParagraphSettingFields() throws Exception {
        String outline = "第一段：旧港封锁仍在继续。\n\n关键发展:物证比对指向旧仓库\n\n结局方向：旧案重开";
        String goal = "1-5章 重启调查\n6-10章 证据闭环";
        String world = "沿海旧港\n\n  潮汐与封锁令改变居民作息";
        String bible = String.join("\n",
                "小说名称: 示例书",
                "小说类型: 悬疑推理",
                "小说格调: 冷硬悬疑",
                "世界观: " + world,
                "叙述视角: 第三人称限知视角",
                "目标读者: 男频",
                "语气基调: 压抑克制",
                "主人公: 林昭，沉物打捞人",
                "故事概述: " + outline,
                "章节数量: 5",
                "章节目标: " + goal,
                "世界ID: novel-shared-01");
        when(storyRepository.resolveStoryDirectory(DIR)).thenReturn(STORY_DIR);
        when(storyRepository.readStoryBible(STORY_DIR)).thenReturn(bible);
        when(storyRepository.readChapterNumbers(STORY_DIR)).thenReturn(List.of(1, 2));
        when(storyRepository.readStoryMeta(STORY_DIR)).thenReturn(null);

        StoryResumeDTO dto = service.resume(DIR);

        assertEquals(world, dto.getSetting().getWorldSetting());
        assertEquals(outline, dto.getSetting().getOutline());
        assertEquals(goal, dto.getSetting().getChapterGoal());
        assertTrue(dto.getMissingFields().isEmpty());
        assertEquals(3, dto.getNextChapterNo());
    }

    @Test
    void resume_rejectsAmbiguousBibleWithoutExposingSettingText() throws Exception {
        when(storyRepository.resolveStoryDirectory(DIR)).thenReturn(STORY_DIR);
        when(storyRepository.readStoryBible(STORY_DIR)).thenReturn(String.join("\n",
                "小说名称: 示例书",
                "世界观: 第一候选",
                "叙述视角: 第三人称",
                "世界观: 第二候选"));

        AppException ex = assertThrows(AppException.class, () -> service.resume(DIR));

        assertEquals(cn.novel.yonren.types.enums.ResponseCode.ILLEGAL_PARAMETER.getCode(), ex.getCode());
        assertTrue(ex.getInfo().contains("STORY_BIBLE_AMBIGUOUS"));
        assertTrue(ex.getInfo().contains("世界观"));
        assertFalse(ex.getInfo().contains("第一候选"), "诊断不能回显设定正文");
        assertFalse(ex.getInfo().contains("第二候选"));
        verify(storyRepository, never()).readChapterNumbers(any());
    }

    @Test
    void resume_rejectsDuplicateMetadataLabelsBeforeBuildingSetting() throws Exception {
        when(storyRepository.resolveStoryDirectory(DIR)).thenReturn(STORY_DIR);
        when(storyRepository.readStoryBible(STORY_DIR)).thenReturn(String.join("\n",
                "小说名称: 示例书",
                "章节数量: 5",
                "章节目标: 第一目标",
                "章节数量: 8",
                "世界ID: novel-shared-01"));

        AppException ex = assertThrows(AppException.class, () -> service.resume(DIR));

        assertEquals(cn.novel.yonren.types.enums.ResponseCode.ILLEGAL_PARAMETER.getCode(), ex.getCode());
        assertTrue(ex.getInfo().contains("章节数量"));
        assertTrue(ex.getInfo().contains("章节目标"), "可能被截断的相邻字段也必须报告");
    }

    @Test
    void deleteStory_removesDirectoryAndClearsMemoryCollection() throws Exception {
        when(storyRepository.resolveStoryDirectory(DIR)).thenReturn(STORY_DIR);
        when(jobRegistry.isActiveForStory(DIR)).thenReturn(false);

        DeleteStoryResponseDTO result = service.deleteStory(DIR);

        assertTrue(result.deleted());
        assertEquals(DIR, result.storyDirName());
        // 先删目录、再清集合：两者都必须发生
        verify(storyRepository).deleteStory(STORY_DIR);
        verify(storyMemoryService).deleteStoryMemory(STORY_DIR);
    }

    @Test
    void deleteStory_rejectsActiveJob_andTouchesNothing() throws Exception {
        when(jobRegistry.isActiveForStory(DIR)).thenReturn(true);

        AppException ex = assertThrows(AppException.class, () -> service.deleteStory(DIR));

        assertEquals(cn.novel.yonren.types.enums.ResponseCode.ACTIVE_JOB_CONFLICT.getCode(), ex.getCode());
        // 守卫先于删除：有活动作业时不得删目录、不得清集合
        verify(storyRepository, never()).deleteStory(any());
        verify(storyMemoryService, never()).deleteStoryMemory(any());
    }

    @Test
    void deleteStory_survivesMemoryClearFailure() throws Exception {
        when(storyRepository.resolveStoryDirectory(DIR)).thenReturn(STORY_DIR);
        when(jobRegistry.isActiveForStory(DIR)).thenReturn(false);
        // 集合清理内部 fail-soft（只 WARN 不抛）；即便它抛，删除结果也不该变红——// 本用例锁定"目录删除是主体"这一语义
        org.mockito.Mockito.doThrow(new RuntimeException("集合清理失败"))
                .when(storyMemoryService).deleteStoryMemory(STORY_DIR);

        DeleteStoryResponseDTO result = service.deleteStory(DIR);

        assertTrue(result.deleted());
        verify(storyRepository).deleteStory(STORY_DIR);
    }
}
