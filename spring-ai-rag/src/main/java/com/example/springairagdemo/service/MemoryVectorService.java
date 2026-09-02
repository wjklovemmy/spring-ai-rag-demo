package com.example.springairagdemo.service;

import com.example.springairagdemo.config.AiConfig;
import com.example.springairagdemo.config.RagConfigProperties;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.request.ReleaseCollectionReq;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 用户长期记忆向量服务（Phase 2）。
 *
 * <p>使用单一全局 Milvus collection（默认 {@code rag_user_memory}）承载所有用户的长期记忆，
 * 通过 {@code userId} 标量字段做用户隔离；主键 = MySQL {@code user_long_term_memory.id}
 * （幂等 upsert，逻辑删除时按 id 同步 delete 向量）。
 *
 * <p>文本由 DashScope text-embedding-v3 向量化（1024 维，与 {@link VectorStoreService} 共用
 * 熔断资源 dashscope-embedding）；集合按需懒创建（embedding 向量索引 IVF_FLAT/COSINE +
 * userId/category/importance 标量倒排索引 INVERTED，加速用户隔离过滤与标量检索）。
 */
@Service
@Slf4j
public class MemoryVectorService {

    /** text-embedding-v3 输出维度（与 VectorStoreService 一致） */
    private static final int EMBEDDING_DIM = 1024;
    /** Milvus VarChar 字段字节上限按 65535 处理，内容按 1/3 字符截断以兼容多字节 */
    private static final int TEXT_MAX_LENGTH = 65535;
    private static final int TEXT_SAFE_CHARS = TEXT_MAX_LENGTH / 3;
    private static final int CATEGORY_MAX_LENGTH = 32;

    private static final String FIELD_ID = "id";
    private static final String FIELD_USER_ID = "userId";
    private static final String FIELD_CATEGORY = "category";
    private static final String FIELD_IMPORTANCE = "importance";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_EMBEDDING = "embedding";

    private static final List<String> OUTPUT_FIELDS =
            List.of(FIELD_ID, FIELD_USER_ID, FIELD_CATEGORY, FIELD_IMPORTANCE, FIELD_CONTENT);

    private final MilvusClientV2 milvusClient;
    private final EmbeddingModel embeddingModel;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;
    private final RagConfigProperties ragConfig;
    private final Gson gson = new Gson();

    /** 集合已确认存在/加载标记（懒初始化，避免每次调用都 hasCollection） */
    private final AtomicBoolean ready = new AtomicBoolean(false);

    public MemoryVectorService(MilvusClientV2 milvusClient,
                               EmbeddingModel embeddingModel,
                               CircuitBreakerFactory<?, ?> circuitBreakerFactory,
                               RagConfigProperties ragConfig) {
        this.milvusClient = milvusClient;
        this.embeddingModel = embeddingModel;
        this.circuitBreakerFactory = circuitBreakerFactory;
        this.ragConfig = ragConfig;
    }

    public record MemoryVectorHit(Long id, String content, String category, Integer importance, double score) {
    }

    private String collectionName() {
        return ragConfig.getMemory().getLongTerm().getCollectionName();
    }

    /**
     * 向量化单条文本（熔断资源 dashscope-embedding，与文档向量化共用规则）；
     * 失败时抛出 {@link EmbeddingServiceUnavailableException}，由调用方决定降级策略。
     */
    public float[] embed(String text) {
        return circuitBreakerFactory.create(AiConfig.EMBEDDING_RESOURCE).run(
                () -> embeddingModel.embed(text),
                throwable -> {
                    log.error("用户长期记忆向量化失败（DashScope embedding 熔断/异常）: {}", throwable.getMessage());
                    throw new EmbeddingServiceUnavailableException("向量化服务暂时不可用，请稍后重试", throwable);
                });
    }

