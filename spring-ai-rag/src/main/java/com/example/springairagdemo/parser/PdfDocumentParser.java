package com.example.springairagdemo.parser;

import com.example.springairagdemo.config.RagConfigProperties;
import com.example.springairagdemo.service.OcrService;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
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
 * PDF 文档解析器：逐页提取 PDF 文本，并按全局配置切分后返回文档列表。
 * <p>
 * 对扫描版 PDF（无文本层）：自动将页面渲染为图片并调用 OCR 识别，
 * 用识别出的文字替换空白页文本；对"文本层 + 图片"混合页额外做 OCR，
 * 把图片内文字与文本层按行去重拼接，保证图表/扫描插图的内容不丢失。
 * <p>
 * 切分统一委托 {@link TextChunkSplitter}（语义切片 + 标题注入 + Parent-Child），与 Word 等其它格式共用同一套策略。
 */
@Component
@Slf4j
public class PdfDocumentParser implements DocumentParser {

    /** metadata 键：父块全文（Parent-Child 检索），实际定义在 {@link TextChunkSplitter}，此处保留转发兼容既有引用 */
    public static final String META_PARENT_TEXT = TextChunkSplitter.META_PARENT_TEXT;

    private final RagConfigProperties config;
    private final OcrService ocrService;
    private final TextChunkSplitter chunkSplitter;

    public PdfDocumentParser(RagConfigProperties config, OcrService ocrService,
                             TextChunkSplitter chunkSplitter) {
        this.config = config;
        this.ocrService = ocrService;
        this.chunkSplitter = chunkSplitter;
    }

    @Override
    public boolean supports(String fileType) {
        return "pdf".equalsIgnoreCase(fileType);
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

        // OCR 兜底：纯图片页（扫描件）整页识别替换；"文本层+图片"混合页识别图片内文字并与文本层拼接
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
     * OCR 兜底：对含图片的页面执行 OCR 识别。
     * <p>
     * 页面只有 XObject 图片时才触发——纯文本页（无图片）不需要，空白页跳过。
     * 扫描页（无文本层）用 OCR 结果整体替换；"文本层 + 图片"混合页（如正文中嵌图表、
     * 流程图、扫描表格）将图片内文字与文本层按行去重拼接，避免图片内容丢失。
     */
    private List<Document> ocrFallback(Path tempFile, List<Document> documents) {
        RagConfigProperties.Ocr ocrConfig = config.getOcr();
        int ocrPageCount = 0;

        try (PDDocument pdfDocument = Loader.loadPDF(tempFile.toFile())) {
            PDFRenderer renderer = new PDFRenderer(pdfDocument);

            for (int i = 0; i < documents.size(); i++) {
                Document doc = documents.get(i);
                String text = doc.getText() == null ? "" : doc.getText();
                // PDF 文本层只覆盖文字，图片内的文字必须靠 OCR；纯文本页/空白页无需 OCR
                if (!containsImage(pdfDocument.getPage(i).getResources())) {
                    continue;
                }
                boolean hasTextLayer = text.trim().length() >= ocrConfig.getMinTextLength();

                log.info("第 {} 页含图片，触发 OCR（文本层是否充足: {}）", i + 1, hasTextLayer);
                BufferedImage image = renderer.renderImageWithDPI(i, ocrConfig.getDpi(), ImageType.RGB);
                String ocrText = ocrService.recognizeImage(toPngBytes(image));
                log.info("第 {} 页 OCR 返回: 是否为空={}, 识别字符数={}", i + 1,
                        ocrText == null || ocrText.isBlank(), ocrText == null ? 0 : ocrText.length());

                if (ocrText != null && !ocrText.isBlank()) {
                    // Spring AI 2.0 Document 不可变，重建替换
                    doc.getMetadata().put("ocr", true);
                    String mergedText = hasTextLayer
                            ? OcrTextMerger.merge(text, ocrText)  // 混合页：文本层 + 图片内文字（按行去重）
                            : ocrText;                            // 扫描页：OCR 结果整体替换
                    Document ocrDocument = Document.builder()
                            .text(mergedText)
                            .metadata(doc.getMetadata())
                            .build();
                    documents.set(i, ocrDocument);
                    ocrPageCount++;
                    log.info("第 {} 页 OCR 处理完成: 文本层 {} 字符 + OCR {} 字符 -> {} 字符",
                            i + 1, text.length(), ocrText.length(), mergedText.length());
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

    /**
     * 页面是否含图片对象（递归嵌套 form XObject）。
     * <p>
     * 仅检测 XObject 图片（覆盖绝大多数 PDF），内联图像（InlineImage）未覆盖；
     * 检测失败时按"无图片"处理（最坏退化为纯文本层路径，不阻断解析）。
     */
    private boolean containsImage(PDResources resources) {
        if (resources == null) {
            return false;
        }
        try {
            Iterable<COSName> names = resources.getXObjectNames();
            if (names == null) {
                return false;
            }
            for (COSName name : names) {
                PDXObject xObject = resources.getXObject(name);
                if (xObject instanceof PDImageXObject) {
                    return true;
                }
                if (xObject instanceof PDFormXObject
                        && containsImage(((PDFormXObject) xObject).getResources())) {
                    return true;
                }
            }
        } catch (IOException e) {
            log.debug("检测页面图片对象失败: {}", e.getMessage());
        }
        return false;
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
        return chunkSplitter.split(documents);
    }
}
