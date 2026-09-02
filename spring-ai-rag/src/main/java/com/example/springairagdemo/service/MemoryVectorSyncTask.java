package com.example.springairagdemo.service;

import com.example.springairagdemo.config.RagConfigProperties;
import com.example.springairagdemo.entity.UserLongTermMemoryEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 用户长期记忆向量后台补偿任务（Phase 2）。
 *
 * <p>新增/抽取记忆时若 DashScope embedding 临时不可用，记忆会降级为「文本落库、向量待同步」
 * （vector_status=0，见 {@link MemoryService#save}）。本任务定期扫描此类记录，重试向量化并写入
 * Milvus，向量服务恢复后无需人工干预即可补齐召回（管理页"待同步"随之变为"已同步"）。
 *
 * <p>设计约束：
 * <ul>
 *   <li>单条失败即终止本轮并置 false 前 break——embedding 失败多为服务级（不可用/熔断），
 *       继续处理只会加剧熔断与配额消耗；下轮按 fixedDelay 自动再试；</li>
 *   <li>每轮条数上限 {@code vector-sync-batch-size}（默认 20），避免恢复瞬间突发批量请求；</li>
 *   <li>进程内 running 标志防同实例重入（与 RedisMemoryMonitor 等定时任务一致，无分布式锁）；</li>
 *   <li>补偿只补齐向量，不改动文本/类别/重要度等业务字段，也不推进记忆的 updateTime 排序语义。</li>
 * </ul>
 * 配置：{@code rag.memory.long-term.vector-sync-enabled}（开关，默认开）/
 * {@code vector-sync-interval-ms}（间隔，默认 60000）/{@code vector-sync-batch-size}（每轮条数，默认 20）。
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "rag.memory.long-term", name = "vector-sync-enabled",
        havingValue = "true", matchIfMissing = true)
public class MemoryVectorSyncTask {

    private final RagConfigProperties ragConfig;
    private final UserLongTermMemoryService userLongTermMemoryService;
    private final MemoryVectorService memoryVectorService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public MemoryVectorSyncTask(RagConfigProperties ragConfig,
                                UserLongTermMemoryService userLongTermMemoryService,
                                MemoryVectorService memoryVectorService) {
        this.ragConfig = ragConfig;
        this.userLongTermMemoryService = userLongTermMemoryService;
        this.memoryVectorService = memoryVectorService;
    }

    @Scheduled(fixedDelayString = "${rag.memory.long-term.vector-sync-interval-ms:60000}")
    public void compensatePendingVectors() {
        if (running.compareAndSet(false, true)) {
            try {
                scanPending();
            } finally {
                running.set(false);
            }
        }
    }

    private void scanPending() {
        RagConfigProperties.LongTerm lt = ragConfig.getMemory() == null ? null : ragConfig.getMemory().getLongTerm();
        if (lt == null || !lt.isEnabled() || !lt.isVectorSyncEnabled()) {
            return;
        }
        int batchSize = Math.max(1, lt.getVectorSyncBatchSize());
        List<UserLongTermMemoryEntity> pending = userLongTermMemoryService.listPendingVectorSync(batchSize);
        if (pending.isEmpty()) {
            return;
        }
        int synced = 0;
        for (UserLongTermMemoryEntity entity : pending) {
            String content = entity.getContent();
            if (content == null || content.isBlank()) {
                // 内容异常（正常流程不会落库）：置为已同步避免无限重扫，管理页仍可见可删
                userLongTermMemoryService.lambdaUpdate()
                        .eq(UserLongTermMemoryEntity::getId, entity.getId())
                        .set(UserLongTermMemoryEntity::getVectorStatus, 1)
                        .update();
                log.error("长期记忆内容为空无法向量化，已跳过并停止重试: id={}", entity.getId());
                continue;
            }
            try {
                float[] vector = memoryVectorService.embed(content);
                int importance = entity.getImportance() == null ? 5 : entity.getImportance();
                memoryVectorService.upsertWithVector(entity.getId(), entity.getUserId(),
                        content, entity.getCategory(), importance, vector);
                userLongTermMemoryService.lambdaUpdate()
                        .eq(UserLongTermMemoryEntity::getId, entity.getId())
                        .set(UserLongTermMemoryEntity::getVectorStatus, 1)
                        .update();
                synced++;
            } catch (Exception e) {
                // 服务级失败（embedding 不可用/熔断）：终止本轮，下轮按 fixedDelay 自动再试
                log.warn("长期记忆向量后台补偿失败，本轮终止（下轮自动重试）: id={}, userId={}, err={}",
                        entity.getId(), entity.getUserId(), e.getMessage());
                break;
            }
        }
        log.info("长期记忆向量后台补偿：本轮扫描 {} 条，成功同步 {} 条", pending.size(), synced);
    }
}