    /** 确保全局用户记忆集合存在并已加载（首次调用懒创建/升级补索引） */
    public void ensureReady() {
        if (ready.get()) {
            return;
        }
        synchronized (this) {
            if (ready.get()) {
                return;
            }
            String name = collectionName();
            boolean exists = milvusClient.hasCollection(HasCollectionReq.builder().collectionName(name).build());
            if (!exists) {
                CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build();
                schema.addField(AddFieldReq.builder().fieldName(FIELD_ID).dataType(DataType.Int64)
                        .isPrimaryKey(true).autoID(false).build());
                schema.addField(AddFieldReq.builder().fieldName(FIELD_USER_ID).dataType(DataType.Int64).build());
                schema.addField(AddFieldReq.builder().fieldName(FIELD_CATEGORY).dataType(DataType.VarChar)
                        .maxLength(CATEGORY_MAX_LENGTH).build());
                schema.addField(AddFieldReq.builder().fieldName(FIELD_IMPORTANCE).dataType(DataType.Int32).build());
                schema.addField(AddFieldReq.builder().fieldName(FIELD_CONTENT).dataType(DataType.VarChar)
                        .maxLength(TEXT_MAX_LENGTH).build());
                schema.addField(AddFieldReq.builder().fieldName(FIELD_EMBEDDING).dataType(DataType.FloatVector)
                        .dimension(EMBEDDING_DIM).build());
                milvusClient.createCollection(CreateCollectionReq.builder()
                        .collectionName(name)
                        .collectionSchema(schema)
                        .build());
                createVectorIndex(name);
                tryCreateScalarIndexes(name);
                log.info("用户长期记忆 Milvus 全局集合 [{}] 已创建（userId 隔离，IVF_FLAT/COSINE + 标量倒排索引，维度 {}）",
                        name, EMBEDDING_DIM);
            } else {
                // 集合已存在（历史版本升级）：release 后补齐/覆盖索引（含 userId/category/importance 标量倒排索引）再加载。
                // createIndex 幂等：同名字段索引覆盖重建、新字段索引增量追加；集合数据量小，重建成本可忽略。
                try {
                    milvusClient.releaseCollection(ReleaseCollectionReq.builder().collectionName(name).build());
                    createVectorIndex(name);
                    tryCreateScalarIndexes(name);
                    log.info("用户长期记忆 Milvus 集合 [{}] 已存在，补齐/覆盖索引完成（含 userId/category/importance 标量倒排索引）",
                            name);
                } catch (Exception e) {
                    log.warn("补齐用户长期记忆集合 [{}] 索引失败（降级为使用集合已有索引，不影响检索）: {}",
                            name, e.getMessage());
                }
            }
            milvusClient.loadCollection(LoadCollectionReq.builder().collectionName(name).build());
            ready.set(true);
            log.debug("用户长期记忆 Milvus 集合 [{}] 已就绪", name);
        }
    }

    /** 创建/覆盖 embedding 向量索引（IVF_FLAT/COSINE）。检索依赖该索引，失败直接上抛终止 */
    private void createVectorIndex(String name) {
        milvusClient.createIndex(CreateIndexReq.builder()
                .collectionName(name)
                .indexParams(List.of(IndexParam.builder()
                        .fieldName(FIELD_EMBEDDING)
                        .indexType(IndexParam.IndexType.IVF_FLAT)
                        .metricType(IndexParam.MetricType.COSINE)
                        .build()))
                .build());
    }

    /** 补建标量倒排索引（userId/category/importance）：Milvus 2.4+ 支持；老版本不支持时仅告警降级，
     *  标量过滤退化为全扫描 expression 求值（功能可用，海量数据下稍慢）。向量索引单独建，互不影响。 */
    private void tryCreateScalarIndexes(String name) {
        try {
            milvusClient.createIndex(CreateIndexReq.builder()
                    .collectionName(name)
                    .indexParams(List.of(
                            // userId 每次检索必过滤（用户隔离），category/importance 在管理与统计中频繁过滤
                            IndexParam.builder().fieldName(FIELD_USER_ID).indexType(IndexParam.IndexType.INVERTED).build(),
                            IndexParam.builder().fieldName(FIELD_CATEGORY).indexType(IndexParam.IndexType.INVERTED).build(),
                            IndexParam.builder().fieldName(FIELD_IMPORTANCE).indexType(IndexParam.IndexType.INVERTED).build()))
                    .build());
        } catch (Exception e) {
            log.warn("创建用户长期记忆集合 [{}] 标量倒排索引失败（Milvus < 2.4 不支持，检索仍可用）: {}",
                    name, e.getMessage());
        }
    }

