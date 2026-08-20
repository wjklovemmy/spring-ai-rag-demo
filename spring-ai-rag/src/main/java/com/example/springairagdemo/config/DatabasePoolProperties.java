package com.example.springairagdemo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 数据库连接池参数（前缀 spring.datasource.pool），
 * 由 {@link DataSourceConfig} 显式绑定到 HikariCP。
 * <p>
 * 所有参数集中在 application.yaml 中维护，默认值与 HikariCP 官方推荐一致。
 */
@Data
@ConfigurationProperties(prefix = "spring.datasource.pool")
public class DatabasePoolProperties {

    /** 连接池名称，便于监控与日志区分 */
    private String poolName = "KnowledgeBase-HikariCP";

    /** 池中最大连接数（含空闲与在用） */
    private int maximumPoolSize = 10;

    /** 池中保持的最小空闲连接数，必须小于等于 maximumPoolSize */
    private int minimumIdle = 2;

    /** 获取连接的超时时间（毫秒），超时抛出 SQLTransientConnectionException */
    private long connectionTimeoutMs = 30000;

    /** 空闲连接回收时间（毫秒），必须小于 maxLifetimeMs */
    private long idleTimeoutMs = 600000;

    /** 连接最大存活时间（毫秒），建议小于数据库 wait_timeout */
    private long maxLifetimeMs = 1800000;

    /** 连接校验超时时间（毫秒），必须小于 connectionTimeoutMs */
    private long validationTimeoutMs = 5000;

    /** 连接校验 SQL，留空则使用 Hikari 默认的数据库 ping */
    private String connectionTestQuery = "";
}
