package cn.novel.yonren.trigger.http;

import cn.novel.yonren.domain.novel.service.manage.StoryManageService;
import cn.novel.yonren.trigger.advice.ApiExceptionHandler;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 状态码契约测试（真实 Spring MVC 栈，standaloneSetup + 全局 advice）。
 *
 * 目的：锁定 分层重构前后的 HTTP 语义一致。单元测试只验证
 * controller 方法返回值/异常，本类验证"经 Spring 异常解析后最终落到客户端的状态码"，
 * 覆盖 ApiExceptionHandler 与端点内 statusOf 的交互。
 */
class StoryManageHttpContractTest {

    private static final String DIR = "20260902-story-0001";

    @Mock
    private StoryManageService storyManageService;

    @InjectMocks
    private StoryManageController controller;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void chapterNotFound_is404() throws Exception {
        when(storyManageService.readChapter(eq(DIR), anyInt()))
                .thenThrow(new AppException(ResponseCode.CHAPTER_NOT_FOUND.getCode(), "章节不存在"));
        mvc.perform(get("/api/stories/{d}/chapters/{n}", DIR, 99)).andExpect(status().isNotFound());
    }

    @Test
    void unknownStoryDir_is404() throws Exception {
        when(storyManageService.listChapters("bad"))
                .thenThrow(new AppException(ResponseCode.NO_STORY_DATA.getCode(), "故事目录不存在"));
        mvc.perform(get("/api/stories/bad/chapters")).andExpect(status().isNotFound());
    }

    @Test
    void activeJobOnSave_is409() throws Exception {
        when(storyManageService.saveChapter(eq(DIR), anyInt(), any()))
                .thenThrow(new AppException(ResponseCode.ACTIVE_JOB_CONFLICT.getCode(), "有运行中的作业"));
        mvc.perform(put("/api/stories/{d}/chapters/{n}", DIR, 1)
                        .contentType("application/json").content("{\"content\":\"x\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void blankContent_is400() throws Exception {
        when(storyManageService.saveChapter(eq(DIR), anyInt(), any()))
                .thenThrow(new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "正文不能为空"));
        mvc.perform(put("/api/stories/{d}/chapters/{n}", DIR, 1)
                        .contentType("application/json").content("{\"content\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    /** 仓储故障必须 500，不能降级成 400 */
    @Test
    void repositoryFailure_is500() throws Exception {
        when(storyManageService.workbench(DIR))
                .thenThrow(new AppException(ResponseCode.UN_ERROR.getCode(), "summaries.json 读取失败"));
        mvc.perform(get("/api/stories/{d}/workbench", DIR)).andExpect(status().isInternalServerError());
    }

    @Test
    void unsupportedExportFormat_is400() throws Exception {
        when(storyManageService.exportBook(DIR, "pdf"))
                .thenThrow(new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "不支持的导出格式"));
        mvc.perform(get("/api/stories/{d}/export", DIR).param("format", "pdf"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void noChaptersToExport_is404() throws Exception {
        when(storyManageService.exportBook(DIR, "txt"))
                .thenThrow(new AppException(ResponseCode.NO_STORY_DATA.getCode(), "该故事还没有可导出的章节"));
        mvc.perform(get("/api/stories/{d}/export", DIR).param("format", "txt"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteWithActiveJob_is409() throws Exception {
        when(storyManageService.deleteStory(DIR))
                .thenThrow(new AppException(ResponseCode.ACTIVE_JOB_CONFLICT.getCode(), "有运行中的作业"));
        mvc.perform(post("/api/stories/{d}/delete", DIR)).andExpect(status().isConflict());
    }

    @Test
    void checkpointBlankName_is400() throws Exception {
        // 空 name 在 controller 前置校验拦截，不进 service
        mvc.perform(post("/api/stories/{d}/checkpoints", DIR)
                        .contentType("application/json").content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest());
    }
}
