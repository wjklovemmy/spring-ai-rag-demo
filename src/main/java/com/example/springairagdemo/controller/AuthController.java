package com.example.springairagdemo.controller;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 简易登录 / 登出 / 用户信息 API，供前端页面使用。
 * 当前为演示模式，任意用户名密码均可登录。
 */
@RestController
@Slf4j
@RequiredArgsConstructor
public class AuthController {

    private static final String SESSION_KEY = "user";

    @PostMapping("/api/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body,
                                                     HttpSession session) {
        String username = body.get("username");
        String password = body.get("password");

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "message", "用户名和密码不能为空"
            ));
        }

        session.setAttribute(SESSION_KEY, username);
        log.info("用户 {} 登录成功", username);
        return ResponseEntity.ok(Map.of("success", true, "username", username));
    }

    @GetMapping("/api/user")
    public ResponseEntity<Map<String, Object>> currentUser(HttpSession session) {
        String username = (String) session.getAttribute(SESSION_KEY);
        if (username == null) {
            return ResponseEntity.status(401).body(Map.of("success", false));
        }
        return ResponseEntity.ok(Map.of("success", true, "username", username));
    }

    @PostMapping("/api/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpSession session) {
        session.invalidate();
        return ResponseEntity.ok(Map.of("success", true));
    }
}
