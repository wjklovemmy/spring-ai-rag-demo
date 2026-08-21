package com.example.springairagdemo.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 用户服务客户端（RAG → spring-ai-user 的跨进程查询，替代拆分前的同进程依赖）：
 * <ul>
 *   <li>{@link #isAdmin}：指定用户是否为全局管理员（知识库管理员豁免判定）</li>
 *   <li>{@link #findUsers} / {@link #getById}：批量/单个用户摘要（知识库创建人、成员展示）</li>
 * </ul>
 * 调用用户服务内部接口 {@code /internal/users/**}（不经过网关），携带 X-Internal-Token 校验，
 * 与用户服务侧 {@code internal-token} 一致。远程不可用时按安全降级：isAdmin 返回 false（拒绝授权）、
 * 用户摘要返回空（展示降级），并记录告警日志。
 */
@Slf4j
@Service
public class UserClient {

    /** 用户摘要（跨服务最小信息，替代拆分前的 UserEntity 直接依赖） */
    public record UserBrief(Long id, String username, String nickname) {
    }

    private final RestClient restClient;
    private final String internalToken;

    public UserClient(@Value("${user-service.internal-url}") String baseUrl,
                      @Value("${internal-token}") String internalToken) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.internalToken = internalToken;
    }

    /** 指定用户是否为全局管理员（远程判定；服务不可用或异常时返回 false，拒绝授权更安全） */
    public boolean isAdmin(Long userId) {
        if (userId == null) {
            return false;
        }
        try {
            Map<String, Object> resp = restClient.get()
                    .uri("/internal/users/{id}/is-admin", userId)
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return resp != null && Boolean.TRUE.equals(resp.get("isAdmin"));
        } catch (Exception e) {
            log.warn("用户服务 isAdmin 查询失败: {}", e.getMessage());
            return false;
        }
    }

    /** 批量查询用户摘要，返回 id -> UserBrief；远程不可用时返回空 Map（列表展示降级） */
    public Map<Long, UserBrief> findUsers(Collection<Long> ids) {
        List<Long> distinct = ids == null
                ? List.of()
                : ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, Object> resp = restClient.post()
                    .uri("/internal/users/batch")
                    .header("X-Internal-Token", internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("ids", distinct))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
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
        } catch (Exception e) {
            log.warn("用户服务批量查询失败: {}", e.getMessage());
            return Map.of();
        }
    }

    /** 查询单个用户，不存在或远程不可用时返回 null */
    public UserBrief getById(Long id) {
        if (id == null) {
            return null;
        }
        return findUsers(List.of(id)).get(id);
    }
}
