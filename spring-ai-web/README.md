# spring-ai-web — 前端独立部署项目

基于 **Vue 3 + Vite + vue-router** 的单页应用，作为 RAG 演示平台的独立前端工程（前后端分离）。所有 API 通过网关 **7070** 访问。

```
spring-ai-web/
├── index.html              # Vite 入口
├── vite.config.js          # 开发代理 /api → http://localhost:7070
├── nginx.conf              # 生产部署示例（静态托管 dist/ + /api 反代网关）
├── package.json
└── src/
    ├── main.js             # 应用入口
    ├── App.vue             # 根组件（路由视图 + 全局 Toast）
    ├── router/index.js     # hash 路由：#/login（登录）、#/（主页面）
    ├── api/request.js      # API_BASE、Token 管理、401 自动刷新、下载
    ├── utils/              # toast、转义/格式化
    ├── styles/main.css     # 全局样式
    ├── views/
    │   ├── LoginView.vue       # 登录 / 注册（粒子背景，自动恢复会话）
    │   └── DashboardView.vue   # 主布局：侧边栏 + 顶栏 + Tab（KeepAlive 保留状态）
    └── components/
        ├── HomeTab.vue         # 首页
        ├── ChatTab.vue         # 知识问答（带引用来源 / 下载）
        ├── UploadTab.vue       # 上传 PDF（任务轮询 + 分阶段进度条）
        ├── DocsTab.vue         # 文档列表（筛选 / 下载 / 删除）
        ├── TasksTab.vue        # 任务列表（自动刷新）
        ├── TaskDetailModal.vue # 任务详情弹窗
        ├── KbTab.vue           # 知识库管理（创建 / 删除）
        ├── MemberModal.vue     # 知识库成员授权弹窗
        ├── UsersTab.vue        # 用户管理（创建 / 启停 / 重置密码 / 删除）
        ├── RoleAssignModal.vue # 用户角色分配弹窗
        └── RolesTab.vue        # 角色管理（创建 / 编辑 / 删除）
```

## 调用关系

```
浏览器 ──> spring-ai-web 静态服务（dist/，如 :9004）
              │  /api/** 同源代理（nginx.conf 或 vite.config.js 代理）
              ▼
          网关 :7070（JWT 校验 / 按路径分流）
              ├── /api/login,/api/admin/** … → 用户服务 :8082
              └── /api/knowledge-* …         → RAG 服务 :8080
```

页面内 `API_BASE` 常量（`src/api/request.js` 顶部）：

```js
export const API_BASE = ''   // 默认：同源代理，/api/** 由 Nginx / Vite 转发到网关 7070
```

## 环境要求

- Node.js ≥ 18（Vite 5 要求，建议 20+）
- 本机项目级 `.npmrc` 已配置 `include=dev`（见下）

### Windows 环境注意事项

1. **Node 不在 PATH**：若使用 IDE 内置下载的 Node（如 IntelliJ IDEA 下载到 `C:\Users\<user>\AppData\Roaming\JetBrains\IntelliJIdea<版本>\node\versions\<版本>\`），cmd/PowerShell 中需手动将对应目录加入 PATH，或在 IDEA Settings → Languages & Frameworks → Node.js 中将其设为默认解释器。
2. **`NODE_ENV=production` 跳过 devDependencies**：若终端环境（常见于 IDE 运行配置注入）存在 `NODE_ENV=production`，npm 默认不会安装 devDependencies（vite 等缺失，`npm run dev/build` 报 `vite: command not found`）。本项目已在 `spring-ai-web/.npmrc` 写入 `include=dev` 强制安装，无需手动加 `--include=dev`。
3. **端口占用**：docker compose 中 milvus 的 minio 占用 `9000`、doc-minio 占用 `9002/9003`，故前端 dev 端口取 `5173`、生产 Nginx 取 `9004`，均刻意避开。
4. **npm 11 `allow-scripts` 安全机制**：首次安装时可能阻止 esbuild 等包的 postinstall 脚本，若 `npm run dev/build` 报 esbuild 相关错误，执行 `npm approve-scripts --allow-scripts-pending` 后重装。

## 开发调试

```bash
npm install
npm run dev        # http://localhost:5173，/api/** 已代理到网关 7070（9000 已被 docker minio 占用）
```

## 生产构建

```bash
npm run build      # 产物输出到 dist/
```

## 部署

### 方式一：Docker Compose（推荐，同源无跨域）

前端 Nginx 已编排进根目录 `docker/docker-compose.yml`（服务名 `frontend-nginx`，端口 **9004**，`/api` 反代宿主机网关 7070）：

1. 先构建前端：

```bash
npm install && npm run build      # 生成 dist/
```

2. 启动容器：

```bash
docker compose -f docker/docker-compose.yml up -d frontend-nginx
```

3. 访问 `http://localhost:9004`（hash 路由 `#/login` 自动进入登录页）。

> 说明：9000~9003 已被 milvus/doc minio 占用，故前端端口取 9004；容器通过 `host.docker.internal` 访问宿主机网关。

### 方式二：宿主机 Nginx（手动部署）

1. `npm run build` 生成 `dist/`。
2. 修改 `nginx.conf` 三处：`listen`（避开 9000~9003）、`root` 指向 `dist/` 实际路径、`proxy_pass` 改为 `localhost:7070`。
3. 启动：

```bash
nginx -c /path/to/spring-ai-web/nginx.conf
```

4. 访问 `http://localhost:<listen端口>`（hash 路由 `#/login` 自动进入登录页）。

### 方式三：任意静态服务器（需直连网关）

静态服务器（如 `python -m http.server 8081`、`npx serve dist`）只托管页面，**不会代理 `/api`**。此时把 `src/api/request.js` 中的 `API_BASE` 改为网关绝对地址后重新构建：

```js
export const API_BASE = 'http://localhost:7070'   // 网关 CORS 已放行所有来源，可直接跨域调用
```

> 提示：构建产物中 `API_BASE` 已固定，请勿通过 `file://` 直接打开 `dist/index.html`（fetch 受浏览器限制无法正常请求接口）。

## 认证机制

- 登录成功后，Access Token / Refresh Token 存入 `localStorage`。
- `fetchApi` 统一携带 `Authorization: Bearer <token>`；收到 401 时自动用 Refresh Token 换取新令牌并重试一次（共享 Promise，避免并发刷新）。
- 刷新失败或未登录时自动跳转 `#/login`。
- 退出登录调用网关 `/api/logout`（将当前令牌加入 Redis 黑名单）并清理本地凭证。
