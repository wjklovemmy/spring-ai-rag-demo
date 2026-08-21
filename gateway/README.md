# gateway

RAG 项目（`spring-ai-rag` 服务 8080 / `spring-ai-user` 服务 8082）的统一入口网关。

## 技术栈

- Spring Boot 4.0.7 + Spring Cloud 2025.1.0（Java 17）
- Spring Cloud Gateway（响应式 WebFlux，非阻塞，gateway-server 5.0.0，starter 为 `spring-cloud-starter-gateway-server-webflux`）
- Spring Cloud Alibaba 2025.1.0.0（Nacos 注册中心 + 配置中心）

## 功能

- **路由**：按路径分流——认证/用户/角色（`/api/login,/api/register,/api/refresh,/api/logout,/api/user,/api/users/**,/api/admin/**`）→ `lb://spring-ai-user`；其余 `/api/**`（知识库/文档/问答）→ `lb://spring-ai-rag`。目标地址经 Nacos 服务发现解析实例
- **JWT 鉴权**：`JwtAuthGlobalFilter` 校验 Token 签名 + Redis 黑名单，注入 `X-User-Id / X-Username / X-Permissions / X-Gateway-Token` 后转发
- **CORS**：全局跨域配置，前端可直接跨域访问网关
- **日志**：全局过滤器记录每个请求的方法、路径、耗时与状态码
- **限流**：内置按客户端 IP 的 `RequestRateLimiter` 配置（依赖 Redis，默认注释，按需启用）

## 启动

前置：Nacos（`docker-compose up -d nacos`）。在仓库根目录用 `-pl` 指定模块启动：

```bash
# Windows（根目录）
mvnw.cmd -pl gateway spring-boot:run

# Linux/macOS（根目录）
./mvnw -pl gateway spring-boot:run
```

启动后访问：`http://localhost:8081/api/knowledge-document/chat`（经网关转发到 RAG 服务）。

## 启用 IP 限流

1. 启动本地 Redis
2. 取消 `application.yaml` 中 `RequestRateLimiter` 过滤器的注释
3. 重启网关即可（默认每 IP 每秒 10 个请求、突发 20 个）

## 验证路由

```bash
# 健康检查
curl http://localhost:8081/actuator/health

# 透传登录接口（用户服务返回 200 且会话建立）
curl -X POST http://localhost:8081/api/login -H "Content-Type: application/json" -d "{\"username\":\"admin\",\"password\":\"admin123\"}"

# Nacos 中确认网关已注册（应看到 gateway 实例）
curl "http://localhost:8848/nacos/v1/ns/instance/list?serviceName=gateway"
```
