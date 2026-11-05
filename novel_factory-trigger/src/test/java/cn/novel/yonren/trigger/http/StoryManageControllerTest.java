package cn.novel.yonren.trigger.http;

import cn.novel.yonren.api.dto.ChapterSaveRequestDTO;
import cn.novel.yonren.api.dto.StorySummaryDTO;
import cn.novel.yonren.domain.novel.service.manage.StoryManageService;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 故事库端点 HTTP 层测试（分层重构后）：
 * 业务断言已迁移至 domain 的 StoryManageServiceTest，本类只验证
 * trigger 层职责——AppException → HTTP 状态码映射、DTO 透传、导出响应头。
 */
class StoryManageControllerTest {

    private static final String DIR = "20260902-story-0001";

    @Mock
    private StoryManageService storyManageService;

    @InjectMocks
    private StoryManageController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    // ==================== AppException → HTTP 状态映射 ====================

    @Test
    void appExceptionMapping_notFound() {
        when(storyManageService.readChapter(eq(DIR), anyInt()))
                .thenThrow(new AppException(ResponseCode.CHAPTER_NOT_FOUND.getCode(), "章节不存在"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.readChapter(DIR, 99));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        assertEquals("章节不存在", e.getReason());
    }

    @Test
    void appExceptionMapping_activeJobConflict() {
        when(storyManageService.saveChapter(eq(DIR), anyInt(), any()))
                .thenThrow(new AppException(ResponseCode.ACTIVE_JOB_CONFLICT.getCode(), "该故事有运行中的作业"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.saveChapter(DIR, 1, new ChapterSaveRequestDTO()));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    @Test
    void appExceptionMapping_illegalParameter() {
        when(storyManageService.factTimeline(DIR, " "))
                .thenThrow(new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "实体名不能为空"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.factTimeline(DIR, " "));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    /** 仓储故障被服务层包成 UN_ERROR，必须映射 500——绝不能降级成 400 把服务端故障误报成用户输入错误 */
    @Test
    void appExceptionMapping_repositoryFailure_mapsTo500() {
        when(storyManageService.workbench(DIR))
                .thenThrow(new AppException(ResponseCode.UN_ERROR.getCode(), "summaries.json 读取失败"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.workbench(DIR));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.getStatusCode());
    }

    /** 未知异常码也走 500（保守：无法归类的一律视为服务端问题） */
    @Test
    void appExceptionMapping_unknownCode_mapsTo500() {
        when(storyManageService.listStories())
                .thenThrow(new AppException("9999", "未定义的码"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class, controller::listStories);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.getStatusCode());
    }

    @Test
    void unknownException_mapsTo500() {
        when(storyManageService.listStories()).thenThrow(new IllegalStateException("仓储崩了"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class, controller::listStories);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.getStatusCode());
        assertTrue(e.getReason().contains("故事列表查询失败"));
    }

    // ==================== 透传与响应头 ====================

    @Test
    void listStories_passesThroughServiceResult() {
        StorySummaryDTO dto = new StorySummaryDTO();
        dto.setStoryDirName(DIR);
        dto.setNovelTitle("测试小说");
        when(storyManageService.listStories()).thenReturn(List.of(dto));

        List<StorySummaryDTO> result = controller.listStories();

        assertEquals(1, result.size());
        assertEquals(DIR, result.get(0).getStoryDirName());
    }

    /** 中文文件名必须走 RFC 5987 的 filename*，否则浏览器下载名会乱码 */
    @Test
    void exportBook_zipHeadersWithUtf8Filename() {
        cn.novel.yonren.api.dto.ExportFilePayloadDTO book =
                new cn.novel.yonren.api.dto.ExportFilePayloadDTO(
                        "示例书-分章TXT-2章-20261011.zip", new byte[]{1, 2, 3});
        when(storyManageService.exportBook(DIR, "txt")).thenReturn(book);

        ResponseEntity<byte[]> response = controller.exportBook(DIR, "txt");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition.startsWith("attachment"));
        assertTrue(disposition.contains("filename*=UTF-8''"));
        assertEquals("application/zip", String.valueOf(response.getHeaders().getContentType()));
        assertArrayEquals(new byte[]{1, 2, 3}, response.getBody());
    }

    @Test
    void exportBook_noChapters_maps404() {
        when(storyManageService.exportBook(DIR, "txt"))
                .thenThrow(new AppException(ResponseCode.NO_STORY_DATA.getCode(), "该故事还没有可导出的章节"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.exportBook(DIR, "txt"));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        assertTrue(e.getReason().contains("还没有可导出的章节"));
    }

    // ==================== 入参前置校验（留在 controller 的 HTTP 语义） ====================

    @Test
    void createCheckpoint_blankName_rejectedBeforeService() {
        cn.novel.yonren.api.dto.CheckpointCreateRequestDTO request =
                new cn.novel.yonren.api.dto.CheckpointCreateRequestDTO();
        request.setName(" ");

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.createCheckpoint(DIR, request));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(storyManageService, never()).createCheckpoint(any(), any());
    }
}
