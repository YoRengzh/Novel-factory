package cn.novel.yonren.api.dto;

/**
 * 导出成书的 HTTP 载荷（2026-11-05 分层重构新增）：
 * 文件名（已清洗非法字符并截断，如「某故事-分章TXT-65章-20261011.zip」）+ 分章 ZIP 字节。
 * 领域侧的完整产物（章数/字数/格式等元信息）留在 BookExport，trigger 只需要响应头与正文。
 */
public record ExportFilePayloadDTO(String fileName, byte[] fileBytes) {
}
