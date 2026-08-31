package com.example.springairagdemo.memory;

import com.example.springairagdemo.config.AiConfig;
import com.example.springairagdemo.memory.RedisChatMemory.StoredMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 对话历史摘要压缩器：把滑出窗口的最老一批对话浓缩成摘要，与既有摘要合并。
 *
 * <p>由 {@link RedisChatMemory} 在存储超窗口阈值时调用（每 summaryBatchSize 轮触发一次），
 * 将"无限增长的原始历史"变为"摘要 + 最近窗口原文"两级结构：模型上下文始终有界，
 * 同时长会话的早期关键信息（诉求/结论/未决问题）不随窗口滑动而丢失。
 *
 * <p>摘要生成复用 DeepSeek（deepSeekChatModel）并受 {@code ai-chat} 熔断保护：
 * 生成失败（异常/熔断/空响应）返回 {@code null}，由调用方降级为纯裁剪，绝不阻塞对话主流程。
 */
@Slf4j
@Component
public class ConversationSummarizer {

    /** 摘要压缩系统提示词：约束模型只输出摘要本身，保留关键信息 */
    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是对话历史压缩器。将「已有摘要」与「新对话片段」合并成一份新的对话摘要。
            要求：
            1. 保留关键信息：用户的核心诉求、已确认的事实与结论、用户提供的重要背景、
               尚未解决的问题、承诺或待办事项；
            2. 语言与对话一致（默认中文），简洁条目化，不要对话体；
            3. 与已有摘要重复的信息去重合并，摘要总长度控制在 500 字以内；
            4. 只输出摘要本身，不要任何解释、前缀或 Markdown 标题。
            """;

    /** 摘要合并提示模板：{0}=既有摘要，{1}=新对话片段（type: content 逐条） */
    private static final String SUMMARY_USER_PROMPT_TEMPLATE = """
            【已有摘要】
            %s

            【新对话片段】
            %s
            """;

    private final ChatModel chatModel;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    public ConversationSummarizer(@Qualifier("deepSeekChatModel") ChatModel chatModel,
                                  CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        this.chatModel = chatModel;
        this.circuitBreakerFactory = circuitBreakerFactory;
    }

    /**
     * 压缩一批对话并入既有摘要。
     *
     * @param existingSummary 既有摘要（可为空串）
     * @param batch           待压缩的对话消息（最老的一批，user/assistant 交替）
     * @return 合并后的新摘要；生成失败返回 {@code null}，调用方应降级为纯裁剪
     */
    public String summarize(String existingSummary, List<StoredMessage> batch) {
        StringBuilder sb = new StringBuilder();
        for (StoredMessage sm : batch) {
            if (sm != null && sm.content() != null) {
                sb.append(sm.type()).append(": ").append(sm.content()).append('\n');
            }
        }
        String userPrompt = SUMMARY_USER_PROMPT_TEMPLATE.formatted(
                existingSummary == null || existingSummary.isBlank() ? "（无）" : existingSummary, sb);
        try {
            String summary = circuitBreakerFactory.create(AiConfig.AI_CHAT_RESOURCE).run(
                    () -> {
                        String resp = chatModel.call(new Prompt(
                                        new SystemMessage(SUMMARY_SYSTEM_PROMPT),
                                        new UserMessage(userPrompt)))
                                .getResult().getOutput().getText();
                        return resp == null ? null : resp.trim();
                    },
                    t -> {
                        log.warn("对话摘要生成熔断降级：{}", t.getMessage());
                        return null;
                    });
            if (summary == null || summary.isBlank()) {
                log.warn("对话摘要生成返回空结果，本次不压缩");
                return null;
            }
            return summary;
        } catch (Exception e) {
            log.warn("对话摘要生成异常：{}", e.getMessage());
            return null;
        }
    }
}
