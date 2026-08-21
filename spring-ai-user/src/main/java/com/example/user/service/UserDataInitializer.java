package com.example.user.service;

import com.example.user.entity.SysPermissionEntity;
import com.example.user.entity.SysRoleEntity;
import com.example.user.entity.SysRolePermissionEntity;
import com.example.user.entity.SysUserRoleEntity;
import com.example.user.entity.UserEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户域启动初始化（独立库 spring_ai_user）：
 * 1. 确保 ADMIN 角色存在（幂等）
 * 2. 确保权限种子存在（sys_permission）并绑定全部权限到 ADMIN 角色（sys_role_permission）
 * 3. 系统无任何用户时创建内置管理员 admin / admin123（首次启动引导，生产环境请立即修改密码）
 * 4. 确保 admin 账号已绑定 ADMIN 角色（防注册页抢注导致“假 admin”）
 * <p>
 * 该初始化类随 spring-ai-user 被宿主应用（RAG）扫描而自动执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserDataInitializer implements ApplicationRunner {

    private final SysRoleService sysRoleService;
    private final SysUserRoleService sysUserRoleService;
    private final SysPermissionService sysPermissionService;
    private final SysRolePermissionService sysRolePermissionService;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        ensureAdminRole();
        ensurePermissionSeeds();
        ensureBootstrapAdmin();
    }

    private void ensureAdminRole() {
        long count = sysRoleService.lambdaQuery()
                .eq(SysRoleEntity::getCode, "ADMIN")
                .count();
        if (count > 0) {
            return;
        }
        SysRoleEntity role = new SysRoleEntity();
        role.setCode("ADMIN");
        role.setName("系统管理员");
        role.setRemark("可管理所有知识库");
        role.setCreateTime(new Date());
        role.setUpdateTime(new Date());
        sysRoleService.save(role);
        log.info("已创建内置角色 ADMIN (id={})", role.getId());
    }

    /** 权限种子（与 sql/user.sql 保持一致）：补齐 sys_permission，并将全部权限绑定到 ADMIN 角色 */
    private void ensurePermissionSeeds() {
        List<SysPermissionEntity> seeds = List.of(
                seed("kb:manage", "知识库管理", 10),
                seed("kb:upload", "文档上传", 20),
                seed("kb:delete", "文档删除", 30),
                seed("kb:query", "知识库问答", 40),
                seed("user:manage", "用户管理", 50),
                seed("role:manage", "角色管理", 60)
        );
        for (SysPermissionEntity seed : seeds) {
            long exists = sysPermissionService.lambdaQuery()
                    .eq(SysPermissionEntity::getCode, seed.getCode())
                    .count();
            if (exists == 0) {
                sysPermissionService.save(seed);
                log.info("已创建权限种子: {}", seed.getCode());
            }
        }
        // ADMIN 角色绑定全部启用权限（幂等）
        SysRoleEntity adminRole = sysRoleService.lambdaQuery()
                .eq(SysRoleEntity::getCode, "ADMIN")
                .one();
        if (adminRole == null) {
            return;
        }
        List<Long> permissionIds = sysPermissionService.lambdaQuery()
                .eq(SysPermissionEntity::getStatus, 1)
                .list().stream()
                .map(SysPermissionEntity::getId)
                .toList();
        Set<Long> boundIds = sysRolePermissionService.lambdaQuery()
                .eq(SysRolePermissionEntity::getRoleId, adminRole.getId())
                .list().stream()
                .map(SysRolePermissionEntity::getPermissionId)
                .collect(Collectors.toSet());
        List<SysRolePermissionEntity> toAdd = permissionIds.stream()
                .filter(pid -> !boundIds.contains(pid))
                .map(pid -> {
                    SysRolePermissionEntity bind = new SysRolePermissionEntity();
                    bind.setRoleId(adminRole.getId());
                    bind.setPermissionId(pid);
                    bind.setCreateTime(new Date());
                    return bind;
                })
                .toList();
        if (!toAdd.isEmpty()) {
            sysRolePermissionService.saveBatch(toAdd);
            log.info("已为 ADMIN 角色绑定 {} 个权限", toAdd.size());
        }
    }

    private SysPermissionEntity seed(String code, String name, int sort) {
        SysPermissionEntity p = new SysPermissionEntity();
        p.setCode(code);
        p.setName(name);
        p.setType(2); // 按钮/API 级权限
        p.setParentId(0L);
        p.setSort(sort);
        p.setStatus(1);
        p.setCreateTime(new Date());
        p.setUpdateTime(new Date());
        return p;
    }

    private void ensureBootstrapAdmin() {
        UserEntity admin = userService.lambdaQuery()
                .eq(UserEntity::getUsername, "admin")
                .one();
        if (admin == null) {
            admin = new UserEntity();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("admin123"));
            admin.setNickname("系统管理员");
            admin.setStatus(1);
            admin.setCreateTime(new Date());
            admin.setUpdateTime(new Date());
            userService.save(admin);
            log.info("已创建内置管理员账号 admin（密码 admin123，请尽快修改）");
        }
        // admin 已存在（如注册页抢注）时，确保其绑定了 ADMIN 角色，避免出现无权限的“假 admin”
        Long adminId = admin.getId();
        boolean bound = sysUserRoleService.lambdaQuery()
                .eq(SysUserRoleEntity::getUserId, adminId)
                .count() > 0;
        if (!bound) {
            SysRoleEntity adminRole = sysRoleService.lambdaQuery()
                    .eq(SysRoleEntity::getCode, "ADMIN")
                    .one();
            if (adminRole != null) {
                SysUserRoleEntity bind = new SysUserRoleEntity();
                bind.setUserId(adminId);
                bind.setRoleId(adminRole.getId());
                bind.setCreateTime(new Date());
                sysUserRoleService.save(bind);
                log.info("已为 admin 用户绑定 ADMIN 角色 (userId={})", adminId);
            }
        }
    }
}
