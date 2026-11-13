package cn.novel.yonren.domain.novel.adapter.repository;

import cn.novel.yonren.domain.novel.model.valobj.quality.QualityIssue;
import cn.novel.yonren.domain.novel.model.valobj.quality.QualityIssueDisposition;
import cn.novel.yonren.domain.novel.model.valobj.quality.QualityIssueRecheck;
import cn.novel.yonren.domain.novel.model.valobj.quality.QualityIssueSnapshot;

import java.nio.file.Path;
import java.util.List;

/** Isolated quality bookkeeping; no method changes generation sources, debts, acceptance or jobs. */
public interface IQualityIssueRepository {
    List<QualityIssue> list(Path storyDir) throws Exception;

    QualityIssue appendAction(Path storyDir, String issueId, QualityIssueDisposition.Action action,
                             String reason, String expectedRevision, String expectedSourceHash) throws Exception;

    /** Read and freeze under the existing shared story lock, without holding that lock during a model call. */
    QualityIssueSnapshot freezeForRecheck(Path storyDir, String issueId, String expectedRevision,
                                         String expectedSourceHash) throws Exception;

    /** CAS checks the frozen manuscript, plan, original issue and history again inside the shared lock. */
    QualityIssue appendRecheck(Path storyDir, QualityIssueSnapshot frozen,
                              QualityIssueRecheck result) throws Exception;
}
