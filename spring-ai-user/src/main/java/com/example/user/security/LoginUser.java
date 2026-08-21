package com.example.user.security;

import java.util.List;

/**
 * 当前登录用户（由网关过滤器注入 UserContext）
 *
 * @param id          用户 ID
 * @param username    用户名
 * @param permissions 权限码集合（来自 JWT 缓存，网关注入；未携带时为空列表）
 */
public record LoginUser(Long id, String username, List<String> permissions) {

    /** 兼容无权限码场景 */
    public LoginUser(Long id, String username) {
        this(id, username, List.of());
    }
}
