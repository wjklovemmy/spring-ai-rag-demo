package com.example.springairagdemo.controller;

import com.example.springairagdemo.entity.UserLongTermMemoryEntity;
import com.example.springairagdemo.security.UserContext;
import com.example.springairagdemo.service.MemoryExtractionService;
import com.example.springairagdemo.service.MemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用户长期记忆管理 API（Phase 2）：本人长期记忆的查看 / 手工新增 / 删除 / 触发沉淀。
 *
 * <p>数据严格限定为当前登录用户（{@link UserContext}），由网关透传的用户身份驱动，不提供跨用户访问。
 */
@Slf4j
@RestController
@RequestMapping("/api/memory")
@RequiredArgsConstructor
public class UserMemoryController {

    private final MemoryService memoryService;
    private final MemoryExtractionService memoryExtractionService;

    /** 本人长期记忆列表（可过滤类别；limit ≤ 0 不限制条数） */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false, defaultValue = "0") int limit) {
        Long userId = UserContext.getUserId();
        List<UserLongTermMemoryEntity> data = memoryService.list(userId, category, limit);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "查询成功",
                "total", memoryService.count(userId),
                "data", data));
    }

    /** 手工新增一条长期记忆 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> add(@RequestBody(required = false) AddMemoryRequest request) {
        Long userId = UserContext.getUserId();
        String content = request == null ? null : request.content();
        MemoryService.SaveResult result = memoryService.save(userId, content,
                request == null ? null : request.category(),
                request == null ? null : request.importance(), "manual");
        return ResponseEntity.ok(Map.of(
                "success", result.id() != null,
                "duplicate", result.duplicate(),
                "message", result.message(),
                "data", result.id() == null ? null : Map.of("id", result.id())));
    }

    /** 删除本人某条长期记忆（逻辑删除 + 同步删向量） */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        boolean deleted = memoryService.delete(UserContext.getUserId(), id);
        return ResponseEntity.ok(Map.of(
                "success", deleted,
                "message", deleted ? "已删除该条长期记忆" : "删除失败：记忆不存在或不属于当前用户"));
    }

    /** 修改本人某条长期记忆的重要度（1-10，同步重写向量元数据） */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateImportance(@PathVariable Long id,
                                                                @RequestBody(required = false) UpdateMemoryRequest request) {
        if (request == null || request.importance() == null) {
            return ResponseEntity.ok(Map.of("success", false, "message", "重要度不能为空（取值 1-10）"));
        }
        boolean updated = memoryService.updateImportance(UserContext.getUserId(), id, request.importance());
        return ResponseEntity.ok(Map.of(
                "success", updated,
                "message", updated ? "重要度已更新为 " + request.importance() : "更新失败：记忆不存在或不属于当前用户"));
    }

    /** 一键清除本人全部长期记忆（逻辑删除 + 删除该用户全部向量） */
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> clearAll() {
        Long userId = UserContext.getUserId();
        int deleted = memoryService.clearAll(userId);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", deleted > 0 ? "已清除 " + deleted + " 条长期记忆" : "当前没有可清除的记忆",
                "data", Map.of("deleted", deleted)));
    }

    /** 手动触发"近期会话沉淀为长期记忆"（同步执行，返回本次新增条数） */
    @PostMapping("/extract")
    public ResponseEntity<Map<String, Object>> extract() {
        Long userId = UserContext.getUserId();
        int saved = memoryExtractionService.extractNow(userId);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", saved > 0 ? "沉淀完成，新增 " + saved + " 条长期记忆" : "近期会话暂无新的可沉淀内容",
                "data", Map.of("saved", saved)));
    }

    /**
     * 最近一次"自动抽取沉淀"的结果（聊天页轮询提醒用）。
     *
     * @param after 调用方记录的会话结束时刻（毫秒时间戳）；传入后仅返回「晚于该时刻完成」的自动抽取，
     *              避免 30 分钟防抖窗口内的旧结果被重复提示；不传则返回最近一次（含旧结果）
     */
    @GetMapping("/auto-extract-result")
    public ResponseEntity<Map<String, Object>> autoExtractResult(
            @RequestParam(required = false) Long after) {
        MemoryExtractionService.AutoExtractResult result =
                memoryExtractionService.latestAutoExtract(UserContext.getUserId());
        if (result == null || (after != null && result.finishedAtMs() <= after)) {
            return ResponseEntity.ok(Map.of("success", true, "data", null));
        }
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", Map.of("saved", result.saved(), "finishedAtMs", result.finishedAtMs())));
    }

    /** 新增记忆请求体 */
    public record AddMemoryRequest(String content, String category, Integer importance) {
    }

    /** 更新记忆请求体（当前仅支持改重要度） */
    public record UpdateMemoryRequest(Integer importance) {
    }
}
