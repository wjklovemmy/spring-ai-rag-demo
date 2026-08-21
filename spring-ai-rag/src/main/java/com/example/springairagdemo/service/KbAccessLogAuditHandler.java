package com.example.springairagdemo.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 管理操作审计落库（RAG 侧）：
 * 将用户/角色管理的关键操作（创建、启禁用、重置密码、删除、分配角色等）落库到
 * kb_access_log。用户服务独立部署后，经本服务 {@code /internal/kb/audit} 内部接口
 * 远程上报，操作者由调用方（用户服务）显式传入（替代拆分前的 UserContext 直接读取）。
 */
@Component
@RequiredArgsConstructor
public class KbAccessLogAuditHandler {

    private final KbAuthorizationService kbAuthorizationService;

    /** 当前请求上下文操作者的审计（本服务内部使用，操作者取自 UserContext） */
    public void audit(String action, String detail) {
        kbAuthorizationService.audit(action, null, null, detail);
    }

    /** 显式操作者的审计（用户服务远程上报时使用，本服务 UserContext 为空） */
    public void audit(String action, String detail,
                      Long operatorId, String operatorName, String operatorIp) {
        kbAuthorizationService.auditAs(action, null, null, detail,
                operatorId, operatorName, operatorIp);
    }
}
