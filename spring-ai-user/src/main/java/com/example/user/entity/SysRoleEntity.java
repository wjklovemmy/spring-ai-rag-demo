package com.example.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 全局角色表（垂直权限）：内置 ADMIN，可扩展
 */
@Data
@TableName("sys_role")
public class SysRoleEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色编码，如 ADMIN */
    private String code;

    /** 角色名称 */
    private String name;

    /** 备注 */
    private String remark;

    private Date createTime;

    private Date updateTime;
}
