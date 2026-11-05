package cn.novel.yonren.api.dto;

/**
 * Continue with ChatGPT 授权跳转（2026-11-05 分层重构新增）：
 * 镜像领域端口嵌套 record ChatGptGateway.Authorization，字段名对齐，JSON 形状不变。
 */
public record ChatGptAuthorizationDTO(String url) {
}
