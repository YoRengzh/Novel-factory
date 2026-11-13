package cn.novel.yonren.trigger.http;

import cn.novel.yonren.api.dto.StoryAuthoringRequestDTO;
import cn.novel.yonren.api.dto.StoryAuthoringResponseDTO;
import cn.novel.yonren.domain.novel.service.manage.StoryAuthoringManageService;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 书内创作维护：本地确定性预览与显式保存，没有模型、生成或回流副作用。
 *
 * <p>保存必须携带预览哈希——候选版本按预览当时的正文依据算出，直接写会让
 * "作者看到的"与"落盘的"分叉。
 */
@Slf4j
@RestController
@RequestMapping("/api/stories/{storyDir}/authoring")
public class StoryAuthoringController {

    @Resource
    private StoryAuthoringManageService storyAuthoringManageService;

    @GetMapping
    public StoryAuthoringResponseDTO get(@PathVariable("storyDir") String storyDir) {
        return storyAuthoringManageService.view(storyDir);
    }

    @PostMapping("/preview")
    public StoryAuthoringResponseDTO preview(@PathVariable("storyDir") String storyDir,
                                             @RequestBody StoryAuthoringRequestDTO request) {
        return storyAuthoringManageService.preview(storyDir, request);
    }

    @PutMapping
    public StoryAuthoringResponseDTO put(@PathVariable("storyDir") String storyDir,
                                         @RequestBody StoryAuthoringRequestDTO request) {
        try {
            return storyAuthoringManageService.save(storyDir, request);
        } catch (AppException e) {
            if (ResponseCode.ACTIVE_JOB_CONFLICT.getCode().equals(e.getCode())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, e.getInfo());
            }
            if (ResponseCode.ILLEGAL_PARAMETER.getCode().equals(e.getCode())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getInfo());
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getInfo());
        }
    }
}
