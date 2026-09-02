package com.example.springairagdemo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.springairagdemo.entity.ChatSessionMemoryEntity;

/**
 * 会话长期记忆 Service（Phase 1：跨会话情景记忆）。
 * 问答结束后把 Redis 会话摘要持久化到 MySQL；新会话开始时检索最近活跃摘要供注入。
 */
public interface ChatSessionMemoryService extends IService<ChatSessionMemoryEntity> {

    /**
     * 问答结束后调用：读取 Redis 中该会话的长期记忆快照（摘要，无摘要时取最近几轮原文），
     * upsert 到 MySQL chat_session_memory。失败仅告警，不影响问答。
     *
     * @param userId          用户 ID
     * @param sessionId       会话 ID
     * @param knowledgeBaseId 会话关联知识库（可为 null）
     */
    void persistFromRedis(Long userId, String sessionId, Long knowledgeBaseId);

    /**
     * 新会话问答开始时调用：按用户检索最近活跃会话摘要（同知识库优先，不足跨库补齐），
     * 组装为可注入系统提示的历史背景文本；无任何历史时返回 null（不注入）。
     *
     * @param userId          用户 ID
     * @param knowledgeBaseId 当前知识库（可为 null，此时仅按用户检索）
     * @param limit           返回的会话摘要条数上限（≤0 返回 null）
     */
    String buildHistoryContext(Long userId, Long knowledgeBaseId, int limit);

    /**
     * 删除会话时级联逻辑删除该会话的长期记忆（防止已删会话摘要继续被注入）。
     */
    void logicalDeleteBySession(Long userId, String sessionId, Long operatorId);
}
