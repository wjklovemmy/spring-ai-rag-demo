package com.example.springairagdemo.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.example.springairagdemo.embedding.DashScopeEmbeddingModel;
import com.example.springairagdemo.tools.CalculatorTool;
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
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

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

    private final Environment environment;
    private final RagConfigProperties ragConfig;

    public AiConfig(Environment environment, RagConfigProperties ragConfig) {
        this.environment = environment;
        this.ragConfig = ragConfig;
    }

    /**
     * 显式使用 DeepSeek 的 ChatModel 创建 ChatClient，
     * 避免因多 ChatModel Bean 导致歧义
     */
    /**
     * 注册模型可自主调用的工具集为 ToolCallbackProvider：
     * <ul>
     *   <li>{@link KbQueryTools}：知识库查询（文档清单、文件名搜索、大纲、检索正文），
     *       解决"知识库中有哪些文档"等纯向量检索无法回答的枚举类问题；
     *       请求级上下文（knowledgeBaseId/userId）由 Service 层经 prompt.toolContext() 注入。</li>
     *   <li>{@link CalculatorTool}：数学表达式计算，解决"年假还剩几天"等需要数值运算的问题，
     *       模型把自然语言翻译为受限表达式，由服务端安全求值。</li>
     * </ul>
     */
    @Bean
    public ToolCallbackProvider kbQueryToolCallbacks(KbQueryTools tools, CalculatorTool calculatorTool) {
        return MethodToolCallbackProvider.builder().toolObjects(tools, calculatorTool).build();
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
     * 注册 Sentinel 降级规则（AI 问答 + 向量化），参数来自配置 {@code rag.sentinel.*}：
     * 最小请求数 &gt;= min-request-amount 且异常比例 &gt;= exception-ratio 时熔断 time-window-seconds 秒。
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
        registerDegradeRules();
    }

    /**
     * Nacos 配置热更新：{@code rag.sentinel.*} 变更时重载熔断规则，无需重启服务。
     * 读取走 Environment（而非依赖 ConfigurationProperties 的 rebind）：
     * EnvironmentChangeEvent 发布时 Environment 已包含新值，而 rebind 与本监听器执行顺序不保证，
     * 直接读 Environment 可保证拿到最新配置。
     */
    @EventListener(EnvironmentChangeEvent.class)
    public void reloadSentinelDegradeRules(EnvironmentChangeEvent event) {
        boolean relevant = event.getKeys().stream().anyMatch(k -> k.startsWith("rag.sentinel"));
        if (!relevant) {
            return;
        }
        log.info("检测到 rag.sentinel.* 配置变更，重载 Sentinel 熔断规则");
        registerDegradeRules();
    }

    /** 按配置注册/重载两条熔断规则（ai-chat + dashscope-embedding） */
    private void registerDegradeRules() {
        DegradeRule chatRule = buildRule(AI_CHAT_RESOURCE, "rag.sentinel.ai-chat", ragConfig.getSentinel().getAiChat());
        DegradeRule embedRule = buildRule(EMBEDDING_RESOURCE, "rag.sentinel.embedding", ragConfig.getSentinel().getEmbedding());
        DegradeRuleManager.loadRules(List.of(chatRule, embedRule));
        log.info("已注册 Sentinel 熔断规则：AI 问答资源={}, Embedding 资源={}, grade=异常比例, "
                        + "chat(count={}, minRequestAmount={}, timeWindow={}s), "
                        + "embedding(count={}, minRequestAmount={}, timeWindow={}s)",
                AI_CHAT_RESOURCE, EMBEDDING_RESOURCE,
                chatRule.getCount(), chatRule.getMinRequestAmount(), chatRule.getTimeWindow(),
                embedRule.getCount(), embedRule.getMinRequestAmount(), embedRule.getTimeWindow());
    }

    /** 从配置（Environment 优先，ConfigurationProperties 兜底默认值）构建一条熔断规则 */
    private DegradeRule buildRule(String resource, String prefix, RagConfigProperties.Rule defaults) {
        double exceptionRatio = environment.getProperty(prefix + ".exception-ratio", Double.class, defaults.getExceptionRatio());
        int minRequestAmount = environment.getProperty(prefix + ".min-request-amount", Integer.class, defaults.getMinRequestAmount());
        int timeWindow = environment.getProperty(prefix + ".time-window-seconds", Integer.class, defaults.getTimeWindowSeconds());
        return new DegradeRule(resource)
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                .setCount(exceptionRatio)
                .setMinRequestAmount(minRequestAmount)
                .setTimeWindow(timeWindow);
    }
}
