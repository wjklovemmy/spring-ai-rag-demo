package com.example.springairagdemo.config;

import com.example.user.security.ForbiddenException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    /** 静态资源/接口不存在 → 404（不打 ERROR，避免刷日志） */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "success", false,
                "code", 404,
                "message", "资源不存在"
        ));
    }

    /** 唯一键冲突 → 409（兜底并发窗口下的重名/重复提交） */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一键冲突: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "success", false,
                "code", 409,
                "message", "数据已存在，请勿重复创建"
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
