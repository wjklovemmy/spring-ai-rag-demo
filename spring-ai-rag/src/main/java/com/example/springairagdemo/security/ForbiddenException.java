package com.example.springairagdemo.security;

/**
 * 403 权限不足异常（RAG 本地版，替代拆分前用户域的同名类）。
 * 由全局异常处理器统一转为 HTTP 403 响应。
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
