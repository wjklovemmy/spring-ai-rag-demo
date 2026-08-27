package com.example.springairagdemo.service;

import com.example.springairagdemo.config.RagConfigProperties;
import com.example.springairagdemo.entity.DocumentStatus;
import com.example.springairagdemo.entity.KnowledgeDocumentEntity;
import com.example.springairagdemo.security.KbRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent 检索链路组件：把「显式文档解析 + Milvus 检索 + Rerank 精排 + 编号累积合并 + 工具事件」收拢为独立组件。
 *
 * <p>背景：Agentic RAG 下模型通过工具调用检索知识库正文。原实现中检索链路的内部环节
 * （{@code resolveExplicitDocuments}、工具事件发布、编号累积合并等）散落在 {@code KbQueryTools}
 * 工具类内部，难以复用与单测。本组件将这些环节集中为独立的 Service：
 * <ul>
 *   <li>{@link #resolveExplicitDocuments}：问题中解析显式点名的文档（文件名/书名号），限定召回范围；</li>
 *   <li>{@link #searchKnowledge}：工具方法体（权限校验 → 检索 → Rerank → [来源N] 编号累积合并 → 事件回调），
 *       由 {@code KbQueryTools.searchKnowledge} 薄壳委托；</li>
 *   <li>工具事件基础设施（{@link ToolEvent} / {@link #emitToolEvent}）与 ToolContext 常量：
 *       与 {@code KbQueryTools}、{@code CalculatorTool} 等所有 Agent 工具共用。</li>
 * </ul>
 * 模型可见的工具面不变（仍是 searchKnowledge / listDocuments / searchDocuments / documentOutline），
 * 仅代码结构收敛，便于复用、测试与后续扩展。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagRetrievalService {

    // ===================== ToolContext 常量（KbQueryTools 迁入，Service 层注入） =====================

    /** ToolContext 中当前知识库 ID 的 key（由 Service 层注入） */
    public static final String KB_ID_KEY = "knowledgeBaseId";
    /** ToolContext 中当前用户 ID 的 key（由 Service 层注入） */
    public static final String USER_ID_KEY = "userId";
    /** ToolContext 中工具调用事件 Sink 的 key（由 Service 层注入，SSE 展示工具调用过程用；同步问答不注入） */
    public static final String TOOL_EVENT_SINK_KEY = "toolEventSink";
    /** ToolContext 中 searchKnowledge 检索结果收集器的 key（由 Service 层注入：
     *  AtomicReference<SearchResult>，工具回调线程写入，供 Service 汇总最终引用来源/落库） */
    public static final String SEARCH_RESULT_HOLDER_KEY = "searchResultHolder";
    /** ToolContext 中会话记忆 ID 的 key（由 Service 层注入：userId:sessionId，供工具感知多轮上下文） */
    public static final String CONVERSATION_ID_KEY = "conversationId";
    /** ToolContext 中 Agent 任务 ID 的 key（由 Service 层注入：流式链路传入；同步链路任务在回答后创建，注入为空） */
    public static final String TASK_ID_KEY = "taskId";

    /** 工具调用事件（SSE 展示：running 开始 / done 完成 / error 失败），args/result 为摘要文本 */
    public record ToolEvent(String name, String status, String args, String result) {
        /** 事件状态：开始 */
        public static final String STATUS_RUNNING = "running";
        /** 事件状态：完成 */
        public static final String STATUS_DONE = "done";
        /** 事件状态：失败 */
        public static final String STATUS_ERROR = "error";
    }

    /** 工具事件参数/结果摘要最大长度（SSE 展示 + agent_task_step 落库共用），避免帧过大 */
    private static final int TOOL_EVENT_MAX_LEN = 500;

    private final KnowledgeSearchService knowledgeSearchService;
    private final KbAuthorizationService kbAuthorizationService;
    private final KnowledgeDocumentEntityService knowledgeDocumentEntityService;
    private final RagConfigProperties config;

    // ===================== 检索入口（searchKnowledge 工具方法体） =====================

    /**
     * 知识库正文检索（工具方法体）：在知识库中检索与问题最相关的内容片段
     * （Hybrid 检索 + Rerank 精排），问题明确点名文档时自动限定范围。
     *
     * <p>回调：检索到的候选来源通过 ToolContext 注入的
     * {@link #SEARCH_RESULT_HOLDER_KEY}（AtomicReference&lt;SearchResult&gt;）写回 Service，
     * 供其汇总最终引用来源（SSE sources 事件 / agent_task 快照）与引用对齐校验。
     *
     * @param question    检索内容（由模型从用户问题拆分传入）
     * @param toolContext 请求级上下文（知识库 ID / 用户 ID / 事件 Sink / 结果收集器）
     * @return 带 [来源N] 编号的检索上下文文本；检索不到时返回提示语
     */
    public String searchKnowledge(String question, ToolContext toolContext) {
        emitToolEvent(toolContext, "searchKnowledge", "running",
                "知识库检索：" + (question == null ? "" : question.trim()), null);
        String result;
        Long kbId = requireKbId(toolContext);
        if (kbId == null) {
            result = "当前未指定知识库，无法检索";
        } else if (!canView(toolContext, kbId)) {
            result = "无权访问该知识库";
        } else {
            String q = question == null ? "" : question.trim();
            if (q.isEmpty()) {
                result = "检索问题为空，请补充需要检索的具体内容";
            } else {
                // 问题明确点名文档时限定检索范围（防止名称相近文档混入引用来源）
                List<KnowledgeDocumentEntity> explicitDocs = resolveExplicitDocuments(kbId, q);
                List<Long> restrictDocIds = explicitDocs.isEmpty() ? null
                        : explicitDocs.stream().map(KnowledgeDocumentEntity::getId).distinct().toList();
                KnowledgeSearchService.SearchResult sr = knowledgeSearchService.search(q, kbId, restrictDocIds);
                // 回调检索结果给 Service（无论是否命中均标记"已执行检索"）：
                // 模型可能多轮调用本工具，各轮编号都从 1 开始，若后一次覆盖前一次，
                // 回答中引用早期轮次的 [来源N] 将与最终来源列表错位。
                // 因此累积合并所有轮次结果（编号全局递增），工具返回给模型的片段也使用
                // 平移后的全局编号，保证回答中的 [来源N] 与最终 sources 的 refIndex 一一对应。
                KnowledgeSearchService.SearchResult thisRound = sr;
                Object holderObj = toolContext.getContext().get(SEARCH_RESULT_HOLDER_KEY);
                if (holderObj instanceof AtomicReference<?> holder) {
                    @SuppressWarnings("unchecked")
                    AtomicReference<KnowledgeSearchService.SearchResult> typed =
                            (AtomicReference<KnowledgeSearchService.SearchResult>) holder;
                    KnowledgeSearchService.SearchResult prev = typed.get();
                    int offset = (prev == null || prev.context().isEmpty()) ? 0 : prev.sources().size();
                    typed.set(prev == null ? sr : prev.append(sr));
                    // 返回给模型的仅本次新增片段，但编号平移到全局编号体系
                    thisRound = sr.shift(offset);
                }
                result = thisRound.context().isEmpty()
                        ? "知识库中未检索到与“" + q + "”相关的信息"
                        : thisRound.context();
            }
        }
        emitToolEvent(toolContext, "searchKnowledge", "done",
                "知识库检索：" + (question == null ? "" : question.trim()), result);
        return result;
    }

    // ===================== 显式文档解析（检索范围限定） =====================

    /**
     * 从用户问题中解析"明确指定的文档"（高置信限定检索范围用）。
     * <p>
     * 识别两类明确点名方式：①带扩展名的完整文件名（如 软件技术说明书.pdf）；
     * ②书名号高亮（如 《软件技术说明书》）。提取候选名后按 {@link #matchDocuments}
     * 四级匹配（多词元AND &gt; 精确 &gt; 前缀 &gt; 包含）定位目标文档。
     * <p>
     * 返回非空列表时，调用方（问答检索链路）应将召回范围限定在这些文档内，
     * 从源头防止名称相近的文档（如 纯图片产品说明书_扫描件.pdf）的 chunk 混入引用来源。
     *
     * @param kbId     知识库 ID
     * @param question 用户问题
     * @return 高置信命中的文档列表；问题未明确点名或未命中时返回空列表（不限定检索）
     */
    public List<KnowledgeDocumentEntity> resolveExplicitDocuments(Long kbId, String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        List<KnowledgeDocumentEntity> docs = activeDocuments(kbId);
        if (docs.isEmpty()) {
            return List.of();
        }
        Set<String> candidates = new LinkedHashSet<>();
        Matcher fm = FILE_NAME_IN_QUESTION.matcher(question);
        while (fm.find()) {
            candidates.add(normalizeName(fm.group().trim()));
        }
        Matcher bm = BOOK_TITLE_IN_QUESTION.matcher(question);
        while (bm.find()) {
            String title = bm.group(1).trim();
            if (!title.isEmpty()) {
                candidates.add(normalizeName(title));
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        Set<Long> seen = new HashSet<>();
        List<KnowledgeDocumentEntity> matched = new ArrayList<>();
        for (String candidate : candidates) {
            for (KnowledgeDocumentEntity doc : matchDocuments(docs, candidate)) {
                if (seen.add(doc.getId())) {
                    matched.add(doc);
                }
            }
        }
        return matched;
    }

    /** 常见文件扩展名（文件名匹配时忽略） */
    private static final Pattern FILE_EXT = Pattern.compile("(?i)\\.(pdf|docx?|xlsx?|pptx?|txt|md)$");

    /** 问题中提取"带扩展名的完整文件名"（如 软件技术说明书.pdf），检索范围限定用 */
    private static final Pattern FILE_NAME_IN_QUESTION = Pattern.compile(
            "[\\u4e00-\\u9fa5A-Za-z0-9_\\-（）()·、]{2,40}\\.(?:pdf|docx?|xlsx?|pptx?|txt|md)",
            Pattern.CASE_INSENSITIVE);

    /** 书名号《》内的高亮文档名（如 《软件技术说明书》），检索范围限定用 */
    private static final Pattern BOOK_TITLE_IN_QUESTION = Pattern.compile("《([^》]{1,50})》");

    /**
     * 按文件名关键词匹配文档，匹配优先级：多词元全命中(AND) &gt; 精确(忽略扩展名) &gt; 前缀 &gt; 包含。
     * <p>
     * 解决"子串 contains 误命中"问题：用户明确说出完整文件名时（LLM 传如 软件技术说明书 或
     * 软件技术说明书.pdf），精确/前缀匹配只命中目标文档，不会把名称相近的文档（如
     * 纯图片产品说明书_扫描件.pdf）一起带出来。
     *
     * @param docs    候选文档列表
     * @param keyword 可为空（返回全部）；可含 .pdf 等扩展名（自动忽略）；多词元以空白分隔（AND 匹配）
     */
    public List<KnowledgeDocumentEntity> matchDocuments(List<KnowledgeDocumentEntity> docs, String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            return docs;
        }
        String core = FILE_EXT.matcher(kw).replaceAll("");
        String[] tokens = core.split("\\s+");
        // 多词元 AND：每个词元都必须出现在文件名中（如 "软件 说明书" 只命中软件技术说明书）
        if (tokens.length > 1) {
            List<KnowledgeDocumentEntity> andMatched = docs.stream()
                    .filter(d -> {
                        String name = normalizeName(d.getFileName());
                        for (String t : tokens) {
                            if (!t.isEmpty() && !name.contains(t.toLowerCase())) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .toList();
            if (!andMatched.isEmpty()) {
                return andMatched;
            }
        }
        String lowerKw = core.toLowerCase();
        // 精确：文件名（去扩展名）与关键词完全一致
        List<KnowledgeDocumentEntity> exact = docs.stream()
                .filter(d -> normalizeName(d.getFileName()).equals(lowerKw))
                .toList();
        if (!exact.isEmpty()) {
            return exact;
        }
        // 前缀：文件名以关键词开头（覆盖"软件技术说明书"命中"软件技术说明书.pdf"，不命中"纯图片产品说明书_扫描件.pdf"）
        List<KnowledgeDocumentEntity> prefix = docs.stream()
                .filter(d -> normalizeName(d.getFileName()).startsWith(lowerKw))
                .toList();
        if (!prefix.isEmpty()) {
            return prefix;
        }
        // 兜底：子串包含（模糊记忆场景）
        return docs.stream()
                .filter(d -> normalizeName(d.getFileName()).contains(lowerKw))
                .toList();
    }

    /** 文件名去扩展名转小写（匹配归一化） */
    private String normalizeName(String fileName) {
        if (fileName == null) {
            return "";
        }
        return FILE_EXT.matcher(fileName).replaceAll("").toLowerCase();
    }

    // ===================== 活跃文档查询（与检索一致的过滤规则） =====================

    /** 查询活跃文档（同名多版本只保留最高版本，按最近创建倒序） */
    public List<KnowledgeDocumentEntity> activeDocuments(Long kbId) {
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

    // ===================== 权限校验（显式 userId，不依赖请求线程） =====================

    /** 校验当前用户对知识库至少有 VIEWER 权限（显式 userId，工具回调线程安全） */
    public boolean canView(ToolContext toolContext, Long kbId) {
        if (toolContext == null || toolContext.getContext() == null) {
            return false;
        }
        Object userIdObj = toolContext.getContext().get(USER_ID_KEY);
        if (!(userIdObj instanceof Number n)) {
            return false;
        }
        return kbAuthorizationService.canAccess(n.longValue(), kbId, KbRole.VIEWER);
    }

    /** 从 ToolContext 读取当前知识库 ID（缺失时返回 null，由调用方给出提示语） */
    public Long requireKbId(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object v = toolContext.getContext().get(KB_ID_KEY);
        return v instanceof Number n ? n.longValue() : null;
    }

    // ===================== 工具事件（SSE 展示 / agent_task_step 落库共用） =====================

    /**
     * 发布工具调用事件（SSE 展示用）。工具回调线程执行，Sink 由 Service 层通过 ToolContext 注入；
     * 同步问答未注入 Sink 时静默跳过。
     */
    public void emitToolEvent(ToolContext toolContext, String name, String status, String args, String result) {
        if (toolContext == null) return;
        Object sinkObj = toolContext.getContext().get(TOOL_EVENT_SINK_KEY);
        if (!(sinkObj instanceof Sinks.Many<?> sink)) return;
        @SuppressWarnings("unchecked")
        Sinks.Many<ToolEvent> typed = (Sinks.Many<ToolEvent>) sink;
        typed.tryEmitNext(new ToolEvent(name, status, truncate(args), truncate(result)));
    }

    /** 工具事件摘要截断，避免超大参数/结果撑爆 SSE 帧 */
    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= TOOL_EVENT_MAX_LEN ? s : s.substring(0, TOOL_EVENT_MAX_LEN) + "…";
    }
}
