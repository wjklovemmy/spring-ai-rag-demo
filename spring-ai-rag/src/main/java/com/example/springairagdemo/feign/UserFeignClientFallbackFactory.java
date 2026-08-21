package com.example.springairagdemo.feign;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 用户服务调用熔断降级工厂（OpenFeign + Sentinel Circuit Breaker，Hystrix 的官方替代）。
 * <p>
 * 用户服务不可达/超时/熔断打开时返回安全兜底值，保证 RAG 主流程不因用户服务故障中断：
 * <ul>
 *   <li>{@link UserFeignClient#isAdmin} 返回 false —— 拒绝授权更安全（非管理员不豁免数据权限）；</li>
 *   <li>{@link UserFeignClient#batch} 返回空列表 —— 创建人/成员昵称展示降级。</li>
 * </ul>
 */
@Slf4j
@Component
public class UserFeignClientFallbackFactory implements FallbackFactory<UserFeignClient> {

    @Override
    public UserFeignClient create(Throwable cause) {
        log.warn("用户服务调用触发熔断降级: {}", cause == null ? "unknown" : cause.getMessage());
        return new UserFeignClient() {
            @Override
            public Map<String, Object> isAdmin(Long userId) {
                return Map.of("success", false, "isAdmin", false);
            }

            @Override
            public Map<String, Object> batch(Map<String, Object> body) {
                return Map.of("success", false, "data", List.of());
            }
        };
    }
}
