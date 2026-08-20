package com.example.springairagdemo.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 自定义异步任务线程池（Embedding 任务专用）：
 * <ul>
 *   <li>线程池参数：来自 spring.task.embedding.*（AsyncTaskProperties）</li>
 *   <li>线程工厂：自定义 {@link NamedThreadFactory}，线程命名 rag-embedding-N，便于日志定位</li>
 *   <li>拒绝策略：CallerRunsPolicy（任务满时由提交线程兜底执行，不丢弃任务）</li>
 *   <li>优雅停机：关闭时等待任务完成，避免进程终止中断任务</li>
 * </ul>
 * 注册为 {@code @Primary}，替代 Spring Boot 自动配置的默认 TaskExecutor。
 */
@Configuration
@EnableConfigurationProperties(AsyncTaskProperties.class)
public class AsyncTaskConfig {

    @Bean
    @Primary
    public ThreadPoolTaskExecutor taskExecutor(AsyncTaskProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getCorePoolSize());
        executor.setMaxPoolSize(props.getMaxPoolSize());
        executor.setQueueCapacity(props.getQueueCapacity());
        executor.setKeepAliveSeconds(props.getKeepAliveSeconds());
        executor.setThreadFactory(new NamedThreadFactory(props.getThreadNamePrefix()));
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(props.getAwaitTerminationSeconds());
        executor.initialize();
        return executor;
    }
}
