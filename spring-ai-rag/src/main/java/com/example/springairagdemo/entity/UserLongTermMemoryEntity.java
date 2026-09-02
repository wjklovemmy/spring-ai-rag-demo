package com.example.springairagdemo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 用户长期记忆（Phase 2）：跨会话、跨知识库持久化的个人事实/偏好/经历，按用户隔离。
 *
 * <p>与知识库无关，由 Agent 工具 {@code saveMemory}/{@code searchMemory} 显式写入与查询，
 * 也会在会话结束后由 {@code MemoryExtractionService} 后台自动抽取沉淀。
 * 文本存本表（数据源 + 管理/审计），向量存 Milvus 全局集合 {@code rag_user_memory}
 * （主键 = 本表 id，userId 过滤召回，删除时同步删向量）。
 */
@Data
@TableName("user_long_term_memory")
public class UserLongTermMemoryEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户 ID（逻辑隔离） */
    private Long userId;

    /** 记忆内容（一条 = 一个事实/偏好） */
    private String content;

    /** 类别：fact 事实 / preference 偏好 / interest 兴趣 / goal 目标 / event 经历 */
    private String category;

    /** 重要度 1-10（模型判定） */
    private Integer importance;

    /** 来源会话（conversationId，形如 {userId}:{sessionId}） */
    private String sourceSession;

    /** 向量同步状态：0 待同步/失败 / 1 已同步 */
    private Integer vectorStatus;

    private Date createTime;

    private Date updateTime;

    @TableLogic
    private Integer deleted;

    private Long deletedBy;

    private Date deleteTime;
}
