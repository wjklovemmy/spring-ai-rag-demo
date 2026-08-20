# CODEBUDDY.md This file provides guidance to CodeBuddy when working with code in this repository.

## Project Overview

This is a **RAG (Retrieval-Augmented Generation)** demonstration application built on Spring Boot 4.0.7 and Spring AI 2.0.0 (Java 17). It ingests PDF documents into a Milvus vector store, then answers user questions by retrieving relevant document chunks and feeding them as context to the DeepSeek LLM.

## Build & Run Commands

The repository root is an aggregator POM (`packaging=pom`). It has two modules:
- `spring-ai-rag` — the RAG service (port 8080)
- `gateway` — Spring Cloud Gateway entry point (port 8081, routes `/api/**` to `spring-ai-rag`)

Build/run with the `-pl <module>` flag from the root:

```bash
# Build the whole project (skip tests)
./mvnw clean package -DskipTests

# Build a single module (e.g. spring-ai-rag or gateway)
./mvnw -pl spring-ai-rag clean package -DskipTests

# Run the RAG application (defaults to port 8080)
./mvnw -pl spring-ai-rag spring-boot:run

# Run the gateway (defaults to port 8081)
./mvnw -pl gateway spring-boot:run

# Run all tests
./mvnw test

# Run a single test class
./mvnw -pl spring-ai-rag test -Dtest=SpringAiRagDemoApplicationTests

# Run a specific test method
./mvnw -pl spring-ai-rag test -Dtest=SpringAiRagDemoApplicationTests#contextLoads
```

On Windows, replace `./mvnw` with `mvnw.cmd`. The `gateway` module manages its own `spring-cloud-dependencies` BOM (2025.0.0) since the parent POM does not declare it.

## Prerequisites

Before running, ensure the following services are available:

- **Milvus** vector database on `localhost:19530` (default database, collection `knowledge_document`)
- **DeepSeek API** key configured in `application.yaml`
- **DashScope (Alibaba Cloud) API** key via environment variable `DASHSCOPE_API_KEY`
- **MySQL** database (MyBatis-Plus is a dependency but no mapper is currently defined)

## Architecture

### Package Structure

```
com.example.springairagdemo
├── config/          — AI bean wiring (ChatClient, model qualification)
├── controller/      — REST API endpoints
├── embedding/       — Custom EmbeddingModel implementation
├── entity/          — MyBatis-Plus entity (knowledge_base table)
└── service/         — Core RAG ingestion and Q&A logic
```

### RAG Pipeline (Two Phases)

**Phase 1 — Document Ingestion (`KnowledgeDocumentService.ingestPdf`):**

1. Uploaded PDF is written to a temp file
2. `PagePdfDocumentReader` (Spring AI PDF reader) parses the PDF into `Document` objects (one per page)
3. `TokenTextSplitter` chunks the documents (chunk size 800 tokens, min 350 chars, max 10,000 chunks)
4. Each chunk is embedded via `DashScopeEmbeddingModel` and stored in Milvus

**Phase 2 — Question Answering (`KnowledgeDocumentService.chat`):**

1. User question is embedded and used to perform similarity search against Milvus (topK=5, threshold=0.3)
2. Retrieved document texts are joined as context
3. Context is injected into a Chinese system prompt instructing the LLM to answer strictly from the knowledge base
4. `ChatClient` (backed by DeepSeek `deepseek-chat`) generates the final answer

### Model Separation

The application uses two distinct AI models with explicit qualification to avoid Spring bean ambiguity:

| Role | Model | Provider | Bean Name |
|------|-------|----------|------------|
| Chat / Generation | `deepseek-chat` | DeepSeek API | `deepSeekChatModel` |
| Embedding / Vectorization | `text-embedding-v3` | DashScope (Alibaba) | Custom `AbstractEmbeddingModel` |

