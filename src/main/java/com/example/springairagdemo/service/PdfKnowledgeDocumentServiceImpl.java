package com.example.springairagdemo.service;

import com.example.springairagdemo.parser.DocumentParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * PDF 知识文档服务实现：继承抽象类，提供 PDF 特有的解析和切分逻辑
 */
@Service
@Slf4j
public class PdfKnowledgeDocumentServiceImpl extends KnowledgeDocumentService {

    private final DocumentParser documentParser;

    public PdfKnowledgeDocumentServiceImpl(DocumentParser documentParser) {
        this.documentParser = documentParser;
    }

    @Override
    protected List<Document> parseDocument(MultipartFile file) throws IOException {
        return documentParser.read(file);
    }

    @Override
    protected List<Document> parseDocument(InputStream inputStream) throws IOException {
        return documentParser.read(inputStream);
    }

    @Override
    protected List<Document> splitDocument(List<Document> documents) {
        return documentParser.split(documents);
    }
}
