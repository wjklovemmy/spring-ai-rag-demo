package com.example.springairagdemo.security;

import java.util.List;

/**
 * 当前登录用户（RAG 本地版，由网关过滤器从透传身份头构造注入 UserContext）。
 *
 * @param id          用户 ID（X-User-Id 头，用户服务签发）
 * @param username    用户名（X-Username 头）
 * @param permissions 权限码集合（X-Permissions 头，网关从 JWT 解出后透传；未携带时为空列表）
 */
public record LoginUser(Long id, String username, List<String> permissions) {

    /** 兼容无权限码场景 */
    public LoginUser(Long id, String username) {
        this(id, username, List.of());
    }
}
