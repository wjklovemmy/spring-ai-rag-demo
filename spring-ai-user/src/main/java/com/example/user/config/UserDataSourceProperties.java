package com.example.user.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 用户域独立数据库配置（spring.datasource.user.*）。
 * <p>
 * spring-ai-user 使用独立数据库（spring_ai_user），与宿主应用（RAG）业务库物理隔离；
 * 该配置由 {@link UserDataSourceConfig} 绑定并构建用户域专属 DataSource。
 */
@Data
@ConfigurationProperties(prefix = "spring.datasource.user")
public class UserDataSourceProperties {

    /** JDBC 连接串，如 jdbc:mysql://localhost:3306/spring_ai_user */
    private String url;

    private String username;

    private String password;

    private String driverClassName = "com.mysql.cj.jdbc.Driver";
}
