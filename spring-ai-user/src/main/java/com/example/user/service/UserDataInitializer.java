package com.example.user.service;

import com.example.user.entity.SysRoleEntity;
import com.example.user.entity.SysUserRoleEntity;
import com.example.user.entity.UserEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 用户域启动初始化：
 * 1. 确保 ADMIN 角色存在（幂等）
 * 2. 系统无任何用户时创建内置管理员 admin / admin123（首次启动引导，生产环境请立即修改密码）
 * 3. 确保 admin 账号已绑定 ADMIN 角色（防注册页抢注导致“假 admin”）
 * <p>
 * 该初始化类随 spring-ai-user 被宿主应用（RAG）扫描而自动执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserDataInitializer implements ApplicationRunner {

    private final SysRoleService sysRoleService;
    private final SysUserRoleService sysUserRoleService;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        ensureAdminRole();
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
