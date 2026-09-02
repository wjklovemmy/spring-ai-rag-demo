package com.example.springairagdemo.controller;

import com.example.springairagdemo.entity.UserLongTermMemoryEntity;
import com.example.springairagdemo.service.KbAuthorizationService;
import com.example.springairagdemo.service.UserClient;
import com.example.springairagdemo.service.UserLongTermMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆全局概览（仅管理员）：全站记忆统计与明细分页。
 *
 * <p>长期记忆表位于 RAG 业务库，本控制器放在 RAG 域，经网关 /api/** 到达；
 * 管理员判定与知识库管理一致（远程委托用户服务），非管理员统一 403。
 * 明细行尽力补全用户名/昵称（用户服务不可用时降级仅显示 userId）。
 */
@Slf4j
@RestController
@RequestMapping("/api/memory/admin")
@RequiredArgsConstructor
public class MemoryAdminController {

    private final UserLongTermMemoryService memoryTableService;
    private final KbAuthorizationService kbAuthorizationService;
    private final UserClient userClient;

    /** 全局记忆概览统计（仅管理员） */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        if (!kbAuthorizationService.isAdmin()) {
            return forbidden();
        }
        return ResponseEntity.ok(Map.of("success", true, "data", memoryTableService.adminStats()));
    }

    /** 记忆明细分页（仅管理员），page 从 1 开始，size 默认 20（上限 100） */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (!kbAuthorizationService.isAdmin()) {
            return forbidden();
        }
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        long total = memoryTableService.adminCount(userId, category, keyword);
        List<UserLongTermMemoryEntity> rows = memoryTableService.adminList(
                userId, category, keyword, (safePage - 1) * safeSize, safeSize);

        Map<Long, UserClient.UserBrief> briefs = userClient.findUsers(
                rows.stream().map(UserLongTermMemoryEntity::getUserId).toList());

        List<Map<String, Object>> data = new ArrayList<>(rows.size());
        for (UserLongTermMemoryEntity r : rows) {
            UserClient.UserBrief brief = r.getUserId() == null ? null : briefs.get(r.getUserId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.getId());
            item.put("userId", r.getUserId());
            item.put("username", brief == null ? null : brief.username());
            item.put("nickname", brief == null ? null : brief.nickname());
            item.put("content", r.getContent());
            item.put("category", r.getCategory());
            item.put("importance", r.getImportance());
            item.put("vectorStatus", r.getVectorStatus());
            item.put("sourceSession", r.getSourceSession());
            item.put("createTime", r.getCreateTime());
            item.put("updateTime", r.getUpdateTime());
            data.add(item);
        }
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "查询成功",
                "total", total,
                "page", safePage,
                "size", safeSize,
                "data", data));
    }

    private ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(403)
                .body(Map.of("success", false, "message", "仅管理员可查看全局记忆概览"));
    }
}
