package com.example.springairagdemo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.springairagdemo.config.JwtUtil;
import com.example.springairagdemo.entity.UserEntity;
import com.example.springairagdemo.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * 用户服务：注册、登录、JWT 签发
 */
@Slf4j
@Service
public class UserService extends ServiceImpl<UserMapper, UserEntity> {

    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public UserService(PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    /**
     * 用户注册
     * @return {success, message, token}
     */
    public RegisterResult register(String username, String password, String nickname, String email) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return RegisterResult.fail("用户名和密码不能为空");
        }
        if (password.length() < 6) {
            return RegisterResult.fail("密码至少需要 6 位");
        }

        // 检查用户名是否已存在
        long count = count(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, username));
        if (count > 0) {
            return RegisterResult.fail("用户名已被注册");
        }

        // 创建用户
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(nickname != null && !nickname.isBlank() ? nickname : username);
        user.setEmail(email);
        user.setStatus(1);
        user.setCreateTime(new Date());
        user.setUpdateTime(new Date());

        save(user);
        log.info("用户注册成功: {} (id={})", username, user.getId());

        String token = jwtUtil.generateToken(user.getId(), username);
        return RegisterResult.ok(token, username, user.getId());
    }

    /**
     * 用户登录（用户名 + 密码验证）
     * @return {success, message, token}
     */
    public LoginResult login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return LoginResult.fail("用户名和密码不能为空");
        }

        UserEntity user = getOne(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, username));
        if (user == null) {
            return LoginResult.fail("用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            return LoginResult.fail("账号已被禁用");
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            return LoginResult.fail("用户名或密码错误");
        }

        String token = jwtUtil.generateToken(user.getId(), username);
        log.info("用户 {} 登录成功", username);
        return LoginResult.ok(token, username, user.getId());
    }

    // ---- 内部 Result 类 ----

    public record RegisterResult(boolean success, String message, String token,
                                  String username, Long userId) {
        public static RegisterResult ok(String token, String username, Long userId) {
            return new RegisterResult(true, "注册成功", token, username, userId);
        }
        public static RegisterResult fail(String message) {
            return new RegisterResult(false, message, null, null, null);
        }
    }

    public record LoginResult(boolean success, String message, String token,
                               String username, Long userId) {
        public static LoginResult ok(String token, String username, Long userId) {
            return new LoginResult(true, "登录成功", token, username, userId);
        }
        public static LoginResult fail(String message) {
            return new LoginResult(false, message, null, null, null);
        }
    }
}
