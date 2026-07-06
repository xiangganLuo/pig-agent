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

- **D1：Web 是门面的 adapter，零内核逻辑。** 所有操作/数据经 `AgentKernel`；Web 不直连内核内部，不新增业务规则。与 CLI 并列。
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

## Open Questions

- 技术栈：Javalin vs Spring Boot 内嵌 vs 纯 `com.sun.net.httpserver` + 静态页？→ 倾向 Javalin（轻、单进程好带）；code 阶段定。
- 「等你决定」审批的回写路径：Web 点批准 → 门面 `runNow`/重放？→ 依赖数字员工的审批回放能力，需 `digital-employee` 归档后对齐。
