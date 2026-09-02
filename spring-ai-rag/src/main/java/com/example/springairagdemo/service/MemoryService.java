package com.example.springairagdemo.service;

import com.example.springairagdemo.config.RagConfigProperties;
import com.example.springairagdemo.entity.UserLongTermMemoryEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户长期记忆领域服务（Phase 2）。
 *
 * <p>组合 MySQL（{@link UserLongTermMemoryService}）与 Milvus（{@link MemoryVectorService}）：
 * <ul>
 *   <li>save：语义去重（内容向量召回，与已有记忆相似度过高视为重复）→ MySQL 落库 → 向量 upsert；</li>
 *   <li>search：问题向量召回用户记忆（userId 过滤 + 余弦阈值）；</li>
 *   <li>buildInjectionContext：问答前按本次问题召回记忆，组装为【用户长期记忆】系统上下文；</li>
 *   <li>delete：逻辑删除 MySQL 行 + 同步删除向量。</li>
 * </ul>
 * 所有对外方法均保证异常不外抛（降级为"无记忆/未保存"并记录日志），不影响主问答链路。
 */
@Service
@Slf4j
public class MemoryService {

    /** 单条记忆内容上限（字符） */
    private static final int CONTENT_MAX_LENGTH = 500;
    /** 记忆数缓存 TTL（毫秒），用于问答注入前的快路径判断（无记忆则连 embedding 都省掉） */
    private static final long COUNT_CACHE_TTL_MS = 60_000L;
    /** 合法类别（归一化用小写），未知类别一律落为 fact */
    private static final Set<String> CATEGORIES = Set.of("fact", "preference", "interest", "goal", "event");
    private static final Map<String, String> CATEGORY_LABELS = Map.of(
            "fact", "事实",
            "preference", "偏好",
            "interest", "兴趣",
            "goal", "目标",
            "event", "经历");

    private final UserLongTermMemoryService userLongTermMemoryService;
    private final MemoryVectorService memoryVectorService;
    private final RagConfigProperties ragConfig;

    /** userId -> [记忆数, 缓存时间戳] */
    private final Map<Long, long[]> countCache = new ConcurrentHashMap<>();

    /** 同用户"语义判重+落库"串行化分段锁（256 段，按 userId 哈希取模，无生命周期管理）。
     *  消除后台自动抽取与手动沉淀/工具保存并发触发 {@link #save} 时，
     *  两线程同时判重未命中导致同内容重复落库 + 重复向量的竞态。 */
    private static final int SAVE_LOCK_SEGMENTS = 256;
    private static final Object[] SAVE_LOCKS = createSaveLocks();

