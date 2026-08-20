package com.example.springairagdemo.service;

import com.example.springairagdemo.config.RagConfigProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 阿里云百炼 gte-rerank 重排序实现
 *
 * <p>调用 DashScope Rerank API：对 "query + documents" 逐对计算相关性分数，
 * 返回按分数降序排列的结果（含原始下标）。API Key 复用 DashScope Embedding 的 key。
 */
@Slf4j
@Service
public class DashScopeRerankService implements RerankService {

    private static final String BASE_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    private final RagConfigProperties config;
    private final RestClient restClient;

    public DashScopeRerankService(RagConfigProperties config,
                                  @Value("${spring.ai.dashscope.api-key}") String apiKey) {
        this.config = config;
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public boolean isEnabled() {
        return config.getRerank().isEnabled();
    }

    @Override
    public List<RerankItem> rerank(String query, List<String> documents, int topN) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        DashScopeRerankRequest body = new DashScopeRerankRequest();
        body.setModel(config.getRerank().getModel());
        body.setInput(new Input(query, documents));
        body.setParameters(new Parameters(false, topN > 0 ? topN : documents.size()));

        try {
            DashScopeRerankResponse response = restClient.post()
                    .body(body)
                    .retrieve()
                    .body(DashScopeRerankResponse.class);

            if (response == null || response.getOutput() == null
                    || response.getOutput().getResults() == null) {
                log.warn("DashScope rerank 返回空响应");
                return null;
            }

            List<RerankItem> items = new ArrayList<>();
            for (ResultItem item : response.getOutput().getResults()) {
                items.add(new RerankItem(item.getIndex(), item.getRelevanceScore()));
            }
            items.sort(Comparator.comparingDouble(RerankItem::score).reversed());
            log.debug("DashScope rerank 完成，共 {} 条结果", items.size());
            return items;
        } catch (Exception e) {
            if (config.getRerank().isFallbackOnError()) {
                log.warn("DashScope rerank 调用失败，降级为向量排序: {}", e.getMessage());
                return null;
            }
            log.error("DashScope rerank 调用失败: {}", e.getMessage());
            throw new RuntimeException("DashScope rerank 调用失败", e);
        }
    }

    // ---------- DashScope Rerank API 请求/响应 DTO ----------

    @Data
    static class DashScopeRerankRequest {
        private String model;
        private Input input;
        private Parameters parameters;
    }

    @Data
    static class Input {
        private String query;
        private List<String> documents;

        Input() {}
        Input(String query, List<String> documents) {
            this.query = query;
            this.documents = documents;
        }
    }

    @Data
    static class Parameters {
        @JsonProperty("return_documents")
        private boolean returnDocuments;
        @JsonProperty("top_n")
        private int topN;

        Parameters() {}
        Parameters(boolean returnDocuments, int topN) {
            this.returnDocuments = returnDocuments;
            this.topN = topN;
        }
    }

    @Data
    static class DashScopeRerankResponse {
        private Output output;
        private Usage usage;
    }

    @Data
    static class Output {
        private List<ResultItem> results;
    }

    @Data
    static class ResultItem {
        private int index;
        @JsonProperty("relevance_score")
        private double relevanceScore;
    }

    @Data
    static class Usage {
        @JsonProperty("total_tokens")
        private int totalTokens;
    }
}
