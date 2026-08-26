package com.example.springairagdemo.controller;

import com.example.springairagdemo.entity.AgentTaskEntity;
import com.example.springairagdemo.entity.AgentTaskStepEntity;
import com.example.springairagdemo.entity.KnowledgeBaseEntity;
import com.example.springairagdemo.security.UserContext;
import com.example.springairagdemo.service.AgentTaskService;
import com.example.springairagdemo.service.KbAuthorizationService;
import com.example.springairagdemo.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent 任务执行轨迹查询 REST API（/api/agent-task）
 * <p>
 * 数据权限：
 * <ul>
 *   <li>普通用户只能查询自己的任务（user_id = 当前登录用户），指定 kbId 时必须是对当前用户可见的知识库</li>
 *   <li>ADMIN（全局角色，远程委托用户服务判定）可查询全部任务、任意任务详情</li>
 * </ul>
 * 权限控制与 {@link KnowledgeDocumentController} 一致：不依赖知识库角色，仅按「归属 + 可见性」拦截。
 */
@RestController
@RequestMapping("/api/agent-task")
@Slf4j
@RequiredArgsConstructor
public class AgentTaskController {

    private final AgentTaskService agentTaskService;
    private final KbAuthorizationService kbAuthorizationService;
    private final KnowledgeBaseService knowledgeBaseService;

    /** Jackson 3 序列化：解析 agent_task.sources JSON 快照为前端可读结构 */
    @Autowired
    private tools.jackson.databind.ObjectMapper objectMapper;

    /**
     * 任务列表（创建时间倒序 + 分页）
     *
     * @param kbId    知识库 ID（可选，指定时必须对当前用户可见）
     * @param status  任务状态（可选，0 执行中 1 成功 2 失败）
     * @param keyword 问题关键词模糊搜索（可选）
     * @param page    页码（默认 1）
     * @param size    每页条数（默认 20，上限 100）
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) Long kbId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        Long currentUserId = UserContext.getUserId();
        if (currentUserId == null) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "未登录"));
        }

        // 数据权限：非 ADMIN 只能查自己的任务；ADMIN 可查全部
        Long queryUserId = kbAuthorizationService.isAdmin() ? null : currentUserId;

        // 指定知识库时前置可见性校验（防枚举他人知识库任务；visible=null 表示 ADMIN 全部可见）
        List<Long> visible = kbAuthorizationService.visibleKbIds();
        if (kbId != null && visible != null && !visible.contains(kbId)) {
            return ResponseEntity.ok(Map.of("success", true, "total", 0, "data", List.of()));
        }

        page = Math.max(1, page);
        size = Math.max(1, Math.min(size, 100));

        long total = agentTaskService.countTasks(queryUserId, kbId, status, keyword);
        if (total == 0) {
            return ResponseEntity.ok(Map.of("success", true, "total", 0, "data", List.of()));
        }
        List<AgentTaskEntity> tasks = agentTaskService.listTasks(queryUserId, kbId, status, keyword, page, size);

        // 批量关联知识库名（避免 N+1）
        Set<Long> kbIds = tasks.stream()
                .map(AgentTaskEntity::getKbId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> kbNameMap = knowledgeBaseService.listByIds(kbIds).stream()
                .collect(Collectors.toMap(KnowledgeBaseEntity::getId, KnowledgeBaseEntity::getName));

        List<Map<String, Object>> data = tasks.stream().map(t -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", t.getId());
            item.put("kbId", t.getKbId());
            item.put("kbName", kbNameMap.get(t.getKbId()));
            item.put("question", t.getQuestion());
            item.put("status", t.getStatus());
            item.put("statusText", statusText(t.getStatus()));
            item.put("toolCount", t.getToolCount());
            item.put("costMs", t.getCostMs());
            item.put("errorMsg", t.getErrorMsg());
            item.put("createTime", t.getCreateTime());
            item.put("finishTime", t.getFinishTime());
            return item;
        }).toList();

        return ResponseEntity.ok(Map.of("success", true, "total", total, "data", data));
    }

    /**
     * 任务详情（含工具步骤轨迹 agent_task_step，按执行顺序排列）
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable Long id) {
        Long currentUserId = UserContext.getUserId();
        if (currentUserId == null) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "未登录"));
        }

        AgentTaskEntity task = agentTaskService.getTask(id);
        if (task == null) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", "任务不存在"));
        }
        // 数据权限：非 ADMIN 只能查看自己的任务（防越权访问他人执行轨迹）
        if (!kbAuthorizationService.isAdmin() && !currentUserId.equals(task.getUserId())) {
            return ResponseEntity.status(403).body(Map.of("success", false, "message", "无权查看该任务"));
        }

        List<AgentTaskStepEntity> steps = agentTaskService.listSteps(id);
        List<Map<String, Object>> stepList = steps.stream().map(s -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", s.getId());
            item.put("type", s.getType());
            item.put("toolName", s.getToolName());
            item.put("status", s.getStatus());
            item.put("args", s.getArgs());
            item.put("result", s.getResult());
            item.put("latencyMs", s.getLatencyMs());
            item.put("createTime", s.getCreateTime());
            return item;
        }).toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", task.getId());
        data.put("userId", task.getUserId());
        data.put("sessionId", task.getSessionId());
        data.put("kbId", task.getKbId());
        data.put("question", task.getQuestion());
        data.put("answer", task.getAnswer());
        // Agent 可观测性字段
        data.put("sources", parseSources(task.getSources()));
        data.put("prompt", task.getPrompt());
        data.put("model", task.getModel());
        data.put("promptTokens", task.getPromptTokens());
        data.put("completionTokens", task.getCompletionTokens());
        data.put("totalTokens", task.getTotalTokens());
        data.put("status", task.getStatus());
        data.put("statusText", statusText(task.getStatus()));
        data.put("toolCount", task.getToolCount());
        data.put("costMs", task.getCostMs());
        data.put("errorMsg", task.getErrorMsg());
        data.put("startMs", task.getStartMs());
        data.put("createTime", task.getCreateTime());
        data.put("finishTime", task.getFinishTime());
        data.put("steps", stepList);
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    /**
     * 解析引用来源快照 JSON（agent_task.sources）。解析失败返回空数组，不影响详情展示。
     */
    private List<Map<String, Object>> parseSources(String sourcesJson) {
        if (sourcesJson == null || sourcesJson.isBlank()) {
            return List.of();
        }
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = objectMapper.readValue(sourcesJson,
                    new tools.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            return list == null ? List.of() : list;
        } catch (Exception e) {
            log.warn("引用来源快照解析失败（taskId 关联查询）：{}", e.getMessage());
            return List.of();
        }
    }

    private String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case AgentTaskService.STATUS_RUNNING -> "执行中";
            case AgentTaskService.STATUS_SUCCESS -> "成功";
            case AgentTaskService.STATUS_FAILED -> "失败";
            default -> "未知";
        };
    }
}
