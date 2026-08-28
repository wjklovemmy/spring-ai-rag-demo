package com.example.springairagdemo.mq;

import com.example.springairagdemo.config.RabbitConfig;
import com.example.springairagdemo.service.KnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 死信消费者：业务队列消息重试 3 次仍失败后进入本队列，统一将任务标记为失败。
 * <p>
 * 幂等：任务已成功 / 已失败 / 不存在时直接 ack 跳过；标记失败自身吞掉异常，
 * 避免死信消息因处理失败再次重试或直接丢失（死信队列已无下一级兜底）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingTaskDlqConsumer {

    private final KnowledgeDocumentService knowledgeDocumentService;

    @RabbitListener(queues = RabbitConfig.EMBEDDING_TASK_DLQ)
    public void consumeDlq(String taskId) {
        Long id;
        try {
            id = Long.valueOf(taskId.trim());
        } catch (NumberFormatException e) {
            log.error("死信消息格式非法，丢弃: {}", taskId);
            return;
        }
        try {
            knowledgeDocumentService.markTaskAndDocumentFailed(id,
                    "消息重试 3 次仍失败，已进入死信队列");
        } catch (Exception e) {
            // 标记失败失败不抛：死信消息已无下一级队列，避免无限重试/丢失
            log.error("死信处理异常（任务标记失败）: taskId={}", id, e);
        }
    }
}
