package com.example.springairagdemo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("knowledge_document")
public class KnowledgeDocumentEntity {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属知识库 */
    @TableField("knowledge_id")
    private Long knowledgeId;

    /** 文件名称 */
    @TableField("file_name")
    private String fileName;

    /** 文件路径 */
    @TableField("file_path")
    private String filePath;

    /** 文件大小(Byte) */
    @TableField("file_size")
    private Long fileSize;

    /** 文件类型 */
    @TableField("file_type")
    private String fileType;

    /** Chunk数量 */
    @TableField("chunk_count")
    private Integer chunkCount;

    /** Embedding模型 */
    @TableField("embedding_model")
    private String embeddingModel;

    /** 状态：0上传中 1解析中 2Embedding中 3成功 4失败 */
    @TableField("status")
    private Integer status;

    /** 创建时间 */
    @TableField("create_time")
    private Date createTime;

    /** 更新时间 */
    @TableField("update_time")
    private Date updateTime;

    /** 文档版本号（同名文档多次上传递增，从1开始） */
    @TableField("version")
    private Integer version;

    /** 过期时间（null=永不过期），用于老版本平滑下线 */
    @TableField("expire_time")
    private Date expireTime;

    /** 是否启用：1-启用 0-禁用 */
    @TableField("is_active")
    private Integer isActive;
}
