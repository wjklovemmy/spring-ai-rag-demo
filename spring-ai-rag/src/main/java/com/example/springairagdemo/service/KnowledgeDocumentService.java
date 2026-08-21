package com.example.springairagdemo.service;

import com.example.springairagdemo.config.AiConfig;
import com.example.springairagdemo.config.RagConfigProperties;
import com.example.springairagdemo.entity.DocumentStatus;
import com.example.springairagdemo.entity.KnowledgeChunkEntity;
import com.example.springairagdemo.entity.KnowledgeDocumentEntity;
import com.example.springairagdemo.entity.KnowledgeEmbeddingTaskEntity;
import com.example.springairagdemo.entity.KnowledgeEmbeddingTaskStatus;
import com.example.springairagdemo.security.KbRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DuplicateKeyException;

import java.util.concurrent.CompletableFuture;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 知识文档服务抽象类：定义文档摄取的模板流程和基于知识库的问答能力
 * <p>
 * 上传流程（submitIngest → 异步 processTaskAsync）：
 * 1. 保存原始文档信息到 MySQL knowledge_document
 * 2. 原始文件最先持久化到存储后端（MinIO/本地）
 * 3. 创建 knowledge_embedding_task 任务（0待处理），立即返回任务编号
 * 4. 异步线程：解析文档（子类实现）
 * 5. 异步线程：切分文档（子类实现）
 * 6. 异步线程：chunk 文本写入 MySQL knowledge_chunk（增量：跳过已处理片段）
 * 7. 异步线程：chunk 向量写入 Milvus（仅存向量 + 引用字段，稳定主键 upsert 幂等）
 * 8. 任务成功/失败状态回写 knowledge_embedding_task
 * <p>
 * 恢复/重跑（resumeInterruptedTask → processTaskAsync）支持增量执行：
 * 已完整处理（MySQL + 向量均完成）的 chunk 直接跳过，只补齐缺失或内容变化的片段；
 * Milvus 幂等 upsert 兜底并发/重复执行。
 */
@Slf4j
public abstract class KnowledgeDocumentService {

    /** 同名文档并发上传版本号冲突时的最大重试次数 */
    private static final int MAX_VERSION_RETRY = 5;

    /** AI 服务（DeepSeek）不可用时的降级提示 */
    private static final String AI_SERVICE_UNAVAILABLE = "AI服务暂时不可用，请稍后再试";

    @Autowired
    protected KnowledgeBaseService knowledgeBaseService;

    @Autowired
    protected KnowledgeDocumentEntityService knowledgeDocumentEntityService;

    @Autowired
    protected KnowledgeChunkEntityService knowledgeChunkEntityService;

    @Autowired
    protected VectorStoreService vectorStoreService;

    @Autowired
    protected ChatClient chatClient;

    /** Sentinel 熔断降级器工厂（spring-cloud-circuitbreaker-sentinel 实现） */
    @Autowired
    protected CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    @Autowired
    protected RagConfigProperties ragConfig;

    @Autowired
    protected FileStorageService fileStorageService;

    @Autowired
    protected TransactionTemplate transactionTemplate;

    @Autowired
    protected RerankService rerankService;

    @Autowired
    protected HybridSearchService hybridSearchService;

    @Autowired
    protected KbAuthorizationService kbAuthorizationService;

    @Autowired
    protected KnowledgeEmbeddingTaskService knowledgeEmbeddingTaskService;

    @Autowired
    @Qualifier("taskExecutor")
    protected TaskExecutor taskExecutor;

    // ===================== 模板方法：上传文档 =====================

    /**
     * Embedding 任务提交结果
     *
     * @param taskId     任务 ID
     * @param taskNo     任务编号（前端轮询状态用）
     * @param documentId 文档 ID
     * @param fileName   文件名
     * @param version    文档版本
     */
    public record TaskSubmitResult(Long taskId, String taskNo, Long documentId, String fileName, int version) {}

    /**
     * 提交文档上传：保存文档信息 + 持久化原始文件 + 创建 Embedding 任务（0待处理），
     * 随后异步执行 PDF 解析/切分/向量化，接口立即返回任务编号，前端轮询任务状态。
     * <p>
     * 原始文件最先持久化到存储后端（MinIO/本地），即使后续处理失败原始文件也已落盘；
     * 提交阶段任一步失败则补偿删除文件与已插入的 document/task 记录，避免孤儿数据。
     */
    public TaskSubmitResult submitIngest(MultipartFile file, Long knowledgeBaseId) throws IOException {
        // 服务层权限守卫（纵深防御）：上传文档需要 EDITOR 及以上
        kbAuthorizationService.assertRole(knowledgeBaseId, KbRole.EDITOR);

        // 提前将文件读入内存，防止 Tomcat 后续清理临时文件导致 InputStream 失效
        byte[] fileBytes = file.getBytes();
        KnowledgeDocumentEntity docEntity = null;
        KnowledgeEmbeddingTaskEntity task = null;
        try {
            // 1. 保存新版本文档信息到 MySQL（含版本号推断）
            docEntity = saveDocumentInfo(file, knowledgeBaseId);

            // 2. 最先持久化原始文件到存储后端（MinIO/本地）
            persistUploadedFile(fileBytes, docEntity);

            // 3. 创建 Embedding 任务（status=0 待处理）
            task = new KnowledgeEmbeddingTaskEntity();
            task.setTaskNo(generateTaskNo());
            task.setDocumentId(docEntity.getId());
            task.setStatus(KnowledgeEmbeddingTaskStatus.PENDING);
            task.setRetryCount(0);
            task.setCreateTime(new Date());
            task.setUpdateTime(new Date());
            knowledgeEmbeddingTaskService.save(task);
            log.info("Embedding 任务已提交: taskNo={}, documentId={}, fileName={}",
                    task.getTaskNo(), docEntity.getId(), docEntity.getFileName());

            // 4. 异步执行 PDF 解析/切分/向量化，立即返回任务编号
            final Long taskId = task.getId();
            CompletableFuture.runAsync(() -> processTaskAsync(taskId), taskExecutor);

            return new TaskSubmitResult(task.getId(), task.getTaskNo(), docEntity.getId(),
                    docEntity.getFileName(), docEntity.getVersion());
        } catch (Exception e) {
            log.error("提交 Embedding 任务失败: {}", docEntity != null ? docEntity.getFileName() : "unknown", e);
            // 提交阶段补偿：删除已上传文件 + 已插入的 document/task，避免孤儿数据
            if (docEntity != null && docEntity.getFilePath() != null) {
                try {
                    fileStorageService.delete(docEntity.getFilePath());
                    log.info("已补偿删除存储端文件: {}", docEntity.getFilePath());
                } catch (Exception ex) {
                    log.error("补偿删除存储端文件失败: {}", docEntity.getFilePath(), ex);
                }
            }
            if (docEntity != null && docEntity.getId() != null) {
                try {
                    knowledgeDocumentEntityService.removeById(docEntity.getId());
                } catch (Exception ex) {
                    log.error("补偿删除文档记录失败: id={}", docEntity.getId(), ex);
                }
            }
            if (task != null && task.getId() != null) {
                try {
                    knowledgeEmbeddingTaskService.removeById(task.getId());
                } catch (Exception ex) {
                    log.error("补偿删除任务记录失败: id={}", task.getId(), ex);
                }
            }
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new RuntimeException("提交 Embedding 任务失败: " + e.getMessage(), e);
        }
    }

