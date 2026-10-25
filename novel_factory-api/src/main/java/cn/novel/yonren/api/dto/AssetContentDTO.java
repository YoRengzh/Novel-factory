package cn.novel.yonren.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户资产读写载体（PUT/GET /api/assets/...）：references 只带 content；
 * styles 额外带 overridden（是否存在覆盖）与 builtinContent（内置原文，供编辑器对照/恢复）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AssetContentDTO {
    /** md 全文（PUT 为新内容；GET 为当前生效内容） */
    private String content;
    /** styles 专用：是否存在用户覆盖（false=当前生效内容即内置原文） */
    private Boolean overridden;
    /** styles 专用：内置原文（无内置时为 null），编辑器"恢复内置"以此展示差异 */
    private String builtinContent;
}
