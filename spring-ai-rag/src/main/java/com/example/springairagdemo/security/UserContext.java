package com.example.springairagdemo.security;

import org.springframework.web.context.request.RequestContextHolder;

import java.util.List;

/**
 * 当前登录用户上下文（RAG 本地版，ThreadLocal）：
 * 由 {@code GatewayIdentityFilter} 在请求进入时注入、请求结束 finally 清理。
 * 用户信息来自网关透传的身份头（X-User-Id / X-Username / X-Permissions），
 * 用户域由独立服务 spring-ai-user（8082）承担。
 *
 * 注意：仅在当前请求线程内有效；若涉及异步/线程池，需显式传递用户信息。
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    /** 当前用户 ID，未登录返回 null */
    public static Long getUserId() {
        LoginUser user = HOLDER.get();
        return user == null ? null : user.id();
    }

    /** 当前用户名，未登录返回 null */
    public static String getUsername() {
        LoginUser user = HOLDER.get();
        return user == null ? null : user.username();
    }

    /**
     * 当前用户权限码（来自 JWT 缓存，网关透传），未登录或未携带返回 null
     */
    public static List<String> getPermissions() {
        LoginUser user = HOLDER.get();
        return user == null ? null : user.permissions();
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** 客户端来源 IP（用于审计），优先取网关透传的 X-Forwarded-For */
    public static String clientIp() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        jakarta.servlet.http.HttpServletRequest request =
                ((org.springframework.web.context.request.ServletRequestAttributes) attrs).getRequest();
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        } else {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
