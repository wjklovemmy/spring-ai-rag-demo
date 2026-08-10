package com.example.springairagdemo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("knowledge_base")
public class KnowledgeBaseEntity {
    /**
     * 知识库ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    /**
     * 知识库名称
     */
    @TableField("name")
    private String name;
    /**
     * 知识库描述
     */
    @TableField("description")
    private String description;
    /**
     * 知识库状态：0-禁用，1-启用
     */
    @TableField("status")
    private Integer status;
    /**
     * 创建人
     */
    @TableField("create_user")
    private String createUser;
    /**
     * 创建时间
     */
    @TableField("create_time")
    private Date createTime;
    /**
     * 更新时间
     */
    @TableField("update_time")
    private Date updateTime;
}
