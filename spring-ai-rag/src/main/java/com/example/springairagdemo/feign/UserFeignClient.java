package com.example.springairagdemo.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * 用户服务声明式客户端（RAG → spring-ai-user 的跨进程查询）。
 * <p>
 * 调用用户服务内部接口 {@code /internal/users/**}（不经过网关），服务名 {@code spring-ai-user}
 * 经 Nacos 注册中心解析实例并负载均衡（lb:// 语义由 OpenFeign + LoadBalancer 承担）。
 * X-Internal-Token 头由 {@code FeignConfig} 的全局 RequestInterceptor 统一注入。
 * 远程不可用/超时/熔断时由 {@link UserFeignClientFallbackFactory} 提供安全降级。
 */
@FeignClient(name = "spring-ai-user", path = "/internal/users",
        fallbackFactory = UserFeignClientFallbackFactory.class)
public interface UserFeignClient {

    /** 指定用户是否为全局管理员（RAG 侧据此判定知识库管理员豁免） */
    @GetMapping("/{id}/is-admin")
    Map<String, Object> isAdmin(@PathVariable("id") Long userId);

    /** 批量查询用户摘要：body { "ids": [1,2,3] }，响应 data 为摘要列表 */
    @PostMapping("/batch")
    Map<String, Object> batch(@RequestBody Map<String, Object> body);
}
