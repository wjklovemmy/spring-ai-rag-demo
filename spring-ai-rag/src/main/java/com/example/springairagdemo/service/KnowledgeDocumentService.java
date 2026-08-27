package com.example.springairagdemo.service;

import com.example.springairagdemo.config.AiConfig;
import com.example.springairagdemo.config.RagConfigProperties;
import com.example.springairagdemo.entity.DocumentStatus;
import com.example.springairagdemo.entity.KnowledgeChunkEntity;
import com.example.springairagdemo.entity.KnowledgeDocumentEntity;
import com.example.springairagdemo.entity.KnowledgeEmbeddingTaskEntity;
import com.example.springairagdemo.entity.KnowledgeEmbeddingTaskStatus;
import com.example.springairagdemo.parser.PdfDocumentParser;
import com.example.springairagdemo.security.KbRole;
import com.example.springairagdemo.security.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DuplicateKeyException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    @Autowired
    protected ChatMemory chatMemory;

    @Autowired
    protected ChatSessionService chatSessionService;

    @Autowired
    protected AgentTaskService agentTaskService;

    /** Jackson 3 序列化（引用来源快照落库） */
    @Autowired
    protected ObjectMapper objectMapper;

    /** 对话模型名（spring.ai.deepseek.chat.model，可观测性记录） */
    @Value("${spring.ai.deepseek.chat.model:deepseek-chat}")
    protected String chatModelName;

    /** Sentinel 熔断降级器工厂（spring-cloud-circuitbreaker-sentinel 实现） */
    @Autowired
    protected CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    @Autowired
    protected RagConfigProperties ragConfig;

    /** 检索核心（Hybrid 召回/Rerank/上下文组装），由 searchKnowledge 工具调用 */
    @Autowired
    protected KnowledgeSearchService knowledgeSearchService;

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
        //    避免与其它入口并发重复处理同一任务）。
        //    注意：必须用 lambdaUpdate 显式 .set(..., null) 清空字段——
        //    updateById 默认 NOT_NULL 策略会跳过 null 字段，导致旧的错误信息/完成时间残留。
        int retryCount = task.getRetryCount() == null ? 1 : task.getRetryCount() + 1;
        knowledgeEmbeddingTaskService.lambdaUpdate()
                .eq(KnowledgeEmbeddingTaskEntity::getId, taskId)
                .set(KnowledgeEmbeddingTaskEntity::getStatus, KnowledgeEmbeddingTaskStatus.PENDING)
                .set(KnowledgeEmbeddingTaskEntity::getTotalChunk, 0)
                .set(KnowledgeEmbeddingTaskEntity::getSuccessChunk, 0)
                .set(KnowledgeEmbeddingTaskEntity::getFailChunk, 0)
                .set(KnowledgeEmbeddingTaskEntity::getRetryCount, retryCount)
                .set(KnowledgeEmbeddingTaskEntity::getErrorMessage, null)
                .set(KnowledgeEmbeddingTaskEntity::getFinishTime, null)
                .set(KnowledgeEmbeddingTaskEntity::getCostTime, null)
                .set(KnowledgeEmbeddingTaskEntity::getUpdateTime, new Date())
                .update();

        // 2. 重新入队执行（增量补齐：已处理过的 chunk 直接跳过）
        final Long finalTaskId = task.getId();
        CompletableFuture.runAsync(() -> processTaskAsync(finalTaskId), taskExecutor);
        log.info("重启恢复：任务 {} 已重新入队执行（增量补齐） documentId={}",
                task.getTaskNo(), docEntity.getId());
    }

    /**
     * 手动重试失败的 Embedding 任务（前端任务列表「重试」按钮）。
     * <p>
     * 原始文件在上传提交阶段（{@code submitIngest}）已最先持久化到存储后端，
     * 因此重试无需重新上传，直接复用 {@link #resumeInterruptedTask} 的增量重置逻辑：
     * 任务置回 PENDING 重新入队，已完整处理（MySQL + 向量均完成）的 chunk 增量跳过，
     * 只补齐缺失或内容变化的片段。
     *
     * @param taskId 任务 ID
     * @throws IllegalStateException 任务不存在 / 非失败状态 / 原始文件已丢失
     */
    public void retryTask(Long taskId) {
        KnowledgeEmbeddingTaskEntity task = knowledgeEmbeddingTaskService.getById(taskId);
        if (task == null) {
            throw new IllegalStateException("任务不存在");
        }
        if (task.getStatus() != KnowledgeEmbeddingTaskStatus.FAILED) {
            throw new IllegalStateException("只有失败的任务可以重试（当前状态：" + task.getStatus().getText() + "）");
        }
        KnowledgeDocumentEntity docEntity = knowledgeDocumentEntityService.getById(task.getDocumentId());
        if (docEntity == null || docEntity.getFilePath() == null) {
            throw new IllegalStateException("原始文档不存在，无法重试，请重新上传");
        }
        if (!fileStorageService.exists(docEntity.getFilePath())) {
            throw new IllegalStateException("原始文件已丢失，无法重试，请重新上传");
        }
        // 复用增量恢复逻辑：重置任务并重新入队，已处理的 chunk 自动跳过
        resumeInterruptedTask(taskId);
        log.info("用户手动重试任务：taskNo={} documentId={}", task.getTaskNo(), docEntity.getId());
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

            // 5. 新增/变化 chunk 批量写入 MySQL（Parent-Child：先写父块行、再写子块行并回填 parent_id），
            //    返回新写入的子块实体（id 已回填，供向量化阶段引用）
            List<KnowledgeChunkEntity> savedChildren = persistChunksToMysql(task, docEntity, diff, chunks);

            // 6. Embedding + Milvus upsert（分批推进，实时更新进度），返回实际写入向量数。
            //    仅子块向量化（父块只存 MySQL，不写 Milvus）
            int vectorCount = embedAndUpsertVectors(task, docEntity, chunks, diff, savedChildren);

            // 7. 收尾：文档置成功、废弃旧版本、任务置成功
            finishTask(task, docEntity, chunks, diff, savedChildren, vectorCount, start);
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
     * 新增/变化、仅缺向量、作废/多余、完整跳过。
     * <p>
     * Parent-Child 模式（切分结果携带 {@link PdfDocumentParser#META_PARENT_TEXT} 元数据）：
     * <ul>
     *     <li>父块（parent_id IS NULL）：语义/token 切分结果 + 标题注入，仅存 MySQL 不向量化，chunk_index = 父块序号</li>
     *     <li>子块（parent_id = 父块ID）：父块细分后的检索单元，向量化存 Milvus，chunk_index = 子块序号</li>
     * </ul>
     * 父块内容变化会级联作废其全部子块（子块由父块切出，内容必然变化）后重新写入。
     * 单级模式（未启用 / 存量单级数据）保持原有行为：全部按父块处理，历史子块行整体作废。
     */
    private ChunkDiff diffChunks(KnowledgeDocumentEntity docEntity, List<Document> chunks) {
        boolean pcEnabled = isParentChildChunks(chunks);
        List<KnowledgeChunkEntity> existing = knowledgeChunkEntityService.lambdaQuery()
                .eq(KnowledgeChunkEntity::getDocumentId, docEntity.getId())
                .list();
        Map<Integer, KnowledgeChunkEntity> existingParentByIndex = new HashMap<>();
        Map<Integer, KnowledgeChunkEntity> existingChildByIndex = new HashMap<>();
        for (KnowledgeChunkEntity e : existing) {
            if (e.getParentId() == null) {
                existingParentByIndex.put(e.getChunkIndex(), e);
            } else {
                existingChildByIndex.put(e.getChunkIndex(), e);
            }
        }

        List<KnowledgeChunkEntity> parentsToSave = new ArrayList<>();     // 新增/变化的父块（写 MySQL，不向量化）
        Map<Integer, KnowledgeChunkEntity> parentsKept = new HashMap<>(); // 未变化的父块（保留，供子块引用 parentId）
        List<ChildToSave> childrenToSave = new ArrayList<>();             // 新增/变化的子块（parentId 写入阶段回填）
        List<KnowledgeChunkEntity> toVectorOnly = new ArrayList<>();      // 已写 MySQL、仅缺向量
        List<KnowledgeChunkEntity> stale = new ArrayList<>();             // 作废/多余旧 chunk（删 MySQL + 向量）
        int skipCount = 0;                                                // 已完整处理，直接跳过

        if (pcEnabled) {
            // ---------- 1. 父块级 diff ----------
            List<Document> parents = buildParents(chunks); // parentIndex -> 父块 Document（含 pageNo）
            for (int i = 0; i < parents.size(); i++) {
                Document parent = parents.get(i);
                String content = parent.getText() != null ? parent.getText() : "";
                String hash = sha256(content);
                KnowledgeChunkEntity old = existingParentByIndex.get(i);
                if (old == null || !hash.equals(old.getContentHash())) {
                    // 新增或父块内容变化（如切分/标题注入升级）：旧父块作废，其子块在子块级一并作废
                    if (old != null) {
                        stale.add(old);
                    }
                    parentsToSave.add(buildChunkEntity(docEntity.getId(), i, content, hash, parsePageNo(parent, i)));
                } else {
                    parentsKept.put(i, old);
                }
            }
            // 新父块数量变少时，尾部残留旧父块需清理
            for (Map.Entry<Integer, KnowledgeChunkEntity> entry : existingParentByIndex.entrySet()) {
                if (entry.getKey() >= parents.size()) {
                    stale.add(entry.getValue());
                }
            }

            // ---------- 2. 子块级 diff ----------
            Map<String, Integer> parentIndexByText = new HashMap<>();
            for (int i = 0; i < parents.size(); i++) {
                parentIndexByText.putIfAbsent(parents.get(i).getText(), i);
            }
            for (int i = 0; i < chunks.size(); i++) {
                Document child = chunks.get(i);
                String content = child.getText() != null ? child.getText() : "";
                String hash = sha256(content);
                KnowledgeChunkEntity old = existingChildByIndex.get(i);
                Integer parentIdx = parentIndexOf(child, parentIndexByText);
                // 父块变化（未保留）→ 子块必然变化，级联重写；Milvus 主键按 index 相同，upsert 自动覆盖
                boolean parentChanged = parentIdx == null || !parentsKept.containsKey(parentIdx);
                if (old == null || !hash.equals(old.getContentHash()) || parentChanged) {
                    if (old != null) {
                        stale.add(old);
                    }
                    childrenToSave.add(new ChildToSave(i, parentIdx == null ? -1 : parentIdx));
                } else if (old.getMilvusId() == null) {
                    // MySQL 已写但向量缺失（上次失败在写向量阶段）：只补向量
                    toVectorOnly.add(old);
                } else {
                    // MySQL + 向量均已写入：跳过（省写库 / embedding / 向量写入）
                    skipCount++;
                }
            }
            // 新子块数量变少时，尾部残留旧子块需清理
            for (Map.Entry<Integer, KnowledgeChunkEntity> entry : existingChildByIndex.entrySet()) {
                if (entry.getKey() >= chunks.size()) {
                    stale.add(entry.getValue());
                }
            }
        } else {
            // ---------- 单级模式（原有行为）：全部按父块处理 ----------
            // 历史 Parent-Child 子块行（parent_id 非空）无法复用，整体作废（其向量由 index 相同的 upsert 覆盖/尾部清理）
            stale.addAll(existingChildByIndex.values());
            for (int i = 0; i < chunks.size(); i++) {
                Document chunk = chunks.get(i);
                String content = chunk.getText() != null ? chunk.getText() : "";
                String hash = sha256(content);
                KnowledgeChunkEntity old = existingParentByIndex.get(i);
                if (old == null || !hash.equals(old.getContentHash())) {
                    // 新增或内容变化（如切分逻辑升级）：旧行（若有）作废删除；Milvus 主键按 index 相同，upsert 自动覆盖
                    if (old != null) {
                        stale.add(old);
                    }
                    parentsToSave.add(buildChunkEntity(docEntity.getId(), i, content, hash, parsePageNo(chunk, i)));
                } else if (old.getMilvusId() == null) {
                    toVectorOnly.add(old);
                } else {
                    skipCount++;
                }
            }
            // 新切分数量变少时，尾部残留旧 chunk（index >= 新数量）需清理
            for (Map.Entry<Integer, KnowledgeChunkEntity> entry : existingParentByIndex.entrySet()) {
                if (entry.getKey() >= chunks.size()) {
                    stale.add(entry.getValue());
                }
            }
        }
        return new ChunkDiff(parentsToSave, parentsKept, childrenToSave, toVectorOnly, stale, skipCount);
    }

    /**
     * 判断本次切分结果是否为 Parent-Child 结构（子块携带 parent_text 元数据）。
     */
    private boolean isParentChildChunks(List<Document> chunks) {
        if (chunks.isEmpty()) {
            return false;
        }
        Map<String, Object> meta = chunks.get(0).getMetadata();
        return meta != null && meta.containsKey(PdfDocumentParser.META_PARENT_TEXT);
    }

    /**
     * 从子块列表重建父块列表（Parent-Child 检索）。
     * <p>子块按首次出现的 parent_text 去重，父块序号的分配即去重顺序；
     * 父块 Document 复用首个子块的 metadata（含 page_number），文本为父块全文（含标题链前缀）。</p>
     */
    private List<Document> buildParents(List<Document> chunks) {
        List<Document> parents = new ArrayList<>();
        Map<String, Integer> indexByText = new HashMap<>();
        for (Document child : chunks) {
            Map<String, Object> meta = child.getMetadata();
            Object pt = meta == null ? null : meta.get(PdfDocumentParser.META_PARENT_TEXT);
            String parentText = pt instanceof String s ? s : (child.getText() != null ? child.getText() : "");
            if (!indexByText.containsKey(parentText)) {
                indexByText.put(parentText, parents.size());
                parents.add(Document.builder().text(parentText).metadata(meta).build());
            }
        }
        return parents;
    }

    /**
     * 子块所属父块的序号（Parent-Child 检索）；未携带 parent_text 元数据时返回 null。
     */
    private Integer parentIndexOf(Document child, Map<String, Integer> parentIndexByText) {
        Map<String, Object> meta = child.getMetadata();
        Object pt = meta == null ? null : meta.get(PdfDocumentParser.META_PARENT_TEXT);
        if (pt instanceof String s) {
            return parentIndexByText.get(s);
        }
        return null;
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
     * 新增/变化 chunk 批量写入 MySQL（Parent-Child：先写父块行、再写子块行并回填 parent_id）。
     * <p>插入后 id 由 MyBatis-Plus saveBatch 回填：父块 id 供子块 parent_id 引用，
     * 子块 id 供 Milvus chunkId 字段引用。</p>
     *
     * @return 新写入的子块实体列表（id 已回填，供向量化阶段使用）；无子块写入时返回空列表
     */
    private List<KnowledgeChunkEntity> persistChunksToMysql(KnowledgeEmbeddingTaskEntity task,
                                                            KnowledgeDocumentEntity docEntity,
                                                            ChunkDiff diff, List<Document> chunks) {
        List<KnowledgeChunkEntity> parentsToSave = diff.parentsToSave();
        List<ChildToSave> childrenToSave = diff.childrenToSave();
        if (parentsToSave.isEmpty() && childrenToSave.isEmpty()) {
            return List.of();
        }
        // 1. 先写父块行（父块不向量化，仅作为子块的完整上下文与 parentId 锚点）
        knowledgeChunkEntityService.saveBatch(parentsToSave);
        // 2. 构建 parentIndex -> parentId 映射：新父块用 saveBatch 回填的 id，未变父块用已有行 id
        Map<Integer, Long> parentIdByIndex = new HashMap<>();
        for (KnowledgeChunkEntity p : parentsToSave) {
            parentIdByIndex.put(p.getChunkIndex(), p.getId());
        }
        for (KnowledgeChunkEntity p : diff.parentsKept().values()) {
            parentIdByIndex.put(p.getChunkIndex(), p.getId());
        }
        // 3. 构造子块实体（补 parentId）并写入
        List<KnowledgeChunkEntity> childEntities = new ArrayList<>(childrenToSave.size());
        for (ChildToSave c : childrenToSave) {
            Document childDoc = chunks.get(c.childIndex());
            String content = childDoc.getText() != null ? childDoc.getText() : "";
            childEntities.add(buildChildEntity(docEntity.getId(), c.childIndex(), content,
                    sha256(content), parsePageNo(childDoc, c.childIndex()),
                    parentIdByIndex.get(c.parentIndex())));
        }
        knowledgeChunkEntityService.saveBatch(childEntities);
        task.setChunkProgress(100);
        task.setUpdateTime(new Date());
        knowledgeEmbeddingTaskService.updateById(task);
        log.info("新增 {} 个父块 / {} 个子块已写入 MySQL knowledge_chunk (documentId={})",
                parentsToSave.size(), childEntities.size(), docEntity.getId());
        return childEntities;
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
                                      List<Document> chunks, ChunkDiff diff,
                                      List<KnowledgeChunkEntity> savedChildren) {
        // 仅子块向量化：父块（parent_id NULL）只存 MySQL，不参与 embedding / Milvus 写入
        List<KnowledgeChunkEntity> toSave = savedChildren;
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
                            List<Document> chunks, ChunkDiff diff, List<KnowledgeChunkEntity> savedChildren,
                            int vectorCount, long start) {
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
        log.info("任务 {} 处理完成: 总chunk={}, 新增父块={}, 新增子块={}, 补齐向量={}, 跳过={}, 向量={}, 耗时 {}ms",
                task.getTaskNo(), chunks.size(), diff.parentsToSave().size(), savedChildren.size(),
                diff.toVectorOnly().size(), diff.skipCount(), vectorCount, task.getCostTime());
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
     * 增量分类结果（Parent-Child 两级）：
     * <ul>
     *     <li>{@code parentsToSave}：新增/变化的父块（写 MySQL，不向量化）</li>
     *     <li>{@code parentsKept}：未变化的父块（parentIndex → 行，供子块引用 parentId）</li>
     *     <li>{@code childrenToSave}：新增/变化的子块（parentId 在写入阶段按 parentIndex 回填）</li>
     *     <li>{@code toVectorOnly}：已写 MySQL、仅缺向量</li>
     *     <li>{@code stale}：作废/多余（删 MySQL + 向量）</li>
     *     <li>{@code skipCount}：已完整处理，直接跳过</li>
     * </ul>
     */
    private record ChunkDiff(List<KnowledgeChunkEntity> parentsToSave,
                             Map<Integer, KnowledgeChunkEntity> parentsKept,
                             List<ChildToSave> childrenToSave,
                             List<KnowledgeChunkEntity> toVectorOnly,
                             List<KnowledgeChunkEntity> stale,
                             int skipCount) {
    }

    /** 待写入的子块：childIndex 为全局子块序号（= chunk_index，Milvus 主键编码依据），parentIndex 为父块序号（写入时回填 parentId） */
    private record ChildToSave(int childIndex, int parentIndex) {
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
     * 构建子块实体（Parent-Child 检索）：在父块基础上补充 parent_id 关联父块行。
     */
    protected KnowledgeChunkEntity buildChildEntity(Long documentId, int chunkIndex,
                                                    String content, String contentHash, Integer pageNo,
                                                    Long parentId) {
        KnowledgeChunkEntity entity = buildChunkEntity(documentId, chunkIndex, content, contentHash, pageNo);
        entity.setParentId(parentId);
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
     * 流式问答结果：token 增量流 + 最终回答上下文
     * <p>流式输出无法在生成前预知回答实际引用了哪些来源，来源在流生成完毕后
     * （模型调用 searchKnowledge 工具 / 服务层兜底检索后）才确定，因此随最终回答一起返回。
     *
     * @param stream 逐 token 增量文本流（与 answer 共享同一数据源，可先订阅 delta）
     * @param answer 生成完毕后（引用对齐校验 {@link #alignCitations} 后）的最终回答 + 完整候选来源
     */
    public record ChatStreamResult(Flux<String> stream, Mono<AnswerContext> answer,
                                   Sinks.Many<RagRetrievalService.ToolEvent> toolEvents) {

        /** 最终回答上下文：answer 为引用对齐后的全文，sources 为完整候选来源（编号不重排，空表示无检索来源） */
        public record AnswerContext(String answer, List<SourceInfo> sources) {}
    }

    /** 检索上下文：上下文文本 + 完整来源列表（context 为空表示知识库中无可用内容） */
    private record RetrievalContext(String context, List<SourceInfo> sources, List<String> fullContents) {}

    /**
     * 引用来源信息
     *
     * @param refIndex 原始来源编号（1-based，与回答中 [来源N] 对应）
     */
    public record SourceInfo(Long documentId, String documentName, Integer pageNo, String snippet, Integer refIndex) {}

    /**
     * 在指定知识库中进行 Agentic RAG 问答：模型自主决定是否调用 searchKnowledge 工具检索知识库，
     * 检索结果经工具回调汇总，LLM 严格基于工具返回的 [来源N] 片段生成回答
     *
     * @param sessionId 会话 ID（多轮对话记忆 key，null/空则用默认会话）
     */
    public ChatResult chat(String question, Long knowledgeBaseId, String sessionId) {
        // 服务层权限守卫（纵深防御）：问答/检索需要 VIEWER 及以上
        kbAuthorizationService.assertRole(knowledgeBaseId, KbRole.VIEWER);
        // 会话联动：补建/刷新会话元数据（标题、知识库、时间），旧 sessionId 平滑接入会话列表
        chatSessionService.touchOnChat(UserContext.getUserId(), sessionId, knowledgeBaseId, question);

        // 0. 懒标记过期文档（TTL 到期的 SUCCESS/DEPRECATED 置为 EXPIRED），无需定时任务
        expireOverdueDocuments();

        // 1. Agent 化：不再预检索注入上下文，由模型自主决定是否调用 searchKnowledge 工具检索；
        //    工具检索结果经 ToolContext 回调收集（模型可能多轮调用，各轮结果累积合并、编号全局唯一）
        AtomicReference<KnowledgeSearchService.SearchResult> toolSearchRef = new AtomicReference<>();
        String systemPrompt = buildAgentSystemPrompt();

        // 2. LLM 生成回答（同步，带 Sentinel 熔断降级 + 多轮记忆 + 工具调用）
        //    同步链路执行轨迹在回答生成后才落库（此时尚无 Agent 任务 ID），taskId 传 null
        String answer = callLlm(systemPrompt, question, sessionId, knowledgeBaseId, null, toolSearchRef);

        // AI 服务不可用（DeepSeek 调用异常/熔断）时降级：不展示引用来源
        if (AI_SERVICE_UNAVAILABLE.equals(answer)) {
            log.warn("AI 服务不可用，知识问答降级处理：knowledgeBaseId={}, question={}", knowledgeBaseId, question);
            // 可观测性：降级也落库为失败任务（无工具步骤、无引用来源）
            try {
                String promptText = (systemPrompt == null ? "" : systemPrompt) + "\n\n[用户问题]\n" + question;
                Long degradedTaskId = agentTaskService.startTask(UserContext.getUserId(), sessionId, knowledgeBaseId,
                        question, promptText, chatModelName);
                if (degradedTaskId != null) {
                    agentTaskService.finishTask(degradedTaskId, answer, null, false,
                            "AI 服务不可用（DeepSeek 调用异常或熔断降级）", null, null, null);
                }
            } catch (Exception e) {
                log.warn("Agent 降级问答落库失败（不影响返回）：{}", e.getMessage());
            }
            return new ChatResult(answer, List.of());
        }

        // 兜底清理：LLM 偶尔仍会输出 Markdown 加粗/行内代码符号，统一移除，保持纯文本展示
        if (answer != null) {
            answer = answer.replace("**", "").replace("`", "");
        }

        // 3. 汇总工具检索结果：模型调用了 searchKnowledge 时取工具回调返回的检索上下文；
        //    工具空结果 / 模型未调用工具时 rc 为 null（回答无 [来源N]，不展示引用来源）
        RetrievalContext rc = null;
        KnowledgeSearchService.SearchResult toolResult = toolSearchRef.get();
        if (toolResult != null) {
            rc = new RetrievalContext(toolResult.context(), toolResult.sources(), toolResult.fullContents());
        }

        // 引用对齐校验（容错）：模型偶尔会把 [来源N] 编号标错（引用了 A 片段却标注 B 的编号）。
        // 因提示词要求"逐字引用原文"，取每个 [来源N] 后一小段文本与各来源完整内容做包含匹配，
        // 仅当唯一命中且与原编号不符时纠正；无法判定则保持原编号（保守，不误伤）
        if (rc != null) {
            answer = alignCitations(answer, rc);
        }

        // 引用来源仅随实际引用展示：回答中无 [来源N]（澄清性提问/告知无信息/AI 降级）时不返回来源，
        // 避免把与回答无关的检索候选展示给用户；回答有引用时只保留被实际引用的来源并重排为连续编号
        // （如回答引用 1、3、7 → 回答与来源列表同步重写为 1、2、3，序号不跳号、点击定位一一对应）
        List<SourceInfo> sources = List.of();
        if (rc != null && hasSourceRefs(answer)) {
            CitedSources cited = renumberCitedSources(answer, rc.sources());
            answer = cited.answer();
            sources = cited.sources();
        }

        // Agent 可观测性：同步问答同样落库执行轨迹（无工具步骤），记录 prompt/model/引用来源；
        // 同步链路不采集 token 用量（流式链路已记录），token 列为 null
        try {
            String promptText = (systemPrompt == null ? "" : systemPrompt) + "\n\n[用户问题]\n" + question;
            Long syncTaskId = agentTaskService.startTask(UserContext.getUserId(), sessionId, knowledgeBaseId,
                    question, promptText, chatModelName);
            if (syncTaskId != null) {
                String finalAnswer = answer;
                agentTaskService.finishTask(syncTaskId, finalAnswer,
                        sourcesJson(sources), true, null,
                        null, null, null);
            }
        } catch (Exception e) {
            log.warn("Agent 同步问答落库失败（不影响返回）：{}", e.getMessage());
        }

        return new ChatResult(answer, sources);
    }

    /** [来源N] 引用标记：回答中不存在该标记（如澄清性提问、告知无信息、AI 降级）时不展示引用来源 */
    private static final Pattern SOURCE_REF_PATTERN = Pattern.compile("\\[来源\\s*\\d+\\]");

    /** 判断回答是否实际引用了 [来源N]：无引用的回答（澄清/拒绝/降级）不随回答展示引用来源 */
    public static boolean hasSourceRefs(String answer) {
        return answer != null && SOURCE_REF_PATTERN.matcher(answer).find();
    }

    /**
     * 引用来源过滤 + 序号重排：按回答中实际引用的 [来源N]（首次出现顺序）保留候选来源，
     * 重新编号为 1..N，回答文本中的编号同步重写为新编号。
     * <p>例如回答引用 [来源1][来源3][来源7] → 过滤后仅保留 3 条，重写为 [来源1][来源2][来源3]，
     * 前端"引用来源"列表与实际引用一一对应且序号连续。
     * <p>注意：须在 {@link #alignCitations} 对齐之后调用——对齐可能纠正编号，
     * 重排基于纠正后的编号，保证前端按 refIndex 定位到的正是回答引用的内容。
     *
     * @return 重写编号后的回答 + 按新编号重排的来源列表
     */
    private CitedSources renumberCitedSources(String answer, List<SourceInfo> candidates) {
        if (answer == null || candidates == null || candidates.isEmpty()) {
            return new CitedSources(answer, List.of());
        }
        // 回答中实际引用的编号（LinkedHashMap 保持首次出现顺序）：旧编号 -> 新连续编号
        Map<Integer, Integer> renumber = new LinkedHashMap<>();
        Matcher m = Pattern.compile("\\[来源\\s*(\\d+)\\]").matcher(answer);
        while (m.find()) {
            int oldRef = Integer.parseInt(m.group(1));
            renumber.computeIfAbsent(oldRef, k -> renumber.size() + 1);
        }
        if (renumber.isEmpty()) {
            return new CitedSources(answer, List.of());
        }
        // 回答文本中的 [来源N] 同步重写为连续新编号
        String rewritten = Pattern.compile("\\[来源\\s*(\\d+)\\]")
                .matcher(answer)
                .replaceAll(mr -> "[来源" + renumber.get(Integer.parseInt(mr.group(1))) + "]");
        // 保留被引用来源并重排 refIndex，按新编号升序排列：
        // 排序后来源列表序号为 1、2、3…（不跳号），且与回答中 [来源N] 的引用顺序一一对应
        // （回答先引用谁，来源列表第一条就是谁；点击 [来源N] 定位到的正是回答引用的内容）
        List<SourceInfo> cited = candidates.stream()
                .filter(s -> s.refIndex() != null && renumber.containsKey(s.refIndex()))
                .map(s -> new SourceInfo(s.documentId(), s.documentName(), s.pageNo(), s.snippet(),
                        renumber.get(s.refIndex())))
                .sorted(Comparator.comparingInt(SourceInfo::refIndex))
                .toList();
        return new CitedSources(rewritten, cited);
    }

    /** 引用过滤 + 重排结果：answer 为编号重写后的回答，sources 为按新编号重排的来源列表 */
    private record CitedSources(String answer, List<SourceInfo> sources) {}

    /**
     * 引用对齐校验（容错）：模型偶尔会把 [来源N] 编号标错（引用了 A 片段却标注了 B 片段的编号）。
     * 因系统提示要求"逐字引用原文"，取每个 [来源N] 之后的一小段文本（遇下一个 [ 停止，最多 80 字符），
     * 与各来源完整内容做包含匹配：仅当唯一命中且与原编号不符时纠正为命中编号；
     * 命中 0 个或多个（无法判定）则保持原编号，避免误伤正常引用。
     */
    private String alignCitations(String answer, RetrievalContext rc) {
        if (answer == null || answer.isBlank() || rc.fullContents().isEmpty()) {
            return answer;
        }
        List<SourceInfo> sources = rc.sources();
        // 归一化（去空白）后做包含匹配：模型逐字引用原文时可能省略缩进/换行等
        List<String> normContents = rc.fullContents().stream()
                .map(c -> c == null ? "" : c.replaceAll("\\s+", ""))
                .toList();

        Map<Integer, Integer> correction = new HashMap<>(); // 原编号 -> 正确编号
        Matcher m = Pattern.compile("\\[来源(\\d+)\\]([^\\[]{0,80})").matcher(answer);
        while (m.find()) {
            int citedRef = Integer.parseInt(m.group(1));
            // 越界编号（模型幻觉标注的 [来源N] 超过来源总数）不跳过：
            // 若引用文本能唯一命中某来源，同样纠正为真实编号，保证前端可定位
            if (citedRef < 1) {
                continue;
            }
            String citedText = m.group(2).replaceAll("\\s+", "");
            // 引用文本过短（如紧跟句末）无法可靠判定，跳过
            if (citedText.length() < 8) {
                continue;
            }
            List<Integer> hits = new ArrayList<>();
            for (int i = 0; i < normContents.size(); i++) {
                if (!normContents.get(i).isEmpty() && normContents.get(i).contains(citedText)) {
                    hits.add(i + 1); // 1-based 来源编号
                }
            }
            if (hits.size() == 1 && hits.get(0) != citedRef) {
                correction.put(citedRef, hits.get(0));
            }
        }
        if (correction.isEmpty()) {
            return answer;
        }

        Matcher rm = Pattern.compile("\\[来源(\\d+)\\]").matcher(answer);
        StringBuilder sb = new StringBuilder();
        while (rm.find()) {
            int oldRef = Integer.parseInt(rm.group(1));
            Integer mapped = correction.get(oldRef);
            if (mapped != null) {
                rm.appendReplacement(sb, Matcher.quoteReplacement("[来源" + mapped + "]"));
            } else {
                rm.appendReplacement(sb, Matcher.quoteReplacement(rm.group()));
            }
        }
        rm.appendTail(sb);
        return sb.toString();
    }

    /**
     * Agent 化系统提示：不预检索注入上下文，强约束模型——知识类问题必须先调用
     * searchKnowledge 工具检索知识库内容，再严格基于工具返回的内容回答；
     * 工具返回片段自带 [来源N] 编号，回答按相同编号标注（复用 {@link #alignCitations} 对齐）。
     * 清单/大纲类问题引导使用 listDocuments/searchDocuments/documentOutline 工具。
     */
    private String buildAgentSystemPrompt() {
        return "你是一个基于知识库的问答助手。当用户的问题需要基于知识库正文内容回答时，"
                + "你必须先调用 searchKnowledge 工具在知识库中检索相关内容，再严格基于检索到的内容回答用户问题。\n"
                + "工具使用规则：\n"
                + "- 需要基于知识库正文内容回答的问题（如“某功能怎么用”“某参数含义”“文档里怎么说的”）"
                + "→ 必须先调用 searchKnowledge 工具检索，将用户问题（或其拆分的子问题）传入，"
                + "若问题中点名了具体文档请保留文档名。\n"
                + "- 一个问题包含多个独立子问题时（如“A 怎么申请？B 需要谁审批？”），"
                + "应拆分为针对性查询词分别调用 searchKnowledge（一次检索一个方面），"
                + "不要把所有子问题揉进同一次检索。\n"
                + "- 若首次检索结果已能覆盖问题所需信息，直接据此回答，不要重复调用工具；"
                + "若确实需要再次检索，必须使用更精确、更聚焦的查询词，严禁原样重复已检索过的问题。\n"
                + "- 用户询问知识库中有哪些文档/查找某份文档 → 调用 listDocuments 或 searchDocuments 工具。\n"
                + "- 用户询问某文档的结构/章节大纲 → 调用 documentOutline 工具。\n"
                + "- 用户问题涉及具体数值的算术计算（如年假余额=总天数-已休天数、金额运算、"
                + "百分比换算、差值/合计等）→ 调用 calculate 工具，把数值和运算翻译成数学表达式（如 5-2、8000*(1-10%)）传入。\n"
                + "- 与知识库内容无关的问题（闲聊、常识等）→ 直接正常回答，无需调用工具。\n"
                + "回答方式：当检索到的知识库内容能直接回答用户问题时，请逐字引用原文片段作答，"
                + "不要用自己的话总结、概括、润色或扩展原文内容；多个相关片段可按原文顺序拼接，"
                + "仅用最少的衔接词连接；仅当用户明确要求“总结”“概括”等时，才可以在引用原文之后附加简要总结。\n"
                + "完整性规则：当问题需要文档级全量或结构信息（如文档一共有几部分/几章、全部章节标题、"
                + "完整目录大纲等），仅凭 searchKnowledge 检索到的少量片段不足以回答时，"
                + "必须调用可用的工具（如查询文档大纲的工具）获取完整信息，"
                + "严禁仅根据不完整的检索片段猜测或只回答片段中出现的部分。\n"
                + "引用规范：searchKnowledge 返回的每个片段自带 [来源N] 编号，"
                + "请在引用对应内容的位置标注与工具返回一致的编号，例如[来源1]、[来源2]。"
                + "每个[来源N]编号必须与实际引用的片段严格对应——你引用了哪个片段的内容，"
                + "就必须标注哪个片段的编号，严禁引用了 A 片段却标注 B 片段（或其他未引用片段）的编号；"
                + "若无法确定对应片段，宁可不标注编号，也不可标错。\n"
                + "请始终使用与用户提问相同的语言作答（用户用中文提问时必须全程使用中文回答，" 
                + "不得混入英文句子、英文措辞或中英夹杂；仅文档名、术语等原文中的专有名词可保留原文）。\n"
                + "回答请使用纯文本，禁止使用任何 Markdown 格式（如 **加粗**、*斜体*、# 标题、- 列表、> 引用等）。\n"
                + "如果调用工具后仍没有相关知识库内容，请如实告知用户“知识库中暂无相关信息”。\n"
                + "如果用户的问题中存在指代不清（如“这些内容”“上面提到的”“刚才说的”等），"
                + "且当前对话上下文无法明确其具体所指，请直接向用户确认所指内容，"
                + "而不要猜测作答。";
    }

    /**
     * 在指定知识库中进行流式问答（SSE）：检索逻辑与 {@link #chat} 完全一致，
     * 回答以 token 增量流式返回；AI 服务异常时流中输出降级提示，不会中断连接。
     *
     * @return token 增量流 + 完整引用来源（编号不重排，见 {@link ChatStreamResult}）
     */
    public ChatStreamResult chatStream(String question, Long knowledgeBaseId, String sessionId) {
        // 服务层权限守卫（纵深防御）：问答/检索需要 VIEWER 及以上
        kbAuthorizationService.assertRole(knowledgeBaseId, KbRole.VIEWER);
        // 会话联动：补建/刷新会话元数据（标题、知识库、时间），旧 sessionId 平滑接入会话列表
        chatSessionService.touchOnChat(UserContext.getUserId(), sessionId, knowledgeBaseId, question);

        // 0. 懒标记过期文档（TTL 到期的 SUCCESS/DEPRECATED 置为 EXPIRED），无需定时任务
        expireOverdueDocuments();

        // 1. Agent 化：不预检索注入上下文，由模型自主决定是否调用 searchKnowledge 工具；
        //    工具检索结果经 ToolContext 回调收集（模型可能多轮调用，各轮结果累积合并、编号全局唯一）
        AtomicReference<KnowledgeSearchService.SearchResult> toolSearchRef = new AtomicReference<>();
        String systemPrompt = buildAgentSystemPrompt();

        // 2.1 执行轨迹落库（agent_task / agent_task_step）：一次提问 = 一条任务，
        //     记录 LLM 实际输入 prompt（系统提示+问题）与模型名，支撑可观测性审计；
        //     工具调用每步落一条步骤。落库失败不阻塞问答，仅告警
        String promptText = (systemPrompt == null ? "" : systemPrompt) + "\n\n[用户问题]\n" + question;
        final Long taskId = startAgentTask(UserContext.getUserId(), sessionId, knowledgeBaseId,
                question, promptText, chatModelName);

        // 2.2 工具调用事件 Sink：RagRetrievalService 工具回调线程写入（running/done），Controller 合并进 SSE 展示。
        //    replay 缓存支持多订阅者：本处订阅落库 + Controller 订阅 SSE，两者都能收到全部事件
        Sinks.Many<RagRetrievalService.ToolEvent> toolSink = Sinks.many().replay().limit(1024);
        if (taskId != null) {
            Long task = taskId;
            toolSink.asFlux().subscribe(evt -> {
                try {
                    agentTaskService.recordStep(task, evt);
                } catch (Exception e) {
                    log.warn("Agent 步骤落库失败：{}", e.getMessage());
                }
            });
        }
        // token 用量：流式响应的每个 chunk 都会携带 usage（OpenAI 兼容），取最后一个
        AtomicReference<Usage> usageRef = new AtomicReference<>();

        // 2.3 模型自主调用工具（无预检索上下文），cache() 共享同一数据源：
        //    Controller 先订阅 delta 逐 token 输出，完成后从缓存收集完整回答做引用对齐
        Flux<String> cached = streamLlm(systemPrompt, question, sessionId, knowledgeBaseId,
                taskId, toolSink, usageRef, toolSearchRef).cache();

        // 2.4 生成完毕后：引用对齐 + 最终来源（工具检索结果；模型未调用工具则无来源）
        Mono<ChatStreamResult.AnswerContext> answerMono = cached.collectList()
                .map(list -> {
                    String full = String.join("", list);
                    if (full.isBlank() || AI_SERVICE_UNAVAILABLE.equals(full)) {
                        return new ChatStreamResult.AnswerContext(full, List.of()); // 降级/空回答
                    }
                    RetrievalContext rc = finalRetrievalContext(toolSearchRef);
                    if (rc == null) {
                        return new ChatStreamResult.AnswerContext(full, List.of());
                    }
                    // 先对齐编号，再按回答实际引用的 [来源N] 过滤候选并重排为连续编号：
                    // 只保留被引用的来源（回答引用 1、3、7 → 重写为 1、2、3，序号不跳号）
                    String aligned = alignCitations(full, rc);
                    CitedSources cited = renumberCitedSources(aligned, rc.sources());
                    return new ChatStreamResult.AnswerContext(cited.answer(), cited.sources());
                })
                .doOnSuccess(ctx -> {
                    if (taskId != null) {
                        try {
                            Usage usage = usageRef.get();
                            agentTaskService.finishTask(taskId, ctx.answer(), sourcesJson(ctx.sources()), true, null,
                                    usage == null ? null : usage.getPromptTokens(),
                                    usage == null ? null : usage.getCompletionTokens(),
                                    usage == null ? null : usage.getTotalTokens());
                        } catch (Exception e) {
                            log.warn("Agent 任务完成落库失败：{}", e.getMessage());
                        }
                    }
                })
                .doOnError(ex -> {
                    if (taskId != null) {
                        try {
                            agentTaskService.finishTask(taskId, null, null, false, String.valueOf(ex.getMessage()),
                                    null, null, null);
                        } catch (Exception e) {
                            log.warn("Agent 任务失败落库异常：{}", e.getMessage());
                        }
                    }
                });
        return new ChatStreamResult(cached, answerMono, toolSink);
    }

    /**
     * 汇总最终检索结果：取 searchKnowledge 工具回调结果（模型调用了工具）；
     * 模型未调用工具时返回 null（回答无来源）。
     */
    private RetrievalContext finalRetrievalContext(
            AtomicReference<KnowledgeSearchService.SearchResult> toolSearchRef) {
        KnowledgeSearchService.SearchResult sr = toolSearchRef.get();
        if (sr != null) {
            return new RetrievalContext(sr.context(), sr.sources(), sr.fullContents());
        }
        return null;
    }

    /**
     * 创建 Agent 任务记录（执行轨迹落库）。落库失败不阻塞问答，仅告警并返回 null
     * （后续步骤/完成更新全部跳过，SSE 展示不受影响）。
     */
    private Long startAgentTask(Long userId, String sessionId, Long kbId, String question,
                                String prompt, String model) {
        try {
            return agentTaskService.startTask(userId, sessionId, kbId, question, prompt, model);
        } catch (Exception e) {
            log.warn("Agent 任务落库失败（问答继续）：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 引用来源序列化为 JSON（agent_task.sources 落库）；失败返回 null，不影响任务状态。
     */
    private String sourcesJson(List<SourceInfo> sources) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (Exception e) {
            log.warn("引用来源序列化失败（忽略）：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 流式调用 LLM（DeepSeek）：逐 token 输出增量文本；
     * 调用异常/网络中断时降级为 {@link #AI_SERVICE_UNAVAILABLE}，避免连接异常中断前端。
     */
    private Flux<String> streamLlm(String systemPrompt, String question, String sessionId, Long knowledgeBaseId,
                                   Long taskId, Sinks.Many<RagRetrievalService.ToolEvent> toolSink,
                                   AtomicReference<Usage> usageRef,
                                   AtomicReference<KnowledgeSearchService.SearchResult> searchResultRef) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .user(question)
                .toolContext(toolContext(knowledgeBaseId, conversationId(sessionId), taskId, toolSink, searchResultRef))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId(sessionId)));
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            spec = spec.system(systemPrompt);
        }
        return spec.stream()
                .chatResponse()
                // token 用量：OpenAI 兼容流式响应的每个 chunk 均携带 usage，取最后一个（工具多轮调用取最终一轮）
                .doOnNext(resp -> {
                    if (resp != null && resp.getMetadata() != null && resp.getMetadata().getUsage() != null) {
                        usageRef.set(resp.getMetadata().getUsage());
                    }
                })
                // 提取增量文本；工具调用中间帧（stopReason=TOOL_CALL）无输出，返回空串
                .map(resp -> {
                    if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) {
                        return "";
                    }
                    String text = resp.getResult().getOutput().getText();
                    // 兜底清理：LLM 偶尔仍会输出 Markdown 加粗/行内代码符号，统一移除，保持纯文本展示
                    return text == null ? "" : text.replace("**", "").replace("`", "");
                })
                .doOnError(t -> log.error("AI 流式调用（DeepSeek）失败，问答降级处理：{}", t.getMessage()))
                .onErrorResume(t -> Flux.just(AI_SERVICE_UNAVAILABLE));
    }

    /**
     * 调用 LLM（DeepSeek）生成回答，带 Sentinel 熔断降级：
     * 调用异常/超时/熔断（资源 {@code ai-chat}）时返回降级提示，而不是向上抛错导致接口 500。
     *
     * @param systemPrompt    系统提示（无检索上下文时传 null）
     * @param question        用户问题
     * @param sessionId       会话 ID（多轮记忆）
     * @param taskId          Agent 任务 ID（同步链路 LLM 调用时任务尚未创建，传 null）
     * @param searchResultRef searchKnowledge 工具检索结果收集器（同步链路可传，供模型调用工具时回调）
     * @return LLM 回答；AI 服务不可用时返回 {@link #AI_SERVICE_UNAVAILABLE}
     */
    private String callLlm(String systemPrompt, String question, String sessionId, Long knowledgeBaseId,
                           Long taskId, AtomicReference<KnowledgeSearchService.SearchResult> searchResultRef) {
        return circuitBreakerFactory.create(AiConfig.AI_CHAT_RESOURCE).run(
                () -> {
                    ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                            .user(question)
                            .toolContext(toolContext(knowledgeBaseId, conversationId(sessionId), taskId, null, searchResultRef)) // 同步问答不展示工具调用过程
                            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId(sessionId)));
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
     * 会话 ID 归一化：拼入当前用户 ID，记忆 key 形如 {@code rag:chat:memory:{userId}:{sessionId}}，
     * 实现会话按用户隔离（不同用户即使 sessionId 相同也不会串号）。
     * 注意：必须在请求线程内调用（UserContext 为 ThreadLocal），
     * 拼装结果经 advisor param 传递，后续读写不依赖线程上下文。
     */
    private String conversationId(String sessionId) {
        Long userId = UserContext.getUserId();
        String sid = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
        return (userId == null ? "anon" : userId) + ":" + sid;
    }

    /**
     * 清除指定会话的多轮对话记忆（Redis key = rag:chat:memory:{userId}:{sessionId}）。
     * 前端「清空对话」时调用：先删除旧会话的 Redis 历史，再更换新的会话 ID，
     * 避免旧数据残留至 7 天 TTL 过期。
     */
    public void clearMemory(String sessionId) {
        chatMemory.clear(conversationId(sessionId));
    }

    /**
     * 工具调用上下文：把当前请求的知识库 ID、用户 ID、会话记忆 ID、Agent 任务 ID 放入 ToolContext。
     * 必须在请求线程内调用（UserContext 为 ThreadLocal）；工具回调线程不在此上下文内，
     * Agent 工具（RagRetrievalService）从 ToolContext 而非 UserContext 读取身份信息，避免线程切换导致权限校验失效。
     *
     * @param conversationId 会话记忆 ID（userId:sessionId，供工具感知多轮上下文）
     * @param taskId         Agent 任务 ID（供工具关联执行轨迹；同步链路 LLM 调用时任务尚未创建，传 null）
     */
    private Map<String, Object> toolContext(Long knowledgeBaseId, String conversationId, Long taskId,
                                            Sinks.Many<RagRetrievalService.ToolEvent> toolSink,
                                            AtomicReference<KnowledgeSearchService.SearchResult> searchResultRef) {
        Map<String, Object> ctx = new HashMap<>(6);
        ctx.put(RagRetrievalService.KB_ID_KEY, knowledgeBaseId);
        ctx.put(RagRetrievalService.USER_ID_KEY, UserContext.getUserId());
        if (conversationId != null) {
            ctx.put(RagRetrievalService.CONVERSATION_ID_KEY, conversationId);
        }
        if (taskId != null) {
            ctx.put(RagRetrievalService.TASK_ID_KEY, taskId);
        }
        if (toolSink != null) {
            ctx.put(RagRetrievalService.TOOL_EVENT_SINK_KEY, toolSink);
        }
        if (searchResultRef != null) {
            ctx.put(RagRetrievalService.SEARCH_RESULT_HOLDER_KEY, searchResultRef);
        }
        return ctx;
    }

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
