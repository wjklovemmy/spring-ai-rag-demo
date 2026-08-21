package com.example.springairagdemo.embedding;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 自定义 DashScope text-embedding-v3 的 EmbeddingModel 实现，
 * 直接调用阿里云 DashScope REST API，适配 Spring AI 2.0。
 */
@Slf4j
public class DashScopeEmbeddingModel extends AbstractEmbeddingModel {

    private static final String BASE_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";

    /** DashScope text-embedding-v3 单次请求文本条数上限（超过会返回 400 InvalidParameter） */
    static final int MAX_BATCH_SIZE = 10;

    /** 连接超时 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** 读取超时 */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);
    /** 网络异常（超时/连接失败）或服务端临时错误（5xx）时的最大重试次数 */
    private static final int MAX_RETRIES = 2;

    private final RestClient restClient;
    private final String model;

    public DashScopeEmbeddingModel(String apiKey, String model) {
        this.model = model;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        if (texts.size() <= MAX_BATCH_SIZE) {
            return new EmbeddingResponse(callBatch(texts, 0));
        }

        // DashScope 单次请求上限 10 条，超限自动分片并拼接（保证返回顺序与输入一致）
        log.debug("DashScope embedding 文本数量 {} 超过单次上限 {}，自动分片", texts.size(), MAX_BATCH_SIZE);
        List<Embedding> all = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + MAX_BATCH_SIZE, texts.size()));
            all.addAll(callBatch(batch, i));
        }
        return new EmbeddingResponse(all);
    }

    private List<Embedding> callBatch(List<String> texts, int baseIndex) {
        log.debug("DashScope embedding 请求，文本数量: {}", texts.size());

        DashScopeRequest body = new DashScopeRequest();
        body.setModel(model);
        body.setInput(new DashScopeRequest.Input(texts));
        body.setParameters(new DashScopeRequest.Parameters("document"));

        Exception lastError = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                log.warn("DashScope embedding 网络异常，第 {}/{} 次重试", attempt, MAX_RETRIES);
                try {
                    Thread.sleep(500L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                DashScopeResponse response = restClient.post()
                        .body(body)
                        .retrieve()
                        .body(DashScopeResponse.class);

                if (response == null || response.getOutput() == null) {
                    log.error("DashScope embedding 返回空响应");
                    return List.of();
                }

                List<Embedding> embeddings = new ArrayList<>(texts.size());
                for (DashScopeResponse.EmbeddingItem item : response.getOutput().getEmbeddings()) {
                    float[] vector = new float[item.getEmbedding().size()];
                    for (int i = 0; i < item.getEmbedding().size(); i++) {
                        vector[i] = item.getEmbedding().get(i).floatValue();
                    }
                    // textIndex 修正为全局下标，避免分片后索引错乱
                    embeddings.add(new Embedding(vector, baseIndex + item.getTextIndex()));
                }

                log.debug("DashScope embedding 返回 {} 个向量", embeddings.size());
                return embeddings;
            } catch (RestClientResponseException e) {
                // 4xx 业务错误（如 400 批量超限、401 鉴权失败）不重试；
                // 5xx 服务端临时错误（500/502/503/504）可重试
                if (e.getStatusCode().value() >= 500 && attempt < MAX_RETRIES) {
                    lastError = e;
                } else {
                    throw e;
                }
            } catch (Exception e) {
                // 网络异常（超时/连接失败）可重试
                lastError = e;
            }
        }
        throw new RuntimeException("DashScope embedding 调用失败: " + lastError.getMessage(), lastError);
    }

    @Override
    public float[] embed(Document document) {
        return this.embed(document.getText());
    }

    @Override
    public float[] embed(String text) {
        EmbeddingResponse response = call(new EmbeddingRequest(List.of(text), null));
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return new float[0];
        }
        return response.getResults().get(0).getOutput();
    }

    // ---------- DashScope API 请求/响应 DTO ----------

    @Data
    static class DashScopeRequest {
        private String model;
        private Input input;
        private Parameters parameters;

        @Data
        static class Input {
            @JsonProperty("texts")
            private List<String> texts;
            Input() {}
            Input(List<String> texts) { this.texts = texts; }
        }

        @Data
        static class Parameters {
            @JsonProperty("text_type")
            private String textType;
            Parameters() {}
            Parameters(String textType) { this.textType = textType; }
        }
    }

    @Data
    static class DashScopeResponse {
        private Output output;
        private Usage usage;

        @Data
        static class Output {
            private List<EmbeddingItem> embeddings;
        }

        @Data
        static class EmbeddingItem {
            private List<Double> embedding;
            @JsonProperty("text_index")
            private int textIndex;
        }

        @Data
        static class Usage {
            @JsonProperty("total_tokens")
            private int totalTokens;
        }
    }
}
