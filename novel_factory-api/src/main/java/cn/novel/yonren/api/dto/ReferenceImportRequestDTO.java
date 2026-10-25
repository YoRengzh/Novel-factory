package cn.novel.yonren.api.dto;

import lombok.Data;

/**
 * 参考资料文件导入请求（POST /api/assets/references/{fileName}/import）：
 * 后端读取本地路径的全文（复制语义，不引用原路径），存为用户资料
 */
@Data
public class ReferenceImportRequestDTO {
    /** 本地文件绝对或相对路径（md/txt，UTF-8） */
    private String sourcePath;
}
