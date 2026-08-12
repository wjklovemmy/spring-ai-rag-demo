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
  - [4. 用户认证（JWT）](#4-用户认证jwt)
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
| 向量数据库 | Milvus 2.6.0 | 向量存储与相似度检索 |
| 数据库 | MySQL 8.x + MyBatis-Plus 3.5.16 | 元数据 / 用户 / chunk 文本持久化 |
| 对象存储 | MinIO 8.5.12 | 原始文档文件存储（同时支持本地磁盘模式） |
| 认证 | JWT（jjwt 0.12.6）+ BCrypt | 无状态登录认证 |
| PDF 解析 | Spring AI `PagePdfDocumentReader` | 按页解析 PDF |
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
        SVC["Service 层<br/>ingest 模板流程 / chat 问答 / 文档删除"]
        PARSER["Parser<br/>PagePdfDocumentReader"]
        SPLIT["TokenTextSplitter<br/>按岗位配置分块"]
        EMBED["DashScopeEmbeddingModel<br/>text-embedding-v3"]
        CHAT["ChatClient<br/>deepseek-chat"]
    end

    subgraph Storage["存储层"]
        MYSQL[("MySQL<br/>文档/Chunk/用户元数据")]
        MILVUS[("Milvus<br/>向量库 kb_{id}")]
        MINIO[("MinIO<br/>原始 PDF 文件")]
    end

    AI["DeepSeek API"]
    DS["DashScope API"]

    LOGIN --> CTRL
    INDEX --> AUTH --> CTRL
    CTRL --> SVC
    SVC --> PARSER --> SPLIT
    SPLIT --> MYSQL
    SPLIT --> EMBED --> DS
    EMBED --> MILVUS
    SVC --> CHAT --> AI
    SVC --> MINIO
    SVC --> MYSQL
    SVC --> MILVUS
```

**模型分工（Bean 显式限定避免歧义）：**

| 角色 | 模型 | 提供商 | Bean 名 |
|------|------|--------|---------|
| 对话 / 生成 | `deepseek-chat` | DeepSeek API | `deepSeekChatModel` |
| 向量化 | `text-embedding-v3` | DashScope（阿里云） | 自定义 `DashScopeEmbeddingModel` |

> Spring AI 2.0 未内置 DashScope Embedding Starter，项目自研了 `DashScopeEmbeddingModel`（继承 `AbstractEmbeddingModel`），直接调用 DashScope REST API，输出 1024 维向量。

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
    │   │   │   ├── AuthController.java            # 注册/登录/登出/当前用户
    │   │   │   ├── KnowledgeBaseController.java   # 知识库管理
    │   │   │   └── KnowledgeDocumentController.java # 上传/问答/删除
    │   │   ├── embedding/
    │   │   │   └── DashScopeEmbeddingModel.java   # 自研 DashScope 向量模型
    │   │   ├── entity/                            # MyBatis-Plus 实体
    │   │   │   ├── KnowledgeBaseEntity.java
    │   │   │   ├── KnowledgeDocumentEntity.java   # 含 version/position 等
    │   │   │   ├── KnowledgeChunkEntity.java
    │   │   │   └── UserEntity.java
    │   │   ├── mapper/                            # MyBatis-Plus Mapper
    │   │   │   ├── KnowledgeBaseMapper.java
    │   │   │   ├── KnowledgeDocumentMapper.java
    │   │   │   ├── KnowledgeChunkMapper.java
    │   │   │   └── UserMapper.java
    │   │   ├── parser/
    │   │   │   ├── DocumentParser.java             # 解析接口
    │   │   │   └── PdfDocumentParser.java          # PDF 解析实现
    │   │   └── service/
    │   │       ├── KnowledgeDocumentService.java   # 摄取模板方法 + 问答（抽象类）
    │   │       ├── PdfKnowledgeDocumentServiceImpl.java # PDF 摄取实现（岗位分类）
    │   │       ├── VectorStoreService.java         # Milvus 向量增删查
    │   │       ├── FileStorageService.java         # 文件存储接口
    │   │       ├── MinioFileStorageService.java    # MinIO 实现
    │   │       ├── LocalFileStorageService.java    # 本地磁盘实现
    │   │       ├── KnowledgeBaseService.java       # 知识库服务接口
    │   │       ├── KnowledgeDocumentEntityService.java
    │   │       ├── KnowledgeChunkEntityService.java
    │   │       ├── UserService.java                # 注册/登录（JWT + BCrypt）
    │   │       └── impl/                           # Service 实现类
    │   └── resources/
    │       ├── application.yaml                    # 全局配置
    │       ├── static/
    │       │   ├── login.html                      # 登录/注册页
    │       │   └── index.html                      # 问答/上传仪表盘
    │       └── sql/init.sql                        # 建表语句
    └── test/
```

---

## 核心业务流程

### 1. 文档上传与摄取（Ingestion）

`KnowledgeDocumentService.ingest()` 是摄取流程的**模板方法**（`@Transactional(rollbackFor = Exception.class)`），由子类 `PdfKnowledgeDocumentServiceImpl` 实现解析/切分细节：

```
上传 PDF
  │
  ├─ ① saveDocumentInfo   写入 MySQL knowledge_document
  │       · 同知识库同名文件 → 自动推断递增版本号 v1/v2/v3...
  │       · 状态置为 0（上传中）
  │
  ├─ ② parseDocument      按页解析 PDF（PagePdfDocumentReader）
  │
  ├─ ③ splitDocument      按岗位配置分块（TokenTextSplitter）
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

`KnowledgeDocumentService.chat(question, knowledgeBaseId)`：

```
用户问题
  │
  ├─ ① 向量检索   问题 → DashScope 向量化 → Milvus 相似度检索
  │       topK=5，相似度阈值 0.3
  │
  ├─ ② 过滤       过滤已过期版本文档的检索结果
  │
  ├─ ③ 取回文本   按 chunk_id 从 MySQL 回查完整 chunk 内容
  │
  ├─ ④ 组装上下文 按检索顺序拼接，标注 [来源n] 文档名 + 页码
  │
  ├─ ⑤ LLM 生成   上下文注入系统提示词（仅依据知识库回答），DeepSeek 生成答案
  │
  └─ ⑥ 来源溯源   从回答中正则提取实际引用的 [来源n]，返回精准来源列表
```

**返回结构**：`{ answer, sources: [{documentId, documentName, pageNo, snippet}] }`
前端可将 `sources` 渲染为可下载/可跳转的引用来源。

### 3. 文档删除

`KnowledgeDocumentService.deleteDocument(documentId)` 三存储独立容错：

1. MySQL：删除 `knowledge_chunk` + `knowledge_document`（同事务，原子性）
2. MinIO：删除原始文件（异常仅记日志，不阻断）
3. Milvus：按文档 ID 删除向量（异常仅记日志，不阻断）

### 4. 用户认证（JWT）

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

访问  GET /api/user        请求头携带 Authorization: Bearer <token>
                             ↓
                     JwtAuthenticationFilter
                     · 校验 Token → 注入 request 属性 username/userId
                     · 放行 register/login/logout，拦截其余 /api/**
```

前端 `index.html` 通过 `fetchApi()` 统一在请求头注入 Token，登出时清除 `localStorage`。

---

## 数据库设计

初始化脚本：`src/main/resources/sql/init.sql`（需手动在 MySQL 执行一次）

| 表 | 用途 | 关键字段 |
|----|------|----------|
| `knowledge_base` | 知识库 | name(唯一)、description、status、create_user |
| `knowledge_document` | 文档元数据 | knowledge_id(FK)、file_name、file_path、file_size、file_type、chunk_count、embedding_model、status(0上传中/1解析中/2Embedding中/3成功/4失败)、**version**、**position**（岗位）、**expire_time**（旧版本下线时间）、**is_active** |
| `knowledge_chunk` | 文本分块 | document_id(FK)、chunk_index、content(LONGTEXT)、content_hash(SHA-256)、token_count、page_no、milvus_id |
| `knowledge_embedding_task` | 向量化任务 | task_no(唯一)、document_id(FK)、status、total/success/fail_chunk、retry_count、error_message、cost_time |
| `sys_user` | 系统用户 | username(唯一)、password(BCrypt)、nickname、email、status |

---

## 配置说明

`application.yaml` 关键配置：

| 配置项 | 说明 |
|--------|------|
| `spring.ai.deepseek.*` | DeepSeek base-url / api-key / 模型 / 温度 |
| `spring.ai.dashscope.*` | DashScope api-key / embedding 模型 |
| `spring.ai.vectorstore.milvus.*` | Milvus 连接、索引类型（IVF_FLAT/COSINE）、维度 1024 |
| `spring.datasource.*` | MySQL 连接（`knowledge_base` 库） |
| `rag.storage.type` | `minio` / `local` 文件存储切换 |
| `rag.storage.minio.*` | MinIO endpoint / 密钥 / bucket |
| `rag.document.version-ttl-days` | 旧版本文档共存天数（默认 30） |
| `rag.document.upload-dir` | 本地存储模式上传目录 |
| `rag.positions.*` | 按岗位（dev/finance/hr）独立配置分块参数 |
| `jwt.secret` / `jwt.expiration-ms` | JWT 密钥（≥32 字节）与过期时间 |

> 注意：`application.yaml` 中已配置真实 API Key（DeepSeek / DashScope），请勿提交到公开仓库；生产环境建议改用环境变量。

---

## 快速开始

### 1. 启动基础服务（Docker）

```bash
cd docker
docker-compose up -d
```

会启动：Milvus 2.6.0（+ etcd）、MinIO（9002/9003，bucket `knowledge-documents` 自动创建）、Attu 管理界面（http://localhost:8000）。

> 仓库中的 compose 未包含 MySQL 服务，需自行准备 MySQL 8.x，并执行 `src/main/resources/sql/init.sql` 初始化表结构。

### 2. 配置环境变量 / 密钥

在 `application.yaml` 中确认：
- DeepSeek API Key
- DashScope API Key（`text-embedding-v3`）
- MySQL 连接与账号密码
- MinIO 连接（默认 `minioadmin/minioadmin`，9002 端口）

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
| POST | `/api/knowledge-document/upload` | 是 | 上传 PDF（multipart，字段 `file`） |
| POST | `/api/knowledge-document/chat` | 是 | 知识问答（`{"question": "..."}`） |
| DELETE | `/api/knowledge-document/{id}` | 是 | 删除文档 |
| GET/POST | `/api/knowledge-base/**` | 是 | 知识库管理 |

---

## 关键设计决策

1. **模板方法模式**：`KnowledgeDocumentService` 抽象类固化摄取 8 步流程，子类只需实现 `parseDocument` / `splitDocument`，便于扩展 Word、Markdown 等格式。
2. **文件后置上传**：原始文件仅在解析、切分、向量化全部成功后写入对象存储，避免脏数据。
3. **版本平滑下线**：同名文档重传自动递增版本，旧版本 TTL 内仍可检索，避免"删旧传新"的中断。
4. **来源溯源**：回答中标注 `[来源n]` 并返回引用列表，LLM 未实际引用的来源会被过滤，保证溯源精准。
5. **三存储独立容错**：删除文档时 MySQL/MinIO/Milvus 各自独立处理，单个失败不阻断整体。
6. **显式 Bean 限定**：多模型场景下用 `@Qualifier` 明确 Chat 与 Embedding 的装配关系。
