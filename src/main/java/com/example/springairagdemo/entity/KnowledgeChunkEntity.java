package com.example.springairagdemo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("knowledge_chunk")
public class KnowledgeChunkEntity {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属文档 */
    @TableField("document_id")
    private Long documentId;

    /** Chunk序号 */
    @TableField("chunk_index")
    private Integer chunkIndex;

    /** Chunk内容 */
    @TableField("content")
    private String content;

    /** Chunk内容Hash */
    @TableField("content_hash")
    private String contentHash;

    /** Token数量 */
    @TableField("token_count")
    private Integer tokenCount;

    /** PDF页码 */
    @TableField("page_no")
    private Integer pageNo;

    /** Milvus主键 */
    @TableField("milvus_id")
    private Long milvusId;

    /** 创建时间 */
    @TableField("create_time")
    private Date createTime;
}
