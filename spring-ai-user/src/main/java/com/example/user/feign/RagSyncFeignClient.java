package com.example.user.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * RAG 服务声明式客户端（spring-ai-user → spring-ai-rag 的跨进程同步）。
 * <p>
 * 调用 RAG 服务内部接口 {@code /internal/kb/**}（不经过网关），服务名 {@code spring-ai-rag}
 * 经 Nacos 注册中心解析实例并负载均衡。X-Internal-Token 头由 {@code FeignConfig} 的
 * 全局 RequestInterceptor 统一注入。远程不可用/超时/熔断时由
 * {@link RagSyncFeignClientFallbackFactory} 提供安全降级。
 */
@FeignClient(name = "spring-ai-rag", path = "/internal/kb",
        fallbackFactory = RagSyncFeignClientFallbackFactory.class)
public interface RagSyncFeignClient {

    /** 删除用户前的知识库归属校验：body { "userId": 1 }，RAG 侧校验不通过返回 409 + message */
    @PostMapping("/deletion-check")
    Map<String, Object> deletionCheck(@RequestBody Map<String, Object> body);

    /** 用户删除后的知识库关联清理（移除 kb_member 等） */
    @PostMapping("/user-cleanup")
    Map<String, Object> userCleanup(@RequestBody Map<String, Object> body);

    /** 管理员操作审计上报 */
    @PostMapping("/audit")
    Map<String, Object> audit(@RequestBody Map<String, Object> body);
}
