package com.example.springairagdemo.controller;

import com.example.springairagdemo.entity.KbMemberEntity;
import com.example.springairagdemo.entity.KnowledgeBaseEntity;
import com.example.springairagdemo.entity.UserEntity;
import com.example.springairagdemo.security.KbRole;
import com.example.springairagdemo.security.RequireKbRole;
import com.example.springairagdemo.security.UserContext;
import com.example.springairagdemo.service.KbAuthorizationService;
import com.example.springairagdemo.service.KnowledgeBaseService;
import com.example.springairagdemo.service.UserService;
import com.example.springairagdemo.service.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 知识库 CRUD REST API（含 Milvus collection 管理 + 成员授权）
 * <p>
 * 权限模型：创建者自动成为 OWNER；删除/授权需要 OWNER（ADMIN 放行）；列表仅返回当前用户可见的知识库。
 */
@RestController
@RequestMapping("/api/knowledge-base")
@Slf4j
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final VectorStoreService vectorStoreService;
    private final KbAuthorizationService kbAuthorizationService;
    private final UserService userService;

    /**
     * 创建知识库 — 同时在 Milvus 中创建对应 collection 和索引。
     * 创建人取自登录态（不信任前端传参），创建者自动成为 OWNER。
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> request) {
        String name = (String) request.get("name");
        String description = (String) request.get("description");

        Long currentUserId = UserContext.getUserId();
        if (currentUserId == null) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "未登录"));
        }
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "知识库名称不能为空"));
        }

        // 名称全局唯一（uk_name）；显式查重，避免触发数据库唯一键异常返回 500
        name = name.trim();
        long nameCount = knowledgeBaseService.lambdaQuery()
                .eq(KnowledgeBaseEntity::getName, name)
                .count();
        if (nameCount > 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("success", false, "code", 409, "message", "知识库名称已存在"));
        }

        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setName(name);
        entity.setDescription(description != null ? description : "");
        entity.setStatus(1);
        entity.setCreateUser(currentUserId);
        entity.setCreateTime(new Date());
        entity.setUpdateTime(new Date());

        boolean saved = knowledgeBaseService.save(entity);
        // 创建者自动成为 OWNER（授权早于 Milvus 初始化，保证任何情况下创建者都拥有控制权）
        kbAuthorizationService.grant(entity.getId(), currentUserId, KbRole.OWNER, currentUserId);
        kbAuthorizationService.audit("CREATE_KB", entity.getId(), null, "创建知识库 " + name);
        log.info("知识库创建成功: id={}, name={}, createUserId={}", entity.getId(), name, currentUserId);

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
     * 删除知识库 — 需要 OWNER 及以上；同时删除 MySQL 记录和 Milvus collection
     */
    @DeleteMapping("/{id}")
    @RequireKbRole(value = KbRole.OWNER, kbParam = "id")
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
        kbAuthorizationService.audit("DELETE_KB", id, null, "删除知识库 " + entity.getName());
        log.info("知识库删除成功: id={}, name={}", id, entity.getName());

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "知识库删除成功"
        ));
    }

    /**
     * 查询当前用户可见的知识库列表（ADMIN 返回全部；其余仅返回有成员授权的知识库）
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        List<KnowledgeBaseEntity> list;
        List<Long> visible = kbAuthorizationService.visibleKbIds();
        if (visible == null) {
            list = knowledgeBaseService.list();
        } else if (visible.isEmpty()) {
            list = List.of();
        } else {
            list = knowledgeBaseService.listByIds(visible);
        }

        Long currentUserId = UserContext.getUserId();
        // 解析创建人用户名（create_user 存用户ID；查不到则回退原始值，兼容历史脏数据）
        Map<Long, String> userNames = new HashMap<>();
        List<Long> creatorIds = list.stream().map(KnowledgeBaseEntity::getCreateUser)
                .filter(Objects::nonNull).distinct().toList();
        if (!creatorIds.isEmpty()) {
            userService.listByIds(creatorIds).forEach(u ->
                    userNames.put(u.getId(), u.getNickname() != null ? u.getNickname() : u.getUsername()));
        }
        List<Map<String, Object>> result = list.stream().map(kb -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", kb.getId());
            item.put("name", kb.getName());
            item.put("description", kb.getDescription());
            item.put("status", kb.getStatus());
            item.put("createUser", kb.getCreateUser());
            item.put("createUserName", kb.getCreateUser() == null ? null
                    : userNames.getOrDefault(kb.getCreateUser(), String.valueOf(kb.getCreateUser())));
            item.put("createTime", kb.getCreateTime());
            KbRole myRole = kbAuthorizationService.roleOf(currentUserId, kb.getId());
            item.put("myRole", myRole == null ? null : myRole.name());
            return item;
        }).toList();

        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", result
        ));
    }

    // ==================== 成员授权管理（OWNER/ADMIN） ====================

    /**
     * 查询知识库成员列表
     */
    @GetMapping("/{id}/members")
    @RequireKbRole(value = KbRole.OWNER, kbParam = "id")
    public ResponseEntity<Map<String, Object>> members(@PathVariable Long id) {
        List<KbMemberEntity> members = kbAuthorizationService.members(id);
        List<Long> userIds = members.stream().map(KbMemberEntity::getUserId).distinct().toList();
        Map<Long, UserEntity> userMap = userIds.isEmpty()
                ? Map.of()
                : userService.listByIds(userIds).stream()
                        .collect(Collectors.toMap(UserEntity::getId, u -> u));

        List<Map<String, Object>> result = members.stream().map(m -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userId", m.getUserId());
            item.put("role", m.getRole());
            item.put("grantUser", m.getGrantUser());
            item.put("createTime", m.getCreateTime());
            UserEntity u = userMap.get(m.getUserId());
            item.put("username", u == null ? null : u.getUsername());
            item.put("nickname", u == null ? null : u.getNickname());
            return item;
        }).toList();

        return ResponseEntity.ok(Map.of("success", true, "data", result));
    }

    /**
     * 授权/调整成员角色，body: {userId, role: OWNER|EDITOR|VIEWER}
     */
    @PostMapping("/{id}/members")
    @RequireKbRole(value = KbRole.OWNER, kbParam = "id")
    public ResponseEntity<Map<String, Object>> grantMember(@PathVariable Long id,
                                                           @RequestBody Map<String, Object> body) {
        Object userIdObj = body.get("userId");
        String roleStr = (String) body.get("role");
        if (userIdObj == null || roleStr == null || roleStr.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "userId 和 role 不能为空"));
        }
        Long userId = userIdObj instanceof Number
                ? ((Number) userIdObj).longValue()
                : Long.parseLong(userIdObj.toString());
        KbRole role = KbRole.fromString(roleStr);
        if (role == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "不支持的成员角色，可选: OWNER/EDITOR/VIEWER"));
        }
        if (userService.getById(userId) == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "目标用户不存在"));
        }

        kbAuthorizationService.grant(id, userId, role, UserContext.getUserId());
        return ResponseEntity.ok(Map.of("success", true, "message", "授权成功"));
    }

    /**
     * 移除成员（最后一个 OWNER 不可移除）
     */
    @DeleteMapping("/{id}/members/{userId}")
    @RequireKbRole(value = KbRole.OWNER, kbParam = "id")
    public ResponseEntity<Map<String, Object>> revokeMember(@PathVariable Long id, @PathVariable Long userId) {
        kbAuthorizationService.revoke(id, userId);
        return ResponseEntity.ok(Map.of("success", true, "message", "已移除成员"));
    }
}
