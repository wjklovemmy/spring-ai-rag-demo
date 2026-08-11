package com.example.springairagdemo.controller;

import com.example.springairagdemo.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用户认证 API：注册、登录、登出、获取当前用户信息（JWT）
 */
@RestController
@Slf4j
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    /**
     * 用户注册
     */
    @PostMapping("/api/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        String nickname = body.getOrDefault("nickname", null);
        String email = body.getOrDefault("email", null);

        UserService.RegisterResult result = userService.register(username, password, nickname, email);

        if (result.success()) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", result.message(),
                    "token", result.token(),
                    "username", result.username(),
                    "userId", result.userId()
            ));
        }
        return ResponseEntity.badRequest().body(Map.of(
                "success", false, "message", result.message()
        ));
    }

    /**
     * 用户登录，返回 JWT Token
     */
    @PostMapping("/api/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        UserService.LoginResult result = userService.login(username, password);

        if (result.success()) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", result.message(),
                    "token", result.token(),
                    "username", result.username(),
                    "userId", result.userId()
            ));
        }
        return ResponseEntity.status(401).body(Map.of(
                "success", false, "message", result.message()
        ));
    }

    /**
     * 获取当前登录用户信息（从 JWT 过滤器注入的 Request 属性中读取）
     */
    @GetMapping("/api/user")
    public ResponseEntity<Map<String, Object>> currentUser(HttpServletRequest request) {
        String username = (String) request.getAttribute("username");
        Long userId = (Long) request.getAttribute("userId");
        if (username == null) {
            return ResponseEntity.status(401).body(Map.of("success", false));
        }
        return ResponseEntity.ok(Map.of(
                "success", true,
                "username", username,
                "userId", userId
        ));
    }

    /**
     * 登出（JWT 无状态，前端删除本地 Token 即可）
     */
    @PostMapping("/api/logout")
    public ResponseEntity<Map<String, Object>> logout() {
        return ResponseEntity.ok(Map.of("success", true, "message", "已登出"));
    }
}
