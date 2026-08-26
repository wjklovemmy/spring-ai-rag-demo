package com.example.springairagdemo.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.springairagdemo.entity.DocumentStatus;
import com.example.springairagdemo.entity.KnowledgeBaseEntity;
import com.example.springairagdemo.entity.KnowledgeDocumentEntity;
import com.example.springairagdemo.entity.KnowledgeEmbeddingTaskEntity;
import com.example.springairagdemo.entity.KnowledgeEmbeddingTaskStatus;
import com.example.springairagdemo.security.ForbiddenException;
import com.example.springairagdemo.security.KbRole;
import com.example.springairagdemo.security.RequireKbRole;
import com.example.springairagdemo.service.FileStorageService;
import com.example.springairagdemo.service.KbAuthorizationService;
import com.example.springairagdemo.service.KnowledgeBaseService;
import com.example.springairagdemo.service.KnowledgeDocumentEntityService;
import com.example.springairagdemo.service.KnowledgeDocumentService;
import com.example.springairagdemo.service.KnowledgeEmbeddingTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;

/**
 * 知识文档 REST API
 * <p>
 * 权限说明：
 * <ul>
 *   <li>upload / chat：注解式校验（@RequireKbRole）</li>
 *   <li>download / delete：文档级，方法内先取文档所属知识库再校验（对象级防越权）</li>
 *   <li>list / knowledge-bases：强制按「当前用户可见知识库集合」过滤（数据源头防泄露）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/knowledge-document")
@Slf4j
@RequiredArgsConstructor
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService knowledgeDocumentService;
    private final KnowledgeDocumentEntityService knowledgeDocumentEntityService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final FileStorageService fileStorageService;
    private final KbAuthorizationService kbAuthorizationService;
    private final KnowledgeEmbeddingTaskService knowledgeEmbeddingTaskService;

    /**
     * 上传文档文件并建立知识库索引（需要 EDITOR 及以上）
     *
     * @param file            MultipartFile 文档文件（form-data 方式上传）
     * @param knowledgeBaseId 知识库 ID
     */
    @PostMapping("/upload")
    @RequireKbRole(value = KbRole.EDITOR, kbParam = "knowledgeBaseId")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                                      @RequestParam("knowledgeBaseId") Long knowledgeBaseId) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "上传文件为空"));
        }

        // 校验文件格式（仅支持 PDF）
        String originalFilename = file.getOriginalFilename();
        String extension = getFileExtension(originalFilename);
        if (!"pdf".equals(extension)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "不支持的文件格式，仅支持: pdf"
            ));
        }

        try {
            KnowledgeDocumentService.TaskSubmitResult result =
                    knowledgeDocumentService.submitIngest(file, knowledgeBaseId);
            log.info("文件 {} v{} 上传提交成功（知识库: {}），任务号: {}",
                    originalFilename, result.version(), knowledgeBaseId, result.taskNo());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "文件上传成功，正在异步生成向量索引（任务号 " + result.taskNo() + "）",
                    "fileName", originalFilename,
                    "knowledgeBaseId", knowledgeBaseId,
                    "taskId", result.taskId(),
                    "taskNo", result.taskNo(),
                    "documentId", result.documentId(),
                    "version", result.version(),
                    "isUpdate", result.version() > 1
            ));
        } catch (IOException e) {
            log.error("文件处理失败: {} (知识库: {})", originalFilename, knowledgeBaseId, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "文件处理失败: " + e.getMessage()
            ));
        } catch (RuntimeException e) {
            log.error("文件处理失败: {} (知识库: {})", originalFilename, knowledgeBaseId, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return null;
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    /**
     * 查询 Embedding 任务状态（前端上传后轮询进度）
     */
    @GetMapping("/task/{taskNo}")
    public ResponseEntity<Map<String, Object>> taskStatus(@PathVariable String taskNo) {
        KnowledgeEmbeddingTaskEntity task = knowledgeEmbeddingTaskService.lambdaQuery()
                .eq(KnowledgeEmbeddingTaskEntity::getTaskNo, taskNo)
                .one();
        if (task == null) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", "任务不存在"));
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("taskId", task.getId());
        item.put("taskNo", task.getTaskNo());
        item.put("documentId", task.getDocumentId());
        item.put("status", task.getStatus() == null ? null : task.getStatus().getCode());
        item.put("statusText", statusText(task.getStatus()));
        item.put("totalChunk", task.getTotalChunk());
        item.put("successChunk", task.getSuccessChunk());
        item.put("failChunk", task.getFailChunk());
        // 分阶段进度（0-100）：PDF解析 / 文本切片 / Chunk入库 / Embedding / Milvus
        item.put("parseProgress", nvl(task.getParseProgress()));
        item.put("splitProgress", nvl(task.getSplitProgress()));
        item.put("chunkProgress", nvl(task.getChunkProgress()));
        item.put("embedProgress", nvl(task.getEmbedProgress()));
        item.put("milvusProgress", nvl(task.getMilvusProgress()));
        item.put("retryCount", task.getRetryCount());
        item.put("errorMessage", task.getErrorMessage());
        item.put("costTime", task.getCostTime());
        item.put("startTime", task.getStartTime());
        item.put("finishTime", task.getFinishTime());
        item.put("createTime", task.getCreateTime());
        return ResponseEntity.ok(Map.of("success", true, "data", item));
    }

    /**
     * 查询 Embedding 任务列表（强制按当前用户可见知识库过滤）
     *
     * @param knowledgeBaseId 知识库 ID（可选，指定时必须是当前用户可见）
     * @param status          任务状态（可选，0待处理 1处理中 2成功 3失败）
     * @param keyword         任务号 / 文档名模糊搜索（可选）
     */
    @GetMapping("/tasks")
    public ResponseEntity<Map<String, Object>> tasks(
            @RequestParam(required = false) Long knowledgeBaseId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword) {

        List<Long> visible = kbAuthorizationService.visibleKbIds(); // null = 全部可见（ADMIN）

        // 可见性前置拦截：无任何可见知识库 / 指定知识库不可见
        if (visible != null && (visible.isEmpty()
                || (knowledgeBaseId != null && !visible.contains(knowledgeBaseId)))) {
            return ResponseEntity.ok(Map.of("success", true, "total", 0, "data", List.of()));
        }

        // 1. 查询任务（按创建时间倒序，最新 100 条）
        LambdaQueryWrapper<KnowledgeEmbeddingTaskEntity> wrapper = new LambdaQueryWrapper<>();
        KnowledgeEmbeddingTaskStatus statusEnum = KnowledgeEmbeddingTaskStatus.fromCode(status);
        wrapper.eq(statusEnum != null, KnowledgeEmbeddingTaskEntity::getStatus, statusEnum);
        if (keyword != null && !keyword.isBlank()) {
            // 任务号模糊 OR 文档名匹配（文档名匹配需先查文档表得到 documentId 集合）
            List<Long> matchedDocIds = knowledgeDocumentEntityService.lambdaQuery()
                    .like(KnowledgeDocumentEntity::getFileName, keyword)
                    .list()
                    .stream()
                    .map(KnowledgeDocumentEntity::getId)
                    .toList();
            wrapper.and(w -> {
                w.like(KnowledgeEmbeddingTaskEntity::getTaskNo, keyword);
                if (!matchedDocIds.isEmpty()) {
                    w.or().in(KnowledgeEmbeddingTaskEntity::getDocumentId, matchedDocIds);
                }
            });
        }
        wrapper.orderByDesc(KnowledgeEmbeddingTaskEntity::getCreateTime).last("LIMIT 100");
        List<KnowledgeEmbeddingTaskEntity> tasks = knowledgeEmbeddingTaskService.list(wrapper);
        if (tasks.isEmpty()) {
            return ResponseEntity.ok(Map.of("success", true, "total", 0, "data", List.of()));
        }

        // 2. 批量关联文档与知识库（避免 N+1 查询）
        Set<Long> docIds = tasks.stream()
                .map(KnowledgeEmbeddingTaskEntity::getDocumentId)
                .collect(Collectors.toSet());
        Map<Long, KnowledgeDocumentEntity> docMap = knowledgeDocumentEntityService.listByIds(docIds).stream()
                .collect(Collectors.toMap(KnowledgeDocumentEntity::getId, d -> d));
        Set<Long> kbIds = docMap.values().stream()
                .map(KnowledgeDocumentEntity::getKnowledgeId)
                .collect(Collectors.toSet());
        Map<Long, String> kbNameMap = knowledgeBaseService.listByIds(kbIds).stream()
                .collect(Collectors.toMap(KnowledgeBaseEntity::getId, KnowledgeBaseEntity::getName));

        // 3. 组装结果 + 按可见知识库过滤（数据源头防泄露）
        List<Map<String, Object>> result = new ArrayList<>();
        for (KnowledgeEmbeddingTaskEntity task : tasks) {
            KnowledgeDocumentEntity doc = docMap.get(task.getDocumentId());
            if (doc == null) {
                continue; // 文档已被删除，任务数据孤立
            }
            if (knowledgeBaseId != null && !knowledgeBaseId.equals(doc.getKnowledgeId())) {
                continue;
            }
            if (visible != null && !visible.contains(doc.getKnowledgeId())) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("taskId", task.getId());
            item.put("taskNo", task.getTaskNo());
            item.put("documentId", doc.getId());
            item.put("documentName", doc.getFileName());
            item.put("knowledgeBaseId", doc.getKnowledgeId());
            item.put("kbName", kbNameMap.get(doc.getKnowledgeId()));
            item.put("version", doc.getVersion());
            KnowledgeEmbeddingTaskStatus st = task.getStatus();
            item.put("status", st == null ? null : st.getCode());
            item.put("statusText", statusText(st));
            item.put("totalChunk", task.getTotalChunk());
            item.put("successChunk", task.getSuccessChunk());
            item.put("failChunk", task.getFailChunk());
            item.put("parseProgress", nvl(task.getParseProgress()));
            item.put("splitProgress", nvl(task.getSplitProgress()));
            item.put("chunkProgress", nvl(task.getChunkProgress()));
            item.put("embedProgress", nvl(task.getEmbedProgress()));
            item.put("milvusProgress", nvl(task.getMilvusProgress()));
            item.put("retryCount", task.getRetryCount());
            item.put("errorMessage", task.getErrorMessage());
            item.put("costTime", task.getCostTime());
            item.put("startTime", task.getStartTime());
            item.put("finishTime", task.getFinishTime());
            item.put("createTime", task.getCreateTime());
            result.add(item);
        }

        return ResponseEntity.ok(Map.of("success", true, "total", result.size(), "data", result));
    }

    private String statusText(KnowledgeEmbeddingTaskStatus status) {
        return status == null ? "未知" : status.getText();
    }

    /** 阶段进度字段空值兜底（存量任务未初始化时为 0） */
    private int nvl(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 基于指定知识库进行问答（需要 VIEWER 及以上），SSE 流式输出
     * <p>事件流格式（data 为 JSON 对象）：
     * <ul>
     *   <li>{@code {"type":"delta","content":"..."}} — 增量文本（逐 token）</li>
     *   <li>{@code {"type":"final","content":"..."}} — 引用对齐校验后的最终全文（覆盖显示，强制纠正编号）</li>
     *   <li>{@code {"type":"sources","sources":[...]}} — 完整引用来源（结束前发送）</li>
     *   <li>{@code {"type":"done"}} — 流结束标记</li>
     *   <li>{@code {"type":"error","message":"..."}} — 参数错误 / 降级提示</li>
     * </ul>
     *
     * @param request JSON body，包含 question 和 knowledgeBaseId 字段
     */
    /**
     * 清除指定会话的多轮对话记忆（Redis key = rag:chat:memory:{userId}:{sessionId}）。
     * 前端「清空对话」时调用：先删旧记忆再换新会话 ID，避免旧数据残留至 TTL 过期。
     * 不需要知识库权限（仅与当前登录用户自己的会话相关，身份由网关注入）。
     *
     * @param request JSON body，可选字段 sessionId（缺省清除默认会话）
     */
    @PostMapping("/chat/clear-memory")
    public ResponseEntity<Map<String, Object>> clearChatMemory(@RequestBody Map<String, Object> request) {
        String sessionId = null;
        Object sObj = request.get("sessionId");
        if (sObj != null) {
            String s = sObj.toString().trim();
            if (!s.isEmpty()) {
                sessionId = s.length() > 128 ? s.substring(0, 128) : s;
            }
        }
        knowledgeDocumentService.clearMemory(sessionId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/chat")
    @RequireKbRole(value = KbRole.VIEWER, kbParam = "knowledgeBaseId")
    public Object chat(@RequestBody Map<String, Object> request) {
        String question = (String) request.get("question");
        Object kbIdObj = request.get("knowledgeBaseId");

        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "问题不能为空"));
        }
        if (kbIdObj == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "知识库 ID 不能为空"));
        }

        Long knowledgeBaseId;
        try {
            knowledgeBaseId = kbIdObj instanceof Number
                    ? ((Number) kbIdObj).longValue()
                    : Long.parseLong(kbIdObj.toString());
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "知识库 ID 格式错误"));
        }

        // 会话 ID：多轮对话记忆的 key（不传则服务端用默认会话）；限长 128 防滥用
        String sessionId = null;
        Object sObj = request.get("sessionId");
        if (sObj != null) {
            String s = sObj.toString().trim();
            if (!s.isEmpty()) {
                sessionId = s.length() > 128 ? s.substring(0, 128) : s;
            }
        }

        // 回答方式：stream=true（默认）SSE 流式输出；stream=false 一次性返回完整 JSON
        boolean stream = true;
        Object streamObj = request.get("stream");
        if (streamObj != null) {
            stream = streamObj instanceof Boolean ? (Boolean) streamObj : Boolean.parseBoolean(streamObj.toString());
        }

        if (!stream) {
            try {
                KnowledgeDocumentService.ChatResult chatResult =
                        knowledgeDocumentService.chat(question, knowledgeBaseId, sessionId);
                List<Map<String, Object>> sources = chatResult.sources().stream().map(s -> {
                    Map<String, Object> src = new LinkedHashMap<>();
                    src.put("documentId", s.documentId());
                    src.put("documentName", s.documentName());
                    src.put("pageNo", s.pageNo());
                    src.put("snippet", s.snippet());
                    src.put("refIndex", s.refIndex());
                    return src;
                }).toList();
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "question", question,
                        "knowledgeBaseId", knowledgeBaseId,
                        "answer", chatResult.answer(),
                        "sources", sources
                ));
            } catch (Exception e) {
                log.error("知识文档问答处理失败（同步）", e);
                return ResponseEntity.internalServerError().body(Map.of(
                        "success", false,
                        "message", "问答处理失败: " + e.getMessage()
                ));
            }
        }

        // ===== 流式（SSE）=====
        KnowledgeDocumentService.ChatStreamResult chatResult =
                knowledgeDocumentService.chatStream(question, knowledgeBaseId, sessionId);

        List<Map<String, Object>> sources = chatResult.sources().stream().map(s -> {
            Map<String, Object> src = new LinkedHashMap<>();
            src.put("documentId", s.documentId());
            src.put("documentName", s.documentName());
            src.put("pageNo", s.pageNo());
            src.put("snippet", s.snippet());
            src.put("refIndex", s.refIndex());
            return src;
        }).toList();

        // SSE 事件流：先逐 token 输出增量文本，再下发引用对齐校验后的最终全文
        // （final：强制纠正 [来源N] 编号张冠李戴，前端覆盖显示），最后携带引用来源与结束标记
        Flux<ServerSentEvent<Map<String, Object>>> flux = chatResult.stream()
                .map(delta -> sseEvent(Map.<String, Object>of("type", "delta", "content", delta)))
                .concatWith(chatResult.correctedAnswer()
                        .map(a -> sseEvent(Map.<String, Object>of("type", "final", "content", a))))
                .concatWith(Flux.just(
                        sseEvent(Map.<String, Object>of("type", "sources", "sources", sources)),
                        sseEvent(Map.<String, Object>of("type", "done"))));

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(flux);
    }

    /** 构建 SSE 事件（data 为 JSON 对象，统一 Map<String,Object> 避免泛型推断冲突） */
    private ServerSentEvent<Map<String, Object>> sseEvent(Map<String, Object> data) {
        return ServerSentEvent.builder(data).build();
    }

    /**
     * 下载文档原始文件（需要 VIEWER 及以上，文档级权限校验）
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<?> download(@PathVariable Long id) {
        KnowledgeDocumentEntity doc = knowledgeDocumentEntityService.getById(id);
        if (doc == null) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", "文档不存在"));
        }
        // 对象级防越权：按文档所属知识库校验
        try {
            kbAuthorizationService.assertRole(doc.getKnowledgeId(), KbRole.VIEWER);
        } catch (ForbiddenException e) {
            return ResponseEntity.status(403).body(Map.of("success", false, "message", e.getMessage()));
        }

        String objectName = doc.getFilePath();
        if (objectName == null || objectName.isBlank()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", "文件路径未记录"));
        }

        try {
            if (!fileStorageService.exists(objectName)) {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "文件不存在"));
            }

            String fileName = doc.getFileName();
            String contentType = "application/pdf";
            if (fileName != null && fileName.contains(".")) {
                String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
                if (!"pdf".equals(ext)) contentType = "application/octet-stream";
            }

            InputStream inputStream = fileStorageService.getInputStream(objectName);
            InputStreamResource resource = new InputStreamResource(inputStream);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
                    .body(resource);

        } catch (Exception e) {
            log.error("文件下载失败: {}", objectName, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false, "message", "文件下载失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 查询文档列表，强制限定在当前用户可见的知识库范围内
     *
     * @param knowledgeBaseId 知识库 ID（可选，若指定则必须是当前用户可见）
     * @param keyword         文档名称模糊搜索（可选）
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) Long knowledgeBaseId,
            @RequestParam(required = false) String keyword) {

        List<Long> visible = kbAuthorizationService.visibleKbIds(); // null = 全部可见（ADMIN）

        LambdaQueryWrapper<KnowledgeDocumentEntity> wrapper = new LambdaQueryWrapper<>();

        if (knowledgeBaseId != null) {
            // 指定知识库：校验可见性（防止枚举他人知识库的文档）
            if (visible != null && !visible.contains(knowledgeBaseId)) {
                return ResponseEntity.ok(Map.of("success", true, "total", 0, "data", List.of()));
            }
            wrapper.eq(KnowledgeDocumentEntity::getKnowledgeId, knowledgeBaseId);
        } else if (visible != null) {
            if (visible.isEmpty()) {
                return ResponseEntity.ok(Map.of("success", true, "total", 0, "data", List.of()));
            }
            wrapper.in(KnowledgeDocumentEntity::getKnowledgeId, visible);
        }

        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(KnowledgeDocumentEntity::getFileName, keyword);
        }

        // 按创建时间倒序，取最新 100 条
        wrapper.orderByDesc(KnowledgeDocumentEntity::getCreateTime).last("LIMIT 100");

        List<KnowledgeDocumentEntity> list = knowledgeDocumentEntityService.list(wrapper);

        List<Map<String, Object>> result = list.stream().map(doc -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", doc.getId());
            item.put("knowledgeBaseId", doc.getKnowledgeId());
            item.put("fileName", doc.getFileName());
            item.put("fileType", doc.getFileType());
            item.put("fileSize", doc.getFileSize());
            item.put("chunkCount", doc.getChunkCount());
            item.put("status", doc.getStatus());
            DocumentStatus st = DocumentStatus.fromCode(doc.getStatus());
            item.put("statusText", st == null ? "未知" : st.getText());
            item.put("version", doc.getVersion());
            item.put("createTime", doc.getCreateTime());
            item.put("updateTime", doc.getUpdateTime());
            return item;
        }).toList();

        return ResponseEntity.ok(Map.of("success", true, "total", result.size(), "data", result));
    }

    /**
     * 获取当前用户可见的知识库列表（供下拉框使用）
     */
    @GetMapping("/knowledge-bases")
    public ResponseEntity<Map<String, Object>> knowledgeBases() {
        List<KnowledgeBaseEntity> list;
        List<Long> visible = kbAuthorizationService.visibleKbIds();
        if (visible == null) {
            list = knowledgeBaseService.lambdaQuery()
                    .eq(KnowledgeBaseEntity::getStatus, 1)
                    .list();
        } else if (visible.isEmpty()) {
            list = List.of();
        } else {
            list = knowledgeBaseService.lambdaQuery()
                    .eq(KnowledgeBaseEntity::getStatus, 1)
                    .in(KnowledgeBaseEntity::getId, visible)
                    .list();
        }

        List<Map<String, Object>> result = list.stream().map(kb -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", kb.getId());
            item.put("name", kb.getName());
            item.put("description", kb.getDescription());
            return item;
        }).toList();

        return ResponseEntity.ok(Map.of("success", true, "data", result));
    }

    /**
     * 删除文档及其关联数据（需要 EDITOR 及以上，文档级权限校验）
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        KnowledgeDocumentEntity doc = knowledgeDocumentEntityService.getById(id);
        if (doc == null) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", "文档不存在"));
        }
        // 对象级防越权：按文档所属知识库校验
        try {
            kbAuthorizationService.assertRole(doc.getKnowledgeId(), KbRole.EDITOR);
        } catch (ForbiddenException e) {
            return ResponseEntity.status(403).body(Map.of("success", false, "message", e.getMessage()));
        }

        try {
            knowledgeDocumentService.deleteDocument(id);
            kbAuthorizationService.audit("DELETE_DOC", doc.getKnowledgeId(), id, "删除文档 " + doc.getFileName());
            return ResponseEntity.ok(Map.of("success", true, "message", "文档已删除"));
        } catch (IllegalStateException e) {
            // 业务冲突：文档仍有待处理/处理中的 Embedding 任务，拒绝删除（409 而非 500）
            log.warn("删除文档被拒绝: id={}, 原因={}", id, e.getMessage());
            return ResponseEntity.status(409).body(Map.of(
                    "success", false, "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("删除文档失败: id={}", id, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false, "message", "删除失败: " + e.getMessage()
            ));
        }
    }
}