`AiConfig` creates a `ChatClient` bean explicitly qualified with `@Qualifier("deepSeekChatModel")`. This is necessary because auto-configuration may register multiple `ChatModel` beans.

### Custom Embedding Model

`DashScopeEmbeddingModel` extends `AbstractEmbeddingModel` and directly calls the DashScope REST API (`https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding`). It was built because Spring AI 2.0 does not ship a built-in DashScope embedding starter. The model outputs **1024-dimensional** vectors (`text-embedding-v3`).

### Configuration (`application.yaml`)

Key settings:
- Multipart file upload limit: 50MB
- DeepSeek base URL, API key, model name, and temperature (0.8)
- DashScope API key from environment variable with fallback
- Milvus connection at `localhost:19530`, database `default`, collection `knowledge_document`, IVF_FLAT index with COSINE metric
- `initialize-schema: true` ensures Milvus auto-creates the collection on first run

### REST API

**RAG endpoints** (`KnowledgeDocumentController`, base path `/api/knowledge-document`):

- **`POST /api/knowledge-document/upload`** — Upload a PDF (multipart form, field name `file`). Returns `{success, message, fileName, chunkCount}`.
- **`POST /api/knowledge-document/chat`** — Send a question (JSON body `{"question": "..."}`). Returns `{success, question, answer}`.

**Auth endpoints** (`AuthController`, RAG 侧负责签发 JWT，校验集中在网关):

- **`POST /api/login`** — Accepts `{username, password}`, validates via BCrypt, returns a JWT access token.
- **`GET /api/user`** — Returns current logged-in user info (from JWT claims injected by the gateway), or 401.
- **`POST /api/logout`** — Revokes the access token (adds to Redis blacklist).

### Gateway Authentication (RAG 不直接对外暴露)

所有 `/api/**` 请求统一经 `gateway`（8081）进入：

1. `JwtAuthGlobalFilter`（gateway）按与 RAG 一致的 `jwt.secret` 校验 `Authorization: Bearer <token>`，查询 Redis 黑名单，白名单放行 `register/login/logout/refresh`，并注入 `X-User-Id` / `X-Username` / `X-Gateway-Token` 请求头。
2. `GatewayIdentityFilter`（RAG）校验 `X-Gateway-Token`（共享 `gateway.internal-token`，防绕过网关直连伪造身份），随后构造 `LoginUser` 写入 `UserContext`（ThreadLocal），请求结束 `finally` 清理。
3. 前端页面由 RAG 在 8080 提供，页面内置 `API_BASE = 'http://localhost:8081'`，所有接口请求自动经网关（跨域由网关 `globalcors` 统一放行）。

### Static Frontend

Two pure HTML pages served from `/static` on port **8080** (all their API calls go to the gateway on **8081** via the `API_BASE` constant):

- `login.html` — Login form with animated background, calls `POST /api/login`
- `index.html` — Dashboard with sidebar navigation (Home, Knowledge Q&A, Upload Document tabs), checks auth via `GET /api/user`, calls `POST /api/knowledge-document/chat` and `POST /api/knowledge-document/upload`

### MyBatis-Plus & MySQL

The `KnowledgeBaseEntity` maps to a `knowledge_base` table with columns: `id`, `name`, `description`, `status`, `create_user`, `create_time`, `update_time`. No mapper interface or service is defined yet — this appears to be scaffolding for a future knowledge-base metadata management feature.

### Key Dependencies

- `spring-ai-starter-model-deepseek` — DeepSeek chat model auto-configuration
- `spring-ai-starter-vector-store-milvus` — Milvus vector store integration
- `spring-ai-pdf-document-reader` — PDF parsing via `PagePdfDocumentReader`
- `mybatis-plus-spring-boot3-starter` 3.5.10.1 — ORM (no mapper defined yet)
- `mysql-connector-j` — MySQL JDBC driver (runtime scope)
- Lombok for boilerplate reduction
- `spring-boot-devtools` for hot reload during development
