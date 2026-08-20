package com.example.springairagdemo.config;

import com.example.springairagdemo.embedding.DashScopeEmbeddingModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 配置：明确指定各模型的职责分工
 * - DeepSeek: Chat / 对话生成
 * - DashScope: Embedding / 文本向量化
 */
@Configuration
public class AiConfig {

    /**
     * 显式使用 DeepSeek 的 ChatModel 创建 ChatClient，
     * 避免因多 ChatModel Bean 导致歧义
     */
    @Bean
    public ChatClient chatClient(@Qualifier("deepSeekChatModel") ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    /**
     * 注册自定义 DashScope EmbeddingModel 为 Spring Bean，
     * 供 MilvusVectorStore 等组件自动注入
     */
    @Bean
    public EmbeddingModel embeddingModel(
            @Value("${spring.ai.dashscope.api-key}") String apiKey,
            @Value("${spring.ai.dashscope.embedding.model}") String model) {
        return new DashScopeEmbeddingModel(apiKey, model);
    }
}
