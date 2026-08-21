package com.example.user.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 注册网关信任过滤器并配置 BCrypt。
 * 说明：JWT 认证已上移到 Gateway（JwtAuthGlobalFilter），
 * 本服务通过 {@link GatewayIdentityFilter} 消费网关注入的身份，不再自行解析 Token。
 */
@Configuration
public class JwtConfig {

    private final GatewayIdentityFilter gatewayIdentityFilter;

    public JwtConfig(GatewayIdentityFilter gatewayIdentityFilter) {
        this.gatewayIdentityFilter = gatewayIdentityFilter;
    }

    /**
     * 注册网关信任过滤器，拦截 /api/* 路径
     */
    @Bean
    public FilterRegistrationBean<GatewayIdentityFilter> gatewayIdentityFilterRegistration() {
        FilterRegistrationBean<GatewayIdentityFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(gatewayIdentityFilter);
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        return registration;
    }

    /**
     * BCrypt 密码编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
