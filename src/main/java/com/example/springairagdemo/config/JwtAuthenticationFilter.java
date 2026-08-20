package com.example.springairagdemo.config;

import com.example.springairagdemo.security.LoginUser;
import com.example.springairagdemo.security.UserContext;
import com.example.springairagdemo.service.RedisRefreshTokenService;
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
    private final RedisRefreshTokenService refreshTokenService;

    /** 无需认证的白名单路径 */
    private static final List<String> WHITELIST = Arrays.asList(
            "/api/login",
            "/api/register",
            "/api/logout",
            "/api/refresh"
    );

    public JwtAuthenticationFilter(JwtUtil jwtUtil, RedisRefreshTokenService refreshTokenService) {
        this.jwtUtil = jwtUtil;
        this.refreshTokenService = refreshTokenService;
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

        String token = authHeader.substring(7).trim();
        if (token.isEmpty()) {
            sendUnauthorized(httpResponse, "未提供认证令牌");
            return;
        }
        if (!jwtUtil.validateToken(token)) {
            // 区分「过期」与「无效」：前端仅对 TOKEN_EXPIRED 触发自动刷新
            if (jwtUtil.isTokenExpired(token)) {
                sendUnauthorized(httpResponse, "认证令牌已过期", "TOKEN_EXPIRED");
            } else {
                sendUnauthorized(httpResponse, "认证令牌无效", "TOKEN_INVALID");
            }
            return;
        }

        // 登出后 Access Token 已进黑名单 -> 立即失效（即使尚未到期）
        if (refreshTokenService.isAccessTokenBlacklisted(token)) {
            sendUnauthorized(httpResponse, "认证令牌已失效", "TOKEN_INVALID");
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
        sendUnauthorized(response, message, "TOKEN_INVALID");
    }

    private void sendUnauthorized(HttpServletResponse response, String message, String code) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"success\":false,\"message\":\"" + message + "\",\"code\":\"" + code + "\"}");
    }
}
