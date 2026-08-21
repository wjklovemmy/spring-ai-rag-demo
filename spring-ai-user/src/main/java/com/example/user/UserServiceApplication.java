package com.example.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 用户服务（spring-ai-user）独立启动类。
 * <p>
 * 用户域作为独立服务部署（默认端口 8082），提供：
 * <ul>
 *   <li>认证：登录 / 注册 / 刷新 / 登出（签发 JWT）</li>
 *   <li>用户管理 / 角色管理（仅 ADMIN）</li>
 *   <li>内部接口 /internal/users/**：供 RAG 服务远程判定 isAdmin、批量查询用户摘要</li>
 * </ul>
 * 请求统一经 Gateway（8081）转发进入，本服务通过 GatewayIdentityFilter 消费身份头；
 * 删除用户时的知识库校验与清理经 RagSyncClient 反向回调 RAG 服务的 /internal/kb/** 接口。
 * <p>
 * 用户域独立数据库（spring_ai_user，标准 spring.datasource.* 主数据源）由 MyBatis-Plus 自动装配，
 * Mapper 通过 {@code @MapperScan} 绑定到默认 SqlSessionTemplate。
 */
@SpringBootApplication
@MapperScan(basePackages = "com.example.user.mapper")
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
