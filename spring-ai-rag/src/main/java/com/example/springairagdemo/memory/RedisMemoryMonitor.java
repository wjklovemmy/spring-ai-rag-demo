package com.example.springairagdemo.memory;

import com.example.springairagdemo.config.RagConfigProperties;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.IntegerOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    /**
     * MEMORY 命令关键字：Lettuce 未内置 MEMORY USAGE 高层 API，需原生 dispatch。
     * 注意不能用 RedisConnection.execute("MEMORY", ...)——它以 ByteArrayOutput 解码，
     * 而 MEMORY USAGE 返回整数回复（:1120），ByteArrayOutput 不支持 set(long)，
     * 会抛 UnsupportedOperationException("does not support set(long)")。
     */
    private static final ProtocolKeyword MEMORY_COMMAND = new ProtocolKeyword() {
        @Override
        public byte[] getBytes() {
            return name().getBytes(StandardCharsets.US_ASCII);
        }

        @Override
        public String name() {
            return "MEMORY";
        }

        @Override
        public String toString() {
            return name();
        }
    };

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
            // 打印完整堆栈：仅 e.getMessage() 会得到包装消息 "Unknown redis exception"，掩盖真实根因
            log.warn("对话记忆监控检查失败", e);
        }
    }

    /** SCAN 全部记忆 key，统计 key 数、总占用、最大 key */
    private MemoryStats scanStats() {
        long count = 0;
        long total = 0;
        String maxKey = "";
        long maxBytes = 0;
        ScanOptions options = ScanOptions.scanOptions().match(KEY_PATTERN).count(500).build();
        // 关键：Lettuce 下 SCAN 游标必须用"粘性连接"（executeWithStickyConnection）迭代——
        // 普通 execute() 在回调返回后即归还/关闭连接，游标后续续期 SCAN 会失败，
        // 抛 RedisSystemException("Unknown redis exception")，根因被包装在 cause 中。
        try (Cursor<byte[]> cursor = redisTemplate.executeWithStickyConnection(
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

    /**
     * MEMORY USAGE key：返回 key 占用内存字节数（Redis 4.0+）；key 不存在/已过期返回 null。
     * 通过 Lettuce 原生 dispatch + IntegerOutput 解码整数回复（见 {@link #MEMORY_COMMAND} 说明）。
     */
    private Long memoryUsage(byte[] key) {
        return redisTemplate.execute((RedisCallback<Long>) conn -> {
            // key 已过期/不存在时 MEMORY USAGE 返回 nil，IntegerOutput 不接受 nil，先用 EXISTS 短路
            if (!Boolean.TRUE.equals(conn.keyCommands().exists(key))) {
                return null;
            }
            RedisAsyncCommands<byte[], byte[]> lettuce =
                    (RedisAsyncCommands<byte[], byte[]>) conn.getNativeConnection();
            RedisFuture<Long> future = lettuce.dispatch(
                    MEMORY_COMMAND,
                    new IntegerOutput<>(ByteArrayCodec.INSTANCE),
                    new CommandArgs<>(ByteArrayCodec.INSTANCE)
                            .add("USAGE".getBytes(StandardCharsets.US_ASCII))
                            .addKey(key));
            try {
                return future.get(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("MEMORY USAGE 被中断", e);
            } catch (ExecutionException | TimeoutException e) {
                throw new IllegalStateException("MEMORY USAGE 执行失败", e);
            }
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
