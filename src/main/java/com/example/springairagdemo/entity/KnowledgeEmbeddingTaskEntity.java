package com.example.springairagdemo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * Embedding 任务实体，映射 knowledge_embedding_task 表。
 * <p>
 * PDF 文档上传后先创建任务（0待处理），异步线程执行解析/切分/向量化，
 * 处理完成后更新为 2成功 或 3失败，前端通过任务编号轮询进度。
 */
@Data
@TableName("knowledge_embedding_task")
public class KnowledgeEmbeddingTaskEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务编号 */
    @TableField("task_no")
    private String taskNo;

    /** 文档 ID（外键指向 knowledge_document.id） */
    @TableField("document_id")
    private Long documentId;

    /** 任务状态（0待处理 1处理中 2成功 3失败） */
    @TableField("status")
    private KnowledgeEmbeddingTaskStatus status;

    /** Chunk 总数 */
    @TableField("total_chunk")
    private Integer totalChunk;

    /** 成功 Chunk 数 */
    @TableField("success_chunk")
    private Integer successChunk;

    /** 失败 Chunk 数 */
    @TableField("fail_chunk")
    private Integer failChunk;

    /** 阶段进度-PDF解析（0-100，0=未开始 100=完成） */
    @TableField("parse_progress")
    private Integer parseProgress;

    /** 阶段进度-文本切片（0-100） */
    @TableField("split_progress")
    private Integer splitProgress;

    /** 阶段进度-Chunk入库 MySQL（0-100） */
    @TableField("chunk_progress")
    private Integer chunkProgress;

    /** 阶段进度-Embedding 向量化（0-100，按批实时推进） */
    @TableField("embed_progress")
    private Integer embedProgress;

    /** 阶段进度-Milvus 写入（0-100，按批实时推进） */
    @TableField("milvus_progress")
    private Integer milvusProgress;

    /** 重试次数 */
    @TableField("retry_count")
    private Integer retryCount;

    /** 失败原因 */
    @TableField("error_message")
    private String errorMessage;

    /** 开始时间 */
    @TableField("start_time")
    private Date startTime;

    /** 结束时间 */
    @TableField("finish_time")
    private Date finishTime;

    /** 耗时(ms) */
    @TableField("cost_time")
    private Long costTime;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;
}
