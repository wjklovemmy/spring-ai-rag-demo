package com.example.springairagdemo.tools;

import com.example.springairagdemo.config.RagConfigProperties;
import com.example.springairagdemo.service.MemoryService;
import com.example.springairagdemo.service.RagRetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户长期记忆工具（Phase 2）：saveMemory / searchMemory。
 *
 * <p>由 Agent 显式调用，实现"记住用户 / 想起用户"的长期记忆闭环：
 * <ul>
 *   <li>{@code saveMemory}：用户透露稳定的个人事实/偏好/经历时保存一条长期记忆
 *       （语义去重 + MySQL 落库 + Milvus 向量 upsert，经 {@link MemoryService}）；</li>
 *   <li>{@code searchMemory}：按主题查询该用户的长期记忆（userId 过滤 + 余弦召回）。</li>
 * </ul>
 * 用户 ID / 会话 ID 从 ToolContext 读取（Service 层注入，线程安全）；工具事件经
 * {@link RagRetrievalService#emitToolEvent} 发布供 SSE 展示。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryTools {

    public static final String SAVE_TOOL_NAME = "saveMemory";
    public static final String SEARCH_TOOL_NAME = "searchMemory";

    private final MemoryService memoryService;
    private final RagRetrievalService ragRetrievalService;
    private final RagConfigProperties ragConfig;

    /**
     * 保存用户长期记忆。
     */
    @Tool(description = "把用户透露的、值得长期记住的个人事实/偏好/经历保存为用户长期记忆，跨会话生效。"
            + "当用户提到以下信息时调用：身份/岗位/公司、生日年龄、饮食或生活习惯、兴趣爱好、"
            + "家庭成员、重要目标与计划、已确认的安排等。仅保存确定且稳定的信息，一次只保存一条；"
            + "知识库文档内容不要保存为记忆。保存成功/重复会返回提示。")
    public String saveMemory(
            @ToolParam(description = "要保存的记忆内容，一条 = 一个独立事实/偏好，用简洁陈述句表达，"
                    + "如：用户喜欢喝拿铁咖啡 / 用户是产品经理 / 用户每年有 5 天年假") String content,
            @ToolParam(description = "记忆类别（可选）：fact 事实 / preference 偏好 / interest 兴趣 / "
                    + "goal 目标 / event 经历，默认 fact") String category,
            @ToolParam(description = "重要度 1-10 整数（可选，默认 5）：生日、身份、岗位等稳定信息 9-10；"
                    + "一般偏好 5-7；次要细节 1-4") Integer importance,
            ToolContext toolContext) {
        Long userId = userIdOf(toolContext);
        emitToolEvent(toolContext, SAVE_TOOL_NAME, RagRetrievalService.ToolEvent.STATUS_RUNNING,
                "保存长期记忆", null);
        if (userId == null) {
            String error = "未获取到当前用户身份，无法保存长期记忆";
            emitToolEvent(toolContext, SAVE_TOOL_NAME, RagRetrievalService.ToolEvent.STATUS_ERROR,
                    "保存长期记忆", error);
            return error;
        }
        String message = memoryService.save(userId, content, category, importance,
                conversationIdOf(toolContext)).message();
        emitToolEvent(toolContext, SAVE_TOOL_NAME, RagRetrievalService.ToolEvent.STATUS_DONE,
                "保存长期记忆：" + (content == null ? "" : content.trim()), message);
        return message;
    }

    /**
     * 查询用户长期记忆。
     */
    @Tool(description = "查询该用户的长期记忆（个人事实/偏好/经历）。"
            + "当需要基于用户历史偏好/个人事实回答时调用，例如用户问“我之前说过什么”“我的偏好是什么”"
            + "“我是什么岗位/做什么的”“按我上次提的要求处理”，或系统注入的【用户长期记忆】背景不足时。"
            + "返回匹配的记忆列表，供个性化回答参考，不要把记忆内容标注为知识库来源。")
    public String searchMemory(
            @ToolParam(description = "要查询的记忆主题，自然语言描述即可，"
                    + "如：我平时喜欢喝什么 / 我的岗位 / 用户的重要目标") String query,
            ToolContext toolContext) {
        Long userId = userIdOf(toolContext);
        emitToolEvent(toolContext, SEARCH_TOOL_NAME, RagRetrievalService.ToolEvent.STATUS_RUNNING,
                "查询长期记忆", null);
        if (userId == null) {
            String error = "未获取到当前用户身份，无法查询长期记忆";
            emitToolEvent(toolContext, SEARCH_TOOL_NAME, RagRetrievalService.ToolEvent.STATUS_ERROR,
                    "查询长期记忆", error);
            return error;
        }
        RagConfigProperties.LongTerm lt = ragConfig.getMemory().getLongTerm();
        int topK = lt == null ? 8 : Math.max(1, lt.getSearchTopK());
        double minScore = lt == null ? 0.3 : lt.getMinScore();
        List<MemoryService.MemoryHit> hits = memoryService.search(userId, query, topK, minScore);
        if (hits.isEmpty()) {
            String none = "未检索到与“" + query + "”相关的用户长期记忆（该用户可能还没有相关记忆）";
            emitToolEvent(toolContext, SEARCH_TOOL_NAME, RagRetrievalService.ToolEvent.STATUS_DONE,
                    "查询长期记忆", none);
            return none;
        }
        StringBuilder builder = new StringBuilder("检索到该用户以下长期记忆（仅作个性化参考，不要当作知识库来源）：\n");
        int index = 0;
        for (MemoryService.MemoryHit hit : hits) {
            if (hit.content() == null || hit.content().isBlank()) {
                continue;
            }
            builder.append(++index)
                    .append(". [").append(MemoryService.categoryLabel(hit.category()))
                    .append("·重要度").append(hit.importance() == null ? 5 : hit.importance())
                    .append("] ").append(hit.content()).append('\n');
        }
        String message = index == 0 ? "未检索到有效长期记忆内容" : builder.toString().trim();
        emitToolEvent(toolContext, SEARCH_TOOL_NAME, RagRetrievalService.ToolEvent.STATUS_DONE,
                "查询长期记忆：" + query, message);
        return message;
    }

    /** 从 ToolContext 读取当前用户 ID（与 RagRetrievalService.canView 一致的读取方式） */
    private Long userIdOf(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get(RagRetrievalService.USER_ID_KEY);
        return value instanceof Number number ? number.longValue() : null;
    }

    /** 从 ToolContext 读取会话记忆 ID（userId:sessionId），作为记忆来源标识 */
    private String conversationIdOf(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get(RagRetrievalService.CONVERSATION_ID_KEY);
        return value instanceof String text ? text : null;
    }

    /** 发布工具事件（SSE 展示用），与 CalculatorTool 一致：Sink 未注入时静默跳过 */
    private void emitToolEvent(ToolContext toolContext, String toolName, String status, String args, String result) {
        try {
            ragRetrievalService.emitToolEvent(toolContext, toolName, status, args, result);
        } catch (Exception e) {
            log.debug("工具事件发布失败（可忽略）：{}", e.getMessage());
        }
    }
}
