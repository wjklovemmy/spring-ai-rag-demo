package com.example.springairagdemo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * RAG 配置属性：按岗位 → 文档类型 → 分块参数 三层结构
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagConfigProperties {

    /** 文档全局配置（版本管理、TTL等） */
    private DocumentGlobal document = new DocumentGlobal();

    /** 文件存储配置（本地磁盘 / MinIO） */
    private Storage storage = new Storage();

    /** 召回重排序（Rerank）配置 */
    private Rerank rerank = new Rerank();

    /** OCR（扫描版 PDF 文字识别）配置 */
    private Ocr ocr = new Ocr();

    /** 混合检索（Hybrid Search：Dense 向量 + BM25 全文检索 + RRF 融合）配置 */
    private Hybrid hybrid = new Hybrid();

    /** 岗位名称 → 岗位配置 */
    private Map<String, PositionConfig> positions = new HashMap<>();

    /** 获取指定岗位的配置 */
    public PositionConfig getPositionConfig(String position) {
        return positions.get(position);
    }

    /** 获取所有岗位名称 */
    public Set<String> getPositionNames() {
        return positions.keySet();
    }

    @Data
    public static class DocumentGlobal {
        /** 文档版本共存天数：旧版本在新版本上传后 N 天内仍可检索，超期后自动过滤（默认30天） */
        private int versionTtlDays = 30;
        /** 上传文件持久化存储目录（相对或绝对路径） */
        private String uploadDir = "./uploads";
    }

    @Data
    public static class Rerank {
        /** 是否启用召回重排序 */
        private boolean enabled = false;
        /** 重排序模型：百炼 gte-rerank（gte-rerank / gte-rerank-v2） */
        private String model = "gte-rerank-v2";
        /** 向量召回候选数（先召回更多，再由 Rerank 精排） */
        private int candidateTopK = 20;
        /** 精排后保留的片段数 */
        private int topN = 5;
        /** 向量召回相似度阈值 */
        private double threshold = 0.3;
        /** 调用失败时是否降级为纯向量排序（默认降级） */
        private boolean fallbackOnError = true;
    }

    @Data
    public static class Ocr {
        /** 是否启用 OCR（扫描版 PDF 无文本层时自动识别） */
        private boolean enabled = false;
        /** OCR 服务地域，如 cn-hangzhou */
        private String regionId = "cn-hangzhou";
        /** 阿里云 AccessKey ID */
        private String accessKeyId = "";
        /** 阿里云 AccessKey Secret */
        private String accessKeySecret = "";
        /** PDF 页渲染分辨率 DPI */
        private int dpi = 200;
        /** 页文本长度低于该值视为无文本层，触发 OCR（字符数） */
        private int minTextLength = 20;
        /** 单页 OCR 失败时是否抛异常中断解析（false=记日志并跳过该页） */
        private boolean failOnError = false;
    }

    @Data
    public static class Hybrid {
        /** 是否启用混合检索（false=降级为纯向量检索） */
        private boolean enabled = true;
        /** 每路（dense / bm25）召回候选数 */
        private int routeTopK = 40;
        /** 融合结果最低 RRF 分数，低于该值视为噪声丢弃（RRF 分通常约 1/(k+rank)，默认 0 不启用过滤） */
        private double minScore = 0.0;
        /** RRF 平滑系数 k：score = Σ 1/(k + rank) */
        private int rrfK = 60;
        /** Hybrid 检索异常时是否降级为纯向量检索 */
        private boolean fallbackOnError = true;
    }

    @Data
    public static class Storage {
        /** 存储类型：local（本地磁盘，默认）、minio（MinIO 对象存储） */
        private String type = "local";
        /** MinIO 配置（type=minio 时生效） */
        private Minio minio = new Minio();
    }

    @Data
    public static class Minio {
        /** MinIO 服务地址 */
        private String endpoint = "http://localhost:9000";
        /** 访问密钥 */
        private String accessKey = "minioadmin";
        /** 秘密密钥 */
        private String secretKey = "minioadmin";
        /** 存储桶名称 */
        private String bucket = "knowledge-documents";
    }

    @Data
    public static class PositionConfig {
        /** 该岗位下的文档配置 */
        private Document document = new Document();
    }

    @Data
    public static class Document {
        /** 文档类型 → 分块配置 */
        private Map<String, Chunk> types = new HashMap<>();

        /** 获取所有支持的文档类型 */
        public Set<String> getSupportedTypes() {
            return types.keySet();
        }

        /** 获取指定类型的 chunk 配置，不存在则返回默认配置 */
        public Chunk getChunkConfig(String type) {
            return types.getOrDefault(type, new Chunk());
        }
    }

    @Data
    public static class Chunk {
        /** 每个片段约 N tokens */
        private int chunkSize = 800;
        /** 最小片段字符数 */
        private int minChunkSizeChars = 350;
        /** 最小可向量化长度 */
        private int minChunkLengthToEmbed = 80;
        /** 最大片段数上限 */
        private int maxNumChunks = 10000;
        /** 保留分隔符 */
        private boolean keepSeparator = true;
        /** 标题感知切分配置 */
        private Heading heading = new Heading();
        /** 语义切片配置 */
        private Semantic semantic = new Semantic();
    }

    @Data
    public static class Heading {
        /** 是否启用标题感知：识别标题行并构建标题链，注入到每个 chunk 前缀 */
        private boolean enabled = true;
        /** 标题链最大深度（如 "3 考勤制度 > 3.2 请假流程" 为 2 级） */
        private int maxDepth = 3;
        /** 标题行最大字符数，超过视为正文 */
        private int maxLength = 40;
        /** 标题前缀注入模板，{heading} 会被替换为标题链 */
        private String prefixTemplate = "【{heading}】";
    }

    @Data
    public static class Semantic {
        /** 是否启用语义切片：段落 embedding 聚类，按相邻相似度找语义断点 */
        private boolean enabled = true;
        /** 相邻段落余弦相似度阈值，低于该值视为语义断点（值越大切得越碎） */
        private double threshold = 0.55;
        /** embedding 批量大小（DashScope text-embedding-v3 单次上限 10 条） */
        private int batchSize = 10;
        /** 过短段落（字符）并入相邻段落，避免噪声干扰聚类 */
        private int minSegmentChars = 20;
        /** 语义切片失败时是否降级为 token 切分（默认降级） */
        private boolean fallbackOnError = true;
    }
}
