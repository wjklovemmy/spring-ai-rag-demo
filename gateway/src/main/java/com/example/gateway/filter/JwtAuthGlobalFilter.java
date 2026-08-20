package com.example.gateway.filter;

import com.example.gateway.security.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * 网关统一认证过滤器（方案：认证集中到网关，RAG 只消费身份）：
 * <ul>
 *   <li>白名单路径（login/register/logout/refresh）直接放行；</li>
 *   <li>其余 /api/** 校验 Authorization Bearer Access Token + Redis 黑名单；</li>
 *   <li>校验通过后：剥离客户端伪造的 X-User-* 头，注入网关解析出的真实身份，
 *       并附带内部信任头 {@code X-Gateway-Token}，RAG 据此确认请求来自本网关；</li>
 *   <li>同时透传真实客户端 IP 到 X-Forwarded-For，供下游审计。</li>
 * </ul>
 * 401 响应格式与 RAG 原过滤器一致（code=TOKEN_EXPIRED 触发前端自动刷新）。
 */
@Slf4j
@Component
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    /** 转发身份头：userId / username / 网关信任令牌 */
    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USERNAME = "X-Username";
    public static final String HEADER_GATEWAY_TOKEN = "X-Gateway-Token";

    private static final String BLACKLIST_PREFIX = "auth:blacklist:";

    /** 无需认证的路径 */
    private static final List<String> WHITELIST = Arrays.asList(
            "/api/login",
            "/api/register",
            "/api/logout",
            "/api/refresh"
    );

    private final JwtUtil jwtUtil;
    private final ReactiveStringRedisTemplate redisTemplate;

    @Value("${gateway.internal-token}")
    private String gatewayToken;

    public JwtAuthGlobalFilter(JwtUtil jwtUtil, ReactiveStringRedisTemplate redisTemplate) {
        this.jwtUtil = jwtUtil;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // 白名单：登录/注册/登出/刷新 不校验（由 RAG 内部处理）
        if (isWhitelisted(path)) {
            return chain.filter(exchange);
        }
        // 仅对 /api/ 开头的请求校验
        if (!path.startsWith("/api/")) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange, "未提供认证令牌", "TOKEN_INVALID");
        }
        String token = authHeader.substring(7).trim();
        if (token.isEmpty()) {
            return unauthorized(exchange, "未提供认证令牌", "TOKEN_INVALID");
        }

        // 先做无阻塞校验，再查 Redis 黑名单（登出后立即失效）
        if (!jwtUtil.validateToken(token)) {
            String code = jwtUtil.isTokenExpired(token) ? "TOKEN_EXPIRED" : "TOKEN_INVALID";
            String message = jwtUtil.isTokenExpired(token) ? "认证令牌已过期" : "认证令牌无效";
            return unauthorized(exchange, message, code);
        }

        String blacklistKey = BLACKLIST_PREFIX + DigestUtils.md5DigestAsHex(token.getBytes(StandardCharsets.UTF_8));
        return redisTemplate.hasKey(blacklistKey)
                .defaultIfEmpty(false)
                .flatMap(blacklisted -> {
                    if (Boolean.TRUE.equals(blacklisted)) {
                        return unauthorized(exchange, "认证令牌已失效", "TOKEN_INVALID");
                    }
                    return forwardWithIdentity(exchange, chain, token);
                });
    }

    /** 剥离客户端伪造的 X-User-* 头，注入真实身份与网关信任令牌 */
    private Mono<Void> forwardWithIdentity(ServerWebExchange exchange, GatewayFilterChain chain, String token) {
        Long userId = jwtUtil.getUserId(token);
        String username = jwtUtil.getUsername(token);
        String clientIp = resolveClientIp(exchange);

        var mutated = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(HEADER_USER_ID);
                    headers.remove(HEADER_USERNAME);
                    headers.remove(HEADER_GATEWAY_TOKEN);
                    headers.set(HEADER_USER_ID, String.valueOf(userId));
                    headers.set(HEADER_USERNAME, username);
                    headers.set(HEADER_GATEWAY_TOKEN, gatewayToken);
                    if (clientIp != null) {
                        headers.set("X-Forwarded-For", clientIp);
                    }
                })
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    private String resolveClientIp(ServerWebExchange exchange) {
        var address = exchange.getRequest().getRemoteAddress();
        return address == null ? null : address.getAddress().getHostAddress();
    }

    private boolean isWhitelisted(String path) {
        return WHITELIST.stream().anyMatch(path::equals);
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message, String code) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"success\":false,\"message\":\"" + message + "\",\"code\":\"" + code + "\"}";
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public int getOrder() {
        // 认证必须最先执行（早于路由），确保下游只收到合法身份
        return -200;
    }
}
