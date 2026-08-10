package com.example.springairagdemo.controller;

import com.example.springairagdemo.entity.KnowledgeBaseEntity;
import com.example.springairagdemo.service.KnowledgeBaseService;
import com.example.springairagdemo.service.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 知识库 CRUD REST API（含 Milvus collection 管理）
 */
@RestController
@RequestMapping("/api/knowledge-base")
@Slf4j
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final VectorStoreService vectorStoreService;

    /**
     * 创建知识库 — 同时在 Milvus 中创建对应 collection 和索引
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> request) {
        String name = (String) request.get("name");
        String description = (String) request.get("description");
        String createUser = (String) request.get("createUser");

        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "知识库名称不能为空"));
        }
        if (createUser == null || createUser.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "创建人不能为空"));
        }

        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setName(name);
        entity.setDescription(description != null ? description : "");
        entity.setStatus(1);
        entity.setCreateUser(createUser);
        entity.setCreateTime(new Date());
        entity.setUpdateTime(new Date());

        boolean saved = knowledgeBaseService.save(entity);
        log.info("知识库创建成功: id={}, name={}, createUser={}", entity.getId(), name, createUser);

        // 在 Milvus 中创建对应的向量 collection
        try {
            vectorStoreService.createCollection(entity.getId());
            log.info("Milvus collection [kb_{}] 创建成功", entity.getId());
        } catch (Exception e) {
            log.error("Milvus collection 创建失败，知识库 {} 可能无法正常使用向量检索", entity.getId(), e);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "知识库创建成功，但向量库初始化失败: " + e.getMessage(),
                    "data", entity
            ));
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "知识库创建成功",
                "data", entity
        ));
    }

    /**
     * 删除知识库 — 同时删除 MySQL 记录和 Milvus collection
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        KnowledgeBaseEntity entity = knowledgeBaseService.getById(id);
        if (entity == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "知识库不存在"));
        }

        // 删除 Milvus collection
        try {
            vectorStoreService.dropCollection(id);
        } catch (Exception e) {
            log.error("Milvus collection [kb_{}] 删除失败", id, e);
        }

        knowledgeBaseService.removeById(id);
        log.info("知识库删除成功: id={}, name={}", id, entity.getName());

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "知识库删除成功"
        ));
    }

    /**
     * 查询所有知识库
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        List<KnowledgeBaseEntity> list = knowledgeBaseService.list();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", list
        ));
    }
}
