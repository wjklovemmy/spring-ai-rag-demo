package com.example.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * JWT 校验工具（网关侧只做校验，不签发）。
 * 密钥必须与 RAG 服务 {@code jwt.secret} 完全一致，否则无法通过签名校验。
 * 签发仍由 RAG 服务的 {@code JwtUtil} 完成（登录/注册/刷新）。
 */
@Slf4j
@Component
public class JwtUtil {

    public static final String CLAIM_TYPE = "type";
    public static final String TYPE_ACCESS = "access";

    private final SecretKey secretKey;

    public JwtUtil(@Value("${jwt.secret}") String secret) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    private Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** 验证 Access Token 是否有效（要求类型为 access 且未过期） */
    public boolean validateToken(String token) {
        try {
            return TYPE_ACCESS.equals(parseToken(token).get(CLAIM_TYPE, String.class));
        } catch (JwtException e) {
            log.debug("JWT 验证失败: {}", e.getMessage());
            return false;
        }
    }

    /** 判断 Token 是否已过期（区别于签名错误/格式错误等无效情况） */
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

    public Long getUserId(String token) {
        return Long.valueOf(parseToken(token).getSubject());
    }

    public String getUsername(String token) {
        return parseToken(token).get("username", String.class);
    }
}
