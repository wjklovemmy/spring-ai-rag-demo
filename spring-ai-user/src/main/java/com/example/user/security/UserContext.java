package com.example.user.security;

import org.springframework.web.context.request.RequestContextHolder;

/**
 * 当前登录用户上下文（ThreadLocal）：
 * 由 {@code JwtAuthenticationFilter} 在请求进入时注入、请求结束时清理。
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

    public static void clear() {
        HOLDER.remove();
    }

    /** 客户端来源 IP（用于审计） */
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
