package com.example.user.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.user.entity.SysRoleEntity;
import com.example.user.entity.SysUserRoleEntity;
import com.example.user.entity.UserEntity;
import com.example.user.security.RequireAdmin;
import com.example.user.security.UserContext;
import com.example.user.service.SysRoleService;
import com.example.user.service.SysUserRoleService;
import com.example.user.config.RagSyncClient;
import com.example.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户管理 REST API（仅 ADMIN 可访问）：
 * 用户列表 / 创建 / 启禁用 / 重置密码 / 删除 / 分配功能角色。
 * <p>
 * 用户域不依赖业务模块：删除用户时的知识库授权校验与清理、管理操作审计，
 * 经 {@link RagSyncClient} 远程回调 RAG 服务（/internal/kb/**）完成。
 */
@RestController
@RequestMapping("/api/admin/users")
@Slf4j
@RequiredArgsConstructor
public class AdminUserController {

    private final UserService userService;
    private final SysRoleService sysRoleService;
    private final SysUserRoleService sysUserRoleService;
    private final PasswordEncoder passwordEncoder;
    private final RagSyncClient ragSyncClient;

    /** 用户列表（可按用户名/昵称模糊搜索），返回每个用户已分配的功能角色 */
    @GetMapping
    @RequireAdmin
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(UserEntity::getUsername, keyword.trim())
                    .or().like(UserEntity::getNickname, keyword.trim()));
        }
        wrapper.orderByAsc(UserEntity::getId);
        List<UserEntity> users = userService.list(wrapper);

        // 批量查出角色关联与角色信息
        List<Long> userIds = users.stream().map(UserEntity::getId).toList();
        Map<Long, List<SysUserRoleEntity>> userRoleMap = userIds.isEmpty() ? Map.of()
                : sysUserRoleService.lambdaQuery()
                        .in(SysUserRoleEntity::getUserId, userIds)
                        .list().stream()
                        .collect(Collectors.groupingBy(SysUserRoleEntity::getUserId));
        List<Long> roleIds = userRoleMap.values().stream()
                .flatMap(List::stream).map(SysUserRoleEntity::getRoleId).distinct().toList();
        Map<Long, SysRoleEntity> roleMap = roleIds.isEmpty() ? Map.of()
                : sysRoleService.listByIds(roleIds).stream()
                        .collect(Collectors.toMap(SysRoleEntity::getId, r -> r));

        List<Map<String, Object>> result = users.stream().map(u -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", u.getId());
            item.put("username", u.getUsername());
            item.put("nickname", u.getNickname());
            item.put("email", u.getEmail());
            item.put("status", u.getStatus());
            item.put("createTime", u.getCreateTime());
            List<SysUserRoleEntity> relations = userRoleMap.getOrDefault(u.getId(), List.of());
            List<Map<String, Object>> roles = relations.stream().map(rel -> {
                SysRoleEntity role = roleMap.get(rel.getRoleId());
                Map<String, Object> roleItem = new LinkedHashMap<>();
                roleItem.put("id", rel.getRoleId());
                roleItem.put("code", role == null ? null : role.getCode());
                roleItem.put("name", role == null ? null : role.getName());
                return roleItem;
            }).toList();
            item.put("roles", roles);
            return item;
        }).toList();

        return ResponseEntity.ok(Map.of("success", true, "data", result));
    }

    /** 创建用户（用户名唯一，密码 BCrypt 加密），初始无功能角色 */
    @PostMapping
    @RequireAdmin
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String username = (String) body.get("username");
        String password = (String) body.get("password");
        String nickname = (String) body.get("nickname");
        String email = (String) body.get("email");
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "用户名和密码不能为空"));
        }
        username = username.trim();
        long exists = userService.lambdaQuery().eq(UserEntity::getUsername, username).count();
        if (exists > 0) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "用户名已存在"));
        }

        UserEntity entity = new UserEntity();
        entity.setUsername(username);
        entity.setPassword(passwordEncoder.encode(password));
        entity.setNickname(nickname != null && !nickname.isBlank() ? nickname.trim() : username);
        entity.setEmail(email != null && !email.isBlank() ? email.trim() : null);
        entity.setStatus(1);
        entity.setCreateTime(new Date());
        entity.setUpdateTime(new Date());
        boolean saved = userService.save(entity);
        if (!saved) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "创建用户失败"));
        }
        ragSyncClient.audit("USER_CREATE", "管理员创建用户 " + username);
        log.info("管理员创建用户: {}", username);
        return ResponseEntity.ok(Map.of("success", true, "message", "创建成功", "id", entity.getId()));
    }

    /** 启用/禁用用户，body: {status: 0|1}。不能禁用自己，也不能禁用最后一个 ADMIN */
    @PutMapping("/{id}/status")
    @RequireAdmin
    public ResponseEntity<Map<String, Object>> updateStatus(@PathVariable Long id,
                                                            @RequestBody Map<String, Object> body) {
        Object statusObj = body.get("status");
        if (!(statusObj instanceof Number)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "status 必须为 0 或 1"));
        }
        int status = ((Number) statusObj).intValue();
        UserEntity user = userService.getById(id);
        if (user == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "用户不存在"));
        }
        if (id.equals(UserContext.getUserId())) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "不能修改自己的状态"));
        }
        if (status == 0 && userService.isAdmin(id) && userService.countAdmins() <= 1) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "不能禁用最后一个管理员"));
        }
        user.setStatus(status);
        user.setUpdateTime(new Date());
        userService.updateById(user);
        ragSyncClient.audit("USER_STATUS",
                "管理员" + (status == 1 ? "启用" : "禁用") + "用户 " + user.getUsername());
        log.info("用户状态变更: userId={}, status={}", id, status);
        return ResponseEntity.ok(Map.of("success", true, "message", status == 1 ? "已启用" : "已禁用"));
    }

    /** 重置密码，body: {password} */
    @PutMapping("/{id}/password")
    @RequireAdmin
    public ResponseEntity<Map<String, Object>> resetPassword(@PathVariable Long id,
                                                             @RequestBody Map<String, Object> body) {
        String password = (String) body.get("password");
        if (password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "新密码不能为空"));
        }
        UserEntity user = userService.getById(id);
        if (user == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "用户不存在"));
        }
        user.setPassword(passwordEncoder.encode(password));
        user.setUpdateTime(new Date());
        userService.updateById(user);
        ragSyncClient.audit("USER_PASSWORD", "管理员重置用户 " + user.getUsername() + " 的密码");
        log.info("重置用户密码: userId={}", id);
        return ResponseEntity.ok(Map.of("success", true, "message", "密码已重置"));
    }

    /**
     * 删除用户：不能删除自己；不能删除最后一个 ADMIN；
     * “最后一个知识库所有者”保护与 kb_member 清理经 {@link RagSyncClient} 远程回调 RAG 服务
     * （/internal/kb/deletion-check 与 /internal/kb/user-cleanup，由 userService.deleteUser 触发）。
     */
    @DeleteMapping("/{id}")
    @RequireAdmin
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        if (id.equals(UserContext.getUserId())) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "不能删除自己"));
        }
        UserEntity user = userService.getById(id);
        if (user == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "用户不存在"));
        }
        if (userService.isAdmin(id) && userService.countAdmins() <= 1) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "不能删除最后一个管理员"));
        }
        userService.deleteUser(id);
        ragSyncClient.audit("USER_DELETE", "管理员删除用户 " + user.getUsername());
        log.info("删除用户: userId={}, username={}", id, user.getUsername());
        return ResponseEntity.ok(Map.of("success", true, "message", "用户已删除"));
    }

    /** 查询用户已分配的功能角色 */
    @GetMapping("/{id}/roles")
    @RequireAdmin
    public ResponseEntity<Map<String, Object>> userRoles(@PathVariable Long id) {
        List<SysUserRoleEntity> relations = sysUserRoleService.lambdaQuery()
                .eq(SysUserRoleEntity::getUserId, id).list();
        List<Long> roleIds = relations.stream().map(SysUserRoleEntity::getRoleId).toList();
        List<SysRoleEntity> roles = roleIds.isEmpty() ? List.of() : sysRoleService.listByIds(roleIds);
        List<Map<String, Object>> result = roles.stream().map(r -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.getId());
            item.put("code", r.getCode());
            item.put("name", r.getName());
            return item;
        }).toList();
        return ResponseEntity.ok(Map.of("success", true, "data", result));
    }

    /**
     * 给用户分配功能角色（覆盖式），body: {roleIds: [1,2]}。
     * 若该用户是最后一个 ADMIN，则新角色必须包含 ADMIN，防止系统失去管理员。
     */
    @PutMapping("/{id}/roles")
    @RequireAdmin
    public ResponseEntity<Map<String, Object>> assignRoles(@PathVariable Long id,
                                                           @RequestBody Map<String, Object> body) {
        UserEntity user = userService.getById(id);
        if (user == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "用户不存在"));
        }
        Object roleIdsObj = body.get("roleIds");
        if (!(roleIdsObj instanceof List)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "roleIds 必须为数组"));
        }
        List<Long> newRoleIds = ((List<?>) roleIdsObj).stream()
                .map(o -> ((Number) o).longValue())
                .toList();
        List<SysRoleEntity> newRoles = newRoleIds.isEmpty() ? List.of() : sysRoleService.listByIds(newRoleIds);
        if (newRoles.size() != newRoleIds.size()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "包含不存在的角色"));
        }
        // 保护最后一个 ADMIN
        boolean currentlyAdmin = userService.isAdmin(id);
        boolean newHasAdmin = newRoles.stream()
                .anyMatch(r -> UserService.ADMIN_ROLE_CODE.equals(r.getCode()));
        if (currentlyAdmin && !newHasAdmin && userService.countAdmins() <= 1) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "不能移除最后一个管理员的功能角色"));
        }

        // 覆盖式重写：删除旧关联，插入新关联
        sysUserRoleService.lambdaUpdate().eq(SysUserRoleEntity::getUserId, id).remove();
        for (Long roleId : newRoleIds) {
            SysUserRoleEntity relation = new SysUserRoleEntity();
            relation.setUserId(id);
            relation.setRoleId(roleId);
            relation.setCreateTime(new Date());
            sysUserRoleService.save(relation);
        }
        String roleCodes = newRoles.stream().map(SysRoleEntity::getCode).collect(Collectors.joining(","));
        ragSyncClient.audit("ROLE_ASSIGN",
                "管理员为用户 " + user.getUsername() + " 分配功能角色 [" + roleCodes + "]");
        log.info("分配角色: userId={}, roleIds={}", id, newRoleIds);
        return ResponseEntity.ok(Map.of("success", true, "message", "角色分配成功"));
    }
}
