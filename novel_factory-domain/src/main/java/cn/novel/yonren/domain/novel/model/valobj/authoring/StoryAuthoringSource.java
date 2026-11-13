package cn.novel.yonren.domain.novel.model.valobj.authoring;

/** 原摘要提取的最新对象状态。verified 仅为正文连续摘录机械匹配，不是语义真实性判定。 */
public record StoryAuthoringSource(String id, StoryAuthoringSnapshot.Kind kind, String entityName,
                                   String currentState, int sourceChapter, String sourceHash,
                                   String evidence, boolean verified) { }
