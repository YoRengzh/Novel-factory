package cn.novel.yonren.api.dto;

import lombok.Data;

/** id/status 为服务端元数据；请求不采信，来源 hash 仅作正文版本 CAS。 */
@Data
public class StoryAuthoringEntryDTO {
    private String id;
    private String kind;
    private String entityName;
    private String operation;
    private String before;
    private String after;
    private String reason;
    private Integer sourceChapter;
    private String sourceHash;
    private String evidence;
    private Integer effectiveFromChapter;
    private String status;
}
