package cn.novel.yonren.domain.novel.service.manage;

import cn.novel.yonren.api.dto.QualityIssueActionRequestDTO;
import cn.novel.yonren.api.dto.QualityIssueDTO;
import cn.novel.yonren.api.dto.QualityIssueListResponseDTO;
import cn.novel.yonren.api.dto.QualityIssueRecheckRequestDTO;
import cn.novel.yonren.api.dto.QualityIssueSaveResponseDTO;
import cn.novel.yonren.domain.novel.model.valobj.quality.QualityIssue;
import cn.novel.yonren.domain.novel.service.quality.QualityIssueService;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 品质问题门面：作者维护的问题处置与复核，与生成侧质量债分开。
 *
 * <p>处置只记作者判断，不改正文、不改质量债、不参与生成依据；因此它可以在作业之间
 * 自由进行，但同一时刻仍与作业互斥（由 {@link QualityIssueService} 内的作业登记保证）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityIssueManageService {

    private final QualityIssueService qualityIssueService;

    public QualityIssueListResponseDTO list(String storyDirName) {
        return call(() -> new QualityIssueListResponseDTO(
                qualityIssueService.list(storyDirName).stream().map(QualityIssueManageService::toDto).toList()));
    }

    public QualityIssueSaveResponseDTO act(String storyDirName, String issueId,
                                           QualityIssueActionRequestDTO request) {
        if (request == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "必须提供处置请求");
        }
        return call(() -> saved(qualityIssueService.act(storyDirName, issueId, request.action(), request.reason(),
                request.expectedRevision(), request.expectedSourceHash())));
    }

    /**
     * 模型复核：必须显式授权。当前版本缺少保真比对，复核一律返回"未确认"，
     * 不会把未验证的结论当成已解决。
     */
    public QualityIssueSaveResponseDTO recheck(String storyDirName, String issueId,
                                               QualityIssueRecheckRequestDTO request) {
        if (request == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "必须明确授权模型复核");
        }
        return call(() -> saved(qualityIssueService.recheck(storyDirName, issueId, request.expectedRevision(),
                request.expectedSourceHash(), request.confirmModelCall())));
    }

    private static QualityIssueSaveResponseDTO saved(QualityIssue issue) {
        QualityIssueDTO dto = toDto(issue);
        return new QualityIssueSaveResponseDTO(true, dto, dto.disposition());
    }

    private static QualityIssueDTO toDto(QualityIssue issue) {
        var disposition = issue.disposition();
        var recheck = issue.recheck();
        return new QualityIssueDTO(issue.id(), issue.chapterNo(), issue.sourceHash(), issue.dimension(),
                issue.severity(), issue.description(), issue.evidence(), issue.suggestion(), issue.systemResolved(),
                new QualityIssueDTO.Disposition(disposition.state().name(), disposition.note(),
                        disposition.revision()),
                recheck == null ? null : new QualityIssueDTO.Recheck(recheck.status().name(),
                        recheck.contentHash(), recheck.manuscriptHash(), recheck.planHash(), recheck.sourceHash(),
                        recheck.checkedAtMs(), recheck.message(), recheck.evidence()),
                issue.currentContentHash(), issue.currentManuscriptHash(),
                issue.acceptedManuscriptHash(), issue.planHash());
    }

    private static <T> T call(CheckedSupplier<T> action) {
        try {
            return action.get();
        } catch (AppException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), e.getMessage());
        } catch (IllegalStateException e) {
            throw new AppException(ResponseCode.ACTIVE_JOB_CONFLICT.getCode(), e.getMessage());
        } catch (Exception e) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), "质量问题读取或保存失败，未改变原质量债：" + e.getMessage());
        }
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
