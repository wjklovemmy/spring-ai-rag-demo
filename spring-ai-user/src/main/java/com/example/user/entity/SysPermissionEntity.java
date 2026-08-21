package com.example.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 权限表（按钮/接口级权限码，经典 RBAC 的权限资源）。
 * 用户 -> 角色（sys_user_role）、角色 -> 权限（sys_role_permission）链式授权。
 */
@Data
@TableName("sys_permission")
public class SysPermissionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 权限编码，如 kb:manage / user:list */
    private String code;

    /** 权限名称 */
    private String name;

    /** 类型：1-菜单 2-按钮/API */
    private Integer type;

    /** 父权限 ID（0 表示根） */
    private Long parentId;

    /** 排序号 */
    private Integer sort;

    /** 状态：1-启用 0-禁用 */
    private Integer status;

    private Date createTime;

    private Date updateTime;
}
