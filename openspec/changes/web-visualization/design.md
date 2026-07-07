## Context

`agent-kernel-facade`（前置）就绪后，`AgentKernel` 门面 + `KernelEvent` 事件流成为内核唯一对外面。本变更把这套能力**可视化**：一个嵌入式、单用户、无 DB 的本地 Web 控制台，作为 `AgentKernel` 的又一个 adapter，与 CLI 共享同一内核与同一批 agent。守个人电脑红线：无 DB / 无多租户 / 无服务端集群 / 无独立部署。

> 依赖 `agent-kernel-facade` 归档；本 spec 的 tasks 在门面 API/事件类型最终定型后可能微调，requirements（契约）稳定。

## Goals / Non-Goals

**Goals:**
- 嵌入式本地 HTTP server（随 `pig-agent-cli` 进程），暴露门面的只读/操作 API + `KernelEvent` 实时推送（SSE/WS）。
- Web MVP：agent 列表/切换、会话流（实时）、运行监控、晨报「收件箱」、「等你决定」审批。
- 仅本机访问（绑 `127.0.0.1`），单用户。

**Non-Goals:**
- DB / 多租户 / 云托管 / 服务端集群 / 独立部署。
- 账号体系 / 复杂鉴权（单用户本机）。
- 新增内核逻辑（Web 只消费门面）。

## Decisions

- **D1：Web 是 adapter，零内核/业务逻辑（门面 + 共享 manager）。** agent 生命周期/对话/事件经 `AgentKernel`；其余能力（model/session/compression/mcp/task/permission-config/memory）复用 CLI **同一批 manager 实例**（`WebContext` 打包，镜像 `ReplContext` 的角色），单一真相源，不重复实现、不直连内核内部私有类。这比最初"只依赖 AgentKernel"更贴合现实——CLI 的 `ReplContext` 本就同时持门面与 manager。据此 `pig-agent-web` 依赖扩到 model/session/mcp/task/providers（无环）。
- **D2：嵌入式轻量 server，随进程。** 单进程内起一个轻量 HTTP server（倾向 Javalin，最终技术栈见 Open Q），随 `pig-agent-cli` 生命周期启停；无独立部署、无 DB。
- **D3：仅本机 + 单用户。** server 绑 `127.0.0.1`，不对外监听；单用户，无账号体系。这是"无 DB/无平台"约束下的安全底线。
- **D4：事件经 SSE/WS 推送。** 订阅 `AgentKernel.subscribeEvents()` → 经 SSE/WS 推给前端，驱动会话流/运行监控/晨报的实时更新。
- **D5：前端轻。** 静态资源 + 轻量前端（无重框架），随 jar 打包；MVP 覆盖上述五类页面。
- **D6：新增 `pig-agent-web` 模块（或 cli 子包）。** 隔离 web 依赖，避免污染核心；由 `PigAgentCli` 在启用时装配。

## Risks / Trade-offs

- [本地 server 暴露 agent 控制面 → 安全] → D3：绑 `127.0.0.1`、不对外；单用户本机；默认可关（配置开关）。
- [门面 API/事件类型未定型 → tasks 返工] → 本 spec requirements 稳定，tasks 在 facade 归档后核对再细化。
- [web 依赖污染核心] → D6 独立模块隔离。
- [事件流背压/断连] → SSE/WS 断线重连；无订阅者时门面零成本（facade D2 已保证）。

## Migration Plan

- 纯新增模块 + 装配开关（默认可关/可开）；不改内核与 CLI。随现有进程分发。
- 回滚：移除模块/关开关即可，内核与 CLI 不受影响。

## Open Questions（已定）

- 技术栈：Javalin vs Spring Boot 内嵌 vs 纯 `com.sun.net.httpserver` + 静态页？→ **已定：选 JDK 内置 `com.sun.net.httpserver`**——零外部依赖、离线可构建，满足绑本机 + SSE + 静态资源，契合"轻、无重框架"。前端纯 HTML/JS 无构建步骤。
- 「等你决定」审批的回写路径：Web 点批准 → 门面 `runNow`/重放？→ 仍依赖数字员工的审批回放能力；本期 Web 已提供 `runNow`（`POST /api/agents/{id}/run`）与晨报事件可见性，审批回写待数字员工审批能力补齐后再接。
- 能力范围：初版仅 agent 管理 + 事件；**已扩为 CLI 全能力对等**（chat 流 + model/session/task/mcp/permission/memory/compress），据 D1 经共享 manager 委托。