    private static Object[] createSaveLocks() {
        Object[] locks = new Object[SAVE_LOCK_SEGMENTS];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    private static Object saveLock(Long userId) {
        long id = userId == null ? 0L : userId;
        int hash = (int) (id ^ (id >>> 32));
        return SAVE_LOCKS[hash & (SAVE_LOCKS.length - 1)];
    }

    public MemoryService(UserLongTermMemoryService userLongTermMemoryService,
                         MemoryVectorService memoryVectorService,
                         RagConfigProperties ragConfig) {
        this.userLongTermMemoryService = userLongTermMemoryService;
        this.memoryVectorService = memoryVectorService;
        this.ragConfig = ragConfig;
    }

    /** saveMemory 工具 / 后台抽取共用：保存一条用户长期记忆 */
    public record SaveResult(Long id, boolean duplicate, String message) {
    }

    /** searchMemory 工具 / 注入共用：一条召回的记忆 */
    public record MemoryHit(Long id, String content, String category, Integer importance, double score) {
    }

    private RagConfigProperties.LongTerm longTerm() {
        return ragConfig == null || ragConfig.getMemory() == null ? null : ragConfig.getMemory().getLongTerm();
    }

    /**
     * 保存一条用户长期记忆：语义去重 + MySQL 落库 + 向量 upsert。
     * embedding/向量不可用时降级为仅文本落库（vector_status=0），仍返回保存成功；
     * 任何失败都不会抛异常（由调用方提示用户即可）。
     */
    public SaveResult save(Long userId, String content, String category, Integer importance, String sourceSession) {
        RagConfigProperties.LongTerm lt = longTerm();
        if (lt == null || !lt.isEnabled()) {
            return new SaveResult(null, false, "用户长期记忆功能未启用");
        }
        if (userId == null) {
            return new SaveResult(null, false, "未获取到当前用户身份，无法保存长期记忆");
        }
        String text = content == null ? "" : content.trim();
        if (text.isBlank()) {
            return new SaveResult(null, false, "记忆内容不能为空");
        }
        if (text.length() > CONTENT_MAX_LENGTH) {
            text = text.substring(0, CONTENT_MAX_LENGTH);
        }
        long total = userLongTermMemoryService.countByUser(userId);
        if (total >= lt.getMaxPerUser()) {
            return new SaveResult(null, false, "长期记忆已满（上限 " + lt.getMaxPerUser() + " 条），请先清理部分旧记忆");
        }
        String normalizedCategory = normalizeCategory(category);
        int normalizedImportance = importance == null ? 5 : Math.max(1, Math.min(10, importance));

        UserLongTermMemoryEntity entity = new UserLongTermMemoryEntity();
        entity.setUserId(userId);
        entity.setContent(text);
        entity.setCategory(normalizedCategory);
        entity.setImportance(normalizedImportance);
        entity.setSourceSession(sourceSession);
        entity.setVectorStatus(0);

        // 语义去重 + 向量入库：内容向量只需算一次（先召回判重，未重复再写入）。
        // 同一用户并发保存（后台自动抽取 × 手动沉淀/工具保存）在用户级分段锁内串行化，
        // 避免"两线程同时判重未命中 → 同内容重复落库 + 重复向量"
        synchronized (saveLock(userId)) {
            try {
                float[] vector = memoryVectorService.embed(text);
                List<MemoryVectorService.MemoryVectorHit> duplicates =
                        memoryVectorService.searchByVector(userId, vector, 1, lt.getDedupeThreshold());
                if (!duplicates.isEmpty()) {
                    MemoryVectorService.MemoryVectorHit hit = duplicates.get(0);
                    if (hit.id() != null) {
                        log.debug("用户长期记忆去重命中，跳过新增: userId={}, memoryId={}", userId, hit.id());
                        return new SaveResult(hit.id(), true, "该记忆已存在（id=" + hit.id() + "），未重复保存");
                    }
                }
                userLongTermMemoryService.save(entity);
                try {
                    memoryVectorService.upsertWithVector(entity.getId(), userId, text, normalizedCategory,
                            normalizedImportance, vector);
                    markVectorSynced(entity.getId(), userId);
                } catch (Exception e) {
                    log.warn("长期记忆向量写入失败（文本已落库 vector_status=0，将由后台补偿任务自动同步向量）: {}", e.getMessage());
                }
                countCache.remove(userId);
                return new SaveResult(entity.getId(), false, "已记住：" + text);
            } catch (Exception e) {
                log.warn("长期记忆 embedding 暂不可用，仅文本落库（vector_status=0，后台补偿任务会自动重试向量化）: {}", e.getMessage());
                userLongTermMemoryService.save(entity);
                countCache.remove(userId);
                return new SaveResult(entity.getId(), false, "已记住：" + text);
            }
        }
    }

    /**
     * 按用户 + 查询文本召回记忆（userId 过滤 + 余弦阈值），失败/无结果返回空列表。
     */
    public List<MemoryHit> search(Long userId, String query, int topK, double minScore) {
        if (userId == null || query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        try {
            return memoryVectorService.search(userId, query, topK, minScore).stream()
                    .map(hit -> new MemoryHit(hit.id(), hit.content(), hit.category(), hit.importance(), hit.score()))
                    .toList();
        } catch (Exception e) {
            log.warn("用户长期记忆向量检索失败（按无记忆处理）: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 组装【用户长期记忆】注入上下文（问答前调用）。
     * 快路径：该用户没有记忆（带 60s 缓存）直接返回 null，避免每次问答都触发 embedding；
     * 无召回或组装失败同样返回 null（不注入）。
     */
    public String buildInjectionContext(Long userId, String query, int limit, double minScore, int maxChars) {
        if (userId == null || query == null || query.isBlank() || limit <= 0 || maxChars <= 0) {
            return null;
        }
        if (!hasMemories(userId)) {
            return null;
        }
        List<MemoryHit> hits = search(userId, query, limit * 2, minScore);
        if (hits.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("【用户长期记忆】\n");
        builder.append("（该用户长期记忆库中的个人事实/偏好/经历，来自历史对话沉淀，仅作个性化背景参考；")
                .append("不要编造，不要把它当作知识库文档来源，也不要在正文中标注 [来源N]。）\n");
        int count = 0;
        for (MemoryHit hit : hits) {
            if (hit.content() == null || hit.content().isBlank()) {
                continue;
            }
            if (count >= limit) {
                break;
            }
            String line = (count + 1) + ". [" + categoryLabel(hit.category())
                    + "·重要度" + (hit.importance() == null ? 5 : hit.importance()) + "] " + hit.content() + "\n";
            if (builder.length() + line.length() > maxChars) {
                break;
            }
            builder.append(line);
            count++;
        }
        return count == 0 ? null : builder.toString().trim();
    }

    /** 某用户全部记忆（管理/展示用），category 非空时过滤；limit ≤ 0 不限制 */
    public List<UserLongTermMemoryEntity> list(Long userId, String category, int limit) {
        if (userId == null) {
            return List.of();
        }
        return userLongTermMemoryService.listByUser(userId, category, limit);
    }

    /** 某用户记忆总数（管理/开关判断用，不走缓存） */
    public long count(Long userId) {
        return userId == null ? 0 : userLongTermMemoryService.countByUser(userId);
    }

    /** 逻辑删除（仅本人）+ 同步删向量，返回是否成功 */
    public boolean delete(Long userId, Long memoryId) {
        if (userId == null || memoryId == null) {
            return false;
        }
        boolean deleted = userLongTermMemoryService.deleteOwn(userId, memoryId);
        if (deleted) {
            countCache.remove(userId);
            try {
                memoryVectorService.deleteByIds(List.of(memoryId));
            } catch (Exception e) {
                log.warn("长期记忆向量删除失败（文本已删除，Milvus 残留可忽略）: {}", e.getMessage());
            }
        }
        return deleted;
    }

    /**
     * 修改本人某条记忆的重要度（1-10，其余字段不变）。
     * MySQL 与 Milvus 两处都更新：Milvus 行按 PK=id 整行重写（需重算一次内容 embedding）。
     * 向量重写失败时仍返回成功（文本已更新，召回展示中的重要度可能滞后，仅告警）。
     */
    public boolean updateImportance(Long userId, Long memoryId, Integer importance) {
        if (userId == null || memoryId == null || importance == null) {
            return false;
        }
        int normalized = Math.max(1, Math.min(10, importance));
        try {
            UserLongTermMemoryEntity entity = userLongTermMemoryService.lambdaQuery()
                    .eq(UserLongTermMemoryEntity::getId, memoryId)
                    .eq(UserLongTermMemoryEntity::getUserId, userId)
                    .one();
            if (entity == null) {
                return false;
            }
            if (entity.getImportance() != null && entity.getImportance() == normalized) {
                return true;
            }
            boolean updated = userLongTermMemoryService.lambdaUpdate()
                    .eq(UserLongTermMemoryEntity::getId, memoryId)
                    .eq(UserLongTermMemoryEntity::getUserId, userId)
                    .set(UserLongTermMemoryEntity::getImportance, normalized)
                    .update();
            if (!updated) {
                return false;
            }
            // Milvus 行整行重写（内容不变 → 重算 embedding 与原值等价），保持两处元数据一致
            if (entity.getContent() != null && !entity.getContent().isBlank()) {
                try {
                    float[] vector = memoryVectorService.embed(entity.getContent());
                    memoryVectorService.upsertWithVector(memoryId, userId, entity.getContent(),
                            entity.getCategory(), normalized, vector);
                    markVectorSynced(memoryId, userId);
                } catch (Exception e) {
                    log.warn("长期记忆重要度已更新，向量行重写失败（召回展示的重要度可能滞后）: {}", e.getMessage());
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("更新长期记忆重要度失败: memoryId={}, userId={}, err={}", memoryId, userId, e.getMessage());
            return false;
        }
    }

    /**
     * 一键清除本人全部长期记忆：MySQL 逻辑删除全部 + Milvus 删除该用户全部向量
     * （无条件清理向量，兼顾历史残留），返回被清除条数。
     */
    public int clearAll(Long userId) {
        if (userId == null) {
            return 0;
        }
        int deleted = userLongTermMemoryService.deleteAllByUser(userId);
        countCache.remove(userId);
        try {
            memoryVectorService.deleteByUser(userId);
        } catch (Exception e) {
            log.warn("清除长期记忆向量失败（文本已删除，Milvus 残留可忽略）: {}", e.getMessage());
        }
        return deleted;
    }

    /** 类别显示名（记忆/工具输出用） */
    public static String categoryLabel(String category) {
        return CATEGORY_LABELS.getOrDefault(normalizeCategory(category), "事实");
    }

    private static String normalizeCategory(String category) {
        if (category == null) {
            return "fact";
        }
        String lower = category.trim().toLowerCase();
        return CATEGORIES.contains(lower) ? lower : "fact";
    }

    /** 快路径：该用户当前是否有记忆（60s TTL 缓存） */
    private boolean hasMemories(Long userId) {
        long now = System.currentTimeMillis();
        long[] cached = countCache.get(userId);
        if (cached != null && now - cached[1] < COUNT_CACHE_TTL_MS) {
            return cached[0] > 0;
        }
        long count = userLongTermMemoryService.countByUser(userId);
        countCache.put(userId, new long[]{count, now});
        return count > 0;
    }

    private void markVectorSynced(Long memoryId, Long userId) {
        try {
            userLongTermMemoryService.lambdaUpdate()
                    .eq(UserLongTermMemoryEntity::getId, memoryId)
                    .eq(UserLongTermMemoryEntity::getUserId, userId)
                    .set(UserLongTermMemoryEntity::getVectorStatus, 1)
                    .update();
        } catch (Exception e) {
            log.warn("更新记忆向量状态失败（可忽略）: {}", e.getMessage());
        }
    }
}
