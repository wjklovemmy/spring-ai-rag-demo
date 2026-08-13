package com.example.springairagdemo.config;

import com.example.springairagdemo.security.ForbiddenException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常处理：统一错误响应结构 {success, code, message}
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 越权/权限不足 → 403 */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(ForbiddenException e) {
        log.warn("权限拒绝: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "success", false,
                "code", 403,
                "message", e.getMessage()
        ));
    }

    /** 参数错误 → 400 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "code", 400,
                "message", e.getMessage() == null ? "参数错误" : e.getMessage()
        ));
    }

    /** 兜底 → 500 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        log.error("系统异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "success", false,
                "code", 500,
                "message", "系统繁忙，请稍后重试"
        ));
    }
}
