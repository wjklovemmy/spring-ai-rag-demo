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
    }
}
