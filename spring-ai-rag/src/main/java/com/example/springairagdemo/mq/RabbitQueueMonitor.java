package com.example.springairagdemo.mq;

import com.example.springairagdemo.config.RabbitConfig;
import com.example.springairagdemo.config.RagConfigProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RabbitMQ 队列积压监控（Ready 消息数告警）。
 * <p>
 * 每 {@code rag.mq-monitor.interval-ms}（默认 30s）轮询 RabbitMQ Management HTTP API，
 * 读取业务队列 {@link RabbitConfig#EMBEDDING_TASK_QUEUE} 的：
 * <ul>
 *   <li>{@code messages_ready}：待消费消息数（积压的直接指标）</li>
 *   <li>{@code messages_unacknowledged}：正在处理/重试中的消息数（消费者卡死时该值会持续偏高）</li>
 * </ul>
 * Ready 数超过 {@code rag.mq-monitor.ready-threshold}（默认 50）判定为积压：
 * <ul>
 *   <li>打 ERROR 日志（持续积压只告警一次，恢复到阈值内自动解除并记恢复日志）；</li>
 *   <li>配置了 {@code rag.mq-monitor.webhook-url} 时 POST 企业微信/钉钉/飞书机器人消息。</li>
 * </ul>
 * 说明：
 * <ul>
 *   <li>Management API 不可用（如 RabbitMQ 未启动）仅降级为 warn 日志，不影响主流程；</li>
 *   <li>多实例部署时每个实例都会轮询，告警动作会重复触发——如需收敛可在轮询外层加分布式锁
 *       （本项目为本地 demo，告警动作幂等，不做收敛）。</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.mq-monitor", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RabbitQueueMonitor {

    /** 队列查询路径：vhost 默认 "/" 必须编码为 %2F。
     *  注意必须用 URI.create 传入（而非字符串模板）：RestClient 默认对 URI 模板再编码，
     *  会把 %2F 二次编码为 %252F，Management API 按字面 vhost "%2F" 查询返回 404。 */
    private static final URI QUEUE_URI = URI.create("/api/queues/%2F/" + RabbitConfig.EMBEDDING_TASK_QUEUE);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RagConfigProperties config;
    private final RestClient managementClient;
    /** 告警状态：true 表示当前正处于积压告警中（用于去抖，只告警一次） */
    private final AtomicBoolean alerting = new AtomicBoolean(false);

    public RabbitQueueMonitor(RagConfigProperties config) {
        this.config = config;
        RagConfigProperties.MqMonitor m = config.getMqMonitor();
        this.managementClient = RestClient.builder()
                .baseUrl(m.getManagementUrl())
                .defaultHeaders(headers -> headers.setBasicAuth(
                        m.getManagementUsername(), m.getManagementPassword()))
                .build();
    }

    /**
     * 定时检查业务队列深度：Ready 超阈值触发积压告警，恢复后自动解除。
     * 异常不抛出（避免调度线程频繁报错），降级为 warn 日志。
     */
    @Scheduled(fixedDelayString = "${rag.mq-monitor.interval-ms:30000}")
    public void checkQueueDepth() {
        try {
            QueueDepth depth = queryQueueDepth();
            long threshold = config.getMqMonitor().getReadyThreshold();
            // 每次轮询输出结果：确认监控在工作（无积压时也打印，否则正常态完全静默难以判断存活）
            log.info("RabbitMQ 队列监控: queue={} ready={} unacked={} threshold={}",
                    RabbitConfig.EMBEDDING_TASK_QUEUE, depth.ready(), depth.unacked(), threshold);
            if (depth.ready() > threshold) {
                if (alerting.compareAndSet(false, true)) {
                    String msg = buildAlertMessage(depth, threshold);
                    log.error("RabbitMQ 消息积压告警: {}", msg);
                    sendWebhook("【RabbitMQ 消息积压告警】" + msg);
                }
            } else if (alerting.compareAndSet(true, false)) {
                log.info("RabbitMQ 消息积压已恢复: ready={}, unacked={}, threshold={}",
                        depth.ready(), depth.unacked(), threshold);
            }
        } catch (Exception e) {
            // 404 常见原因：① vhost 编码（应 /api/queues/%2F/...，勿用 /api/queues///...）
            // ② 队列未声明（spring-ai-rag 服务尚未启动成功，RabbitConfig 声明队列需连上 broker）
            // ③ 队列名拼写（应为 rag.embedding.task.queue）
            log.warn("查询 RabbitMQ 队列深度失败（检查 vhost 编码 / 队列是否已声明）: {}", e.getMessage());
        }
    }

    /**
     * 查询业务队列的 Ready / Unacked 消息数。
     * <p>
     * 注意：响应体用 JDK 原生 {@link Map} 接收，而不是 Jackson 2 的 {@code JsonNode}——
     * 本项目是 Spring Boot 4（Jackson 3，包名 {@code tools.jackson}），RestClient 的 JSON
     * converter 无法反序列化到 Jackson 2 类型（会报 Type definition error）。
     */
    @SuppressWarnings("unchecked")
    private QueueDepth queryQueueDepth() {
        Map<String, Object> body = managementClient.get()
                .uri(QUEUE_URI)
                .retrieve()
                .body(Map.class);
        if (body == null) {
            throw new IllegalStateException("Management API 响应为空");
        }
        return new QueueDepth(asLong(body.get("messages_ready")), asLong(body.get("messages_unacknowledged")));
    }

    /** JSON 数字（Integer/Long）安全转 long，缺失或非法返回 0 */
    private long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {
                // 非数字字段，按 0 处理
            }
        }
        return 0L;
    }

    private String buildAlertMessage(QueueDepth depth, long threshold) {
        return String.format("队列=%s ready=%d(阈值%d) unacked=%d 时间=%s",
                RabbitConfig.EMBEDDING_TASK_QUEUE, depth.ready(), threshold, depth.unacked(),
                LocalDateTime.now().format(TIME_FORMAT));
    }

    /** 发送告警 Webhook（企业微信/钉钉/飞书机器人通用 text 消息格式），未配置则仅日志 */
    private void sendWebhook(String content) {
        String url = config.getMqMonitor().getWebhookUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            Map<String, Object> body = Map.of(
                    "msgtype", "text",
                    "text", Map.of("content", content));
            managementClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("积压告警已推送 webhook");
        } catch (Exception e) {
            log.warn("发送积压告警 webhook 失败: {}", e.getMessage());
        }
    }

    /** 队列深度快照 */
    private record QueueDepth(long ready, long unacked) {
    }
}
