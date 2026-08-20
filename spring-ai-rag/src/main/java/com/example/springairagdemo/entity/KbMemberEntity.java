package com.example.springairagdemo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 知识库成员授权表（数据权限核心）：
 * 用户 × 知识库 × 角色(OWNER/EDITOR/VIEWER)，是防越权的唯一权威
 */
@Data
@TableName("kb_member")
public class KbMemberEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 知识库 ID */
    private Long kbId;

    /** 用户 ID */
    private Long userId;

    /** 角色：OWNER / EDITOR / VIEWER */
    private String role;

    /** 授权人（用户 ID） */
    private Long grantUser;

    private Date createTime;

    private Date updateTime;
}
