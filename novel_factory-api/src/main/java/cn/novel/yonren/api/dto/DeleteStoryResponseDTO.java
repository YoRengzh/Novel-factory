package cn.novel.yonren.api.dto;

/**
 * 删除故事结果：必须带 JSON 体（前端 fetchJson 按 Content-Type 判别，void 空体会被误报为服务不可用）。
 * 2026-11-05 从 controller 内部 record 迁入 api 模块。
 */
public record DeleteStoryResponseDTO(boolean deleted, String storyDirName) {
}
