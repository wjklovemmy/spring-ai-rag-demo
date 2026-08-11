package com.example.springairagdemo.controller;

import com.example.springairagdemo.config.RagConfigProperties;
import com.example.springairagdemo.entity.KnowledgeBaseEntity;
import com.example.springairagdemo.entity.KnowledgeDocumentEntity;
import com.example.springairagdemo.service.FileStorageService;
import com.example.springairagdemo.service.KnowledgeBaseService;
import com.example.springairagdemo.service.KnowledgeDocumentEntityService;
import com.example.springairagdemo.service.KnowledgeDocumentService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识文档 REST API
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
    private final RagConfigProperties ragConfig;

    /**
     * 上传文档文件并建立知识库索引
     *
     * @param file            MultipartFile 文档文件（form-data 方式上传）
     * @param position        岗位类型（如 dev、finance、hr）
     * @param knowledgeBaseId 知识库 ID
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                                      @RequestParam("position") String position,
                                                      @RequestParam("knowledgeBaseId") Long knowledgeBaseId) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "上传文件为空"));
        }

        // 校验岗位类型
        if (position == null || position.isBlank() || !ragConfig.getPositionNames().contains(position)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "不支持的岗位类型，可选: " + ragConfig.getPositionNames()
            ));
        }

        // 校验文件格式（按岗位配置）
        String originalFilename = file.getOriginalFilename();
        String extension = getFileExtension(originalFilename);
        RagConfigProperties.PositionConfig posConfig = ragConfig.getPositionConfig(position);
        if (extension == null || !posConfig.getDocument().getSupportedTypes().contains(extension)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "岗位 [" + position + "] 不支持的文件格式，仅支持: " + posConfig.getDocument().getSupportedTypes()
            ));
        }

        try {
            KnowledgeDocumentService.IngestResult result =
                    knowledgeDocumentService.ingest(file, position, knowledgeBaseId);
            log.info("文件 {} v{} 上传处理成功（岗位: {}, 知识库: {}），共 {} 个文本片段入库",
                    originalFilename, result.version(), position, knowledgeBaseId, result.chunkCount());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", result.isUpdate()
                            ? "文件上传并处理成功，已更新为 v" + result.version()
                            : "文件上传并处理成功",
                    "fileName", originalFilename,
                    "position", position,
                    "knowledgeBaseId", knowledgeBaseId,
                    "chunkCount", result.chunkCount(),
                    "version", result.version(),
                    "isUpdate", result.isUpdate()
            ));
        } catch (IOException e) {
            log.error("文件处理失败: {} (岗位: {}, 知识库: {})", originalFilename, position, knowledgeBaseId, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "文件处理失败: " + e.getMessage()
            ));
        } catch (RuntimeException e) {
            log.error("文件处理失败: {} (岗位: {}, 知识库: {})", originalFilename, position, knowledgeBaseId, e);
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
     * 基于指定知识库进行问答
     *
     * @param request JSON body，包含 question 和 knowledgeBaseId 字段
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, Object> request) {
        String question = (String) request.get("question");
        Object kbIdObj = request.get("knowledgeBaseId");

        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "问题不能为空"));
        }
        if (kbIdObj == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "知识库 ID 不能为空"));
        }

        Long knowledgeBaseId = kbIdObj instanceof Number
                ? ((Number) kbIdObj).longValue()
                : Long.parseLong(kbIdObj.toString());

        try {
            KnowledgeDocumentService.ChatResult chatResult =
                    knowledgeDocumentService.chat(question, knowledgeBaseId);

            List<Map<String, Object>> sources = chatResult.sources().stream().map(s -> {
                Map<String, Object> src = new LinkedHashMap<>();
                src.put("documentId", s.documentId());
                src.put("documentName", s.documentName());
                src.put("pageNo", s.pageNo());
                src.put("snippet", s.snippet());
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
            log.error("知识文档问答处理失败", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "问答处理失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 下载文档原始文件
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<?> download(@PathVariable Long id) {
        KnowledgeDocumentEntity doc = knowledgeDocumentEntityService.getById(id);
        if (doc == null) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", "文档不存在"));
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
                            ContentDisposition.attachment().filename(fileName).build().toString())
                    .body(resource);

        } catch (Exception e) {
            log.error("文件下载失败: {}", objectName, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false, "message", "文件下载失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 查询文档列表，支持多条件筛选
     *
     * @param knowledgeBaseId 知识库 ID（可选）
     * @param position        岗位（可选）
     * @param keyword         文档名称模糊搜索（可选）
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) Long knowledgeBaseId,
            @RequestParam(required = false) String position,
            @RequestParam(required = false) String keyword) {

        LambdaQueryWrapper<KnowledgeDocumentEntity> wrapper = new LambdaQueryWrapper<>();

        if (knowledgeBaseId != null) {
            wrapper.eq(KnowledgeDocumentEntity::getKnowledgeId, knowledgeBaseId);
        }
        if (position != null && !position.isBlank()) {
            wrapper.eq(KnowledgeDocumentEntity::getPosition, position);
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
            item.put("position", doc.getPosition());
            item.put("fileName", doc.getFileName());
            item.put("fileType", doc.getFileType());
            item.put("fileSize", doc.getFileSize());
            item.put("chunkCount", doc.getChunkCount());
            item.put("status", doc.getStatus());
            item.put("version", doc.getVersion());
            item.put("createTime", doc.getCreateTime());
            item.put("updateTime", doc.getUpdateTime());
            return item;
        }).toList();

        return ResponseEntity.ok(Map.of("success", true, "total", result.size(), "data", result));
    }

    /**
     * 获取可用知识库列表（供下拉框使用）
     */
    @GetMapping("/knowledge-bases")
    public ResponseEntity<Map<String, Object>> knowledgeBases() {
        List<KnowledgeBaseEntity> list = knowledgeBaseService.lambdaQuery()
                .eq(KnowledgeBaseEntity::getStatus, 1)
                .list();

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
     * 删除文档及其关联数据（MySQL chunk + Milvus 向量 + MinIO 文件）
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        KnowledgeDocumentEntity doc = knowledgeDocumentEntityService.getById(id);
        if (doc == null) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", "文档不存在"));
        }

        try {
            knowledgeDocumentService.deleteDocument(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "文档已删除"));
        } catch (Exception e) {
            log.error("删除文档失败: id={}", id, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false, "message", "删除失败: " + e.getMessage()
            ));
        }
    }
}
