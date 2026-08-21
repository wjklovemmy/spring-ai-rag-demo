package com.example.user.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 负载均衡的 RestClient.Builder：
 * 使 {@code lb://service-name} 前缀的 baseUrl 可经 Spring Cloud LoadBalancer
 * 从 Nacos 注册中心解析实例（用于跨服务内部调用 RagSyncClient）。
 */
@Configuration
public class LoadBalancedRestClientConfig {

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }
}
