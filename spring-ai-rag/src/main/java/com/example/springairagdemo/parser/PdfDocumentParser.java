package com.example.springairagdemo.parser;

import com.example.springairagdemo.config.RagConfigProperties;
import com.example.springairagdemo.service.OcrService;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PDF 文档解析器：读取 PDF 文件内容，按全局配置分块后返回文档列表。
 * <p>
 * 对扫描版 PDF（无文本层）：自动将页面渲染为图片并调用 OCR 识别，
 * 用识别出的文字替换空白页文本，保证扫描件也能进入 RAG 链路。
 * <p>
 * 切分策略（自研，Spring AI 2.0 无 SemanticTextSplitter）：
 * <ol>
 *   <li>语义切片：段落批量 embedding 聚类，按相邻相似度找语义断点；</li>
 *   <li>标题注入：识别标题行构建标题链，将所属标题前缀注入 chunk 文本并写入 metadata.heading。</li>
 * </ol>
 */
@Component
@Slf4j
public class PdfDocumentParser implements DocumentParser {

    private final RagConfigProperties config;
    private final OcrService ocrService;
    private final SemanticSplitter semanticSplitter;
    private final HeadingExtractor headingExtractor;

    public PdfDocumentParser(RagConfigProperties config, OcrService ocrService,
                             SemanticSplitter semanticSplitter, HeadingExtractor headingExtractor) {
        this.config = config;
        this.ocrService = ocrService;
        this.semanticSplitter = semanticSplitter;
        this.headingExtractor = headingExtractor;
    }

    @Override
    public List<Document> read(MultipartFile file) throws IOException {
        Path tempFile = Files.createTempFile("pdf-upload-", ".pdf");
        try {
            file.transferTo(tempFile.toFile());
            return readFromTemp(tempFile);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Override
    public List<Document> read(InputStream inputStream) throws IOException {
        Path tempFile = Files.createTempFile("pdf-async-", ".pdf");
        try {
            Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            return readFromTemp(tempFile);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * 统一读取临时 PDF 文件：逐页提取文本 + OCR 兜底
     */
    private List<Document> readFromTemp(Path tempFile) throws IOException {
        List<Document> documents = readAllPages(tempFile);
        log.info("从 PDF 中读取到 {} 个文档页面", documents.size());

        // OCR 兜底：扫描版 PDF 页面无文本层时渲染图片识别文字（原地替换页内容）
        if (ocrService.isEnabled() && !documents.isEmpty()) {
            ocrFallback(tempFile, documents);
        }
        return documents;
    }

    /**
     * 逐页提取 PDF 文本，空文本页面也保留（供 OCR 兜底）。
     * <p>
     * 不直接使用 Spring AI 的 {@code PagePdfDocumentReader}：它内部用 {@code StringUtils.hasText}
     * 过滤无文本页面，纯图片 PDF（扫描件）会被过滤成空列表，导致后续 OCR 兜底无从触发。
     */
    private List<Document> readAllPages(Path tempFile) throws IOException {
        List<Document> documents = new ArrayList<>();
        try (PDDocument pdfDocument = Loader.loadPDF(tempFile.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            int totalPages = pdfDocument.getNumberOfPages();
            for (int pageNo = 1; pageNo <= totalPages; pageNo++) {
                stripper.setStartPage(pageNo);
                stripper.setEndPage(pageNo);
                String text = stripper.getText(pdfDocument);

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("page_number", pageNo);
                metadata.put("end_page_number", pageNo);
                metadata.put("file_name", tempFile.getFileName().toString());
                documents.add(Document.builder().text(text == null ? "" : text).metadata(metadata).build());
            }
        }
        return documents;
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
                log.info("第 {} 页 OCR 返回: 是否为空={}, 识别字符数={}", i + 1,
                        ocrText == null || ocrText.isBlank(), ocrText == null ? 0 : ocrText.length());

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
    public List<Document> parse(MultipartFile file) throws IOException {
        List<Document> documents = read(file);
        return split(documents);
    }

    @Override
    public List<Document> split(List<Document> documents) {
        RagConfigProperties.Chunk chunk = config.getDocument().getChunk();
        RagConfigProperties.Heading headingCfg = chunk.getHeading();
        RagConfigProperties.Semantic semanticCfg = chunk.getSemantic();

        List<Document> result = new ArrayList<>();
        for (Document pageDoc : documents) {
            // 1. 提取页面标题链（按字符偏移定位）
            List<HeadingExtractor.HeadingLine> headings = headingCfg.isEnabled()
                    ? headingExtractor.extract(pageDoc.getText(), headingCfg)
                    : List.of();

            // 2. 语义切片（失败降级 token 切分）
            List<Document> pageChunks;
            if (semanticCfg.isEnabled()) {
                try {
                    pageChunks = semanticSplitter.split(pageDoc, semanticCfg,
                            chunk.getChunkSize(), chunk.getMinChunkSizeChars());
                } catch (Exception e) {
                    if (semanticCfg.isFallbackOnError()) {
                        log.warn("语义切片失败，降级为 token 切分: {}", e.getMessage());
                        pageChunks = tokenSplit(pageDoc, chunk);
                    } else {
                        throw new RuntimeException("语义切片失败", e);
                    }
                }
            } else {
                pageChunks = tokenSplit(pageDoc, chunk);
            }

            // 3. 标题前缀注入 + metadata
            for (Document pageChunk : pageChunks) {
                result.add(injectHeading(pageChunk, headings, headingCfg));
            }
        }

        log.info("PDF 文档分割为 {} 个文本片段", result.size());
        return result;
    }

    /**
     * 整页 token 切分（语义切片关闭或降级时使用）
     */
    private List<Document> tokenSplit(Document pageDoc, RagConfigProperties.Chunk chunk) {
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(chunk.getChunkSize())
                .withMinChunkSizeChars(chunk.getMinChunkSizeChars())
                .withMinChunkLengthToEmbed(chunk.getMinChunkLengthToEmbed())
                .withMaxNumChunks(chunk.getMaxNumChunks())
                .withKeepSeparator(chunk.isKeepSeparator())
                .build();
        return splitter.apply(List.of(pageDoc));
    }

    /**
     * 按 chunk 在页面文本中的起始偏移定位所属标题，将标题链前缀注入文本并写入 metadata.heading。
     * 无偏移（token 切分产物）时使用页面首个标题链。
     */
    private Document injectHeading(Document chunk, List<HeadingExtractor.HeadingLine> headings,
                                   RagConfigProperties.Heading cfg) {
        if (!cfg.isEnabled() || headings.isEmpty()) {
            return chunk;
        }

        Object startObj = chunk.getMetadata().get(SemanticSplitter.META_CHUNK_START);
        int start = startObj instanceof Number n ? n.intValue() : -1;

        HeadingExtractor.HeadingLine target = null;
        if (start >= 0) {
            for (HeadingExtractor.HeadingLine h : headings) {
                if (h.offset() <= start) {
                    target = h;
                } else {
                    break;
                }
            }
        }
        if (target == null) {
            target = headings.get(0);
        }
        if (target.chain() == null || target.chain().isBlank()) {
            return chunk;
        }

        String prefix = cfg.getPrefixTemplate().replace("{heading}", target.chain());
        Map<String, Object> meta = new HashMap<>(chunk.getMetadata());
        meta.put("heading", target.chain());
        return Document.builder()
                .text(prefix + chunk.getText())
                .metadata(meta)
                .build();
    }
}
