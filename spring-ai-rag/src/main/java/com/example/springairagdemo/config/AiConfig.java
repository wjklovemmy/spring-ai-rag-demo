package com.example.springairagdemo.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.example.springairagdemo.embedding.DashScopeEmbeddingModel;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * AI 配置：明确指定各模型的职责分工
 * - DeepSeek: Chat / 对话生成
 * - DashScope: Embedding / 文本向量化
 */
@Slf4j
@Configuration
public class AiConfig {

    /** AI 聊天调用（DeepSeek）的 Sentinel 熔断资源名 */
    public static final String AI_CHAT_RESOURCE = "ai-chat";

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

    /**
     * 注册 AI 问答（DeepSeek）的 Sentinel 降级规则：
     * 最小请求数 &gt;= 5 且异常比例 &gt;= 50% 时熔断 10 秒；
     * 熔断/异常期间 KnowledgeDocumentService#chat 直接返回降级提示
     * （"AI服务暂时不可用，请稍后再试"），避免接口 500。
     */
    @PostConstruct
    public void initAiChatDegradeRule() {
        DegradeRule rule = new DegradeRule(AI_CHAT_RESOURCE)
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                .setCount(0.5)
                .setMinRequestAmount(5)
                .setTimeWindow(10);
        DegradeRuleManager.loadRules(List.of(rule));
        log.info("已注册 AI 问答熔断规则：资源={}, grade=异常比例, count=0.5, minRequestAmount=5, timeWindow=10s",
                AI_CHAT_RESOURCE);
    }
}
