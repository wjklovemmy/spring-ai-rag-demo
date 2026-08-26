package com.example.springairagdemo.service;

import com.example.springairagdemo.config.RagConfigProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 混合检索服务：Milvus 原生 Hybrid Search（Dense 向量 + BM25 全文检索 + RRF 融合）
 * <p>
 * 编排 {@link VectorStoreService#hybridSearch}，负责：
 * <ul>
 *   <li>确定每路（dense / bm25）召回候选数</li>
 *   <li>按 {@code rag.hybrid.min-score} 过滤融合结果中的低分噪声</li>
 *   <li>检索异常时按 {@code rag.hybrid.fallback-on-error} 降级为纯向量检索</li>
 * </ul>
 */
@Service
@Slf4j
public class HybridSearchService {

    private final VectorStoreService vectorStoreService;
    private final RagConfigProperties ragConfig;

    public HybridSearchService(VectorStoreService vectorStoreService, RagConfigProperties ragConfig) {
        this.vectorStoreService = vectorStoreService;
        this.ragConfig = ragConfig;
    }

    /**
     * 混合检索：dense + BM25 双路召回，Milvus 端 RRF 融合
     *
     * @param knowledgeBaseId 知识库 ID
     * @param query           查询文本
     * @param topK            融合后返回条数（对应 rerank.candidate-top-k）
     * @param threshold       降级为纯向量检索时的余弦相似度阈值
     * @return 检索结果列表（score 为 RRF 融合分）
     */
    public List<VectorStoreService.SearchResult> search(Long knowledgeBaseId, String query, int topK,
                                                        double threshold) {
        return search(knowledgeBaseId, query, topK, threshold, null);
    }

    /**
     * 混合检索：dense + BM25 双路召回，Milvus 端 RRF 融合，可按文档 ID 限定召回范围
     *
     * @param knowledgeBaseId 知识库 ID
     * @param query           查询文本
     * @param topK            融合后返回条数（对应 rerank.candidate-top-k）
     * @param threshold       降级为纯向量检索时的余弦相似度阈值
     * @param documentIds     限定召回范围的文档 ID 列表（null/空 = 全库检索）
     * @return 检索结果列表（score 为 RRF 融合分）
     */
    public List<VectorStoreService.SearchResult> search(Long knowledgeBaseId, String query, int topK,
                                                        double threshold, List<Long> documentIds) {
        RagConfigProperties.Hybrid hybrid = ragConfig.getHybrid();
        // 限定文档范围时：当前 Milvus SDK 的 HybridSearchReq 不支持 filter 表达式，
        // 改用纯向量检索（同样按文档过滤）——已点名到具体文档，dense 语义检索足够精准
        if (documentIds != null && !documentIds.isEmpty()) {
            log.info("问题明确指定文档，限定召回范围 {}，使用纯向量检索", documentIds);
            return vectorStoreService.search(knowledgeBaseId, query, topK, threshold, documentIds);
        }
        // 每路召回数至少覆盖融合 topK 的 2 倍，保证两路有足够候选参与 RRF
        int routeTopK = Math.max(hybrid.getRouteTopK(), topK * 2);
        try {
            List<VectorStoreService.SearchResult> results = vectorStoreService.hybridSearch(
                    knowledgeBaseId, query, routeTopK, routeTopK, hybrid.getRrfK(), topK);

            double minScore = hybrid.getMinScore();
            if (minScore > 0) {
                int before = results.size();
                results = results.stream()
                        .filter(r -> r.getScore() != null && r.getScore() >= minScore)
                        .toList();
                log.debug("Hybrid 检索按 min-score={} 过滤：{} -> {}", minScore, before, results.size());
            }
            return results;
        } catch (Exception e) {
            if (hybrid.isFallbackOnError()) {
                // 优先降级 BM25 全文检索：不依赖 embedding，embedding 服务异常时仍可关键词兜底
                try {
                    log.warn("Hybrid 检索失败，降级为 BM25 全文检索: {}", e.getMessage());
                    return vectorStoreService.bm25Search(knowledgeBaseId, query, topK, documentIds);
                } catch (Exception bm25Ex) {
                    log.warn("BM25 全文检索失败，降级为纯向量检索: {}", bm25Ex.getMessage());
                    return vectorStoreService.search(knowledgeBaseId, query, topK, threshold, documentIds);
                }
            }
            throw e;
        }
    }
}
