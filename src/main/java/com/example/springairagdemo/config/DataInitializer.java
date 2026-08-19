package com.example.springairagdemo.config;

import com.example.springairagdemo.entity.KnowledgeEmbeddingTaskEntity;
import com.example.springairagdemo.entity.KnowledgeEmbeddingTaskStatus;
import com.example.springairagdemo.entity.SysRoleEntity;
import com.example.springairagdemo.entity.SysUserRoleEntity;
import com.example.springairagdemo.entity.UserEntity;
import com.example.springairagdemo.service.KnowledgeDocumentService;
import com.example.springairagdemo.service.KnowledgeEmbeddingTaskService;
import com.example.springairagdemo.service.SysRoleService;
import com.example.springairagdemo.service.SysUserRoleService;
import com.example.springairagdemo.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * 启动初始化：
 * 1. 确保 ADMIN 角色存在（幂等）
 * 2. 系统无任何用户时创建内置管理员 admin / admin123（首次启动引导，生产环境请立即修改密码）
 * 3. 恢复中断的 Embedding 任务：将上次运行时"处理中"的任务标记为失败（服务重启中断）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final SysRoleService sysRoleService;
    private final SysUserRoleService sysUserRoleService;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final KnowledgeEmbeddingTaskService knowledgeEmbeddingTaskService;
    private final KnowledgeDocumentService knowledgeDocumentService;

    @Override
    public void run(ApplicationArguments args) {
        ensureAdminRole();
        ensureBootstrapAdmin();
        recoverInterruptedTasks();
    }

    /**
     * 服务重启后，恢复上次中断的任务：
     * 同时覆盖"待处理"（status=0）与"处理中"（status=1）——
     * 若 JVM 在任务保存为 PENDING 后、异步线程实际开始前崩溃，仅恢复 PROCESSING 会漏掉它，
     * 导致任务永远卡在"待处理"。恢复以增量方式重新入队执行：
     * 已完整处理（MySQL + 向量均完成）的 chunk 直接跳过，只补齐缺失或内容变化的片段，
     * 避免重复解析/切分/embedding/写入；恢复失败（如解析异常）则标记为失败，
     * 避免任务永远卡在中间状态。
     */
    private void recoverInterruptedTasks() {
        List<KnowledgeEmbeddingTaskEntity> interrupted = knowledgeEmbeddingTaskService.lambdaQuery()
                .in(KnowledgeEmbeddingTaskEntity::getStatus,
                        KnowledgeEmbeddingTaskStatus.PENDING, KnowledgeEmbeddingTaskStatus.PROCESSING)
                .list();
        if (interrupted.isEmpty()) {
            return;
        }
        log.info("发现 {} 个中断的 Embedding 任务，开始恢复执行", interrupted.size());
        int resumed = 0;
        for (KnowledgeEmbeddingTaskEntity task : interrupted) {
            try {
                knowledgeDocumentService.resumeInterruptedTask(task.getId());
                resumed++;
            } catch (Exception e) {
                log.error("恢复中断任务异常: taskNo={}", task.getTaskNo(), e);
                markTaskFailed(task.getId(), "重启恢复异常: " + e.getMessage());
            }
        }
        log.info("中断任务恢复完成: {}/{}", resumed, interrupted.size());
    }

    /**
     * 将任务标记为失败（恢复失败时的兜底，避免下次启动再次扫描）
     */
    private void markTaskFailed(Long taskId, String error) {
        try {
            knowledgeEmbeddingTaskService.lambdaUpdate()
                    .eq(KnowledgeEmbeddingTaskEntity::getId, taskId)
                    .set(KnowledgeEmbeddingTaskEntity::getStatus, KnowledgeEmbeddingTaskStatus.FAILED)
                    .set(KnowledgeEmbeddingTaskEntity::getErrorMessage, error)
                    .set(KnowledgeEmbeddingTaskEntity::getFinishTime, new Date())
                    .set(KnowledgeEmbeddingTaskEntity::getUpdateTime, new Date())
                    .update();
        } catch (Exception ex) {
            log.error("标记任务失败异常: taskId={}", taskId, ex);
        }
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
