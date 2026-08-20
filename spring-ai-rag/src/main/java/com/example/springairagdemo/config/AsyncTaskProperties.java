package com.example.springairagdemo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Embedding 异步任务线程池参数（前缀 spring.task.embedding），
 * 由 {@link AsyncTaskConfig} 绑定到自定义 ThreadPoolTaskExecutor。
 */
@Data
@ConfigurationProperties(prefix = "spring.task.embedding")
public class AsyncTaskProperties {

    /** 核心线程数 */
    private int corePoolSize = 2;

    /** 最大线程数 */
    private int maxPoolSize = 4;

    /** 队列容量（任务超出核心线程后先入队） */
    private int queueCapacity = 50;

    /** 空闲线程存活时间（秒） */
    private int keepAliveSeconds = 60;

    /** 线程名前缀，便于日志/监控定位 */
    private String threadNamePrefix = "rag-embedding-";

    /** 关闭时等待线程池任务完成的最长时间（秒），优雅停机 */
    private int awaitTerminationSeconds = 30;
}
