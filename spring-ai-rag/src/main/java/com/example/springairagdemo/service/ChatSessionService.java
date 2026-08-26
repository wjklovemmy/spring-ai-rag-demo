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

    /**
     * 逻辑删除会话并级联逻辑删除其下全部 Agent 任务与步骤轨迹（记录删除人/时间，数据保留供审计追溯）。
     * Redis 聊天记忆清理由调用方负责（记忆 key 与库表独立）。
     *
     * @param userId     会话归属用户（数据权限）
     * @param sessionId  会话 ID
     * @param operatorId 删除人用户 ID（审计）
     */
    void logicalDelete(Long userId, String sessionId, Long operatorId);
}
