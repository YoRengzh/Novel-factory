package cn.novel.yonren.api.dto;

/** currentState 是原摘要提取值；verified 仅为连续原文匹配旗，不是语义核验结果。 */
public record StoryAuthoringSourceDTO(String id, String kind, String entityName, String currentState,
                                      int sourceChapter, String sourceHash, String evidence, boolean verified) { }
