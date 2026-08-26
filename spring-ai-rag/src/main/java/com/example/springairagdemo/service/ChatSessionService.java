package com.example.springairagdemo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.springairagdemo.entity.ChatSessionEntity;

/**
 * 聊天会话 Service：会话元数据的创建、查询、删除，
 * 以及 chat 时的自动补建/标题更新（兼容旧前端残留的 sessionId）。
 */
public interface ChatSessionService extends IService<ChatSessionEntity> {

    /**
     * 创建新会话（后端生成 sessionId = UUID）
     *
     * @param userId          用户 ID
     * @param knowledgeBaseId 会话关联的知识库（可为 null）
     */
    ChatSessionEntity createSession(Long userId, Long knowledgeBaseId);

    /**
     * chat 时联动：会话存在则刷新时间/标题，不存在则自动补建（旧 sessionId 平滑接入）。
     *
     * @param userId          用户 ID
     * @param sessionId       会话 ID
     * @param knowledgeBaseId 当前知识库
     * @param question        当前问题（用于填充空标题）
     */
    void touchOnChat(Long userId, String sessionId, Long knowledgeBaseId, String question);

    /**
     * 按用户+会话查询（归属校验）
     */
    ChatSessionEntity getOwned(Long userId, String sessionId);
}
