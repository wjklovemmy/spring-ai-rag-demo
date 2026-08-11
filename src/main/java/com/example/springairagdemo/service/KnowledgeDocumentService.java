package com.example.springairagdemo.service;

import com.example.springairagdemo.config.RagConfigProperties;
import com.example.springairagdemo.entity.KnowledgeChunkEntity;
import com.example.springairagdemo.entity.KnowledgeDocumentEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 知识文档服务抽象类：定义文档摄取的模板流程和基于知识库的问答能力
 * <p>
 * 模板流程（ingest）：
 * 1. 保存原始文档信息到 MySQL knowledge_document
 * 2. 解析文档（子类实现）
 * 3. 切分文档（子类实现）
 * 4. chunk 文本写入 MySQL knowledge_chunk
 * 5. chunk 向量写入 Milvus（仅存向量 + 引用字段）
 */
@Slf4j
public abstract class KnowledgeDocumentService {

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
    protected RagConfigProperties ragConfig;

    @Autowired
    protected FileStorageService fileStorageService;

    @Autowired
    protected TransactionTemplate transactionTemplate;

    // ===================== 模板方法：上传文档 =====================

    /**
     * 文档摄取结果
     */
    public record IngestResult(int chunkCount, int version, boolean isUpdate) {}

    /**
     * 上传并处理文档文件。
     * <p>
     * 使用 {@link Transactional} 保证 MySQL 操作（document + chunk）原子性；
     * MinIO 文件上传在上游解析/切分/向量化全部成功后才执行，避免脏数据；
     * Milvus 向量在失败时主动删除回滚。
     */
    @Transactional(rollbackFor = Exception.class)
    public IngestResult ingest(MultipartFile file, String position, Long knowledgeBaseId) throws IOException {
        KnowledgeDocumentEntity docEntity = null;
        List<KnowledgeChunkEntity> chunkEntities = null;

        // 提前将文件读入内存，防止 Tomcat 后续清理临时文件导致 InputStream 失效
        byte[] fileBytes = file.getBytes();

        try {
            // 1. 保存新版本文档信息到 MySQL（含版本号推断，不在此处上传文件）
            docEntity = saveDocumentInfo(file, position, knowledgeBaseId);

            // 2. 解析文档
            List<Document> documents = parseDocument(file, position);
            log.info("解析完成，共 {} 个文档页面", documents.size());

            // 3. 切分文档
            List<Document> chunks = splitDocument(documents, position);
            log.info("切分完成，共 {} 个文本片段", chunks.size());

            // 4. chunk 文本写入 MySQL
            chunkEntities = saveChunks(knowledgeBaseId, docEntity.getId(), chunks);

            // 5. chunk 向量写入 Milvus
            int vectorCount = storeToVector(knowledgeBaseId, docEntity.getId(), chunkEntities, chunks);

            // 6. 上传文件到存储后端（仅在以上步骤全部成功后执行）
            persistUploadedFile(fileBytes, docEntity);

            // 7. 更新文档 chunk 数量和状态为成功
            docEntity.setChunkCount(chunks.size());
            docEntity.setStatus(3); // 成功
            docEntity.setUpdateTime(new Date());
            knowledgeDocumentEntityService.updateById(docEntity);

            // 8. 新版本入库成功后，将同名旧版本标记过期时间（平滑下线）
            deprecateOldVersions(knowledgeBaseId, docEntity);

            log.info("文档 {} v{} 处理完成，chunk 数量: {}，向量数量: {}",
                    docEntity.getFileName(), docEntity.getVersion(), chunks.size(), vectorCount);
            return new IngestResult(vectorCount, docEntity.getVersion(), docEntity.getVersion() > 1);

        } catch (Exception e) {
            log.error("文档摄取失败: {}", docEntity != null ? docEntity.getFileName() : "unknown", e);

            // 回滚 Milvus 向量（若已写入）
            if (chunkEntities != null && !chunkEntities.isEmpty() && docEntity != null) {
                try {
                    vectorStoreService.deleteVectorsByDocumentId(knowledgeBaseId, docEntity.getId());
                } catch (Exception ex) {
                    log.error("回滚 Milvus 向量失败", ex);
                }
            }

            // 标记文档失败状态（独立事务，不随主事务回滚）
            markDocumentFailed(docEntity);

            throw new RuntimeException("文档摄取失败: " + e.getMessage(), e);
        }
    }

    // ===================== 步骤 1：保存原始文档信息（含版本号推断） =====================

    /**
     * 保存文档信息到 MySQL，自动推断版本号（不在此步骤上传文件，
     * 文件仅在整个上游流程成功后由 {@link #persistUploadedFile} 上传）。
     */
    protected KnowledgeDocumentEntity saveDocumentInfo(MultipartFile file, String position, Long knowledgeBaseId) {
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase();
        }

