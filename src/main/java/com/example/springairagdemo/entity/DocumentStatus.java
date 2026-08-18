package com.example.springairagdemo.entity;

/**
 * 文档状态枚举。
 * <p>
 * 与 knowledge_document.status 列对应，覆盖"处理阶段 + 版本生命周期"两个维度：
 * <ul>
 *   <li>0 UPLOADING(上传中) → 1 PARSING(解析中) → 2 EMBEDDING(向量化中) → 3 SUCCESS(成功)</li>
 *   <li>4 FAILED(失败)：处理失败，不参与问答</li>
 *   <li>5 DEPRECATED(已废弃)：被同名更高版本顶替，TTL 内可作兜底接管服务</li>
 *   <li>6 EXPIRED(已过期)：TTL 到期，问答中不可见</li>
 * </ul>
 */
public enum DocumentStatus {

    /** 上传中：文件已提交，任务待处理 */
    UPLOADING(0, "上传中"),
    /** 解析中：PDF 解析/切分阶段 */
    PARSING(1, "解析中"),
    /** 向量化中：embedding 与向量写入阶段 */
    EMBEDDING(2, "向量化中"),
    /** 成功：处理完成，当前生效版本 */
    SUCCESS(3, "成功"),
    /** 失败：处理失败，不参与问答 */
    FAILED(4, "失败"),
    /** 已废弃：被同名更高版本顶替，TTL 内可兜底 */
    DEPRECATED(5, "已废弃"),
    /** 已过期：TTL 到期，问答中不可见 */
    EXPIRED(6, "已过期");

    private final int code;
    private final String text;

    DocumentStatus(int code, String text) {
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
    public static DocumentStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (DocumentStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }
}
