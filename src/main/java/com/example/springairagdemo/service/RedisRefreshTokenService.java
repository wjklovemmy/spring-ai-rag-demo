package com.example.springairagdemo.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.Set;

/**
 * Refresh Token 会话管理（Redis 实现）：
 * <ul>
 *   <li>key: {@code auth:refresh:{sha256(token)}}，value: userId，TTL = Token 剩余有效期，到期自动消失</li>
 *   <li>保存（登录/注册/刷新）-> SETEX；校验（续期）-> EXISTS；撤销（登出/轮换）-> DEL</li>
 * </ul>
 * 相比 MySQL 方案：无需建表、无定时清理任务，TTL 天然契合「过期即失效」。
 */
@Slf4j
@Service
public class RedisRefreshTokenService {

    private static final String KEY_PREFIX = "auth:refresh:";

    private final StringRedisTemplate redisTemplate;

    public RedisRefreshTokenService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 保存一个新的有效 Refresh Token（TTL 按 token 剩余有效期设置，到期自动清除）
     */
    public void saveToken(Long userId, String refreshToken, Date expiresAt) {
        long ttlSeconds = (expiresAt.getTime() - System.currentTimeMillis()) / 1000;
        if (ttlSeconds <= 0) {
            return;
        }
        redisTemplate.opsForValue().set(
                key(refreshToken), String.valueOf(userId), Duration.ofSeconds(ttlSeconds));
    }

    /**
     * 判断 Refresh Token 是否仍处于服务端有效状态（未被登出撤销 / 未被轮换替换）
     */
    public boolean isActive(String refreshToken) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key(refreshToken)));
    }

    /**
     * 撤销指定的 Refresh Token（登出、刷新轮换时调用）
     */
    public void revoke(String refreshToken) {
        redisTemplate.delete(key(refreshToken));
    }

    /**
     * 撤销某用户的全部 Refresh Token（改密 / 强制下线等场景可用）
     */
    public void revokeAllByUserId(Long userId) {
        String prefix = KEY_PREFIX;
        Set<String> keys = redisTemplate.keys(prefix + "*");
        if (keys == null || keys.isEmpty()) {
            return;
        }
        String uid = String.valueOf(userId);
        int removed = 0;
        for (String k : keys) {
            if (uid.equals(redisTemplate.opsForValue().get(k))) {
                redisTemplate.delete(k);
                removed++;
            }
        }
        log.info("已撤销用户 {} 的 {} 个 Refresh Token", userId, removed);
    }

    private String key(String refreshToken) {
        return KEY_PREFIX + DigestUtils.md5DigestAsHex(
                refreshToken.getBytes(StandardCharsets.UTF_8));
    }
}
