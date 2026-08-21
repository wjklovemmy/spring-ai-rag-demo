package com.example.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.user.config.JwtUtil;
import com.example.user.entity.SysRoleEntity;
import com.example.user.entity.SysUserRoleEntity;
import com.example.user.entity.UserEntity;
import com.example.user.mapper.UserMapper;
import com.example.user.security.UserContext;
import com.example.user.spi.UserDeletionGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 用户服务（用户域）：
 * 注册、登录、双 Token 签发与刷新续期；全局角色（ADMIN）判定；
 * 用户删除时通过 {@link UserDeletionGuard} 扩展点联动业务模块（RAG）清理跨域数据。
 */
@Slf4j
@Service
public class UserService extends ServiceImpl<UserMapper, UserEntity> {

    /** 内置全局管理员角色编码 */
    public static final String ADMIN_ROLE_CODE = "ADMIN";

    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RedisRefreshTokenService refreshTokenService;
    private final SysRoleService sysRoleService;
    private final SysUserRoleService sysUserRoleService;
    private final List<UserDeletionGuard> deletionGuards;

    public UserService(PasswordEncoder passwordEncoder, JwtUtil jwtUtil,
                       RedisRefreshTokenService refreshTokenService,
                       SysRoleService sysRoleService,
                       SysUserRoleService sysUserRoleService,
                       List<UserDeletionGuard> deletionGuards) {
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.refreshTokenService = refreshTokenService;
        this.sysRoleService = sysRoleService;
        this.sysUserRoleService = sysUserRoleService;
        this.deletionGuards = deletionGuards;
    }

