package com.example.springairagdemo.mq;

import com.example.springairagdemo.config.RabbitConfig;
import com.example.springairagdemo.config.RagConfigProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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

    private static final String QUEUE_URL = "/api/queues/%2F/" + RabbitConfig.EMBEDDING_TASK_QUEUE;
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
            log.warn("查询 RabbitMQ 队列深度失败（Management API 不可用？）: {}", e.getMessage());
        }
    }

    /** 查询业务队列的 Ready / Unacked 消息数 */
    private QueueDepth queryQueueDepth() {
        JsonNode node = managementClient.get()
                .uri(QUEUE_URL)
                .retrieve()
                .body(JsonNode.class);
        if (node == null) {
            throw new IllegalStateException("Management API 响应为空");
        }
        long ready = node.path("messages_ready").asLong(0);
        long unacked = node.path("messages_unacknowledged").asLong(0);
        return new QueueDepth(ready, unacked);
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
