package com.example.springairagdemo.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.springairagdemo.entity.ChatSessionEntity;
import com.example.springairagdemo.mapper.ChatSessionMapper;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private AgentTaskService agentTaskService;

    @Autowired
    private ChatSessionMemoryService chatSessionMemoryService;

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

    @Override
    public void logicalDelete(Long userId, String sessionId, Long operatorId) {
        Date now = new Date();
        // 1. 会话本身逻辑删除（deleted=1 + 删除人/时间）
        lambdaUpdate()
                .set(ChatSessionEntity::getDeleted, 1)
                .set(ChatSessionEntity::getDeletedBy, operatorId)
                .set(ChatSessionEntity::getDeleteTime, now)
                .eq(ChatSessionEntity::getUserId, userId)
                .eq(ChatSessionEntity::getSessionId, sessionId)
                .update();
        // 2. 级联逻辑删除该会话下的 Agent 任务与步骤轨迹（记录删除人/时间）
        agentTaskService.logicalDeleteBySession(userId, sessionId, operatorId);
        // 3. 级联逻辑删除该会话的长期记忆（防止已删会话摘要继续被注入）
        chatSessionMemoryService.logicalDeleteBySession(userId, sessionId, operatorId);
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
