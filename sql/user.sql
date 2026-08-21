-- ============================================================
-- 用户域独立数据库：spring_ai_user
-- 执行：mysql -u root -p < sql/user.sql
-- 说明：
--   1. 用户域（spring-ai-user 共享 jar）使用独立数据库，与 RAG 业务库（knowledge_base）物理隔离
--   2. 共 5 张表：sys_user / sys_role / sys_permission / sys_user_role / sys_role_permission
--   3. RBAC 权限模型：用户 -> 角色（sys_user_role）、角色 -> 权限（sys_role_permission）
--   4. RAG 库 kb_member / kb_access_log 中的 user_id 为跨库逻辑引用（无外键约束），
--      删除用户前请先通过应用 SPI（UserDeletionGuard）清理业务域数据
-- ============================================================
CREATE DATABASE IF NOT EXISTS `spring_ai_user` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `spring_ai_user`;

-- 1. 系统用户表
CREATE TABLE IF NOT EXISTS `sys_user` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `username`    VARCHAR(50)  NOT NULL COMMENT '用户名，登录用',
    `password`    VARCHAR(255) NOT NULL COMMENT 'BCrypt 加密后的密码',
    `nickname`    VARCHAR(50)  DEFAULT NULL COMMENT '显示昵称',
    `email`       VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

-- 2. 角色表
CREATE TABLE IF NOT EXISTS `sys_role` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `code`        VARCHAR(50)  NOT NULL COMMENT '角色编码，如 ADMIN',
    `name`        VARCHAR(100) NOT NULL COMMENT '角色名称',
    `remark`      VARCHAR(255) DEFAULT NULL COMMENT '备注',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

-- 3. 权限表（按钮/接口级权限码，经典 RBAC 的权限资源）
CREATE TABLE IF NOT EXISTS `sys_permission` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `code`        VARCHAR(100) NOT NULL COMMENT '权限编码，如 kb:manage / user:list',
    `name`        VARCHAR(50)  NOT NULL COMMENT '权限名称',
    `type`        TINYINT      NOT NULL DEFAULT 2 COMMENT '类型：1-菜单 2-按钮/API',
    `parent_id`   BIGINT       NOT NULL DEFAULT 0 COMMENT '父权限 ID（0 表示根）',
    `sort`        INT          NOT NULL DEFAULT 0 COMMENT '排序号',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_permission_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限表';

-- 4. 用户-角色关联表
CREATE TABLE IF NOT EXISTS `sys_user_role` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT,
    `user_id`     BIGINT   NOT NULL COMMENT '用户 ID',
    `role_id`     BIGINT   NOT NULL COMMENT '角色 ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_role` (`user_id`, `role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关联表';

-- 5. 角色-权限关联表
CREATE TABLE IF NOT EXISTS `sys_role_permission` (
    `id`            BIGINT   NOT NULL AUTO_INCREMENT,
    `role_id`       BIGINT   NOT NULL COMMENT '角色 ID',
    `permission_id` BIGINT   NOT NULL COMMENT '权限 ID',
    `create_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_permission` (`role_id`, `permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色权限关联表';

-- ============================================================
-- 种子数据（幂等，可重复执行）
-- ============================================================

-- 内置 ADMIN 角色（应用启动时 UserDataInitializer 也会自动补齐）
INSERT INTO `sys_role` (`code`, `name`, `remark`) VALUES ('ADMIN', '系统管理员', '可管理所有知识库')
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`);

-- 权限种子（应用启动时 UserDataInitializer 也会自动补齐）
INSERT INTO `sys_permission` (`code`, `name`, `type`, `parent_id`, `sort`) VALUES
('kb:manage', '知识库管理', 2, 0, 10),
('kb:upload', '文档上传', 2, 0, 20),
('kb:delete', '文档删除', 2, 0, 30),
('kb:query',  '知识库问答', 2, 0, 40),
('user:manage', '用户管理', 2, 0, 50),
('role:manage', '角色管理', 2, 0, 60)
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`);

-- ADMIN 角色绑定全部权限（幂等）
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `sys_role` r CROSS JOIN `sys_permission` p
WHERE r.code = 'ADMIN'
ON DUPLICATE KEY UPDATE `permission_id` = VALUES(`permission_id`);

-- 内置管理员账号（首次部署引导，密码 admin123，上线后请立即修改）
INSERT INTO `sys_user` (`username`, `password`, `nickname`, `status`)
VALUES ('admin', '$2b$12$5W5lVdCRZfzsNzb/9XN1keZFrv42O5EtGeFlne3HxqRhOpvVb/mDm', '系统管理员', 1)
ON DUPLICATE KEY UPDATE `username` = VALUES(`username`);

-- 绑定 admin -> ADMIN 角色（幂等）
INSERT INTO `sys_user_role` (`user_id`, `role_id`)
SELECT u.id, r.id FROM `sys_user` u CROSS JOIN `sys_role` r
WHERE u.username = 'admin' AND r.code = 'ADMIN'
ON DUPLICATE KEY UPDATE `user_id` = VALUES(`user_id`);
