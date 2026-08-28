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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PDF 文档解析器：读取 PDF 文件内容，按全局配置分块后返回文档列表。
 * <p>
 * 对扫描版 PDF（无文本层）：自动将页面渲染为图片并调用 OCR 识别，
 * 用识别出的文字替换空白页文本；对"文本层 + 图片"混合页额外做 OCR，
 * 把图片内文字与文本层按行去重拼接，保证图表/扫描插图的内容不丢失。
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

    /** metadata 键：父块全文（Parent-Child 检索）。语义/token 切分结果作为父块注入标题后，
     *  再细分为子块；每个子块都携带 parent_text 以便检索命中后反查父块全文，以及摄取阶段重建父块列表 */
    public static final String META_PARENT_TEXT = "parent_text";

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
                            ? mergeTextLayerAndOcr(text, ocrText)  // 混合页：文本层 + 图片内文字
                            : ocrText;                              // 扫描页：OCR 结果整体替换
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

    /**
     * 混合页合并：保留文本层，把 OCR 识别出的图片内文字按行去重后追加在末尾。
     * <p>
     * 整页渲染 OCR 会把正文也识别出来，与文本层重复；通过归一化行匹配剔除重复行，
     * 仅保留图片新增内容。若追加内容过少（如图片只是装饰/logo），回退为纯文本层。
     */
    private String mergeTextLayerAndOcr(String textLayer, String ocrText) {
        List<String> layerLines = new ArrayList<>();
        for (String line : textLayer.split("\\R")) {
            String norm = normalizeOcrLine(line);
            if (!norm.isEmpty()) {
                layerLines.add(norm);
            }
        }
        Set<String> layerSet = new HashSet<>(layerLines);

        StringBuilder sb = new StringBuilder(textLayer);
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
        int added = 0;
        for (String line : ocrText.split("\\R")) {
            String norm = normalizeOcrLine(line);
            if (norm.isEmpty() || layerSet.contains(norm)) {
                continue;
            }
            // 防 OCR 截断/合并导致的重复：双方足够长且互相包含，视为同一内容
            boolean dup = false;
            for (String l : layerLines) {
                if (l.length() >= 4 && norm.length() >= 4
                        && (l.contains(norm) || norm.contains(l))) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                sb.append(line.trim()).append('\n');
                added++;
            }
        }
        if (added == 0) {
            // 图片内无新增文字（装饰图/logo），保持纯文本层，避免噪声污染
            return textLayer;
        }
        log.info("混合页合并：追加 OCR 新增 {} 行", added);
        return sb.toString();
    }

    /** OCR 行归一化：去首尾空白、压缩连续空白，用于文本层与 OCR 结果的重复匹配 */
    private String normalizeOcrLine(String line) {
        return line.trim().replaceAll("[\\s\u00A0]+", " ");
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

            // 3. 标题前缀注入：语义/token 切分结果 = 父块（注入标题链后作为完整上下文单元）
            RagConfigProperties.ParentChild pcCfg = chunk.getParentChild();
            List<Document> parents = new ArrayList<>();
            for (Document pageChunk : pageChunks) {
                parents.add(injectHeading(pageChunk, headings, headingCfg));
            }

            // 4. Parent-Child：父块再细分为子块（子块向量化检索，命中后反查父块全文）
            if (pcCfg.isEnabled()) {
                for (Document parent : parents) {
                    result.addAll(childSplit(parent, pcCfg));
                }
            } else {
                result.addAll(parents);
            }
        }

        log.info("PDF 文档分割为 {} 个文本片段（Parent-Child 已启用: {}）",
                result.size(), chunk.getParentChild().isEnabled());
        return result;
    }

    /**
     * 将父块细分为子块（Parent-Child 检索）。
     * <p>
     * 父块文本已含标题链前缀；子块由 TokenTextSplitter 按 {@code childChunkSize} 二次切分，
     * 每个子块的 metadata 记录 {@link #META_PARENT_TEXT}（父块全文），
     * 供摄取阶段重建父块列表、检索阶段反查父块上下文。
     * <p>
     * 子块切分不启用 minChunkLengthToEmbed 过滤（设为 1），避免父块尾部内容因过短被丢弃。
     */
    private List<Document> childSplit(Document parent, RagConfigProperties.ParentChild cfg) {
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(cfg.getChildChunkSize())
                .withMinChunkSizeChars(cfg.getChildMinChunkSizeChars())
                .withMinChunkLengthToEmbed(1)
                .withMaxNumChunks(cfg.getChildMaxNumChunks())
                .withKeepSeparator(cfg.isChildKeepSeparator())
                .build();
        List<Document> children = splitter.apply(List.of(parent));
        List<Document> result = new ArrayList<>(children.size());
        for (Document child : children) {
            Map<String, Object> meta = new HashMap<>(child.getMetadata());
            meta.put(META_PARENT_TEXT, parent.getText());
            result.add(Document.builder().text(child.getText()).metadata(meta).build());
        }
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
