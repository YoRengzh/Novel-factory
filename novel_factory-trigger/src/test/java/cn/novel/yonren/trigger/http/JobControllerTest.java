package cn.novel.yonren.trigger.http;

import cn.novel.yonren.api.dto.JobStatusResponseDTO;
import cn.novel.yonren.api.dto.StoryGenerateRequestDTO;
import cn.novel.yonren.domain.novel.service.job.StoryJobService;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 作业端点 HTTP 层测试（分层重构后）：
 * 业务断言由 domain 的 StoryJobService/StoryCommandAssembler 测试覆盖，
 * 本类只验证 DTO 门面调用、状态码映射（503 队列满 / 404 未知作业 / 409 非挂起态裁决）。
 */
class JobControllerTest {

    @Mock
    private StoryJobService storyJobService;

    @InjectMocks
    private JobController jobController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void submit_returnsDtoFromFacade() {
        JobStatusResponseDTO dto = new JobStatusResponseDTO();
        dto.setJobId("job-7");
        dto.setStatus("CREATED");
        when(storyJobService.submitDto(any())).thenReturn(dto);

        StoryGenerateRequestDTO request = new StoryGenerateRequestDTO();
        request.setNovel_title("灵气复苏后的外卖员");
        request.setResumeStoryDir("20260902-story-0001");

        JobStatusResponseDTO result = jobController.submit(request);

        assertEquals("job-7", result.getJobId());
        assertEquals("CREATED", result.getStatus());
        verify(storyJobService).submitDto(request);
    }

    @Test
    void submit_queueFull_throws503() {
        when(storyJobService.submitDto(any()))
                .thenThrow(new AppException(ResponseCode.UN_ERROR.getCode(), "作业队列已满，请稍后重试"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> jobController.submit(new StoryGenerateRequestDTO()));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatusCode());
    }

    @Test
    void submit_activeJobConflict_throws409() {
        when(storyJobService.submitDto(any()))
                .thenThrow(new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                        "该故事已有运行中的作业，请等待完成或取消后再提交"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> jobController.submit(new StoryGenerateRequestDTO()));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    @Test
    void status_knownJob_returnsDto() {
        JobStatusResponseDTO dto = new JobStatusResponseDTO();
        dto.setJobId("job-8");
        dto.setStatus("RUNNING");
        dto.setCurrentStage("CHAPTER_GENERATION");
        dto.setCurrentChapter(5);
        dto.setTotalChapters(24);
        dto.setStoryDirName("20260903-story-0001");
        when(storyJobService.statusDto("job-8")).thenReturn(dto);

        JobStatusResponseDTO result = jobController.status("job-8");

        assertEquals("job-8", result.getJobId());
        assertEquals("RUNNING", result.getStatus());
        assertEquals("CHAPTER_GENERATION", result.getCurrentStage());
        assertEquals(5, result.getCurrentChapter());
        assertEquals(24, result.getTotalChapters());
        assertEquals("20260903-story-0001", result.getStoryDirName());
    }

    @Test
    void status_unknownJob_throws404() {
        when(storyJobService.statusDto("nope")).thenReturn(null);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> jobController.status("nope"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void cancel_knownJob_returnsDto() {
        JobStatusResponseDTO dto = new JobStatusResponseDTO();
        dto.setJobId("job-9");
        dto.setStatus("CANCELLING");
        when(storyJobService.cancelDto("job-9")).thenReturn(dto);

        JobStatusResponseDTO result = jobController.cancel("job-9");

        assertEquals("job-9", result.getJobId());
        assertEquals("CANCELLING", result.getStatus());
    }

    @Test
    void cancel_unknownJob_throws404() {
        when(storyJobService.cancelDto("nope")).thenReturn(null);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> jobController.cancel("nope"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void approve_notAwaiting_throws409() {
        when(storyJobService.approveChapterPlanDto(any(), any())).thenReturn(null);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> jobController.approveChapterPlan("job-1", null));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    @Test
    void reject_notAwaiting_throws409() {
        when(storyJobService.rejectChapterPlanDto("job-1")).thenReturn(null);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> jobController.rejectChapterPlan("job-1"));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }
}
