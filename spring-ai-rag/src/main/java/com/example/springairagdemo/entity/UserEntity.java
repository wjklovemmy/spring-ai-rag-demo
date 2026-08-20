package com.example.springairagdemo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 系统用户表
 */
@Data
@TableName("sys_user")
public class UserEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户名，登录用 */
    @TableField("username")
    private String username;

    /** BCrypt 加密后的密码 */
    @TableField("password")
    private String password;

    /** 显示昵称 */
    @TableField("nickname")
    private String nickname;

    /** 邮箱 */
    @TableField("email")
    private String email;

    /** 状态：1-启用 0-禁用 */
    @TableField("status")
    private Integer status;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;
}
