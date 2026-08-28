package com.example.springairagdemo.mq;

import com.example.springairagdemo.config.RabbitConfig;
import com.example.springairagdemo.service.KnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Embedding 任务消费者（业务队列）。
 * <p>
 * 消费语义：
 * <ul>
 *   <li><b>幂等预检</b>：任务已终结（SUCCESS/FAILED）或不存在时直接 ack 跳过——
 *       RabbitMQ at-least-once 投递下，消费者 ack 前崩溃会重复投递同一消息，
 *       靠数据库任务状态去重，满足"消息不重复消费"；</li>
 *   <li><b>失败重试</b>：处理异常向上抛，由 Spring AMQP 重试（默认最多 3 次重试，
 *       见 {@code spring.rabbitmq.listener.simple.retry.max-attempts=4}）；
 *       重试仍失败时消息被拒绝（{@code default-requeue-rejected=false}），
 *       按业务队列声明的死信配置自动进入死信队列；</li>
 *   <li><b>幂等兜底</b>：处理全程增量（chunk diff）+ claimTask CAS 抢占——
 *       即使极端情况下重复处理，结果也是幂等的（已完成的 chunk 跳过）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingTaskConsumer {

    private final KnowledgeDocumentService knowledgeDocumentService;

    @RabbitListener(queues = RabbitConfig.EMBEDDING_TASK_QUEUE)
    public void consume(String taskId) throws Exception {
        Long id;
        try {
            id = Long.valueOf(taskId.trim());
        } catch (NumberFormatException e) {
            log.error("Embedding 任务消息格式非法，拒绝处理: {}", taskId);
            throw e; // 重试后进死信队列（死信消费者幂等处理）
        }
        if (!knowledgeDocumentService.shouldProcessTask(id)) {
            return; // 任务已终结/不存在 → ack 跳过（防重复投递导致的重复消费）
        }
        try {
            knowledgeDocumentService.processTask(id);
        } catch (Exception e) {
            log.error("Embedding 任务处理失败，等待消息重试: taskId={}", id, e);
            throw e; // 交给 Spring AMQP 重试拦截器
        }
    }
}
