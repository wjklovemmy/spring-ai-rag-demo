package com.example.user.config;

import com.example.user.feign.RagSyncFeignClient;
import com.example.user.security.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * RAG 服务同步客户端（用户服务 → RAG 的跨进程协作，OpenFeign 声明式调用）。
 * <p>
 * 原同进程 SPI（UserDeletionGuard / UserAdminAuditHandler）在服务拆分后不再适用，
 * 改为通过 HTTP 回调 RAG 服务的 {@code /internal/kb/**} 内部接口：
 * <ul>
 *   <li>{@link #validateDeletion}：删除用户前校验（如知识库最后一个 OWNER 不允许删除）</li>
 *   <li>{@link #onUserDeleted}：删除用户后清理知识库授权数据（kb_member）</li>
 *   <li>{@link #audit}：管理操作审计上报（落库 kb_access_log）</li>
 * </ul>
 * 底层委托 {@link RagSyncFeignClient}（服务名经 Nacos 注册中心解析实例 + 负载均衡），
 * 内部接口不经过网关，X-Internal-Token 头由 FeignConfig 全局拦截器统一注入。
 * 熔断降级（OpenFeign fallbackFactory + Sentinel，Hystrix 的官方替代）：
 * 删除前校验降级为失败并阻止删除（宁可拒绝也不破坏归属关系，RAG 侧 409 语义透传）；
 * 清理/审计降级为失败仅记录告警，不阻塞用户删除主流程。
 * 熔断规则声明式配置于 application.yaml 的 feign.sentinel.rules（资源名 spring-ai-rag#方法）。
 */
@Slf4j
@Component
public class RagSyncClient {

    private final RagSyncFeignClient feignClient;

    public RagSyncClient(RagSyncFeignClient feignClient) {
        this.feignClient = feignClient;
    }

    /**
     * 删除用户前校验。校验不通过或 RAG 服务不可用（熔断降级）时抛出异常，阻止删除（保证数据一致性）。
     */
    public void validateDeletion(Long userId) {
        Map<String, Object> resp = feignClient.deletionCheck(Map.of("userId", userId));
        if (resp == null || !Boolean.TRUE.equals(resp.get("success"))) {
            String message = resp == null
                    ? "知识库服务无响应"
                    : String.valueOf(resp.getOrDefault("message", "知识库删除前校验失败"));
            throw new IllegalStateException(message);
        }
    }

    /**
     * 删除用户后清理知识库授权（kb_member）。失败仅记录告警，不阻塞用户删除主流程。
     */
    public void onUserDeleted(Long userId) {
        Map<String, Object> resp = feignClient.userCleanup(Map.of("userId", userId));
        if (resp == null || !Boolean.TRUE.equals(resp.get("success"))) {
            log.warn("知识库清理返回异常: {}", resp);
        }
    }

    /**
     * 管理操作审计上报（落库 kb_access_log）。操作者信息由用户服务侧 UserContext 提供。
     * 失败仅记录告警，不阻塞管理操作。
     */
    public void audit(String action, String detail) {
        Map<String, Object> body = new HashMap<>();
        body.put("action", action);
        body.put("detail", detail);
        body.put("operatorId", UserContext.getUserId());
        body.put("operatorName", UserContext.getUsername() == null ? "" : UserContext.getUsername());
        body.put("operatorIp", UserContext.clientIp() == null ? "" : UserContext.clientIp());
        Map<String, Object> resp = feignClient.audit(body);
        if (resp == null || !Boolean.TRUE.equals(resp.get("success"))) {
            log.warn("审计上报返回异常: {}", resp);
        }
    }
}
