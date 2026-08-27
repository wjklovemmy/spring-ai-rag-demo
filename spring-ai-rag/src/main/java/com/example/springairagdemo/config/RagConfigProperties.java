package com.example.springairagdemo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 配置属性：全局文档配置（版本管理、分块参数等）
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagConfigProperties {

    /** 文档全局配置（版本管理、TTL、分块参数等） */
    private DocumentGlobal document = new DocumentGlobal();

    /** 文件存储配置（本地磁盘 / MinIO） */
    private Storage storage = new Storage();

    /** 召回重排序（Rerank）配置 */
    private Rerank rerank = new Rerank();

    /** OCR（扫描版 PDF 文字识别）配置 */
    private Ocr ocr = new Ocr();

    /** 混合检索（Hybrid Search：Dense 向量 + BM25 全文检索 + RRF 融合）配置 */
    private Hybrid hybrid = new Hybrid();

    /** 工具调用（Tool Calling）限制配置：防止枚举/大纲类工具在大数据量下撑爆上下文 */
    private Tools tools = new Tools();

    /** Sentinel 熔断降级规则配置（ai-chat 问答 / dashscope-embedding 向量化） */
    private Sentinel sentinel = new Sentinel();

    @Data
    public static class DocumentGlobal {
        /** 文档版本共存天数：旧版本在新版本上传后 N 天内仍可检索，超期后自动过滤（默认30天） */
        private int versionTtlDays = 30;
        /** 上传文件持久化存储目录（相对或绝对路径） */
        private String uploadDir = "./uploads";
        /** 向量化批处理大小：每批 chunk 数（每批执行一次 embedding 批量调用 + 一次 Milvus upsert + 一次进度回写），
         *  分批降低大文档单次 embedding/upsert 的内存与超时风险，并支持进度实时感知 */
        private int batchSize = 100;
        /** 文档分块配置（全局统一） */
        private Chunk chunk = new Chunk();
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
        /** Parent-Child 检索配置：语义切分结果作为父块（存 MySQL），再细分为子块（向量化存 Milvus） */
        private ParentChild parentChild = new ParentChild();
    }

    @Data
    public static class ParentChild {
        /** 是否启用 Parent-Child 检索：切分结果为父块，再按子块大小细分为子块。
         *  子块向量化存 Milvus（小块召回精度高），检索命中子块后反查父块全文作为 LLM 上下文（上下文完整）。 */
        private boolean enabled = true;
        /** 子块大小（tokens）：父块细分为子块的粒度，子块更小 → 召回更精准，但向量数变多 */
        private int childChunkSize = 200;
        /** 子块最小字符数 */
        private int childMinChunkSizeChars = 80;
        /** 子块最小可向量化长度（低于该长度不单独向量化，并入相邻子块） */
        private int childMinChunkLengthToEmbed = 40;
        /** 子块最大数量上限（防御超大文档） */
        private int childMaxNumChunks = 50000;
        /** 子块切分保留分隔符 */
        private boolean childKeepSeparator = true;
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

    @Data
    public static class Tools {
        /** 单份文档大纲标题数量上限：超过即停止扫描并提示截断，避免超大文档撑爆上下文 */
        private int outlineLimit = 200;
        /** 一次大纲查询最多处理的文档数：文档过多时引导用户/模型指定具体文档，避免枚举全部文档撑爆上下文 */
        private int maxOutlineDocs = 20;
        /** 大纲结果文本总长度上限（字符）：防止多文档/超大文档拼接出超大工具结果 */
        private int maxOutlineChars = 12000;
        /** 文档清单最多展示条数：上万文档场景下列全部会撑爆工具结果，超限截断并引导关键词定位 */
        private int maxInventoryDocs = 200;
    }

    @Data
    public static class Sentinel {
        /** ai-chat（DeepSeek 问答）熔断规则 */
        private Rule aiChat = new Rule();
        /** dashscope-embedding（DashScope 向量化）熔断规则 */
        private Rule embedding = new Rule();
    }

    @Data
    public static class Rule {
        /** 异常比例阈值（0~1）：达到最小请求数后异常占比 >= 该值即熔断 */
        private double exceptionRatio = 0.5;
        /** 最小请求数：请求量不足该值不参与熔断统计 */
        private int minRequestAmount = 5;
        /** 熔断时间窗（秒）：熔断持续时长，期间快速失败 */
        private int timeWindowSeconds = 10;
    }
}
