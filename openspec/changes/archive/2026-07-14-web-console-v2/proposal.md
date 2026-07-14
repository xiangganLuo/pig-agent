## Why

`agent-kernel-facade` 让内核对外只剩一个 `AgentKernel` 门面 + `KernelEvent` 事件流，并明确「加一个前端 = 加一个 adapter」。此前的 `web-visualization` 曾据此做过一版嵌入式 Web 控制台，但在 `tui-frontend` 收敛到 CC 风格 REPL 时被整体移除。本变更（`web-console-v2`）在**当前门面 API** 上重新引入一个**最小可用**的本地 Web 控制台——作为 `AgentKernel` 的又一个 adapter，与 CLI 共享同一内核与同一批 agent，让非终端场景也能发起对话、看事件、切 agent。

与上一版的区别：**不追求 CLI 全能力对等**，只做「可用的后端 adapter + 极简 UI」——聊天流 + 实时事件 + agent 列表/切换。其余能力（model/session/mcp/task/permission）留待后续按需扩展，以把首版风险压到最低。

## What Changes

- **新增 `pig-agent-web` 模块**：仅依赖 `pig-agent-core`（门面 + `KernelEvent` + agent 领域类型）+ Jackson，**不触碰** `AgentRegistry`/`AgentInstanceFactory`/`AgentSpecRepository`/`AgentRunner` 等内核内部类型。
- **嵌入式 HTTP server**：JDK 内置 `com.sun.net.httpserver`，零外部 web 框架、离线可构建；绑 `127.0.0.1`，随 `pig-agent-cli` 进程启停。
- **最小端点**：
  - `GET /` → 极简静态控制台（聊天框 + 流式答案 + agent 列表）。
  - `POST /api/chat` → 经 `kernel.chat(agentId, msg)` 以 SSE 流式返回一次对话。
  - `GET /api/events` → 桥接 `kernel.subscribeEvents()`，把 `KernelEvent` 以 SSE `data:` 帧推给前端。
  - `GET /api/agents` / `POST /api/agents/{id}/use`（含 get/create/update/delete/run）→ 经门面管理 agent。
- **配置开关**：`PigAgentConfig` 恢复 `web`（`enabled`/`host`/`port`），默认 `enabled=false`；`PigAgentCli` 在启用时在**同进程内**起 Web 控制台，`mvn exec:java -pl pig-agent-cli` 即可拉起。默认关闭，向后兼容。
- **凭据不外泄**：任何响应 MUST NOT 回传 apiKey / token / env / headers 等敏感字段。

## Capabilities

### New Capabilities
- `web-console`: 嵌入式、单用户、无 DB 的本地 Web 控制台，作为 `AgentKernel` 的一个 adapter，提供流式对话、实时事件推送与 agent 列表/切换的最小可用前端。

## Impact

- **代码**：新增 `pig-agent-web`（server + 4 类 handler + 极简前端资源 + `WebLauncher`）；`PigAgentConfig` 恢复 `WebConfig`；`PigAgentCli`/parent-pom/cli-pom 装配。
- **依赖**：仅 `pig-agent-core` + `jackson-databind`（HTTP server 用 JDK 内置，无新框架）。
- **约束守住**：无 DB / 无多租户 / 无独立部署 / 单用户；仅绑本机回环。
- **不改动**：内核逻辑与 CLI REPL（Web 是并列的另一个 adapter，默认关闭）。
