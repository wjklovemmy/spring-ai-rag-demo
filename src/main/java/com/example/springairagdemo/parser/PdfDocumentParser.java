package com.example.springairagdemo.parser;

import com.example.springairagdemo.config.RagConfigProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * PDF 文档解析器：读取 PDF 文件内容，按岗位配置分块后返回文档列表
 */
@Component
@Slf4j
public class PdfDocumentParser implements DocumentParser {

    private final RagConfigProperties config;

    public PdfDocumentParser(RagConfigProperties config) {
        this.config = config;
    }

    @Override
    public List<Document> read(MultipartFile file) throws IOException {
        Path tempFile = Files.createTempFile("pdf-upload-", ".pdf");
        try {
            file.transferTo(tempFile.toFile());
            PagePdfDocumentReader reader = new PagePdfDocumentReader(new FileSystemResource(tempFile));
            List<Document> documents = reader.read();
            log.info("从 PDF 中读取到 {} 个文档页面", documents.size());
            return documents;
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Override
    public List<Document> parse(MultipartFile file, String position) throws IOException {
        List<Document> documents = read(file);
        return split(documents, position);
    }

    @Override
    public List<Document> split(List<Document> documents, String position) {
        RagConfigProperties.PositionConfig posConfig = config.getPositionConfig(position);
        RagConfigProperties.Chunk chunk = (posConfig != null)
                ? posConfig.getDocument().getChunkConfig("pdf")
                : new RagConfigProperties.Chunk();

        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(chunk.getChunkSize())
                .withMinChunkSizeChars(chunk.getMinChunkSizeChars())
                .withMinChunkLengthToEmbed(chunk.getMinChunkLengthToEmbed())
                .withMaxNumChunks(chunk.getMaxNumChunks())
                .withKeepSeparator(chunk.isKeepSeparator())
                .build();

        List<Document> chunks = splitter.apply(documents);
        log.info("PDF 文档分割为 {} 个文本片段 (岗位: {})", chunks.size(), position);
        return chunks;
    }
}