    /**
     * 服务重启后恢复中断的 Embedding 任务（增量执行）：
     * 1. 重置任务计数与错误信息，累加重试次数
     * 2. 重新入队 {@link #processTaskAsync}，由增量逻辑查缺补漏：
     *    已完整处理（MySQL + 向量均完成）的 chunk 直接跳过，只补齐缺失或内容变化的片段
     * <p>
     * 不再删除半成品数据 —— 已写入的 chunk/向量作为增量线索保留（milvus_id 标记"向量已写入"），
     * 恢复时跳过，避免重复解析/切分/embedding/写入。
     */
    public void resumeInterruptedTask(Long taskId) {
        KnowledgeEmbeddingTaskEntity task = knowledgeEmbeddingTaskService.getById(taskId);
        if (task == null) {
            log.warn("恢复中断任务失败：任务不存在 taskId={}", taskId);
            return;
        }
        long start = System.currentTimeMillis();
        KnowledgeDocumentEntity docEntity = knowledgeDocumentEntityService.getById(task.getDocumentId());
        if (docEntity == null) {
            log.error("恢复中断任务失败：文档不存在 taskNo={}", task.getTaskNo());
            failTask(task, "重启恢复失败：文档不存在", start);
            return;
        }

        // 1. 重置任务字段并置回 PENDING（processTaskAsync 通过 CAS 抢占 PENDING 才能执行，
        //    避免与其它入口并发重复处理同一任务）
        task.setStatus(KnowledgeEmbeddingTaskStatus.PENDING);
        task.setTotalChunk(0);
        task.setSuccessChunk(0);
        task.setFailChunk(0);
        task.setRetryCount(task.getRetryCount() == null ? 1 : task.getRetryCount() + 1);
        task.setErrorMessage(null);
        task.setFinishTime(null);
        task.setCostTime(null);
        task.setUpdateTime(new Date());
        knowledgeEmbeddingTaskService.updateById(task);

        // 2. 重新入队执行（增量补齐：已处理过的 chunk 直接跳过）
        final Long finalTaskId = task.getId();
        CompletableFuture.runAsync(() -> processTaskAsync(finalTaskId), taskExecutor);
        log.info("重启恢复：任务 {} 已重新入队执行（增量补齐） documentId={}",
                task.getTaskNo(), docEntity.getId());
    }

    /**
     * 异步执行 Embedding 任务：解析 PDF → 切分 → chunk 写 MySQL → 向量写 Milvus。
     * 支持增量执行（恢复/重跑）：已完整处理（MySQL + 向量均完成）的 chunk 直接跳过，
     * 只补齐缺失或内容变化的片段。
     * 任务状态逐阶段推进（PENDING → PROCESSING → SUCCESS/FAILED），
     * 文档状态同步推进（0上传中 → 1解析中 → 2向量化中 → 3成功/4失败），
     * 处理进度在文档列表与任务表均可感知。
     * <p>
     * 该线程不持有 HTTP 请求上下文（UserContext 已清理），处理流程不依赖登录用户。
     */
    public void processTaskAsync(Long taskId) {
        KnowledgeEmbeddingTaskEntity task = knowledgeEmbeddingTaskService.getById(taskId);
        if (task == null) {
            log.error("Embedding 任务不存在: taskId={}", taskId);
            return;
        }
        long start = System.currentTimeMillis();
        KnowledgeDocumentEntity docEntity = knowledgeDocumentEntityService.getById(task.getDocumentId());
        if (docEntity == null) {
            log.error("任务对应文档不存在: documentId={}", task.getDocumentId());
            failTask(task, "文档不存在", start);
            return;
        }
        try {
            // 1. CAS 抢占任务（PENDING -> PROCESSING），防止同一任务被并发/重复入队时重复处理
            if (!claimTask(task, start)) {
                return;
            }

            // 2. 读取原始文件解析 PDF 并切分为 chunks
            List<Document> chunks = parseAndSplit(task, docEntity);

            // 3. 增量查缺：按 (chunk_index, content_hash, milvus_id) 分类已有 chunk
            ChunkDiff diff = diffChunks(docEntity, chunks);

            // 4. 删除作废/多余的旧 chunk（MySQL 行 + Milvus 向量）
            cleanStaleChunks(docEntity, chunks, diff.stale());

            // 5. 新增/变化 chunk 批量写入 MySQL
            persistChunksToMysql(task, docEntity, diff.toSave());

            // 6. Embedding + Milvus upsert（分批推进，实时更新进度），返回实际写入向量数
            int vectorCount = embedAndUpsertVectors(task, docEntity, chunks, diff);

            // 7. 收尾：文档置成功、废弃旧版本、任务置成功
            finishTask(task, docEntity, chunks, diff, vectorCount, start);
        } catch (Exception e) {
            log.error("Embedding 任务执行失败: taskNo={}, documentId={}",
                    task.getTaskNo(), task.getDocumentId(), e);
            // 回滚未完成向量（已回填 milvus_id 的保留，供恢复时增量跳过），标记文档与任务失败
            rollbackIncompleteVectors(docEntity);
            markDocumentFailed(docEntity);
            failTask(task, friendlyErrorMessage(e), start);
        }
    }

    /**
     * CAS 抢占任务（PENDING -> PROCESSING）：
     * 只有从 PENDING 原子转成功的线程获得处理权，其他线程直接放弃。
     *
     * @return true 表示抢占成功，可继续处理
     */
    private boolean claimTask(KnowledgeEmbeddingTaskEntity task, long start) {
        boolean claimed = knowledgeEmbeddingTaskService.lambdaUpdate()
                .eq(KnowledgeEmbeddingTaskEntity::getId, task.getId())
                .eq(KnowledgeEmbeddingTaskEntity::getStatus, KnowledgeEmbeddingTaskStatus.PENDING)
                .set(KnowledgeEmbeddingTaskEntity::getStatus, KnowledgeEmbeddingTaskStatus.PROCESSING)
                .set(KnowledgeEmbeddingTaskEntity::getStartTime, new Date(start))
                .set(KnowledgeEmbeddingTaskEntity::getUpdateTime, new Date())
                .update();
        if (!claimed) {
            log.warn("任务 {} 已被其他线程处理或状态不允许（并发/重复入队），跳过本次处理",
                    task.getTaskNo());
            return false;
        }
        task.setStatus(KnowledgeEmbeddingTaskStatus.PROCESSING);
        task.setStartTime(new Date(start));
        task.setUpdateTime(new Date());
        return true;
    }

