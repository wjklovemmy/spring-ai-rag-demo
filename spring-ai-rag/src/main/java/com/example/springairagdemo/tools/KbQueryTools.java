package com.example.springairagdemo.tools;

import com.example.springairagdemo.entity.DocumentStatus;
import com.example.springairagdemo.entity.KnowledgeDocumentEntity;
import com.example.springairagdemo.security.KbRole;
import com.example.springairagdemo.service.KbAuthorizationService;
import com.example.springairagdemo.service.KnowledgeDocumentEntityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库查询工具集（Spring AI Tool Calling / Function Calling）。
 *
 * <p>背景：文档名等文档级元数据不在 chunk 正文中，纯向量检索无法回答
 * "知识库中有哪些文档""有没有某份文档"等枚举/定位类问题。与其在 Service 里穷举关键词
 * 兜底注入，不如把查询能力注册为模型可自主调用的工具：模型判断问题需要结构化数据时
 * 主动调用工具查询 MySQL，通用且可扩展（后续可继续加"知识库列表""文档小节标题"等工具）。
 *
 * <p>请求级上下文（当前知识库 ID、当前用户 ID）通过 {@link ToolContext} 注入，
 * 由 Service 层在请求线程内设置——工具回调线程可能不在请求线程，不能依赖 UserContext 线程变量，
 * 因此权限校验也使用显式 userId 版本的 {@link KbAuthorizationService#canAccess}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbQueryTools {

    /** ToolContext 中当前知识库 ID 的 key（由 Service 层注入） */
    public static final String KB_ID_KEY = "knowledgeBaseId";
    /** ToolContext 中当前用户 ID 的 key（由 Service 层注入） */
    public static final String USER_ID_KEY = "userId";

    private final KnowledgeDocumentEntityService knowledgeDocumentEntityService;
    private final KbAuthorizationService kbAuthorizationService;

    /**
     * 列出当前知识库中收录的所有活跃文档（文件名、版本号、内容片段数）。
     */
    @Tool(description = "列出当前知识库中收录的所有活跃文档清单（文件名、版本号、内容片段数）。"
            + "当用户询问知识库中有哪些文档/文件、文档列表/清单、收录/上传了什么文档等问题时，必须调用本工具。")
    public String listDocuments(ToolContext toolContext) {
        Long kbId = requireKbId(toolContext);
        if (kbId == null) {
            return "当前未指定知识库，无法查询文档清单";
        }
        if (!canView(toolContext, kbId)) {
            return "无权访问该知识库";
        }
        String inventory = inventoryText(kbId);
        return inventory.isEmpty() ? "当前知识库中暂无文档" : inventory;
    }

    /**
     * 按文件名关键词搜索当前知识库中的文档。
     */
    @Tool(description = "按文件名关键词搜索当前知识库中的文档，返回匹配的文档清单。"
            + "当用户想查找/定位某份具体文档（记得部分文件名）时调用；关键词为空时等同列出全部文档。")
    public String searchDocuments(
            @ToolParam(description = "文件名关键词，例如软件或说明书") String keyword,
            ToolContext toolContext) {
        Long kbId = requireKbId(toolContext);
        if (kbId == null) {
            return "当前未指定知识库，无法搜索文档";
        }
        if (!canView(toolContext, kbId)) {
            return "无权访问该知识库";
        }
        String kw = keyword == null ? "" : keyword.trim();
        List<KnowledgeDocumentEntity> matched = activeDocuments(kbId).stream()
                .filter(d -> kw.isEmpty() || d.getFileName() != null
                        && d.getFileName().toLowerCase().contains(kw.toLowerCase()))
                .toList();
        if (matched.isEmpty()) {
            return kw.isEmpty() ? "当前知识库中暂无文档"
                    : "未找到文件名包含“" + kw + "”的文档";
        }
        return formatInventory(matched);
    }

    /**
     * 构建当前知识库的活跃文档清单文本（无权限校验，供 Service 层检索兜底复用）。
     * 过滤规则与检索一致：仅 SUCCESS/DEPRECATED、未过期、启用（is_active 为空视为启用），
     * 同名多版本只保留最高版本，按最近创建倒序。
     *
     * @return 清单文本；知识库 ID 为空或无活跃文档时返回空字符串
     */
    public String inventoryText(Long kbId) {
        if (kbId == null) {
            return "";
        }
        List<KnowledgeDocumentEntity> docs = activeDocuments(kbId);
        return docs.isEmpty() ? "" : formatInventory(docs);
    }

    // ===================== 内部方法 =====================

    /** 校验当前用户对知识库至少有 VIEWER 权限（显式 userId，不依赖请求线程） */
    private boolean canView(ToolContext toolContext, Long kbId) {
        if (toolContext == null || toolContext.getContext() == null) {
            return false;
        }
        Object userIdObj = toolContext.getContext().get(USER_ID_KEY);
        if (!(userIdObj instanceof Number n)) {
            return false;
        }
        return kbAuthorizationService.canAccess(n.longValue(), kbId, KbRole.VIEWER);
    }

    private Long requireKbId(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object v = toolContext.getContext().get(KB_ID_KEY);
        return v instanceof Number n ? n.longValue() : null;
    }

    /** 查询活跃文档（同名多版本只保留最高版本，按最近创建倒序） */
    private List<KnowledgeDocumentEntity> activeDocuments(Long kbId) {
        Date now = new Date();
        List<KnowledgeDocumentEntity> docs = knowledgeDocumentEntityService.lambdaQuery()
                .eq(KnowledgeDocumentEntity::getKnowledgeId, kbId)
                .in(KnowledgeDocumentEntity::getStatus,
                        DocumentStatus.SUCCESS.getCode(), DocumentStatus.DEPRECATED.getCode())
                .and(w -> w.isNull(KnowledgeDocumentEntity::getIsActive)
                        .or()
                        .eq(KnowledgeDocumentEntity::getIsActive, 1))
                .and(w -> w.isNull(KnowledgeDocumentEntity::getExpireTime)
                        .or()
                        .gt(KnowledgeDocumentEntity::getExpireTime, now))
                .orderByDesc(KnowledgeDocumentEntity::getVersion)
                .list();
        // 同名多版本只保留最高版本（版本倒序后首条即最高版）
        Map<String, KnowledgeDocumentEntity> latestByName = new LinkedHashMap<>();
        for (KnowledgeDocumentEntity doc : docs) {
            latestByName.putIfAbsent(doc.getFileName(), doc);
        }
        List<KnowledgeDocumentEntity> latest = new ArrayList<>(latestByName.values());
        latest.sort(Comparator.comparing(KnowledgeDocumentEntity::getCreateTime,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return latest;
    }

    /** 格式化文档清单文本 */
    private String formatInventory(List<KnowledgeDocumentEntity> docs) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前知识库中收录了以下 ").append(docs.size())
                .append(" 份文档：").append(System.lineSeparator());
        int idx = 1;
        for (KnowledgeDocumentEntity doc : docs) {
            sb.append(idx++).append(". ").append(doc.getFileName());
            if (doc.getVersion() != null && doc.getVersion() > 1) {
                sb.append("（版本 ").append(doc.getVersion()).append("）");
            }
            if (doc.getChunkCount() != null) {
                sb.append("，共 ").append(doc.getChunkCount()).append(" 个内容片段");
            }
            sb.append(System.lineSeparator());
        }
        return sb.toString();
    }
}