        // 查找同一知识库下同名文档的已有版本（仅成功状态的），确定新版本号
        int newVersion = 1;
        List<KnowledgeDocumentEntity> existingDocs = knowledgeDocumentEntityService.lambdaQuery()
                .eq(KnowledgeDocumentEntity::getKnowledgeId, knowledgeBaseId)
                .eq(KnowledgeDocumentEntity::getFileName, originalFilename)
                .eq(KnowledgeDocumentEntity::getStatus, 3)   // 仅成功入库的
                .orderByDesc(KnowledgeDocumentEntity::getVersion)
                .list();
        if (!existingDocs.isEmpty()) {
            newVersion = existingDocs.get(0).getVersion() + 1;
            log.info("检测到同名文档已有 {} 个版本，新版本号: {}", existingDocs.size(), newVersion);
        }

        KnowledgeDocumentEntity entity = new KnowledgeDocumentEntity();
        entity.setKnowledgeId(knowledgeBaseId);
        entity.setPosition(position);
        entity.setFileName(originalFilename);
        entity.setFilePath(originalFilename);
        entity.setFileSize(file.getSize());
        entity.setFileType(extension);
        entity.setChunkCount(0);
        entity.setStatus(0);
        entity.setVersion(newVersion);
        entity.setIsActive(1);
        entity.setCreateTime(new Date());
        entity.setUpdateTime(new Date());
        knowledgeDocumentEntityService.save(entity);

        log.info("原始文档信息已保存: {} v{} (id={}, knowledgeBaseId={})",
                originalFilename, newVersion, entity.getId(), knowledgeBaseId);

        return entity;
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

        log.info("开始删除文档: id={}, fileName={}, knowledgeBaseId={}", documentId, doc.getFileName(), doc.getKnowledgeId());

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
     * 仅在解析、切分、向量化全部成功后调用。
     */
    private void persistUploadedFile(byte[] fileBytes, KnowledgeDocumentEntity entity) throws Exception {
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
                    latest.setStatus(4); // 失败
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
     * 新版本入库成功后，为同名旧版本设置过期时间（平滑下线）。
     * 旧版本在 TTL 天数内仍然可检索，超期后在问答中自动过滤。
     */
    private void deprecateOldVersions(Long knowledgeBaseId, KnowledgeDocumentEntity newDoc) {
        int ttlDays = ragConfig.getDocument().getVersionTtlDays();
        Date expireTime = new Date(System.currentTimeMillis() + ttlDays * 86400000L);

        List<KnowledgeDocumentEntity> oldActiveDocs = knowledgeDocumentEntityService.lambdaQuery()
                .eq(KnowledgeDocumentEntity::getKnowledgeId, knowledgeBaseId)
                .eq(KnowledgeDocumentEntity::getFileName, newDoc.getFileName())
                .eq(KnowledgeDocumentEntity::getStatus, 3)
                .eq(KnowledgeDocumentEntity::getIsActive, 1)
                .ne(KnowledgeDocumentEntity::getId, newDoc.getId())  // 排除新版本自身
                .isNull(KnowledgeDocumentEntity::getExpireTime)      // 尚未设置过期时间的
                .list();

        for (KnowledgeDocumentEntity oldDoc : oldActiveDocs) {
            oldDoc.setExpireTime(expireTime);
            oldDoc.setUpdateTime(new Date());
            knowledgeDocumentEntityService.updateById(oldDoc);
            log.info("旧版本 {} v{} 已设置过期时间: {}（{}天后下线）",
                    oldDoc.getFileName(), oldDoc.getVersion(), expireTime, ttlDays);
        }
    }

    // ===================== 步骤 2-3：解析与切分（子类实现） =====================

    protected abstract List<Document> parseDocument(MultipartFile file, String position) throws IOException;

    protected abstract List<Document> splitDocument(List<Document> documents, String position);

    // ===================== 步骤 4：chunk 写入 MySQL =====================

    /**
     * 将切分后的 chunk 批量写入 MySQL knowledge_chunk 表
     */
    protected List<KnowledgeChunkEntity> saveChunks(Long knowledgeBaseId, Long documentId, List<Document> chunks) {
        List<KnowledgeChunkEntity> entities = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            String content = chunk.getText() != null ? chunk.getText() : "";
            Integer pageNo = parsePageNo(chunk, i);

            KnowledgeChunkEntity entity = new KnowledgeChunkEntity();
            entity.setDocumentId(documentId);
            entity.setChunkIndex(i);
            entity.setContent(content);
            entity.setContentHash(sha256(content));
            entity.setTokenCount(0);
            entity.setPageNo(pageNo);
            entity.setCreateTime(new Date());
            entities.add(entity);
        }
        knowledgeChunkEntityService.saveBatch(entities);

        // 批量插入后 ID 会自动回填到实体
        log.info("{} 个 chunk 已写入 MySQL knowledge_chunk (documentId={})", entities.size(), documentId);
        return entities;
    }

