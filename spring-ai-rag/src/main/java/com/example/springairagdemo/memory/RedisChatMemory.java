package com.example.springairagdemo.memory;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Redis 的多轮对话记忆（ChatMemory 实现）。
 *
 * <p>消息以 JSON 数组存储：key = {@code rag:chat:memory:{conversationId}}，其中 conversationId 由
 * Service 层拼装为 {@code {userId}:{sessionId}}（会话按用户隔离，未登录为 anon 前缀）；
 * 写入时刷新 TTL（默认 7 天），
 * 长期不活跃的会话自动过期，避免 Redis 膨胀。仅持久化 user / assistant / system 三类文本消息
 * （阶段 1 无工具调用，其余消息类型在读取时跳过），metadata 不保存。
 */
@Slf4j
@Component
public class RedisChatMemory implements ChatMemory {

    private static final String KEY_PREFIX = "rag:chat:memory:";
    private static final String DEFAULT_CONVERSATION = "default";
    private static final Duration TTL = Duration.ofDays(7);
    /** 单会话最多返回的历史消息条数（窗口保护，防止历史无限膨胀撑爆模型上下文） */
    private static final int MAX_HISTORY = 100;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisChatMemory(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Message> get(String conversationId) {
        String json = redisTemplate.opsForValue().get(key(conversationId));
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<StoredMessage> stored = objectMapper.readValue(json, new TypeReference<List<StoredMessage>>() {});
            if (stored == null || stored.isEmpty()) {
                return List.of();
            }
            // 窗口保护：仅返回最近 MAX_HISTORY 条
            int from = Math.max(0, stored.size() - MAX_HISTORY);
            List<Message> messages = new ArrayList<>();
            for (StoredMessage sm : stored.subList(from, stored.size())) {
                Message m = toMessage(sm);
                if (m != null) {
                    messages.add(m);
                }
            }
            return messages;
        } catch (Exception e) {
            log.warn("读取对话记忆失败，conversationId={}：{}", conversationId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        String k = key(conversationId);
        // 读-改-写：追加新消息到既有历史（advisor 每轮写入 user + assistant）
        List<StoredMessage> existing = new ArrayList<>();
        String json = redisTemplate.opsForValue().get(k);
        if (json != null && !json.isBlank()) {
            try {
                List<StoredMessage> old = objectMapper.readValue(json, new TypeReference<List<StoredMessage>>() {});
                if (old != null) {
                    existing.addAll(old);
                }
            } catch (Exception e) {
                log.warn("解析既有对话记忆失败，conversationId={}，将覆盖：{}", conversationId, e.getMessage());
            }
        }
        for (Message m : messages) {
            MessageType type = m.getMessageType();
            // 只存 user/assistant 纯文本：system 消息是动态拼的检索上下文，绝不落库；
            // 含工具调用的 assistant 消息（正文为空）与 tool 结果消息也跳过，
            // 避免历史回放时出现无工具定义的 tool-call 消息导致模型困惑
            if (type == MessageType.USER) {
                existing.add(new StoredMessage("user", m.getText()));
            } else if (type == MessageType.ASSISTANT
                    && !(m instanceof AssistantMessage am && am.hasToolCalls())) {
                existing.add(new StoredMessage("assistant", m.getText()));
            }
        }
        try {
            redisTemplate.opsForValue().set(k, objectMapper.writeValueAsString(existing), TTL);
        } catch (Exception e) {
            log.error("写入对话记忆失败，conversationId={}：{}", conversationId, e.getMessage());
        }
    }

    @Override
    public void clear(String conversationId) {
        redisTemplate.delete(key(conversationId));
    }

    private String key(String conversationId) {
        return KEY_PREFIX + (conversationId == null || conversationId.isBlank()
                ? DEFAULT_CONVERSATION : conversationId);
    }

    /** 反序列化：按存储类型重建 Spring AI 消息对象，未知类型返回 null（跳过） */
    private Message toMessage(StoredMessage sm) {
        if (sm == null || sm.content() == null) {
            return null;
        }
        return switch (sm.type() == null ? "" : sm.type().toLowerCase()) {
            case "user" -> new UserMessage(sm.content());
            case "assistant" -> new AssistantMessage(sm.content());
            case "system" -> new SystemMessage(sm.content());
            default -> null;
        };
    }

    /** 轻量存储 DTO：仅保留消息类型与文本，避免依赖 Spring AI 消息类的多态序列化 */
    public record StoredMessage(String type, String content) {}
}
