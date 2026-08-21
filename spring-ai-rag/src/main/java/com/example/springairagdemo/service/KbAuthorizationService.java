package com.example.springairagdemo.service;

import com.example.springairagdemo.entity.KbAccessLogEntity;
import com.example.springairagdemo.entity.KbMemberEntity;
import com.example.springairagdemo.security.KbRole;
import com.example.springairagdemo.security.ForbiddenException;
import com.example.springairagdemo.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库授权核心服务（RAG 业务域）：
 * <ul>
 *   <li>垂直权限：全局角色（ADMIN 可管理一切知识库），判定经 {@link UserClient} 远程委托用户服务</li>
 *   <li>水平/数据权限：kb_member 显式授权（OWNER / EDITOR / VIEWER），唯一权威</li>
 *   <li>安全审计：关键操作与越权拒绝（ACCESS_DENIED）落库</li>
 * </ul>
 * 所有「用户对某知识库是否有权限」的判定都必须经过本服务，禁止在业务代码中自行推断。
 * 用户域由独立服务 spring-ai-user（8082）承担：ADMIN 判定与用户摘要经 /internal/users/** 远程查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbAuthorizationService {

    /** 内置全局管理员角色编码（与用户服务 sys_role.code 的 ADMIN 一致） */
    public static final String ADMIN_ROLE_CODE = "ADMIN";

    private final KbMemberService kbMemberService;
    private final KbAccessLogService kbAccessLogService;
    private final UserClient userClient;

    // ==================== 全局角色（垂直权限） ====================

    /** 当前登录用户是否为 ADMIN（远程委托用户服务判定） */
    public boolean isAdmin() {
        return userClient.isAdmin(UserContext.getUserId());
    }

    /** 指定用户是否为 ADMIN（远程委托用户服务判定） */
    public boolean isAdmin(Long userId) {
        return userClient.isAdmin(userId);
    }

    // ==================== 数据权限判定（水平/对象级） ====================

    /** 指定用户对指定知识库的角色；无任何授权返回 null */
    public KbRole roleOf(Long userId, Long kbId) {
        if (userId == null || kbId == null) {
            return null;
        }
        if (isAdmin(userId)) {
            return KbRole.OWNER;
        }
        KbMemberEntity member = kbMemberService.lambdaQuery()
                .eq(KbMemberEntity::getKbId, kbId)
                .eq(KbMemberEntity::getUserId, userId)
                .one();
        return member == null ? null : KbRole.fromString(member.getRole());
    }

    /** 当前用户对指定知识库是否满足所需最低角色 */
    public boolean canAccess(Long kbId, KbRole required) {
        return canAccess(UserContext.getUserId(), kbId, required);
    }

    /** 指定用户对指定知识库是否满足所需最低角色 */
    public boolean canAccess(Long userId, Long kbId, KbRole required) {
        KbRole role = roleOf(userId, kbId);
        return role != null && role.satisfies(required);
    }

    /**
     * 断言当前用户对指定知识库满足所需最低角色；不满足时记录审计并抛 {@link ForbiddenException}。
     * 这是 Controller 注解之外的第二道防线（服务层兜底，防绕过非标准入口）。
     */
    public void assertRole(Long kbId, KbRole required) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new ForbiddenException("未登录，无法访问知识库");
        }
        if (!canAccess(userId, kbId, required)) {
            KbRole actual = roleOf(userId, kbId);
            audit("ACCESS_DENIED", kbId, null,
                    "用户 " + UserContext.getUsername() + " 尝试执行需要 " + required.name()
                            + " 的操作，实际权限=" + (actual == null ? "无权限" : actual.name()));
            log.warn("越权拦截: userId={}, kbId={}, required={}, actual={}, uri={}",
                    userId, kbId, required, actual,
                    UserContext.clientIp());
            throw new ForbiddenException("无权访问该知识库（需要 " + required.label() + " 及以上权限）");
        }
    }

    /** 当前用户可见的知识库 ID 集合（ADMIN 返回 null 表示全部可见） */
    public List<Long> visibleKbIds() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return Collections.emptyList();
        }
        if (isAdmin(userId)) {
            return null;
        }
        return kbMemberService.lambdaQuery()
                .eq(KbMemberEntity::getUserId, userId)
                .list()
                .stream()
                .map(KbMemberEntity::getKbId)
                .distinct()
                .collect(Collectors.toList());
    }

    // ==================== 成员管理（授权） ====================

    /**
     * 授权/更新成员角色。调用前必须由调用方完成 OWNER/ADMIN 校验。
     */
    public void grant(Long kbId, Long userId, KbRole role, Long grantUserId) {
        if (kbId == null || userId == null || role == null) {
            throw new IllegalArgumentException("授权参数不完整");
        }
        KbMemberEntity existing = kbMemberService.lambdaQuery()
                .eq(KbMemberEntity::getKbId, kbId)
                .eq(KbMemberEntity::getUserId, userId)
                .one();
        Date now = new Date();
        if (existing == null) {
            KbMemberEntity member = new KbMemberEntity();
            member.setKbId(kbId);
            member.setUserId(userId);
            member.setRole(role.name());
            member.setGrantUser(grantUserId);
            member.setCreateTime(now);
            kbMemberService.save(member);
        } else {
            existing.setRole(role.name());
            existing.setUpdateTime(now);
            kbMemberService.updateById(existing);
        }
        audit("GRANT", kbId, null,
                "授权用户 " + userId + " 为 " + role.name());
    }

    /** 移除成员；若被移除者是最后一个 OWNER 则拒绝 */
    public void revoke(Long kbId, Long userId) {
        KbMemberEntity existing = kbMemberService.lambdaQuery()
                .eq(KbMemberEntity::getKbId, kbId)
                .eq(KbMemberEntity::getUserId, userId)
                .one();
        if (existing == null) {
            throw new ForbiddenException("该用户不是本知识库成员");
        }
        if ("OWNER".equals(existing.getRole()) && isLastOwner(kbId, userId)) {
            throw new ForbiddenException("不能移除最后一个所有者，请先转移所有权");
        }
        kbMemberService.removeById(existing.getId());
        audit("REVOKE", kbId, null, "移除成员 " + userId);
    }

    /** 是否最后一个 OWNER（ownerCount &lt;= 1 即视为最后一个，防止系统失去所有者） */
    public boolean isLastOwner(Long kbId, Long userId) {
        long ownerCount = kbMemberService.lambdaQuery()
                .eq(KbMemberEntity::getKbId, kbId)
                .eq(KbMemberEntity::getRole, "OWNER")
                .count();
        return ownerCount <= 1;
    }

    /** 知识库成员列表 */
    public List<KbMemberEntity> members(Long kbId) {
        return kbMemberService.lambdaQuery()
                .eq(KbMemberEntity::getKbId, kbId)
                .list();
    }

    // ==================== 审计 ====================

    /** 当前请求上下文的审计（操作者取自 UserContext） */
    public void audit(String action, Long kbId, Long documentId, String detail) {
        auditAs(action, kbId, documentId, detail,
                UserContext.getUserId(), UserContext.getUsername(), UserContext.clientIp());
    }

    /**
     * 显式操作者的审计：供用户服务经 {@code /internal/kb/audit} 远程上报管理操作
     * （此时本服务 UserContext 为空，操作者由调用方显式传入）。
     */
    public void auditAs(String action, Long kbId, Long documentId, String detail,
                        Long operatorId, String operatorName, String operatorIp) {
        KbAccessLogEntity logEntity = new KbAccessLogEntity();
        logEntity.setUserId(operatorId);
        logEntity.setUsername(operatorName);
        logEntity.setAction(action);
        logEntity.setKbId(kbId);
        logEntity.setDocumentId(documentId);
        logEntity.setIp(operatorIp);
        logEntity.setDetail(detail == null || detail.length() <= 500 ? detail : detail.substring(0, 500));
        logEntity.setCreateTime(new Date());
        try {
            kbAccessLogService.save(logEntity);
        } catch (Exception e) {
            log.warn("审计日志写入失败: {}", e.getMessage());
        }
    }
}
