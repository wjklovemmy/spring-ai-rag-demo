package com.example.springairagdemo.controller;

import com.example.springairagdemo.security.ForbiddenException;
import com.example.springairagdemo.service.KbAccessLogAuditHandler;
import com.example.springairagdemo.service.KbMemberDeletionGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * RAG 内部接口（供用户服务 spring-ai-user 跨进程回调，不经过网关）。
 * <p>
 * 用户服务独立部署后，原同进程 SPI 扩展点（删除用户前的知识库授权校验 / 删除后清理 /
 * 管理操作审计）改为经本接口回调：
 * <ul>
 *   <li>{@code POST /internal/kb/deletion-check}：删除用户前校验（最后一个 OWNER 保护）</li>
 *   <li>{@code POST /internal/kb/user-cleanup}：删除用户后清理 kb_member</li>
 *   <li>{@code POST /internal/kb/audit}：管理操作审计落库（操作者由用户服务显式传入）</li>
 * </ul>
 * 所有接口必须携带与用户服务侧一致的 X-Internal-Token 头，防止未授权访问。
 */
@RestController
@RequestMapping("/internal/kb")
public class InternalController {

    private final KbMemberDeletionGuard deletionGuard;
    private final KbAccessLogAuditHandler auditHandler;
    private final String internalToken;

    public InternalController(KbMemberDeletionGuard deletionGuard,
                              KbAccessLogAuditHandler auditHandler,
                              @Value("${internal-token}") String internalToken) {
        this.deletionGuard = deletionGuard;
        this.auditHandler = auditHandler;
        this.internalToken = internalToken;
    }

    /** 删除用户前校验：{ "userId": 1 } */
    @PostMapping("/deletion-check")
    public ResponseEntity<Map<String, Object>> deletionCheck(@RequestBody Map<String, Object> body,
                                                             HttpServletRequest request) {
        verifyInternalToken(request);
        Long userId = parseUserId(body);
        try {
            deletionGuard.validateDeletion(userId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (ForbiddenException e) {
            // 校验不通过：409 + message，用户服务据此阻止删除
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** 删除用户后清理知识库授权：{ "userId": 1 } */
    @PostMapping("/user-cleanup")
    public ResponseEntity<Map<String, Object>> userCleanup(@RequestBody Map<String, Object> body,
                                                           HttpServletRequest request) {
        verifyInternalToken(request);
        Long userId = parseUserId(body);
        deletionGuard.onUserDeleted(userId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** 管理操作审计上报：{ "action": "...", "detail": "...", "operatorId": 1, "operatorName": "...", "operatorIp": "..." } */
    @PostMapping("/audit")
    public ResponseEntity<Map<String, Object>> audit(@RequestBody Map<String, Object> body,
                                                     HttpServletRequest request) {
        verifyInternalToken(request);
        String action = String.valueOf(body.getOrDefault("action", ""));
        String detail = String.valueOf(body.getOrDefault("detail", ""));
        Long operatorId = body.get("operatorId") instanceof Number n ? n.longValue() : null;
        String operatorName = String.valueOf(body.getOrDefault("operatorName", ""));
        String operatorIp = String.valueOf(body.getOrDefault("operatorIp", ""));
        auditHandler.audit(action, detail, operatorId, operatorName, operatorIp);
        return ResponseEntity.ok(Map.of("success", true));
    }

    private Long parseUserId(Map<String, Object> body) {
        Object v = body.get("userId");
        if (v instanceof Number n) {
            return n.longValue();
        }
        throw new IllegalArgumentException("缺少有效的 userId");
    }

    private void verifyInternalToken(HttpServletRequest request) {
        String token = request.getHeader("X-Internal-Token");
        if (internalToken == null || internalToken.isBlank() || !internalToken.equals(token)) {
            throw new ForbiddenException("内部令牌无效");
        }
    }
}