    /**
     * 从存储读取原始文件并解析（原始文件在提交阶段已最先持久化），文档状态推进为解析中；
     * 再切分为 chunks（纯计算、确定性：同输入重跑时 chunkIndex 从 0 稳定编号），
     * 并记录 chunk 总数（同时标记切片阶段完成）。
     */
    private List<Document> parseAndSplit(KnowledgeEmbeddingTaskEntity task, KnowledgeDocumentEntity docEntity)
            throws Exception {
        updateDocumentStatus(docEntity, DocumentStatus.PARSING);
        List<Document> documents;
        try (InputStream inputStream = fileStorageService.getInputStream(docEntity.getFilePath())) {
            documents = parseDocument(inputStream);
        }
        log.info("任务 {} 解析完成，共 {} 个文档页面", task.getTaskNo(), documents.size());
        task.setParseProgress(100);
        task.setUpdateTime(new Date());
        knowledgeEmbeddingTaskService.updateById(task);

        List<Document> chunks = splitDocument(documents);
        log.info("任务 {} 切分完成，共 {} 个文本片段", task.getTaskNo(), chunks.size());

        task.setTotalChunk(chunks.size());
        task.setSuccessChunk(0);
        task.setFailChunk(0);
        task.setSplitProgress(100);
        task.setUpdateTime(new Date());
        knowledgeEmbeddingTaskService.updateById(task);
        return chunks;
    }

