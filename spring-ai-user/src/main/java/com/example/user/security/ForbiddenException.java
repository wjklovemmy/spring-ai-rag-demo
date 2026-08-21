package com.example.user.security;

/**
 * 越权/权限不足异常：由权限切面或服务层守卫抛出，全局异常处理器转为 403
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
