package com.example.springairagdemo.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 基础设施：Embedding 任务队列 + 死信队列。
 * <p>
 * 队列与消息均持久化（durable）：
 * <ul>
 *   <li>业务队列 {@code rag.embedding.task.queue}：收到任务消息即执行 PDF 解析/切分/向量化；
 *       声明死信交换机与死信路由键——消息重试 3 次仍失败（reject）时自动路由到死信队列</li>
 *   <li>死信队列 {@code rag.embedding.task.dlq}：由死信消费者统一标记任务失败</li>
 * </ul>
 * 消息投递语义：RabbitMQ 默认 at-least-once（消费者 ack 前崩溃会重新投递），
 * 重复消费防护在消费端完成（任务状态预检 + claimTask CAS + 增量处理），
 * 见 {@code EmbeddingTaskConsumer} 与 {@code KnowledgeDocumentService}。
 */
@Configuration
public class RabbitConfig {

    public static final String EMBEDDING_EXCHANGE = "rag.embedding.exchange";
    public static final String EMBEDDING_TASK_QUEUE = "rag.embedding.task.queue";
    public static final String EMBEDDING_TASK_DLQ = "rag.embedding.task.dlq";
    public static final String EMBEDDING_TASK_ROUTING_KEY = "rag.embedding.task";
    public static final String EMBEDDING_TASK_DLQ_ROUTING_KEY = "rag.embedding.task.dlq";

    @Bean
    public DirectExchange embeddingExchange() {
        return new DirectExchange(EMBEDDING_EXCHANGE, true, false);
    }

    /** 业务队列：持久化，绑定死信交换机/路由键，重试耗尽的消息自动进入死信队列 */
    @Bean
    public Queue embeddingTaskQueue() {
        return QueueBuilder.durable(EMBEDDING_TASK_QUEUE)
                .deadLetterExchange(EMBEDDING_EXCHANGE)
                .deadLetterRoutingKey(EMBEDDING_TASK_DLQ_ROUTING_KEY)
                .build();
    }

    /** 死信队列：持久化 */
    @Bean
    public Queue embeddingTaskDlq() {
        return QueueBuilder.durable(EMBEDDING_TASK_DLQ).build();
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
}
