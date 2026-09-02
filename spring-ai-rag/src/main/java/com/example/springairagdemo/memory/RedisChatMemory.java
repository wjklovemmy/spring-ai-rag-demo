package com.example.springairagdemo.memory;

import com.example.springairagdemo.config.RagConfigProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Redis 的多轮对话记忆（ChatMemory 实现）。
 *
 * <p>存储结构为 {@code {"summary": "...", "messages": [...]}}：
 * key = {@code rag:chat:memory:{conversationId}}，其中 conversationId 由 Service 层拼装为
 * {@code {userId}:{sessionId}}（会话按用户隔离，未登录为 anon 前缀）；写入时刷新 TTL（默认 7 天），
 * 长期不活跃的会话自动过期。仅持久化 user / assistant 纯文本消息（system 为动态检索上下文，
 * 工具调用消息无正文均跳过），metadata 不保存。
 *
 * <p><b>滑动窗口 + 摘要压缩</b>（防记忆无限增长）：窗口以 <b>token 预算主控 + 条数兜底</b>
 * 双约束——{@code rag.memory.max-tokens}（默认 16000）控制历史总 token 估算上限（这才是
 * 真正撑爆模型上下文的因素），{@code rag.memory.max-history}（默认 100 条）兜底防止
 * 单条消息过小时窗口无限拉长；token 估算为本地保守上界（{@link MessageTokenEstimator}，
 * ASCII 4 字符/token、中文 1 字符/token），估算结果随消息落库（旧数据缺失时读取端补算）。
 * 当存储超过「条数上限 + batch」或「token 预算」时，把最老的 batch 条交给
 * {@link ConversationSummarizer} 浓缩进摘要，存储回落到窗口内。读取时返回「摘要（system 消息）
 * + 窗口原文（同样按 token/条数裁剪）」，模型上下文始终有界，早期关键信息经摘要保留。
 * 摘要生成失败/未启用时降级为纯裁剪，存储仍有上界。旧版纯数组格式数据（无摘要字段）读取时自动兼容。
 */
@Slf4j
@Component
public class RedisChatMemory implements ChatMemory {

    private static final String KEY_PREFIX = "rag:chat:memory:";
    private static final String DEFAULT_CONVERSATION = "default";
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ConversationSummarizer summarizer;
    private final RagConfigProperties ragConfig;

