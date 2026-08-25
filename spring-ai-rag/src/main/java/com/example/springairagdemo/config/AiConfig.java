package com.example.springairagdemo.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.example.springairagdemo.embedding.DashScopeEmbeddingModel;
import com.example.springairagdemo.tools.KbQueryTools;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
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

    /** 向量化调用（DashScope Embedding）的 Sentinel 熔断资源名 */
    public static final String EMBEDDING_RESOURCE = "dashscope-embedding";

    /**
     * 显式使用 DeepSeek 的 ChatModel 创建 ChatClient，
     * 避免因多 ChatModel Bean 导致歧义
     */
    /**
     * 注册知识库查询工具集（KbQueryTools）为 ToolCallbackProvider：
     * 模型可自主调用工具查询 MySQL（文档清单、文件名搜索等），
     * 解决"知识库中有哪些文档"等纯向量检索无法回答的枚举类问题。
     * 请求级上下文（knowledgeBaseId/userId）由 Service 层经 prompt.toolContext() 注入。
     */
    @Bean
    public ToolCallbackProvider kbQueryToolCallbacks(KbQueryTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }

    @Bean
    public ChatClient chatClient(@Qualifier("deepSeekChatModel") ChatModel chatModel, ChatMemory chatMemory,
                                 ToolCallbackProvider toolCallbackProvider) {
        // 挂载多轮对话记忆 Advisor：按会话 ID（chat_memory_conversation_id）存取历史消息，
        // 历史中的 user/assistant 消息自动注入 prompt；检索上下文（system 消息）不进记忆
        return ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultToolCallbacks(toolCallbackProvider)
                .build();
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
     * 注册 Sentinel 降级规则（AI 问答 + 向量化）：
     * 最小请求数 &gt;= 5 且异常比例 &gt;= 50% 时熔断 10 秒。
     * <ul>
     *   <li>{@code ai-chat}（DeepSeek）：熔断/异常期间 KnowledgeDocumentService#chat 直接返回
     *       降级提示（"AI服务暂时不可用，请稍后再试"），避免接口 500；</li>
     *   <li>{@code dashscope-embedding}（DashScope Embedding）：连续异常比例高时快速失败，
     *       避免大批量文档排队把配额打爆，上传任务错误归一为「向量化服务暂时不可用，请稍后重试」，
     *       与 ai-chat 互不影响。</li>
     * </ul>
     */
    @PostConstruct
    public void initSentinelDegradeRules() {
        DegradeRule chatRule = new DegradeRule(AI_CHAT_RESOURCE)
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                .setCount(0.5)
                .setMinRequestAmount(5)
                .setTimeWindow(10);
        DegradeRule embedRule = new DegradeRule(EMBEDDING_RESOURCE)
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                .setCount(0.5)
                .setMinRequestAmount(5)
                .setTimeWindow(10);
        DegradeRuleManager.loadRules(List.of(chatRule, embedRule));
        log.info("已注册 Sentinel 熔断规则：AI 问答资源={}, Embedding 资源={}, grade=异常比例, count=0.5, minRequestAmount=5, timeWindow=10s",
                AI_CHAT_RESOURCE, EMBEDDING_RESOURCE);
    }
}
