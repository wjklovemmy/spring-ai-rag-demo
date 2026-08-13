package com.example.springairagdemo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 知识库安全审计日志：记录关键操作与越权拒绝
 */
@Data
@TableName("kb_access_log")
public class KbAccessLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作人用户 ID */
    private Long userId;

    /** 操作人用户名 */
    private String username;

    /** 操作类型：CREATE_KB / DELETE_KB / GRANT / REVOKE / UPLOAD_DOC / DELETE_DOC / QUERY / ACCESS_DENIED */
    private String action;

    /** 知识库 ID */
    private Long kbId;

    /** 文档 ID */
    private Long documentId;

    /** 来源 IP */
    private String ip;

    /** 详情 */
    private String detail;

    private Date createTime;
}
