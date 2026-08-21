package com.example.springairagdemo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * RAG 服务启动类。
 * <ul>
 *   <li>scanBasePackages 同时扫描 RAG 业务域与用户域（spring-ai-user 共享 jar）的组件；</li>
 *   <li>本类的 @MapperScan 仅扫描 RAG 业务表 Mapper（绑定主数据源 knowledge_base）；
 *       用户域 Mapper 由 spring-ai-user 的 UserDataSourceConfig 独立装配（独立库 spring_ai_user），
 *       两套数据源物理隔离。</li>
 * </ul>
 */
@SpringBootApplication(scanBasePackages = {"com.example.springairagdemo", "com.example.user"})
@MapperScan("com.example.springairagdemo.mapper")
public class SpringAiRagDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringAiRagDemoApplication.class, args);
    }

}
