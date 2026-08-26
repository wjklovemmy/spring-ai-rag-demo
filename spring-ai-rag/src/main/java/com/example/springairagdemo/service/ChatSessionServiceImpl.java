package com.example.springairagdemo.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.springairagdemo.entity.ChatSessionEntity;
import com.example.springairagdemo.mapper.ChatSessionMapper;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.UUID;

/**
 * 聊天会话 Service 实现
 */
@Service
public class ChatSessionServiceImpl extends ServiceImpl<ChatSessionMapper, ChatSessionEntity>
        implements ChatSessionService {

    /** 会话标题最大长度（取首个问题截断） */
    private static final int TITLE_MAX = 30;

    @Override
    public ChatSessionEntity createSession(Long userId, Long knowledgeBaseId) {
        ChatSessionEntity s = new ChatSessionEntity();
        s.setUserId(userId);
        s.setSessionId(UUID.randomUUID().toString().replace("-", ""));
        s.setTitle("");
        s.setKnowledgeBaseId(knowledgeBaseId);
        Date now = new Date();
        s.setCreateTime(now);
        s.setUpdateTime(now);
        save(s);
        return s;
    }

    @Override
    public void touchOnChat(Long userId, String sessionId, Long knowledgeBaseId, String question) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        String title = trimTitle(question);
        ChatSessionEntity s = lambdaQuery()
                .eq(ChatSessionEntity::getUserId, userId)
                .eq(ChatSessionEntity::getSessionId, sessionId)
                .one();
        if (s == null) {
            // 旧前端/旧 localStorage 残留的 sessionId：自动补建会话记录，平滑接入会话列表
            s = new ChatSessionEntity();
            s.setUserId(userId);
            s.setSessionId(sessionId);
            s.setTitle(title);
            s.setKnowledgeBaseId(knowledgeBaseId);
            Date now = new Date();
            s.setCreateTime(now);
            s.setUpdateTime(now);
            save(s);
            return;
        }
        boolean changed = false;
        if ((s.getTitle() == null || s.getTitle().isBlank()) && !title.isEmpty()) {
            s.setTitle(title);
            changed = true;
        }
        if (s.getKnowledgeBaseId() == null) {
            s.setKnowledgeBaseId(knowledgeBaseId);
            changed = true;
        }
        if (changed) {
            s.setUpdateTime(new Date());
            updateById(s);
        }
    }

    @Override
    public ChatSessionEntity getOwned(Long userId, String sessionId) {
        return lambdaQuery()
                .eq(ChatSessionEntity::getUserId, userId)
                .eq(ChatSessionEntity::getSessionId, sessionId)
                .one();
    }

    /** 问题截断为会话标题（单行、去空白） */
    private String trimTitle(String question) {
        if (question == null) {
            return "";
        }
        String t = question.replaceAll("\\s+", " ").trim();
        return t.length() > TITLE_MAX ? t.substring(0, TITLE_MAX) + "…" : t;
    }
}
