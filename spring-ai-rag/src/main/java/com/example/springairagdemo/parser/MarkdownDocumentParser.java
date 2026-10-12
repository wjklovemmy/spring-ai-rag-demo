package com.example.springairagdemo.parser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 文档解析器：支持 .md / .markdown。
 * <p>
 * Markdown 本身已是 LLM 友好的结构化文本，因此不做格式转换，重点是「保结构 + 切逻辑单元」：
 * <ol>
 *   <li><b>编码识别</b>：按 BOM（UTF-8/UTF-16LE/BE）→ 严格 UTF-8 → GBK 的顺序解码
 *       （中文 Windows 编辑器常存 GBK，直接按 UTF-8 读会整篇乱码）；</li>
 *   <li><b>结构保留</b>：标题、表格、列表、代码块原样保留（表格本就是 {@code | 单元格 |} 结构，
 *       标题本就是 {@code #} 前缀，可被 {@link HeadingExtractor} 直接识别成标题链，无需像 Word 那样转换）；</li>
 *   <li><b>YAML Front Matter</b>：文件头的 {@code ---} 元信息块（docs 站点的 title/description/tags）
 *       转为引用块 {@code > key: value} 保留——它是检索时最有用的文档标识，
 *       用 {@code >} 前缀可避免被误判为标题而污染标题链；</li>
 *   <li><b>逻辑单元切分</b>：与 Word / Excel 一致，1-2 级标题（{@code #} / {@code ##}）处切单元，
 *       单元过短（&lt; {@link #MIN_UNIT_CHARS}）时继续累积，避免产生大量一两行的小单元；
 *       累计超过 {@link #MAX_CHARS_PER_UNIT} 也在块边界切分（超长块先按行/字符预切）；</li>
 *   <li><b>代码块优先</b>：先用围栏识别代码块再判标题，代码块内的 {@code #} 不会被误认为标题；
 *       超长代码块按围栏重组，每段仍是完整代码块（保留语言标记）；未闭合围栏自动补齐。</li>
 * </ol>
 * <p>
 * 单元的 metadata 记录 {@code page_number}（逻辑分段序号），下游切分/落库/引用展示与 PDF / Word / Excel 复用同一链路。
 * 切分统一委托 {@link TextChunkSplitter}（语义切片 + 标题注入 + Parent-Child）。
 */
@Component
@Slf4j
public class MarkdownDocumentParser implements DocumentParser {

    /** 支持的扩展名 */
    private static final Set<String> SUPPORTED_TYPES = Set.of("md", "markdown");

    /** 单个逻辑单元的字符上限（与 Word / Excel 一致） */
    private static final int MAX_CHARS_PER_UNIT = 4000;

    /** 单元最小长度：不足该长度时不因下一个标题而切分，避免产生大量一两行的小单元 */
    private static final int MIN_UNIT_CHARS = 200;

    /** 作为单元边界的标题层级：1-2 级标题（# / ##）切单元，更深层级标题留在单元内 */
    private static final int SPLIT_HEADING_LEVEL = 2;

    /** 单文件最大读取字节数（防御异常大文件：正常 Markdown 远小于该值） */
    private static final int MAX_FILE_BYTES = 20 * 1024 * 1024;

    /** ATX 标题：{@code # 标题} */
    private static final Pattern ATX_HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    /** 代码块围栏：{@code ```} 或 {@code ~~~}（可带语言标记） */
    private static final Pattern FENCE = Pattern.compile("^\\s*(`{3,}|~{3,})");
    /** 表格行 */
    private static final Pattern TABLE_ROW = Pattern.compile("^\\s*\\|");
    /** 列表项（无序 {@code - * +} / 有序 {@code 1. 1)}） */
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*+]\\s|\\d+[.)]\\s)");
    /** Front Matter 分隔符 */
    private static final Pattern FRONT_MATTER_DELIM = Pattern.compile("^(?:---|\\.\\.\\.)\\s*$");
    /** HTML 注释（对读者不可见：锚点标记、{@code <!-- more -->} 等） */
    private static final Pattern HTML_COMMENT = Pattern.compile("(?s)<!--.*?-->");

    /** 需要清理的控制字符（保留 \t \n；\u0000 常用于识别"二进制误标为 .md"） */
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]");

    private final TextChunkSplitter chunkSplitter;

    public MarkdownDocumentParser(TextChunkSplitter chunkSplitter) {
        this.chunkSplitter = chunkSplitter;
    }

    /** 文件头元信息与正文 */
    private record FrontMatter(String block, String body) {
    }

    /**
     * Markdown 文本块：段落 / 标题 / 代码块 / 表格 / 列表
     *
     * @param heading      是否为标题块
     * @param level        标题层级（非标题为 0）
     * @param continuation 是否为超长块预切分后的续块（续块会继承最近的标题，保证切分后仍有上下文）
     */
    private record Block(String text, boolean heading, int level, boolean continuation) {
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
        byte[] bytes = inputStream.readNBytes(MAX_FILE_BYTES);
        if (bytes.length == 0) {
            log.warn("Markdown 文档内容为空，未解析出任何内容");
            return List.of();
        }
        if (bytes.length == MAX_FILE_BYTES) {
            log.warn("Markdown 文件超过 {} 字节，已截断处理（可能存在内容缺失）", MAX_FILE_BYTES);
        }

        String text = normalize(decode(bytes));
        if (text.isBlank()) {
            log.warn("Markdown 文档解码后为空（可能不是文本文件），未解析出任何内容");
            return List.of();
        }

        FrontMatter frontMatter = splitFrontMatter(text);
        List<Block> blocks = toBlocks(frontMatter.body());
        List<Document> units = toUnits(frontMatter.block(), blocks);
        if (units.isEmpty()) {
            log.warn("Markdown 文档未解析出任何内容（可能全为空白/注释）");
            return List.of();
        }
        log.info("从 Markdown 文档中读取到 {} 个文档片段（Front Matter: {}，文本块: {}）",
                units.size(), frontMatter.block() != null, blocks.size());
        return units;
    }

    // ===================== 文本解码 =====================

    /** 按 BOM → 严格 UTF-8 → GBK → UTF-8 宽松的顺序解码文本 */
    private String decode(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        String utf8 = decodeStrict(bytes, StandardCharsets.UTF_8);
        if (utf8 != null) {
            return utf8;
        }
        String gbk = decodeStrict(bytes, gbkCharset());
        if (gbk != null) {
            log.info("Markdown 文件不是 UTF-8 编码，已按 GBK 解码");
            return gbk;
        }
        log.warn("Markdown 文件编码无法识别，按 UTF-8 宽松解码（非法字节将被替换）");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** GBK 字符集（jlink 精简运行时可能不含，缺失时返回 null 跳过该兜底） */
    private Charset gbkCharset() {
        try {
            return Charset.forName("GBK");
        } catch (Exception e) {
            return null;
        }
    }

    private String decodeStrict(byte[] bytes, Charset charset) {
        if (charset == null) {
            return null;
        }
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** 统一换行符 + 清理控制字符（保留 \t \n） */
    private String normalize(String text) {
        return CONTROL_CHARS.matcher(text.replace("\r\n", "\n").replace('\r', '\n')).replaceAll("");
    }

    // ===================== Front Matter =====================

    /** 提取文件头 Front Matter（仅当首个非空行是分隔符且存在收尾分隔符时生效，避免误吃正文） */
    private FrontMatter splitFrontMatter(String text) {
        String[] lines = text.split("\n", -1);
        int start = 0;
        while (start < lines.length && lines[start].isBlank()) {
            start++;
        }
        if (start >= lines.length || !FRONT_MATTER_DELIM.matcher(lines[start]).matches()) {
            return new FrontMatter(null, text);
        }
        for (int end = start + 1; end < lines.length; end++) {
            if (!FRONT_MATTER_DELIM.matcher(lines[end]).matches()) {
                continue;
            }
            StringBuilder block = new StringBuilder();
            for (int i = start + 1; i < end; i++) {
                String line = lines[i].strip();
                if (!line.isEmpty()) {
                    // 引用块前缀：保留元信息（title/description/tags 对检索有用），又不会被识别为标题污染标题链
                    block.append("> ").append(line).append('\n');
                }
            }
            String body = String.join("\n", List.of(lines).subList(end + 1, lines.length));
            String blockText = block.toString().stripTrailing();
            return new FrontMatter(blockText.isEmpty() ? null : blockText, body);
        }
        // 无收尾分隔符：视为普通正文（避免把正文整体当作元信息丢弃）
        return new FrontMatter(null, text);
    }

    // ===================== 文本块切分 =====================

    /**
     * 正文切分为文本块：代码块 / 标题 / 表格 / 列表 / 段落。
     * <p>代码块优先识别，保证块内 {@code #} 不被误判为标题；超长块在此处预切，避免单元过大。
     */
    private List<Block> toBlocks(String text) {
        List<Block> blocks = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            if (lines[i].isBlank()) {
                i++;
                continue;
            }

            // 1. 代码块：从围栏到收尾围栏（未闭合时补围栏，保证每段代码块自洽）
            Matcher fence = FENCE.matcher(lines[i]);
            if (fence.find()) {
                String openLine = lines[i].stripTrailing();
                String marker = fence.group(1);
                int end = i + 1;
                while (end < lines.length && !isClosingFence(lines[end], marker)) {
                    end++;
                }
                List<String> body = new ArrayList<>(List.of(lines).subList(i + 1, Math.min(end, lines.length)));
                String closeLine = end < lines.length ? lines[end].stripTrailing() : marker;
                String compound = openLine + "\n" + String.join("\n", body) + "\n" + closeLine;
                if (compound.length() <= MAX_CHARS_PER_UNIT) {
                    blocks.add(new Block(compound, false, 0, false));
                } else {
                    int budget = Math.max(1, MAX_CHARS_PER_UNIT - openLine.length() - closeLine.length() - 2);
                    List<String> chunks = chunkLines(body, budget);
                    if (chunks.isEmpty()) {
                        blocks.add(new Block(compound, false, 0, false));
                    }
                    for (int c = 0; c < chunks.size(); c++) {
                        blocks.add(new Block(openLine + "\n" + chunks.get(c) + "\n" + closeLine, false, 0, c > 0));
                    }
                }
                i = Math.min(end + 1, lines.length);
                continue;
            }

            // 2. 标题：单行一块
            Matcher heading = ATX_HEADING.matcher(lines[i]);
            if (heading.matches()) {
                blocks.add(new Block(lines[i].stripTrailing(), true, heading.group(1).length(), false));
                i++;
                continue;
            }

            // 3. 表格 / 列表 / 段落：连续同类型行合成一块（表格行以 | 开头；列表延续行含缩进）
            int end;
            if (TABLE_ROW.matcher(lines[i]).find()) {
                end = i;
                while (end < lines.length && TABLE_ROW.matcher(lines[end]).find()) {
                    end++;
                }
            } else if (LIST_ITEM.matcher(lines[i]).find()) {
                end = i;
                while (end < lines.length && !lines[end].isBlank()
                        && (LIST_ITEM.matcher(lines[end]).find()
                        || lines[end].startsWith("  ") || lines[end].startsWith("\t"))) {
                    end++;
                }
            } else {
                end = i;
                while (end < lines.length && !lines[end].isBlank()) {
                    end++;
                }
            }
            List<String> blockLines = new ArrayList<>(List.of(lines).subList(i, end));
            String joined = String.join("\n", blockLines);
            if (joined.length() <= MAX_CHARS_PER_UNIT) {
                addBlock(blocks, joined, false);
            } else {
                List<String> chunks = chunkLines(blockLines, MAX_CHARS_PER_UNIT);
                for (int c = 0; c < chunks.size(); c++) {
                    addBlock(blocks, chunks.get(c), c > 0);
                }
            }
            i = end;
        }
        return blocks;
    }

    /**
     * 添加普通文本块（去 HTML 注释，避免不可见内容进入索引；纯分隔线块跳过）
     *
     * @param continuation 是否为超长块预切分后的续块
     */
    private void addBlock(List<Block> blocks, String text, boolean continuation) {
        String cleaned = HTML_COMMENT.matcher(text).replaceAll("").strip();
        if (!cleaned.isBlank() && !cleaned.matches("\\s*[-*_]{3,}\\s*")) {
            blocks.add(new Block(cleaned, false, 0, continuation));
        }
    }

    /** 是否为收尾围栏（同一字符重复且长度不小于起始围栏） */
    private boolean isClosingFence(String line, String marker) {
        String stripped = line.strip();
        if (stripped.length() < marker.length()) {
            return false;
        }
        char c = marker.charAt(0);
        for (int i = 0; i < stripped.length(); i++) {
            if (stripped.charAt(i) != c) {
                return false;
            }
        }
        return true;
    }

    /** 超长内容按行拆分为不超过 budget 的多段；单行本身超长时按字符硬切 */
    private List<String> chunkLines(List<String> lines, int budget) {
        List<String> chunks = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() > 0 && sb.length() + line.length() + 1 > budget) {
                chunks.add(sb.toString().stripTrailing());
                sb.setLength(0);
            }
            if (line.length() > budget) {
                for (int s = 0; s < line.length(); s += budget) {
                    chunks.add(line.substring(s, Math.min(line.length(), s + budget)));
                }
                continue;
            }
            sb.append(line).append('\n');
        }
        if (sb.length() > 0) {
            chunks.add(sb.toString().stripTrailing());
        }
        return chunks;
    }

    // ===================== 逻辑单元组装 =====================

    /**
     * 文本块累积为逻辑单元：1-2 级标题处切分（单元过短则继续累积），超过字符上限在块边界切分。
     * 与 Word / Excel 一致，单元序号写入 metadata.page_number 供下游复用引用展示。
     */
    private List<Document> toUnits(String frontMatter, List<Block> blocks) {
        List<Document> units = new ArrayList<>();
        List<Block> current = new ArrayList<>();
        Block lastHeading = null;
        int size = 0;
        int unitNo = 1;

        if (frontMatter != null) {
            current.add(new Block(frontMatter, false, 0, false));
            size = frontMatter.length() + 2;
        }
        for (Block block : blocks) {
            boolean sectionStart = block.heading() && block.level() <= SPLIT_HEADING_LEVEL;
            if (sectionStart && !current.isEmpty() && size >= MIN_UNIT_CHARS) {
                units.add(buildUnit(current, unitNo++));
                current = new ArrayList<>();
                size = 0;
            } else if (!current.isEmpty() && current.size() > 1
                    && size + block.text().length() > MAX_CHARS_PER_UNIT) {
                // 超过字符上限：尾部的标题块跟随内容进入下一个单元，避免出现"只有标题没有内容"的孤儿单元
                List<Block> carry = new ArrayList<>();
                while (current.size() > 1 && current.get(current.size() - 1).heading()) {
                    carry.add(0, current.remove(current.size() - 1));
                }
                units.add(buildUnit(current, unitNo++));
                current = new ArrayList<>(carry);
                size = sizeOf(current);
            }
            // 超长块预切分的续块：新单元开头补上最近的标题，保证切分后片段仍带章节上下文
            if (current.isEmpty() && block.continuation() && lastHeading != null) {
                current.add(lastHeading);
                size = sizeOf(current);
            }
            current.add(block);
            size += block.text().length() + 2; // +2 计入块间空行
            if (block.heading()) {
                lastHeading = block;
            }
        }
        if (!current.isEmpty()) {
            units.add(buildUnit(current, unitNo));
        }
        return units;
    }

    private int sizeOf(List<Block> blocks) {
        int size = 0;
        for (Block block : blocks) {
            size += block.text().length() + 2;
        }
        return size;
    }

    /** 块之间用空行分隔（与 PDF 页文本/Word 一致，供语义切片按空行切段） */
    private Document buildUnit(List<Block> blocks, int unitNo) {
        Map<String, Object> metadata = new HashMap<>();
        // Markdown 无分页，page_number 记录逻辑分段序号；沿用同名 metadata 键以复用下游 page_no 落库与引用展示
        metadata.put("page_number", unitNo);
        metadata.put("end_page_number", unitNo);
        metadata.put("source_type", "markdown");
        return Document.builder()
                .text(String.join("\n\n", blocks.stream().map(Block::text).toList()))
                .metadata(metadata)
                .build();
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
