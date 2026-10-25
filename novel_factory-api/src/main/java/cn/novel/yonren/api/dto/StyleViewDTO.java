package cn.novel.yonren.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 文风条目视图（GET /api/assets/styles）：按 GenreTypeVO 全量列出，
 * hasOverride=true 表示 data/styles/{code}.md 覆盖生效中
 */
@Data
@AllArgsConstructor
public class StyleViewDTO {
    /** 题材码（GenreTypeVO.code，也是 styles/{code}.md 的文件名） */
    private String code;
    /** 是否存在内置文风文件（jar 内 assets/styles；false=该题材原本回落 default.md） */
    private boolean hasBuiltin;
    /** 是否存在用户覆盖文件（data/styles） */
    private boolean hasOverride;
    /** 覆盖文件最后修改时间（epoch millis；无覆盖为 0） */
    private long updatedAt;
}