    /**
     * 用户注册，签发 Access + Refresh 双 Token
     * @return {success, message, token, refreshToken}
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

        TokenPair pair = issueTokenPair(user.getId(), username);
        return RegisterResult.ok(pair.accessToken(), pair.refreshToken(), username, user.getId());
    }

    /**
     * 用户登录（用户名 + 密码验证），签发 Access + Refresh 双 Token
     * @return {success, message, token, refreshToken}
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

        TokenPair pair = issueTokenPair(user.getId(), username);
        log.info("用户 {} 登录成功", username);
        return LoginResult.ok(pair.accessToken(), pair.refreshToken(), username, user.getId());
    }

    /**
     * 使用 Refresh Token 换取新的双 Token（Refresh Token 每次刷新都会轮换，被盗即失效）。
     * 只要用户在 Refresh Token 有效期内持续活跃，即可自动续期、无需重新登录。
     *
     * @param refreshToken 前端保存的 Refresh Token
     * @return 新双 Token；无效、已过期或已被撤销（登出）返回 fail
     */
    public LoginResult refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return LoginResult.fail("Refresh Token 不能为空");
        }
        if (!jwtUtil.validateRefreshToken(refreshToken)) {
            return LoginResult.fail("Refresh Token 无效或已过期，请重新登录");
        }
        Long userId = jwtUtil.getUserId(refreshToken);
        String username = jwtUtil.getUsername(refreshToken);
        String jti = jwtUtil.getJti(refreshToken);

        // 原子消费（GETDEL）：同一 Refresh Token 全局只能被成功消费一次，
        // 消除「检查存在 + 删除」两步之间的并发竞态；并发刷新 / 重放时只有一个请求成功。
        Long consumedUserId = refreshTokenService.consume(jti);
        if (consumedUserId == null) {
            // JWT 本身有效但 Redis 中已无记录：已被轮换 / 登出撤销 / 重放
            log.warn("Refresh Token 已被消费或重放，拒绝续期: userId={}", userId);
            return LoginResult.fail("Refresh Token 已被撤销，请重新登录");
        }

        // 轮换成功：旧 Refresh Token 已被原子消费，签发并保存新双 Token
        TokenPair pair = issueTokenPair(userId, username);
        log.info("用户 {} 通过 Refresh Token 续期成功", username);
        return LoginResult.ok(pair.accessToken(), pair.refreshToken(), username, userId);
    }

    /**
     * 登出：
     * <ol>
     *   <li>撤销该用户的<b>全部</b> Refresh Token（含刚轮换出的新 token，杜绝登出-刷新竞态）</li>
     *   <li>当前 Access Token 加入黑名单，立即失效（JWT 无状态的补充，TTL 到期自动清除）</li>
     * </ol>
     * 前端无论调用成功与否都应清理本地 Token 并跳转登录页。
     */
    public void logout(String refreshToken, String accessToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            try {
                if (jwtUtil.validateRefreshToken(refreshToken)) {
                    Long userId = jwtUtil.getUserId(refreshToken);
                    refreshTokenService.revokeAllByUserId(userId);
                    log.info("用户 {} 登出，已撤销其全部 Refresh Token", userId);
                }
            } catch (Exception e) {
                log.debug("登出撤销 Refresh Token 失败（忽略）: {}", e.getMessage());
            }
        }
        if (accessToken != null && !accessToken.isBlank()) {
            try {
                long ttl = jwtUtil.getRemainingTtlSeconds(accessToken);
                if (ttl > 0) {
                    refreshTokenService.blacklistAccessToken(accessToken, ttl);
                }
            } catch (Exception e) {
                log.debug("Access Token 加入黑名单失败（忽略）: {}", e.getMessage());
            }
        }
    }

    // ==================== 全局角色（垂直权限） ====================

    /** 当前登录用户是否为 ADMIN */
    public boolean isAdmin() {
        return isAdmin(UserContext.getUserId());
    }

    /** 指定用户是否为 ADMIN */
    public boolean isAdmin(Long userId) {
        if (userId == null) {
            return false;
        }
        List<Long> adminRoleIds = adminRoleIds();
        if (adminRoleIds.isEmpty()) {
            return false;
        }
        return sysUserRoleService.lambdaQuery()
                .eq(SysUserRoleEntity::getUserId, userId)
                .in(SysUserRoleEntity::getRoleId, adminRoleIds)
                .count() > 0;
    }

    /** ADMIN 角色 ID 集合 */
    public List<Long> adminRoleIds() {
        return sysRoleService.lambdaQuery()
                .eq(SysRoleEntity::getCode, ADMIN_ROLE_CODE)
                .list()
                .stream()
                .map(SysRoleEntity::getId)
                .toList();
    }

    /** 当前 ADMIN 用户总数（用于“不能删除/禁用最后一个管理员”保护） */
    public long countAdmins() {
        List<Long> adminRoleIds = adminRoleIds();
        if (adminRoleIds.isEmpty()) {
            return 0;
        }
        return sysUserRoleService.lambdaQuery()
                .in(SysUserRoleEntity::getRoleId, adminRoleIds)
                .count();
    }

    /**
     * 删除用户（联动业务模块清理）：
     * <ol>
     *   <li>依次调用各 {@link UserDeletionGuard#validateDeletion} 前置校验（不满足则抛异常阻止删除）</li>
     *   <li>清理功能角色关联（sys_user_role）</li>
     *   <li>删除用户本体</li>
     *   <li>依次调用各 {@link UserDeletionGuard#onUserDeleted} 清理跨域数据（如知识库成员授权）</li>
     * </ol>
     */
    public void deleteUser(Long userId) {
        for (UserDeletionGuard guard : deletionGuards) {
            guard.validateDeletion(userId);
        }
        sysUserRoleService.remove(new LambdaQueryWrapper<SysUserRoleEntity>()
                .eq(SysUserRoleEntity::getUserId, userId));
        removeById(userId);
        for (UserDeletionGuard guard : deletionGuards) {
            guard.onUserDeleted(userId);
        }
        log.info("用户已删除: userId={}", userId);
    }

    /**
     * 生成一组新的 Access + Refresh Token，并将 Refresh Token 存入 Redis（登录 / 注册 / 续期时调用）
     */
    private TokenPair issueTokenPair(Long userId, String username) {
        TokenPair pair = new TokenPair(
                jwtUtil.generateAccessToken(userId, username),
                jwtUtil.generateRefreshToken(userId, username)
        );
        refreshTokenService.saveToken(userId, jwtUtil.getJti(pair.refreshToken()),
                jwtUtil.getRemainingTtlSeconds(pair.refreshToken()));
        return pair;
    }

    private record TokenPair(String accessToken, String refreshToken) {
    }

    // ---- 内部 Result 类 ----

    public record RegisterResult(boolean success, String message, String token,
                                 String refreshToken, String username, Long userId) {
        public static RegisterResult ok(String token, String refreshToken, String username, Long userId) {
            return new RegisterResult(true, "注册成功", token, refreshToken, username, userId);
        }
        public static RegisterResult fail(String message) {
            return new RegisterResult(false, message, null, null, null, null);
        }
    }

    public record LoginResult(boolean success, String message, String token,
                              String refreshToken, String username, Long userId) {
        public static LoginResult ok(String token, String refreshToken, String username, Long userId) {
            return new LoginResult(true, "登录成功", token, refreshToken, username, userId);
        }
        public static LoginResult fail(String message) {
            return new LoginResult(false, message, null, null, null, null);
        }
    }
}
