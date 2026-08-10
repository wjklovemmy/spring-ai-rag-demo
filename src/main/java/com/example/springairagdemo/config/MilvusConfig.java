package com.example.springairagdemo.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus 原生 SDK 配置，暴露 MilvusServiceClient 用于动态管理 collection
 */
@Configuration
public class MilvusConfig {

    @Value("${spring.ai.vectorstore.milvus.client.host:localhost}")
    private String host;

    @Value("${spring.ai.vectorstore.milvus.client.port:19530}")
    private int port;

    @Value("${spring.ai.vectorstore.milvus.database-name:default}")
    private String databaseName;

    @Bean
    public MilvusServiceClient milvusServiceClient() {
        return new MilvusServiceClient(
                ConnectParam.newBuilder()
                        .withHost(host)
                        .withPort(port)
                        .withDatabaseName(databaseName)
                        .build()
        );
    }
}
