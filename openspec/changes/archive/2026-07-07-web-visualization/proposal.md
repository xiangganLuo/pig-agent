## Why

前三个 spec（内核 → 数字员工 → 门面）让 Pig Agent 有了可管理的多 agent、自主运行与统一的 `AgentKernel` 门面。本变更（独立后续）把这套能力**可视化**：一个嵌入式、单用户、无 DB 的本地 Web 控制台，让非终端场景也能管 agent、看运行、读晨报。它是设计文档 point 3「多种入口」的应用，与承重墙耦合度低，故**独立成 spec**。

> **依赖**：本变更依赖 `agent-kernel-facade` 已归档（Web 只作为 `AgentKernel` 的又一个 adapter，消费其 API + `KernelEvent` 事件流）。归档前本 proposal 仅锁定范围与依赖，`tasks.md` 待门面归档后再细化。

## What Changes

- **新增嵌入式本地 server**：单进程内起一个轻量 HTTP server（技术栈待定，倾向 Javalin / Spring Boot 内嵌），暴露 `AgentKernel` 的只读/操作 API + `KernelEvent` 事件流（SSE/WS）。**无 DB、单用户、无独立部署**——随 `pig-agent-cli` 进程一起跑。
- **Web MVP 页面**：agent 列表/切换、会话流（实时）、运行监控、晨报「收件箱」、「等你决定」审批。
- **Web 作为 adapter**：不新增内核逻辑，只消费门面；与 CLI 共享同一内核与同一批 agent。

## Capabilities

### New Capabilities
- `web-console`: 嵌入式、单用户、无 DB 的本地 Web 控制台，作为 `AgentKernel` 的一个 adapter，可视化地管理 agent、监控运行、阅读晨报、审批「等你决定」项。

## Impact

- **代码**：新增 `pig-agent-web`（或 cli 内子包）：嵌入式 server + 前端资源 + `AgentKernel` adapter。
- **依赖**：轻量 web 框架（如 Javalin）+ 前端（静态资源，无重框架）。
- **约束守住**：无 DB / 无多租户 / 无服务端集群 / 单用户；随现有进程分发。
- **前置**：`agent-kernel-facade` 归档。
- **不改动**：内核与 CLI（Web 是并列的另一个 adapter）。