    public RedisChatMemory(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                           ConversationSummarizer summarizer, RagConfigProperties ragConfig) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.summarizer = summarizer;
        this.ragConfig = ragConfig;
    }

    @Override
    public List<Message> get(String conversationId) {
        StoredConversation conv = read(key(conversationId));
        boolean hasSummary = conv.summary() != null && !conv.summary().isBlank();
        if (conv.messages().isEmpty() && !hasSummary) {
            return List.of();
        }
        List<Message> messages = new ArrayList<>();
        // 摘要以 system 消息置于最前，供模型做长会话背景参考
        if (hasSummary) {
            messages.add(new SystemMessage("【历史对话摘要】" + conv.summary()));
        }
        // 窗口裁剪：token 预算主控 + 条数兜底（与 compact 纯裁剪同一逻辑），始终保留至少 1 条
        for (StoredMessage sm : trimToBudget(conv.messages(), maxHistory(), maxTokens())) {
            Message m = toMessage(sm);
            if (m != null) {
                messages.add(m);
            }
        }
        return messages;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        String k = key(conversationId);
        StoredConversation conv = read(k);
        List<StoredMessage> existing = new ArrayList<>(conv.messages());
        for (Message m : messages) {
            MessageType type = m.getMessageType();
            // 只存 user/assistant 纯文本：system 消息是动态拼的检索上下文，绝不落库；
            // 含工具调用的 assistant 消息（正文为空）与 tool 结果消息也跳过，
            // 避免历史回放时出现无工具定义的 tool-call 消息导致模型困惑；
            // 写入时本地估算 token 并落库（供窗口按预算裁剪，旧数据缺失时读取端补算）
            if (type == MessageType.USER) {
                existing.add(new StoredMessage("user", m.getText()));
            } else if (type == MessageType.ASSISTANT
                    && !(m instanceof AssistantMessage am && am.hasToolCalls())) {
                existing.add(new StoredMessage("assistant", m.getText()));
            }
        }
        write(k, compact(conv.summary(), existing));
    }

    @Override
    public void clear(String conversationId) {
        redisTemplate.delete(key(conversationId));
    }

    /**
     * 读取存储的会话记忆快照（含摘要与消息窗口），供服务层做长期记忆持久化。
     * 与 {@link #get} 的区别：返回原始存储结构，不注入为 Spring AI 消息、不裁剪窗口。
     */
    public StoredConversation readStored(String conversationId) {
        return read(key(conversationId));
    }

    /**
     * 滑动窗口压缩（双触发）：存储条数超过 {@code maxHistory + batch}，或总 token 估算
     * 超过 {@code maxTokens} 时，把最老的 batch 条（token 超限时按需移除更多）压缩进摘要。
     * 摘要成功 → 剩余消息已回落到窗口内；摘要未启用或失败 → 纯裁剪（同 {@link #trimToBudget}）。
     * 两种路径下存储均有上界（条数 ≤ maxHistory + batch、token ≤ maxTokens + 一条超限消息）。
     */
    private StoredConversation compact(String summary, List<StoredMessage> messages) {
        int maxHistory = maxHistory();
        int batchSize = summaryBatchSize();
        int maxTokens = maxTokens();
        boolean overByCount = messages.size() > maxHistory + batchSize;
        boolean overByTokens = totalTokens(messages) > maxTokens;
        if (!overByCount && !overByTokens) {
            return new StoredConversation(summary, messages);
        }
        // 需移除的最老条数：条数超 → 至少 batch 条；token 超 → 移除到总 token 回落预算内
        int toRemove = batchSize;
        if (overByTokens) {
            toRemove = Math.max(toRemove, countToFitTokens(messages, maxTokens));
        }
        toRemove = Math.min(toRemove, messages.size() - 1);
        if (ragConfig.getMemory().isSummaryEnabled()) {
            List<StoredMessage> batch = List.copyOf(messages.subList(0, toRemove));
            String newSummary = summarizer.summarize(summary, batch);
            if (newSummary != null) {
                log.info("对话记忆压缩：最老 {} 条并入摘要（条数超限={}，token超限={}），剩余 {} 条/{} tokens",
                        toRemove, overByCount, overByTokens, messages.size() - toRemove,
                        totalTokens(messages.subList(toRemove, messages.size())));
                return new StoredConversation(newSummary,
                        new ArrayList<>(messages.subList(toRemove, messages.size())));
            }
            log.warn("摘要压缩失败，降级为纯裁剪（丢弃最老 {} 条原文）", toRemove);
        }
        return new StoredConversation(summary,
                new ArrayList<>(trimToBudget(messages, maxHistory, maxTokens)));
    }

    /**
     * 按预算裁剪窗口：从最新消息往前累计 token，超过 {@code maxTokens} 即停（始终保留至少 1 条），
     * 同时受 {@code maxHistory} 条数上限约束。返回从窗口起点到末尾的子列表视图。
     */
    private List<StoredMessage> trimToBudget(List<StoredMessage> messages, int maxHistory, int maxTokens) {
        int minFrom = Math.max(0, messages.size() - maxHistory);
        int acc = 0;
        int from = messages.size();
        while (from > minFrom) {
            int t = tokensOf(messages.get(from - 1));
            if (acc + t > maxTokens && from < messages.size()) {
                break;
            }
            acc += t;
            from--;
        }
        return messages.subList(from, messages.size());
    }

    /** 从最老开始移除所需条数，使剩余消息总 token ≤ 预算（返回值 = 需移除条数，0 = 已达标） */
    private int countToFitTokens(List<StoredMessage> messages, int maxTokens) {
        int total = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            total += tokensOf(messages.get(i));
            if (total > maxTokens) {
                return i + 1;
            }
        }
        return 0;
    }

    private int totalTokens(List<StoredMessage> messages) {
        int total = 0;
        for (StoredMessage sm : messages) {
            total += tokensOf(sm);
        }
        return total;
    }

    /** 单条消息 token：优先用落库估算值，旧数据（tokens 缺失）读取时补算 */
    private int tokensOf(StoredMessage sm) {
        if (sm == null || sm.content() == null) {
            return 0;
        }
        Integer t = sm.tokens();
        return t == null ? MessageTokenEstimator.estimate(sm.content()) : t;
    }

    /**
     * 读取存储：兼容新版对象结构 {@code {"summary":...,"messages":[...]}} 与
     * 旧版纯数组 {@code [...]}（旧数据视为无摘要）。
     */
    private StoredConversation read(String k) {
        String json = redisTemplate.opsForValue().get(k);
        if (json == null || json.isBlank()) {
            return new StoredConversation("", List.of());
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.isArray()) {
                List<StoredMessage> msgs = objectMapper.convertValue(root,
                        new TypeReference<List<StoredMessage>>() {});
                return new StoredConversation("", msgs == null ? List.of() : msgs);
            }
            if (root.isObject()) {
                StoredConversation conv = objectMapper.convertValue(root, StoredConversation.class);
                return conv == null ? new StoredConversation("", List.of()) : conv;
            }
            log.warn("对话记忆数据格式未知，key={}，按空记忆处理", k);
            return new StoredConversation("", List.of());
        } catch (Exception e) {
            log.warn("解析对话记忆失败，key={}：{}", k, e.getMessage());
            return new StoredConversation("", List.of());
        }
    }

    private void write(String k, StoredConversation conv) {
        try {
            redisTemplate.opsForValue().set(k, objectMapper.writeValueAsString(conv), TTL);
        } catch (Exception e) {
            log.error("写入对话记忆失败，key={}：{}", k, e.getMessage());
        }
    }

    private String key(String conversationId) {
        return KEY_PREFIX + (conversationId == null || conversationId.isBlank()
                ? DEFAULT_CONVERSATION : conversationId);
    }

    private int maxHistory() {
        return ragConfig.getMemory().getMaxHistory();
    }

    private int maxTokens() {
        return ragConfig.getMemory().getMaxTokens();
    }

    private int summaryBatchSize() {
        return ragConfig.getMemory().getSummaryBatchSize();
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

    /** 轻量存储 DTO：消息类型 + 文本 + 本地估算的 token 数（旧数据 tokens 为 null，读取时补算），
     *  避免依赖 Spring AI 消息类的多态序列化 */
    public record StoredMessage(String type, String content, Integer tokens) {
        public StoredMessage(String type, String content) {
            this(type, content, MessageTokenEstimator.estimate(content));
        }
    }

    /** 存储载体：历史摘要 + 消息列表（旧版纯数组数据读取时兼容） */
    public record StoredConversation(String summary, List<StoredMessage> messages) {}
}
