package com.example.user.spi;

/**
 * 用户域操作审计扩展点（SPI）：
 * 用户/角色管理的关键操作（创建、启禁用、重置密码、删除、分配角色等）通过该接口上报，
 * 由宿主应用（RAG）实现审计落库（kb_access_log），用户模块不依赖审计实现细节。
 */
public interface UserAdminAuditHandler {

    /**
     * 记录一条用户域管理操作审计。
     *
     * @param action 操作类型（如 USER_CREATE / USER_DELETE / ROLE_ASSIGN）
     * @param detail 操作描述
     */
    void audit(String action, String detail);
}
