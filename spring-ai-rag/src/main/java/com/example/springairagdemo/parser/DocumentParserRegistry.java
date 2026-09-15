package com.example.springairagdemo.parser;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档解析器注册表：按文件扩展名路由到对应格式的 {@link DocumentParser} 实现。
 * <p>
 * 由 Spring 自动注入全部实现，新增格式只需新增一个 {@code @Component} 解析器
 * 并让 {@link DocumentParser#supports(String)} 返回 true，无需改动摄取流程。
 */
@Component
public class DocumentParserRegistry {

    private final List<DocumentParser> parsers;

    public DocumentParserRegistry(List<DocumentParser> parsers) {
        this.parsers = parsers;
    }

    /**
     * 按文件扩展名选择解析器
     *
     * @param fileType 文件扩展名（如 pdf / docx / doc，大小写不敏感）
     * @return 匹配的解析器
     * @throws IllegalArgumentException 无解析器支持该格式（任务直接失败，避免静默产出空索引）
     */
    public DocumentParser resolve(String fileType) {
        if (fileType != null) {
            String type = fileType.trim().toLowerCase();
            for (DocumentParser parser : parsers) {
                if (parser.supports(type)) {
                    return parser;
                }
            }
        }
        throw new IllegalArgumentException("不支持的文档格式: " + fileType);
    }
}
