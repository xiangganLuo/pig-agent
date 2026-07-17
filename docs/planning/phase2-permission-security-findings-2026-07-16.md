# Phase 2 原生权限——安全复核发现与处置（2026-07-16）

> security-reviewer 对 v2 线 Phase-2（`ToolPermissionHook`/`PermissionDeniedTool` → 原生 `PermissionEngine`+`PermissionContextFactory`）的只读复核。`PermissionContextFactory` 映射逻辑正确、fail-closed 生效、readOnly 标注无越权。以下为发现项与处置。

## 已修（v1 main，commit `a3ad992`）
- **H-1（HIGH）webSearch 网络出口**：原 READ_ONLY → 在 plan/EXPLORE 放行，可经搜索查询外泄上下文（Brave 公网、不经 SSRF 守卫）。改归 `NETWORK`（与 fetchUrl 一致）。**v2 线也需同改 + 移除 `BraveWebSearchTool` 的 `readOnly=true`（Phase 2 加的）** → 纳入 Phase 4。
- **M-3（MEDIUM）checklist 未分类**：`createChecklist`/`markComplete`→WRITE、`showChecklist`→READ_ONLY（原默认 EXEC）。**v2 线还需给 `showChecklist` 加 `@Tool(readOnly=true)`** → 纳入 Phase 4。

## Phase 4 必办（v2 线接线/清理，随 Phase 4 一并做）
- **C-1（CRITICAL，已知中间态）权限执行未接线**：旧 hook 删了、新引擎未在 cli 装（cli 本就未上 2.0）。Phase 4 必须：`AgentBootstrap` 调 `PermissionContextFactory.build(cfg,pigMode,toolNames,interactive)` → `ReActAgent.Builder.permissionContext(...)`（HarnessAgent 经 fromAgent 继承）+ 运行时 `setPermissionMode`；替换全部 4 处 `ToolPermissionHook` 构造（interactive/per-agent/autonomous/channel）；`AgentWiring.toolkitFor` 去掉 `PermissionDeniedTool.TOOL_NAME` sentinel 保留逻辑；同步 `AgentWiringTest`；`LoopDetectionHook` ignore-set 去掉 sentinel 引用。**加 CI 门：cli 在 2.0 干净编译**。
- **H-2（HIGH）权限 IT 重写**：`PermissionEnforcementIT` 仍引用已删类、无法编译。用原生路径（`permissionContext`）重写，Phase-4 真模型验收跑：plan 拒变更工具 / bypass 放行 / 模型收到 DENIED 后产出计划。
- **M-1（MEDIUM）命令级 allowlist**：`PermissionContextFactory` 仅读 `allowlist.getTools()`，`allowlist.commands` 被忽略（fail-closed=命令需重新确认）。Phase 4 让 `executeCommand` 成 `ToolBase` 覆写 `checkPermissions`/`matchRule` 以支持命令粒度；过渡期启动时若 `allowlist.commands` 非空则告警。
- **M-2（MEDIUM）MCP 热加工具无 per-tool 规则**：`build()` 快照 toolNames，`/mcp add` 后新工具仅走 base 模式（非交互 DONT_ASK fail-closed 安全；交互/ BYPASS 有风险）。Phase 4 加 `McpManager` 回调，加服务器时重建/增补 `PermissionContextState`。
- **L-1** 归档/重命名过时的 `PermissionVetoSpikeIT`（测的是已废的 sentinel 机制），新增原生引擎端到端 IT。
- **L-2** 更新 `PermissionCommand` 过时 Javadoc（引用已删 `ToolPermissionHook`）。

## 自审复核结论
plan 真只读（tier-1 DENY 胜 allowlist）、channel/autonomous fail-closed（DONT_ASK+ASK→DENY）、MCP_ADMIN 走 D-SEC 不双提示、bypass 下原生危险路径检查仍在（javap 佐证、待真测确认）、无 READ_ONLY 越权——逻辑均确认；唯"执行已接线"未成立（C-1，Phase 4）。
