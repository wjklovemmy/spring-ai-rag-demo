package com.example.springairagdemo.service;

import com.example.springairagdemo.feign.UserFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 用户服务客户端（RAG → spring-ai-user 的跨进程查询，OpenFeign 声明式调用）：
 * <ul>
 *   <li>{@link #isAdmin}：指定用户是否为全局管理员（知识库管理员豁免判定）</li>
 *   <li>{@link #findUsers} / {@link #getById}：批量/单个用户摘要（知识库创建人、成员展示）</li>
 * </ul>
 * 底层委托 {@link UserFeignClient} 调用用户服务内部接口 {@code /internal/users/**}
 * （不经过网关，服务名经 Nacos 注册中心解析实例 + 负载均衡）。
 * 熔断降级（OpenFeign fallbackFactory + Sentinel，Hystrix 的官方替代）：
 * 用户服务不可用/超时/熔断打开时 isAdmin 返回 false（拒绝授权更安全）、用户摘要返回空（展示降级）。
 * 熔断规则声明式配置于 application.yaml 的 feign.sentinel.rules（资源名 spring-ai-user#方法）。
 */
@Slf4j
@Service
public class UserClient {

    /** 用户摘要（跨服务最小信息，替代拆分前的 UserEntity 直接依赖） */
    public record UserBrief(Long id, String username, String nickname) {
    }

    private final UserFeignClient feignClient;

    public UserClient(UserFeignClient feignClient) {
        this.feignClient = feignClient;
    }

    /** 指定用户是否为全局管理员（远程判定；熔断降级时返回 false，拒绝授权更安全） */
    public boolean isAdmin(Long userId) {
        if (userId == null) {
            return false;
        }
        Map<String, Object> resp = feignClient.isAdmin(userId);
        return resp != null && Boolean.TRUE.equals(resp.get("isAdmin"));
    }

    /** 批量查询用户摘要，返回 id -> UserBrief；远程熔断降级时返回空 Map（列表展示降级） */
    public Map<Long, UserBrief> findUsers(Collection<Long> ids) {
        List<Long> distinct = ids == null
                ? List.of()
                : ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> resp = feignClient.batch(Map.of("ids", distinct));
        Map<Long, UserBrief> result = new HashMap<>();
        if (resp != null && resp.get("data") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("id") instanceof Number id) {
                    Object usernameObj = m.get("username");
                    Object nicknameObj = m.get("nickname");
                    String username = usernameObj == null ? "" : String.valueOf(usernameObj);
                    String nickname = nicknameObj == null ? "" : String.valueOf(nicknameObj);
                    result.put(id.longValue(), new UserBrief(id.longValue(), username, nickname));
                }
            }
        }
        return result;
    }

    /** 查询单个用户，不存在或远程熔断降级时返回 null */
    public UserBrief getById(Long id) {
        if (id == null) {
            return null;
        }
        return findUsers(List.of(id)).get(id);
    }
}
