package cn.novel.yonren.domain.novel.model.valobj.authoring;

/** 同一锁内读取依据与候选；previewHash 绑定工作副本、执行版本、来源正文和全部变更。 */
public record StoryAuthoringPreview(StoryAuthoringView view, StoryAuthoringSnapshot candidate, String previewHash) { }
