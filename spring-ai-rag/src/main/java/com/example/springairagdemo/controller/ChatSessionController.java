package com.example.springairagdemo.controller;

import com.example.springairagdemo.entity.ChatSessionEntity;
import com.example.springairagdemo.security.UserContext;
import com.example.springairagdemo.service.ChatSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天会话管理（会话列表 / 切换 / 删除）。
 * 会话元数据存 MySQL（chat_session），消息历史存 Redis（ChatMemory）。
 * 会话按当前登录用户隔离（UserContext，由网关注入）；未登录一律 401。
 */
@RestController
@RequestMapping("/api/chat-session")
@RequiredArgsConstructor
public class ChatSessionController {

    private final ChatSessionService chatSessionService;
    private final ChatMemory chatMemory;

    /**
     * 创建新会话：后端生成 sessionId，返回给前端作为聊天会话标识。
     *
     * @param body 可选 {knowledgeBaseId}
     */
    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) Map<String, Object> body) {
        Long userId = requireUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Long kbId = null;
        if (body != null && body.get("knowledgeBaseId") != null) {
            kbId = ((Number) body.get("knowledgeBaseId")).longValue();
        }
        ChatSessionEntity s = chatSessionService.createSession(userId, kbId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", s.getSessionId());
        data.put("title", s.getTitle());
        data.put("knowledgeBaseId", s.getKnowledgeBaseId());
        data.put("createTime", s.getCreateTime());
        return ResponseEntity.ok(data);
    }

    /**
     * 当前用户会话列表（按最近更新倒序）
     */
    @GetMapping("/list")
    public ResponseEntity<List<Map<String, Object>>> list() {
        Long userId = requireUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<ChatSessionEntity> sessions = chatSessionService.lambdaQuery()
                .eq(ChatSessionEntity::getUserId, userId)
                .orderByDesc(ChatSessionEntity::getUpdateTime)
                .list();
        List<Map<String, Object>> data = new ArrayList<>();
        for (ChatSessionEntity s : sessions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", s.getSessionId());
            m.put("title", s.getTitle() == null || s.getTitle().isBlank() ? "新对话" : s.getTitle());
            m.put("knowledgeBaseId", s.getKnowledgeBaseId());
            m.put("createTime", s.getCreateTime());
            m.put("updateTime", s.getUpdateTime());
            data.add(m);
        }
        return ResponseEntity.ok(data);
    }

    /**
     * 拉取指定会话的历史消息（Redis 记忆，按 user/assistant 顺序返回纯文本）。
     * 归属校验：只能查看当前用户自己的会话。
     */
    @GetMapping("/{sessionId}/messages")
    public ResponseEntity<List<Map<String, Object>>> messages(@PathVariable String sessionId) {
        Long userId = requireUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (chatSessionService.getOwned(userId, sessionId) == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        List<Message> msgs = chatMemory.get(memoryKey(userId, sessionId));
        List<Map<String, Object>> data = new ArrayList<>();
        for (Message m : msgs) {
            if (m.getMessageType() == MessageType.USER) {
                data.add(msg("user", m.getText()));
            } else if (m.getMessageType() == MessageType.ASSISTANT
                    && !(m instanceof AssistantMessage am && am.hasToolCalls())) {
                data.add(msg("assistant", m.getText()));
            }
        }
        return ResponseEntity.ok(data);
    }

    /**
     * 删除会话：删 MySQL 会话元数据 + Redis 记忆（幂等，不存在返回 404）
     */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String sessionId) {
        Long userId = requireUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        ChatSessionEntity s = chatSessionService.getOwned(userId, sessionId);
        if (s == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        chatSessionService.removeById(s.getId());
        chatMemory.clear(memoryKey(userId, sessionId));
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** Redis 记忆 key，与 KnowledgeDocumentService.conversationId 拼装规则一致 */
    private String memoryKey(Long userId, String sessionId) {
        return userId + ":" + sessionId;
    }

    private Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private Long requireUserId() {
        return UserContext.getUserId();
    }
}
