package com.example.user.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenFeign 全局配置：为用户服务所有 Feign 客户端统一注入 X-Internal-Token 头，
 * 目标服务（spring-ai-rag）的 /internal/** 接口据此校验调用方身份，
 * 取值与两端 internal-token 配置保持一致。
 */
@Configuration
public class FeignConfig {

    @Bean
    public RequestInterceptor internalTokenRequestInterceptor(@Value("${internal-token}") String internalToken) {
        return template -> template.header("X-Internal-Token", internalToken);
    }
}
