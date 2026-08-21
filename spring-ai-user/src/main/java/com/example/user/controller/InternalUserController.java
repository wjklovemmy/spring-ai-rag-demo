package com.example.user.controller;

import com.example.user.entity.UserEntity;
import com.example.user.security.ForbiddenException;
import com.example.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用户服务内部接口（供 RAG 服务调用，不经过网关）。
 * <p>
 * RAG 服务通过本接口完成跨进程的"用户域查询"，替代拆分前的同进程
 * {@code UserService} / {@code SysUser} 直接依赖：
 * <ul>
 *   <li>{@code GET /internal/users/{id}/is-admin}：指定用户是否为 ADMIN（知识库管理员豁免判定）</li>
 *   <li>{@code POST /internal/users/batch}：批量查询用户摘要（知识库创建人 / 成员展示）</li>
 * </ul>
 * 所有接口必须携带与 RAG 侧一致的 X-Internal-Token 头，防止未授权访问。
 */
@RestController
@RequestMapping("/internal/users")
public class InternalUserController {

    private final UserService userService;
    private final String internalToken;

    public InternalUserController(UserService userService,
                                  @Value("${internal-token}") String internalToken) {
        this.userService = userService;
        this.internalToken = internalToken;
    }

    /** 指定用户是否为 ADMIN */
    @GetMapping("/{id}/is-admin")
    public ResponseEntity<Map<String, Object>> isAdmin(@PathVariable Long id, HttpServletRequest request) {
        verifyInternalToken(request);
        boolean isAdmin = userService.isAdmin(id);
        return ResponseEntity.ok(Map.of("success", true, "isAdmin", isAdmin));
    }

    /** 批量查询用户摘要。请求体：{ "ids": [1,2,3] } */
    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> batch(@RequestBody Map<String, Object> body,
                                                     HttpServletRequest request) {
        verifyInternalToken(request);
        @SuppressWarnings("unchecked")
        List<Number> ids = (List<Number>) body.getOrDefault("ids", List.of());
        List<Long> userIds = ids.stream().map(Number::longValue).distinct().toList();

        List<Map<String, Object>> users = userService.listByIds(userIds).stream()
                .map(this::toBrief)
                .toList();
        return ResponseEntity.ok(Map.of("success", true, "data", users));
    }

    private Map<String, Object> toBrief(UserEntity u) {
        return Map.of(
                "id", u.getId(),
                "username", u.getUsername(),
                "nickname", u.getNickname() == null ? "" : u.getNickname()
        );
    }

    private void verifyInternalToken(HttpServletRequest request) {
        String token = request.getHeader("X-Internal-Token");
        if (internalToken == null || internalToken.isBlank() || !internalToken.equals(token)) {
            throw new ForbiddenException("内部令牌无效");
        }
    }
}
