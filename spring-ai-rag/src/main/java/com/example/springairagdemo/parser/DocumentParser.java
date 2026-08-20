package com.example.springairagdemo.parser;

import org.springframework.ai.document.Document;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 文档解析器接口：包含读取原始内容和切分两个独立步骤
 */
public interface DocumentParser {

    /**
     * 读取文档文件原始内容（不含切分）
     *
     * @param file 上传的文档文件
     * @return 原始文档列表（如 PDF 按页）
     */
    List<Document> read(MultipartFile file) throws IOException;

    /**
     * 从输入流读取文档内容（不含切分），用于异步任务从存储后端读取原始文件
     *
     * @param inputStream 文档输入流
     * @return 原始文档列表（如 PDF 按页）
     */
    List<Document> read(InputStream inputStream) throws IOException;

    /**
     * 便捷方法：读取文档内容并按全局配置分块
     *
     * @param file 上传的文档文件
     * @return 分块后的文档列表
     */
    List<Document> parse(MultipartFile file) throws IOException;

    /**
     * 将文档列表按全局配置切分为适合向量检索的片段
     *
     * @param documents 原始文档列表
     * @return 分块后的文档列表
     */
    List<Document> split(List<Document> documents);
}
