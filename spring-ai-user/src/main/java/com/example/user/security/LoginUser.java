package com.example.user.security;

/**
 * 当前登录用户（由 JWT 过滤器注入 UserContext）
 *
 * @param id       用户 ID
 * @param username 用户名
 */
public record LoginUser(Long id, String username) {
}
