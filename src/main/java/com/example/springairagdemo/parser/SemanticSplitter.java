package com.example.springairagdemo.parser;

import com.example.springairagdemo.config.RagConfigProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 语义切片器（自研，Spring AI 2.0 已移除 SemanticTextSplitter）
 * <p>
 * 算法：页面文本 → 按空行切出候选段落 → 批量 embedding → 计算相邻段落余弦相似度 →
 * 相似度低于阈值处作为语义断点 → 贪心合并控制 chunk 长度 → 超长段用 TokenTextSplitter 二次切分。
 * <p>
 * 每个输出 chunk 的 metadata 会保留原页面 Document 的 metadata（如 pageNo），
 * 并额外写入 {@link #META_CHUNK_START} 记录其在页面文本中的起始字符偏移，
 * 供标题感知切分（HeadingExtractor）定位该 chunk 所属的标题链。
 */
@Component
@Slf4j
public class SemanticSplitter {

    /** metadata key：chunk 在页面文本中的起始字符偏移 */
    public static final String META_CHUNK_START = "chunk_start";

    private final EmbeddingModel embeddingModel;

    public SemanticSplitter(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * 对单个页面 Document 做语义切片
     *
     * @param pageDoc   页面文档（文本 + 原 metadata）
     * @param semantic  语义切片配置
     * @param chunkSize token 大小（用于推导字符上限，中文约 1.5 字符/token）
     * @param minChars  最小 chunk 字符数（低于该长度不强制断点）
     * @return 语义切片结果；文本过短或切分异常时可能返回原样文档
     */
    public List<Document> split(Document pageDoc, RagConfigProperties.Semantic semantic,
                                int chunkSize, int minChars) {
        String text = pageDoc.getText();
        if (text == null || text.isBlank()) {
            return List.of(pageDoc);
        }

        List<Segment> segments = toSegments(text, semantic.getMinSegmentChars());
        if (segments.size() <= 1) {
            // 单段文本无边界可切，原样返回（保留 offset 便于标题注入）
            return List.of(withMeta(pageDoc, META_CHUNK_START, 0));
        }

        List<float[]> vectors = embed(segments, semantic.getBatchSize());
        int maxChars = Math.max(chunkSize * 2, 200);
        List<Segment> merged = merge(segments, vectors, semantic.getThreshold(), maxChars, minChars);
        return toDocuments(pageDoc, merged, maxChars, chunkSize, minChars);
    }

    // ===================== 段落切分 =====================

    /**
     * 按空行把文本切成候选段落，记录每段在原文中的字符偏移；
     * 过短段落并入前一段，避免孤立噪声影响聚类。
     */
    private List<Segment> toSegments(String text, int minSegmentChars) {
        List<Segment> raw = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        StringBuilder buf = new StringBuilder();
        int segStart = 0;
        int segEnd = 0;
        int offset = 0;

        for (String line : lines) {
            int lineStart = offset;
            offset += line.length() + 1; // +1 计入换行符
            if (line.isBlank()) {
                if (buf.length() > 0) {
                    raw.add(new Segment(buf.toString(), segStart, segEnd));
                    buf.setLength(0);
                }
                continue;
            }
            if (buf.length() == 0) {
                segStart = lineStart;
            }
            buf.append(line).append('\n');
            segEnd = offset;
        }
        if (buf.length() > 0) {
            raw.add(new Segment(buf.toString(), segStart, segEnd));
        }

        // 过短段落并入前一段
        List<Segment> merged = new ArrayList<>();
        for (Segment seg : raw) {
            if (!merged.isEmpty() && seg.text.length() < minSegmentChars) {
                Segment prev = merged.get(merged.size() - 1);
                merged.set(merged.size() - 1,
                        new Segment(prev.text + seg.text, prev.start, seg.end));
            } else {
                merged.add(seg);
            }
        }
        return merged;
    }

    // ===================== embedding =====================

    private List<float[]> embed(List<Segment> segments, int batchSize) {
        List<float[]> result = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i += batchSize) {
            List<Segment> batch = segments.subList(i, Math.min(i + batchSize, segments.size()));
            List<String> texts = batch.stream().map(s -> s.text).toList();
            EmbeddingResponse response = embeddingModel.call(new EmbeddingRequest(texts, null));
            List<Embedding> embeddings = response.getResults();
            for (int j = 0; j < batch.size(); j++) {
                result.add(embeddings.get(j).getOutput());
            }
        }
        return result;
    }

    // ===================== 语义合并 =====================

    /**
     * 贪心合并：相邻段落相似度 &lt; 阈值（且当前 chunk 已够长）→ 断点；
     * 累计长度超过上限 → 强制断点。
     */
    private List<Segment> merge(List<Segment> segments, List<float[]> vectors,
                                double threshold, int maxChars, int minChars) {
        List<Segment> result = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int curStart = segments.get(0).start;
        int curEnd = segments.get(0).end;

        for (int i = 0; i < segments.size(); i++) {
            Segment seg = segments.get(i);
            boolean boundary = false;

            if (cur.length() > 0) {
                double sim = cosine(vectors.get(i - 1), vectors.get(i));
                if (sim < threshold && cur.length() >= minChars) {
                    boundary = true;
                }
                if (cur.length() + seg.text.length() > maxChars) {
                    boundary = true;
                }
            }

            if (boundary) {
                result.add(new Segment(cur.toString(), curStart, curEnd));
                cur.setLength(0);
                curStart = seg.start;
            }
            cur.append(seg.text);
            curEnd = seg.end;
        }

        if (cur.length() > 0) {
            result.add(new Segment(cur.toString(), curStart, curEnd));
        }
        return result;
    }

    private double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    // ===================== 输出 =====================

    private List<Document> toDocuments(Document pageDoc, List<Segment> merged,
                                       int maxChars, int chunkSize, int minChars) {
        List<Document> docs = new ArrayList<>(merged.size());
        for (Segment seg : merged) {
            Map<String, Object> meta = new HashMap<>(pageDoc.getMetadata());
            meta.put(META_CHUNK_START, seg.start);
            if (seg.text.length() > maxChars) {
                // 单段超长（极少见，如整页无空行），用 token 二次切分
                Document tmp = Document.builder()
                        .text(seg.text)
                        .metadata(meta)
                        .build();
                TokenTextSplitter splitter = TokenTextSplitter.builder()
                        .withChunkSize(chunkSize)
                        .withMinChunkSizeChars(minChars)
                        .withMaxNumChunks(10_000)
                        .build();
                for (Document d : splitter.apply(List.of(tmp))) {
                    docs.add(d);
                }
            } else {
                docs.add(Document.builder()
                        .text(seg.text)
                        .metadata(meta)
                        .build());
            }
        }
        return docs;
    }

    private Document withMeta(Document pageDoc, String key, int value) {
        Map<String, Object> meta = new HashMap<>(pageDoc.getMetadata());
        meta.put(key, value);
        return Document.builder()
                .text(pageDoc.getText())
                .metadata(meta)
                .build();
    }

    /**
     * 语义合并后的文本片段（含原文偏移）
     */
    private record Segment(String text, int start, int end) {
    }
}
