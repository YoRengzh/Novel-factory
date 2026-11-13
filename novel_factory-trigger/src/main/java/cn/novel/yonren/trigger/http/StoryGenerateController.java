package cn.novel.yonren.trigger.http;

import cn.novel.yonren.api.dto.StoryGenerateRequestDTO;
import cn.novel.yonren.api.dto.StoryGenerateResponseDTO;
import cn.novel.yonren.domain.novel.service.armory.StoryGenerateService;
import cn.novel.yonren.domain.novel.service.job.JobRegistry;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 触发层：只负责触发与 HTTP 语义，不承载业务逻辑（装配与结果 DTO 化已下沉 StoryGenerateService）。
 * 同步调试端点：阻塞直到全批生成结束；异步作业走 /api/jobs
 */
@Slf4j
@RestController
@RequestMapping("/api/story")
public class StoryGenerateController {

    @Resource
    private StoryGenerateService storyGenerateService;

    @Resource
    private JobRegistry jobRegistry;

    @PostMapping("/generate-chapter")
    public StoryGenerateResponseDTO chapterGenerate(@RequestBody StoryGenerateRequestDTO request) throws Exception {
        // 续写的并发守卫：同步端点与异步作业共用同一个故事目录时，
        // 两边各持一份上下文写正文/摘要，后完成者会整表覆盖（与 /api/jobs 同款语义）
        String storyDir = request.getResumeStoryDir();
        if (StringUtils.isBlank(storyDir)) {
            return storyGenerateService.generateDto(request);
        }
        if (!jobRegistry.tryBeginStoryOperation(storyDir)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该故事有运行中的作业或维护操作，请等待完成后重试");
        }
        try {
            return storyGenerateService.generateDto(request);
        } finally {
            jobRegistry.endStoryOperation(storyDir);
        }
    }
}
