## Context

工具经 `@Tool`/`@ToolParam` 声明，`AgentWiring`/`AgentBootstrap` 把工具实例注册进 `Toolkit`（`toolkit.registration().tool(new X()).apply()`）。传给模型的工具定义来自该 Toolkit。执行期由 `ToolPermissionHook`（`PreActingEvent`，risk 分级 + mode）veto。目前没有「构建 schema 前按前置条件过滤工具」的机制——所有注册工具都对模型可见。凭据（apiKey/env/headers）在 model/mcp 中已有脱敏约定。

## Goals / Non-Goals

**Goals:**
- 工具可声明可用性判据；不可用工具不进模型 schema；判据异常 fail-safe。
- 被隐藏工具及原因对用户可见，凭据不外露。
- 与既有权限 veto 互补、不改其契约。

**Non-Goals:**
- 不改 `ToolPermissionHook`/`ToolRiskClassifier`/权限 mode/allowlist 行为。
- 不做「运行时动态重算可用性并热增删 schema」（构建 agent/工具定义时求值即可；MCP 热增删走既有 `McpManager`）。
- 不把凭据值暴露到任何展示或日志。

## Decisions

- **D1 — 判据声明形式**：优先用「不侵入 `@Tool` 方法签名」的方式声明可用性——如工具类实现一个可选接口（`ToolAvailability { Availability check(); }`，返回 available + 可选 reason）或注册时附带一个 `Supplier<Availability>`。理由：多数工具无需判据（默认可用），侵入式注解会污染全部工具；可选接口/注册附加项只让需要的工具实现。
- **D2 — 求值时机**：在组装「传给模型的工具定义」处求值（agent/toolkit 构建时），过滤不可用工具。判据求值包 try/catch，异常→不可用（fail-safe），并记录一条 debug 日志（不含凭据）。
- **D3 — 隐藏原因的承载**：过滤时收集「(工具名, 原因)」列表，挂在可查询处（如 toolkit 组装结果或一个只读查询 API），供 `/status` 与 TUI status 面板读取。reason 只描述缺失前置项名（如变量名），不含其值。
- **D4 — 与权限系统的层次**：可用性过滤在「工具是否进 schema」层；权限 veto 在「已在 schema 的工具能否执行」层。两层独立叠加：不可用→模型看不到；可用但受 mode 限→执行期 veto。
- **D5 — 判据内容示例**：`webSearch` → 检查 `BRAVE_API_KEY`；需二进制的工具 → 检查可执行存在；需服务的 → 检查连通配置。判据应廉价（无网络往返），必要的重检查可缓存一次求值结果。

## Risks / Trade-offs

- **R1 — 判据副作用/耗时**：判据若做重操作（网络探测）会拖慢启动。约束判据廉价、对同一进程一次求值可缓存；文档指导。
- **R2 — 过度隐藏**：判据过严会误藏可用工具，用户以为功能缺失。→ 隐藏原因对用户可见（D3）正是为此：用户能看到「因缺 X 被隐藏」并补齐。
- **R3 — 与 MCP 动态工具的关系**：MCP 工具由 `McpManager` 动态注册；本能力主要覆盖内建 `@Tool`。MCP 工具的可用性由其连接状态体现（既有），不在本 spec 强行统一。
