package com.example.user.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * 用户域独立数据源装配（多数据源方案）。
 * <p>
 * - 宿主应用（RAG）的主数据源（@Primary）走 Spring Boot / MyBatis-Plus 自动配置，负责 RAG 业务表；
 * - 本配置构建用户域专属 DataSource + MyBatis-Plus SqlSessionFactory + 事务管理器，
 *   并通过 {@code @MapperScan} 将 com.example.user.mapper 下的 Mapper 绑定到该数据源，
 *   实现用户域（spring_ai_user 库）与 RAG 业务库的物理隔离。
 * <p>
 * 配置项：spring.datasource.user.url / username / password / driver-class-name
 * （数据库脚本见 sql/user.sql）
 */
@Configuration
@EnableConfigurationProperties(UserDataSourceProperties.class)
@MapperScan(basePackages = "com.example.user.mapper",
        sqlSessionTemplateRef = "userSqlSessionTemplate")
public class UserDataSourceConfig {

    @Bean(name = "userDataSource")
    public DataSource userDataSource(UserDataSourceProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("User-HikariCP");
        config.setJdbcUrl(properties.getUrl());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setDriverClassName(properties.getDriverClassName());
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30_000);
        return new HikariDataSource(config);
    }

    @Bean(name = "userSqlSessionFactory")
    public SqlSessionFactory userSqlSessionFactory(
            @Qualifier("userDataSource") DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setTypeAliasesPackage("com.example.user.entity");
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setBanner(false);
        factoryBean.setGlobalConfig(globalConfig);
        return factoryBean.getObject();
    }

    @Bean(name = "userSqlSessionTemplate")
    public SqlSessionTemplate userSqlSessionTemplate(
            @Qualifier("userSqlSessionFactory") SqlSessionFactory factory) {
        return new SqlSessionTemplate(factory);
    }

    @Bean(name = "userTransactionManager")
    public PlatformTransactionManager userTransactionManager(
            @Qualifier("userDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
