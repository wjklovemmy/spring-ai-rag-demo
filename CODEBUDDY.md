# CODEBUDDY.md This file provides guidance to CodeBuddy when working with code in this repository.

## 项目概览

基于 **Spring Boot 4.0.7 + Spring AI 2.0.0（Java 17）** 的企业级 RAG（检索增强生成）演示项目。PDF / Word 文档解析、切分、向量化后存入 Milvus；问答采用 **Agentic RAG**——模型自主决定是否调用 `searchKnowledge` 工具检索，严格基于 `[来源N]` 片段逐字引用回答（DeepSeek 生成）。附带用户长期记忆、聊天会话管理、Agent 执行轨迹等能力。

仓库为**三服务微服务**（聚合父 POM，`packaging=pom`）：

- `spring-ai-rag`（8080）— RAG 业务域：知识库/文档/问答/长期记忆，独立库 `knowledge_base`
- `spring-ai-user`（8082）— 用户域独立服务：认证/JWT/RBAC/系统管理，独立库 `spring_ai_user`
- `gateway`（7070）— Spring Cloud Gateway 统一入口，按路径分流两服务

## 构建与运行

Windows 用 `mvnw.cmd`，Linux/macOS 用 `./mvnw`，均在仓库根目录执行：

```bash
# 全量构建（跳过测试）
mvnw.cmd clean package -DskipTests

# 构建单个模块
mvnw.cmd -pl spring-ai-rag clean package -DskipTests

# 启动三个服务（各开一个终端；网关 7070 为对外入口）
mvnw.cmd -pl spring-ai-rag spring-boot:run
mvnw.cmd -pl spring-ai-user spring-boot:run
mvnw.cmd -pl gateway spring-boot:run

# 启动基础中间件（Milvus/etcd/MinIO/Redis/RabbitMQ/Nacos/Sentinel Dashboard/前端 Nginx）
cd docker && docker-compose up -d

# 前端（独立 Vue 3 工程 spring-ai-web/）
cd spring-ai-web && npm install && npm run dev   # 或 npm run build 产物 dist/
```

**前置依赖**：Nacos（localhost:8848，控制台 http://localhost:8090/nacos）；Milvus（19530）；Redis（6379）；RabbitMQ（5672）；MinIO（9002）；MySQL 双库（`sql/init.sql` 建 `knowledge_base`，`sql/user.sql` 建 `spring_ai_user`，均幂等）；环境变量 `DEEPSEEK_API_KEY`、`DASHSCOPE_API_KEY`，OCR 另需 `ALIYUN_OCR_AK/SK`。

**共享密钥三端必须一致**：`jwt.secret`（网关↔用户服务）、`gateway.internal-token`（网关→下游 `X-Gateway-Token`）、`internal-token`（RAG↔用户服务 `X-Internal-Token`）。优先从 Nacos 配置中心 `common.yaml`（见 `nacos/common.yaml`）拉取，本地 `application.yaml` 保留兜底值。

**测试**：项目无独立测试套件，验证以 `mvnw.cmd -pl <module> compile` 编译通过 + 手动经网关调接口为主。

## 架构

### 服务拓扑与请求链路

```
浏览器（Vue SPA，Nginx :9004 同源 /api → 网关）
  → gateway :7070  JwtAuthGlobalFilter
      白名单 /api/register /login /logout /refresh 直放；
      其余校验 JWT 签名 + Redis 黑名单 → 注入 X-User-Id / X-Username / X-Permissions / X-Gateway-Token
      ├── 认证/用户/角色（/api/user /api/users/** /api/admin/**）→ lb://spring-ai-user
      └── 其余 /api/**（知识库/文档/记忆）→ lb://spring-ai-rag   （lb:// 经 Nacos 服务发现）
  → 下游各自本地 GatewayIdentityFilter：校验 X-Gateway-Token → 构造 LoginUser → UserContext（ThreadLocal，finally 清理）
```

- `/internal/**` 端点不走网关，以 `X-Internal-Token` 鉴权，且被 `GatewayIdentityFilter` 跳过（只守 `/api/**`）。
- 服务间调用用 **OpenFeign**（RAG 侧 `feign/UserFeignClient`，用户侧 `feign/RagSyncFeignClient`），服务名经 Nacos + LoadBalancer 解析；全局 `RequestInterceptor`（各模块 `FeignConfig`）注入 `X-Internal-Token`；熔断由 `feign.circuitbreaker.enabled=true` + Sentinel `fallbackFactory` 兜底（Hystrix 已 EOL）。
- RAG→用户：`UserClient` 查 isAdmin/用户摘要（`/internal/users/**`）；用户→RAG：`RagSyncClient` 回调删除校验/成员清理/审计（`/internal/kb/**`，由 `InternalController` 承接）。

