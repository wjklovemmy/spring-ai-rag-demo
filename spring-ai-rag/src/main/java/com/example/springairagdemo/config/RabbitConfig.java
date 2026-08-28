package com.example.springairagdemo.config;

import com.example.springairagdemo.service.KnowledgeDocumentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 基础设施：Embedding 任务队列 + 死信队列 + 生产者可靠性。
 * <p>
 * 队列与消息均持久化（durable），队列类型为 <b>Quorum</b>（Raft 复制队列）：
 * <ul>
 *   <li>业务队列 {@code rag.embedding.task.queue}（quorum）：收到任务消息即执行 PDF 解析/切分/向量化；
 *       声明死信交换机与死信路由键——消息重试 3 次仍失败（reject）时自动路由到死信队列</li>
 *   <li>死信队列 {@code rag.embedding.task.dlq}（quorum）：由死信消费者统一标记任务失败</li>
 * </ul>
 * Quorum 队列（需 RabbitMQ &gt;= 3.10 才能配置 DLX）：消息在多数副本（leader + followers）间复制，
 * 任一节点宕机不影响消息可用性，是 RabbitMQ 官方推荐的替代镜像队列的高可用方案
 * （classic mirroring 自 RabbitMQ 4.0 起已移除）。单节点下副本数=1，行为等价于普通持久化队列。
 * <p>
 * 生产者端可靠性（本类 {@link #rabbitTemplate(ConnectionFactory, ObjectProvider)}）：
 * <ul>
 *   <li>Publisher Confirm（{@code spring.rabbitmq.publisher-confirm-type=correlated}）：
 *       Broker 收到消息后异步 ack/nack，nack 回调将任务标记失败，消除"发送成功但 broker 未收到"盲区</li>
 *   <li>Publisher Return（{@code spring.rabbitmq.publisher-returns=true} + mandatory）：
 *       消息路由不到任何队列（交换机/绑定缺失、key 不匹配）时退回，回调将任务标记失败</li>
 * </ul>
 * 消息投递语义：RabbitMQ 默认 at-least-once（消费者 ack 前崩溃会重新投递），
 * 重复消费防护在消费端完成（任务状态预检 + claimTask CAS + 增量处理），
 * 见 {@code EmbeddingTaskConsumer} 与 {@code KnowledgeDocumentService}。
 */
@Slf4j
@Configuration
public class RabbitConfig {

    public static final String EMBEDDING_EXCHANGE = "rag.embedding.exchange";
    public static final String EMBEDDING_TASK_QUEUE = "rag.embedding.task.queue";
    public static final String EMBEDDING_TASK_DLQ = "rag.embedding.task.dlq";
    public static final String EMBEDDING_TASK_ROUTING_KEY = "rag.embedding.task";
    public static final String EMBEDDING_TASK_DLQ_ROUTING_KEY = "rag.embedding.task.dlq";

    /** 消息头：taskId（生产者投递时写入，Publisher Return 回调据此定位任务） */
    public static final String HEADER_TASK_ID = "x-task-id";

    @Bean
    public DirectExchange embeddingExchange() {
        return new DirectExchange(EMBEDDING_EXCHANGE, true, false);
    }

    /** 业务队列：Quorum（持久化 + 高可用），绑定死信交换机/路由键，重试耗尽的消息自动进入死信队列 */
    @Bean
    public Queue embeddingTaskQueue() {
        return QueueBuilder.durable(EMBEDDING_TASK_QUEUE)
                .quorum()
                .deadLetterExchange(EMBEDDING_EXCHANGE)
                .deadLetterRoutingKey(EMBEDDING_TASK_DLQ_ROUTING_KEY)
                .build();
    }

    /** 死信队列：Quorum（持久化 + 高可用） */
    @Bean
    public Queue embeddingTaskDlq() {
        return QueueBuilder.durable(EMBEDDING_TASK_DLQ).quorum().build();
    }

    @Bean
    public Binding embeddingTaskBinding(Queue embeddingTaskQueue, DirectExchange embeddingExchange) {
        return BindingBuilder.bind(embeddingTaskQueue).to(embeddingExchange)
                .with(EMBEDDING_TASK_ROUTING_KEY);
    }

    @Bean
    public Binding embeddingTaskDlqBinding(Queue embeddingTaskDlq, DirectExchange embeddingExchange) {
        return BindingBuilder.bind(embeddingTaskDlq).to(embeddingExchange)
                .with(EMBEDDING_TASK_DLQ_ROUTING_KEY);
    }

    /**
     * RabbitTemplate：开启 mandatory + Publisher Confirm / Return 回调。
     * <p>
     * 回调里通过 {@link ObjectProvider} 懒加载 {@link KnowledgeDocumentService}：
     * 避免与 {@code KnowledgeDocumentService → EmbeddingTaskProducer → RabbitTemplate} 的
     * 构造依赖形成循环引用；回调在 Broker 确认的异步线程执行，此时容器已刷新完成，可安全获取。
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         ObjectProvider<KnowledgeDocumentService> knowledgeDocumentServiceProvider) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        // mandatory：路由不到任何队列时消息退回（触发 returnsCallback），而不是静默丢弃
        template.setMandatory(true);
        // Publisher Confirm：broker 收到消息后异步 ack；nack（broker 拒收/网络异常）时标记任务失败
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (correlationData == null) {
                return;
            }
            String taskId = correlationData.getId();
            if (ack) {
                log.debug("MQ 消息发送确认成功: taskId={}", taskId);
                return;
            }
            log.error("MQ 消息发送确认失败: taskId={}, cause={}", taskId, cause);
            failTaskOnSendError(knowledgeDocumentServiceProvider, taskId, "MQ 发送确认失败: " + cause);
        });
        // Publisher Return：路由失败退回（交换机/绑定缺失、routing key 不匹配）
        template.setReturnsCallback(returned -> {
            String taskId = (String) returned.getMessage().getMessageProperties().getHeaders().get(HEADER_TASK_ID);
            log.error("MQ 消息路由失败（无匹配队列）: taskId={}, exchange={}, routingKey={}, replyText={}",
                    taskId, returned.getExchange(), returned.getRoutingKey(), returned.getReplyText());
            failTaskOnSendError(knowledgeDocumentServiceProvider, taskId,
                    "MQ 路由失败: " + returned.getReplyText());
        });
        return template;
    }

    /**
     * 发送失败兜底：解析 taskId 并委托 {@link KnowledgeDocumentService#markTaskSendFailed} 标记任务失败。
     * 服务不可用或 taskId 非法时仅告警（不抛出，避免影响 RabbitTemplate 回调线程）。
     */
    private void failTaskOnSendError(ObjectProvider<KnowledgeDocumentService> provider,
                                     String taskIdStr, String reason) {
        if (taskIdStr == null) {
            log.error("MQ 发送失败但消息缺少 taskId，仅告警: {}", reason);
            return;
        }
        try {
            Long taskId = Long.parseLong(taskIdStr);
            KnowledgeDocumentService svc = provider.getIfAvailable();
            if (svc == null) {
                log.error("KnowledgeDocumentService 不可用，发送失败未标记任务: taskId={}, reason={}",
                        taskId, reason);
                return;
            }
            svc.markTaskSendFailed(taskId, reason);
        } catch (NumberFormatException e) {
            log.error("MQ 发送失败，taskId 解析异常: taskIdStr={}, reason={}", taskIdStr, reason);
        }
    }
}
