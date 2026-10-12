package com.example.springairagdemo.parser;

import com.example.springairagdemo.config.RagConfigProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hssf.usermodel.HSSFPatriarch;
import org.apache.poi.hssf.usermodel.HSSFPicture;
import org.apache.poi.hssf.usermodel.HSSFPictureData;
import org.apache.poi.hssf.usermodel.HSSFShape;
import org.apache.poi.hssf.usermodel.HSSFShapeGroup;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.apache.poi.xssf.usermodel.XSSFPictureData;
import org.apache.poi.xssf.usermodel.XSSFShape;
import org.apache.poi.xssf.usermodel.XSSFShapeGroup;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Excel 文档解析器：支持 .xlsx（OOXML，poi-ooxml）与 .xls（OLE2 二进制，poi 主包的 HSSF），
 * 由 {@link WorkbookFactory} 按文件魔数判别真实格式（不信任扩展名）。
 * <p>
 * 内容提取覆盖五类信息，尽量不丢内容：
 * <ol>
 *   <li><b>单元格</b>：公式取计算值（无缓存结果也能取到），日期/百分比按 Excel 显示格式输出
 *       （DataFormatter 渲染，避免日期读成 45000 这类序列号）；</li>
 *   <li><b>合并单元格</b>：把合并区域左上角的值补全到区域内其它单元格——表头/分类列常用纵向合并
 *       （如"部门"跨多行），不补全则后续行会丢失该维度；</li>
 *   <li><b>表格结构</b>：每行输出 {@code | 单元格 | 单元格 |}（与 Word 表格一致），
 *       首个非空行作为表头并补 Markdown 分隔行，帮助模型识别表头语义；</li>
 *   <li><b>多工作表</b>：每张表以 {@code ## 工作表: 名称} 开篇，作为标题进入标题链
 *       （检索命中时上下文带上表名，跨表同名字段不再混淆）；</li>
 *   <li><b>图片</b>：内嵌图片（截图/扫描件）走 OCR，识别文字作为该表的补充单元追加
 *       （{@link ImageOcrSupport}，与 Word 共用去重/过滤/限流规则）。</li>
 * </ol>
 * <p>
 * Excel 没有物理分页概念，因此与 Word 一致按「逻辑单元」组织内容，与 PDF 的「页」对应：
 * 数据行累积到 {@link #MAX_CHARS_PER_UNIT} 即切出一个单元，<b>每个单元都重复表头</b>
 * （Excel 表通常很长，切碎后没有表头则行数据无法理解）；
 * 单元的 metadata 记录 {@code page_number}（逻辑分段序号），下游切分/落库/引用展示复用 PDF 链路。
 * <p>
 * 切分统一委托 {@link TextChunkSplitter}，与 PDF / Word 共用语义切片 + 标题注入 + Parent-Child 策略。
 */
@Component
@Slf4j
public class ExcelDocumentParser implements DocumentParser {

    /** 支持的扩展名 */
    private static final Set<String> SUPPORTED_TYPES = Set.of("xlsx", "xls");

    /** 单个逻辑单元的字符上限（与 Word 一致）：超出即切单元并重复表头 */
    private static final int MAX_CHARS_PER_UNIT = 4000;

    /** 单表最大列数：Excel 常残留大量空列导致 lastCellNum 虚高，超过即截断（防噪声与内存膨胀） */
    private static final int MAX_COLUMNS = 64;

    /** 单表最大行数（防御超大表）：超出部分跳过并告警 */
    private static final int MAX_ROWS = 10000;

    /** 单元格内空白（换行/制表/不间断空格等）压成单个空格：表格要求"一行一条记录"，行内不能出现换行 */
    private static final Pattern CELL_WHITESPACE = Pattern.compile("[\\s\\u00A0\\u000B\\u000C\\uFEFF]+");

    private final RagConfigProperties config;
    private final ImageOcrSupport imageOcr;
    private final TextChunkSplitter chunkSplitter;

    public ExcelDocumentParser(RagConfigProperties config, ImageOcrSupport imageOcr,
                               TextChunkSplitter chunkSplitter) {
        this.config = config;
        this.imageOcr = imageOcr;
        this.chunkSplitter = chunkSplitter;
    }

    /** 单次解析的可变状态：解析器是单例 Bean，文档级状态只能放在方法内，不可用实例字段 */
    private static final class ParseState {
        final List<Document> units = new ArrayList<>();
        /** 已出现过的图片内容指纹（同一张图在文档中重复出现时只识别一次） */
        final Set<String> imageFingerprints = new HashSet<>();
        /** 逻辑单元序号（跨工作表连续，下游作为 page_no 落库与引用展示） */
        int unitNo = 1;
        /** 已尝试 OCR 的图片总数（跨工作表累计，受 {@code rag.ocr.word-max-images} 限制） */
        int imageAttempts = 0;
    }

    /** 单张工作表的有效数据：行 × 列（已补全合并单元格、剔除全空行与首尾空列） */
    private record SheetGrid(List<List<String>> rows) {
    }

    /** 工作表内嵌图片 */
    private record SheetImage(String extension, byte[] data) {
    }

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
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            ParseState state = new ParseState();
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = createEvaluator(workbook);
            int sheetCount = workbook.getNumberOfSheets();
            for (int i = 0; i < sheetCount; i++) {
                readSheet(workbook.getSheetAt(i), formatter, evaluator, state);
            }
            if (state.units.isEmpty()) {
                // 空表/全空白：返回空列表，由摄取流程判定"未解析出内容"并失败，避免静默产出空索引
                log.warn("Excel 文档未解析出任何内容（工作表数: {}，可能为空表或全为空白单元格）", sheetCount);
                return List.of();
            }
            log.info("从 Excel 文档中读取到 {} 个文档片段（{} 张工作表）", state.units.size(), sheetCount);
            return state.units;
        } catch (IOException e) {
            throw new IOException("Excel 解析失败: " + e.getMessage(), e);
        } catch (Exception e) {
            // POI 对损坏/非法/加密文档会抛 POIXMLException、EncryptedDocumentException 等运行时异常
            throw new IOException("Excel 解析失败（文件可能已损坏、加密或非 Excel 格式）: " + e.getMessage(), e);
        }
    }

    /** 公式求值器：单元格取值优先用计算值（公式未缓存结果时也能取到），创建失败则退化为只读缓存值 */
    private FormulaEvaluator createEvaluator(Workbook workbook) {
        try {
            return workbook.getCreationHelper().createFormulaEvaluator();
        } catch (Exception e) {
            log.warn("创建 Excel 公式求值器失败，公式单元格将只读取缓存值: {}", e.getMessage());
            return null;
        }
    }

    /** 解析单张工作表：先提取表格单元，再追加图片 OCR 单元（单元序号连续） */
    private void readSheet(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator, ParseState state) {
        String sheetName = sheet.getSheetName();
        SheetGrid grid = readGrid(sheet, formatter, evaluator);
        if (grid == null) {
            log.debug("工作表 [{}] 无有效数据，已跳过表格提取", sheetName);
        } else {
            appendTableUnits(sheetName, grid.rows(), state);
        }
        appendImageUnits(sheet, sheetName, state);
    }

    /**
     * 读取工作表为「行 × 列」网格：补全合并单元格 → 保留非空行 → 剔除首尾全空列。
     *
     * @return 网格；整表无有效数据时返回 null
     */
    private SheetGrid readGrid(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        int firstRow = sheet.getFirstRowNum();
        int lastRow = sheet.getLastRowNum();
        if (lastRow < firstRow) {
            return null;
        }

        List<CellRangeAddress> mergedRegions;
        try {
            mergedRegions = sheet.getMergedRegions();
        } catch (Exception e) {
            log.debug("读取工作表 [{}] 合并区域失败: {}", sheet.getSheetName(), e.getMessage());
            mergedRegions = List.of();
        }

        // 1. 有效列上界：所有行的 lastCellNum 与合并区域的并集（lastCellNum 常因格式残留虚高）
        int rawMaxColumn = -1;
        for (int r = firstRow; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row != null) {
                rawMaxColumn = Math.max(rawMaxColumn, row.getLastCellNum() - 1);
            }
        }
        for (CellRangeAddress region : mergedRegions) {
            rawMaxColumn = Math.max(rawMaxColumn, region.getLastColumn());
        }
        if (rawMaxColumn < 0) {
            return null;
        }
        int maxColumn = Math.min(rawMaxColumn, MAX_COLUMNS - 1);
        if (rawMaxColumn > maxColumn) {
            log.warn("工作表 [{}] 列数 {} 超过上限 {}，超出列已忽略",
                    sheet.getSheetName(), rawMaxColumn + 1, MAX_COLUMNS);
        }

        // 2. 逐行取值（键为原始行号，供合并区域补全定位）；全空行不保留，避免残留格式产生大段空白
        int lastReadRow = Math.min(lastRow, firstRow + MAX_ROWS - 1);
        if (lastRow > lastReadRow) {
            log.warn("工作表 [{}] 行数 {} 超过上限 {}，剩余行已忽略",
                    sheet.getSheetName(), lastRow - firstRow + 1, MAX_ROWS);
        }
        Map<Integer, String[]> byRow = new HashMap<>();
        for (int r = firstRow; r <= lastReadRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            String[] cells = new String[maxColumn + 1];
            boolean blank = true;
            for (int c = 0; c <= maxColumn; c++) {
                String value = cellText(row.getCell(c), formatter, evaluator);
                cells[c] = value;
                blank = blank && value.isEmpty();
            }
            if (!blank) {
                byRow.put(r, cells);
            }
        }
        if (byRow.isEmpty()) {
            return null;
        }

        // 3. 合并单元格补全：区域内其它单元格填上左上角的值
        fillMergedRegions(byRow, mergedRegions, maxColumn);

        // 4. 剔除首尾全空列（行内保留中间空列以维持列对齐）
        int minColumn = maxColumn;
        int maxNonEmptyColumn = -1;
        for (String[] cells : byRow.values()) {
            for (int c = 0; c <= maxColumn; c++) {
                if (!cells[c].isEmpty()) {
                    minColumn = Math.min(minColumn, c);
                    maxNonEmptyColumn = Math.max(maxNonEmptyColumn, c);
                }
            }
        }
        List<List<String>> rows = new ArrayList<>(byRow.size());
        for (int r = firstRow; r <= lastReadRow; r++) {
            String[] cells = byRow.get(r);
            if (cells == null) {
                continue;
            }
            List<String> line = new ArrayList<>(maxNonEmptyColumn - minColumn + 1);
            for (int c = minColumn; c <= maxNonEmptyColumn; c++) {
                line.add(cells[c]);
            }
            rows.add(line);
        }
        return rows.isEmpty() ? null : new SheetGrid(rows);
    }

    /**
     * 合并单元格补全：合并区域只有左上角单元格保留值，其余为空。
     * 表头/分类列常用纵向合并（如"部门"跨多行），不补全则后续行读起来会丢失该维度。
     * 区域内完全没有数据的行（其本身为全空行）不补全，避免凭空多出无意义行。
     */
    private void fillMergedRegions(Map<Integer, String[]> byRow, List<CellRangeAddress> regions, int maxColumn) {
        for (CellRangeAddress region : regions) {
            int firstColumn = region.getFirstColumn();
            String[] sourceRow = byRow.get(region.getFirstRow());
            if (sourceRow == null || firstColumn < 0 || firstColumn > maxColumn) {
                continue;
            }
            String value = sourceRow[firstColumn];
            if (value == null || value.isEmpty()) {
                continue;
            }
            int lastColumn = Math.min(region.getLastColumn(), maxColumn);
            for (int r = region.getFirstRow(); r <= region.getLastRow(); r++) {
                String[] cells = byRow.get(r);
                if (cells == null) {
                    continue;
                }
                for (int c = firstColumn; c <= lastColumn; c++) {
                    if (cells[c].isEmpty()) {
                        cells[c] = value;
                    }
                }
            }
        }
    }

    /**
     * 生成表格单元：数据行转 Markdown 表格行，首行（表头）与分隔行在每个单元里重复，
     * 保证切碎后每个片段都能独立理解行列含义。
     */
    private void appendTableUnits(String sheetName, List<List<String>> rows, ParseState state) {
        String title = "## 工作表: " + sheetName;
        // 首个非空行视为表头：仅当还有后续数据行时才补 Markdown 分隔行（单行表即数据本身）
        boolean hasHeader = rows.size() > 1;
        List<String> headerLines = new ArrayList<>(2);
        if (hasHeader) {
            headerLines.add(toTableLine(rows.get(0)));
            headerLines.add(toSeparatorLine(rows.get(0).size()));
        }

        List<String> dataLines = new ArrayList<>();
        int size = 0;
        for (int i = hasHeader ? 1 : 0; i < rows.size(); i++) {
            String line = toTableLine(rows.get(i));
            if (!dataLines.isEmpty() && size + line.length() > MAX_CHARS_PER_UNIT) {
                state.units.add(buildUnit(title, headerLines, dataLines, state.unitNo++));
                dataLines = new ArrayList<>();
                size = 0;
            }
            dataLines.add(line);
            size += line.length() + 1;
        }
        state.units.add(buildUnit(title, headerLines, dataLines, state.unitNo++));
    }

    /** 一行 → Markdown 表格行：{@code | 单元格 | 单元格 |}（与 Word 表格输出保持一致） */
    private String toTableLine(List<String> cells) {
        StringBuilder sb = new StringBuilder("|");
        for (String cell : cells) {
            sb.append(' ').append(cell).append(" |");
        }
        return sb.toString();
    }

    /** 表头分隔行：{@code | --- | --- |} */
    private String toSeparatorLine(int columns) {
        return "|" + " --- |".repeat(columns);
    }

    /**
     * 工作表内嵌图片 OCR：识别文字作为该表的补充单元（单元同样以工作表名开篇，检索命中时上下文完整）。
     * 与 Word 共用规则：同图只识别一次、图标按尺寸过滤、单篇张数受 {@code rag.ocr.word-max-images} 限制。
     */
    private void appendImageUnits(Sheet sheet, String sheetName, ParseState state) {
        if (!imageOcr.isEnabled()) {
            return;
        }
        List<SheetImage> images = new ArrayList<>();
        collectImages(sheet, images, state.imageFingerprints);
        if (images.isEmpty()) {
            return;
        }

        RagConfigProperties.Ocr ocr = config.getOcr();
        List<String> texts = new ArrayList<>();
        int attempts = 0;
        for (SheetImage image : images) {
            if (state.imageAttempts >= ocr.getWordMaxImages()) {
                log.warn("Excel 内嵌图片超过 OCR 上限 {}，剩余 {} 张跳过（可调大 rag.ocr.word-max-images）",
                        ocr.getWordMaxImages(), images.size() - attempts);
                break;
            }
            state.imageAttempts++;
            attempts++;
            String text = imageOcr.recognize(image.data(), image.extension());
            if (text != null) {
                texts.add(text);
            }
        }
        if (texts.isEmpty()) {
            log.debug("工作表 [{}] 内嵌图片 {} 张均未识别出文字", sheetName, attempts);
            return;
        }

        String text = "## 工作表: " + sheetName + "\n\n### 图片文字\n\n" + String.join("\n\n", texts);
        state.units.add(newUnit(text, state.unitNo++, Map.of("ocr", true)));
        log.info("工作表 [{}] 内嵌图片 OCR 完成：{} 张识别出文字并追加（尝试 {} 张）",
                sheetName, texts.size(), attempts);
    }

    /** 收集工作表内嵌图片（xlsx 走 XSSFDrawing，xls 走 HSSFPatriarch），同一张图只保留一次 */
    private void collectImages(Sheet sheet, List<SheetImage> images, Set<String> fingerprints) {
        try {
            if (sheet instanceof XSSFSheet xssfSheet) {
                XSSFDrawing drawing = xssfSheet.getDrawingPatriarch();
                if (drawing != null) {
                    collectXssfShapes(drawing, drawing.getShapes(), images, fingerprints);
                }
            } else if (sheet instanceof HSSFSheet hssfSheet) {
                HSSFPatriarch patriarch = hssfSheet.getDrawingPatriarch();
                if (patriarch != null) {
                    collectHssfShapes(patriarch.getChildren(), images, fingerprints);
                }
            }
        } catch (Exception e) {
            // 单表图形对象读取失败不影响表格内容提取（异常 XML/不支持的图形对象）
            log.warn("读取工作表 [{}] 内嵌图片失败，跳过图片 OCR: {}", sheet.getSheetName(), e.getMessage());
        }
    }

    /** 递归收集 xlsx 形状中的图片（组合形状内的图片同样识别） */
    private void collectXssfShapes(XSSFDrawing drawing, List<XSSFShape> shapes,
                                   List<SheetImage> images, Set<String> fingerprints) {
        for (XSSFShape shape : shapes) {
            if (shape instanceof XSSFPicture picture) {
                XSSFPictureData data = picture.getPictureData();
                if (data != null) {
                    addImage(data.getData(), data.suggestFileExtension(), images, fingerprints);
                }
            } else if (shape instanceof XSSFShapeGroup group) {
                collectXssfShapes(drawing, drawing.getShapes(group), images, fingerprints);
            }
        }
    }

    /** 递归收集 xls 形状中的图片（组合形状内的图片同样识别） */
    private void collectHssfShapes(List<HSSFShape> shapes, List<SheetImage> images, Set<String> fingerprints) {
        for (HSSFShape shape : shapes) {
            if (shape instanceof HSSFPicture picture) {
                HSSFPictureData data = picture.getPictureData();
                if (data != null) {
                    addImage(data.getData(), data.suggestFileExtension(), images, fingerprints);
                }
            } else if (shape instanceof HSSFShapeGroup group) {
                collectHssfShapes(group.getChildren(), images, fingerprints);
            }
        }
    }

    /** 记录图片（内容指纹去重：同一张图在文档中重复出现时只处理一次） */
    private void addImage(byte[] data, String extension, List<SheetImage> images, Set<String> fingerprints) {
        if (data == null || data.length == 0) {
            return;
        }
        if (!fingerprints.add(ImageOcrSupport.fingerprint(data))) {
            return;
        }
        images.add(new SheetImage(extension == null ? "" : extension.toLowerCase(Locale.ROOT), data));
    }

    /**
     * 单元格文本：优先用公式计算值（公式单元格也能取到结果），日期/百分比按 Excel 显示格式输出
     * （DataFormatter 按单元格格式渲染，避免日期读成 45000 这类序列号）。
     * 单元格内换行/制表压成空格：表格要求"一行一条记录"，行内不能出现换行。
     */
    private String cellText(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        String value;
        try {
            value = evaluator == null ? formatter.formatCellValue(cell)
                    : formatter.formatCellValue(cell, evaluator);
        } catch (Exception e) {
            // 公式计算失败（外部引用/循环引用/不支持的函数）：退回缓存值，避免整个单元格丢失
            log.debug("单元格(行 {}, 列 {})取值失败，退回缓存值: {}",
                    cell.getRowIndex(), cell.getColumnIndex(), e.getMessage());
            value = formatter.formatCellValue(cell);
        }
        if (value == null || value.isEmpty()) {
            return "";
        }
        return CELL_WHITESPACE.matcher(value).replaceAll(" ").strip();
    }

    /** 单元文本：标题（工作表名） + 表头块（每个单元重复） + 数据行，段落间空行分隔（供语义切片识别段落） */
    private Document buildUnit(String title, List<String> headerLines, List<String> dataLines, int unitNo) {
        StringBuilder sb = new StringBuilder(title).append("\n\n");
        for (String header : headerLines) {
            sb.append(header).append('\n');
        }
        for (String line : dataLines) {
            sb.append(line).append('\n');
        }
        return newUnit(sb.toString().stripTrailing(), unitNo, Map.of());
    }

    private Document newUnit(String text, int unitNo) {
        return newUnit(text, unitNo, Map.of());
    }

    /** 构建单元 Document：写入逻辑分段序号 metadata（沿用 PDF/Word 同名键，复用下游 page_no 落库与引用展示） */
    private Document newUnit(String text, int unitNo, Map<String, Object> extraMetadata) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("page_number", unitNo);
        metadata.put("end_page_number", unitNo);
        metadata.put("source_type", "excel");
        metadata.putAll(extraMetadata);
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
