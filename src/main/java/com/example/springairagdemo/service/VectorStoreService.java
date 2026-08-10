package com.example.springairagdemo.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import io.milvus.response.SearchResultsWrapper.IDScore;
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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 向量库服务：按知识库维度管理 Milvus collection 的创建/删除/写入/检索
 * <p>
 * 每个知识库对应一个独立的 collection，命名规则：kb_{knowledgeBaseId}
 * <p>
 * Milvus collection 字段（仅存向量 + 引用，文本内容存 MySQL knowledge_chunk）：
 * <pre>
 *   id          INT64        主键（自增）
 *   knowledgeId INT64        知识库 ID
 *   documentId  INT64        文档 ID
 *   chunkId     INT64        knowledge_chunk.id
 *   pageNo      INT32        PDF 页码
 *   chunkIndex  INT32        chunk 序号
 *   embedding   FLOAT_VECTOR(1024)
 * </pre>
 */
@Service
@Slf4j
public class VectorStoreService {

    /** Collection 前缀 */
    private static final String COLLECTION_PREFIX = "kb_";

    /** 向量维度，须与 EmbeddingModel 输出一致 */
    private static final int EMBEDDING_DIM = 1024;

    /** 字段名 */
    private static final String FIELD_ID = "id";
    private static final String FIELD_KNOWLEDGE_ID = "knowledgeId";
    private static final String FIELD_DOCUMENT_ID = "documentId";
    private static final String FIELD_CHUNK_ID = "chunkId";
    private static final String FIELD_PAGE_NO = "pageNo";
    private static final String FIELD_CHUNK_INDEX = "chunkIndex";
    private static final String FIELD_EMBEDDING = "embedding";

    /** search 时额外返回的字段 */
    private static final List<String> OUT_FIELDS = List.of(
            FIELD_KNOWLEDGE_ID, FIELD_DOCUMENT_ID, FIELD_CHUNK_ID,
            FIELD_PAGE_NO, FIELD_CHUNK_INDEX);

    private final MilvusServiceClient milvusClient;
    private final EmbeddingModel embeddingModel;

    /** 缓存已确认集合存在的 KB ID，避免重复 hasCollection 调用 */
    private final ConcurrentHashMap<Long, Boolean> collectionReady = new ConcurrentHashMap<>();

    public VectorStoreService(MilvusServiceClient milvusClient, EmbeddingModel embeddingModel) {
        this.milvusClient = milvusClient;
        this.embeddingModel = embeddingModel;
    }

    @PreDestroy
    public void close() {
        milvusClient.close();
    }

    // ===================== ChunkVectorData =====================

