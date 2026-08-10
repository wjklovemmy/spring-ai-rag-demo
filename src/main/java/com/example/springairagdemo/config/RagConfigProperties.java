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
