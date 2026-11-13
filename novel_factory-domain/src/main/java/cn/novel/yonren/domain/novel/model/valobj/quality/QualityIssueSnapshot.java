package cn.novel.yonren.domain.novel.model.valobj.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;

/** Internal, full read snapshot for an explicitly requested current-manuscript recheck. */
public record QualityIssueSnapshot(QualityIssue issue, ChapterManuscript manuscript,
                                   ChapterPlanItemEntity plan, String storyBible, String storageHash) { }