    /**
     * 增量查缺：按 (chunk_index, content_hash, milvus_id) 将已有 chunk 分类为
     * 新增/变化（toSave）、仅缺向量（toVectorOnly）、作废/多余（stale）、完整跳过（skipCount）。
     */
    private ChunkDiff diffChunks(KnowledgeDocumentEntity docEntity, List<Document> chunks) {
        List<KnowledgeChunkEntity> existing = knowledgeChunkEntityService.lambdaQuery()
                .eq(KnowledgeChunkEntity::getDocumentId, docEntity.getId())
                .list();
        Map<Integer, KnowledgeChunkEntity> existingByIndex = existing.stream()
                .collect(Collectors.toMap(KnowledgeChunkEntity::getChunkIndex, e -> e, (a, b) -> a));

        List<KnowledgeChunkEntity> toSave = new ArrayList<>();       // 新 / 内容变化：写 MySQL + 向量
        List<KnowledgeChunkEntity> toVectorOnly = new ArrayList<>(); // 已写 MySQL、仅缺向量
        List<KnowledgeChunkEntity> stale = new ArrayList<>();        // 作废/多余旧 chunk（删 MySQL + 向量）
        int skipCount = 0;                                          // 已完整处理，直接跳过

        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            String content = chunk.getText() != null ? chunk.getText() : "";
            String hash = sha256(content);
            KnowledgeChunkEntity old = existingByIndex.get(i);
            if (old == null || !hash.equals(old.getContentHash())) {
                // 新增或内容变化（如切分逻辑升级）：旧行（若有）作废删除；Milvus 主键按 index 相同，upsert 自动覆盖
                if (old != null) {
                    stale.add(old);
                }
                toSave.add(buildChunkEntity(docEntity.getId(), i, content, hash, parsePageNo(chunk, i)));
            } else if (old.getMilvusId() == null) {
                // MySQL 已写但向量缺失（上次失败在写向量阶段）：只补向量
                toVectorOnly.add(old);
            } else {
                // MySQL + 向量均已写入：跳过（省写库 / embedding / 向量写入）
                skipCount++;
            }
        }
        // 新切分数量变少时，尾部残留旧 chunk（index >= 新数量）需清理
        for (Map.Entry<Integer, KnowledgeChunkEntity> entry : existingByIndex.entrySet()) {
            if (entry.getKey() >= chunks.size()) {
                stale.add(entry.getValue());
            }
        }
        return new ChunkDiff(toSave, toVectorOnly, stale, skipCount);
    }

    /**
     * 删除作废/多余的旧 chunk（MySQL 行 + Milvus 向量）。
     */
    private void cleanStaleChunks(KnowledgeDocumentEntity docEntity, List<Document> chunks,
                                  List<KnowledgeChunkEntity> stale) {
        if (stale.isEmpty()) {
            return;
        }
        List<Long> staleIds = stale.stream().map(KnowledgeChunkEntity::getId).toList();
        knowledgeChunkEntityService.removeByIds(staleIds);
        // 覆盖删除该文档 index >= 新切分数的向量（含未回填 milvus_id 的残留）
        vectorStoreService.deleteVectorsByChunkIndexFrom(
                docEntity.getKnowledgeId(), docEntity.getId(), chunks.size());
        log.info("增量清理 {} 个作废/多余旧 chunk (documentId={})", stale.size(), docEntity.getId());
    }

    /**
     * 新增/变化 chunk 批量写入 MySQL（插入后 id 回填，供 Milvus chunkId 字段引用）。
     */
    private void persistChunksToMysql(KnowledgeEmbeddingTaskEntity task, KnowledgeDocumentEntity docEntity,
                                      List<KnowledgeChunkEntity> toSave) {
        if (toSave.isEmpty()) {
            return;
        }
        knowledgeChunkEntityService.saveBatch(toSave);
        task.setChunkProgress(100);
        task.setUpdateTime(new Date());
        knowledgeEmbeddingTaskService.updateById(task);
        log.info("新增 {} 个 chunk 已写入 MySQL knowledge_chunk (documentId={})", toSave.size(), docEntity.getId());
    }

    /**
     * 向量化（Batch 流水线，拆两阶段分别推进，前端可分别展示 Embedding / Milvus 进度）：
     * <ul>
     *     <li>Embedding：按 batch-size 分批向量化，实时更新 embed_progress；向量暂存内存
     *         （总量 ≈ total×1024×4B，10000 chunks 上限时约 40MB，可控）。</li>
     *     <li>Milvus：按 batch-size 分批 upsert + 回填 milvus_id + 更新任务成功数。</li>
     * </ul>
     * 每批 = 一次 embedding 批量调用 / 一次 Milvus upsert，避免超大文档单次调用内存与超时风险。
     *
     * @return 实际写入 Milvus 的向量数
     */
    private int embedAndUpsertVectors(KnowledgeEmbeddingTaskEntity task, KnowledgeDocumentEntity docEntity,
                                      List<Document> chunks, ChunkDiff diff) {
        List<KnowledgeChunkEntity> toSave = diff.toSave();
        List<KnowledgeChunkEntity> toVectorOnly = diff.toVectorOnly();
        if (toSave.isEmpty() && toVectorOnly.isEmpty()) {
            return 0;
        }
        updateDocumentStatus(docEntity, DocumentStatus.EMBEDDING);
        List<KnowledgeChunkEntity> vectorEntities = new ArrayList<>(toSave);
        vectorEntities.addAll(toVectorOnly);
        int batchSize = ragConfig.getDocument().getBatchSize();
        int total = vectorEntities.size();
        int totalBatches = (total + batchSize - 1) / batchSize;

        // 7a. Embedding 阶段
        List<float[]> allEmbeddings = new ArrayList<>(total);
        for (int i = 0; i < total; i += batchSize) {
            List<KnowledgeChunkEntity> batch = vectorEntities.subList(i, Math.min(i + batchSize, total));
            allEmbeddings.addAll(vectorStoreService.embedChunks(
                    toChunkVectorData(docEntity.getKnowledgeId(), docEntity.getId(), batch, chunks)));
            task.setEmbedProgress((int) ((i + batch.size()) * 100L / total));
            task.setUpdateTime(new Date());
            knowledgeEmbeddingTaskService.updateById(task);
            log.info("Embedding 批次 {}/{}: 已向量化 {} 个文本 (documentId={}, 进度 {}%)",
                    (i / batchSize) + 1, totalBatches, batch.size(), docEntity.getId(), task.getEmbedProgress());
        }

        // 7b. Milvus 阶段：按批 upsert + 回填 milvus_id + 更新成功数
        int vectorCount = 0;
        int processed = 0;
        for (int i = 0; i < total; i += batchSize) {
            List<KnowledgeChunkEntity> batch = vectorEntities.subList(i, Math.min(i + batchSize, total));
            List<float[]> embeddings = allEmbeddings.subList(i, Math.min(i + batchSize, total));
            vectorCount += vectorStoreService.upsertVectors(docEntity.getKnowledgeId(),
                    toChunkVectorData(docEntity.getKnowledgeId(), docEntity.getId(), batch, chunks), embeddings);
            // 回填 milvus_id：作为"向量已写入"的增量判定依据（下次恢复时跳过）
            for (KnowledgeChunkEntity entity : batch) {
                entity.setMilvusId(VectorStoreService.buildVectorId(docEntity.getId(), entity.getChunkIndex()));
            }
            knowledgeChunkEntityService.updateBatchById(batch);
            processed += batch.size();
            // 每批完成后回写任务进度，便于前端实时展示（成功数 = 增量跳过 + 已写入）
            task.setMilvusProgress((int) (processed * 100L / total));
            task.setSuccessChunk(diff.skipCount() + processed);
            task.setUpdateTime(new Date());
            knowledgeEmbeddingTaskService.updateById(task);
            log.info("Milvus 批次 {}/{}: 已写入 {} 个 chunk 并回填 (documentId={}, 累计 {}/{}, 进度 {}%)",
                    (i / batchSize) + 1, totalBatches, batch.size(),
                    docEntity.getId(), diff.skipCount() + processed, chunks.size(), task.getMilvusProgress());
        }
        return vectorCount;
    }

    /**
     * 任务收尾：文档置成功（含 chunk 数）、同名旧版本标记过期（平滑下线）、任务置成功。
     * <p>分阶段进度统一置 100：即使本次全增量跳过（无新增/补齐 chunk），
     * 各阶段实际已完成，避免"任务成功但进度条全 0"的困惑展示。</p>
     */
    private void finishTask(KnowledgeEmbeddingTaskEntity task, KnowledgeDocumentEntity docEntity,
                            List<Document> chunks, ChunkDiff diff, int vectorCount, long start) {
        docEntity.setChunkCount(chunks.size());
        updateDocumentStatus(docEntity, DocumentStatus.SUCCESS);
        deprecateOldVersions(docEntity.getKnowledgeId(), docEntity);

        task.setStatus(KnowledgeEmbeddingTaskStatus.SUCCESS);
        task.setChunkProgress(100);
        task.setEmbedProgress(100);
        task.setMilvusProgress(100);
        task.setSuccessChunk(diff.skipCount() + vectorCount);
        task.setFailChunk(Math.max(0, chunks.size() - diff.skipCount() - vectorCount));
        task.setFinishTime(new Date());
        task.setCostTime(System.currentTimeMillis() - start);
        task.setUpdateTime(new Date());
        knowledgeEmbeddingTaskService.updateById(task);
        log.info("任务 {} 处理完成: 总chunk={}, 新增/补齐={}, 跳过={}, 向量={}, 耗时 {}ms",
                task.getTaskNo(), chunks.size(), diff.toSave().size() + diff.toVectorOnly().size(),
                diff.skipCount(), vectorCount, task.getCostTime());
    }

    /**
     * 任务失败后回滚未完成的向量：已回填 milvus_id 的视为已完成保留（供下次恢复增量跳过），
     * 仅删除未完成的残留。
     */
    private void rollbackIncompleteVectors(KnowledgeDocumentEntity docEntity) {
        try {
            List<Long> doneMilvusIds = knowledgeChunkEntityService.lambdaQuery()
                    .eq(KnowledgeChunkEntity::getDocumentId, docEntity.getId())
                    .isNotNull(KnowledgeChunkEntity::getMilvusId)
                    .list().stream()
                    .map(KnowledgeChunkEntity::getMilvusId)
                    .toList();
            vectorStoreService.deleteVectorsByDocumentIdExcept(
                    docEntity.getKnowledgeId(), docEntity.getId(), doneMilvusIds);
        } catch (Exception ex) {
            log.error("任务失败后清理未完成 Milvus 向量失败", ex);
        }
    }

    /**
     * 增量分类结果：新增/变化、仅缺向量、作废/多余、完整跳过。
     */
    private record ChunkDiff(List<KnowledgeChunkEntity> toSave,
                             List<KnowledgeChunkEntity> toVectorOnly,
                             List<KnowledgeChunkEntity> stale,
                             int skipCount) {
    }

    /**
     * 将异常转为对用户友好的错误信息：
     * 向量化/Embedding 类故障（含熔断抛出的 {@link EmbeddingServiceUnavailableException}）
     * 归一为「向量化服务暂时不可用，请稍后重试」；其余异常截断原始消息，
     * 避免向用户暴露过长堆栈。
     */
    private String friendlyErrorMessage(Throwable e) {
        if (e instanceof EmbeddingServiceUnavailableException) {
            return "向量化服务暂时不可用，请稍后重试";
        }
        String msg = e.getMessage();
        if (msg != null && (msg.contains("DashScope") || msg.contains("dashscope")
                || msg.contains("embedding") || msg.contains("Embedding"))) {
            return "向量化服务暂时不可用，请稍后重试";
        }
        if (msg == null || msg.isBlank()) {
            msg = e.toString();
        }
        return msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
    }

    /**
     * 将任务标记为失败（含错误信息与耗时）
     */
    private void failTask(KnowledgeEmbeddingTaskEntity task, String error, long start) {
        try {
            int total = task.getTotalChunk() == null ? 0 : task.getTotalChunk();
            int success = task.getSuccessChunk() == null ? 0 : task.getSuccessChunk();
            task.setStatus(KnowledgeEmbeddingTaskStatus.FAILED);
            task.setFailChunk(Math.max(0, total - success));
            task.setErrorMessage(error != null && error.length() > 2000 ? error.substring(0, 2000) : error);
            task.setFinishTime(new Date());
            task.setCostTime(System.currentTimeMillis() - start);
            task.setUpdateTime(new Date());
            knowledgeEmbeddingTaskService.updateById(task);
            log.warn("任务 {} 已标记为失败", task.getTaskNo());
        } catch (Exception ex) {
            log.error("更新任务失败状态异常: taskNo={}", task.getTaskNo(), ex);
        }
    }

    /**
     * 生成任务编号：EMB + 时间戳 + 随机串
     */
    private String generateTaskNo() {
        return "EMB" + System.currentTimeMillis() + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    // ===================== 步骤 1：保存原始文档信息（含版本号推断） =====================

    /**
     * 保存文档信息到 MySQL，自动推断版本号；
     * 原始文件由调用方在后续步骤最先通过 {@link #persistUploadedFile} 持久化。
     * <p>并发安全：同名文档同时上传时，两个线程可能推断出相同版本号，
     * 依赖唯一索引 (knowledge_id, file_name, version) 拦截冲突，捕获 DuplicateKeyException 后重查重试。</p>
     */
    protected KnowledgeDocumentEntity saveDocumentInfo(MultipartFile file, Long knowledgeBaseId) {
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase();
        }

        // 并发同名上传时版本号可能被并发推断重号，靠唯一索引兜底并重试（最多 MAX_VERSION_RETRY 次）
        for (int attempt = 0; ; attempt++) {
            // 查找同一知识库下同名文档的所有版本（不限状态，避免失败版本导致版本号重号），确定新版本号
            int newVersion = 1;
            List<KnowledgeDocumentEntity> existingDocs = knowledgeDocumentEntityService.lambdaQuery()
                    .eq(KnowledgeDocumentEntity::getKnowledgeId, knowledgeBaseId)
                    .eq(KnowledgeDocumentEntity::getFileName, originalFilename)
                    .orderByDesc(KnowledgeDocumentEntity::getVersion)
                    .list();
            if (!existingDocs.isEmpty()) {
                newVersion = existingDocs.get(0).getVersion() + 1;
            }

            KnowledgeDocumentEntity entity = new KnowledgeDocumentEntity();
            entity.setKnowledgeId(knowledgeBaseId);
            entity.setFileName(originalFilename);
            entity.setFilePath(originalFilename);
            entity.setFileSize(file.getSize());
            entity.setFileType(extension);
            entity.setChunkCount(0);
            entity.setStatus(DocumentStatus.UPLOADING.getCode());
            entity.setVersion(newVersion);
            entity.setIsActive(1);
            entity.setCreateTime(new Date());
            entity.setUpdateTime(new Date());

            try {
                knowledgeDocumentEntityService.save(entity);
                log.info("原始文档信息已保存: {} v{} (id={}, knowledgeBaseId={})",
                        originalFilename, newVersion, entity.getId(), knowledgeBaseId);
                return entity;
            } catch (DuplicateKeyException e) {
                if (attempt >= MAX_VERSION_RETRY) {
                    throw new RuntimeException("同名文档并发上传版本号冲突，请稍后重试", e);
                }
                log.warn("同名文档并发上传版本号冲突（v{}），第 {} 次重试", newVersion, attempt + 1);
            }
        }
    }

    // ===================== 文档删除 =====================

    /**
     * 删除文档及其关联数据：MySQL 文档+chunk 记录、Milvus 向量、MinIO 文件。
     * 三个存储独立处理：任一个失败不影响其他回滚（只记日志，不阻断）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDocument(Long documentId) {
        KnowledgeDocumentEntity doc = knowledgeDocumentEntityService.getById(documentId);
        if (doc == null) {
            log.warn("删除文档时未找到记录: id={}", documentId);
            return;
        }

        // 0. 防幽灵数据：存在待处理/处理中的 Embedding 任务时拒绝删除。
        //    异步摄取线程已持有内存中的任务对象，删除记录并不能中止它，
        //    它仍可能在删除后重新插入 chunk 并写入 Milvus 向量，
        //    产生无法从文档维度回收的残留数据。
        long runningTaskCount = knowledgeEmbeddingTaskService.lambdaQuery()
                .eq(KnowledgeEmbeddingTaskEntity::getDocumentId, documentId)
                .in(KnowledgeEmbeddingTaskEntity::getStatus,
                        KnowledgeEmbeddingTaskStatus.PENDING, KnowledgeEmbeddingTaskStatus.PROCESSING)
                .count();
        if (runningTaskCount > 0) {
            log.warn("拒绝删除文档: id={}, 存在处理中的 Embedding 任务数={}", documentId, runningTaskCount);
            throw new IllegalStateException("文档正在处理中，无法删除，请稍后再试");
        }

        log.info("开始删除文档: id={}, fileName={}, knowledgeBaseId={}", documentId, doc.getFileName(), doc.getKnowledgeId());

        // 0. MySQL：删除关联的 Embedding 任务记录（先于 document，满足外键约束）
        try {
            knowledgeEmbeddingTaskService.lambdaUpdate()
                    .eq(KnowledgeEmbeddingTaskEntity::getDocumentId, documentId)
                    .remove();
            log.info("MySQL Embedding 任务记录已删除: documentId={}", documentId);
        } catch (Exception e) {
            log.error("删除 Embedding 任务记录失败: documentId={}", documentId, e);
        }

        // 1. MySQL：删除 chunk 记录
        boolean chunkDeleted = knowledgeChunkEntityService.lambdaUpdate()
                .eq(KnowledgeChunkEntity::getDocumentId, documentId)
                .remove();
        log.info("MySQL chunk 记录已删除: documentId={}, 结果={}", documentId, chunkDeleted);

        // 2. MySQL：删除 document 记录（@Transactional 保证与 chunk 原子性）
        knowledgeDocumentEntityService.removeById(documentId);
        log.info("MySQL 文档记录已删除: id={}", documentId);

        // 3. MinIO：删除文件（独立捕获异常，不阻塞事务提交）
        String filePath = doc.getFilePath();
        if (filePath != null && !filePath.isBlank()) {
            try {
                fileStorageService.delete(filePath);
            } catch (Exception e) {
                log.error("MinIO 文件删除失败（文档 id={}, path={}），需手动清理", documentId, filePath, e);
            }
        }

        // 4. Milvus：删除向量（独立捕获异常，不阻塞事务提交）
        if (doc.getKnowledgeId() != null) {
            try {
                vectorStoreService.deleteVectorsByDocumentId(doc.getKnowledgeId(), documentId);
            } catch (Exception e) {
                log.error("Milvus 向量删除失败（知识库 id={}, 文档 id={}），需手动清理", doc.getKnowledgeId(), documentId, e);
            }
        }
    }

    /**
     * 将上传文件持久化到存储后端（本地磁盘或 MinIO），按 年/月/日 分层存储。
     * <p>在摄取流程中最先调用：优先落盘原始文件，避免后续步骤失败产生"孤儿索引"
     * （有索引/向量但原始文件缺失）。失败时由 {@link #submitIngest} 补偿删除。
     *
     * @return 对象存储路径
     */
    private String persistUploadedFile(byte[] fileBytes, KnowledgeDocumentEntity entity) throws Exception {
        String extension = entity.getFileType();
        LocalDate now = LocalDate.now();
        String datePath = String.format("%04d/%02d/%02d", now.getYear(), now.getMonthValue(), now.getDayOfMonth());
        String objectName = entity.getKnowledgeId() + "/" + datePath + "/"
                + entity.getId() + "_" + sanitizeFileName(entity.getFileName()) + "." + extension;

        fileStorageService.store(new ByteArrayInputStream(fileBytes), objectName,
                "application/" + (extension.equals("pdf") ? "pdf" : "octet-stream"));
        entity.setFilePath(objectName);
        knowledgeDocumentEntityService.updateById(entity);
        log.info("文件已持久化: {}", objectName);
        return objectName;
    }

    // ===================== 辅助方法 =====================

    /**
     * 清洗文件名，去掉可能导致对象存储路径问题的特殊字符
     */
    private String sanitizeFileName(String fileName) {
        if (fileName == null) return "unknown";
        String name = fileName;
        if (name.contains(".")) {
            name = name.substring(0, name.lastIndexOf('.'));
        }
        // 保留中文、英文、数字、下划线、连字符，其余替换为下划线
        name = name.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9_\\-]", "_");
        // 长度限制 100 字符
        if (name.length() > 100) {
            name = name.substring(0, 100);
        }
        return name;
    }

    /**
     * 标记文档为失败状态（独立事务，不受主事务回滚影响）
     */
    private void markDocumentFailed(KnowledgeDocumentEntity entity) {
        if (entity == null || entity.getId() == null) return;
        try {
            transactionTemplate.executeWithoutResult(status -> {
                KnowledgeDocumentEntity latest = knowledgeDocumentEntityService.getById(entity.getId());
                if (latest != null) {
                    latest.setStatus(DocumentStatus.FAILED.getCode());
                    latest.setUpdateTime(new Date());
                    knowledgeDocumentEntityService.updateById(latest);
                    log.error("文档 {} v{} 已标记为失败", latest.getFileName(), latest.getVersion());
                }
            });
        } catch (Exception ex) {
            log.error("标记文档失败状态异常", ex);
        }
    }

    /**
     * 新版本入库成功后，为同名旧版本标记已废弃（DEPRECATED）并设置过期时间（平滑下线）。
     * 问答检索默认新版优先（同名只召回最高版本），TTL 作为兜底安全网：
     * 最新版本被删除后，TTL 内旧版本可自动接管服务；超期后懒标记为 EXPIRED 并在问答中过滤。
     */
    private void deprecateOldVersions(Long knowledgeBaseId, KnowledgeDocumentEntity newDoc) {
        int ttlDays = ragConfig.getDocument().getVersionTtlDays();
        Date expireTime = new Date(System.currentTimeMillis() + ttlDays * 86400000L);

        List<KnowledgeDocumentEntity> oldActiveDocs = knowledgeDocumentEntityService.lambdaQuery()
                .eq(KnowledgeDocumentEntity::getKnowledgeId, knowledgeBaseId)
                .eq(KnowledgeDocumentEntity::getFileName, newDoc.getFileName())
                .eq(KnowledgeDocumentEntity::getStatus, DocumentStatus.SUCCESS.getCode())
                .eq(KnowledgeDocumentEntity::getIsActive, 1)
                .ne(KnowledgeDocumentEntity::getId, newDoc.getId())  // 排除新版本自身
                .isNull(KnowledgeDocumentEntity::getExpireTime)      // 尚未设置过期时间的
                .list();

        for (KnowledgeDocumentEntity oldDoc : oldActiveDocs) {
            oldDoc.setStatus(DocumentStatus.DEPRECATED.getCode());
            oldDoc.setExpireTime(expireTime);
            oldDoc.setUpdateTime(new Date());
            knowledgeDocumentEntityService.updateById(oldDoc);
            log.info("旧版本 {} v{} 已置为已废弃（DEPRECATED），过期时间: {}（{}天后过期）",
                    oldDoc.getFileName(), oldDoc.getVersion(), expireTime, ttlDays);
        }
    }

    /**
     * 更新文档处理状态（进度感知：0上传中 → 1解析中 → 2向量化中 → 3成功/4失败）
     */
    private void updateDocumentStatus(KnowledgeDocumentEntity docEntity, DocumentStatus status) {
        docEntity.setStatus(status.getCode());
        docEntity.setUpdateTime(new Date());
        knowledgeDocumentEntityService.updateById(docEntity);
        log.info("文档 {} v{} 状态推进为 {}（{}）",
                docEntity.getFileName(), docEntity.getVersion(), status.getText(), status.getCode());
    }

    /**
     * 懒标记过期文档：将 TTL 到期（expire_time &lt; now）的 SUCCESS/DEPRECATED 版本置为 EXPIRED。
     * 幂等、可重复执行，由 chat 检索前触发，无需定时任务。
     */
    private void expireOverdueDocuments() {
        try {
            boolean updated = knowledgeDocumentEntityService.lambdaUpdate()
                    .in(KnowledgeDocumentEntity::getStatus,
                            DocumentStatus.SUCCESS.getCode(), DocumentStatus.DEPRECATED.getCode())
                    .isNotNull(KnowledgeDocumentEntity::getExpireTime)
                    .lt(KnowledgeDocumentEntity::getExpireTime, new Date())
                    .set(KnowledgeDocumentEntity::getStatus, DocumentStatus.EXPIRED.getCode())
                    .set(KnowledgeDocumentEntity::getUpdateTime, new Date())
                    .update();
            if (updated) {
                log.info("已执行过期文档懒标记（存在 TTL 到期的版本被置为 EXPIRED）");
            }
        } catch (Exception e) {
            log.warn("懒标记过期文档失败: {}", e.getMessage());
        }
    }

    // ===================== 步骤 2-3：解析与切分（子类实现） =====================

    protected abstract List<Document> parseDocument(MultipartFile file) throws IOException;

    protected abstract List<Document> parseDocument(InputStream inputStream) throws IOException;

    protected abstract List<Document> splitDocument(List<Document> documents);

    // ===================== 步骤 4/5：chunk 写入 MySQL + 向量写入 Milvus =====================

    /**
     * 构建单个 chunk 实体（增量分类后按需写入，插入后 id 由 MyBatis-Plus 回填）
     */
    protected KnowledgeChunkEntity buildChunkEntity(Long documentId, int chunkIndex,
                                                    String content, String contentHash, Integer pageNo) {
        KnowledgeChunkEntity entity = new KnowledgeChunkEntity();
        entity.setDocumentId(documentId);
        entity.setChunkIndex(chunkIndex);
        entity.setContent(content);
        entity.setContentHash(contentHash);
        entity.setTokenCount(0);
        entity.setPageNo(pageNo);
        entity.setCreateTime(new Date());
        return entity;
    }

    /**
     * 构建 ChunkVectorData 列表（纯内存，供 Embedding / Milvus 两阶段分批使用）
     * <p>增量场景下 chunkEntities 的 chunkIndex 可能不连续（已完成的被跳过），
     * 故按 entity.chunkIndex 从切分结果中取对应文本（hash 一致时内容相同）。
     */
    protected List<VectorStoreService.ChunkVectorData> toChunkVectorData(Long knowledgeBaseId, Long documentId,
                                                                         List<KnowledgeChunkEntity> chunkEntities,
                                                                         List<Document> chunks) {
        List<VectorStoreService.ChunkVectorData> dataList = new ArrayList<>();
        for (KnowledgeChunkEntity entity : chunkEntities) {
            Document chunk = chunks.get(entity.getChunkIndex());
            dataList.add(new VectorStoreService.ChunkVectorData(
                    entity.getId(),
                    documentId,
                    entity.getPageNo(),
                    entity.getChunkIndex(),
                    chunk.getText() != null ? chunk.getText() : ""));
        }
        return dataList;
    }

    // ===================== 知识问答 =====================

    /**
     * 知识问答结果：包含答案和引用来源
     */
    public record ChatResult(String answer, List<SourceInfo> sources) {}

    /**
     * 引用来源信息
     *
     * @param refIndex 原始来源编号（1-based，与回答中 [来源N] 对应）
     */
    public record SourceInfo(Long documentId, String documentName, Integer pageNo, String snippet, Integer refIndex) {}

    /**
     * 在指定知识库中进行问答：向量检索 → MySQL 获取 chunk 文本 → LLM 生成回答
     */
    public ChatResult chat(String question, Long knowledgeBaseId) {
        // 服务层权限守卫（纵深防御）：问答/检索需要 VIEWER 及以上
        kbAuthorizationService.assertRole(knowledgeBaseId, KbRole.VIEWER);

        // 0. 懒标记过期文档（TTL 到期的 SUCCESS/DEPRECATED 置为 EXPIRED），无需定时任务
        expireOverdueDocuments();

        // 1. 检索召回：启用 Hybrid Search 时走「Dense + BM25 + RRF 融合」，否则纯向量检索
        //    召回 candidateTopK 个候选，供后续 Rerank 精排
        RagConfigProperties.Rerank rerankConfig = ragConfig.getRerank();
        List<VectorStoreService.SearchResult> searchResults;
        if (ragConfig.getHybrid().isEnabled()) {
            searchResults = hybridSearchService.search(knowledgeBaseId, question,
                    rerankConfig.getCandidateTopK(), rerankConfig.getThreshold());
        } else {
            searchResults = vectorStoreService.search(knowledgeBaseId, question,
                    rerankConfig.getCandidateTopK(), rerankConfig.getThreshold());
        }

        if (searchResults.isEmpty()) {
            String answer = callLlm(null, question);
            return new ChatResult(answer, List.of());
        }

        // 2. 从 MySQL 获取 chunk 内容
        List<Long> chunkIds = searchResults.stream()
                .map(VectorStoreService.SearchResult::getChunkId)
                .toList();
        List<KnowledgeChunkEntity> chunks = knowledgeChunkEntityService.listByIds(chunkIds);

        // 按检索顺序组装上下文
        Map<Long, KnowledgeChunkEntity> chunkMap = chunks.stream()
                .collect(Collectors.toMap(KnowledgeChunkEntity::getId, c -> c, (a, b) -> a));

        // 获取文档信息，过滤已过期版本，且同名文档多版本只保留最高版本（新版优先，避免混入旧内容）
        Date now = new Date();
        List<Long> docIds = searchResults.stream()
                .map(VectorStoreService.SearchResult::getDocumentId)
                .distinct()
                .toList();
        Map<Long, KnowledgeDocumentEntity> docMap = knowledgeDocumentEntityService.listByIds(docIds).stream()
                .collect(Collectors.toMap(KnowledgeDocumentEntity::getId, d -> d, (a, b) -> a));

        // 过滤：只允许 SUCCESS(生效) 与 DEPRECATED(TTL 兜底) 版本参与问答，
        // 排除处理中(0/1/2)、失败(4)、已过期(6) 的残留向量
        Map<Long, String> docNameMap = new LinkedHashMap<>();
        List<VectorStoreService.SearchResult> validResults = new ArrayList<>();
        // 第一遍：按状态过滤，收集活跃文档，并统计同名文档的最高版本
        Map<Long, KnowledgeDocumentEntity> activeDocs = new HashMap<>();
        Map<String, Integer> maxVersionByFileName = new HashMap<>();
        for (VectorStoreService.SearchResult r : searchResults) {
            KnowledgeDocumentEntity doc = docMap.get(r.getDocumentId());
            if (doc == null) continue;
            Integer st = doc.getStatus();
            // 仅成功/已废弃版本可参与（处理中、失败、已过期一律排除）
            if (st == null
                    || (st != DocumentStatus.SUCCESS.getCode() && st != DocumentStatus.DEPRECATED.getCode())) continue;
            // 兜底：expire_time 已到但尚未懒标记的按过期处理
            if (doc.getExpireTime() != null && doc.getExpireTime().before(now)) continue;
            activeDocs.putIfAbsent(doc.getId(), doc);
            String fileName = doc.getFileName();
            int ver = doc.getVersion() != null ? doc.getVersion() : 0;
            maxVersionByFileName.merge(fileName, ver, Math::max);
        }
        // 第二遍：同名文档只保留版本最高的检索结果（旧版本不再召回；最新版本被删除后旧版本自动接管）
        for (VectorStoreService.SearchResult r : searchResults) {
            KnowledgeDocumentEntity doc = activeDocs.get(r.getDocumentId());
            if (doc == null) continue;
            int ver = doc.getVersion() != null ? doc.getVersion() : 0;
            if (ver < maxVersionByFileName.getOrDefault(doc.getFileName(), ver)) continue;
            validResults.add(r);
            docNameMap.putIfAbsent(doc.getId(), doc.getFileName());
        }
        searchResults = validResults;

        // 2.5 召回重排序（Rerank）：对候选片段按 "问题相关性" 精排，取 topN
        if (rerankService.isEnabled() && searchResults.size() > 1) {
            List<String> texts = searchResults.stream()
                    .map(r -> {
                        KnowledgeChunkEntity chunk = chunkMap.get(r.getChunkId());
                        return chunk == null ? "" : chunk.getContent();
                    })
                    .toList();
            try {
                List<RerankService.RerankItem> ranked =
                        rerankService.rerank(question, texts, rerankConfig.getTopN());
                if (ranked != null && !ranked.isEmpty()) {
                    // 按重排分数降序重建检索结果（只保留 topN）
                    List<VectorStoreService.SearchResult> currentResults = searchResults;
                    List<VectorStoreService.SearchResult> reranked = ranked.stream()
                            .map(item -> currentResults.get(item.index()))
                            .toList();
                    searchResults = reranked;
                    log.debug("Rerank 精排完成，保留 {} 条候选", searchResults.size());
                } else {
                    log.debug("Rerank 未返回结果，降级为向量排序结果");
                }
            } catch (Exception e) {
                log.warn("Rerank 精排失败，降级为向量排序结果: {}", e.getMessage());
            }
        }

        // 构建来源信息
        List<SourceInfo> sources = new ArrayList<>();
        StringBuilder contextBuilder = new StringBuilder();
        int refIndex = 1;

        for (VectorStoreService.SearchResult r : searchResults) {
            KnowledgeChunkEntity chunk = chunkMap.get(r.getChunkId());
            if (chunk == null) continue;

            String docName = docNameMap.getOrDefault(r.getDocumentId(), "未知文档");
            String snippet = chunk.getContent();
            if (snippet.length() > 120) {
                snippet = snippet.substring(0, 120) + "...";
            }

            int ref = refIndex++;
            sources.add(new SourceInfo(r.getDocumentId(), docName, r.getPageNo(), snippet, ref));

            // 在上下文中标记来源
            contextBuilder.append(String.format("[来源%d] 文档：%s，第%d页%n%s%n%n",
                    ref, docName, r.getPageNo() != null ? r.getPageNo() : 1, chunk.getContent()));
        }

        String context = contextBuilder.toString();
        if (context.isEmpty()) {
            String answer = callLlm(null, question);
            return new ChatResult(answer, List.of());
        }

        // 3. LLM 生成回答
        String systemPrompt = String.format(
                "你是一个基于知识库的问答助手。请严格根据以下知识库内容回答用户问题。%n"
                + "回答时，请在引用知识库内容的地方用方括号标注来源编号，例如[来源1]、[来源2]。%n"
                + "回答请使用纯文本，禁止使用任何 Markdown 格式（如 **加粗**、*斜体*、# 标题、- 列表、> 引用等）。%n"
                + "如果知识库中没有相关信息，请如实告知用户\"知识库中暂无相关信息\"。%n"
                + "%n"
                + "知识库内容：%n"
                + "%s", context);

        String answer = callLlm(systemPrompt, question);

        // AI 服务不可用（DeepSeek 调用异常/熔断）时降级：不展示引用来源
        if (AI_SERVICE_UNAVAILABLE.equals(answer)) {
            log.warn("AI 服务不可用，知识问答降级处理：knowledgeBaseId={}, question={}", knowledgeBaseId, question);
            return new ChatResult(answer, List.of());
        }

        // 兜底清理：LLM 偶尔仍会输出 Markdown 加粗/行内代码符号，统一移除，保持纯文本展示
        if (answer != null) {
            answer = answer.replace("**", "").replace("`", "");
        }

        // 从回答中提取实际引用的来源编号，只保留精准来源（TreeSet 保证按来源编号升序返回）
        Set<Integer> citedRefs = new TreeSet<>();
        Matcher m = Pattern.compile("\\[来源(\\d+)\\]").matcher(answer);
        while (m.find()) {
            citedRefs.add(Integer.parseInt(m.group(1)));
        }

        // 被引用来源重新编号为从 1 开始的连续编号（旧编号 → 新编号），并同步改写回答中的 [来源N]
        Map<Integer, Integer> refMap = new HashMap<>();
        int newRef = 1;
        for (int idx : citedRefs) {
            if (idx >= 1 && idx <= sources.size()) {
                refMap.put(idx, newRef++);
            }
        }
        if (!refMap.isEmpty()) {
            Matcher rm = Pattern.compile("\\[来源(\\d+)\\]").matcher(answer);
            StringBuilder sb = new StringBuilder();
            while (rm.find()) {
                int oldRef = Integer.parseInt(rm.group(1));
                Integer mapped = refMap.get(oldRef);
                if (mapped != null) {
                    rm.appendReplacement(sb, Matcher.quoteReplacement("[来源" + mapped + "]"));
                } else {
                    rm.appendReplacement(sb, Matcher.quoteReplacement(rm.group()));
                }
            }
            rm.appendTail(sb);
            answer = sb.toString();
        }

        List<SourceInfo> citedSources = new ArrayList<>();
        for (int idx : citedRefs) {
            if (idx >= 1 && idx <= sources.size()) {
                SourceInfo s = sources.get(idx - 1);
                citedSources.add(new SourceInfo(s.documentId(), s.documentName(), s.pageNo(),
                        s.snippet(), refMap.get(idx)));
            }
        }

        return new ChatResult(answer, citedSources);
    }

    /**
     * 调用 LLM（DeepSeek）生成回答，带 Sentinel 熔断降级：
     * 调用异常/超时/熔断（资源 {@code ai-chat}）时返回降级提示，而不是向上抛错导致接口 500。
     *
     * @param systemPrompt 系统提示（无检索上下文时传 null）
     * @param question     用户问题
     * @return LLM 回答；AI 服务不可用时返回 {@link #AI_SERVICE_UNAVAILABLE}
     */
    private String callLlm(String systemPrompt, String question) {
        return circuitBreakerFactory.create(AiConfig.AI_CHAT_RESOURCE).run(
                () -> {
                    ChatClient.ChatClientRequestSpec spec = chatClient.prompt().user(question);
                    if (systemPrompt != null && !systemPrompt.isBlank()) {
                        spec = spec.system(systemPrompt);
                    }
                    String content = spec.call().content();
                    if (content == null || content.isBlank()) {
                        throw new IllegalStateException("AI 返回空内容");
                    }
                    return content;
                },
                t -> {
                    log.error("AI 服务（DeepSeek）调用失败，问答降级处理：{}", t.getMessage(), t);
                    return AI_SERVICE_UNAVAILABLE;
                });
    }

    // ===================== 辅助方法 =====================

    /**
     * 从 Document metadata 中提取页码，取不到时从 chunk 索引反推
     */
    private Integer parsePageNo(Document chunk, int index) {
        if (chunk.getMetadata() != null) {
            Object pageObj = chunk.getMetadata().get("page_number");
            if (pageObj == null) {
                pageObj = chunk.getMetadata().get("page");
            }
            if (pageObj instanceof Number) {
                return ((Number) pageObj).intValue();
            }
        }
        return index;
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
