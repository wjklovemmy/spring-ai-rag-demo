package com.example.user.config;

import com.example.user.security.LoginUser;
import com.example.user.security.UserContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * 网关信任过滤器（认证已上移到 Gateway，本服务只消费身份）：
 * <ul>
 *   <li>校验请求携带网关内部信任令牌 {@code X-Gateway-Token}，确认请求来自本服务网关
 *       （防止绕过网关直接访问本服务伪造身份）；</li>
 *   <li>从网关注入的 {@code X-User-Id} / {@code X-Username} 头读取当前用户，
 *       注入 {@link UserContext}（ThreadLocal）与 Request 属性，请求结束自动清理；</li>
 *   <li>白名单路径（login/register/logout/refresh）直接放行——这些接口本身无需身份。</li>
 * </ul>
 */
@Slf4j
@Component
public class GatewayIdentityFilter implements Filter {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USERNAME = "X-Username";
    public static final String HEADER_GATEWAY_TOKEN = "X-Gateway-Token";

    /** 无需身份的白名单路径（与网关侧保持一致） */
    private static final List<String> WHITELIST = Arrays.asList(
            "/api/login",
            "/api/register",
            "/api/logout",
            "/api/refresh"
    );

    @Value("${gateway.internal-token}")
    private String gatewayToken;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String path = httpRequest.getRequestURI();

        // 白名单直接放行（登录/注册/刷新/登出不消费身份）
        if (isWhitelisted(path)) {
            chain.doFilter(request, response);
            return;
        }
        // 仅对 /api/ 开头的请求校验
        if (!path.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }

        // 校验网关信任令牌：只有携带正确令牌的请求（即经由网关转发）才被信任，
        // 否则视为绕过网关直连，拒绝（防伪造 X-User-* 头）
        String token = httpRequest.getHeader(HEADER_GATEWAY_TOKEN);
        if (gatewayToken == null || !gatewayToken.equals(token)) {
            sendUnauthorized(httpResponse, "拒绝访问：仅允许通过网关访问", "GATEWAY_REQUIRED");
            return;
        }

        // 从网关注入的身份头读取当前用户
        String userIdStr = httpRequest.getHeader(HEADER_USER_ID);
        String username = httpRequest.getHeader(HEADER_USERNAME);
        if (userIdStr == null || userIdStr.isBlank() || username == null || username.isBlank()) {
            sendUnauthorized(httpResponse, "认证信息缺失", "TOKEN_INVALID");
            return;
        }

        Long userId = Long.valueOf(userIdStr);
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

    private void sendUnauthorized(HttpServletResponse response, String message, String code) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"success\":false,\"message\":\"" + message + "\",\"code\":\"" + code + "\"}");
    }
}
