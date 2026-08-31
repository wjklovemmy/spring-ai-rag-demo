package com.example.springairagdemo.memory;

import com.example.springairagdemo.config.RagConfigProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对话记忆膨胀监控：定时 SCAN {@code rag:chat:memory:*} key，统计会话数与总占用内存
 * （MEMORY USAGE 逐 key 汇总），任一超过阈值即告警（ERROR 日志 + 可选 Webhook）。
 *
 * <p>用于兜底发现两类异常：① 会话 key 无限增长（如 TTL 未生效 / 大量匿名会话）；
 * ② 单会话异常膨胀（窗口压缩失效 / 超大消息）。持续超阈值去抖只告警一次，恢复自动解除。
 * 与 {@code RabbitQueueMonitor} 同一监控模式（@Scheduled + 阈值 + 去抖 + Webhook）。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.memory-monitor", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisMemoryMonitor {

    private static final String KEY_PATTERN = "rag:chat:memory:*";

    private final RagConfigProperties ragConfig;
    private final StringRedisTemplate redisTemplate;
    private final RestClient webhookClient;
    /** 告警去抖：持续超阈值只告警一次，恢复后重置 */
    private final AtomicBoolean alerting = new AtomicBoolean(false);

    public RedisMemoryMonitor(RagConfigProperties ragConfig, StringRedisTemplate redisTemplate) {
        this.ragConfig = ragConfig;
        this.redisTemplate = redisTemplate;
        this.webhookClient = RestClient.create();
    }

    @Scheduled(fixedDelayString = "${rag.memory-monitor.interval-ms:60000}")
    public void checkMemoryUsage() {
        RagConfigProperties.MemoryMonitor cfg = ragConfig.getMemoryMonitor();
        try {
            MemoryStats stats = scanStats();
            long keyThreshold = cfg.getKeyCountThreshold();
            long bytesThreshold = cfg.getTotalBytesThreshold();
            boolean over = stats.keyCount() > keyThreshold || stats.totalBytes() > bytesThreshold;
            if (over) {
                String msg = buildAlertMessage(stats, keyThreshold, bytesThreshold);
                if (alerting.compareAndSet(false, true)) {
                    log.error("对话记忆膨胀告警：{}", msg);
                    sendWebhook(cfg.getWebhookUrl(), "【对话记忆膨胀告警】" + msg);
                } else {
                    log.error("对话记忆仍超阈值：{}", msg);
                }
            } else {
                if (alerting.compareAndSet(true, false)) {
                    log.info("对话记忆已恢复：keys={} totalBytes={} maxKey={}({}B)",
                            stats.keyCount(), stats.totalBytes(), stats.maxKey(), stats.maxBytes());
                } else {
                    log.debug("对话记忆监控正常：keys={} totalBytes={}（阈值 {} keys / {}B）",
                            stats.keyCount(), stats.totalBytes(), keyThreshold, bytesThreshold);
                }
            }
        } catch (Exception e) {
            log.warn("对话记忆监控检查失败：{}", e.getMessage());
        }
    }

    /** SCAN 全部记忆 key，统计 key 数、总占用、最大 key */
    private MemoryStats scanStats() {
        long count = 0;
        long total = 0;
        String maxKey = "";
        long maxBytes = 0;
        ScanOptions options = ScanOptions.scanOptions().match(KEY_PATTERN).count(500).build();
        try (Cursor<byte[]> cursor = redisTemplate.execute(
                (RedisCallback<Cursor<byte[]>>) conn -> conn.scan(options))) {
            if (cursor == null) {
                return new MemoryStats(0, 0, "", 0);
            }
            while (cursor.hasNext()) {
                byte[] key = cursor.next();
                Long usage = memoryUsage(key);
                long bytes = usage == null ? 0 : usage;
                count++;
                total += bytes;
                if (bytes > maxBytes) {
                    maxBytes = bytes;
                    maxKey = new String(key);
                }
            }
        }
        return new MemoryStats(count, total, maxKey, maxBytes);
    }

    /** MEMORY USAGE key：返回 key 占用内存字节数（Redis 4.0+）；key 不存在/已过期返回 null */
    private Long memoryUsage(byte[] key) {
        return redisTemplate.execute((RedisCallback<Long>) conn -> {
            Object resp = conn.execute("MEMORY", new byte[][]{
                    "USAGE".getBytes(java.nio.charset.StandardCharsets.UTF_8), key});
            // RESP2 协议返回 bulk string（"123"），RESP3 返回整数，兼容两种
            if (resp instanceof byte[] b) {
                return Long.parseLong(new String(b));
            }
            if (resp instanceof Number n) {
                return n.longValue();
            }
            return null;
        });
    }

    private String buildAlertMessage(MemoryStats stats, long keyThreshold, long bytesThreshold) {
        return String.format("key数=%d(阈值%d)，总占用=%.2fMB(阈值%.2fMB)，最大key=%s(%.2fMB)",
                stats.keyCount(), keyThreshold,
                stats.totalBytes() / 1048576.0, bytesThreshold / 1048576.0,
                stats.maxKey(), stats.maxBytes() / 1048576.0);
    }

    /** 发送 Webhook（企业微信/钉钉/飞书机器人 text 消息），失败仅记日志，不影响主流程 */
    private void sendWebhook(String webhookUrl, String content) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }
        try {
            String body = "{\"msgtype\":\"text\",\"text\":{\"content\":\"" + escapeJson(content) + "\"}}";
            webhookClient.post().uri(webhookUrl)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("对话记忆告警 Webhook 发送失败：{}", e.getMessage());
        }
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    /** 扫描统计结果 */
    private record MemoryStats(long keyCount, long totalBytes, String maxKey, long maxBytes) {}
}
