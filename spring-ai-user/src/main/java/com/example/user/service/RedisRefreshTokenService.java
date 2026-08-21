package com.example.user.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Refresh Token 会话管理（Redis 实现，并发安全）：
 * <ul>
 *   <li>Refresh 会话 key: {@code auth:refresh:{jti}} -> userId，TTL = Token 剩余有效期，到期自动消失</li>
 *   <li>Access 黑名单 key: {@code auth:blacklist:{md5(token)}} -> "1"，TTL = Token 剩余有效期（登出后立即失效）</li>
 * </ul>
 * 关键设计：
 * <ul>
 *   <li>轮换使用 {@link #consume(String)}（Redis GETDEL，原子操作）：同一 Refresh Token 全局只能成功消费一次，
 *       消除「检查存在 + 删除」两步之间的并发竞态与重放窗口</li>
 *   <li>撤销用户全部会话用 SCAN 而非 KEYS，避免阻塞 Redis</li>
 * </ul>
 */
@Slf4j
@Service
public class RedisRefreshTokenService {

    private static final String REFRESH_PREFIX = "auth:refresh:";
    private static final String BLACKLIST_PREFIX = "auth:blacklist:";

    private final StringRedisTemplate redisTemplate;

    public RedisRefreshTokenService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 保存一个新的有效 Refresh Token（登录 / 注册 / 续期时调用）
     *
     * @param userId     用户 ID
     * @param jti        Refresh Token 的 jti（唯一标识）
     * @param ttlSeconds 剩余有效秒数
     */
    public void saveToken(Long userId, String jti, long ttlSeconds) {
        if (ttlSeconds <= 0) {
            return;
        }
        redisTemplate.opsForValue().set(
                REFRESH_PREFIX + jti, String.valueOf(userId), Duration.ofSeconds(ttlSeconds));
    }

    /**
     * 原子消费 Refresh Token（GETDEL）：
     * 同一 jti 只能被成功消费一次，返回其 userId；返回 null 表示该 token 已被消费 /
     * 已被登出撤销 / 已被轮换替换（即重放或过期会话）。
     */
    public Long consume(String jti) {
        String v = redisTemplate.opsForValue().getAndDelete(REFRESH_PREFIX + jti);
        return v == null ? null : Long.valueOf(v);
    }

    /**
     * Access Token 加入黑名单（登出时调用），TTL = 剩余有效期，到期自动失效
     */
    public void blacklistAccessToken(String accessToken, long ttlSeconds) {
        if (ttlSeconds <= 0) {
            return;
        }
        redisTemplate.opsForValue().set(
                blacklistKey(accessToken), "1", Duration.ofSeconds(ttlSeconds));
    }

    /**
     * 判断 Access Token 是否已被列入黑名单（登出后立即失效）
     */
    public boolean isAccessTokenBlacklisted(String accessToken) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(blacklistKey(accessToken)));
    }

    /**
     * 撤销某用户的全部 Refresh Token（登出 / 强制下线）。
     * 使用 SCAN 而非 KEYS，避免大 key 量时阻塞 Redis。
     */
    public void revokeAllByUserId(Long userId) {
        String uid = String.valueOf(userId);
        int removed = 0;
        ScanOptions options = ScanOptions.scanOptions()
                .match(REFRESH_PREFIX + "*").count(100).build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                if (uid.equals(redisTemplate.opsForValue().get(key))) {
                    redisTemplate.delete(key);
                    removed++;
                }
            }
        }
        if (removed > 0) {
            log.info("已撤销用户 {} 的 {} 个 Refresh Token", userId, removed);
        }
    }

    private String blacklistKey(String accessToken) {
        return BLACKLIST_PREFIX + DigestUtils.md5DigestAsHex(
                accessToken.getBytes(StandardCharsets.UTF_8));
    }
}
