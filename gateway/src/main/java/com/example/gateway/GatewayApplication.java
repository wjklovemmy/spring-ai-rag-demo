package com.example.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Mono;

@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    /**
     * 限流 KeyResolver：按客户端 IP 维度限流。
     * 与 application.yaml 中 RequestRateLimiter 过滤器的 key-resolver: "#{@clientIpKeyResolver}" 配合使用。
     */
    @Bean
    public KeyResolver clientIpKeyResolver() {
        return exchange -> Mono.just(
                exchange.getRequest().getRemoteAddress() != null
                        ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                        : "unknown");
    }
}
