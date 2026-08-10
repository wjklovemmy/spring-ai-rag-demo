package com.example.springairagdemo.embedding;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.web.client.RestClient;

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

    private final RestClient restClient;
    private final String model;

    public DashScopeEmbeddingModel(String apiKey, String model) {
        this.model = model;
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        log.debug("DashScope embedding 请求，文本数量: {}", texts.size());

        DashScopeRequest body = new DashScopeRequest();
        body.setModel(model);
        body.setInput(new DashScopeRequest.Input(texts));
        body.setParameters(new DashScopeRequest.Parameters("document"));

        DashScopeResponse response = restClient.post()
                .body(body)
                .retrieve()
                .body(DashScopeResponse.class);

        if (response == null || response.getOutput() == null) {
            log.error("DashScope embedding 返回空响应");
            return new EmbeddingResponse(List.of());
        }

        List<Embedding> embeddings = new ArrayList<>();
        for (DashScopeResponse.EmbeddingItem item : response.getOutput().getEmbeddings()) {
            float[] vector = new float[item.getEmbedding().size()];
            for (int i = 0; i < item.getEmbedding().size(); i++) {
                vector[i] = item.getEmbedding().get(i).floatValue();
            }
            embeddings.add(new Embedding(vector, item.getTextIndex()));
        }

        log.debug("DashScope embedding 返回 {} 个向量", embeddings.size());
        return new EmbeddingResponse(embeddings);
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
