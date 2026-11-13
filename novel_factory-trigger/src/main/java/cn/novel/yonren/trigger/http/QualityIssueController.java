package cn.novel.yonren.trigger.http;

import cn.novel.yonren.api.dto.QualityIssueActionRequestDTO;
import cn.novel.yonren.api.dto.QualityIssueListResponseDTO;
import cn.novel.yonren.api.dto.QualityIssueRecheckRequestDTO;
import cn.novel.yonren.api.dto.QualityIssueSaveResponseDTO;
import cn.novel.yonren.domain.novel.service.manage.QualityIssueManageService;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import jakarta.annotation.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 品质问题处置：作者对已生成问题的独立记账，与生成侧质量债分开。
 *
 * <p>处置不改正文、不改质量债、不参与生成依据；它只是作者对"这条问题怎么办"的判断。
 */
@RestController
@RequestMapping("/api/stories/{story}/quality-issues")
public class QualityIssueController {

    @Resource
    private QualityIssueManageService qualityIssueManageService;

    @GetMapping
    public QualityIssueListResponseDTO list(@PathVariable("story") String story) {
        return qualityIssueManageService.list(story);
    }

    @PostMapping("/{id}/actions")
    public QualityIssueSaveResponseDTO act(@PathVariable("story") String story,
                                           @PathVariable("id") String id,
                                           @RequestBody QualityIssueActionRequestDTO request) {
        return map(() -> qualityIssueManageService.act(story, id, request));
    }

    @PostMapping("/{id}/recheck")
    public QualityIssueSaveResponseDTO recheck(@PathVariable("story") String story,
                                               @PathVariable("id") String id,
                                               @RequestBody QualityIssueRecheckRequestDTO request) {
        return map(() -> qualityIssueManageService.recheck(story, id, request));
    }

    private static <T> T map(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (AppException e) {
            if (ResponseCode.ILLEGAL_PARAMETER.getCode().equals(e.getCode())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getInfo());
            }
            if (ResponseCode.ACTIVE_JOB_CONFLICT.getCode().equals(e.getCode())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, e.getInfo());
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getInfo());
        }
    }
}
