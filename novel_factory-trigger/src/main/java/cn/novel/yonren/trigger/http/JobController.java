package cn.novel.yonren.trigger.http;

import cn.novel.yonren.api.dto.ChapterPlanApprovalRequestDTO;
import cn.novel.yonren.api.dto.JobStatusResponseDTO;
import cn.novel.yonren.api.dto.StoryGenerateRequestDTO;
import cn.novel.yonren.domain.novel.service.job.StoryJobService;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 异步作业端点（D9 个人用范围：只做触发/进度/取消，无列表/详情/导出）。
 * 请求体与同步端点相同；作业在内存注册表中运行，job-status.json 仅供崩溃后人工查看。
 * 2026-11-05 分层重构：装配与状态 DTO 化已下沉 StoryJobService 的 DTO 门面。
 */
@Slf4j
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    @Resource
    private StoryJobService storyJobService;

    @PostMapping
    public JobStatusResponseDTO submit(@RequestBody StoryGenerateRequestDTO request) {
        // 入参校验失败（如 worldId 格式非法）由 ApiExceptionHandler 统一转 400，此处不再手写 try/catch
        JobStatusResponseDTO dto;
        try {
            dto = storyJobService.submitDto(request);
        } catch (AppException e) {
            // 同一故事已有运行中作业（入参类冲突）→ 409；队列满 → 503 提示稍后重试
            if (ResponseCode.ILLEGAL_PARAMETER.getCode().equals(e.getCode())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, e.getInfo());
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getInfo());
        }
        log.info("作业已提交：{}，resumeStoryDir: {}", dto.getJobId(), request.getResumeStoryDir());
        return dto;
    }

    @GetMapping("/{jobId}")
    public JobStatusResponseDTO status(@PathVariable("jobId") String jobId) {
        JobStatusResponseDTO dto = storyJobService.statusDto(jobId);
        if (dto == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "作业不存在：" + jobId);
        }
        return dto;
    }

    @PostMapping("/{jobId}/cancel")
    public JobStatusResponseDTO cancel(@PathVariable("jobId") String jobId) {
        JobStatusResponseDTO dto = storyJobService.cancelDto(jobId);
        if (dto == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "作业不存在：" + jobId);
        }
        return dto;
    }

    /**
     * 章节计划裁决：通过（可携带人工修订稿）。
     * 修订稿未过机械校验（0003 参数为空 / 0004 参数重复）由 ApiExceptionHandler 统一转 400，
     * 作业仍在挂起态等用户改正后重提
     */
    @PostMapping("/{jobId}/chapter-plan/approve")
    public JobStatusResponseDTO approveChapterPlan(@PathVariable("jobId") String jobId,
                                                   @RequestBody(required = false) ChapterPlanApprovalRequestDTO request) {
        JobStatusResponseDTO dto = storyJobService.approveChapterPlanDto(jobId, request);
        if (dto == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "作业不在待裁决状态（可能已超时放行、已取消或已裁决过）：" + jobId);
        }
        log.info("章节计划裁决通过：{}", jobId);
        return dto;
    }

    /** 章节计划裁决：驳回 → 本批中止（计划保留在 run 目录，可另起命令复用） */
    @PostMapping("/{jobId}/chapter-plan/reject")
    public JobStatusResponseDTO rejectChapterPlan(@PathVariable("jobId") String jobId) {
        JobStatusResponseDTO dto = storyJobService.rejectChapterPlanDto(jobId);
        if (dto == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "作业不在待裁决状态（可能已超时放行、已取消或已裁决过）：" + jobId);
        }
        log.info("章节计划裁决驳回：{}", jobId);
        return dto;
    }
}
