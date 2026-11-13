package cn.novel.yonren.api.dto;

import java.time.Instant;

/**
 * Continue with ChatGPT 授权状态：
 * 镜像领域端口嵌套 record ChatGptGateway.AuthStatus，字段名对齐，JSON 形状不变。
 * 令牌从不返回浏览器。
 */
public record ChatGptAuthStatusDTO(boolean authorized, String email, Instant expiresAt) {
}
