package com.example.springairagdemo.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

/**
 * 主数据源（RAG 业务库 knowledge_base）装配：
 * <ul>
 *   <li>连接地址/账号/驱动：来自 spring.datasource.* （由 Spring Boot 绑定到 DataSourceProperties）</li>
 *   <li>连接池参数：来自 spring.datasource.pool.* （自定义 DatabasePoolProperties）</li>
 *   <li>SqlSessionFactory / SqlSessionTemplate：显式注册（@Primary），
 *       替代 MyBatis-Plus 自动配置 —— 用户域（spring-ai-user）已注册独立的
 *       userSqlSessionFactory，若依赖自动配置会因 @ConditionalOnMissingBean
 *       导致默认 SqlSessionFactory 缺失，RAG Mapper 无工厂可用。</li>
 * </ul>
 * 用户域独立库（spring_ai_user）由 spring-ai-user 的 UserDataSourceConfig 装配，与本配置隔离。
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

    /** 主 SqlSessionFactory（@Primary），RAG Mapper 绑定该工厂 */
    @Bean
    @Primary
    public SqlSessionFactory sqlSessionFactory(
            @Qualifier("dataSource") DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setTypeAliasesPackage("com.example.springairagdemo.entity");
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setBanner(false);
        factoryBean.setGlobalConfig(globalConfig);
        return factoryBean.getObject();
    }

    /** 主 SqlSessionTemplate（@Primary） */
    @Bean
    @Primary
    public SqlSessionTemplate sqlSessionTemplate(
            @Qualifier("sqlSessionFactory") SqlSessionFactory factory) {
        return new SqlSessionTemplate(factory);
    }
}
