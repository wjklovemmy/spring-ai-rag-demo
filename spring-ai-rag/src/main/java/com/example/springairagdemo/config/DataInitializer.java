package com.example.springairagdemo.config;

import com.example.springairagdemo.entity.KnowledgeEmbeddingTaskEntity;
import com.example.springairagdemo.entity.KnowledgeEmbeddingTaskStatus;
import com.example.springairagdemo.service.KnowledgeDocumentService;
import com.example.springairagdemo.service.KnowledgeEmbeddingTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * RAG 业务域启动初始化：
 * 恢复中断的 Embedding 任务：将上次运行时"待处理/处理中"的任务重新入队执行。
 * （内置 ADMIN 角色与引导管理员账号的初始化已随用户域迁移至
 * spring-ai-user 模块的 {@code UserDataInitializer}，由宿主应用统一扫描执行。）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final KnowledgeEmbeddingTaskService knowledgeEmbeddingTaskService;
    private final KnowledgeDocumentService knowledgeDocumentService;

    @Override
    public void run(ApplicationArguments args) {
        recoverInterruptedTasks();
    }

    /**
     * 服务重启后，恢复上次中断的任务：
     * 同时覆盖"待处理"（status=0）与"处理中"（status=1）——
     * 若 JVM 在任务保存为 PENDING 后、异步线程实际开始前崩溃，仅恢复 PROCESSING 会漏掉它，
     * 导致任务永远卡在"待处理"。恢复以增量方式重新入队执行：
     * 已完整处理（MySQL + 向量均完成）的 chunk 直接跳过，只补齐缺失或内容变化的片段，
     * 避免重复解析/切分/embedding/写入；恢复失败（如解析异常）则标记为失败，
     * 避免任务永远卡在中间状态。
     */
    private void recoverInterruptedTasks() {
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
                markTaskFailed(task.getId(), "重启恢复异常: " + e.getMessage());
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
