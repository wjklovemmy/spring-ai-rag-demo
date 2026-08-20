package com.example.springairagdemo.config;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 工具类：双 Token 机制（Access Token + Refresh Token）。
 * <ul>
 *   <li>Access Token：有效期短（默认 30 分钟），用于正常 API 鉴权，claim 含 type=access</li>
 *   <li>Refresh Token：有效期长（默认 7 天），用于换取新的双 Token，claim 含 type=refresh</li>
 * </ul>
 * 前端在收到 401 且 code=TOKEN_EXPIRED 时，使用 Refresh Token 调用 /api/refresh 自动续期。
 */
@Slf4j
@Component
public class JwtUtil {

    /** Token 类型 claim 的 key */
    public static final String CLAIM_TYPE = "type";
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private final SecretKey secretKey;
    private final long expirationMs;
    private final long refreshExpirationMs;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expiration-ms}") long expirationMs,
                   @Value("${jwt.refresh-expiration-ms}") long refreshExpirationMs) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    /**
     * 生成 Access Token（用于接口鉴权）
     */
    public String generateAccessToken(Long userId, String username) {
        return generateToken(userId, username, TYPE_ACCESS, expirationMs);
    }

    /**
     * 生成 Refresh Token（用于续期换取新 Token）
     */
    public String generateRefreshToken(Long userId, String username) {
        return generateToken(userId, username, TYPE_REFRESH, refreshExpirationMs);
    }

    private String generateToken(Long userId, String username, String type, long expireMs) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expireMs);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim(CLAIM_TYPE, type)
                // jti 唯一标识：作为 Redis 中 Refresh Token 会话的 key，支持原子消费与重放检测
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }

    /**
     * 从 Token 中解析出 Claims（签名或过期异常会抛出 JwtException / ExpiredJwtException）
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 验证 Access Token 是否有效（要求类型为 access 且未过期）
     */
    public boolean validateToken(String token) {
        try {
            return TYPE_ACCESS.equals(parseToken(token).get(CLAIM_TYPE, String.class));
        } catch (JwtException e) {
            log.debug("JWT 验证失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 验证 Refresh Token 是否有效（要求类型为 refresh 且未过期）
     */
    public boolean validateRefreshToken(String token) {
        try {
            return TYPE_REFRESH.equals(parseToken(token).get(CLAIM_TYPE, String.class));
        } catch (JwtException e) {
            log.debug("Refresh Token 验证失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 判断 Token 是否已过期（区别于签名错误/格式错误等无效情况）
     */
    public boolean isTokenExpired(String token) {
        try {
            parseToken(token);
            return false;
        } catch (ExpiredJwtException e) {
            return true;
        } catch (JwtException e) {
            return false;
        }
    }

    /**
     * 从 Token 中获取用户 ID
     */
    public Long getUserId(String token) {
        return Long.valueOf(parseToken(token).getSubject());
    }

    /**
     * 从 Token 中获取用户名
     */
    public String getUsername(String token) {
        return parseToken(token).get("username", String.class);
    }

    /**
     * 获取 Token 的过期时间（存储到 Redis 时计算 TTL 用）
     */
    public Date getExpiration(String token) {
        return parseToken(token).getExpiration();
    }

    /**
     * 获取 Token 的 jti（唯一 ID），作为 Redis 中 Refresh Token 会话的 key
     */
    public String getJti(String token) {
        return parseToken(token).getId();
    }

    /**
     * 获取 Token 剩余有效秒数（黑名单 / 会话 TTL 用）
     */
    public long getRemainingTtlSeconds(String token) {
        long ms = getExpiration(token).getTime() - System.currentTimeMillis();
        return Math.max(ms / 1000, 0);
    }
}
