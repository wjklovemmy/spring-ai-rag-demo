package com.example.springairagdemo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 聊天会话实体：会话元数据（标题/关联知识库/时间）。
 * 消息历史仍存 Redis（ChatMemory，key = rag:chat:memory:{userId}:{sessionId}），
 * 本表仅支撑会话列表、切换、删除。
 */
@Data
@TableName("chat_session")
public class ChatSessionEntity {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户 ID */
    @TableField("user_id")
    private Long userId;

    /** 会话标识（后端生成 UUID，Redis 记忆 key 后缀） */
    @TableField("session_id")
    private String sessionId;

    /** 会话标题（取首个问题截断） */
    @TableField("title")
    private String title;

    /** 会话关联知识库 ID */
    @TableField("knowledge_base_id")
    private Long knowledgeBaseId;

    /** 创建时间 */
    @TableField("create_time")
    private Date createTime;

    /** 更新时间 */
    @TableField("update_time")
    private Date updateTime;
}
