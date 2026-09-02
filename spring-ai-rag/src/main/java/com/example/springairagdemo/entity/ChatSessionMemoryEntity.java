package com.example.springairagdemo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 会话长期记忆实体（Phase 1：跨会话情景记忆）。
 * 每轮问答结束后，把该会话在 Redis 中的「历史对话摘要」同步持久化到本表；
 * 新会话问答开始时按用户检索最近活跃会话摘要注入系统提示，实现跨会话复用。
 */
@Data
@TableName("chat_session_memory")
public class ChatSessionMemoryEntity {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户 ID */
    @TableField("user_id")
    private Long userId;

    /** 来源会话 ID（chat_session.session_id） */
    @TableField("session_id")
    private String sessionId;

    /** 来源会话关联知识库 ID（跨库会话为 NULL） */
    @TableField("knowledge_base_id")
    private Long knowledgeBaseId;

    /** 会话摘要（摘要压缩产物，或最近对话要点） */
    @TableField("summary")
    private String summary;

    /** 创建时间 */
    @TableField("create_time")
    private Date createTime;

    /** 更新时间 */
    @TableField("update_time")
    private Date updateTime;

    /** 逻辑删除标记：0 正常 / 1 已删除（删除会话时置 1，MyBatis-Plus 自动过滤） */
    @TableLogic(value = "0", delval = "1")
    @TableField("deleted")
    private Integer deleted;

    /** 删除人用户 ID（逻辑删除时记录） */
    @TableField("deleted_by")
    private Long deletedBy;

    /** 删除时间 */
    @TableField("delete_time")
    private Date deleteTime;
}