    /** 幂等写入一条记忆向量（PK = MySQL memory id；embedding 由调用方预先算好，避免重复调用） */
    public void upsertWithVector(Long memoryId, Long userId, String content, String category,
                                 int importance, float[] embedding) {
        ensureReady();
        JsonObject row = new JsonObject();
        row.addProperty(FIELD_ID, memoryId);
        row.addProperty(FIELD_USER_ID, userId);
        row.addProperty(FIELD_CATEGORY, truncate(category, CATEGORY_MAX_LENGTH));
        row.addProperty(FIELD_IMPORTANCE, importance);
        row.addProperty(FIELD_CONTENT, truncate(content, TEXT_SAFE_CHARS));
        row.add(FIELD_EMBEDDING, gson.toJsonTree(toFloats(embedding)));
        UpsertReq request = UpsertReq.builder()
                .collectionName(collectionName())
                .data(List.of(row))
                .build();
        milvusClient.upsert(request);
    }

    /** 按用户+查询文本做余弦检索（内部完成向量化，失败抛 EmbeddingServiceUnavailableException） */
    public List<MemoryVectorHit> search(Long userId, String query, int topK, double minScore) {
        return searchByVector(userId, embed(query), topK, minScore);
    }

    /** 按用户+给定向量检索（saveMemory 语义去重复用，避免重复向量化） */
    public List<MemoryVectorHit> searchByVector(Long userId, float[] vector, int topK, double minScore) {
        ensureReady();
        SearchReq request = SearchReq.builder()
                .collectionName(collectionName())
                .data(List.of(new FloatVec(toFloats(vector))))
                .annsField(FIELD_EMBEDDING)
                .metricType(IndexParam.MetricType.COSINE)
                .topK(topK)
                .filter(FIELD_USER_ID + " == " + userId)
                .outputFields(OUTPUT_FIELDS)
                .build();
        SearchResp resp = milvusClient.search(request);
        List<MemoryVectorHit> hits = new ArrayList<>();
        List<List<SearchResp.SearchResult>> rows = resp.getSearchResults();
        if (rows == null || rows.isEmpty()) {
            return hits;
        }
        for (SearchResp.SearchResult result : rows.get(0)) {
            double score = result.getScore() == null ? 0.0 : result.getScore();
            if (score < minScore) {
                continue;
            }
            Map<String, Object> entity = result.getEntity();
            Long id = toLong(entity.get(FIELD_ID));
            if (id == null) {
                continue;
            }
            String content = entity.get(FIELD_CONTENT) == null ? "" : String.valueOf(entity.get(FIELD_CONTENT));
            String category = entity.get(FIELD_CATEGORY) == null ? null : String.valueOf(entity.get(FIELD_CATEGORY));
            Integer importance = entity.get(FIELD_IMPORTANCE) == null ? null : toInt(entity.get(FIELD_IMPORTANCE));
            hits.add(new MemoryVectorHit(id, content, category, importance, score));
        }
        return hits;
    }

    /** 按记忆 id 删除向量（逻辑删除后调用；幂等） */
    public void deleteByIds(List<Long> memoryIds) {
        if (memoryIds == null || memoryIds.isEmpty()) {
            return;
        }
        ensureReady();
        String ids = memoryIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        milvusClient.delete(DeleteReq.builder()
                .collectionName(collectionName())
                .filter(FIELD_ID + " in [" + ids + "]")
                .build());
    }

    /** 删除某用户的全部记忆向量（用户注销/清空用；幂等） */
    public void deleteByUser(Long userId) {
        if (userId == null) {
            return;
        }
        ensureReady();
        milvusClient.delete(DeleteReq.builder()
                .collectionName(collectionName())
                .filter(FIELD_USER_ID + " == " + userId)
                .build());
    }

    private static Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String str && !str.isBlank()) {
            try {
                return Long.parseLong(str);
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private static Integer toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str && !str.isBlank()) {
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private static List<Float> toFloats(float[] values) {
        List<Float> floats = new ArrayList<>(values.length);
        for (float value : values) {
            floats.add(value);
        }
        return floats;
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }
}
