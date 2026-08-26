package com.example.springairagdemo.tools;

import com.example.springairagdemo.config.RagConfigProperties;
import com.example.springairagdemo.entity.DocumentStatus;
import com.example.springairagdemo.entity.KnowledgeChunkEntity;
import com.example.springairagdemo.entity.KnowledgeDocumentEntity;
import com.example.springairagdemo.parser.HeadingExtractor;
import com.example.springairagdemo.security.KbRole;
import com.example.springairagdemo.service.KbAuthorizationService;
import com.example.springairagdemo.service.KnowledgeChunkEntityService;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final KnowledgeChunkEntityService knowledgeChunkEntityService;
    private final KbAuthorizationService kbAuthorizationService;
    private final HeadingExtractor headingExtractor;
    private final RagConfigProperties config;

    /** 标题链前缀模式：摄取时按 prefixTemplate 【{heading}】注入每个 chunk 文本开头 */
    private static final Pattern HEADING_PREFIX = Pattern.compile("^【([^】]+)】");
    /** 单份文档大纲标题数量上限，避免超大文档撑爆上下文 */
    private static final int OUTLINE_LIMIT = 200;
    /** 带编号的章节标题模式（数字序号 / 中文数字序数 / 第X章 / （一）），用于过滤封面标题、页眉等无编号标题 */
    private static final Pattern NUMBERED_HEADING = Pattern.compile(
            "\\d+(?:\\.\\d+)*\\s*[.、．:：]"
                    + "|[一二三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+\\s*[.、．:：]"
                    + "|第[一二三四五六七八九十百千万\\d]+[章节篇部部分条]"
                    + "|[（(][一二三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+[)）]");

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
     * 查询文档的完整标题大纲（章节/小节结构）。
     *
     * <p>标题数据来源：摄取时每个 chunk 文本都按 prefixTemplate 注入了【标题链】前缀，
     * 因此按页面/序号顺序扫描全量 chunk、提取前缀即可还原文档大纲，无需额外存储、无需重新摄取。
     */
    @Tool(description = "查询指定文档的完整标题大纲（章节/小节结构），返回文档包含的标题清单与总数。"
            + "当用户询问某文档包含几部分/几章/几个小节/哪些章节、文档结构、目录大纲等问题时，必须调用本工具。"
            + "可传入文档名关键词定位文档；省略时返回当前知识库全部活跃文档的大纲。")
    public String documentOutline(
            @ToolParam(description = "文档名关键词，例如软件或说明书；省略则查询当前知识库全部文档") String keyword,
            ToolContext toolContext) {
        Long kbId = requireKbId(toolContext);
        if (kbId == null) {
            return "当前未指定知识库，无法查询文档大纲";
        }
        if (!canView(toolContext, kbId)) {
            return "无权访问该知识库";
        }
        String kw = keyword == null ? "" : keyword.trim();
        List<KnowledgeDocumentEntity> docs = activeDocuments(kbId);
        if (docs.isEmpty()) {
            return "当前知识库中暂无文档";
        }
        if (!kw.isEmpty()) {
            docs = docs.stream()
                    .filter(d -> d.getFileName() != null
                            && d.getFileName().toLowerCase().contains(kw.toLowerCase()))
                    .toList();
            if (docs.isEmpty()) {
                return "未找到文件名包含“" + kw + "”的文档";
            }
        }

        StringBuilder sb = new StringBuilder();
        for (KnowledgeDocumentEntity doc : docs) {
            sb.append(buildOutline(doc)).append(System.lineSeparator());
        }
        return sb.toString();
    }

    /**
     * 构建单份文档的标题大纲文本：按页码/序号扫描全部 chunk，双通道提取标题——
     * ①【标题链】前缀（摄取时注入）；②正文逐行识别（复用 HeadingExtractor 规则，
     * 覆盖"标题链未注入前缀"的场景，如每页页眉占位导致所有 chunk 只挂根标题）。
     * <p>
     * 输出规则：若识别到带编号的章节标题（"一、""1.1""第一章"等），则过滤掉无编号标题
     * （多为文档封面标题、页眉等，如文档名/副标题），大纲直接逐行列出标题文本，不带序号。
     */
    private String buildOutline(KnowledgeDocumentEntity doc) {
        List<KnowledgeChunkEntity> chunks = knowledgeChunkEntityService.lambdaQuery()
                .eq(KnowledgeChunkEntity::getDocumentId, doc.getId())
                .orderByAsc(KnowledgeChunkEntity::getPageNo)
                .orderByAsc(KnowledgeChunkEntity::getChunkIndex)
                .list();

        RagConfigProperties.Heading headingCfg = config.getDocument().getChunk().getHeading();

        // 保持首次出现顺序去重（同一标题会出现在其下多个 chunk 中）
        LinkedHashSet<String> headings = new LinkedHashSet<>();
        for (KnowledgeChunkEntity chunk : chunks) {
            if (chunk.getContent() == null) continue;
            String content = chunk.getContent();
            // 通道①：剥离【标题链】前缀，提取前缀标题；前缀不属于正文，剥离后避免重复识别
            Matcher m = HEADING_PREFIX.matcher(content);
            if (m.find()) {
                headings.add(m.group(1).trim());
                content = content.substring(m.end());
            }
            // 通道②：正文逐行识别标题行（与摄取时同一套启发式规则）
            if (headingCfg.isEnabled()) {
                for (HeadingExtractor.HeadingLine h : headingExtractor.extract(content, headingCfg)) {
                    headings.add(h.title().trim());
                }
            }
            if (headings.size() >= OUTLINE_LIMIT) break;
        }

        // 存在带编号的章节标题时，过滤掉无编号标题（封面标题/页眉等，如文档名、副标题）；
        // 整篇文档都无编号时（纯无序号版式）则全部保留作为大纲
        List<String> outline;
        boolean hasNumbered = headings.stream().anyMatch(this::isNumberedHeading);
        if (hasNumbered) {
            outline = headings.stream().filter(this::isNumberedHeading).toList();
        } else {
            outline = new ArrayList<>(headings);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("《").append(doc.getFileName()).append("》");
        if (outline.isEmpty()) {
            sb.append("：未识别到标题结构（可能为无标题版式文档）");
            return sb.toString();
        }
        sb.append("共 ").append(outline.size()).append(" 个标题：").append(System.lineSeparator());
        for (String h : outline) {
            sb.append(h).append(System.lineSeparator());
        }
        if (outline.size() >= OUTLINE_LIMIT) {
            sb.append("（标题较多，仅列出前 ").append(OUTLINE_LIMIT).append(" 个）");
        }
        return sb.toString();
    }

    /** 标题文本是否带编号（数字序号 / 中文数字序数 / 第X章 / （一）），用于大纲过滤无编号封面/页眉标题 */
    private boolean isNumberedHeading(String title) {
        return title != null && NUMBERED_HEADING.matcher(title).find();
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
