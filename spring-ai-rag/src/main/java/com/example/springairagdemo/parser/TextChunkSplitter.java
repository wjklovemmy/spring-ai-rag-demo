package com.example.springairagdemo.parser;

import com.example.springairagdemo.config.RagConfigProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用文本切分器：与文档来源格式无关的切分链路，供各格式解析器（PDF / Word）复用。
 * <p>
 * 切分策略（自研，Spring AI 2.0 无 SemanticTextSplitter）：
 * <ol>
 *   <li>语义切片：段落批量 embedding 聚类，按相邻相似度找语义断点（失败降级 token 切分）；</li>
 *   <li>标题注入：识别标题行构建标题链，将所属标题前缀注入 chunk 文本并写入 metadata.heading；</li>
 *   <li>Parent-Child：父块（语义/token 切分结果）再细分为子块，子块向量化检索、
 *       命中后反查父块全文作为 LLM 上下文。</li>
 * </ol>
 */
@Component
@Slf4j
public class TextChunkSplitter {

    /** metadata 键：父块全文（Parent-Child 检索）。语义/token 切分结果作为父块注入标题后，
     *  再细分为子块；每个子块都携带 parent_text 以便检索命中后反查父块全文，以及摄取阶段重建父块列表 */
    public static final String META_PARENT_TEXT = "parent_text";

    private final RagConfigProperties config;
    private final SemanticSplitter semanticSplitter;
    private final HeadingExtractor headingExtractor;

    public TextChunkSplitter(RagConfigProperties config,
                             SemanticSplitter semanticSplitter,
                             HeadingExtractor headingExtractor) {
        this.config = config;
        this.semanticSplitter = semanticSplitter;
        this.headingExtractor = headingExtractor;
    }

    /**
     * 将原始文档单元（PDF 页 / Word 逻辑分段）切分为检索用片段
     *
     * @param documents 原始文档列表（每个元素为一个独立上下文单元）
     * @return 切分结果（Parent-Child 开启时为子块，关闭时为父块）
     */
    public List<Document> split(List<Document> documents) {
        RagConfigProperties.Chunk chunk = config.getDocument().getChunk();
        RagConfigProperties.Heading headingCfg = chunk.getHeading();
        RagConfigProperties.Semantic semanticCfg = chunk.getSemantic();

        List<Document> result = new ArrayList<>();
        for (Document unitDoc : documents) {
            // 1. 提取单元内标题链（按字符偏移定位）
            List<HeadingExtractor.HeadingLine> headings = headingCfg.isEnabled()
                    ? headingExtractor.extract(unitDoc.getText(), headingCfg)
                    : List.of();

            // 2. 语义切片（失败降级 token 切分）
            List<Document> unitChunks;
            if (semanticCfg.isEnabled()) {
                try {
                    unitChunks = semanticSplitter.split(unitDoc, semanticCfg,
                            chunk.getChunkSize(), chunk.getMinChunkSizeChars());
                } catch (Exception e) {
                    if (semanticCfg.isFallbackOnError()) {
                        log.warn("语义切片失败，降级为 token 切分: {}", e.getMessage());
                        unitChunks = tokenSplit(unitDoc, chunk);
                    } else {
                        throw new RuntimeException("语义切片失败", e);
                    }
                }
            } else {
                unitChunks = tokenSplit(unitDoc, chunk);
            }

            // 3. 标题前缀注入：语义/token 切分结果 = 父块（注入标题链后作为完整上下文单元）
            RagConfigProperties.ParentChild pcCfg = chunk.getParentChild();
            List<Document> parents = new ArrayList<>();
            for (Document unitChunk : unitChunks) {
                parents.add(injectHeading(unitChunk, headings, headingCfg));
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

        log.info("文档分割为 {} 个文本片段（Parent-Child 已启用: {}）",
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
     * 整单元 token 切分（语义切片关闭或降级时使用）
     */
    private List<Document> tokenSplit(Document unitDoc, RagConfigProperties.Chunk chunk) {
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(chunk.getChunkSize())
                .withMinChunkSizeChars(chunk.getMinChunkSizeChars())
                .withMinChunkLengthToEmbed(chunk.getMinChunkLengthToEmbed())
                .withMaxNumChunks(chunk.getMaxNumChunks())
                .withKeepSeparator(chunk.isKeepSeparator())
                .build();
        return splitter.apply(List.of(unitDoc));
    }

    /**
     * 按 chunk 在单元文本中的起始偏移定位所属标题，将标题链前缀注入文本并写入 metadata.heading。
     * 无偏移（token 切分产物）时使用单元首个标题链。
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
