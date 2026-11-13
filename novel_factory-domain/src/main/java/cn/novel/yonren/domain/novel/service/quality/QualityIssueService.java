package cn.novel.yonren.domain.novel.service.quality;

import cn.novel.yonren.domain.novel.adapter.repository.IQualityIssueRepository;
import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.model.valobj.quality.*;
import cn.novel.yonren.domain.novel.service.armory.audit.ChapterAuditService;
import cn.novel.yonren.domain.novel.service.job.JobRegistry;
import com.alibaba.fastjson2.JSON;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Explicit user maintenance only; no writer, worker, generation job or quality-debt mutation. */
@Service
public class QualityIssueService {
    private final IStoryRepository storyRepository;
    private final IQualityIssueRepository qualityRepository;
    private final JobRegistry jobRegistry;
    private final ChapterAuditService chapterAuditService;
    private final StoryProperties storyProperties;

    public QualityIssueService(IStoryRepository storyRepository, IQualityIssueRepository qualityRepository,
                               JobRegistry jobRegistry, ChapterAuditService chapterAuditService,
                               StoryProperties storyProperties) {
        this.storyRepository = Objects.requireNonNull(storyRepository);
        this.qualityRepository = Objects.requireNonNull(qualityRepository);
        this.jobRegistry = Objects.requireNonNull(jobRegistry);
        this.chapterAuditService = Objects.requireNonNull(chapterAuditService);
        this.storyProperties = Objects.requireNonNull(storyProperties);
    }

    public List<QualityIssue> list(String storyDirName) throws Exception {
        validateStoryName(storyDirName);
        return qualityRepository.list(storyRepository.resolveStoryDirectoryReadOnly(storyDirName));
    }

    public QualityIssue act(String storyDirName, String issueId, String actionName, String reason,
                           String expectedRevision, String expectedSourceHash) throws Exception {
        QualityIssuePolicy.validateExpected(issueId, expectedRevision, expectedSourceHash);
        QualityIssuePolicy.validateReason(reason);
        QualityIssueDisposition.Action action;
        try { action = QualityIssueDisposition.Action.valueOf(Objects.toString(actionName, "")); }
        catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("action 仅接受 NOTE、DEFER 或 FALSE_POSITIVE");
        }
        if (action == QualityIssueDisposition.Action.RECHECK)
            throw new IllegalArgumentException("模型复核必须通过明确授权的 recheck 接口");
        begin(storyDirName);
        try {
            checkCancelled();
            var directory = storyRepository.resolveStoryDirectoryReadOnly(storyDirName);
            return qualityRepository.appendAction(directory, issueId, action, reason.strip(), expectedRevision, expectedSourceHash);
        } finally {
            jobRegistry.endStoryOperation(storyDirName);
        }
    }

    public QualityIssue recheck(String storyDirName, String issueId, String expectedRevision,
                               String expectedSourceHash, Boolean confirmModelCall) throws Exception {
        if (!Boolean.TRUE.equals(confirmModelCall))
            throw new IllegalArgumentException("复核会调用模型，必须明确提供 confirmModelCall=true");
        QualityIssuePolicy.validateExpected(issueId, expectedRevision, expectedSourceHash);
        begin(storyDirName);
        try {
            checkCancelled();
            var directory = storyRepository.resolveStoryDirectoryReadOnly(storyDirName);
            var frozen = qualityRepository.freezeForRecheck(directory, issueId, expectedRevision, expectedSourceHash);
            checkCancelled();
            var outcome = evaluate(frozen);
            checkCancelled();
            return qualityRepository.appendRecheck(directory, frozen, outcome);
        } finally {
            jobRegistry.endStoryOperation(storyDirName);
        }
    }

    /**
     * 当前稿目标复核。
     *
     * <p>本仓的修订复审只返回"仍未修复的子集"与降级标记，没有历史稿与候选稿的保真比对，
     * 因此无法区分"目标问题已解决"与"改动了未授权内容"。缺少这层比对时认定"已解决"
     * 等于用一个未验证的结论覆盖质量债，比保留问题更贵——因此复核一律返回"未确认"，
     * 由作者自行判断并显式处置（延后或标记误报）。
     */
    private QualityIssueRecheck evaluate(QualityIssueSnapshot snapshot) {
        if (!hasContext(snapshot)) {
            return outcome(snapshot, QualityIssueRecheck.Status.RECHECK_UNCONFIRMED,
                    "缺少完整当前正文、有效章节计划或问题依据；未调用模型，未认定解决", "");
        }
        return outcome(snapshot, QualityIssueRecheck.Status.RECHECK_UNCONFIRMED,
                "当前版本不支持保真比对，无法证明目标问题已解决且未改动未授权内容；未调用模型，未认定解决", "");
    }

    private static boolean hasContext(QualityIssueSnapshot snapshot) {
        if (snapshot == null || snapshot.issue() == null || snapshot.manuscript() == null || snapshot.plan() == null)
            return false;
        var row = snapshot.issue();
        var manuscript = snapshot.manuscript();
        var plan = snapshot.plan();
        return manuscript.chapterNo() == row.chapterNo() && !manuscript.content().isBlank()
                && Objects.equals(plan.getChapterNo(), row.chapterNo())
                && row.description() != null && !row.description().isBlank()
                && row.dimension() != null && !row.dimension().isBlank()
                && row.evidence() != null && !row.evidence().isBlank()
                && Objects.equals(manuscript.hash(), row.currentManuscriptHash())
                && Objects.equals(cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript.digest(manuscript.content()),
                                  row.currentContentHash())
                && Objects.equals(QualityIssuePolicy.digest(plan), row.planHash())
                && ((plan.getGoal() != null && !plan.getGoal().isBlank())
                    || (plan.getKeyEvents() != null && plan.getKeyEvents().stream().anyMatch(event -> event != null && !event.isBlank()))
                    || (plan.getScenes() != null && !plan.getScenes().isEmpty()));
    }

    private static QualityIssueRecheck outcome(QualityIssueSnapshot snapshot, QualityIssueRecheck.Status status,
                                               String message, String evidence) {
        var issue = Objects.requireNonNull(snapshot, "snapshot").issue();
        return new QualityIssueRecheck(status, issue.currentContentHash(), issue.currentManuscriptHash(), issue.planHash(),
                issue.sourceHash(), System.currentTimeMillis(), message, evidence);
    }

    private void begin(String storyDirName) {
        validateStoryName(storyDirName);
        if (!jobRegistry.tryBeginStoryOperation(storyDirName))
            throw new IllegalStateException("该故事有活动作业或维护操作，请完成后再处置或复核问题");
    }

    private static void validateStoryName(String storyDirName) {
        if (storyDirName == null || !storyDirName.matches("[0-9]{8}-story-[0-9]{4}"))
            throw new IllegalArgumentException("故事目录名无效");
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw cancelled(null);
    }

    private static boolean isCancellation(Throwable failure) {
        for (int depth = 0; failure != null && depth < 32; depth++, failure = failure.getCause()) {
            if (failure instanceof CancellationException || failure instanceof InterruptedException) return true;
        }
        return Thread.currentThread().isInterrupted();
    }

    private static CancellationException cancelled(Throwable cause) {
        var cancelled = new CancellationException("质量问题复核已取消，结果未保存");
        if (cause != null) cancelled.initCause(cause);
        return cancelled;
    }
}
