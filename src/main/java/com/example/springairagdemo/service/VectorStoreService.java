package com.example.springairagdemo.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.request.ReleaseCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.ranker.RRFRanker;
import io.milvus.v2.service.vector.response.SearchResp;
import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 向量库服务：按知识库维度管理 Milvus collection 的创建/删除/写入/检索
 * <p>
 * 每个知识库对应一个独立的 collection，命名规则：kb_{knowledgeBaseId}
 * <p>
 * 使用 Milvus v2 客户端（MilvusClientV2），collection 字段（文本落库，Milvus 侧同时存 BM25 全文索引字段）：
 * <pre>
 *   id          INT64                 主键（自增）
 *   knowledgeId INT64                 知识库 ID
 *   documentId  INT64                 文档 ID
 *   chunkId     INT64                 knowledge_chunk.id
 *   pageNo      INT32                 PDF 页码
 *   chunkIndex  INT32                 chunk 序号
 *   text        VARCHAR(65535)        chunk 文本（enable_analyzer，供 BM25 全文检索）
 *   sparse      SPARSE_FLOAT_VECTOR   BM25 函数输出（FUNCTION 自动生成，无需写入）
 *   embedding   FLOAT_VECTOR(1024)    dense 语义向量
 * </pre>
 * 检索支持两种模式：
 * <ul>
 *   <li>{@link #search} 纯向量检索（dense，兼容旧 collection）</li>
 *   <li>{@link #hybridSearch} 混合检索（dense + BM25 全文，RRF 融合，Milvus 服务端 2.5+）</li>
 * </ul>
 */
@Service
@Slf4j
public class VectorStoreService {

    /** Collection 前缀 */
    private static final String COLLECTION_PREFIX = "kb_";

    /** 向量维度，须与 EmbeddingModel 输出一致 */
    private static final int EMBEDDING_DIM = 1024;

    /** VarChar 字段最大字节数（Milvus 上限），中文按 UTF-8 3 字节/字符估算 */
    private static final int TEXT_MAX_LENGTH = 65535;

    /** 字段名 */
    private static final String FIELD_ID = "id";
    private static final String FIELD_KNOWLEDGE_ID = "knowledgeId";
    private static final String FIELD_DOCUMENT_ID = "documentId";
    private static final String FIELD_CHUNK_ID = "chunkId";
    private static final String FIELD_PAGE_NO = "pageNo";
    private static final String FIELD_CHUNK_INDEX = "chunkIndex";
    private static final String FIELD_EMBEDDING = "embedding";
    /** BM25 全文检索文本字段（schema 中配置 chinese analyzer） */
    private static final String FIELD_TEXT = "text";
    /** BM25 函数输出稀疏向量字段 */
    private static final String FIELD_SPARSE = "sparse";

    /** search 时额外返回的字段 */
    private static final List<String> OUT_FIELDS = List.of(
            FIELD_KNOWLEDGE_ID, FIELD_DOCUMENT_ID, FIELD_CHUNK_ID,
            FIELD_PAGE_NO, FIELD_CHUNK_INDEX);

    private final MilvusClientV2 milvusClient;
    private final EmbeddingModel embeddingModel;
    private final Gson gson = new Gson();

    /** 缓存已确认集合存在的 KB ID，避免重复 hasCollection 调用 */
    private final ConcurrentHashMap<Long, Boolean> collectionReady = new ConcurrentHashMap<>();

    public VectorStoreService(MilvusClientV2 milvusClient, EmbeddingModel embeddingModel) {
        this.milvusClient = milvusClient;
        this.embeddingModel = embeddingModel;
    }

    @PreDestroy
    public void close() {
        milvusClient.close();
    }

    // ===================== ChunkVectorData =====================

    /**
     * 待写入向量库的 chunk 数据（含文本用于生成 embedding 与 BM25 全文索引）
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChunkVectorData {
        /** MySQL knowledge_chunk.id */
        private Long chunkId;
        private Long documentId;
        private Integer pageNo;
        private Integer chunkIndex;
        /** 用于生成 embedding 与 BM25 索引的 chunk 文本 */
        private String content;
    }

    // ===================== SearchResult =====================

    /**
     * 检索返回结果（dense 相似度分或 RRF 融合分，见各方法说明）
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchResult {
        /** MySQL knowledge_chunk.id */
        private Long chunkId;
        private Long knowledgeId;
        private Long documentId;
        private Integer pageNo;
        private Integer chunkIndex;
        /** 分数：纯向量检索为余弦相似度，混合检索为 RRF 融合分 */
        private Double score;
    }

    // ===================== Collection 生命周期管理 =====================

    /**
     * 为指定知识库创建 Milvus collection（含 BM25 全文检索字段 + dense/稀疏双索引）
     *
     * @param knowledgeBaseId 知识库 ID
     */
    public void createCollection(Long knowledgeBaseId) {
        String collectionName = getCollectionName(knowledgeBaseId);

        if (hasCollection(knowledgeBaseId)) {
            if (!isHybridReady(collectionName)) {
                log.warn("Milvus collection [{}] 由旧版本创建，缺少 BM25 全文检索字段（text/sparse）。"
                        + "如需启用 Hybrid Search，请删除该 collection（或删除知识库后重建）并重新上传文档，"
                        + "当前将降级为纯向量检索。", collectionName);
            } else {
                log.info("Milvus collection [{}] 已存在，跳过创建", collectionName);
            }
            collectionReady.put(knowledgeBaseId, true);
            return;
        }

        // addField / addFunction 为 CollectionSchema 实例方法，需先构建空 schema 再链式添加
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build();
        schema.addField(scalarField(FIELD_ID, DataType.Int64, true))
                .addField(scalarField(FIELD_KNOWLEDGE_ID, DataType.Int64, false))
                .addField(scalarField(FIELD_DOCUMENT_ID, DataType.Int64, false))
                .addField(scalarField(FIELD_CHUNK_ID, DataType.Int64, false))
                .addField(scalarField(FIELD_PAGE_NO, DataType.Int32, false))
                .addField(scalarField(FIELD_CHUNK_INDEX, DataType.Int32, false))
                .addField(AddFieldReq.builder()
                        .fieldName(FIELD_EMBEDDING)
                        .dataType(DataType.FloatVector)
                        .dimension(EMBEDDING_DIM)
                        .build())
                // BM25 全文检索文本字段：启用内置 analyzer，Milvus 端自动分词（chinese）
                .addField(AddFieldReq.builder()
                        .fieldName(FIELD_TEXT)
                        .dataType(DataType.VarChar)
                        .maxLength(TEXT_MAX_LENGTH)
                        .enableAnalyzer(true)
                        .analyzerParams(Map.of("type", "chinese"))
                        .build())
                // BM25 函数输出字段：由 Milvus 端根据 text 自动生成稀疏向量，无需写入
                .addField(AddFieldReq.builder()
                        .fieldName(FIELD_SPARSE)
                        .dataType(DataType.SparseFloatVector)
                        .build())
                .addFunction(CreateCollectionReq.Function.builder()
                        .name("bm25")
                        .functionType(FunctionType.BM25)
                        .inputFieldNames(List.of(FIELD_TEXT))
                        .outputFieldNames(List.of(FIELD_SPARSE))
                        .build());

        CreateCollectionReq createReq = CreateCollectionReq.builder()
                .collectionName(collectionName)
                .collectionSchema(schema)
                .build();
        milvusClient.createCollection(createReq);
        log.info("Milvus collection [{}] 创建成功（含 BM25 全文检索字段）", collectionName);

        createIndexes(collectionName);

        milvusClient.loadCollection(LoadCollectionReq.builder().collectionName(collectionName).build());

        collectionReady.put(knowledgeBaseId, true);
        log.info("Milvus collection [{}] 索引创建并加载完毕", collectionName);
    }

    /**
     * 删除指定知识库的 Milvus collection
     */
    public void dropCollection(Long knowledgeBaseId) {
        String collectionName = getCollectionName(knowledgeBaseId);

        if (!hasCollection(knowledgeBaseId)) {
            log.info("Milvus collection [{}] 不存在，跳过删除", collectionName);
            collectionReady.remove(knowledgeBaseId);
            return;
        }

        milvusClient.releaseCollection(ReleaseCollectionReq.builder().collectionName(collectionName).build());
        milvusClient.dropCollection(DropCollectionReq.builder().collectionName(collectionName).build());
        log.info("Milvus collection [{}] 已删除", collectionName);

        collectionReady.remove(knowledgeBaseId);
    }

    // ===================== 向量写入 =====================

    /**
     * 将 chunk 向量批量写入 Milvus（文本同时写入 BM25 全文检索字段）
     *
     * @param knowledgeBaseId 知识库 ID
     * @param chunks          chunk 数据列表（含文本，用于生成 embedding 与 BM25 索引）
     * @return 写入条数
     */
    public int insertVectors(Long knowledgeBaseId, List<ChunkVectorData> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return 0;
        }
        String collectionName = getCollectionName(knowledgeBaseId);
        ensureReady(knowledgeBaseId);

        // 1. 批量向量化
        List<String> texts = chunks.stream().map(ChunkVectorData::getContent).toList();
        EmbeddingResponse embeddingResponse = embeddingModel.call(new EmbeddingRequest(texts, null));
        List<float[]> embeddings = embeddingResponse.getResults().stream()
                .map(Embedding::getOutput)
                .toList();

        // 2. 构建插入数据（id 自增无需提供，sparse 由 BM25 函数自动生成）
        List<JsonObject> data = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            ChunkVectorData c = chunks.get(i);
            float[] emb = embeddings.get(i);
            List<Float> vec = new ArrayList<>(emb.length);
            for (float v : emb) vec.add(v);

            JsonObject row = new JsonObject();
            row.addProperty(FIELD_KNOWLEDGE_ID, knowledgeBaseId);
            row.addProperty(FIELD_DOCUMENT_ID, c.getDocumentId());
            row.addProperty(FIELD_CHUNK_ID, c.getChunkId());
            row.addProperty(FIELD_PAGE_NO, c.getPageNo() != null ? c.getPageNo() : 0);
            row.addProperty(FIELD_CHUNK_INDEX, c.getChunkIndex() != null ? c.getChunkIndex() : 0);
            row.addProperty(FIELD_TEXT, truncateText(c.getContent()));
            row.add(FIELD_EMBEDDING, gson.toJsonTree(vec));
            data.add(row);
        }

        InsertReq insertReq = InsertReq.builder()
                .collectionName(collectionName)
                .data(data)
                .build();
        milvusClient.insert(insertReq);

        log.info("{} 个 chunk 向量已写入 Milvus collection [{}]", chunks.size(), collectionName);
        return chunks.size();
    }

    // ===================== 向量删除 =====================

    /**
     * 按文档 ID 删除向量（回滚用）
     *
     * @param knowledgeBaseId 知识库 ID
     * @param documentId      文档 ID
     */
    public void deleteVectorsByDocumentId(Long knowledgeBaseId, Long documentId) {
        String collectionName = getCollectionName(knowledgeBaseId);
        ensureReady(knowledgeBaseId);

        String expr = FIELD_DOCUMENT_ID + " == " + documentId;
        DeleteReq deleteReq = DeleteReq.builder()
                .collectionName(collectionName)
                .filter(expr)
                .build();
        milvusClient.delete(deleteReq);
        log.info("已删除文档 ID={} 的 Milvus 向量", documentId);
    }

    // ===================== 向量检索 =====================

    /**
     * 纯向量检索（dense）：在指定知识库中检索与查询最相关的 chunk
     *
     * @param knowledgeBaseId 知识库 ID
     * @param query           查询文本
     * @param topK            返回 Top-K 条
     * @param threshold       余弦相似度阈值
     * @return 检索结果列表（含 chunkId，调用方通过 chunkId 从 MySQL 获取文本内容）
     */
    public List<SearchResult> search(Long knowledgeBaseId, String query, int topK, double threshold) {
        String collectionName = getCollectionName(knowledgeBaseId);
        ensureReady(knowledgeBaseId);

        List<Float> queryVec = embedQuery(query);

        SearchReq searchReq = SearchReq.builder()
                .collectionName(collectionName)
                .data(List.of(new FloatVec(queryVec)))
                .annsField(FIELD_EMBEDDING)
                .metricType(IndexParam.MetricType.COSINE)
                .topK(topK)
                .outputFields(OUT_FIELDS)
                .build();
        SearchResp resp = milvusClient.search(searchReq);

        List<SearchResult> results = parseSearchResp(resp, threshold);
        log.info("在 collection [{}] 中检索到 {} 个相关 chunk (query={})", collectionName, results.size(), query);
        return results;
    }

    /**
     * 混合检索（Hybrid Search）：Dense 向量 + BM25 全文检索双路召回，Milvus 端 RRF 融合
     * <p>
     * 需要 collection 含 BM25 字段（由 {@link #createCollection} 创建），旧版 collection 不支持。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param query           查询文本
     * @param denseTopK       dense 路召回候选数
     * @param bm25TopK        BM25 路召回候选数
     * @param rrfK            RRF 平滑系数 k（score = Σ 1/(k + rank)）
     * @param topK            融合后返回 Top-K 条
     * @return 融合结果列表（score 为 RRF 融合分）
     */
    public List<SearchResult> hybridSearch(Long knowledgeBaseId, String query, int denseTopK, int bm25TopK,
                                           int rrfK, int topK) {
        String collectionName = getCollectionName(knowledgeBaseId);
        ensureReady(knowledgeBaseId);

        List<Float> queryVec = embedQuery(query);

        // 路 1：dense 语义检索
        AnnSearchReq denseReq = AnnSearchReq.builder()
                .vectorFieldName(FIELD_EMBEDDING)
                .metricType(IndexParam.MetricType.COSINE)
                .vectors(List.of(new FloatVec(queryVec)))
                .topK(denseTopK)
                .build();
        // 路 2：BM25 全文检索（文本直接由 Milvus 内置 analyzer 分词并生成稀疏向量）
        AnnSearchReq bm25Req = AnnSearchReq.builder()
                .vectorFieldName(FIELD_SPARSE)
                .metricType(IndexParam.MetricType.BM25)
                .vectors(List.of(new EmbeddedText(query)))
                .topK(bm25TopK)
                .build();

        HybridSearchReq hybridReq = HybridSearchReq.builder()
                .collectionName(collectionName)
                .searchRequests(List.of(denseReq, bm25Req))
                .ranker(RRFRanker.builder().k(rrfK).build())
                .topK(topK)
                .outFields(OUT_FIELDS)
                .build();
        SearchResp resp = milvusClient.hybridSearch(hybridReq);

        // RRF 融合分不应用 cosine 阈值，过滤由调用方（HybridSearchService）按配置处理
        List<SearchResult> results = parseSearchResp(resp, 0.0);
        log.info("Hybrid 检索 collection [{}] 召回 {} 个 chunk (query={})", collectionName, results.size(), query);
        return results;
    }

    /**
     * 纯 BM25 全文检索：仅用关键词路召回，不依赖 embedding
     * <p>
     * 用于 embedding 服务异常时的兜底检索（HybridSearchService 降级链的一环），
     * 需要 collection 含 BM25 字段（由 {@link #createCollection} 创建），旧版 collection 不支持。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param query           查询文本（由 Milvus 内置 analyzer 分词）
     * @param topK            返回 Top-K 条
     * @return 检索结果列表（score 为 BM25 相关性分）
     */
    public List<SearchResult> bm25Search(Long knowledgeBaseId, String query, int topK) {
        String collectionName = getCollectionName(knowledgeBaseId);
        ensureReady(knowledgeBaseId);

        SearchReq searchReq = SearchReq.builder()
                .collectionName(collectionName)
                .data(List.of(new EmbeddedText(query)))
                .annsField(FIELD_SPARSE)
                .metricType(IndexParam.MetricType.BM25)
                .topK(topK)
                .outputFields(OUT_FIELDS)
                .build();
        SearchResp resp = milvusClient.search(searchReq);

        List<SearchResult> results = parseSearchResp(resp, 0.0);
        log.info("BM25 全文检索 collection [{}] 召回 {} 个 chunk (query={})", collectionName, results.size(), query);
        return results;
    }

    // ===================== 辅助方法 =====================

    private List<Float> embedQuery(String query) {
        float[] queryEmbedding = embeddingModel.embed(query);
        List<Float> queryVec = new ArrayList<>(queryEmbedding.length);
        for (float v : queryEmbedding) queryVec.add(v);
        return queryVec;
    }

    private List<SearchResult> parseSearchResp(SearchResp resp, double threshold) {
        List<SearchResult> results = new ArrayList<>();
        List<List<SearchResp.SearchResult>> rows = resp.getSearchResults();
        if (rows == null || rows.isEmpty()) {
            return results;
        }
        for (SearchResp.SearchResult sr : rows.get(0)) {
            double score = sr.getScore() != null ? sr.getScore() : 0.0;
            if (score < threshold) continue;
            Map<String, Object> entity = sr.getEntity();
            results.add(new SearchResult(
                    toLong(entity.get(FIELD_CHUNK_ID)),
                    toLong(entity.get(FIELD_KNOWLEDGE_ID)),
                    toLong(entity.get(FIELD_DOCUMENT_ID)),
                    toInt(entity.get(FIELD_PAGE_NO)),
                    toInt(entity.get(FIELD_CHUNK_INDEX)),
                    score));
        }
        return results;
    }

    private String getCollectionName(Long knowledgeBaseId) {
        return COLLECTION_PREFIX + knowledgeBaseId;
    }

    private boolean hasCollection(Long knowledgeBaseId) {
        return Boolean.TRUE.equals(milvusClient.hasCollection(
                HasCollectionReq.builder().collectionName(getCollectionName(knowledgeBaseId)).build()));
    }

    /** 检查 collection 是否为含 BM25 字段的新版 schema */
    private boolean isHybridReady(String collectionName) {
        try {
            DescribeCollectionResp resp = milvusClient.describeCollection(
                    DescribeCollectionReq.builder().collectionName(collectionName).build());
            return resp.getFieldNames() != null && resp.getFieldNames().contains(FIELD_SPARSE);
        } catch (Exception e) {
            log.warn("检查 collection [{}] schema 失败: {}", collectionName, e.getMessage());
            return false;
        }
    }

    private void ensureReady(Long knowledgeBaseId) {
        if (Boolean.TRUE.equals(collectionReady.get(knowledgeBaseId))) {
            return;
        }
        if (!hasCollection(knowledgeBaseId)) {
            throw new RuntimeException("知识库 [" + knowledgeBaseId + "] 的向量集合不存在，请先创建知识库");
        }
        collectionReady.put(knowledgeBaseId, true);
    }

    private void createIndexes(String collectionName) {
        List<IndexParam> indexParams = new ArrayList<>();
        indexParams.add(IndexParam.builder()
                .fieldName(FIELD_EMBEDDING)
                .indexType(IndexParam.IndexType.IVF_FLAT)
                .metricType(IndexParam.MetricType.COSINE)
                .build());
        indexParams.add(IndexParam.builder()
                .fieldName(FIELD_SPARSE)
                .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                .metricType(IndexParam.MetricType.BM25)
                .build());
        milvusClient.createIndex(CreateIndexReq.builder()
                .collectionName(collectionName)
                .indexParams(indexParams)
                .build());
    }

    private static AddFieldReq scalarField(String fieldName, DataType dataType, boolean primaryKey) {
        AddFieldReq.AddFieldReqBuilder builder = AddFieldReq.builder()
                .fieldName(fieldName)
                .dataType(dataType);
        if (primaryKey) {
            builder.isPrimaryKey(true).autoID(true);
        }
        return builder.build();
    }

    private static String truncateText(String content) {
        if (content == null) {
            return "";
        }
        // VarChar 按字节计长，中文 UTF-8 约 3 字节/字符，保守按字节上限的 1/3 截断字符数
        if (content.length() > TEXT_MAX_LENGTH / 3) {
            return content.substring(0, TEXT_MAX_LENGTH / 3);
        }
        return content;
    }

    private static Long toLong(Object obj) {
        if (obj instanceof Long) return (Long) obj;
        if (obj instanceof Number) return ((Number) obj).longValue();
        if (obj instanceof String) return Long.parseLong((String) obj);
        return null;
    }

    private static Integer toInt(Object obj) {
        if (obj instanceof Integer) return (Integer) obj;
        if (obj instanceof Number) return ((Number) obj).intValue();
        if (obj instanceof String) return Integer.parseInt((String) obj);
        return null;
    }
}
