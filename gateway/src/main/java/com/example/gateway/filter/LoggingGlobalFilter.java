package com.example.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局日志过滤器：打印每个请求的方法、路径、客户端 IP 与响应耗时，便于排查链路问题。
 */
@Component
public class LoggingGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(LoggingGlobalFilter.class);
    private static final AtomicLong REQUEST_ID = new AtomicLong();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long requestId = REQUEST_ID.incrementAndGet();
        ServerHttpRequest request = exchange.getRequest();
        long start = System.currentTimeMillis();

        return chain.filter(exchange)
                .doOnSuccess(v -> log.info("[{}] {} {} -> {}ms status={}",
                        requestId, request.getMethod(), request.getPath(),
                        System.currentTimeMillis() - start, exchange.getResponse().getStatusCode()))
                .doOnError(e -> log.error("[{}] {} {} -> {}ms error={}",
                        requestId, request.getMethod(), request.getPath(),
                        System.currentTimeMillis() - start, e.getMessage()));
    }

    /** 优先级：高于默认过滤器，保证最早记录 */
    @Override
    public int getOrder() {
        return -100;
    }
}
