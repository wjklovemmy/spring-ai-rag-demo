# CODEBUDDY.md This file provides guidance to CodeBuddy when working with code in this repository.

## Project Overview

This is a **RAG (Retrieval-Augmented Generation)** demonstration application built on Spring Boot 4.0.7 and Spring AI 2.0.0 (Java 17). It ingests PDF documents into a Milvus vector store, then answers user questions by retrieving relevant document chunks and feeding them as context to the DeepSeek LLM.

The repository is a **three-service microservice demo** behind a single API gateway:
- `spring-ai-rag` — RAG service (port 8080), knowledge base / document / Q&A, own DB `knowledge_base`
- `spring-ai-user` — **standalone user service** (port 8082), auth / JWT / RBAC / system admin, own DB `spring_ai_user`
- `gateway` — Spring Cloud Gateway entry point (port 7070), splits traffic by path to the two services

## Build & Run Commands

The repository root is an aggregator POM (`packaging=pom`). All three modules are **independently deployable services** (the old "spring-ai-user as a shared jar inside RAG" layout has been removed). Build/run with the `-pl <module>` flag from the root:

```bash
# Build the whole project (skip tests)
./mvnw clean package -DskipTests

# Build a single module
./mvnw -pl spring-ai-rag clean package -DskipTests
./mvnw -pl spring-ai-user clean package -DskipTests
./mvnw -pl gateway clean package -DskipTests

# Run the RAG service (port 8080)
./mvnw -pl spring-ai-rag spring-boot:run

# Run the user service (port 8082)
./mvnw -pl spring-ai-user spring-boot:run

# Run the gateway (port 7070, external entry; routes /api/** to the two services)
./mvnw -pl gateway spring-boot:run
```

On Windows, replace `./mvnw` with `mvnw.cmd`. Spring Cloud (`2025.1.0`) and Spring Cloud Alibaba (`2025.1.0.0`, Nacos) BOMs are declared in the parent POM (`spring-cloud-dependencies` / `spring-cloud-alibaba-dependencies`). Note: Spring Cloud Gateway 5.0 (SC 2025.1) renamed the starter to `spring-cloud-starter-gateway-server-webflux`.

## Prerequisites

Before running, ensure the following services are available:

- **Nacos** on `localhost:8848` (registry + config center, via `docker-compose up -d nacos`, console `http://localhost:8090/nacos`, default `nacos/nacos`). All three services register here; gateway `lb://` routes and OpenFeign service-to-service calls (`UserFeignClient` / `RagSyncFeignClient`) resolve instances via Nacos. Shared secrets may be published to config center data-id `common.yaml` (see `nacos/common.yaml`), otherwise local fallbacks apply.
- **Milvus** vector database on `localhost:19530` (default database, per-knowledge-base dynamic collections `kb_{id}`)
- **DeepSeek API** key via environment variable `DEEPSEEK_API_KEY`
- **DashScope (Alibaba Cloud) API** key via environment variable `DASHSCOPE_API_KEY`
- **MySQL** — two schemas on one instance: RAG business DB `knowledge_base` (`sql/init.sql`) + user DB `spring_ai_user` (`sql/user.sql`, RBAC five tables)
- **Redis** — used by gateway (token blacklist) and user service (refresh-token sessions)

The three services' `application.yaml` share three secrets that MUST match everywhere:
`jwt.secret` (gateway ↔ user service), `gateway.internal-token` (gateway → downstream `X-Gateway-Token`), `internal-token` (RAG ↔ user service internal calls `X-Internal-Token`).
These are normally served from the Nacos config center `common.yaml` (higher precedence when Nacos is reachable); local values are fallbacks so services still boot without Nacos.

## Architecture

### Service Topology

