# Spring AI RAG Demo

一个基于 **Spring Boot 4 + Spring AI 2.0** 的企业级 **RAG（Retrieval-Augmented Generation，检索增强生成）** 演示项目。

项目将 PDF 文档解析、切分、向量化后存入 Milvus 向量数据库，问答时通过向量检索召回相关片段，作为上下文注入 DeepSeek 大模型生成回答，并附带可溯源的知识库引用。

---

## 目录

- [技术栈](#技术栈)
- [整体架构](#整体架构)
- [目录结构](#目录结构)
- [核心业务流程](#核心业务流程)
  - [1. 文档上传与摄取（Ingestion）](#1-文档上传与摄取ingestion)
  - [2. 知识问答（Q&A）](#2-知识问答qa)
  - [3. 文档删除](#3-文档删除)
  - [4. 用户认证与授权（JWT + RBAC）](#4-用户认证与授权jwt--rbac)
- [数据库设计](#数据库设计)
- [配置说明](#配置说明)
- [快速开始](#快速开始)
- [REST API 一览](#rest-api-一览)
- [关键设计决策](#关键设计决策)

---

## 技术栈

| 分类 | 技术 | 说明 |
|------|------|------|
| 框架 | Spring Boot 4.0.7 | 应用基础框架（Java 17） |
| AI 框架 | Spring AI 2.0.0 | 统一抽象 Chat / Embedding / VectorStore |
| 对话模型 | DeepSeek `deepseek-chat` | 问答生成模型 |
| 向量模型 | DashScope `text-embedding-v3`（1024 维） | 文本向量化（自研 `AbstractEmbeddingModel` 实现） |
| 重排序模型 | 百炼 `gte-rerank-v2` | 召回后精排（Cross-Encoder），提升上下文质量 |
| 向量数据库 | Milvus 2.6.0（SDK `milvus-sdk-java` 2.6.23） | 向量存储 + **BM25 全文检索（Hybrid Search，RRF 融合）** |
| 数据库 | MySQL 8.x + MyBatis-Plus 3.5.16 | 元数据 / 用户 / chunk 文本持久化 |
| 对象存储 | MinIO 8.5.12 | 原始文档文件存储（同时支持本地磁盘模式） |
| 认证/授权 | JWT（jjwt 0.12.6）+ BCrypt + RBAC | 无状态登录认证 + 知识库数据权限（防越权） |
| PDF 解析 | Spring AI `PagePdfDocumentReader` | 按页解析 PDF（文本层） |
| OCR | 阿里云 OCR（`ocr_api20210707` SDK） | 扫描版 PDF（无文本层）自动识别文字 |
| 前端 | 原生 HTML / CSS / JS | `login.html` + `index.html` 静态页面 |

---

## 整体架构

```mermaid
graph TB
    subgraph Frontend["前端（静态页面）"]
        LOGIN["login.html<br/>登录 / 注册"]
        INDEX["index.html<br/>问答 & 上传"]
    end

    subgraph Backend["Spring Boot 应用"]
        CTRL["Controller 层<br/>Auth / KnowledgeBase / KnowledgeDocument"]
        AUTH["JWT 认证过滤器<br/>JwtAuthenticationFilter"]
        ASPECT["KbAccessAspect<br/>@RequireKbRole AOP 鉴权"]
        AUTHZ["KbAuthorizationService<br/>assertRole / visibleKbIds"]
        SVC["Service 层<br/>ingest 模板流程 / chat 问答 / 文档删除"]
        PARSER["Parser<br/>PagePdfDocumentReader + OCR 兜底"]
        SPLIT["自研 Chunking<br/>语义切片 + 标题注入"]
        EMBED["DashScopeEmbeddingModel<br/>text-embedding-v3"]
        RERANK["DashScopeRerankService<br/>gte-rerank-v2 精排"]
        CHAT["ChatClient<br/>deepseek-chat"]
    end

    subgraph Storage["存储层"]
        MYSQL[("MySQL<br/>文档/Chunk/用户/角色/kb_member 元数据")]
        MILVUS[("Milvus<br/>向量库 kb_{id}<br/>Dense + BM25 + RRF")]
        MINIO[("MinIO<br/>原始 PDF 文件")]
    end

    OCRSVC["阿里云 OCR API"]
    AI["DeepSeek API"]
    DS["DashScope API"]

    LOGIN --> CTRL
    INDEX --> AUTH --> CTRL
    CTRL --> ASPECT --> AUTHZ --> MYSQL
    AUTHZ --> CTRL
    CTRL --> SVC
    SVC --> PARSER --> SPLIT
    PARSER -.无文本层.-> OCRSVC
    SPLIT --> MYSQL
    SPLIT --> EMBED --> DS
    EMBED --> MILVUS
    SVC --> CHAT --> AI
    MILVUS --> SVC
    SVC --> RERANK --> DS
    RERANK --> SVC
    SVC --> MINIO
    SVC --> MYSQL
```

**模型分工（Bean 显式限定避免歧义）：**

| 角色 | 模型 | 提供商 | Bean 名 |
|------|------|--------|---------|
| 对话 / 生成 | `deepseek-chat` | DeepSeek API | `deepSeekChatModel` |
| 向量化 | `text-embedding-v3` | DashScope（阿里云） | 自定义 `DashScopeEmbeddingModel` |
| 召回重排序 | `gte-rerank-v2` | 百炼（DashScope） | 自定义 `DashScopeRerankService` |
| 图片文字识别 | 阿里云 OCR `RecognizeAllText` | 阿里云 OCR | 自定义 `AliyunOcrService` |

> Spring AI 2.0 未内置 DashScope Embedding Starter，项目自研了 `DashScopeEmbeddingModel`（继承 `AbstractEmbeddingModel`），直接调用 DashScope REST API，输出 1024 维向量。Rerank 复用同一 DashScope API Key。

---

## 目录结构

```
spring-ai-rag-demo/
├── docker/
│   └── docker-compose.yml          # Milvus(含 etcd/attu) + MinIO 编排
├── logs/                           # 运行日志
├── pom.xml                         # Maven 依赖（Java 17）
├── mvnw / mvnw.cmd                 # Maven Wrapper
└── src/
    ├── main/
    │   ├── java/com/example/springairagdemo/
    │   │   ├── SpringAiRagDemoApplication.java
    │   │   ├── config/
    │   │   │   ├── AiConfig.java                  # ChatClient / 模型装配
    │   │   │   ├── MilvusConfig.java              # Milvus 客户端
    │   │   │   ├── RagConfigProperties.java       # rag.* 配置绑定
    │   │   │   ├── JwtUtil.java                   # JWT 生成/解析
    │   │   │   ├── JwtConfig.java                 # JWT 配置属性
    │   │   │   └── JwtAuthenticationFilter.java   # 认证过滤器
    │   │   ├── controller/
    │   │   │   ├── AuthController.java            # 注册/登录/登出/当前用户/用户搜索
    │   │   │   ├── KnowledgeBaseController.java   # 知识库管理 + 成员授权
    │   │   │   └── KnowledgeDocumentController.java # 上传/问答/删除/下载
    │   │   ├── security/                          # 防越权（RBAC + 数据授权）
    │   │   │   ├── LoginUser.java                 # 登录用户模型（id/username/nickname）
    │   │   │   ├── UserContext.java               # ThreadLocal 当前用户上下文
    │   │   │   ├── KbRole.java                    # 角色枚举 VIEWER < EDITOR < OWNER
    │   │   │   ├── ForbiddenException.java        # 403 业务异常
    │   │   │   ├── RequireKbRole.java             # 方法级权限注解
    │   │   │   └── KbAccessAspect.java            # AOP 切面：自动解析 kbId 并鉴权
    │   │   ├── embedding/
    │   │   │   └── DashScopeEmbeddingModel.java   # 自研 DashScope 向量模型
    │   │   ├── entity/                            # MyBatis-Plus 实体
    │   │   │   ├── KnowledgeBaseEntity.java
    │   │   │   ├── KnowledgeDocumentEntity.java   # 含 version 等
    │   │   │   ├── KnowledgeChunkEntity.java
    │   │   │   ├── UserEntity.java
    │   │   │   ├── SysRoleEntity.java             # RBAC 角色
    │   │   │   ├── SysUserRoleEntity.java         # 用户-角色关联
    │   │   │   ├── KbMemberEntity.java            # 知识库成员授权（数据权限）
    │   │   │   └── KbAccessLogEntity.java         # 访问审计日志
    │   │   ├── mapper/                            # MyBatis-Plus Mapper
    │   │   │   ├── KnowledgeBaseMapper.java
    │   │   │   ├── KnowledgeDocumentMapper.java
    │   │   │   ├── KnowledgeChunkMapper.java
    │   │   │   ├── UserMapper.java
    │   │   │   ├── SysRoleMapper.java
    │   │   │   ├── SysUserRoleMapper.java
    │   │   │   ├── KbMemberMapper.java
    │   │   │   └── KbAccessLogMapper.java
    │   │   ├── parser/
    │   │   │   ├── DocumentParser.java             # 解析接口
    │   │   │   └── PdfDocumentParser.java          # PDF 解析实现（含 OCR 兜底）
    │   │   └── service/
    │   │       ├── KnowledgeDocumentService.java   # 摄取模板方法 + 问答（抽象类）
    │   │       ├── PdfKnowledgeDocumentServiceImpl.java # PDF 摄取实现
    │   │       ├── VectorStoreService.java         # Milvus 增删查（Dense + BM25 Hybrid Search）
    │   │       ├── HybridSearchService.java        # 混合检索编排（RRF 融合 + 异常降级）
    │   │       ├── RerankService.java              # 重排序接口
    │   │       ├── DashScopeRerankService.java     # 百炼 gte-rerank 实现
    │   │       ├── OcrService.java                 # OCR 接口
    │   │       ├── AliyunOcrService.java           # 阿里云 OCR 实现
    │   │       ├── FileStorageService.java         # 文件存储接口
    │   │       ├── MinioFileStorageService.java    # MinIO 实现
    │   │       ├── LocalFileStorageService.java    # 本地磁盘实现
    │   │       ├── KnowledgeBaseService.java       # 知识库服务接口
    │   │       ├── KnowledgeDocumentEntityService.java
    │   │       ├── KnowledgeChunkEntityService.java
    │   │       ├── UserService.java                # 注册/登录（JWT + BCrypt）
    │   │       ├── KbAuthorizationService.java     # 权限判定中枢（assertRole/visibleKbIds/授权）
    │   │       ├── KbMemberService.java            # 知识库成员授权服务
    │   │       ├── KbAccessLogService.java         # 访问审计日志服务
    │   │       ├── SysRoleService.java             # RBAC 角色服务
    │   │       ├── SysUserRoleService.java         # 用户-角色关联服务
    │   │       └── impl/                           # Service 实现类
    │   └── resources/
    │       ├── application.yaml                    # 全局配置
    │       ├── static/
    │       │   ├── login.html                      # 登录/注册页
    │       │   └── index.html                      # 问答/上传仪表盘
    │       └── sql/init.sql                        # 业务建表语句
    └── test/
├── sql/
│   └── init.sql                              # RBAC 权限表（sys_role/sys_user_role/kb_member/kb_access_log）
```

---

## 核心业务流程

### 1. 文档上传与摄取（Ingestion）

`KnowledgeDocumentService.ingest()` 是摄取流程的**模板方法**（`@Transactional(rollbackFor = Exception.class)`），由子类 `PdfKnowledgeDocumentServiceImpl` 实现解析/切分细节。入口先执行 `assertRole(kbId, EDITOR)` 权限校验（需 EDITOR 及以上）：

```
上传 PDF
  │
  ├─ ① saveDocumentInfo   写入 MySQL knowledge_document
  │       · 同知识库同名文件 → 自动推断递增版本号 v1/v2/v3...
  │       · 状态置为 0（上传中）
  │
  ├─ ② parseDocument      按页解析 PDF（PagePdfDocumentReader）
  │
  ├─ ③ splitDocument      自研 Chunking：语义切片 + 标题感知注入
  │       · 段落批量 embedding 聚类 → 相邻相似度 < 0.55 处断点（语义边界）
  │       · 识别标题行构建标题链（如 "3 考勤制度 > 3.2 请假流程"），
  │         以 "【标题链】正文" 前缀注入 chunk 文本并写 metadata.heading
  │       · 超长段 token 二次切分；语义切片失败自动降级 TokenTextSplitter
  │       · 默认 chunk-size 800、min 350 字符、最大 10000 chunk
  │
  ├─ ④ saveChunks         chunk 文本批量写入 MySQL knowledge_chunk
  │       · 记录 chunk_index / content_hash(SHA-256) / page_no
  │
  ├─ ⑤ storeToVector      chunk 文本 → DashScope 向量化 → 写入 Milvus kb_{id}
  │
  ├─ ⑥ persistUploadedFile  以上全部成功后才把原始文件存入 MinIO/本地
  │       · 路径规则：{知识库id}/{年/月/日}/{文档id}_{清洗文件名}.pdf
  │
  ├─ ⑦ 更新文档状态为成功（status=3），回填 chunk_count
  │
  └─ ⑧ deprecateOldVersions  为新版设置旧版本过期时间（平滑下线）
          · 旧版本 TTL（默认 30 天）内仍可检索，超期自动过滤
```

**失败兜底：**
- 任何步骤异常 → 主事务回滚 MySQL 写入；
- 若 Milvus 已写入向量则主动删除回滚；
- 文档状态通过独立事务标记为失败（status=4），不随主事务回滚。

### 2. 知识问答（Q&A）

`KnowledgeDocumentService.chat(question, knowledgeBaseId)`（入口先执行 `assertRole(kbId, VIEWER)`，需 VIEWER 及以上）：

```
用户问题
  │
  ├─ ① 检索召回   Hybrid Search：问题 → DashScope 向量化（Dense 路）+ 关键词全文（BM25 路），
  │       Milvus 端 RRF 融合，召回 candidateTopK=20 候选
  │       · rag.hybrid.enabled=false 时降级为纯向量相似度检索（阈值 0.3）
  │
  ├─ ② 过滤       过滤已过期版本文档的检索结果
  │
  ├─ ③ 取回文本   按 chunk_id 从 MySQL 回查完整 chunk 内容
  │
  ├─ ④ Rerank 精排 百炼 gte-rerank 对 "问题 vs 候选" 逐对打分，
  │       按相关性降序取 topN=5（失败自动降级为向量排序）
  │
  ├─ ⑤ 组装上下文 按精排顺序拼接，标注 [来源n] 文档名 + 页码
  │
  ├─ ⑥ LLM 生成   上下文注入系统提示词（仅依据知识库回答），DeepSeek 生成答案
  │
  └─ ⑦ 来源溯源   从回答中正则提取实际引用的 [来源n]，返回精准来源列表
```

**检索增强说明**：采用"先宽后精"的两阶段检索——**混合检索**（Dense 语义向量 + BM25 全文关键词双路召回，RRF 融合）召回较多候选（默认 20），再由专门的 Cross-Encoder 重排序模型精排取前 5，显著优于纯向量 top-5。BM25 路能召回向量路遗漏的"关键词精确命中"片段，对专有名词、编号、缩写类问题尤其有效。

**返回结构**：`{ answer, sources: [{documentId, documentName, pageNo, snippet}] }`
前端可将 `sources` 渲染为可下载/可跳转的引用来源。

### 3. 文档删除

`KnowledgeDocumentService.deleteDocument(documentId)` 三存储独立容错（对象级权限：先按文档 ID 查所属知识库，再 `assertRole(kbId, EDITOR)` 校验）：

1. MySQL：删除 `knowledge_chunk` + `knowledge_document`（同事务，原子性）
2. MinIO：删除原始文件（异常仅记日志，不阻断）
3. Milvus：按文档 ID 删除向量（异常仅记日志，不阻断）

### 4. 用户认证与授权（JWT + RBAC）

**认证**（识别"你是谁"）：

```
注册  POST /api/register   username/password/nickname/email
                             ↓
                     UserService.register
                     · 校验用户名唯一
                     · BCrypt 加密密码入库
                     · 返回 JWT Token

登录  POST /api/login      username/password
                             ↓
                     UserService.login
                     · BCrypt 校验密码
                     · 生成 JWT（有效期 7 天，默认）

访问  /api/**             请求头携带 Authorization: Bearer <token>
                             ↓
                     JwtAuthenticationFilter
                     · 校验 Token → 构造 LoginUser → UserContext.set()
                     · 放行 register/login/logout，拦截其余 /api/**
                     · 请求结束 finally 中 UserContext.clear()
```

**授权**（判定"你能做什么"）——纵深防御三层：

```
① 注解式入口校验（AOP）
   @RequireKbRole(EDITOR) 等标注在 Controller 方法上
   KbAccessAspect 自动从方法参数解析 kbId（参数名 / 唯一 Number / JSON body）
   → KbAuthorizationService.assertRole(kbId, role)

② Service 层守卫（核心业务兜底）
   ingest()  → assertRole(kbId, EDITOR)   # 上传文档
   chat()    → assertRole(kbId, VIEWER)   # 问答检索
   deleteDocument() → 对象级：先查文档所属 kbId 再校验

③ 数据源头过滤（防泄露）
   list / knowledge-bases 强制按「当前用户可见知识库集合」过滤
   download/delete 为对象级安全：先 getDocument() 取 kbId 再校验
```

**角色模型（双层权限）：**

- **垂直权限（RBAC）**：`sys_user_role` 关联全局角色，`ADMIN` 为超级管理员，放行全部操作（不参与 kb_member 判定）。
- **水平权限（数据授权）**：`kb_member` 记录"用户 × 知识库 × 角色"，是知识库访问的唯一权威。

| 角色 | 级别 | 权限 |
|------|------|------|
| `ADMIN` | 全局 | 全部操作（不看 kb_member） |
| `OWNER` | 4 | 管理/删除/授权成员 + 上传/编辑 + 查看检索 |
| `EDITOR` | 3 | 上传/编辑文档 + 查看检索 |
| `VIEWER` | 2 | 仅查看/检索/下载 |

安全特性：
- **创建人强制**：创建知识库时 `createUser` 取自登录态（不信任前端传参），创建者自动成为 OWNER；
- **最后一个 OWNER 保护**：移除成员时不允许移除知识库最后一名 OWNER；
- **AOP 自动解析 kbId**：无需手工写重复鉴权代码；
- **统一 403**：`GlobalExceptionHandler` 将 `ForbiddenException` 转为 `{success:false, code:403}`；
- **审计日志**：`kb_access_log` 记录关键访问行为；
- 默认初始化 `ADMIN` 角色 + `admin/admin123` 账号（见 `DataInitializer`）。

前端 `index.html` 通过 `fetchApi()` 统一在请求头注入 Token，登出时清除 `localStorage`。

---

## 数据库设计

初始化脚本：
- `src/main/resources/sql/init.sql` — 业务表（需手动在 MySQL 执行一次）
- `sql/init.sql` — 权限表 `sys_role` / `sys_user_role` / `kb_member` / `kb_access_log`（需执行；应用启动时 `DataInitializer` 会自动补 `ADMIN` 角色与默认账号，但不建表）

| 表 | 用途 | 关键字段 |
|----|------|----------|
| `knowledge_base` | 知识库 | name(唯一)、description、status、create_user |
| `knowledge_document` | 文档元数据 | knowledge_id(FK)、file_name、file_path、file_size、file_type、chunk_count、embedding_model、status(0上传中/1解析中/2Embedding中/3成功/4失败)、**version**、**expire_time**（旧版本下线时间）、**is_active** |
| `knowledge_chunk` | 文本分块 | document_id(FK)、chunk_index、content(LONGTEXT)、content_hash(SHA-256)、token_count、page_no、milvus_id |
| `knowledge_embedding_task` | 向量化任务 | task_no(唯一)、document_id(FK)、status、total/success/fail_chunk、retry_count、error_message、cost_time |
| `sys_user` | 系统用户 | username(唯一)、password(BCrypt)、nickname、email、status |
| `sys_role` | 全局角色（RBAC） | role_code(唯一)、role_name、description |
| `sys_user_role` | 用户-角色关联 | user_id(FK)、role_id(FK) |
| `kb_member` | 知识库成员授权（数据权限） | knowledge_id(FK)、user_id(FK)、role(VIEWER/EDITOR/OWNER)、create_time |
| `kb_access_log` | 访问审计日志 | user_id、knowledge_id、action、ip、create_time |

---

## 配置说明

`application.yaml` 关键配置：

| 配置项 | 说明 |
|--------|------|
| `spring.ai.deepseek.*` | DeepSeek base-url / 模型 / 温度（api-key 从环境变量 `DEEPSEEK_API_KEY` 读取） |
| `spring.ai.dashscope.*` | DashScope embedding 模型（api-key 从环境变量 `DASHSCOPE_API_KEY` 读取） |
| `spring.ai.vectorstore.milvus.*` | Milvus 连接、索引类型（IVF_FLAT/COSINE）、维度 1024 |
| `spring.datasource.*` | MySQL 连接（`knowledge_base` 库） |
| `rag.storage.type` | `minio` / `local` 文件存储切换 |
| `rag.storage.minio.*` | MinIO endpoint / 密钥 / bucket |
| `rag.document.version-ttl-days` | 旧版本文档共存天数（默认 30） |
| `rag.document.upload-dir` | 本地存储模式上传目录 |
| `rag.document.chunk.*` | 全局文档分块参数（chunk-size、heading、semantic 等，见下表） |
| `rag.rerank.enabled` | 是否启用召回重排序（默认 true） |
| `rag.rerank.model` | 重排序模型（默认 `gte-rerank-v2`） |
| `rag.rerank.candidate-top-k` | 向量召回候选数（默认 20） |
| `rag.rerank.top-n` | 精排后保留片段数（默认 5） |
| `rag.rerank.threshold` | 向量召回相似度阈值（默认 0.3） |
| `rag.rerank.fallback-on-error` | Rerank 失败时降级为纯向量排序（默认 true） |
| `rag.hybrid.enabled` | 是否启用混合检索（Dense + BM25 + RRF，默认 true；false=纯向量） |
| `rag.hybrid.route-top-k` | 每路（dense / bm25）召回候选数（默认 40，融合取 rerank.candidate-top-k 条） |
| `rag.hybrid.min-score` | 融合结果最低 RRF 分数，低于该值视为噪声（默认 0 不启用过滤） |
| `rag.hybrid.rrf-k` | RRF 平滑系数 k（默认 60，score = Σ 1/(k + rank)） |
| `rag.hybrid.fallback-on-error` | Hybrid 检索异常时降级为纯向量检索（默认 true） |
| `rag.ocr.enabled` | 是否启用 OCR（默认 true） |
| `rag.ocr.region-id` | OCR 服务地域（默认 cn-hangzhou） |
| `rag.ocr.access-key-id/secret` | 阿里云 AccessKey（建议环境变量 `ALIYUN_OCR_AK/SK`） |
| `rag.ocr.dpi` | PDF 页渲染分辨率（默认 200） |
| `rag.ocr.min-text-length` | 页文本低于该长度触发 OCR（默认 20） |
| `rag.document.chunk.heading.enabled` | 标题感知切分开关（默认 true） |
| `rag.document.chunk.heading.max-depth` | 标题链最大深度（默认 3） |
| `rag.document.chunk.heading.max-length` | 标题行最大字符数（默认 40） |
| `rag.document.chunk.heading.prefix-template` | 标题前缀注入模板（默认 `【{heading}】`） |
| `rag.document.chunk.semantic.enabled` | 语义切片开关（默认 true） |
| `rag.document.chunk.semantic.threshold` | 相邻段落相似度断点阈值（默认 0.55） |
| `rag.document.chunk.semantic.batch-size` | 段落 embedding 批量大小（默认 10） |
| `rag.document.chunk.semantic.fallback-on-error` | 语义切片失败降级 token 切分（默认 true） |
| `jwt.secret` / `jwt.expiration-ms` | JWT 密钥（≥32 字节）与过期时间 |

> Rerank 复用 `spring.ai.dashscope.api-key`，无需单独配置 key；
> OCR 需在阿里云开通"文字识别 OCR"服务，并配置 AccessKey（建议用环境变量注入）；
> 语义切片复用 `DashScopeEmbeddingModel`（text-embedding-v3），每篇文档按段落批量向量化一次（价格极低），失败自动降级为 TokenTextSplitter。

> 注意：所有大模型 Key（DeepSeek / DashScope）均已从环境变量读取，`application.yaml` 中不再含明文密钥，可安全提交到仓库。
> Hybrid Search 依赖 Milvus 服务端 2.5+ 的 BM25 稀疏向量与内置 analyzer；项目通过 pom 的 `milvus-sdk.version`（2.6.23）覆盖 Spring AI 传递引入的旧版 SDK（2.5.8，仅 v1 客户端、不支持 BM25）。

---

## 快速开始

### 1. 启动基础服务（Docker）

```bash
cd docker
docker-compose up -d
```

会启动：Milvus 2.6.0（+ etcd）、MinIO（9002/9003，bucket `knowledge-documents` 自动创建）、Attu 管理界面（http://localhost:8000）。

> 仓库中的 compose 未包含 MySQL 服务，需自行准备 MySQL 8.x，并依次执行：
> 1. `src/main/resources/sql/init.sql` — 初始化业务表结构；
> 2. `sql/init.sql` — 初始化权限表（`sys_role` / `sys_user_role` / `kb_member` / `kb_access_log`）。

### 1.1 默认账号

应用启动时 `DataInitializer` 会自动初始化 `ADMIN` 角色及超级管理员账号：

| 账号 | 密码 | 说明 |
|------|------|------|
| `admin` | `admin123` | 超级管理员（建议首次登录后立即修改密码） |

> 存量知识库升级提示：已有知识库若没有 `kb_member` 记录，普通用户将看不到它们。可在 `DataInitializer` 或迁移脚本中为存量库的 `create_user` 自动补插 OWNER 记录。

### 2. 配置环境变量 / 密钥

大模型 Key 通过环境变量注入（`application.yaml` 中无明文密钥）：

| 环境变量 | 用途 |
|----------|------|
| `DEEPSEEK_API_KEY` | DeepSeek 对话模型（`deepseek-chat`） |
| `DASHSCOPE_API_KEY` | DashScope 向量模型（`text-embedding-v3`）与重排序（`gte-rerank-v2`） |
| `ALIYUN_OCR_AK` / `ALIYUN_OCR_SK` | 阿里云 OCR AccessKey（仅扫描版 PDF 需要，需在[阿里云 OCR 控制台](https://ocr.console.aliyun.com/)开通服务） |

设置示例（Windows cmd）：

```bat
set DEEPSEEK_API_KEY=sk-xxxx
set DASHSCOPE_API_KEY=sk-xxxx
set ALIYUN_OCR_AK=xxxx
set ALIYUN_OCR_SK=xxxx
```

Linux/macOS：

```bash
export DEEPSEEK_API_KEY=sk-xxxx
export DASHSCOPE_API_KEY=sk-xxxx
export ALIYUN_OCR_AK=xxxx
export ALIYUN_OCR_SK=xxxx
```

另外在 `application.yaml` 中确认：
- MySQL 连接与账号密码
- MinIO 连接（默认 `minioadmin/minioadmin`，9002 端口）

> 不需要 OCR / Rerank / Hybrid 时，可分别将 `rag.ocr.enabled`、`rag.rerank.enabled`、`rag.hybrid.enabled` 置为 `false`。

> **重要（已有数据的升级提示）**：升级到 Hybrid Search 后，新创建的知识库 collection 自动包含 BM25 字段（`text`/`sparse`）。由旧版本创建的 collection 缺少这些字段，应用会输出 warn 日志并**自动降级为纯向量检索**；如需启用 Hybrid，请删除旧 collection（或删除知识库后重建）并重新上传文档。

### 3. 启动应用

```bash
# Windows
mvnw.cmd spring-boot:run

# Linux/macOS
./mvnw spring-boot:run
```

### 4. 访问页面

- 登录/注册：http://localhost:8080/login.html
- 问答/上传：http://localhost:8080/index.html

---

## REST API 一览

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/api/register` | 否 | 用户注册，返回 JWT |
| POST | `/api/login` | 否 | 登录，返回 JWT |
| GET | `/api/user` | 是 | 当前登录用户信息 |
| POST | `/api/logout` | 是 | 登出（无状态，前端清 Token） |
| GET | `/api/users/search?keyword=` | 是 | 用户模糊搜索（授权时选人） |
| POST | `/api/knowledge-base` | 是 | 创建知识库（创建者自动 OWNER） |
| GET | `/api/knowledge-base` | 是 | 可见知识库列表（按 kb_member 过滤） |
| DELETE | `/api/knowledge-base/{id}` | 是 | 删除知识库（需 OWNER，同时删 Milvus collection） |
| GET | `/api/knowledge-base/{id}/members` | 是 | 成员列表（需 OWNER） |
| POST | `/api/knowledge-base/{id}/members` | 是 | 授权/调整成员角色（需 OWNER，body `{userId, role}`） |
| DELETE | `/api/knowledge-base/{id}/members/{userId}` | 是 | 移除成员（需 OWNER，最后一个 OWNER 不可移除） |
| POST | `/api/knowledge-document/upload` | 是 | 上传 PDF（需 EDITOR，multipart 字段 `file` + `knowledgeBaseId`） |
| POST | `/api/knowledge-document/chat` | 是 | 知识问答（需 VIEWER，`{"question","knowledgeBaseId"}`） |
| DELETE | `/api/knowledge-document/{id}` | 是 | 删除文档（需 EDITOR，对象级校验） |
| GET | `/api/knowledge-document/{id}/download` | 是 | 下载原始文件（需 VIEWER，对象级校验） |
| GET | `/api/knowledge-document/list` | 是 | 文档列表（按可见知识库过滤） |

---

## 关键设计决策

1. **模板方法模式**：`KnowledgeDocumentService` 抽象类固化摄取 8 步流程，子类只需实现 `parseDocument` / `splitDocument`，便于扩展 Word、Markdown 等格式。
2. **文件后置上传**：原始文件仅在解析、切分、向量化全部成功后写入对象存储，避免脏数据。
3. **版本平滑下线**：同名文档重传自动递增版本，旧版本 TTL 内仍可检索，避免"删旧传新"的中断。
4. **两阶段检索（Hybrid + Rerank）**："先宽后精"——第一阶段用 **Hybrid Search**（Milvus Dense 语义向量 + BM25 全文关键词双路召回，RRF 融合）召回 20 条候选，弥补纯向量检索对"关键词精确命中"的盲区；第二阶段由百炼 gte-rerank 精排取 5 条，任一路失败均自动降级，兼顾效果与可用性。
5. **OCR 兜底**：PDF 文本层缺失的页面自动渲染为图片识别文字，扫描版文档与文本型文档走同一条 RAG 链路。
6. **语义切片（自研）**：Spring AI 2.0 已移除 `SemanticTextSplitter`，自行实现"段落 embedding 聚类 + 相邻相似度断点"的语义分块，避免固定 token 硬切导致的主题割裂；失败自动降级 `TokenTextSplitter`。
7. **标题感知注入**：识别数字/中文序数/无序号标题行构建标题链，将所属标题以 `【标题链】正文` 前缀注入 chunk 文本（参与向量化，孤立 chunk 也有上下文）并写 `metadata.heading` 供溯源。
8. **来源溯源**：回答中标注 `[来源n]` 并返回引用列表，LLM 未实际引用的来源会被过滤，保证溯源精准。
9. **三存储独立容错**：删除文档时 MySQL/MinIO/Milvus 各自独立处理，单个失败不阻断整体。
10. **显式 Bean 限定**：多模型场景下用 `@Qualifier` 明确 Chat 与 Embedding 的装配关系。
11. **纵深防御防越权**：① `@RequireKbRole` AOP 注解拦截入口；② Service 层 `assertRole` 守卫核心业务（含对象级——先查文档所属知识库再校验）；③ 列表/下拉强制按可见知识库集合过滤。三层任一独立可拦截越权。
12. **数据授权为唯一权威**：`kb_member` 表（用户×知识库×角色）是知识库访问的唯一判定依据，`ADMIN` 全局放行；不信任前端传参（创建人取自登录态），并保护最后一个 OWNER 不可被移除。
13. **角色双轨模型**：垂直 RBAC（`sys_user_role` 全局角色）+ 水平数据授权（`kb_member`），分离"能访问哪些库"与"在库内能做什么"；权限与文档处理策略完全解耦。
