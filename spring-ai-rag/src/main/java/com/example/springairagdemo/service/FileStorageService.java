package com.example.springairagdemo.service;

import java.io.InputStream;

/**
 * 文件存储服务抽象（MinIO 对象存储实现）。
 */
public interface FileStorageService {

    /**
     * 存储文件
     *
     * @param inputStream 文件流
     * @param objectName  对象名称/路径（如 kb_1/123.pdf）
     * @param contentType MIME 类型
     */
    void store(InputStream inputStream, String objectName, String contentType) throws Exception;

    /**
     * 获取文件输入流（下载用）
     */
    InputStream getInputStream(String objectName) throws Exception;

    /**
     * 检查文件是否存在
     */
    boolean exists(String objectName);

    /**
     * 删除文件（回滚用）
     */
    void delete(String objectName) throws Exception;
}
