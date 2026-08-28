package com.example.springairagdemo.service;

import com.example.springairagdemo.entity.KnowledgeEmbeddingTaskEntity;
import com.example.springairagdemo.entity.KnowledgeEmbeddingTaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 中断 Embedding 任务恢复服务。
 * <p>
 * 负责扫描"待处理/处理中"的 Embedding 任务并重新入队执行，供多个入口复用：
 * <ul>
 *   <li>启动兜底：{@code DataInitializer}（ApplicationRunner）在服务冷启动时立即执行一次；</li>
 *   <li>定时巡检（可扩展）：接入 xxl-job / @Scheduled 定时扫描，兜住"运行中崩溃（Pod 被 kill
 *       无重启机会）导致任务永久卡在处理中"的场景。</li>
 * </ul>
 * 恢复以增量方式重新入队（{@link KnowledgeDocumentService#resumeInterruptedTask}）：
 * 已完整处理（MySQL + 向量均完成）的 chunk 直接跳过，只补齐缺失或内容变化的片段，
 * 避免重复解析/切分/embedding/写入。多实例/多入口并发执行是安全的——
 * {@code resumeInterruptedTask} 内部将任务置回 PENDING 后发送 MQ 消息，
 * 消费者执行 {@code processTask} 通过 CAS（PENDING -&gt; PROCESSING 条件更新）原子抢占，
 * 只有一例真正处理，其余直接放弃。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskRecoveryService {

    private final KnowledgeEmbeddingTaskService knowledgeEmbeddingTaskService;
    private final KnowledgeDocumentService knowledgeDocumentService;

    /**
     * 恢复中断的任务：
     * 同时覆盖"待处理"（status=0）与"处理中"（status=1）——
     * 若 JVM 在任务保存为 PENDING 后、异步线程实际开始前崩溃，仅恢复 PROCESSING 会漏掉它，
     * 导致任务永远卡在"待处理"。恢复以增量方式重新入队执行：
     * 已完整处理（MySQL + 向量均完成）的 chunk 直接跳过，只补齐缺失或内容变化的片段，
     * 避免重复解析/切分/embedding/写入；恢复失败（如解析异常）则标记为失败，
     * 避免任务永远卡在中间状态。
     */
    public void recoverInterruptedTasks() {
        List<KnowledgeEmbeddingTaskEntity> interrupted = knowledgeEmbeddingTaskService.lambdaQuery()
                .in(KnowledgeEmbeddingTaskEntity::getStatus,
                        KnowledgeEmbeddingTaskStatus.PENDING, KnowledgeEmbeddingTaskStatus.PROCESSING)
                .list();
        if (interrupted.isEmpty()) {
            return;
        }
        log.info("发现 {} 个中断的 Embedding 任务，开始恢复执行", interrupted.size());
        int resumed = 0;
        for (KnowledgeEmbeddingTaskEntity task : interrupted) {
            try {
                knowledgeDocumentService.resumeInterruptedTask(task.getId());
                resumed++;
            } catch (Exception e) {
                log.error("恢复中断任务异常: taskNo={}", task.getTaskNo(), e);
                markTaskFailed(task.getId(), "恢复异常: " + e.getMessage());
            }
        }
        log.info("中断任务恢复完成: {}/{}", resumed, interrupted.size());
    }

    /**
     * 将任务标记为失败（恢复失败时的兜底，避免下次启动再次扫描）
     */
    private void markTaskFailed(Long taskId, String error) {
        try {
            knowledgeEmbeddingTaskService.lambdaUpdate()
                    .eq(KnowledgeEmbeddingTaskEntity::getId, taskId)
                    .set(KnowledgeEmbeddingTaskEntity::getStatus, KnowledgeEmbeddingTaskStatus.FAILED)
                    .set(KnowledgeEmbeddingTaskEntity::getErrorMessage, error)
                    .set(KnowledgeEmbeddingTaskEntity::getFinishTime, new Date())
                    .set(KnowledgeEmbeddingTaskEntity::getUpdateTime, new Date())
                    .update();
        } catch (Exception ex) {
            log.error("标记任务失败异常: taskId={}", taskId, ex);
        }
    }
}
