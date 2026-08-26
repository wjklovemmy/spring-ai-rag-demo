package com.example.springairagdemo.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.springairagdemo.entity.AgentTaskEntity;
import com.example.springairagdemo.entity.ChatSessionEntity;
import com.example.springairagdemo.security.UserContext;
import com.example.springairagdemo.service.AgentTaskService;
import com.example.springairagdemo.service.ChatSessionService;
import com.example.springairagdemo.service.KnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天会话管理（会话列表 / 切换 / 删除）。
 * 会话元数据存 MySQL（chat_session），消息历史存 Redis（ChatMemory）。
 * 会话按当前登录用户隔离（UserContext，由网关注入）；未登录一律 401。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat-session")
@RequiredArgsConstructor
public class ChatSessionController {

    private final ChatSessionService chatSessionService;
    private final ChatMemory chatMemory;
    private final AgentTaskService agentTaskService;
    private final ObjectMapper objectMapper;

    /**
     * 创建新会话：后端生成 sessionId，返回给前端作为聊天会话标识。
     * 若该用户在该知识库下已存在"空会话"（从未问答、Redis 无消息历史），
     * 直接复用返回，避免反复点击"新建对话"堆积空白会话。
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
        // 复用现有空会话：同一用户 + 同一知识库 + Redis 无消息（从未问答）
        LambdaQueryWrapper<ChatSessionEntity> qw = Wrappers.<ChatSessionEntity>lambdaQuery()
                .eq(ChatSessionEntity::getUserId, userId);
        if (kbId == null) {
            qw.isNull(ChatSessionEntity::getKnowledgeBaseId);
        } else {
            qw.eq(ChatSessionEntity::getKnowledgeBaseId, kbId);
        }
        qw.orderByDesc(ChatSessionEntity::getUpdateTime);
        for (ChatSessionEntity e : chatSessionService.list(qw)) {
            if (chatMemory.get(memoryKey(userId, e.getSessionId())).isEmpty()) {
                log.debug("复用空会话：userId={}, sessionId={}", userId, e.getSessionId());
                return ResponseEntity.ok(toSession(e));
            }
        }
        ChatSessionEntity s = chatSessionService.createSession(userId, kbId);
        return ResponseEntity.ok(toSession(s));
    }

    /**
     * 当前用户会话列表（按最近更新倒序）。
     * 过滤空会话：从未问答（Redis 无消息）的会话不返回，避免列表堆积空白会话。
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
            // 空会话（从未问答）不出现在会话列表
            if (chatMemory.get(memoryKey(userId, s.getSessionId())).isEmpty()) {
                continue;
            }
            data.add(toSession(s));
        }
        return ResponseEntity.ok(data);
    }

    /** 会话元数据 → 接口返回结构（create / list 共用） */
    private Map<String, Object> toSession(ChatSessionEntity s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", s.getSessionId());
        m.put("title", s.getTitle() == null || s.getTitle().isBlank() ? "新对话" : s.getTitle());
        m.put("knowledgeBaseId", s.getKnowledgeBaseId());
        m.put("createTime", s.getCreateTime());
        m.put("updateTime", s.getUpdateTime());
        return m;
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
        // 历史消息引用来源回补：agent_task 按 (userId, sessionId) 时间升序落库，
        // 一次问答一条，与过滤工具调用后的 assistant 消息一一对应
        List<AgentTaskEntity> tasks = agentTaskService.listBySession(userId, sessionId);
        List<Map<String, Object>> data = new ArrayList<>();
        int taskIdx = 0;
        for (Message m : msgs) {
            if (m.getMessageType() == MessageType.USER) {
                data.add(msg("user", m.getText()));
            } else if (m.getMessageType() == MessageType.ASSISTANT
                    && !(m instanceof AssistantMessage am && am.hasToolCalls())) {
                Map<String, Object> am = msg("assistant", m.getText());
                if (taskIdx < tasks.size()) {
                    AgentTaskEntity task = tasks.get(taskIdx++);
                    // 引用来源仅随实际引用展示：回答中无 [来源N]（澄清性提问等）回补为空，
                    // 与实时问答的展示规则保持一致（agent_task 快照仍保留完整候选供审计查看）
                    List<Map<String, Object>> srcs = KnowledgeDocumentService.hasSourceRefs(task.getAnswer())
                            ? parseSources(task.getSources()) : List.of();
                    am.put("sources", srcs);
                }
                data.add(am);
            }
        }
        return ResponseEntity.ok(data);
    }

    /** 解析 agent_task 落库的引用来源快照（JSON 数组字符串 → List） */
    private List<Map<String, Object>> parseSources(String sourcesJson) {
        if (sourcesJson == null || sourcesJson.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> list = objectMapper.readValue(sourcesJson,
                    new tools.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            return list == null ? List.of() : list;
        } catch (Exception e) {
            log.warn("历史消息引用来源解析失败：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 批量删除会话：逐条归属校验（仅删当前用户自己的），MySQL 元数据 + Redis 记忆同时清理。
     * 接口幂等：不存在的 sessionId 直接跳过，返回实际删除数量。
     *
     * @param body {"sessionIds": ["a", "b", ...]}
     */
    @PostMapping("/batch-delete")
    public ResponseEntity<Map<String, Object>> batchDelete(@RequestBody(required = false) Map<String, Object> body) {
        Long userId = requireUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Object raw = body == null ? null : body.get("sessionIds");
        if (!(raw instanceof List<?> ids) || ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "sessionIds 不能为空"));
        }
        int deleted = 0;
        for (Object o : ids) {
            String sessionId = String.valueOf(o);
            ChatSessionEntity s = chatSessionService.getOwned(userId, sessionId);
            if (s == null) {
                continue;
            }
            // 逻辑删除：会话 + 级联 Agent 任务/步骤（记录删除人/时间，数据保留供审计追溯）
            chatSessionService.logicalDelete(userId, sessionId, userId);
            chatMemory.clear(memoryKey(userId, sessionId));
            deleted++;
        }
        return ResponseEntity.ok(Map.of("success", true, "deleted", deleted));
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
        // 逻辑删除：会话 + 级联 Agent 任务/步骤（记录删除人/时间，数据保留供审计追溯）
        chatSessionService.logicalDelete(userId, sessionId, userId);
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
