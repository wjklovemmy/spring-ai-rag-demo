package com.example.springairagdemo.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus 原生 SDK 配置
 * <p>
 * - MilvusServiceClient（v1 客户端）：兼容 Spring AI milvus-store 等存量依赖
 * - MilvusClientV2（v2 客户端）：官方推荐客户端，支持 BM25 全文检索与 Hybrid Search
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

    @Bean
    public MilvusClientV2 milvusClientV2() {
        return new MilvusClientV2(
                ConnectConfig.builder()
                        .uri("http://" + host + ":" + port)
                        .dbName(databaseName)
                        .build()
        );
    }
}
