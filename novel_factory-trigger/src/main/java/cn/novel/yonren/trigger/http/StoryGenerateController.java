package cn.novel.yonren.trigger.http;

import cn.novel.yonren.api.dto.StoryGenerateRequestDTO;
import cn.novel.yonren.api.dto.StoryGenerateResponseDTO;
import cn.novel.yonren.domain.novel.model.aggregate.StoryGenerateResultAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.service.armory.StoryGenerateService;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.domain.novel.service.job.JobRegistry;
import cn.novel.yonren.trigger.http.StoryCommandAssembler;
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
 * 触发层：只负责触发与 DTO 适配，不承载业务逻辑。
 * 同步调试端点：阻塞直到全批生成结束；异步作业走 /api/jobs
 */
@Slf4j
@RestController
@RequestMapping("/api/story")
public class StoryGenerateController {

    @Resource
    private StoryGenerateService storyGenerateService;

    @Resource
    private StoryProperties storyProperties;

    @Resource
    private JobRegistry jobRegistry;

    @PostMapping("/generate-chapter")
    public StoryGenerateResponseDTO chapterGenerate(@RequestBody StoryGenerateRequestDTO request) throws Exception {
        // 续写的并发守卫：同步端点与异步作业共用同一个故事目录时，
        // 两边各持一份上下文写正文/摘要，后完成者会整表覆盖（与 /api/jobs 同款语义）
        if (StringUtils.isNotBlank(request.getResumeStoryDir())
                && jobRegistry.isActiveForStory(request.getResumeStoryDir())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该故事已有运行中的作业，请等待完成或取消后重试");
        }
        ArmoryCommandEntity command = StoryCommandAssembler.toCommand(request, storyProperties);

        StoryGenerateResultAggregate result = storyGenerateService.generate(command);

        // 领域结果 -> DTO
        StoryGenerateResponseDTO response = new StoryGenerateResponseDTO();
        response.setChapterPlan(result.getChapterPlanAggregate());
        response.setUsedPromptMap(result.getUsedPromptMap());
        response.setStoryDirName(result.getStoryDirName());

        return response;
    }
}
