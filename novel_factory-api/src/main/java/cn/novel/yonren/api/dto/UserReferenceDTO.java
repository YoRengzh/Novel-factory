package cn.novel.yonren.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 用户自备参考资料条目（GET /api/assets/references）：设置页「我的参考资料」列表行。
 * fileName 不含 .md 扩展名，是选择器输出与白名单校验的对外标识
 */
@Data
@AllArgsConstructor
public class UserReferenceDTO {
    /** 文件名（不含扩展名，小写字母/数字/连字符） */
    private String fileName;
    /** 一级标题（文件首个 "# " 行；缺省回退文件名） */
    private String title;
    /** 标题后首段简介（截断 60 字），供列表预览 */
    private String digest;
    /** 资料分组：technique / structure / genre（决定哪些场景能检索到它） */
    private String group;
    /** 文件大小（字节） */
    private long sizeBytes;
    /** 最后修改时间（epoch millis） */
    private long updatedAt;
}
