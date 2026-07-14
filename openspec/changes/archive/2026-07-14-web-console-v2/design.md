## Context

`agent-kernel-facade` 就绪后，`AgentKernel`（`listAgents`/`getAgent`/`activeId`/`useAgent`/`createAgent`/`updateAgent`/`deleteAgent`/`chat`/`runNow`/`interruptCurrent`/`subscribeEvents`）+ `KernelEvent` 热 `Flux` 是内核唯一对外面。本变更据「加一个前端 = 加一个 adapter」重新引入 Web 控制台，但**范围最小化**：只做可用的后端 adapter + 极简 UI，不追全能力对等。守个人电脑红线：无 DB / 无多租户 / 无独立部署 / 单用户。

## Goals / Non-Goals

**Goals:**
- 新增 `pig-agent-web` 模块，仅依赖 `pig-agent-core` + Jackson。
- 嵌入式 HTTP server（JDK `com.sun.net.httpserver`），绑 `127.0.0.1`，随进程启停，配置开关默认关。
- 端点：`GET /`（静态）、`POST /api/chat`（SSE 流）、`GET /api/events`（SSE 事件）、`/api/agents`（列/取/建/改/删/切/运行）。
- 极简前端：聊天框 + 流式答案 + agent 列表/切换。
- 凭据永不出现在响应中。

**Non-Goals:**
- CLI 全能力对等（model/session/mcp/task/permission/memory/compress 端点）——留待后续。
- DB / 多租户 / 云托管 / 账号体系 / 复杂鉴权。
- 新增内核逻辑；直连内核内部私有类。
- 中断按钮 / 会话持久化钩子（REPL 的 noteUserMessage→maybeCompress→saveCurrent 回合钩子）——首版不接。

## Decisions

- **D1：Web 是 adapter，只消费 `AgentKernel` 门面。** `WebContext` 只打包 `AgentKernel`（镜像 CLI 的 `ReplContext`，但极简），handler 只做 HTTP↔门面的委托，零内核/业务逻辑，不直连 `AgentRegistry`/`AgentInstanceFactory`/`AgentSpecRepository`/`AgentRunner`。故 `pig-agent-web` 依赖仅 `pig-agent-core`（含门面 + `KernelEvent` + `AgentInstance`/`AgentSpec` 领域类型，及经 agentscope 传递的 Reactor）+ Jackson。
- **D2：嵌入式 JDK server，随进程。** `com.sun.net.httpserver.HttpServer` 起于 `pig-agent-cli` 进程内，`start`/`stop`/`boundPort`；线程池为 daemon（不阻塞 JVM 退出）。零外部依赖、离线可构建。
- **D3：仅本机 + 单用户。** 绑 `127.0.0.1`，不对外监听；无账号体系。这是「无 DB/无平台」约束下的安全底线；配置默认关闭。
- **D4：对话与事件都走 SSE。** `POST /api/chat` 以 `text/event-stream` 增量返回一次对话的 `reasoning`/`tool`/`answer` 帧，结束发 `done` 帧；`GET /api/events` 订阅 `subscribeEvents()` 把 `KernelEvent` 以 `data:` 帧推送，带 `retry:` 提示浏览器 `EventSource` 断线重连。写失败（客户端断开）即释放 handler 线程与订阅。
- **D5：前端轻。** 纯 HTML/CSS/JS 静态资源随 jar 打包（`resources/web/`），无构建步骤、无重框架；`fetch()` 流式读 `/api/chat`，`EventSource` 读 `/api/events`。
- **D6：配置开关 + 同进程装配。** `PigAgentConfig` 恢复 `WebConfig`（`enabled` 默认 false / `host` 默认 `127.0.0.1` / `port` 默认 `7317`）；`PigAgentCli` 在 `web.enabled` 时经 `WebLauncher.startIfEnabled(kernel, ...)` 在同进程内起控制台，与 CLI 共享同一 `AgentKernel`，shutdown hook 停服。`WebLauncher` 只收原语（enabled/host/port），不引入 `pig-agent-config` 依赖到 web 模块。
- **D7：凭据不外泄。** `WebJson` 仅投影非敏感字段（agent 的 id/name/model-id/tools/state、事件的 type/agentId/message、chat 帧）；不投影 apiKey/token/env/headers。SSE 帧文本来自 agent 事件的可见文本，不含凭据。

## Risks / Trade-offs

- [本地 server 暴露 agent 控制面 → 安全] → D3：绑 `127.0.0.1`、不对外；单用户本机；默认关闭（`web.enabled=false`）。
- [同进程起 server 与 REPL 争用 → 单飞冲突] → `ReActAgent` 单飞：Web 与 REPL 若并发同一 agent 会撞（"Agent is still running"）。首版接受——单用户本机，二者一般不同时对话；文档提示。
- [事件流背压/断连] → SSE `retry:` 断线重连；写失败即释放线程；无订阅者时门面零成本（facade 保证）。
- [web 依赖污染核心] → D1 独立模块，仅依赖 core + Jackson。
- [不接会话持久化钩子 → Web 对话不落 meta] → 首版限制：Web 发起的回合仍写入活动 agent 的内存对话（与 REPL 同一 `AgentInstance`），进程退出时 `shutdownCommon` 统一 `saveCurrent`；但不镜像 REPL 的 per-turn 保存/压缩。列为已知局限，待后续 spec 扩。

## Non-Goals（重申）

model/session/mcp/task/permission/memory/compress 的 REST 端点、tab 化前端、Web 侧中断按钮、审批回写——均不在本 spec，留待后续按需扩展。

## 落实追踪表（评审/待优化发现项映射）

| 发现项 | 落点 | 状态 |
|--------|------|------|
| 只依赖门面、不碰内核内部 | D1 + `pig-agent-web` 仅 core+Jackson 依赖 | 已实现 |
| 仅绑本机、默认关闭 | D3/D6 + `WebConfig.enabled=false` | 已实现 |
| 凭据不外泄 | D7 + `WebJson` 只投影非敏感字段（无 model/mcp 端点，无凭据可泄） | 已实现 |
| 单飞冲突风险 | Risks 记录 + 文档提示 | 已实现（文档提示） |
| 会话持久化钩子不接 | Non-Goals + Risks「已知局限」 | 延后（后续 spec） |
| CLI 全能力对等 | Non-Goals | 延后（后续 spec） |

## Migration Plan

- 纯新增模块 + 配置开关（默认关）；不改内核与 CLI REPL 行为。
- 回滚：`web.enabled=false`（默认）或移除模块即可，内核与 CLI 不受影响。

## Open Questions（已定）

- 技术栈：→ **已定 JDK 内置 `com.sun.net.httpserver`**（零外部依赖、离线、够用）。
- 装配方式：独立 `WebLauncher` main vs 随 CLI 同进程？→ **已定同进程**——本任务要求 `mvn exec:java -pl pig-agent-cli` 能直接拉起，故 `PigAgentCli` 在 `web.enabled` 时内联启动，`WebLauncher` 提供 `startIfEnabled` 便于装配与单测。
