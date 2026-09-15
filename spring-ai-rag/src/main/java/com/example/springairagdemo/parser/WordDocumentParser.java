package com.example.springairagdemo.parser;

import com.example.springairagdemo.config.RagConfigProperties;
import com.example.springairagdemo.service.OcrService;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.hwpf.usermodel.Picture;
import org.apache.poi.xwpf.usermodel.BodyElementType;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRow;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Word 文档解析器：支持 .docx（OOXML，poi-ooxml）与 .doc（OLE2 二进制，poi-scratchpad）。
 * <p>
 * 内容提取覆盖四类信息，尽量不丢内容：
 * <ol>
 *   <li><b>段落与标题</b>：标题段落（Word 标题 1-9 样式，中英文样式名均可）转 Markdown 前缀，
 *       供 {@link HeadingExtractor} 构建标题链；</li>
 *   <li><b>表格</b>：按行转 {@code | 单元格 | 单元格 |}，保留行列对应关系（表格类问题依赖）；
 *       首行为表头时补 Markdown 分隔行，嵌套表格递归展开；</li>
 *   <li><b>图片</b>：内嵌图片用 OCR 识别图片内文字（架构图/流程图/截图/扫描表格），
 *       识别结果按原位置插回正文（图片所在段落之后），未定位图片（页眉页脚、浮动图片）追加到文末；
 *       同图只识别一次（内容指纹去重），图标/装饰图按尺寸过滤，单篇张数有上限；</li>
 *   <li><b>扫描件</b>：整篇由图片组成（文本层几乎为空）时判定为扫描件，逐图 OCR，一图一个单元，
 *       与 PDF 扫描件走同一条 RAG 链路。</li>
 * </ol>
 * <p>
 * Word 没有物理分页概念，因此按「逻辑单元」组织内容，与 PDF 的「页」对应：
 * 遇到标题段落或累计字符超过 {@link #MAX_CHARS_PER_UNIT} 即切出一个单元，
 * 单元的 metadata 记录 {@code page_number}（逻辑分段序号），下游切分/落库/引用展示可直接复用 PDF 链路。
 * <p>
 * 切分统一委托 {@link TextChunkSplitter}，与 PDF 共用语义切片 + 标题注入 + Parent-Child 策略。
 */
@Component
@Slf4j
public class WordDocumentParser implements DocumentParser {

    /** 支持的扩展名 */
    private static final Set<String> SUPPORTED_TYPES = Set.of("docx", "doc");

    /** 单个逻辑单元的字符上限：Word 无分页，按段落累积到该上限切一个单元，避免单元过长影响语义聚类与标题定位 */
    private static final int MAX_CHARS_PER_UNIT = 4000;

    /** Word 标题样式名：英文 "Heading 1"，中文 "标题 1" */
    private static final Pattern HEADING_STYLE = Pattern.compile(
            "^(?:heading|标题)\\s*([1-9])$", Pattern.CASE_INSENSITIVE);

    /** Word 控制字符（\u0007 单元格结束符单独转为制表符保留结构）：\u000b 软换行、\u000c 分页符、\u0002 脚注引用等 */
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\u0000-\\u0006\\u0008\\u000b\\u000c\\u000e-\\u001f]");

    /** OCR 可直接识别的图片格式：直接用原始字节送识别，避免大图转 PNG 体积膨胀（阿里云 OCR 有请求体上限） */
    private static final Set<String> OCR_RAW_FORMATS = Set.of("jpg", "jpeg", "png", "bmp");

    /** 送 OCR 前图片长边上限（像素）：超过则等比缩小，避免请求体过大与识别变慢 */
    private static final int OCR_MAX_IMAGE_SIDE = 2400;

    private final RagConfigProperties config;
    private final OcrService ocrService;
    private final TextChunkSplitter chunkSplitter;

    public WordDocumentParser(RagConfigProperties config, OcrService ocrService,
                              TextChunkSplitter chunkSplitter) {
        this.config = config;
        this.ocrService = ocrService;
        this.chunkSplitter = chunkSplitter;
    }

    /**
     * 文档内图片
     *
     * @param hash             内容指纹（同图只识别一次：logo 等重复图片不重复消耗 OCR）
     * @param data             原始图片字节
     * @param extension        原始扩展名（小写、无点），用于判断是否可直接送 OCR
     * @param insertBlockIndex OCR 文字插入位置：插到第 N 个文本块之前；
     *                         {@link #APPENDIX_IMAGE_INDEX} 表示未定位图片（页眉页脚/浮动图片/未引用图片），追加到文末
     */
    private record WordImage(String hash, byte[] data, String extension, int insertBlockIndex) {
    }

    /** 未定位图片的插入位置标记：追加到文档末尾 */
    private static final int APPENDIX_IMAGE_INDEX = -1;

    @Override
    public boolean supports(String fileType) {
        return fileType != null && SUPPORTED_TYPES.contains(fileType.toLowerCase());
    }

    @Override
    public List<Document> read(MultipartFile file) throws IOException {
        try (InputStream inputStream = file.getInputStream()) {
            return read(inputStream);
        }
    }

    @Override
    public List<Document> read(InputStream inputStream) throws IOException {
        // 按文件魔数区分格式：.doc（OLE2 复合文档）以 D0 CF 11 E0 开头，.docx（OOXML）为 ZIP（PK...）。
        // 不信任扩展名——扩展名与实际内容不符时（如 .doc 实为 docx）仍能正确解析；
        // PushbackInputStream 回退探测字节，保证后续解析读到完整数据。
        try (PushbackInputStream magicStream = new PushbackInputStream(inputStream, 8)) {
            byte[] head = magicStream.readNBytes(8);
            magicStream.unread(head);
            boolean ole2 = head.length >= 8
                    && (head[0] & 0xFF) == 0xD0 && (head[1] & 0xFF) == 0xCF
                    && (head[2] & 0xFF) == 0x11 && (head[3] & 0xFF) == 0xE0;
            List<Document> documents = ole2 ? readDoc(magicStream) : readDocx(magicStream);
            log.info("从 Word({}) 文档中读取到 {} 个文档片段", ole2 ? "doc" : "docx", documents.size());
            return documents;
        }
    }

    // ===================== .docx（OOXML） =====================

    private List<Document> readDocx(InputStream inputStream) throws IOException {
        try (XWPFDocument docx = new XWPFDocument(inputStream)) {
            List<String> blocks = new ArrayList<>();
            List<WordImage> images = new ArrayList<>();
            Set<String> imageHashes = new HashSet<>();

            for (IBodyElement element : docx.getBodyElements()) {
                if (element.getElementType() == BodyElementType.PARAGRAPH) {
                    XWPFParagraph paragraph = (XWPFParagraph) element;
                    String text = cleanText(paragraph.getText());
                    if (!text.isEmpty()) {
                        Integer level = headingLevel(paragraph, docx);
                        // 标题段落转 Markdown 前缀：HeadingExtractor 据此构建标题链（避免依赖样式名跨语言差异）
                        blocks.add(level == null ? text : "#".repeat(Math.min(level, 6)) + " " + text);
                    }
                    // 段落内嵌图片：OCR 文字紧随该段落（也覆盖"只有图片、没有文字"的段落）
                    collectParagraphImages(paragraph, blocks.size(), images, imageHashes);
                } else if (element.getElementType() == BodyElementType.TABLE) {
                    XWPFTable table = (XWPFTable) element;
                    String tableText = tableToText(table);
                    if (!tableText.isEmpty()) {
                        blocks.add(tableText);
                    }
                    collectTableImages(table, blocks.size(), images, imageHashes);
                }
            }

            // 正文未引用的图片（页眉/页脚/浮动图片/未使用图片）：识别后追加到文末，保证内容不丢
            try {
                for (XWPFPictureData pictureData : docx.getAllPictures()) {
                    addImage(pictureData.getData(), pictureData.suggestFileExtension(),
                            APPENDIX_IMAGE_INDEX, images, imageHashes);
                }
            } catch (Exception e) {
                // 图片池读取失败不影响正文提取（异常文件/不支持的图形对象）
                log.warn("读取 .docx 图片池失败，跳过图片 OCR: {}", e.getMessage());
            }
            return assemble(blocks, images, "docx");
        } catch (IOException e) {
            throw new IOException("Word(.docx) 解析失败: " + e.getMessage(), e);
        } catch (Exception e) {
            // POI 对损坏/非法 OOXML 会抛 POIXMLException 等运行时异常，统一转为可读的解析错误
            throw new IOException("Word(.docx) 解析失败（文件可能已损坏或非 Word 格式）: " + e.getMessage(), e);
        }
    }

    /**
     * 判断段落是否为标题并返回层级（1-9），非标题返回 null。
     * <p>
     * 优先用样式名（"Heading 1"/"标题 1"）判断，样式表读取失败时退化为样式 ID
     * （OOXML 内置标题样式 ID 约定为 "1".."9"）。
     */
    private Integer headingLevel(XWPFParagraph paragraph, XWPFDocument docx) {
        String styleId = paragraph.getStyleID();
        if (styleId == null || styleId.isBlank()) {
            return null;
        }
        String styleName = styleId;
        try {
            XWPFStyles styles = docx.getStyles();
            XWPFStyle style = styles == null ? null : styles.getStyle(styleId);
            if (style != null && style.getName() != null && !style.getName().isBlank()) {
                styleName = style.getName();
            }
        } catch (Exception e) {
            log.debug("读取 Word 标题样式失败，退化为样式 ID 判断: {}", e.getMessage());
        }

        Matcher matcher = HEADING_STYLE.matcher(styleName.strip());
        if (matcher.matches()) {
            return Integer.parseInt(matcher.group(1));
        }
        if (styleId.matches("[1-9]")) {
            return Integer.parseInt(styleId);
        }
        return null;
    }

    /**
     * 表格转文本：每行输出 {@code | 单元格 | 单元格 |}，保留行列对应关系（表格类问题依赖该结构）。
     * <p>
     * 首行带 OOXML 表头标记（tblHeader）时补一行 Markdown 分隔符，帮助模型识别表头语义；
     * 嵌套表格由 {@link #cellToText} 递归展开（换行折叠为空格，避免破坏"一行一记录"的结构）。
     */
    private String tableToText(XWPFTable table) {
        StringBuilder sb = new StringBuilder();
        List<XWPFTableRow> rows = table.getRows();
        for (int i = 0; i < rows.size(); i++) {
            XWPFTableRow row = rows.get(i);
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(cellToText(cell));
            }
            if (cells.stream().allMatch(String::isEmpty)) {
                continue;
            }
            sb.append("| ").append(String.join(" | ", cells)).append(" |\n");
            if (i == 0 && isHeaderRow(row)) {
                sb.append('|').append(" --- |".repeat(cells.size())).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    /** 单元格文本：段落用空格连接，嵌套表格递归展开后追加 */
    private String cellToText(XWPFTableCell cell) {
        StringBuilder sb = new StringBuilder();
        for (XWPFParagraph paragraph : cell.getParagraphs()) {
            String text = cleanText(paragraph.getText());
            if (!text.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(text);
            }
        }
        for (XWPFTable nested : cell.getTables()) {
            String nestedText = tableToText(nested);
            if (!nestedText.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(nestedText);
            }
        }
        return sb.toString().replace('\n', ' ');
    }

    /** 首行是否为表头行（OOXML trPr/tblHeader 标记，跨页重复的表头行） */
    private boolean isHeaderRow(XWPFTableRow row) {
        try {
            CTRow ctRow = row.getCtRow();
            if (ctRow == null || !ctRow.isSetTrPr()) {
                return false;
            }
            // 不用 schema 生成的访问器（不同 POI / ooxml-schemas 版本方法名不一致），
            // 直接读 trPr 的 DOM 子节点，跨版本稳定
            Node trPrNode = ctRow.getTrPr().getDomNode();
            NodeList children = trPrNode.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if ("tblHeader".equals(children.item(i).getLocalName())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.debug("读取表格表头标记失败: {}", e.getMessage());
            return false;
        }
    }

    /** 收集表格内图片（含嵌套表格），递归遍历 */
    private void collectTableImages(XWPFTable table, int insertIndex,
                                    List<WordImage> images, Set<String> imageHashes) {
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                for (XWPFParagraph paragraph : cell.getParagraphs()) {
                    collectParagraphImages(paragraph, insertIndex, images, imageHashes);
                }
                for (XWPFTable nested : cell.getTables()) {
                    collectTableImages(nested, insertIndex, images, imageHashes);
                }
            }
        }
    }

    /** 收集段落内图片（按 run 顺序，保持文档内出现次序） */
    private void collectParagraphImages(XWPFParagraph paragraph, int insertIndex,
                                        List<WordImage> images, Set<String> imageHashes) {
        try {
            for (XWPFRun run : paragraph.getRuns()) {
                for (XWPFPicture picture : run.getEmbeddedPictures()) {
                    XWPFPictureData data = picture.getPictureData();
                    if (data != null) {
                        addImage(data.getData(), data.suggestFileExtension(),
                                insertIndex, images, imageHashes);
                    }
                }
            }
        } catch (Exception e) {
            // 单个段落图片读取失败不影响整篇提取（可能为异常 XML/不支持的图形对象）
            log.debug("读取段落内嵌图片失败，已跳过: {}", e.getMessage());
        }
    }

    // ===================== .doc（OLE2 二进制） =====================

    private List<Document> readDoc(InputStream inputStream) throws IOException {
        try (HWPFDocument doc = new HWPFDocument(inputStream);
             WordExtractor extractor = new WordExtractor(doc)) {
            List<String> blocks = new ArrayList<>();
            for (String paragraph : extractor.getParagraphText()) {
                String text = cleanText(paragraph);
                if (!text.isEmpty()) {
                    blocks.add(text);
                }
            }

            // .doc 的 PicturesTable 是全文档图片池，不暴露图片与段落的对应关系，
            // 因此统一识别后追加到文末（扫描件场景下仍是"一图一单元"，见 assemble）
            List<WordImage> images = new ArrayList<>();
            Set<String> imageHashes = new HashSet<>();
            try {
                for (Picture picture : doc.getPicturesTable().getAllPictures()) {
                    addImage(picture.getContent(), picture.suggestFileExtension(),
                            APPENDIX_IMAGE_INDEX, images, imageHashes);
                }
            } catch (Exception e) {
                // 图片池解析对异常 .doc 较敏感，失败时退回纯文本提取（不整篇失败）
                log.warn("读取 .doc 图片池失败，跳过图片 OCR: {}", e.getMessage());
            }
            return assemble(blocks, images, "doc");
        } catch (IOException e) {
            throw new IOException("Word(.doc) 解析失败: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IOException("Word(.doc) 解析失败（文件可能已损坏或非 Word 格式）: " + e.getMessage(), e);
        }
    }

    // ===================== 图片 OCR =====================

    /** 记录图片（内容指纹去重：同一张图在文档中重复出现时只处理一次） */
    private void addImage(byte[] data, String extension, int insertIndex,
                          List<WordImage> images, Set<String> imageHashes) {
        if (data == null || data.length == 0) {
            return;
        }
        String hash = imageHash(data);
        if (!imageHashes.add(hash)) {
            return;
        }
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        images.add(new WordImage(hash, data, ext, insertIndex));
    }

    private String imageHash(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (Exception e) {
            // 理论上不会发生（MD5 为 JDK 必备算法），退化为"长度 + 内容散列"
            return data.length + "-" + java.util.Arrays.hashCode(data);
        }
    }

    /**
     * 组装文档单元：把图片 OCR 结果并入文本层。
     * <p>
     * 两种形态：
     * <ul>
     *   <li><b>扫描件</b>（文本层字符数低于 {@code rag.ocr.min-text-length} 且存在图片）：
     *       图片即"页"，逐图识别、一图一个单元；少量文本层（页眉标题等）保留为独立单元；</li>
     *   <li><b>混合文档</b>：行内图片识别文字按原位置插回正文，未定位图片追加到文末。</li>
     * </ul>
     */
    private List<Document> assemble(List<String> blocks, List<WordImage> images, String format) {
        RagConfigProperties.Ocr ocr = config.getOcr();
        if (!ocrService.isEnabled() || images.isEmpty()) {
            return toDocuments(blocks, 1);
        }

        String plainText = String.join("\n", blocks);
        if (plainText.strip().length() < ocr.getMinTextLength()) {
            List<Document> textUnits = plainText.isBlank() ? List.of() : toDocuments(blocks, 1);
            List<Document> imageUnits = ocrAsUnits(images, ocr, format, textUnits.size() + 1);
            if (!imageUnits.isEmpty()) {
                log.info("Word({}) 文本层缺失（{} 字符），按扫描件逐图 OCR：{} 个图片单元 + {} 个文本单元",
                        format, plainText.strip().length(), imageUnits.size(), textUnits.size());
                List<Document> result = new ArrayList<>(textUnits.size() + imageUnits.size());
                result.addAll(textUnits);
                result.addAll(imageUnits);
                return result;
            }
            return toDocuments(blocks, 1);
        }

        insertImageText(blocks, images, ocr, format);
        return toDocuments(blocks, 1);
    }

    /** 扫描件形态：逐图 OCR，一图一个单元（与 PDF 逐页 OCR 对齐） */
    private List<Document> ocrAsUnits(List<WordImage> images, RagConfigProperties.Ocr ocr,
                                      String format, int startUnitNo) {
        List<Document> units = new ArrayList<>();
        int attempts = 0;
        int pageNo = startUnitNo;
        for (WordImage image : images) {
            if (attempts >= ocr.getWordMaxImages()) {
                log.warn("Word({}) 图片数超过 OCR 上限 {}，剩余 {} 张跳过（可调大 rag.ocr.word-max-images）",
                        format, ocr.getWordMaxImages(), images.size() - attempts);
                break;
            }
            attempts++;
            String text = recognize(image);
            if (text == null) {
                continue;
            }
            // Spring AI 2.0 Document 不可变，metadata 由本类构建，可直接标记 OCR 来源
            Document unit = buildUnit(List.of(text), pageNo++);
            unit.getMetadata().put("ocr", true);
            units.add(unit);
        }
        if (!units.isEmpty()) {
            log.info("Word({}) 扫描件 OCR 完成：识别 {} 张图片（尝试 {} 张）", format, units.size(), attempts);
        }
        return units;
    }

    /** 混合文档形态：图片 OCR 文字按原位置插回正文（未定位图片追加文末） */
    private void insertImageText(List<String> blocks, List<WordImage> images,
                                 RagConfigProperties.Ocr ocr, String format) {
        Map<Integer, List<String>> insertions = new HashMap<>();
        List<String> appendix = new ArrayList<>();
        int attempts = 0;
        int recognized = 0;

        for (WordImage image : images) {
            if (attempts >= ocr.getWordMaxImages()) {
                log.warn("Word({}) 内嵌图片超过 OCR 上限 {}，剩余 {} 张跳过（可调大 rag.ocr.word-max-images）",
                        format, ocr.getWordMaxImages(), images.size() - attempts);
                break;
            }
            attempts++;
            String text = recognize(image);
            if (text == null) {
                continue;
            }
            recognized++;
            // 插入到第 N 个文本块之前；APPENDIX 为未定位图片，统一追加文末
            if (image.insertBlockIndex() == APPENDIX_IMAGE_INDEX) {
                appendix.add(text);
            } else {
                insertions.computeIfAbsent(image.insertBlockIndex(), k -> new ArrayList<>()).add(text);
            }
        }
        if (recognized == 0) {
            return;
        }

        List<String> merged = new ArrayList<>(blocks.size() + recognized + 2);
        for (int i = 0; i < blocks.size(); i++) {
            merged.addAll(insertions.getOrDefault(i, List.of()));
            merged.add(blocks.get(i));
        }
        merged.addAll(insertions.getOrDefault(blocks.size(), List.of()));
        merged.addAll(appendix);

        blocks.clear();
        blocks.addAll(merged);
        log.info("Word({}) 内嵌图片 OCR 完成：{} 张识别出文字并插入正文（尝试 {} 张）",
                format, recognized, attempts);
    }

    /**
     * 单张图片 OCR
     *
     * @return 识别文字；图片被过滤（图标/矢量图）或未识别出文字时返回 null
     */
    private String recognize(WordImage image) {
        try {
            byte[] payload = toOcrPayload(image);
            if (payload == null) {
                return null;
            }
            String text = ocrService.recognizeImage(payload);
            if (text == null || text.isBlank()) {
                return null;
            }
            log.debug("图片 OCR 成功：原始 {} 字节/{}，送识别 {} 字节，识别 {} 字符",
                    image.data().length, image.extension(), payload.length, text.length());
            return text;
        } catch (Exception e) {
            // 单图失败不影响整篇解析（与 PDF 单页失败跳过一致）
            log.warn("图片 OCR 失败，已跳过: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 生成送 OCR 的图片字节。
     * <p>
     * 过滤规则：不可解码的图片（emf/wmf 矢量图、异常格式）跳过；短边小于
     * {@code rag.ocr.word-min-image-side} 的图片（图标/装饰线/logo）跳过。
     * 编码规则：jpg/png/bmp 直接用原始字节（避免大图转 PNG 体积膨胀、超出 OCR 请求体上限），
     * 其余格式（gif/tiff 等）统一转 PNG；长边超过 {@link #OCR_MAX_IMAGE_SIDE} 时等比缩小。
     *
     * @return 图片字节；应跳过时返回 null
     */
    private byte[] toOcrPayload(WordImage image) throws IOException {
        BufferedImage decoded;
        try (ByteArrayInputStream in = new ByteArrayInputStream(image.data())) {
            decoded = ImageIO.read(in);
        }
        if (decoded == null) {
            log.debug("跳过不可解码图片（可能为 emf/wmf 矢量图）：{} 字节", image.data().length);
            return null;
        }

        int minSide = Math.min(decoded.getWidth(), decoded.getHeight());
        if (minSide < config.getOcr().getWordMinImageSide()) {
            log.debug("跳过过小图片（{}x{}，视为图标/装饰）", decoded.getWidth(), decoded.getHeight());
            return null;
        }

        boolean oversize = Math.max(decoded.getWidth(), decoded.getHeight()) > OCR_MAX_IMAGE_SIDE;
        if (!oversize && OCR_RAW_FORMATS.contains(image.extension())) {
            return image.data();
        }
        return toPngBytes(oversize ? scaleDown(decoded) : decoded);
    }

    /** 等比缩小到长边不超过 {@link #OCR_MAX_IMAGE_SIDE} */
    private BufferedImage scaleDown(BufferedImage source) {
        double ratio = (double) OCR_MAX_IMAGE_SIDE / Math.max(source.getWidth(), source.getHeight());
        int width = Math.max(1, (int) Math.round(source.getWidth() * ratio));
        int height = Math.max(1, (int) Math.round(source.getHeight() * ratio));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private byte[] toPngBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    // ===================== 公共处理 =====================

    /**
     * 清理文本：去除 Word 控制字符、统一换行为 \n、压缩空行并去首尾空白。
     * <p>
     * 空行对下游很关键——{@link SemanticSplitter} 以空行切分候选段落，
     * 因此段落之间必须保留换行语义，且不能残留大量控制字符噪声。
     * .doc（HWPF）中表格单元格以 \u0007 结束，转为制表符以保留行列分隔。
     */
    private String cleanText(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String text = CONTROL_CHARS.matcher(raw.replace('\u0007', '\t')).replaceAll("")
                .replace('\r', '\n')
                .replace('\u00A0', ' ')
                .replace("\uFEFF", "");
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                sb.append(trimmed).append('\n');
            }
        }
        return sb.toString().strip();
    }

    /**
     * 按逻辑单元（标题边界 / 字符上限）把段落块聚合成文档单元
     *
     * @param startUnitNo 起始单元号（扫描件形态下文本单元与图片单元共用连续编号）
     */
    private List<Document> toDocuments(List<String> blocks, int startUnitNo) {
        List<Document> documents = new ArrayList<>();
        List<String> buffer = new ArrayList<>();
        int size = 0;
        int unitNo = startUnitNo;
        for (String block : blocks) {
            boolean heading = block.startsWith("#");
            if (!buffer.isEmpty() && (size + block.length() > MAX_CHARS_PER_UNIT || heading)) {
                documents.add(buildUnit(buffer, unitNo++));
                buffer.clear();
                size = 0;
            }
            buffer.add(block);
            size += block.length() + 2;
        }
        if (!buffer.isEmpty()) {
            documents.add(buildUnit(buffer, unitNo));
        }
        return documents;
    }

    private Document buildUnit(List<String> blocks, int unitNo) {
        // 段落之间用空行分隔，与 PDF 页文本的行结构保持一致（供语义切片按空行切段）
        String text = String.join("\n\n", blocks);
        Map<String, Object> metadata = new HashMap<>();
        // Word 无物理页，page_number 记录逻辑分段序号；沿用同名 metadata 键以复用下游 page_no 落库与引用展示
        metadata.put("page_number", unitNo);
        metadata.put("end_page_number", unitNo);
        metadata.put("source_type", "word");
        return Document.builder().text(text).metadata(metadata).build();
    }

    @Override
    public List<Document> parse(MultipartFile file) throws IOException {
        return split(read(file));
    }

    @Override
    public List<Document> split(List<Document> documents) {
        return chunkSplitter.split(documents);
    }
}
