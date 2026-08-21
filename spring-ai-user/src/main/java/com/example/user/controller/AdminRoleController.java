package com.example.user.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.user.entity.SysPermissionEntity;
import com.example.user.entity.SysRoleEntity;
import com.example.user.entity.SysRolePermissionEntity;
import com.example.user.entity.SysUserRoleEntity;
import com.example.user.security.RequireAdmin;
import com.example.user.service.SysPermissionService;
import com.example.user.service.SysRolePermissionService;
import com.example.user.service.SysRoleService;
import com.example.user.service.SysUserRoleService;
import com.example.user.service.UserService;
import com.example.user.spi.UserAdminAuditHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 角色管理 REST API（仅 ADMIN 可访问）：
 * 角色列表（含用户数）/ 创建 / 更新 / 删除（内置 ADMIN 角色不可删除）。
 */
@RestController
@RequestMapping("/api/admin/roles")
@Slf4j
@RequiredArgsConstructor
public class AdminRoleController {

    private final SysRoleService sysRoleService;
    private final SysUserRoleService sysUserRoleService;
    private final SysPermissionService sysPermissionService;
    private final SysRolePermissionService sysRolePermissionService;
    private final UserAdminAuditHandler auditHandler;

    /** 角色列表（含每个角色的用户数） */
    @GetMapping
    @RequireAdmin
    public ResponseEntity<Map<String, Object>> list() {
        List<SysRoleEntity> roles = sysRoleService.list(
                new LambdaQueryWrapper<SysRoleEntity>().orderByAsc(SysRoleEntity::getId));

        Map<Long, Long> userCountMap = sysUserRoleService.lambdaQuery()
                .list().stream()
                .collect(Collectors.groupingBy(SysUserRoleEntity::getRoleId, Collectors.counting()));

        List<Map<String, Object>> result = roles.stream().map(r -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.getId());
            item.put("code", r.getCode());
            item.put("name", r.getName());
            item.put("remark", r.getRemark());
            item.put("userCount", userCountMap.getOrDefault(r.getId(), 0L));
            item.put("createTime", r.getCreateTime());
            return item;
        }).toList();

        return ResponseEntity.ok(Map.of("success", true, "data", result));
    }

    /** 创建角色，body: {code, name, remark} */
    @PostMapping
    @RequireAdmin
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String code = (String) body.get("code");
        String name = (String) body.get("name");
        String remark = (String) body.get("remark");
        if (code == null || code.isBlank() || name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "角色编码和名称不能为空"));
        }
        code = code.trim().toUpperCase();
        long exists = sysRoleService.lambdaQuery().eq(SysRoleEntity::getCode, code).count();
        if (exists > 0) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "角色编码已存在"));
        }

        SysRoleEntity entity = new SysRoleEntity();
        entity.setCode(code);
        entity.setName(name.trim());
        entity.setRemark(remark != null && !remark.isBlank() ? remark.trim() : null);
        entity.setCreateTime(new Date());
        entity.setUpdateTime(new Date());
        boolean saved = sysRoleService.save(entity);
        if (!saved) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "创建角色失败"));
        }
        auditHandler.audit("ROLE_CREATE", "创建功能角色 " + code);
        log.info("创建角色: code={}, name={}", code, name);
        return ResponseEntity.ok(Map.of("success", true, "message", "创建成功", "id", entity.getId()));
    }

    /** 更新角色（名称/备注），body: {name, remark}。内置 ADMIN 名称不可改 */
    @PutMapping("/{id}")
    @RequireAdmin
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id,
                                                      @RequestBody Map<String, Object> body) {
        SysRoleEntity role = sysRoleService.getById(id);
        if (role == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "角色不存在"));
        }
        String name = (String) body.get("name");
        String remark = (String) body.get("remark");
        if (name != null && !name.isBlank() && !UserService.ADMIN_ROLE_CODE.equals(role.getCode())) {
            role.setName(name.trim());
        }
        if (remark != null) {
            role.setRemark(remark.isBlank() ? null : remark.trim());
        }
        role.setUpdateTime(new Date());
        sysRoleService.updateById(role);
        auditHandler.audit("ROLE_UPDATE", "更新角色 " + role.getCode());
        return ResponseEntity.ok(Map.of("success", true, "message", "更新成功"));
    }

    /** 删除角色：内置 ADMIN 不可删除；同时清理用户角色关联与角色权限关联 */
    @DeleteMapping("/{id}")
    @RequireAdmin
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        SysRoleEntity role = sysRoleService.getById(id);
        if (role == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "角色不存在"));
        }
        if (UserService.ADMIN_ROLE_CODE.equals(role.getCode())) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "内置 ADMIN 角色不可删除"));
        }
        sysUserRoleService.lambdaUpdate().eq(SysUserRoleEntity::getRoleId, id).remove();
        sysRolePermissionService.lambdaUpdate().eq(SysRolePermissionEntity::getRoleId, id).remove();
        sysRoleService.removeById(id);
        auditHandler.audit("ROLE_DELETE", "删除功能角色 " + role.getCode());
        log.info("删除角色: id={}, code={}", id, role.getCode());
        return ResponseEntity.ok(Map.of("success", true, "message", "角色已删除"));
    }

    // ==================== 权限（sys_permission / sys_role_permission） ====================

    /** 全量权限列表（供角色分配时勾选）。独立路径，避免与 /{id} 冲突 */
    @GetMapping("/api/admin/permissions")
    @RequireAdmin
    public ResponseEntity<Map<String, Object>> permissionList() {
        List<Map<String, Object>> result = sysPermissionService.lambdaQuery()
                .eq(SysPermissionEntity::getStatus, 1)
                .orderByAsc(SysPermissionEntity::getSort)
                .list().stream().map(p -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", p.getId());
                    item.put("code", p.getCode());
                    item.put("name", p.getName());
                    item.put("type", p.getType());
                    return item;
                }).toList();
        return ResponseEntity.ok(Map.of("success", true, "data", result));
    }

    /** 查询角色已绑定的权限 */
    @GetMapping("/{id}/permissions")
    @RequireAdmin
    public ResponseEntity<Map<String, Object>> rolePermissions(@PathVariable Long id) {
        SysRoleEntity role = sysRoleService.getById(id);
        if (role == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "角色不存在"));
        }
        List<Long> permissionIds = sysRolePermissionService.lambdaQuery()
                .eq(SysRolePermissionEntity::getRoleId, id)
                .list().stream()
                .map(SysRolePermissionEntity::getPermissionId)
                .toList();
        List<Map<String, Object>> perms = permissionIds.isEmpty() ? List.of()
                : sysPermissionService.listByIds(permissionIds).stream().map(p -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", p.getId());
                    item.put("code", p.getCode());
                    item.put("name", p.getName());
                    return item;
                }).toList();
        return ResponseEntity.ok(Map.of("success", true, "data", perms));
    }

    /** 给角色分配权限（覆盖式），body: {permissionIds: [1,2,3]} */
    @PutMapping("/{id}/permissions")
    @RequireAdmin
    public ResponseEntity<Map<String, Object>> assignPermissions(@PathVariable Long id,
                                                                 @RequestBody Map<String, Object> body) {
        SysRoleEntity role = sysRoleService.getById(id);
        if (role == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "角色不存在"));
        }
        Object permissionIdsObj = body.get("permissionIds");
        if (!(permissionIdsObj instanceof List)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "permissionIds 必须为数组"));
        }
        List<Long> permissionIds = ((List<?>) permissionIdsObj).stream()
                .map(o -> ((Number) o).longValue())
                .distinct()
                .toList();
        if (!permissionIds.isEmpty()
                && sysPermissionService.listByIds(permissionIds).size() != permissionIds.size()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "包含不存在的权限"));
        }

        // 覆盖式重写：删除旧关联，插入新关联
        sysRolePermissionService.lambdaUpdate().eq(SysRolePermissionEntity::getRoleId, id).remove();
        if (!permissionIds.isEmpty()) {
            Date now = new Date();
            List<SysRolePermissionEntity> binds = permissionIds.stream().map(pid -> {
                SysRolePermissionEntity relation = new SysRolePermissionEntity();
                relation.setRoleId(id);
                relation.setPermissionId(pid);
                relation.setCreateTime(now);
                return relation;
            }).toList();
            sysRolePermissionService.saveBatch(binds);
        }
        auditHandler.audit("ROLE_GRANT_PERMISSION",
                "为角色 " + role.getCode() + " 分配权限 " + permissionIds);
        log.info("角色权限分配: roleId={}, permissionIds={}", id, permissionIds);
        return ResponseEntity.ok(Map.of("success", true, "message", "权限分配成功"));
    }
}
