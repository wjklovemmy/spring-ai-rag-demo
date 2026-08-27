package com.example.springairagdemo.tools;

import com.example.springairagdemo.config.RagConfigProperties;
import com.example.springairagdemo.entity.KnowledgeChunkEntity;
import com.example.springairagdemo.entity.KnowledgeDocumentEntity;
import com.example.springairagdemo.parser.HeadingExtractor;
import com.example.springairagdemo.service.KnowledgeChunkEntityService;
import com.example.springairagdemo.service.RagRetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
 * <p>组件化说明：正文检索链路（{@code resolveExplicitDocuments} + Milvus 混合检索 + Rerank 精排 +
 * 编号累积合并 + 工具事件发布 + 权限校验）已收敛到 {@link RagRetrievalService}，本类仅保留
 * 文档级工具（listDocuments / searchDocuments / documentOutline）与检索工具的委托壳，
 * 避免链路内部实现与工具声明耦合，便于复用与单测。
 *
 * <p>请求级上下文（当前知识库 ID、当前用户 ID）通过 {@link ToolContext} 注入，
 * 由 Service 层在请求线程内设置——工具回调线程可能不在请求线程，不能依赖 UserContext 线程变量，
 * 因此权限校验也使用显式 userId 版本（见 {@link RagRetrievalService#canView}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbQueryTools {

    /** 检索链路组件：searchKnowledge 方法体、显式文档解析、权限校验、工具事件基础设施均收敛于此 */
    private final RagRetrievalService ragRetrievalService;
    private final KnowledgeChunkEntityService knowledgeChunkEntityService;
    private final HeadingExtractor headingExtractor;
    private final RagConfigProperties config;

    /** 标题链前缀模式：摄取时按 prefixTemplate 【{heading}】注入每个 chunk 文本开头 */
    private static final Pattern HEADING_PREFIX = Pattern.compile("^【([^】]+)】");
    /** 带编号的章节标题模式（数字序号 / 中文数字序数 / 第X章 / （一）），用于过滤封面标题、页眉等无编号标题 */
    private static final Pattern NUMBERED_HEADING = Pattern.compile(
            "\\d+(?:\\.\\d+)*\\s*[.、．:：]"
                    + "|[一二三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+\\s*[.、．:：]"
                    + "|第[一二三四五六七八九十百千万\\d]+[章节篇部部分条]"
                    + "|[（(][一二三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+[)）]");

    /**
     * 列出当前知识库中收录的所有活跃文档（文件名、版本号、内容片段数）。
     */
    @Tool(description = "列出当前知识库中收录的活跃文档清单（文件名、版本号、内容片段数）。"
            + "当用户询问知识库中有哪些文档/文件、文档列表/清单、收录/上传了什么文档等问题时，必须调用本工具。"
            + "文档较多时仅返回部分清单（超限自动截断并提示），用户想找具体文档时改用 searchDocuments 按文件名关键词定位。")
    public String listDocuments(ToolContext toolContext) {
        ragRetrievalService.emitToolEvent(toolContext, "listDocuments", "running", "查询知识库文档清单", null);
        String result;
        Long kbId = ragRetrievalService.requireKbId(toolContext);
        if (kbId == null) {
            result = "当前未指定知识库，无法查询文档清单";
        } else if (!ragRetrievalService.canView(toolContext, kbId)) {
            result = "无权访问该知识库";
        } else {
            String inventory = inventoryText(kbId);
            result = inventory.isEmpty() ? "当前知识库中暂无文档" : inventory;
        }
        ragRetrievalService.emitToolEvent(toolContext, "listDocuments", "done", "查询知识库文档清单", result);
        return result;
    }

    /**
     * 按文件名关键词搜索当前知识库中的文档。
     */
    @Tool(description = "按文件名关键词搜索当前知识库中的文档，返回匹配的文档清单。"
            + "当用户想查找/定位某份具体文档（记得部分文件名）时调用；关键词为空时等同列出全部文档。"
            + "问题中明确提到具体文件名时，必须传入完整文件名（不含扩展名，如 软件技术说明书），"
            + "禁止省略成仅个别字词（如 说明书），否则可能误命中名称相近的其他文档。")
    public String searchDocuments(
            @ToolParam(description = "文件名关键词，问题中明确提到文件名时传完整文件名（不含扩展名），"
                    + "例如 软件技术说明书，禁止省略为个别字词；模糊记忆时可传部分字词") String keyword,
            ToolContext toolContext) {
        ragRetrievalService.emitToolEvent(toolContext, "searchDocuments", "running",
                "搜索文档，关键词：" + (keyword == null ? "" : keyword.trim()), null);
        String result;
        Long kbId = ragRetrievalService.requireKbId(toolContext);
        if (kbId == null) {
            result = "当前未指定知识库，无法搜索文档";
        } else if (!ragRetrievalService.canView(toolContext, kbId)) {
            result = "无权访问该知识库";
        } else {
            String kw = keyword == null ? "" : keyword.trim();
            List<KnowledgeDocumentEntity> matched =
                    ragRetrievalService.matchDocuments(ragRetrievalService.activeDocuments(kbId), kw);
            result = matched.isEmpty()
                    ? (kw.isEmpty() ? "当前知识库中暂无文档" : "未找到文件名包含“" + kw + "”的文档")
                    : formatInventory(matched);
        }
        ragRetrievalService.emitToolEvent(toolContext, "searchDocuments", "done",
                "搜索文档，关键词：" + (keyword == null ? "" : keyword.trim()), result);
        return result;
    }

    /**
     * 查询文档的完整标题大纲（章节/小节结构）。
     *
     * <p>标题数据来源：摄取时每个 chunk 文本都按 prefixTemplate 注入了【标题链】前缀，
     * 因此按页面/序号顺序扫描全量 chunk、提取前缀即可还原文档大纲，无需额外存储、无需重新摄取。
     */
    @Tool(description = "查询指定文档的完整标题大纲（章节/小节结构），返回文档包含的标题清单与总数。"
            + "当用户询问某文档包含几部分/几章/几个小节/哪些章节、文档结构、目录大纲等问题时，必须调用本工具。"
            + "问题中明确提到具体文件名时，必须传入完整文件名（不含扩展名，如 软件技术说明书），"
            + "禁止省略成仅个别字词（如 说明书），否则可能误命中名称相近的其他文档；"
            + "省略时仅当知识库文档较少才返回全部文档大纲，文档较多时本工具会拒绝枚举并要求指定文档名。")
    public String documentOutline(
            @ToolParam(description = "文档名关键词，问题中明确提到文件名时传完整文件名（不含扩展名，"
                    + "如 软件技术说明书），禁止省略为个别字词；模糊记忆时可传部分字词；"
                    + "省略则仅当知识库文档较少时返回全部文档大纲，文档较多时必须先通过 listDocuments/searchDocuments 定位具体文档") String keyword,
            ToolContext toolContext) {
        ragRetrievalService.emitToolEvent(toolContext, "documentOutline", "running",
                "查询文档大纲，关键词：" + (keyword == null ? "" : keyword.trim()), null);
        String result;
        Long kbId = ragRetrievalService.requireKbId(toolContext);
        if (kbId == null) {
            result = "当前未指定知识库，无法查询文档大纲";
        } else if (!ragRetrievalService.canView(toolContext, kbId)) {
            result = "无权访问该知识库";
        } else {
            String kw = keyword == null ? "" : keyword.trim();
            List<KnowledgeDocumentEntity> docs = ragRetrievalService.activeDocuments(kbId);
            if (docs.isEmpty()) {
                result = "当前知识库中暂无文档";
            } else if (kw.isEmpty()) {
                // 未指定文档：文档较少时直接给全部大纲；文档较多时拒绝枚举，
                // 引导用户/模型指定具体文档名，避免上万文档的大纲拼接撑爆上下文
                if (docs.size() > config.getTools().getMaxOutlineDocs()) {
                    result = "当前知识库共有 " + docs.size() + " 份活跃文档，文档较多，无法一次性列出全部文档结构。"
                            + "请用户指定具体文档名（如：《软件技术说明书》的结构/包含几部分），或先用文档清单/文档搜索定位目标文档。";
                } else {
                    result = buildOutlines(docs);
                }
            } else {
                docs = ragRetrievalService.matchDocuments(docs, kw);
                if (docs.isEmpty()) {
                    result = "未找到文件名包含“" + kw + "”的文档";
                } else {
                    result = buildOutlines(docs);
                }
            }
        }
        ragRetrievalService.emitToolEvent(toolContext, "documentOutline", "done",
                "查询文档大纲，关键词：" + (keyword == null ? "" : keyword.trim()), result);
        return result;
    }

    /**
     * 知识库正文检索（工具委托壳）：方法体已收敛到 {@link RagRetrievalService#searchKnowledge}，
     * 含权限校验、显式文档解析、Milvus 混合检索 + Rerank 精排、[来源N] 编号累积合并与工具事件发布。
     *
     * <p>检索结果自带 [来源N] 编号，模型须按相同编号在回答中标注引用。
     */
    @Tool(description = "在知识库中检索与问题最相关的内容片段（向量+关键词混合检索+Rerank 精排）。"
            + "当用户的问题需要基于知识库正文内容回答（如某功能/概念/参数的用法、文档里怎么写的、具体条款等）时，"
            + "必须先调用本工具检索相关内容，再严格基于检索结果回答。"
            + "问题中明确提到具体文档名时，原样保留文档名传入，工具会自动限定在该文档内检索。"
            + "注意：文档清单/查找文档用 listDocuments/searchDocuments，文档结构/章节大纲用 documentOutline，不要用本工具。"
            + "检索不到内容时工具会明确提示“未检索到”，请如实告知用户。")
    public String searchKnowledge(
            @ToolParam(description = "需要检索的查询内容（问题含多个独立子问题时拆分为针对性查询词传入，"
                    + "一次检索一个方面；若问题中提到了具体文档名请保留，例如：X 功能的用法、Y 参数的含义）") String question,
            ToolContext toolContext) {
        return ragRetrievalService.searchKnowledge(question, toolContext);
    }

    // ===================== 文档大纲构建（文档级工具内部逻辑） =====================

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
            if (headings.size() >= config.getTools().getOutlineLimit()) break;
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
        if (outline.size() >= config.getTools().getOutlineLimit()) {
            sb.append("（标题较多，仅列出前 ").append(config.getTools().getOutlineLimit()).append(" 个）");
        }
        return sb.toString();
    }

    /** 标题文本是否带编号（数字序号 / 中文数字序数 / 第X章 / （一）），用于大纲过滤无编号封面/页眉标题 */
    private boolean isNumberedHeading(String title) {
        return title != null && NUMBERED_HEADING.matcher(title).find();
    }

    /** 拼接多份文档的大纲文本：文档数 + 总字符数双上限，超限截断并提示，防止工具结果撑爆上下文 */
    private String buildOutlines(List<KnowledgeDocumentEntity> docs) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (KnowledgeDocumentEntity doc : docs) {
            if (shown >= config.getTools().getMaxOutlineDocs()) {
                break;
            }
            String outline = buildOutline(doc);
            // 第一条超限也保留（至少给出内容），后续超限则截断
            if (sb.length() > 0 && sb.length() + outline.length() > config.getTools().getMaxOutlineChars()) {
                break;
            }
            sb.append(outline).append(System.lineSeparator());
            shown++;
        }
        if (shown < docs.size()) {
            sb.append("（文档较多/大纲较长，仅列出前 ").append(shown)
                    .append(" 份，可指定文档名查询具体结构）");
        }
        return sb.toString();
    }

    // ===================== 文档清单文本（文档级工具内部逻辑） =====================

    /**
     * 构建当前知识库的活跃文档清单文本（无权限校验，供 listDocuments 工具构建清单）。
     * 过滤规则与检索一致：仅 SUCCESS/DEPRECATED、未过期、启用（is_active 为空视为启用），
     * 同名多版本只保留最高版本，按最近创建倒序。
     *
     * @return 清单文本；知识库 ID 为空或无活跃文档时返回空字符串
     */
    public String inventoryText(Long kbId) {
        if (kbId == null) {
            return "";
        }
        List<KnowledgeDocumentEntity> docs = ragRetrievalService.activeDocuments(kbId);
        return docs.isEmpty() ? "" : formatInventory(docs);
    }

    /** 格式化文档清单文本：条目数上限截断，防止上万文档清单撑爆工具结果/SSE 帧 */
    private String formatInventory(List<KnowledgeDocumentEntity> docs) {
        StringBuilder sb = new StringBuilder();
        int total = docs.size();
        int shown = Math.min(total, config.getTools().getMaxInventoryDocs());
        sb.append("当前知识库中收录了以下 ").append(total)
                .append(" 份文档：").append(System.lineSeparator());
        int idx = 1;
        for (int i = 0; i < shown; i++) {
            KnowledgeDocumentEntity doc = docs.get(i);
            sb.append(idx++).append(". ").append(doc.getFileName());
            if (doc.getVersion() != null && doc.getVersion() > 1) {
                sb.append("（版本 ").append(doc.getVersion()).append("）");
            }
            if (doc.getChunkCount() != null) {
                sb.append("，共 ").append(doc.getChunkCount()).append(" 个内容片段");
            }
            sb.append(System.lineSeparator());
        }
        if (total > shown) {
            sb.append("…（文档较多，仅显示前 ").append(shown)
                    .append(" 份，可用文件名关键词进一步定位）").append(System.lineSeparator());
        }
        return sb.toString();
    }
}
