package com.example.springairagdemo.mq;

import com.example.springairagdemo.config.RabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Embedding 任务消息生产者。
 * <p>
 * 发送任务 ID（String 消息体，默认 SimpleMessageConverter 以持久化模式投递）到业务队列，
 * 并携带 {@link CorrelationData}（id = taskId，供 Publisher Confirm 回调定位）与
 * {@code x-task-id} 消息头（供 Publisher Return 回调定位）。
 * <p>
 * 可靠性三层：
 * <ol>
 *   <li>同步异常（连接不可用）：由调用方 {@code submitIngest} 补偿删除已插入的
 *       document/task 与文件避免孤儿数据；{@code resumeInterruptedTask} 则将异常上抛，
 *       由恢复兜底逻辑标记任务失败</li>
 *   <li>Publisher Confirm nack（broker 拒收/网络异常）：RabbitTemplate 回调标记任务失败</li>
 *   <li>Publisher Return（路由不到队列）：RabbitTemplate 回调标记任务失败</li>
 * </ol>
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
        CorrelationData correlationData = new CorrelationData(taskId.toString());
        MessagePostProcessor postProcessor = message -> {
            message.getMessageProperties().setHeader(RabbitConfig.HEADER_TASK_ID, taskId.toString());
            return message;
        };
        rabbitTemplate.convertAndSend(
                RabbitConfig.EMBEDDING_EXCHANGE,
                RabbitConfig.EMBEDDING_TASK_ROUTING_KEY,
                taskId.toString(),
                postProcessor,
                correlationData);
        log.info("Embedding 任务消息已发送: taskId={}", taskId);
    }
}
