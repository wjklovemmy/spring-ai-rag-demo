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

    /** 文件存储配置（MinIO 对象存储，多实例部署下文件须共享，不提供本地磁盘模式） */
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

    /** RabbitMQ 队列积压监控配置（Ready 消息数告警） */
    private MqMonitor mqMonitor = new MqMonitor();

    /** 对话记忆配置（滑动窗口 + 摘要压缩，防单会话历史无限增长） */
    private Memory memory = new Memory();

    /** 对话记忆监控配置（Redis key 数量 / 总占用告警） */
    private MemoryMonitor memoryMonitor = new MemoryMonitor();

    @Data
    public static class DocumentGlobal {
        /** 文档版本共存天数：旧版本在新版本上传后 N 天内仍可检索，超期后自动过滤（默认30天） */
        private int versionTtlDays = 30;
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
        /** MinIO 配置 */
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

    @Data
    public static class MqMonitor {
        /** 是否启用队列积压监控（false 关闭定时轮询） */
        private boolean enabled = true;
        /** 轮询间隔（毫秒）：上一次检查完成后延迟该时长再检查 */
        private long intervalMs = 30000;
        /** Ready（待消费）消息数告警阈值：超过即判定积压并告警 */
        private long readyThreshold = 50;
        /** RabbitMQ Management API 地址（docker-compose rabbitmq:3.13-management，默认 15672） */
        private String managementUrl = "http://localhost:15672";
        /** Management API 账号（guest 默认仅允许 localhost 访问，本服务与 RabbitMQ 同机，满足） */
        private String managementUsername = "guest";
        /** Management API 密码 */
        private String managementPassword = "guest";
        /** 告警 Webhook（企业微信/钉钉/飞书机器人地址），留空仅打 ERROR 日志 */
        private String webhookUrl = "";
    }

    @Data
    public static class Memory {
        /** 是否启用摘要压缩：历史超窗口时，最老一批交给 LLM 浓缩进摘要（false=仅纯裁剪，不消耗 LLM 调用） */
        private boolean summaryEnabled = true;
        /** 单会话记忆窗口（条数兜底）：最多保留/返回给模型的历史消息条数，防单条消息过多时无限膨胀 */
        private int maxHistory = 100;
        /** 单会话记忆窗口（token 预算主控）：窗口内历史消息的总 token 估算上限。
         *  建议按模型上下文窗口的 1/4~1/3 预留（DeepSeek 64K → 取 16000）。
         *  token 估算为本地保守上界（ASCII 4 字符/token、中文 1 字符/token），非精确计数 */
        private int maxTokens = 16000;
        /** 摘要压缩批次（条数）：存储超过 maxHistory + batch 条，或总 token 超过 maxTokens 时，
         *  把最老的 batch 条压缩进摘要。每 batch 轮对话触发一次压缩（额外消耗一次 DeepSeek 调用），
         *  batch 越小压缩越频繁、摘要越精细 */
        private int summaryBatchSize = 20;
        /** Phase 1 长期记忆：是否把会话摘要持久化到 MySQL chat_session_memory（跨会话复用） */
        private boolean historyPersistEnabled = true;
        /** 新会话问答开始时注入的历史会话摘要条数上限（0 = 关闭历史背景注入） */
        private int historyInjectLimit = 5;
        /** Redis 记忆无摘要时，取最近 N 轮对话原文作为「会话要点」落库（0 = 跳过无摘要会话） */
        private int fallbackLastTurns = 6;
        /** Phase 2 长期记忆：用户级长期记忆（saveMemory/searchMemory 工具 + 问答注入 + 会话后自动抽取） */
        private LongTerm longTerm = new LongTerm();
    }

    /** Phase 2 用户级长期记忆配置（rag.memory.long-term.*） */
    @Data
    public static class LongTerm {
        /** 用户级长期记忆总开关（saveMemory/searchMemory 工具注册 + 问答前注入 + 自动抽取） */
        private boolean enabled = true;
        /** Milvus 全局用户记忆集合名（userId 标量字段做用户隔离，主键 = MySQL memory id） */
        private String collectionName = "rag_user_memory";
        /** 每次问答按问题语义召回的长期记忆条数上限（≤0 关闭问答前注入） */
        private int injectLimit = 5;
        /** 注入系统提示的【用户长期记忆】文本总字符上限（超出截断，避免挤占上下文） */
        private int maxChars = 1600;
        /** 召回最低余弦相似度（低于该分的记忆不注入/不返回，避免无关记忆干扰模型） */
        private double minScore = 0.3;
        /** searchMemory 工具单次召回条数上限 */
        private int searchTopK = 8;
        /** 保存时语义去重阈值：与已有记忆余弦相似度达到该值视为同一事实，不重复新增 */
        private double dedupeThreshold = 0.95;
        /** 单用户记忆条数上限（防无限膨胀；达到后自动抽取跳过、工具保存拒绝） */
        private int maxPerUser = 500;
        /** 会话结束后自动抽取用户长期记忆的开关 */
        private boolean autoExtractEnabled = true;
        /** 同一用户自动抽取最小间隔（分钟）：防抖，避免每次问答都触发一次 LLM 抽取 */
        private long autoExtractIntervalMinutes = 30;
        /** 自动抽取输入（近期对话原文）总字符上限 */
        private int extractMaxChars = 4000;
        /** 单次抽取最多保存的记忆条数 */
        private int extractMaxFacts = 6;
        /** 向量后台补偿开关：vector_status=0（embedding 临时不可用而降级文本落库）的记忆定期重试向量化入库 */
        private boolean vectorSyncEnabled = true;
        /** 向量后台补偿扫描间隔（毫秒）：上一轮补偿完成后延迟该时长再扫描下一轮 */
        private long vectorSyncIntervalMs = 60000;
        /** 每轮后台补偿条数上限（限制突发批量，避免向量服务刚恢复即被打满/触发熔断） */
        private int vectorSyncBatchSize = 20;
    }

    @Data
    public static class MemoryMonitor {
        /** 是否启用记忆膨胀监控（false 关闭定时轮询） */
        private boolean enabled = true;
        /** 轮询间隔（毫秒）：上一次检查完成后延迟该时长再检查 */
        private long intervalMs = 60000;
        /** 会话 key 数量告警阈值：SCAN rag:chat:memory:* 计数超过即告警 */
        private long keyCountThreshold = 10000;
        /** 记忆总占用告警阈值（字节）：各 key MEMORY USAGE 汇总超过即告警，默认 256MB */
        private long totalBytesThreshold = 256L * 1024 * 1024;
        /** 告警 Webhook（企业微信/钉钉/飞书机器人地址），留空仅打 ERROR 日志 */
        private String webhookUrl = "";
    }
}
