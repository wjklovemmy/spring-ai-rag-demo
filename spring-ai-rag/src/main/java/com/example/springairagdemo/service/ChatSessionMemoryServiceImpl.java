package com.example.springairagdemo.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.springairagdemo.config.RagConfigProperties;
import com.example.springairagdemo.entity.ChatSessionMemoryEntity;
import com.example.springairagdemo.mapper.ChatSessionMemoryMapper;
import com.example.springairagdemo.memory.RedisChatMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 会话长期记忆 Service 实现。
 * <p>写入：问答结束后读取 Redis 记忆快照（{@link RedisChatMemory#readStored}），
 * 有摘要用摘要、无摘要取最近几轮对话原文拼「会话要点」，按 user_id+session_id upsert。
 * <p>读取：按用户（同知识库优先）按更新时间倒序取最近若干条摘要，拼装为
 * 「过往对话背景」注入系统提示，实现跨会话「记得之前聊过什么」。
 */
@Slf4j
@Service
public class ChatSessionMemoryServiceImpl extends ServiceImpl<ChatSessionMemoryMapper, ChatSessionMemoryEntity>
        implements ChatSessionMemoryService {

    /** 注入文本总长度上限（字符），防止历史背景撑爆上下文 */
    private static final int MAX_CONTEXT_CHARS = 2000;
    /** 单条摘要截断长度（字符） */
    private static final int MAX_SUMMARY_CHARS = 400;
    /** 无摘要时单条消息截断长度（字符） */
    private static final int MAX_MESSAGE_CHARS = 200;
    /** 注入文本前缀：用于判定是否拼接出了有效内容 */
    private static final String CONTEXT_HEADER = "【过往对话背景】";

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Autowired
    private RedisChatMemory redisChatMemory;

    @Autowired
    private RagConfigProperties ragConfig;

    @Override
    public void persistFromRedis(Long userId, String sessionId, Long knowledgeBaseId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            String conversationId = userId + ":" + sessionId;
            RedisChatMemory.StoredConversation conv = redisChatMemory.readStored(conversationId);
            if (conv == null) {
                return;
            }
            String summary = conv.summary();
            if (summary == null || summary.isBlank()) {
                // Redis 无摘要（窗口未触发压缩）：取最近几轮对话原文拼「会话要点」，
                // 保证短会话也能留下跨会话可复用的记忆
                summary = buildFallbackSummary(conv.messages());
                if (summary == null) {
                    return;
                }
            }
            upsertSummary(userId, sessionId, knowledgeBaseId, summary);
        } catch (Exception e) {
            log.warn("会话长期记忆持久化失败（不影响问答）：sessionId={}：{}", sessionId, e.getMessage());
        }
    }

    @Override
    public String buildHistoryContext(Long userId, Long knowledgeBaseId, int limit) {
        if (userId == null || limit <= 0) {
            return null;
        }
        // 同知识库优先：取该知识库下最近活跃的会话摘要
        List<ChatSessionMemoryEntity> items = new ArrayList<>();
        if (knowledgeBaseId != null) {
            items.addAll(lambdaQuery()
                    .eq(ChatSessionMemoryEntity::getUserId, userId)
                    .eq(ChatSessionMemoryEntity::getKnowledgeBaseId, knowledgeBaseId)
                    .orderByDesc(ChatSessionMemoryEntity::getUpdateTime)
                    .last("LIMIT " + limit)
                    .list());
        }
        // 不足则跨库补齐（排除已选中的会话，避免重复）
        int need = limit - items.size();
        if (need > 0) {
            Set<String> picked = new HashSet<>();
            for (ChatSessionMemoryEntity it : items) {
                picked.add(it.getSessionId());
            }
            List<ChatSessionMemoryEntity> others = lambdaQuery()
                    .eq(ChatSessionMemoryEntity::getUserId, userId)
                    .orderByDesc(ChatSessionMemoryEntity::getUpdateTime)
                    .last("LIMIT " + need)
                    .list();
            for (ChatSessionMemoryEntity o : others) {
                if (!picked.contains(o.getSessionId())) {
                    items.add(o);
                }
            }
        }
        if (items.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(CONTEXT_HEADER)
                .append("以下是你在历史会话中讨论内容的摘要，可能与你当前的问题相关。")
                .append("请参考这些背景信息（若与当前问题无关可忽略），回答仍以当前会话和知识库检索结果为准：\n");
        for (ChatSessionMemoryEntity e : items) {
            String s = e.getSummary();
            if (s == null || s.isBlank()) {
                continue;
            }
            sb.append("- ").append(fmtTime(e.getUpdateTime())).append("：")
                    .append(truncate(s, MAX_SUMMARY_CHARS)).append("\n");
        }
        if (sb.length() <= CONTEXT_HEADER.length()) {
            return null;
        }
        return truncate(sb.toString(), MAX_CONTEXT_CHARS);
    }

    @Override
    public void logicalDeleteBySession(Long userId, String sessionId, Long operatorId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        Date now = new Date();
        lambdaUpdate()
                .set(ChatSessionMemoryEntity::getDeleted, 1)
                .set(ChatSessionMemoryEntity::getDeletedBy, operatorId)
                .set(ChatSessionMemoryEntity::getDeleteTime, now)
                .eq(ChatSessionMemoryEntity::getUserId, userId)
                .eq(ChatSessionMemoryEntity::getSessionId, sessionId)
                .update();
    }

    /** Redis 无摘要时：取最近 {@code fallbackLastTurns} 轮对话原文拼「会话要点」 */
    private String buildFallbackSummary(List<RedisChatMemory.StoredMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        int turns = ragConfig.getMemory().getFallbackLastTurns();
        if (turns <= 0) {
            return null;
        }
        List<RedisChatMemory.StoredMessage> recent = new ArrayList<>(
                messages.size() <= turns ? messages : messages.subList(messages.size() - turns, messages.size()));
        StringBuilder sb = new StringBuilder("会话要点（最近对话，尚无自动摘要）：\n");
        for (RedisChatMemory.StoredMessage m : recent) {
            if (m == null || m.content() == null || m.content().isBlank()) {
                continue;
            }
            String role = "user".equalsIgnoreCase(m.type()) ? "用户" : "助手";
            sb.append(role).append("：").append(truncate(m.content(), MAX_MESSAGE_CHARS)).append("\n");
        }
        String s = sb.toString();
        return s.contains("：") ? s : null;
    }

    /** 按 user_id + session_id upsert（同会话每轮更新摘要，保持最新） */
    private void upsertSummary(Long userId, String sessionId, Long knowledgeBaseId, String summary) {
        ChatSessionMemoryEntity exist = lambdaQuery()
                .eq(ChatSessionMemoryEntity::getUserId, userId)
                .eq(ChatSessionMemoryEntity::getSessionId, sessionId)
                .one();
        Date now = new Date();
        if (exist == null) {
            ChatSessionMemoryEntity e = new ChatSessionMemoryEntity();
            e.setUserId(userId);
            e.setSessionId(sessionId);
            e.setKnowledgeBaseId(knowledgeBaseId);
            e.setSummary(summary);
            e.setCreateTime(now);
            e.setUpdateTime(now);
            save(e);
            return;
        }
        exist.setSummary(summary);
        if (knowledgeBaseId != null) {
            exist.setKnowledgeBaseId(knowledgeBaseId);
        }
        exist.setUpdateTime(now);
        updateById(exist);
    }

    /** 单行化 + 截断（带省略号） */
    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    private String fmtTime(Date d) {
        if (d == null) {
            return "";
        }
        return TIME_FMT.format(d.toInstant().atZone(ZoneId.systemDefault()));
    }
}
