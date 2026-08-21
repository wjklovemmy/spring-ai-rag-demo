package com.example.user.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 管理员权限注解（Controller 方法级）：
 * 由 {@code AdminAccessAspect} 在方法执行前校验当前用户是否为 ADMIN。
 * 用于用户管理、角色管理等系统管理接口，防止越权操作。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAdmin {
}
