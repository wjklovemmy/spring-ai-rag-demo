package com.example.user.feign;

import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * RAG 服务调用熔断降级工厂（OpenFeign + Sentinel Circuit Breaker，Hystrix 的官方替代）。
 * <p>
 * RAG 服务不可达/超时/熔断打开时返回安全兜底值：
 * <ul>
 *   <li>{@link RagSyncFeignClient#deletionCheck} —— 降级为校验失败（success=false），
 *       阻止删除用户（宁可拒绝也不允许破坏知识库归属关系）；若 RAG 侧返回 409 校验拒绝，
 *       则透传其 message（如"最后一个 OWNER 不可删除"），保证业务语义完整；</li>
 *   <li>{@link RagSyncFeignClient#userCleanup} / {@link RagSyncFeignClient#audit} ——
 *       降级为失败并记录告警日志，不影响用户删除主流程。</li>
 * </ul>
 */
@Slf4j
@Component
public class RagSyncFeignClientFallbackFactory implements FallbackFactory<RagSyncFeignClient> {

    @Override
    public RagSyncFeignClient create(Throwable cause) {
        log.warn("RAG 服务调用触发熔断降级: {}", cause == null ? "unknown" : cause.getMessage());
        return new RagSyncFeignClient() {
            @Override
            public Map<String, Object> deletionCheck(Map<String, Object> body) {
                return Map.of("success", false, "message", extractConflictMessage(cause));
            }

            @Override
            public Map<String, Object> userCleanup(Map<String, Object> body) {
                return Map.of("success", false);
            }

            @Override
            public Map<String, Object> audit(Map<String, Object> body) {
                return Map.of("success", false);
            }
        };
    }

    /**
     * RAG 删除前校验返回 409 时，响应体形如 {"success":false,"message":"..."}，
     * 解析并透传 message；其余异常统一按"知识库服务不可用"降级。
     */
    private String extractConflictMessage(Throwable cause) {
        if (cause instanceof FeignException fe && fe.status() == 409 && fe.responseBody().isPresent()) {
            String body = new String(fe.responseBody().get().array(), StandardCharsets.UTF_8);
            try {
                int idx = body.indexOf("\"message\"");
                if (idx >= 0) {
                    int colon = body.indexOf(':', idx);
                    int start = body.indexOf('"', colon + 1);
                    int end = body.indexOf('"', start + 1);
                    if (start >= 0 && end > start) {
                        return body.substring(start + 1, end);
                    }
                }
            } catch (Exception ignored) {
                // fall through：直接用原始响应体
            }
            return body;
        }
        return "知识库服务不可用，无法完成删除前校验";
    }
}
