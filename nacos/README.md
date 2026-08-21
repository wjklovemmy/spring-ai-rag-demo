# Nacos 注册中心 + 配置中心

本项目通过 Spring Cloud Alibaba 2025.1.0.0（适配 Spring Cloud 2025.1 / Spring Boot 4.0.x）接入 Nacos，三个服务统一注册与拉取共享配置。

## 服务拓扑（Nacos 视角）

```
                 Nacos Server :8848（注册中心 + 配置中心）
                        ▲ 注册 ▲ 配置 ▲
        ┌───────────────┼───────┼───────────────┐
    gateway         spring-ai-rag          spring-ai-user
   (8081, lb://路由)   (8080)                 (8082)
```

- **服务注册与发现**：三个服务以 `spring.application.name` 为服务名注册到 Nacos；网关路由与内部调用全部改用 `lb://服务名`，由 Spring Cloud LoadBalancer 从 Nacos 解析实例，不再硬编码 `localhost:8080/8082`。
- **配置中心**：三端共享密钥上收 Nacos 公共配置 `common.yaml`（group=DEFAULT_GROUP），各服务 `spring.config.import: optional:nacos:common.yaml` 拉取；本地 yaml 保留兜底值，Nacos 不可用时服务仍可启动。

## 启动 Nacos

```bash
cd docker
docker compose up -d nacos
```

- 控制台：http://localhost:8848/nacos（默认账号 `nacos/nacos`）
- 客户端需连通 8848（HTTP）与 9848（gRPC），docker-compose 已映射

## 初始化配置中心

在 Nacos 控制台 → 配置管理 → 配置列表 → 新建配置：

| Data ID | Group | 格式 | 内容 |
|---------|-------|------|------|
| `common.yaml` | `DEFAULT_GROUP` | YAML | 三端共享密钥（jwt.secret / gateway.internal-token / internal-token），见 [`common.yaml`](./common.yaml) |

> 密钥为演示值。生产环境请在 Nacos 控制台修改并同步更新三个服务的本地兜底值。

## 验证

```bash
# 依次启动三个服务后，控制台"服务管理 → 服务列表"应看到三个服务
curl http://localhost:8848/nacos/v1/ns/instance/list?serviceName=spring-ai-rag
curl http://localhost:8848/nacos/v1/ns/instance/list?serviceName=spring-ai-user
curl http://localhost:8848/nacos/v1/ns/instance/list?serviceName=gateway
```

访问 http://localhost:8081/api/login 等网关入口，链路仍与改造前一致（注册中心只影响服务寻址方式）。
