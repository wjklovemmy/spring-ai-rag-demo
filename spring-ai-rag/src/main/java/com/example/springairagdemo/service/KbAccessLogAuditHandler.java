package com.example.springairagdemo.service;

import com.example.user.spi.UserAdminAuditHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 用户域管理操作审计扩展点（RAG 侧实现）：
 * 将用户/角色管理的关键操作（创建、启禁用、重置密码、删除、分配角色等）落库到
 * kb_access_log，操作者取自当前请求的 {@link com.example.user.security.UserContext}。
 */
@Component
@RequiredArgsConstructor
public class KbAccessLogAuditHandler implements UserAdminAuditHandler {

    private final KbAuthorizationService kbAuthorizationService;

    @Override
    public void audit(String action, String detail) {
        kbAuthorizationService.audit(action, null, null, detail);
    }
}
