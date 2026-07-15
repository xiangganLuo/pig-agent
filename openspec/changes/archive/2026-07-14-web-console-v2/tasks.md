# Tasks: web-console-v2

## 1. 模块骨架（pig-agent-web）

- [x] 1.1 新增 `pig-agent-web` 模块（parent pom `<modules>` + dependencyManagement 条目）；依赖仅 `pig-agent-core` + `jackson-databind`。
- [x] 1.2 `Http` 共享工具（body/segments/json/str）+ `WebJson`（agent/event/chatFrame 投影 + toJson/parse，凭据不外泄）+ `WebContext`（打包 `AgentKernel`，`ofKernel` 工厂）。
- [x] 1.3 单测：`WebJsonTest`（agent 投影 / event 序列化 / parse 容错）。

## 2. 嵌入式 server + 端点 adapter

- [x] 2.1 `WebConsole`：绑 `127.0.0.1`、`start`/`stop`/`boundPort`、daemon 线程池；注册 4 路由（`/api/agents`、`/api/chat`、`/api/events`、`/`）。
- [x] 2.2 `AgentApiHandler`：`GET /api/agents`、`GET/PUT/DELETE /api/agents/{id}`、`POST /api/agents`、`POST /api/agents/{id}/use`、`POST /api/agents/{id}/run` → 委托门面。
- [x] 2.3 `ChatHandler`：`POST /api/chat` 经 `kernel.chat` SSE 流式（reasoning/tool/answer + done）；缺 message → 400。
- [x] 2.4 `EventStreamHandler`：`GET /api/events` 订阅 `subscribeEvents()` → SSE `data:` 帧 + `retry:` 重连；非 GET → 405。
- [x] 2.5 `StaticHandler`：`GET /` → `resources/web/index.html`；禁止路径穿越 + 扩展名白名单。
- [x] 2.6 单测：`WebConsoleTest`（REST 全生命周期 + 仅本机 + 端口释放 + 静态页）、`ChatHandlerTest`（流式 + 400，mock kernel）、`EventStreamHandlerTest`（事件经门面推送）。

## 3. 极简前端（静态资源）

- [x] 3.1 `resources/web/index.html` + `style.css` + `app.js`：聊天框 + 流式答案 + agent 列表/切换 + 事件流（`textContent` 防 XSS）。

## 4. 装配 + 校验

- [x] 4.1 `PigAgentConfig` 恢复 `WebConfig`（`enabled`=false / `host`=127.0.0.1 / `port`=7317）+ `getWeb()`。
- [x] 4.2 `WebLauncher.startIfEnabled(kernel, enabled, host, port)`（原语入参，返回 `Optional<WebConsole>`，日志打印 URL）。
- [x] 4.3 `PigAgentCli` 在 `web.enabled` 时同进程起控制台，与 CLI 共享同一 `AgentKernel`；shutdown hook 停服。cli-pom 加 `pig-agent-web` 依赖。
- [x] 4.4 `mvn -q test` 单线程全绿；`mvn -q -pl pig-agent-cli -am compile` 绿。
