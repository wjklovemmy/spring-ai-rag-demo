package com.example.springairagdemo.parser;

import com.example.springairagdemo.config.RagConfigProperties;
import com.example.springairagdemo.service.OcrService;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * PDF 文档解析器：读取 PDF 文件内容，按岗位配置分块后返回文档列表。
 * <p>
 * 对扫描版 PDF（无文本层）：自动将页面渲染为图片并调用 OCR 识别，
 * 用识别出的文字替换空白页文本，保证扫描件也能进入 RAG 链路。
 */
@Component
@Slf4j
public class PdfDocumentParser implements DocumentParser {

    private final RagConfigProperties config;
    private final OcrService ocrService;

    public PdfDocumentParser(RagConfigProperties config, OcrService ocrService) {
        this.config = config;
        this.ocrService = ocrService;
    }

    @Override
    public List<Document> read(MultipartFile file) throws IOException {
        Path tempFile = Files.createTempFile("pdf-upload-", ".pdf");
        try {
            file.transferTo(tempFile.toFile());
            PagePdfDocumentReader reader = new PagePdfDocumentReader(new FileSystemResource(tempFile));
            List<Document> documents = reader.read();
            log.info("从 PDF 中读取到 {} 个文档页面", documents.size());

            // OCR 兜底：扫描版 PDF 页面无文本层时渲染图片识别文字
            if (ocrService.isEnabled() && !documents.isEmpty()) {
                documents = ocrFallback(tempFile, documents);
            }
            return documents;
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * 对文本层缺失或过短的页面执行 OCR 识别，替换为识别出的文字
     */
    private List<Document> ocrFallback(Path tempFile, List<Document> documents) {
        RagConfigProperties.Ocr ocrConfig = config.getOcr();
        int ocrPageCount = 0;

        try (PDDocument pdfDocument = Loader.loadPDF(tempFile.toFile())) {
            PDFRenderer renderer = new PDFRenderer(pdfDocument);

            for (int i = 0; i < documents.size(); i++) {
                Document doc = documents.get(i);
                String text = doc.getText() == null ? "" : doc.getText();
                // 有足够文本层的页面无需 OCR
                if (text.trim().length() >= ocrConfig.getMinTextLength()) {
                    continue;
                }

                log.info("第 {} 页无有效文本层，触发 OCR 识别", i + 1);
                BufferedImage image = renderer.renderImageWithDPI(i, ocrConfig.getDpi(), ImageType.RGB);
                String ocrText = ocrService.recognizeImage(toPngBytes(image));

                if (ocrText != null && !ocrText.isBlank()) {
                    // Spring AI 2.0 Document 不可变，重建替换
                    doc.getMetadata().put("ocr", true);
                    Document ocrDocument = Document.builder()
                            .text(ocrText)
                            .metadata(doc.getMetadata())
                            .build();
                    documents.set(i, ocrDocument);
                    ocrPageCount++;
                    log.info("第 {} 页 OCR 识别完成，识别字符数: {}", i + 1, ocrText.length());
                }
            }
            if (ocrPageCount > 0) {
                log.info("本次 PDF 共 {} 页执行了 OCR 识别", ocrPageCount);
            }
        } catch (Exception e) {
            if (ocrConfig.isFailOnError()) {
                throw new RuntimeException("PDF OCR 处理失败", e);
            }
            log.error("PDF OCR 处理失败，保留原始文本层: {}", e.getMessage());
        }
        return documents;
    }

    private byte[] toPngBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
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
