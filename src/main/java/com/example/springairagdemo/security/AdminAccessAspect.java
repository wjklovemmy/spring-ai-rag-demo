package com.example.springairagdemo.security;

import com.example.springairagdemo.service.KbAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

/**
 * 管理员权限切面：拦截标注 {@link RequireAdmin} 的 Controller 方法，
 * 在执行前校验当前登录用户是否拥有 ADMIN 全局角色，否则抛出 {@link ForbiddenException}。
 */
@Aspect
@Component
@RequiredArgsConstructor
public class AdminAccessAspect {

    private final KbAuthorizationService kbAuthorizationService;

    @Before("@annotation(requireAdmin)")
    public void checkAdmin(JoinPoint joinPoint, RequireAdmin requireAdmin) {
        if (!kbAuthorizationService.isAdmin()) {
            throw new ForbiddenException("需要管理员权限才能执行该操作");
        }
    }
}
