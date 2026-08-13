package com.example.springairagdemo.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 知识库访问控制注解（Controller 方法级）：
 * 由 {@code KbAccessAspect} 在方法执行前校验当前用户对指定知识库的角色。
 *
 * <p>用法示例：</p>
 * <pre>
 *   // 参数为 @PathVariable("id") Long id
 *   @RequireKbRole(value = KbRole.OWNER, kbParam = "id")
 *   public ... deleteKnowledgeBase(@PathVariable Long id)
 *
 *   // 参数为 @RequestParam Long knowledgeBaseId
 *   @RequireKbRole(value = KbRole.EDITOR, kbParam = "knowledgeBaseId")
 *   public ... upload(@RequestParam Long knowledgeBaseId, ...)
 *
 *   // kbId 位于 JSON body
 *   @RequireKbRole(value = KbRole.VIEWER, kbParam = "knowledgeBaseId")
 *   public ... chat(@RequestBody Map&lt;String,Object&gt; body)
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireKbRole {

    /** 所需最低知识库角色 */
    KbRole value() default KbRole.VIEWER;

    /** 知识库 ID 来源：方法参数名（@PathVariable/@RequestParam）或 JSON body 的 key */
    String kbParam() default "knowledgeBaseId";
}