```
Browser (Vue SPA served by spring-ai-web/ nginx :9004, same-origin /api → gateway)
        │
        ▼
gateway :7070 (JwtAuthGlobalFilter: whitelist register/login/logout/refresh,
               validate JWT + Redis blacklist, inject X-User-Id / X-Username /
               X-Permissions / X-Gateway-Token, route by path via Nacos lb://)
        ├── /api/login,/api/register,/api/refresh,/api/logout,/api/user,/api/users/**,/api/admin/**  → lb://spring-ai-user
        └── other /api/** (knowledge-base, knowledge-document)                                      → lb://spring-ai-rag

Nacos :8848 (registry + config center)  ← all three services register (spring.application.name)

spring-ai-rag :8080         spring-ai-user :8082
  GatewayIdentityFilter       GatewayIdentityFilter
  (consume identity headers   (consume identity headers
   → local UserContext)        → user-domain UserContext)
        │                              │
        │  UserClient (isAdmin,        │  RagSyncClient (deletion-check,
        │  user briefs)                │  user-cleanup, audit)
        └──── /internal/users/** ◄─────┘  └──── /internal/kb/** ◄────────┘
                 (user service)               (RAG service internal endpoints)
```

- `/internal/**` endpoints do NOT go through the gateway; they authenticate via the `X-Internal-Token` header and are skipped by each service's `GatewayIdentityFilter` (which only guards `/api/**`).
- Service-to-service calls use **OpenFeign** (`UserFeignClient` in RAG, `RagSyncFeignClient` in user service, under each service's `feign/` package): the service name (`spring-ai-user` / `spring-ai-rag`) is resolved through Nacos + Spring Cloud LoadBalancer instead of hard-coded localhost ports. A global `RequestInterceptor` (`FeignConfig`) injects `X-Internal-Token`. Fault tolerance is provided by `feign.circuitbreaker.enabled=true` + Spring Cloud Circuit Breaker (**Sentinel** via `spring-cloud-circuitbreaker-sentinel`, the official Hystrix replacement — Hystrix is EOL and removed from Spring Cloud 2020+), with `fallbackFactory` classes returning safe degradation values (`UserFeignClientFallbackFactory` / `RagSyncFeignClientFallbackFactory`). Sentinel degrade rules are declared in each service's `application.yaml` under `feign.sentinel.rules` (key `default` = all Feign clients, or exact resource name like `spring-ai-user#isAdmin(Long)`; the resource name defaults to the Feign client service name). `sentinel-transport-simple-http` enables optional Dashboard metric reporting (`spring.cloud.sentinel.transport.dashboard`, port 8719).

### Package Structure

```
spring-ai-rag  (com.example.springairagdemo — RAG 业务域, standalone :8080)
├── config/          — AI bean wiring, async task pool, DataSourceConfig(@Primary), DataInitializer(恢复中断任务)
├── controller/      — KnowledgeBase / KnowledgeDocument / InternalController(/internal/kb/**: 删除校验/清理/审计回调)
├── embedding/       — Custom EmbeddingModel implementation
├── entity/          — knowledge_base / knowledge_document / chunk / task / kb_member / kb_access_log
├── mapper/          — MyBatis-Plus mappers (业务表)
├── parser/          — PDF parser + OCR fallback + semantic splitting
├── security/        — 本地安全包（用户域拆分后自建）: KbRole / RequireKbRole / KbAccessAspect /
│                       GatewayIdentityFilter(消费网关头) / LoginUser / UserContext / ForbiddenException
└── service/         — Core RAG ingestion and Q&A logic, KbAuthorizationService,
                       UserClient(远程查用户服务 isAdmin/用户摘要), KbMemberDeletionGuard,
                       KbAccessLogAuditHandler(均改由 InternalController 驱动), SPI 实现

spring-ai-user  (com.example.user — 用户域独立服务 :8082)
├── UserServiceApplication.java — 独立启动类（@MapperScan 用户域 mapper）
├── config/          — JwtUtil / JwtConfig / GatewayIdentityFilter(校验内部令牌 → UserContext)
│                      / RagSyncClient(回调 RAG /internal/kb/** 做删除校验/清理/审计)
├── controller/      — Auth / AdminUser / AdminRole / InternalUserController(/internal/users/**: is-admin、batch)
├── security/        — LoginUser / UserContext / RequireAdmin / AdminAccessAspect / ForbiddenException
├── entity/          — sys_user / sys_role / sys_user_role
├── mapper/          — UserMapper / SysRoleMapper / SysUserRoleMapper
└── service/         — UserService / SysRoleService / SysUserRoleService / RedisRefreshTokenService / UserDataInitializer
```

### RAG Pipeline (Two Phases)

**Phase 1 — Document Ingestion (`KnowledgeDocumentService.ingestPdf`):**

1. Uploaded PDF is written to a temp file
2. `PagePdfDocumentReader` (Spring AI PDF reader) parses the PDF into `Document` objects (one per page)
3. `TokenTextSplitter` (plus heading-aware prefix and semantic splitting) chunks the documents
4. Each chunk is embedded via `DashScopeEmbeddingModel` (auto-retry on network errors / 5xx, max 2 attempts; 4xx business errors not retried) and stored in Milvus (per-knowledge-base collection `kb_{id}`, batched 100)

**Phase 2 — Question Answering (`KnowledgeDocumentService.chat`):**

1. User question is embedded and used for Hybrid Search (Dense + BM25 + RRF) + rerank (gte-rerank-v2) against Milvus
2. Retrieved document texts are joined as context
3. Context is injected into a Chinese system prompt instructing the LLM to answer strictly from the knowledge base
4. `ChatClient` (backed by DeepSeek `deepseek-chat`) generates the final answer with source citations
5. The DeepSeek call is wrapped by a `CircuitBreakerFactory` (Sentinel, resource `ai-chat`, degrade rule registered programmatically in `AiConfig`): on exception/timeout/circuit-open it degrades to `AI服务暂时不可用，请稍后再试` with empty `sources` (HTTP 200) instead of failing with 500

### Model Separation

The application uses two distinct AI models with explicit qualification to avoid Spring bean ambiguity:

| Role | Model | Provider | Bean Name |
|------|-------|----------|------------|
| Chat / Generation | `deepseek-chat` | DeepSeek API | `deepSeekChatModel` |
| Embedding / Vectorization | `text-embedding-v3` | DashScope (Alibaba) | Custom `AbstractEmbeddingModel` |

`AiConfig` creates a `ChatClient` bean explicitly qualified with `@Qualifier("deepSeekChatModel")`. This is necessary because auto-configuration may register multiple `ChatModel` beans.

### Custom Embedding Model

`DashScopeEmbeddingModel` extends `AbstractEmbeddingModel` and directly calls the DashScope REST API (`https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding`). It was built because Spring AI 2.0 does not ship a built-in DashScope embedding starter. The model outputs **1024-dimensional** vectors (`text-embedding-v3`). Embedding calls (`VectorStoreService.embedChunks` / `embedQuery`) are protected by a Sentinel circuit breaker (resource `dashscope-embedding`, degrade rule registered in `AiConfig`): sustained high error ratio fails fast to avoid exhausting the DashScope quota; upload-task errors are normalized to `向量化服务暂时不可用，请稍后重试`; network errors / 5xx are auto-retried (max 2).

### Configuration (`application.yaml`)

**spring-ai-rag (8080):** multipart 50MB; DeepSeek base URL / api-key / model / temperature; DashScope api-key from env; Milvus at `localhost:19530`; MinIO/local storage; OCR; rerank; hybrid retrieval; Nacos (register + config center, `common.yaml` shared keys); `gateway.internal-token`; OpenFeign (`UserFeignClient` → `lb://spring-ai-user`, `feign.circuitbreaker.enabled=true` + Sentinel fallbackFactory, `feign.sentinel.rules`); Sentinel transport (`spring.cloud.sentinel.*`, Dashboard at `localhost:8858` via the docker compose `sentinel-dashboard` service, credentials `sentinel/sentinel`); `internal-token`. No Redis, no JWT config (moved to user service), no `spring.datasource.user.*`.

**spring-ai-user (8082):** MySQL `spring_ai_user` (standard `spring.datasource.*`, MyBatis-Plus auto-configured); Redis (refresh-token sessions); Nacos (register + config center); `jwt.secret` (signing side, must match gateway); `gateway.internal-token`; OpenFeign (`RagSyncFeignClient` → `lb://spring-ai-rag`, `feign.circuitbreaker.enabled=true` + Sentinel fallbackFactory, `feign.sentinel.rules`); Sentinel transport; `internal-token`.

**gateway (7070):** routes split by path with `lb://spring-ai-user` / `lb://spring-ai-rag` (Nacos discovery; starter renamed to `spring-cloud-starter-gateway-server-webflux` in Gateway 5.0); CORS for all origins; `jwt.secret` (validate only); Redis blacklist; Nacos (register + config center); `gateway.internal-token`; `internal-token`.

### Authentication & Authorization Flow

1. `POST /api/login` (whitelisted at gateway) → forwarded to **user service :8082**, which verifies BCrypt and returns Access + Refresh JWT. Access token embeds the user's **permission codes** (`permissions` claim) — "权限码缓存进 JWT", so downstream authorization never re-queries the DB.
2. All other `/api/**` requests carry `Authorization: Bearer <token>`; the gateway validates signature + Redis blacklist, then injects `X-User-Id` / `X-Username` / `X-Permissions` (parsed from JWT) / `X-Gateway-Token` and forwards.
3. Downstream services (`spring-ai-rag` and `spring-ai-user`, each with a local `GatewayIdentityFilter` + `UserContext`) verify `X-Gateway-Token`, build `LoginUser`, and set `UserContext` (ThreadLocal, cleared in `finally`).
4. **Data authorization (RAG)**: `kb_member` (user × knowledge base × VIEWER/EDITOR/OWNER) is the single source of truth for knowledge-base access; `ADMIN` global role (queried remotely via `UserClient.isAdmin`) bypasses it. `KbAccessAspect` + `KbAuthorizationService.assertRole` enforce at AOP and service layers.
5. **Cross-service cleanup**: when the user service deletes a user, it calls RAG `POST /internal/kb/deletion-check` (last-OWNER protection, 409 blocks deletion) then `/internal/kb/user-cleanup` (remove `kb_member` rows); admin operations are audited to `kb_access_log` via `POST /internal/kb/audit` (operator passed explicitly).

### REST API

**RAG endpoints** (via gateway → 8080, base `/api/knowledge-*`): upload / chat / task polling / list / delete / download / knowledge-bases dropdown.

**Auth endpoints** (via gateway → 8082): `POST /api/login` (JWT), `POST /api/register`, `POST /api/logout`, `POST /api/refresh`, `GET /api/user`, `GET /api/users/search`.

**Admin endpoints** (via gateway → 8082): `/api/admin/users/**` (CRUD, enable/disable, reset password, assign roles) and `/api/admin/roles/**` (CRUD) — all `@RequireAdmin`.

**Internal endpoints** (NOT via gateway, `X-Internal-Token` required): user service `/internal/users/{id}/is-admin`, `/internal/users/batch`; RAG `/internal/kb/deletion-check`, `/internal/kb/user-cleanup`, `/internal/kb/audit`.

### Frontend (spring-ai-web/ — Vue 3 SPA)

Frontend-backend separation: the frontend was extracted from the RAG service's `/static` into a standalone **Vue 3 + Vite** project `spring-ai-web/` (deployed independently, see `spring-ai-web/README.md` and `spring-ai-web/nginx.conf`). All API calls go to the gateway on **7070** via the `API_BASE` constant in `src/api/request.js` (`''` for same-origin Nginx proxy, or `http://localhost:7070` for direct calls with gateway CORS). Vite dev server proxies `/api` → `http://localhost:7070` (`vite.config.js`).

- `npm run dev` — Vite dev server (http://localhost:5173, proxy `/api` → 7070; 9000 is taken by docker minio); `npm run build` — production build to `dist/`; production hosting: `docker-compose.yml` service `frontend-nginx` (nginx:1.27-alpine, http://localhost:9004, `/api` reverse-proxied to host gateway 7070 via `host.docker.internal`) or manual Nginx per `nginx.conf`
- `src/views/LoginView.vue` — Login/register page with animated background, calls `POST /api/login`
- `src/views/DashboardView.vue` — Main layout with sidebar navigation (Home, Knowledge Q&A, Upload Document, 系统管理 tabs, lazy-loaded tab components), checks auth via `GET /api/user`
- Tab components — `ChatTab.vue` (`POST /api/knowledge-document/chat`), `UploadTab.vue` (`POST /api/knowledge-document/upload` + task polling), `DocsTab.vue`, `TasksTab.vue`, `KbTab.vue`, `UsersTab.vue`, `RolesTab.vue`, with modals `TaskDetailModal.vue` / `MemberModal.vue` / `RoleAssignModal.vue`
- `src/api/request.js` — Token management, 401 auto-refresh with shared-Promise dedup, download helper; `src/utils/` — `toast.js` / `format.js`

### Databases (two schemas, one MySQL instance, per-service data sources)

- `knowledge_base` (RAG service): `DataSourceConfig` explicitly wires the @Primary `dataSource` + `sqlSessionFactory` + `sqlSessionTemplate`, `@MapperScan("com.example.springairagdemo.mapper")`. Tables: `knowledge_base` / `knowledge_document` / `knowledge_chunk` / `knowledge_embedding_task` / `kb_member` / `kb_access_log`.
- `spring_ai_user` (user service): standard `spring.datasource.*` + MyBatis-Plus auto-config; `@MapperScan` on the application class binds `com.example.user.mapper`. RBAC tables: `sys_user` / `sys_role` / `sys_permission` / `sys_user_role` / `sys_role_permission`.

`kb_member` / `kb_access_log` reference `user_id` from the other DB **logically** (no FK); user deletion triggers RAG cleanup via the `RagSyncClient` ↔ `/internal/kb/**` HTTP contract described above.

### Key Dependencies

- `spring-ai-user` — standalone service (port 8082); RAG no longer depends on it
- `spring-ai-starter-model-deepseek` — DeepSeek chat model auto-configuration (only in spring-ai-rag)
- `spring-ai-starter-vector-store-milvus` — Milvus vector store integration (only in spring-ai-rag)
- `spring-ai-pdf-document-reader` — PDF parsing via `PagePdfDocumentReader` (only in spring-ai-rag)
- `mybatis-plus-spring-boot4-starter` 3.5.16 (+ `mybatis-plus-jsqlparser`) — ORM, declared in **both** RAG and user service. The parent POM `<dependencies>` holds **only** deps shared by all three services (Nacos, loadbalancer, OpenFeign, Sentinel, devtools, lombok); web/AOP/MyBatis-Plus/MySQL/Redis/JWT/BCrypt are module-scoped so that `gateway` (pure WebFlux, no datasource) never inherits `spring-boot-starter-webmvc` or `mybatis-plus` (which would trigger `DataSourceAutoConfiguration` and fail with "Failed to configure a DataSource")
- `mysql-connector-j` — MySQL JDBC driver (runtime scope, declared in RAG and user service)
- Lombok for boilerplate reduction
- `spring-boot-devtools` for hot reload during development
- Spring AI BOM / Milvus SDK version management is declared in `spring-ai-rag/pom.xml` (not the parent)
