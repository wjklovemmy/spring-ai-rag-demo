# gateway

RAG 服务（`spring-ai-rag` 模块，端口 8080）的统一入口网关，作为聚合工程的第二个子模块。

## 技术栈

- Spring Boot 4.0.7 + Spring Cloud 2025.0.0（Java 17）
- Spring Cloud Gateway（响应式 WebFlux，非阻塞）

## 功能

- **路由**：`/api/**` 全部透传到 RAG 服务（`http://localhost:8080`），路径前缀保持不变
- **CORS**：全局跨域配置，前端可直接跨域访问网关
- **日志**：全局过滤器记录每个请求的方法、路径、耗时与状态码
- **限流**：内置按客户端 IP 的 `RequestRateLimiter` 配置（依赖 Redis，默认注释，按需启用）

## 启动

在仓库根目录用 `-pl` 指定模块启动：

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

# 透传登录接口（RAG 服务返回 200 且会话建立）
curl -X POST http://localhost:8081/api/login -H "Content-Type: application/json" -d "{\"username\":\"admin\",\"password\":\"123456\"}"
```
