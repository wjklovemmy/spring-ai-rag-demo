package com.example.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 角色-权限关联表
 */
@Data
@TableName("sys_role_permission")
public class SysRolePermissionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色 ID */
    private Long roleId;

    /** 权限 ID */
    private Long permissionId;

    private Date createTime;
}
