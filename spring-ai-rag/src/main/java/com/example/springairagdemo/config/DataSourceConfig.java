package com.example.springairagdemo.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

/**
 * 自定义数据库连接池配置：
 * <ul>
 *   <li>连接地址/账号/驱动：来自 spring.datasource.* （由 Spring Boot 绑定到 DataSourceProperties）</li>
 *   <li>连接池参数：来自 spring.datasource.pool.* （自定义 DatabasePoolProperties）</li>
 * </ul>
 * 显式注册 HikariDataSource，替代 Spring Boot 的隐式默认配置，参数全部由配置文件驱动。
 */
@Configuration
@EnableConfigurationProperties({DataSourceProperties.class, DatabasePoolProperties.class})
public class DataSourceConfig {

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties dataSourceProperties,
                                 DatabasePoolProperties poolProperties) {
        HikariDataSource dataSource = dataSourceProperties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();

        // 连接池参数从 spring.datasource.pool.* 读取
        dataSource.setPoolName(poolProperties.getPoolName());
        dataSource.setMaximumPoolSize(poolProperties.getMaximumPoolSize());
        dataSource.setMinimumIdle(poolProperties.getMinimumIdle());
        dataSource.setConnectionTimeout(poolProperties.getConnectionTimeoutMs());
        dataSource.setIdleTimeout(poolProperties.getIdleTimeoutMs());
        dataSource.setMaxLifetime(poolProperties.getMaxLifetimeMs());
        dataSource.setValidationTimeout(poolProperties.getValidationTimeoutMs());
        if (StringUtils.hasText(poolProperties.getConnectionTestQuery())) {
            dataSource.setConnectionTestQuery(poolProperties.getConnectionTestQuery());
        }
        return dataSource;
    }
}