    /**
     * 待写入向量库的 chunk 数据（含文本用于生成 embedding）
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
        /** 用于生成 embedding 的 chunk 文本 */
        private String content;
    }

    // ===================== SearchResult =====================

    /**
     * 向量检索返回结果
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
        /** 相似度分数 */
        private Double score;
    }

    // ===================== Collection 生命周期管理 =====================

    /**
     * 为指定知识库创建 Milvus collection（含索引）
     *
     * @param knowledgeBaseId 知识库 ID
     */
    public void createCollection(Long knowledgeBaseId) {
        String collectionName = getCollectionName(knowledgeBaseId);

        if (hasCollection(knowledgeBaseId)) {
            log.info("Milvus collection [{}] 已存在，跳过创建", collectionName);
            collectionReady.put(knowledgeBaseId, true);
            return;
        }

        List<FieldType> fields = new ArrayList<>();
        fields.add(FieldType.newBuilder()
                .withName(FIELD_ID)
                .withDataType(DataType.Int64)
                .withPrimaryKey(true)
                .withAutoID(true)
                .build());
        fields.add(FieldType.newBuilder()
                .withName(FIELD_KNOWLEDGE_ID)
                .withDataType(DataType.Int64)
                .build());
        fields.add(FieldType.newBuilder()
                .withName(FIELD_DOCUMENT_ID)
                .withDataType(DataType.Int64)
                .build());
        fields.add(FieldType.newBuilder()
                .withName(FIELD_CHUNK_ID)
                .withDataType(DataType.Int64)
                .build());
        fields.add(FieldType.newBuilder()
                .withName(FIELD_PAGE_NO)
                .withDataType(DataType.Int32)
                .build());
        fields.add(FieldType.newBuilder()
                .withName(FIELD_CHUNK_INDEX)
                .withDataType(DataType.Int32)
                .build());
        fields.add(FieldType.newBuilder()
                .withName(FIELD_EMBEDDING)
                .withDataType(DataType.FloatVector)
                .withDimension(EMBEDDING_DIM)
                .build());

        CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldTypes(fields)
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
                .build();

        R<RpcStatus> createResult = milvusClient.createCollection(createParam);
        if (createResult.getStatus() != 0) {
            throw new RuntimeException("创建 Milvus collection [" + collectionName + "] 失败: "
                    + createResult.getMessage());
        }
        log.info("Milvus collection [{}] 创建成功", collectionName);

        createIndex(collectionName);

        milvusClient.loadCollection(
                io.milvus.param.collection.LoadCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .build());

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

        milvusClient.releaseCollection(
                io.milvus.param.collection.ReleaseCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .build());

        R<RpcStatus> dropResult = milvusClient.dropCollection(
                DropCollectionParam.newBuilder().withCollectionName(collectionName).build());

        if (dropResult.getStatus() != 0) {
            log.error("删除 Milvus collection [{}] 失败: {}", collectionName, dropResult.getMessage());
        } else {
            log.info("Milvus collection [{}] 已删除", collectionName);
        }

        collectionReady.remove(knowledgeBaseId);
    }

    // ===================== 向量写入 =====================

    /**
     * 将 chunk 向量批量写入 Milvus
     *
     * @param knowledgeBaseId 知识库 ID
     * @param chunks          chunk 数据列表（含文本，用于生成 embedding）
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

        // 2. 构建插入数据（id 自增，无需提供）
        List<Long> knowledgeIds = new ArrayList<>();
        List<Long> docIds = new ArrayList<>();
        List<Long> chunkIds = new ArrayList<>();
        List<Integer> pageNos = new ArrayList<>();
        List<Integer> chunkIndices = new ArrayList<>();
        List<List<Float>> vectorList = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            ChunkVectorData c = chunks.get(i);
            knowledgeIds.add(knowledgeBaseId);
            docIds.add(c.getDocumentId());
            chunkIds.add(c.getChunkId());
            pageNos.add(c.getPageNo() != null ? c.getPageNo() : 0);
            chunkIndices.add(c.getChunkIndex() != null ? c.getChunkIndex() : 0);

            float[] emb = embeddings.get(i);
            List<Float> vec = new ArrayList<>(emb.length);
            for (float v : emb) vec.add(v);
            vectorList.add(vec);
        }

        List<InsertParam.Field> fieldsData = new ArrayList<>();
        fieldsData.add(new InsertParam.Field(FIELD_KNOWLEDGE_ID, knowledgeIds));
        fieldsData.add(new InsertParam.Field(FIELD_DOCUMENT_ID, docIds));
        fieldsData.add(new InsertParam.Field(FIELD_CHUNK_ID, chunkIds));
        fieldsData.add(new InsertParam.Field(FIELD_PAGE_NO, pageNos));
        fieldsData.add(new InsertParam.Field(FIELD_CHUNK_INDEX, chunkIndices));
        fieldsData.add(new InsertParam.Field(FIELD_EMBEDDING, vectorList));

        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(collectionName)
                .withFields(fieldsData)
                .build();

        R<MutationResult> insertResult = milvusClient.insert(insertParam);
        if (insertResult.getStatus() != 0) {
            throw new RuntimeException("向量写入 collection [" + collectionName + "] 失败: "
                    + insertResult.getMessage());
        }

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
        DeleteParam param = DeleteParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr(expr)
                .build();

        R<MutationResult> result = milvusClient.delete(param);
        if (result.getStatus() != 0) {
            log.error("按文档 ID={} 删除 Milvus 向量失败: {}", documentId, result.getMessage());
        } else {
            log.info("已删除文档 ID={} 的 Milvus 向量", documentId);
        }
    }

    // ===================== 向量检索 =====================

    /**
     * 在指定知识库中检索与查询最相关的 chunk
     *
     * @param knowledgeBaseId 知识库 ID
     * @param query           查询文本
     * @param topK            返回 Top-K 条
     * @param threshold       相似度阈值
     * @return 检索结果列表（含 chunkId，调用方通过 chunkId 从 MySQL 获取文本内容）
     */
    public List<SearchResult> search(Long knowledgeBaseId, String query, int topK, double threshold) {
        String collectionName = getCollectionName(knowledgeBaseId);
        ensureReady(knowledgeBaseId);

        // 1. 向量化查询
        float[] queryEmbedding = embeddingModel.embed(query);
        List<Float> queryVec = new ArrayList<>(queryEmbedding.length);
        for (float v : queryEmbedding) queryVec.add(v);

        // 2. 构建检索参数
        SearchParam.Builder searchBuilder = SearchParam.newBuilder()
                .withCollectionName(collectionName)
                .withVectorFieldName(FIELD_EMBEDDING)
                .withVectors(Collections.singletonList(queryVec))
                .withTopK(topK)
                .withMetricType(MetricType.COSINE)
                .withParams("{\"nprobe\": 16}")
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG);
        for (String outField : OUT_FIELDS) {
            searchBuilder.addOutField(outField);
        }
        SearchParam searchParam = searchBuilder.build();

        R<SearchResults> searchResult = milvusClient.search(searchParam);
        if (searchResult.getStatus() != 0) {
            throw new RuntimeException("向量检索 collection [" + collectionName + "] 失败: "
                    + searchResult.getMessage());
        }

        // 3. 解析结果：用 getFieldData() 取标量字段的 List<?>，避免 FieldDataWrapper.get(int,String) 只支持 JSON 类型的问题
        SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResult.getData().getResults());

        List<?> knowledgeIds = wrapper.getFieldData(FIELD_KNOWLEDGE_ID, 0);
        List<?> docIds = wrapper.getFieldData(FIELD_DOCUMENT_ID, 0);
        List<?> chunkIds = wrapper.getFieldData(FIELD_CHUNK_ID, 0);
        List<?> pageNos = wrapper.getFieldData(FIELD_PAGE_NO, 0);
        List<?> chunkIndices = wrapper.getFieldData(FIELD_CHUNK_INDEX, 0);

        List<SearchResult> results = new ArrayList<>();
        List<IDScore> idScores = wrapper.getIDScore(0);
        for (int i = 0; i < idScores.size(); i++) {
            IDScore idScore = idScores.get(i);
            double score = idScore.getScore();
            if (score < threshold) continue;

            results.add(new SearchResult(
                    toLong(chunkIds.get(i)),
                    toLong(knowledgeIds.get(i)),
                    toLong(docIds.get(i)),
                    toInt(pageNos.get(i)),
                    toInt(chunkIndices.get(i)),
                    score));
        }

        log.info("在 collection [{}] 中检索到 {} 个相关 chunk (query={})", collectionName, results.size(), query);
        return results;
    }

    // ===================== 辅助方法 =====================

    private String getCollectionName(Long knowledgeBaseId) {
        return COLLECTION_PREFIX + knowledgeBaseId;
    }

    private boolean hasCollection(Long knowledgeBaseId) {
        R<Boolean> result = milvusClient.hasCollection(
                HasCollectionParam.newBuilder()
                        .withCollectionName(getCollectionName(knowledgeBaseId))
                        .build());
        return result.getStatus() == 0 && Boolean.TRUE.equals(result.getData());
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

    private void createIndex(String collectionName) {
        CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldName(FIELD_EMBEDDING)
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.COSINE)
                .withExtraParam("{\"nlist\": 128}")
                .build();

        R<RpcStatus> result = milvusClient.createIndex(indexParam);
        if (result.getStatus() != 0) {
            throw new RuntimeException("创建索引失败: " + result.getMessage());
        }
    }
}
