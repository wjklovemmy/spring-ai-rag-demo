package com.example.springairagdemo.config;

import com.example.springairagdemo.security.LoginUser;
import com.example.springairagdemo.security.UserContext;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * JWT 认证过滤器，拦截所有 /api/** 请求（除 login/register 外）验证 Token。
 * 认证通过后将当前用户注入 {@link UserContext}（ThreadLocal），请求结束自动清理。
 */
@Slf4j
@Component
public class JwtAuthenticationFilter implements Filter {

    private final JwtUtil jwtUtil;

    /** 无需认证的白名单路径 */
    private static final List<String> WHITELIST = Arrays.asList(
            "/api/login",
            "/api/register",
            "/api/logout"
    );

    public JwtAuthenticationFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String path = httpRequest.getRequestURI();

        // 白名单直接放行
        if (isWhitelisted(path)) {
            chain.doFilter(request, response);
            return;
        }

        // 仅对 /api/ 开头的请求校验 Token
        if (!path.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }

        // 从 Authorization 头提取 Token
        String authHeader = httpRequest.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            sendUnauthorized(httpResponse, "未提供认证令牌");
            return;
        }

        String token = authHeader.substring(7);
        if (!jwtUtil.validateToken(token)) {
            sendUnauthorized(httpResponse, "认证令牌无效或已过期");
            return;
        }

        // 将用户信息注入 Request 属性 + UserContext（ThreadLocal），供 Controller / Service / 权限切面使用
        Long userId = jwtUtil.getUserId(token);
        String username = jwtUtil.getUsername(token);
        httpRequest.setAttribute("userId", userId);
        httpRequest.setAttribute("username", username);
        try {
            UserContext.set(new LoginUser(userId, username));
            chain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }

    private boolean isWhitelisted(String path) {
        return WHITELIST.stream().anyMatch(path::equals);
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\"" + message + "\"}");
    }
}