### RAG 摄取流水线（异步任务制）

`KnowledgeDocumentService.submitIngest` 立即返回 `taskNo`，实际处理走 **RabbitMQ**（Quorum 队列 + Publisher Confirm/Return + 消费重试 3 次进死信，代码在 `mq/` 包）。消费端流程：按扩展名分派解析器（PDF 用 PDFBox 逐页提取、无文本层走阿里云 OCR 兜底；Word 用 POI 提取段落/表格，标题样式转 Markdown，内嵌图片 OCR 后按原位插回正文、整篇仅图片则按扫描件逐图 OCR）→ 自研 `SemanticSplitter` 语义切片 + 标题链注入 → **Parent-Child 两级切分**：父块仅存 MySQL（不向量化），再按 200 token 细分为子块、子块 metadata 记 `parent_text` 并向量化入 Milvus（按库动态集合 `kb_{id}`）→ 两级增量 diff（父块变化级联重写子块，`diffChunks`）→ 5 阶段进度回写（parse/split/chunk/embed/milvus）。失败保留半成品（`milvus_id` 判空标记），重启 `DataInitializer` 扫描中断任务重新入队增量补齐。

### 问答（Agentic RAG）

`chat`/`chatStream` 无预检索注入：模型自主决定是否调 `searchKnowledge`（`tools/KbQueryTools` 薄壳，实现收敛在 `RagRetrievalService` → `KnowledgeSearchService`）。检索链：显式文档限定（问题点名文档时 Milvus filter 收窄）→ Milvus Hybrid（Dense + BM25 + RRF）召回 20 → gte-rerank-v2 精排 5 → `[来源N]` 上下文（多轮调用编号经 `SearchResult.shift/append` 全局累积）。另有 `CalculatorTool`（受限表达式求值，防 RCE）。命中子块反查父块全文作为 LLM 上下文（小块检索、大块上下文）。回答后 `alignCitations` 对齐 + `renumberCitedSources` 按实际引用过滤重排编号；每轮问答落 `agent_task`/`agent_task_step` 审计轨迹。DeepSeek 调用受 Sentinel 熔断（资源 `ai-chat`），降级返回固定话术而非 5xx。

### 模型分离（避免 Bean 歧义）

| 角色 | 模型 | Bean |
|------|------|------|
| 对话生成 | DeepSeek `deepseek-chat` | `deepSeekChatModel`（`AiConfig` 显式 `@Qualifier`） |
| 向量化 | DashScope `text-embedding-v3`（1024 维） | 自研 `DashScopeEmbeddingModel`（Spring AI 2.0 无内置 starter，直调 REST，熔断资源 `dashscope-embedding`，网络/5xx 自动重试 2 次） |
| 重排序 | 百炼 `gte-rerank-v2` | `DashScopeRerankService`（复用 DashScope key） |

### 记忆体系（三层）

1. **单会话多轮记忆**：`memory/RedisChatMemory` 实现 Spring AI `ChatMemory`，key `rag:chat:memory:{userId}:{sessionId}`（TTL 7 天，按用户隔离）。窗口为 **token 预算主控（`rag.memory.max-tokens`=16000，本地估算 ASCII 4字符/token、中文 1字符/token）+ 条数兜底（max-history=100）**，超限把最老一批交 `ConversationSummarizer`（DeepSeek，ai-chat 熔断）浓缩进摘要，读取返回「摘要 + 窗口原文」。system/Tool 消息不落库。
2. **Phase 1 跨会话摘要**：每轮问答后 `ChatSessionMemoryService.persistFromRedis` 把会话摘要落 MySQL `chat_session_memory`；新会话问答按用户（同知识库优先）注入「过往对话背景」。删会话级联逻辑删除。
3. **Phase 2 用户级长期记忆**：MySQL `user_long_term_memory`（文本）+ Milvus 全局集合 `rag_user_memory`（userId 标量过滤），`MemoryService` 编排（语义去重 0.95 + 256 段分段锁防并发重复）。三入口：Agent 工具 `tools/MemoryTools`（saveMemory/searchMemory，ToolContext 取 userId）、手动接口 `/api/memory/**`（`UserMemoryController`，仅本人）、`MemoryExtractionService` 会话后自动抽取（后台单线程 + 30 分钟用户级防抖，LLM 输出 JSON）。向量失败文本兜底（`vector_status=0`），`MemoryVectorSyncTask` 定期补偿。全链路异常零外抛。管理端 `MemoryAdminController`（仅 ADMIN，远程判 isAdmin）。

### 认证与授权（纵深防御）

