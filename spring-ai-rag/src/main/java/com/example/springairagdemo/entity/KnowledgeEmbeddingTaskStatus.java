package com.example.springairagdemo.entity;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * Embedding 任务状态枚举。
 * <p>
 * 与 knowledge_embedding_task.status 列对应：
 * 0待处理 → 1处理中 → 2成功 / 3失败。
 * 标注 {@link EnumValue} 后 MyBatis-Plus 自动按 code 与数据库互转，
 * 前端仍拿到数字，文案统一由 {@link #getText()} 提供。
 */
public enum KnowledgeEmbeddingTaskStatus {

    /** 待处理 */
    PENDING(0, "待处理"),
    /** 处理中 */
    PROCESSING(1, "处理中"),
    /** 成功 */
    SUCCESS(2, "成功"),
    /** 失败 */
    FAILED(3, "失败");

    @EnumValue
    private final int code;
    private final String text;

    KnowledgeEmbeddingTaskStatus(int code, String text) {
        this.code = code;
        this.text = text;
    }

    public int getCode() {
        return code;
    }

    public String getText() {
        return text;
    }

    /**
     * 根据数字状态码解析枚举，未知或空值返回 null。
     */
    public static KnowledgeEmbeddingTaskStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (KnowledgeEmbeddingTaskStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }
}