    // ===================== 步骤 5：向量写入 Milvus =====================

    /**
     * 构建 ChunkVectorData 并写入 Milvus
     */
    protected int storeToVector(Long knowledgeBaseId, Long documentId,
                                 List<KnowledgeChunkEntity> chunkEntities,
                                 List<Document> chunks) {
        List<VectorStoreService.ChunkVectorData> dataList = new ArrayList<>();
        for (int i = 0; i < chunkEntities.size(); i++) {
            KnowledgeChunkEntity entity = chunkEntities.get(i);
            Document chunk = chunks.get(i);
            dataList.add(new VectorStoreService.ChunkVectorData(
                    entity.getId(),
                    documentId,
                    entity.getPageNo(),
                    entity.getChunkIndex(),
                    chunk.getText() != null ? chunk.getText() : ""));
        }
        return vectorStoreService.insertVectors(knowledgeBaseId, dataList);
    }

    // ===================== 知识问答 =====================

    /**
     * 知识问答结果：包含答案和引用来源
     */
    public record ChatResult(String answer, List<SourceInfo> sources) {}

    /**
     * 引用来源信息
     */
    public record SourceInfo(Long documentId, String documentName, Integer pageNo, String snippet) {}

    /**
     * 在指定知识库中进行问答：向量检索 → MySQL 获取 chunk 文本 → LLM 生成回答
     */
    public ChatResult chat(String question, Long knowledgeBaseId) {
        // 1. 向量检索
        List<VectorStoreService.SearchResult> searchResults =
                vectorStoreService.search(knowledgeBaseId, question, 5, 0.3);

        if (searchResults.isEmpty()) {
            String answer = chatClient.prompt().user(question).call().content();
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

        // 获取文档信息，并过滤已过期的版本（过期文档在问答中不可见）
        Date now = new Date();
        List<Long> docIds = searchResults.stream()
                .map(VectorStoreService.SearchResult::getDocumentId)
                .distinct()
                .toList();
        Map<Long, KnowledgeDocumentEntity> docMap = knowledgeDocumentEntityService.listByIds(docIds).stream()
                .collect(Collectors.toMap(KnowledgeDocumentEntity::getId, d -> d, (a, b) -> a));

        // 过滤掉已过期文档的检索结果
        Map<Long, String> docNameMap = new LinkedHashMap<>();
        List<VectorStoreService.SearchResult> validResults = new ArrayList<>();
        for (VectorStoreService.SearchResult r : searchResults) {
            KnowledgeDocumentEntity doc = docMap.get(r.getDocumentId());
            if (doc == null) continue;
            // 已过期的跳过
            if (doc.getExpireTime() != null && doc.getExpireTime().before(now)) continue;
            validResults.add(r);
            docNameMap.putIfAbsent(doc.getId(), doc.getFileName());
        }
        searchResults = validResults;

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

            sources.add(new SourceInfo(r.getDocumentId(), docName, r.getPageNo(), snippet));

            // 在上下文中标记来源
            contextBuilder.append(String.format("[来源%d] 文档：%s，第%d页%n%s%n%n",
                    refIndex++, docName, r.getPageNo() != null ? r.getPageNo() : 1, chunk.getContent()));
        }

        String context = contextBuilder.toString();
        if (context.isEmpty()) {
            String answer = chatClient.prompt().user(question).call().content();
            return new ChatResult(answer, List.of());
        }

        // 3. LLM 生成回答
        String systemPrompt = String.format(
                "你是一个基于知识库的问答助手。请严格根据以下知识库内容回答用户问题。%n"
                + "回答时，请在引用知识库内容的地方用方括号标注来源编号，例如[来源1]、[来源2]。%n"
                + "如果知识库中没有相关信息，请如实告知用户\"知识库中暂无相关信息\"。%n"
                + "%n"
                + "知识库内容：%n"
                + "%s", context);

        String answer = chatClient.prompt()
                .system(systemPrompt)
                .user(question)
                .call()
                .content();

        // 从回答中提取实际引用的来源编号，只保留精准来源
        Set<Integer> citedRefs = new HashSet<>();
        Matcher m = Pattern.compile("\\[来源(\\d+)\\]").matcher(answer);
        while (m.find()) {
            citedRefs.add(Integer.parseInt(m.group(1)));
        }
        List<SourceInfo> citedSources = new ArrayList<>();
        for (int idx : citedRefs) {
            if (idx >= 1 && idx <= sources.size()) {
                citedSources.add(sources.get(idx - 1));
            }
        }

        return new ChatResult(answer, citedSources);
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
