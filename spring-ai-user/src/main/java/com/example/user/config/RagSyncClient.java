package com.example.user.config;

import com.example.user.security.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

/**
 * RAG 服务同步客户端（用户服务 → RAG 的跨进程协作）。
 * <p>
 * 原同进程 SPI（UserDeletionGuard / UserAdminAuditHandler）在服务拆分后不再适用，
 * 改为通过 HTTP 回调 RAG 服务的 {@code /internal/kb/**} 内部接口：
 * <ul>
 *   <li>{@link #validateDeletion}：删除用户前校验（如知识库最后一个 OWNER 不允许删除）</li>
 *   <li>{@link #onUserDeleted}：删除用户后清理知识库授权数据（kb_member）</li>
 *   <li>{@link #audit}：管理操作审计上报（落库 kb_access_log）</li>
 * </ul>
 * 内部接口不经过网关，携带 X-Internal-Token 头校验，与 RAG 侧 {@code internal-token} 一致。
 */
@Slf4j
@Component
public class RagSyncClient {

    private final RestClient restClient;
    private final String internalToken;

    public RagSyncClient(@Value("${rag.internal-url}") String baseUrl,
                         @Value("${internal-token}") String internalToken,
                         RestClient.Builder loadBalancedRestClientBuilder) {
        // 注入 @LoadBalanced builder：baseUrl 支持 lb://spring-ai-rag，经 Nacos 注册中心解析实例
        this.restClient = loadBalancedRestClientBuilder.baseUrl(baseUrl).build();
        this.internalToken = internalToken;
    }

    /**
     * 删除用户前校验。校验不通过或 RAG 服务不可用时抛出异常，阻止删除（保证数据一致性）。
     */
    public void validateDeletion(Long userId) {
        try {
            Map<String, Object> resp = restClient.post()
                    .uri("/internal/kb/deletion-check")
                    .header("X-Internal-Token", internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("userId", userId))
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<>() {
                    });
            if (resp == null || !Boolean.TRUE.equals(resp.get("success"))) {
                String message = resp == null
                        ? "知识库服务无响应"
                        : String.valueOf(resp.getOrDefault("message", "知识库删除前校验失败"));
                throw new IllegalStateException(message);
            }
        } catch (Exception e) {
            if (e instanceof IllegalStateException) {
                throw e;
            }
            log.warn("知识库删除前校验调用失败: {}", e.getMessage());
            throw new IllegalStateException("知识库服务不可用，无法完成删除前校验");
        }
    }

    /**
     * 删除用户后清理知识库授权（kb_member）。失败仅记录告警，不阻塞用户删除主流程。
     */
    public void onUserDeleted(Long userId) {
        try {
            Map<String, Object> resp = restClient.post()
                    .uri("/internal/kb/user-cleanup")
                    .header("X-Internal-Token", internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("userId", userId))
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<>() {
                    });
            if (resp == null || !Boolean.TRUE.equals(resp.get("success"))) {
                log.warn("知识库清理返回异常: {}", resp);
            }
        } catch (Exception e) {
            log.warn("知识库清理调用失败: {}", e.getMessage());
        }
    }

    /**
     * 管理操作审计上报（落库 kb_access_log）。操作者信息由用户服务侧 UserContext 提供。
     * 失败仅记录告警，不阻塞管理操作。
     */
    public void audit(String action, String detail) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("action", action);
            body.put("detail", detail);
            body.put("operatorId", UserContext.getUserId());
            body.put("operatorName", UserContext.getUsername() == null ? "" : UserContext.getUsername());
            body.put("operatorIp", UserContext.clientIp() == null ? "" : UserContext.clientIp());
            Map<String, Object> resp = restClient.post()
                    .uri("/internal/kb/audit")
                    .header("X-Internal-Token", internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<>() {
                    });
            if (resp == null || !Boolean.TRUE.equals(resp.get("success"))) {
                log.warn("审计上报返回异常: {}", resp);
            }
        } catch (Exception e) {
            log.warn("审计上报调用失败: {}", e.getMessage());
        }
    }
}
