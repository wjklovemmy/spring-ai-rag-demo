package com.example.springairagdemo.mq;

import com.example.springairagdemo.config.RabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Embedding 任务消息生产者。
 * <p>
 * 发送任务 ID（String 消息体，默认 SimpleMessageConverter 以持久化模式投递）到业务队列。
 * 发送失败（如 RabbitMQ 不可用）抛异常：由调用方 {@code submitIngest} 补偿删除已插入的
 * document/task 与文件避免孤儿数据；{@code resumeInterruptedTask} 则将异常上抛，
 * 由恢复兜底逻辑标记任务失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingTaskProducer {

    private final RabbitTemplate rabbitTemplate;

    /**
     * 发送 Embedding 任务消息
     *
     * @param taskId 任务 ID（消息体为 taskId 字符串，队列持久化 + 消息持久化投递）
     */
    public void sendTask(Long taskId) {
        rabbitTemplate.convertAndSend(
                RabbitConfig.EMBEDDING_EXCHANGE,
                RabbitConfig.EMBEDDING_TASK_ROUTING_KEY,
                taskId.toString());
        log.info("Embedding 任务消息已发送: taskId={}", taskId);
    }
}