- **认证**：用户服务签发 JWT（权限码写入 claims），网关校验 + Redis 黑名单（登出/刷新即失效）。
- **授权双层**：垂直 RBAC（`sys_user_role`，ADMIN 全放行）+ 水平数据授权（`kb_member`：用户×知识库×VIEWER/EDITOR/OWNER，唯一权威）。
- RAG 侧三层拦截：`@RequireKbRole` AOP（`KbAccessAspect` 自动解析 kbId）→ Service 层 `KbAuthorizationService.assertRole`（含对象级——先查文档所属 kbId）→ 列表按可见知识库集合过滤。保护最后一个 OWNER 不可移除。
- 用户侧对应 `@RequireAdmin` + `AdminAccessAspect`；默认账号 `admin/admin123`（`UserDataInitializer` 幂等初始化）。

### 包结构速览

```
spring-ai-rag (com.example.springairagdemo)
├── config/    AiConfig(模型装配+熔断规则) / RagConfigProperties(rag.* 绑定) / RabbitConfig /
│              DataSourceConfig(@Primary) / DataInitializer(恢复中断任务) / FeignConfig / GlobalExceptionHandler
├── mq/        EmbeddingTask{Producer,Consumer,DlqConsumer} / RabbitQueueMonitor(积压告警)
├── controller/ KnowledgeBase / KnowledgeDocument / ChatSession / AgentTask / UserMemory / MemoryAdmin / Internal(/internal/kb/**)
├── memory/    RedisChatMemory / ConversationSummarizer / MessageTokenEstimator / RedisMemoryMonitor(记忆膨胀告警)
├── tools/     KbQueryTools / CalculatorTool / MemoryTools
├── parser/    DocumentParserRegistry(按扩展名路由 pdf/docx/doc) / PdfDocumentParser(OCR兜底) /
│              WordDocumentParser(.docx/.doc：段落+表格+内嵌图片OCR+扫描件逐图OCR) /
│              OcrTextMerger(OCR与文本层按行去重合并) /
│              TextChunkSplitter(语义切片+标题注入+Parent-Child，各格式共用) / SemanticSplitter / HeadingExtractor
├── security/  本地安全包：KbRole / RequireKbRole / KbAccessAspect / GatewayIdentityFilter / UserContext
├── embedding/ DashScopeEmbeddingModel
├── feign/     UserFeignClient(+FallbackFactory)
└── service/   KnowledgeDocumentService(摄取+问答核心，解析器按扩展名分派) /
               KnowledgeSearchService(检索唯一入口) / RagRetrievalService(Agent检索链) /
               HybridSearchService / VectorStoreService / KbAuthorizationService /
               MemoryService / MemoryExtractionService / MemoryVectorService / MemoryVectorSyncTask /
               ChatSessionMemoryService / AgentTaskService ...

spring-ai-user (com.example.user)
├── config/    JwtUtil / JwtConfig / GatewayIdentityFilter / RagSyncClient / FeignConfig
├── controller/ Auth / AdminUser / AdminRole / InternalUserController(/internal/users/**)
├── security/  LoginUser / UserContext / RequireAdmin / AdminAccessAspect
└── service/   UserService / SysRoleService / RedisRefreshTokenService / UserDataInitializer
```

## 关键约定与陷阱

- **配置前缀**：RAG 侧自定义配置统一 `rag.*`（`RagConfigProperties` 绑定，Nacos 改配置自动重绑热生效）；网关配置用 `spring.cloud.gateway.server.webflux.*`（SC 2025.0 起旧前缀废弃）。
- **Spring Cloud Gateway 5.0** starter 名为 `spring-cloud-starter-gateway-server-webflux`。
- **Redis 三端共用实例**（网关黑名单 / 用户刷新令牌 / RAG 对话记忆），key 前缀各自隔离。
- **AI 密钥全部走环境变量**，`application.yaml` 不含明文密钥，可安全提交。
- **向量搜索维度 1024**（text-embedding-v3）；Milvus SDK 由父 POM 覆盖为 2.6.23（旧版不支持 BM25）；Hybrid 依赖 Milvus 2.5+ 服务端。
- **升级已部署库**：新表已含在 `sql/init.sql`；存量库用 `sql/migration_*.sql`（如 `migration_agent_logic_delete.sql`、`migration_parent_child.sql`）。
- **Lettuce + SCAN**：SCAN 游标须用 `executeWithStickyConnection` 粘性连接迭代；`MEMORY USAGE` 等整数回复命令不能走 `RedisConnection.execute`（ByteArrayOutput 不支持 set(long)），需 Lettuce 原生 `dispatch` + `IntegerOutput`（参考 `RedisMemoryMonitor`）。
- **`Map.of` 不接受 null 值**，响应体含可空字段时用 `HashMap` 构造。
- README.md 是最完整的业务/配置文档（含 Token 预算、SQL 表结构、全部 API 清单），改动业务行为时应同步更新。
