package cn.novel.yonren.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Getter
public enum ResponseCode {

    SUCCESS("0000", "成功"),
    UN_ERROR("0001", "未知失败"),
    ILLEGAL_PARAMETER("0002", "非法参数"),
    NULL_EXCEPTION("0003","这个参数不能为空"),
    PARAM_REPETITION("0004","重复参数"),
    /** 资源不存在（章节/故事目录/可导出数据）——trigger 层映射为 404 */
    NO_STORY_DATA("0010", "故事数据不存在"),
    /** 章节不存在——trigger 层映射为 404 */
    CHAPTER_NOT_FOUND("0011", "章节不存在"),
    /** 目标故事有运行中作业，写操作被拒——trigger 层映射为 409 */
    ACTIVE_JOB_CONFLICT("0012", "存在运行中的作业"),
    ;

    private String code;
    private String info;

}
