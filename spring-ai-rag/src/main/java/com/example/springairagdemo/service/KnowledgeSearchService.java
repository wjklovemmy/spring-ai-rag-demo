package com.example.springairagdemo.service;

import com.example.springairagdemo.config.RagConfigProperties;
import com.example.springairagdemo.entity.DocumentStatus;
import com.example.springairagdemo.entity.KnowledgeChunkEntity;
import com.example.springairagdemo.entity.KnowledgeDocumentEntity;
import com.example.springairagdemo.service.KnowledgeDocumentService.SourceInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 知识库检索服务：向量/混合检索召回 → 状态与版本过滤 → Rerank 精排 → 组装带 [来源N] 的上下文。
 * <p>
 * 由 Agent 工具（searchKnowledge）调用（Agentic RAG：模型自主决定何时检索），
 * 是知识库正文检索的唯一入口，避免检索逻辑多处维护。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeSearchService {

    /** 检索结果：上下文文本（带[来源N]编号）、来源列表、完整片段内容（供引用对齐） */
    public record SearchResult(String context, List<SourceInfo> sources, List<String> fullContents) {

        /** 空结果 */
        public static SearchResult empty() {
            return new SearchResult("", List.of(), List.of());
        }

        /** [来源N] 编号正则（供编号平移） */
        private static final Pattern REF_PATTERN = Pattern.compile("\\[来源(\\d+)\\]");

        /**
         * 编号平移：将本结果中的 [来源N] 统一改为 [来源(N+offset)]，
         * 来源列表的 refIndex 同步平移。offset &lt;= 0 时原样返回。
         * <p>用于模型多轮调用 searchKnowledge 时保证编号全局唯一：
         * 工具返回给模型的片段、累积的最终来源列表使用同一套编号，
         * 避免各轮编号都从 1 开始导致回答中 [来源N] 与来源列表错位。
         */
        public SearchResult shift(int offset) {
            if (offset <= 0) {
                return this;
            }
            String shiftedContext = REF_PATTERN.matcher(context).replaceAll(m ->
                    "[来源" + (offset + Integer.parseInt(m.group(1))) + "]");
            List<SourceInfo> shiftedSources = sources.stream()
                    .map(s -> new SourceInfo(s.documentId(), s.documentName(), s.pageNo(), s.snippet(),
                            s.refIndex() + offset))
                    .toList();
            return new SearchResult(shiftedContext, shiftedSources, fullContents);
        }

        /**
         * 累积合并：将本次检索结果（编号从 1 开始）追加到已有累积结果之后，
         * 本次片段的编号统一顺延（offset = 已有来源数），context 拼接、来源/全文列表合并。
         * <p>用于模型多轮调用 searchKnowledge：后一次调用不再覆盖前一次，
         * 回答中的任何 [来源N] 都能在最终来源列表中找到唯一对应。
         */
        public SearchResult append(SearchResult next) {
            if (next == null || next.context().isEmpty()) {
                return this;
            }
            if (context.isEmpty()) {
                return next; // 首次调用：编号保持 1..N
            }
            SearchResult shifted = next.shift(sources.size());
            List<SourceInfo> mergedSources = new ArrayList<>(sources);
            mergedSources.addAll(shifted.sources);
            List<String> mergedContents = new ArrayList<>(fullContents);
            mergedContents.addAll(shifted.fullContents);
            return new SearchResult(context + "\n\n" + shifted.context, mergedSources, mergedContents);
        }
    }

    private final RagConfigProperties ragConfig;
    private final HybridSearchService hybridSearchService;
    private final VectorStoreService vectorStoreService;
    private final RerankService rerankService;
    private final KnowledgeChunkEntityService knowledgeChunkEntityService;
    private final KnowledgeDocumentEntityService knowledgeDocumentEntityService;

    /**
     * 检索与问题最相关的内容片段（Hybrid Search：Dense + BM25 + RRF 融合，可降级为纯向量检索），
     * 过滤已过期/处理中/失败文档，同名文档多版本只保留最高版本，再经 Rerank 精排取 topN，
     * 组装为带 [来源N] 编号的上下文文本。
     *
     * @param question       检索问题
     * @param knowledgeBaseId 知识库 ID
     * @param restrictDocIds 显式文档限定（null 表示全库检索）
     * @return context 为空表示知识库中没有可用内容
     */
    public SearchResult search(String question, Long knowledgeBaseId, List<Long> restrictDocIds) {
        RagConfigProperties.Rerank rerankConfig = ragConfig.getRerank();
        List<VectorStoreService.SearchResult> searchResults;
        if (ragConfig.getHybrid().isEnabled()) {
            searchResults = hybridSearchService.search(knowledgeBaseId, question,
                    rerankConfig.getCandidateTopK(), rerankConfig.getThreshold(), restrictDocIds);
        } else {
            searchResults = vectorStoreService.search(knowledgeBaseId, question,
                    rerankConfig.getCandidateTopK(), rerankConfig.getThreshold(), restrictDocIds);
        }

        if (searchResults.isEmpty()) {
            return SearchResult.empty();
        }

        // 2. 从 MySQL 获取 chunk 内容
        List<Long> chunkIds = searchResults.stream()
                .map(VectorStoreService.SearchResult::getChunkId)
                .toList();
        List<KnowledgeChunkEntity> chunks = knowledgeChunkEntityService.listByIds(chunkIds);

        // 按检索顺序组装上下文
        Map<Long, KnowledgeChunkEntity> chunkMap = chunks.stream()
                .collect(Collectors.toMap(KnowledgeChunkEntity::getId, c -> c, (a, b) -> a));

        // Parent-Child 反查：命中子块（parent_id 非空）时，一次批量反查父块行，
        // 用父块全文作为 LLM 上下文/片段——小块召回精准、父块上下文完整
        Map<Long, KnowledgeChunkEntity> parentMap = resolveParents(chunkMap);

        // 获取文档信息，过滤已过期版本，且同名文档多版本只保留最高版本（新版优先，避免混入旧内容）
        Date now = new Date();
        List<Long> docIds = searchResults.stream()
                .map(VectorStoreService.SearchResult::getDocumentId)
                .distinct()
                .toList();
        Map<Long, KnowledgeDocumentEntity> docMap = knowledgeDocumentEntityService.listByIds(docIds).stream()
                .collect(Collectors.toMap(KnowledgeDocumentEntity::getId, d -> d, (a, b) -> a));

        // 过滤：只允许 SUCCESS(生效) 与 DEPRECATED(TTL 兜底) 版本参与问答，
        // 排除处理中(0/1/2)、失败(4)、已过期(6) 的残留向量
        Map<Long, String> docNameMap = new LinkedHashMap<>();
        List<VectorStoreService.SearchResult> validResults = new ArrayList<>();
        // 第一遍：按状态过滤，收集活跃文档，并统计同名文档的最高版本
        Map<Long, KnowledgeDocumentEntity> activeDocs = new HashMap<>();
        Map<String, Integer> maxVersionByFileName = new HashMap<>();
        for (VectorStoreService.SearchResult r : searchResults) {
            KnowledgeDocumentEntity doc = docMap.get(r.getDocumentId());
            if (doc == null) continue;
            Integer st = doc.getStatus();
            // 仅成功/已废弃版本可参与（处理中、失败、已过期一律排除）
            if (st == null
                    || (st != DocumentStatus.SUCCESS.getCode() && st != DocumentStatus.DEPRECATED.getCode())) continue;
            // 兜底：expire_time 已到但尚未懒标记的按过期处理
            if (doc.getExpireTime() != null && doc.getExpireTime().before(now)) continue;
            activeDocs.putIfAbsent(doc.getId(), doc);
            String fileName = doc.getFileName();
            int ver = doc.getVersion() != null ? doc.getVersion() : 0;
            maxVersionByFileName.merge(fileName, ver, Math::max);
        }
        // 第二遍：同名文档只保留版本最高的检索结果（旧版本不再召回；最新版本被删除后旧版本自动接管）
        for (VectorStoreService.SearchResult r : searchResults) {
            KnowledgeDocumentEntity doc = activeDocs.get(r.getDocumentId());
            if (doc == null) continue;
            int ver = doc.getVersion() != null ? doc.getVersion() : 0;
            if (ver < maxVersionByFileName.getOrDefault(doc.getFileName(), ver)) continue;
            validResults.add(r);
            docNameMap.putIfAbsent(doc.getId(), doc.getFileName());
        }
        searchResults = validResults;

        // 2.5 召回重排序（Rerank）：对候选片段按 "问题相关性" 精排，取 topN
        if (rerankService.isEnabled() && searchResults.size() > 1) {
            List<String> texts = searchResults.stream()
                    .map(r -> {
                        KnowledgeChunkEntity chunk = chunkMap.get(r.getChunkId());
                        return chunk == null ? "" : resolveContent(chunk, parentMap);
                    })
                    .toList();
            try {
                List<RerankService.RerankItem> ranked =
                        rerankService.rerank(question, texts, rerankConfig.getTopN());
                if (ranked != null && !ranked.isEmpty()) {
                    // 按重排分数降序重建检索结果（只保留 topN）
                    List<VectorStoreService.SearchResult> currentResults = searchResults;
                    List<VectorStoreService.SearchResult> reranked = ranked.stream()
                            .map(item -> currentResults.get(item.index()))
                            .toList();
                    searchResults = reranked;
                    log.debug("Rerank 精排完成，保留 {} 条候选", searchResults.size());
                } else {
                    log.debug("Rerank 未返回结果，降级为向量排序结果");
                }
            } catch (Exception e) {
                log.warn("Rerank 精排失败，降级为向量排序结果: {}", e.getMessage());
            }
        }

        if (searchResults.isEmpty()) {
            return SearchResult.empty();
        }

        // 构建来源信息
        List<SourceInfo> sources = new ArrayList<>();
        List<String> fullContents = new ArrayList<>();
        StringBuilder contextBuilder = new StringBuilder();
        int refIndex = 1;

        for (VectorStoreService.SearchResult r : searchResults) {
            KnowledgeChunkEntity chunk = chunkMap.get(r.getChunkId());
            if (chunk == null) continue;

            // 子块命中 → 用父块全文作为上下文与片段（parentMap 已批量反查，无 N+1）
            String content = resolveContent(chunk, parentMap);
            String docName = docNameMap.getOrDefault(r.getDocumentId(), "未知文档");
            String snippet = content;
            if (snippet.length() > 120) {
                snippet = snippet.substring(0, 120) + "...";
            }

            int ref = refIndex++;
            sources.add(new SourceInfo(r.getDocumentId(), docName, r.getPageNo(), snippet, ref));
            fullContents.add(content);

            // 在上下文中标记来源
            contextBuilder.append(String.format("[来源%d] 文档：%s，第%d页%n%s%n%n",
                    ref, docName, r.getPageNo() != null ? r.getPageNo() : 1, content));
        }

        return new SearchResult(contextBuilder.toString(), sources, fullContents);
    }

    /**
     * 反查父块（Parent-Child 检索）：收集命中子块的 parent_id，一次性批量查询父块行。
     * 父块仅存 MySQL 不向量化，检索命中子块后用父块全文保证上下文完整。
     */
    private Map<Long, KnowledgeChunkEntity> resolveParents(Map<Long, KnowledgeChunkEntity> chunkMap) {
        Set<Long> parentIds = chunkMap.values().stream()
                .map(KnowledgeChunkEntity::getParentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (parentIds.isEmpty()) {
            return Map.of();
        }
        return knowledgeChunkEntityService.listByIds(parentIds).stream()
                .collect(Collectors.toMap(KnowledgeChunkEntity::getId, p -> p, (a, b) -> a));
    }

    /**
     * 解析用于 LLM 上下文的片段内容：
     * 子块（parent_id 非空）→ 反查父块全文（上下文完整）；父块/单级块 → 自身内容。
     */
    private String resolveContent(KnowledgeChunkEntity chunk, Map<Long, KnowledgeChunkEntity> parentMap) {
        if (chunk.getParentId() != null) {
            KnowledgeChunkEntity parent = parentMap.get(chunk.getParentId());
            if (parent != null && parent.getContent() != null && !parent.getContent().isBlank()) {
                return parent.getContent();
            }
        }
        String content = chunk.getContent();
        return content != null ? content : "";
    }
}
