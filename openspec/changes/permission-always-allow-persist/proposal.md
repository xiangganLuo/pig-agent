## Why

`ask` 模式下用户对某工具选择 `a`（始终允许）后，**同一 REPL 会话内、后续回合再次调用该工具仍会重新弹确认**——这与 `tool-permissions` 主 spec 已承诺的「始终允许后免确认」（`之后相同调用不再确认`）不符，是一个**安全敏感 + 架构性**的 bug。复现：回合 1「我叫陈志雄」→ agent 调 `updateProfile`、用户选 `a`；回合 2「我家住在翻斗花园」→ agent 再调 `updateProfile` → **又问一次**（bug）。

根因经既有调查以真实 2.0 jar + 离线两回合 harness 证实：`a` 分支现只做 2 参 `ConfirmResult(true, call)`（不带规则）+ `rememberTool()`（只写配置 allowlist、下次 build 才生效），而**两个相互独立的拦路石**使「只挂一条 ALLOW 规则」的天真修法失效——① **ASK 遮蔽**（`PermissionContextFactory` 给 `ask` 模式的 WRITE 工具显式发一条 per-tool ASK 规则，原生优先级 `deny>ask>allow`，ASK 恒压过后加的 ALLOW）；② **跨回合不持久**（`applyConfirmResults` 把 ALLOW 加进本次调用的引擎 `allowRules`，但**从不写回** `AgentState.permissionContext`；pig 每个用户回合是一次独立 `stream()`，下回合从未变的持久上下文重建全新引擎 → 再问）。

## What Changes

- **`a`（始终允许）分支挂 ALLOW 规则**（`AgentRepl.confirm()`）：为 `a` 选择的工具挂一条 `PermissionRule(name, null, ALLOW, "user:always")` 到 `ConfirmResult`（3 参 `ConfirmResult(boolean, ToolUseBlock, List<PermissionRule>)`），使**本次调用内**后续相同调用即时免确认；保留 `rememberTool()`（写配置 allowlist）以支撑**跨重启**持久。此为必要非充分。
- **消除 ASK 遮蔽——会话级逐工具 ASK→ALLOW 置换（安全敏感；spike 已改设计）**：**`PermissionContextFactory` 不改**（所有 per-tool ASK 规则保留 → 全局首问不变）。选 `a` 时改为在**当前会话槽**对**该一个工具**做 ASK→ALLOW 置换（删该工具 ASK 规则 + 加 ALLOW 规则），使 `deny>ask>allow` 不再遮蔽。**为何不能全局删 ASK 依模式默认（原设计已被 spike 推翻）**：agent 的 ReAct 循环对纯模式默认 ASK **不弹 HITL、直接执行工具**（只有显式 ASK 规则/内建 ASK 检查才暂停），全局删 ASK = 可变工具无确认执行 = 安全回归。**MUST 保持**：首次调用仍弹确认（所有工具）、非交互/自主 fail-closed、plan 只读（DENY 规则不受影响）、风险分级不变。
- **跨回合持久化「始终允许」（架构性、新增 pig 能力）**：选 `a` 时把置换后的上下文写进**当前会话槽 `(userId,sessionId)` 的持久 `AgentState.permissionContext`** 并刷新该槽权限引擎缓存（`setPermissionMode(sameMode)`）+ `saveAgentState`，使下一回合从该槽取用/重建的引擎放行。复用 `SubagentPermissions.deriveChildContext` 已证的「读现有 `PermissionContextState` → 重建修改后副本」模式；`AgentState.get/setPermissionContext`、`ReActAgent.getAgentState/saveAgentState/setPermissionMode` 均经 spike javap 确认。
- **验收**：真实两回合离线回归——回合 2 自动放行，且**首次调用仍弹确认、另一工具仍确认、非交互仍 fail-closed、另一会话仍确认**（spike `PermissionAlwaysAllowSpikeTest` 7 测试已证机制）。

**非破坏**：`bypass`（全放行）、`plan`（EXPLORE 只读，DENY 规则）、`auto` 对 EXEC 的把关、命令粒度 allowlist（M-1）语义不变；`PermissionContextFactory` 的风险→规则映射不变；「始终允许」的**运行时**作用域为**本会话**（跨会话/跨重启仍靠 `rememberTool` 写入的配置 allowlist 于下次 build 生效）。

## Capabilities

### New Capabilities
<!-- 无新增能力：本变更修的是既有 tool-permissions 能力已承诺但未兑现的行为。 -->

### Modified Capabilities
- `tool-permissions`: 收紧「ask 模式人工确认 / 始终允许」的语义——明确 `a` 的免确认 MUST **跨同一会话的后续回合**立即生效（非仅本次调用、非仅跨重启）；新增「会话级逐工具 ASK→ALLOW 置换（`PermissionContextFactory` 不变）」与「回合级持久化到会话权限上下文」两条要求，并显式保全首问（所有工具）、非交互 fail-closed、plan 只读、deny/危险路径不可绕过等安全不变量。

## Impact

- **代码（改设计后）**：`pig-agent-core`（`PigAgent` 新增会话级 `allowToolForSession(sessionId, toolName)`：读槽上下文 → 删该工具 ASK 规则 + 加 ALLOW 规则 → `setPermissionContext` + `setPermissionMode(sameMode)` 刷新引擎缓存 + `saveAgentState`，复用 `SubagentPermissions` 的规则拷贝模式；`AgentKernel` 加一个门面方法 `allowToolForSession(agentId, sessionId, toolName)`，前端只依赖门面）；`pig-agent-cli`（`AgentRepl.confirm()` 的 `a` 分支：3 参 `ConfirmResult` 带 ALLOW 规则 + 调门面持久化 + 保留 `rememberTool`）。
- **明确不改**：`PermissionContextFactory`（**spike 改设计后完全不动**——保留全局 ASK 规则映射）；风险分级表（`ToolRiskClassifier`/`PermissionPolicy` 判定矩阵）；`GuardedAgentTool extends ToolBase` 的 P0 门控；命令粒度 allowlist（M-1）；渠道/自主 fail-closed 姿态；plan/Plan-Mode 只读；deny 规则与危险路径不可绕过；`bypass` 语义。
- **测试**：离线单测——spike `PermissionAlwaysAllowSpikeTest`（机制证明，7 测试）+ `PigAgent.allowToolForSession` 单测（置换幂等、deny 不动）+ **真实两回合离线回归**（回合 1 弹→选 a→回合 2 同工具免确认；另一工具仍问；首次仍问；非交互 fail-closed；另一会话仍问）。真实链路（真模型多回合确认）→ 权限 `*IT`（`/ls:itest`）。
- **文档**：`CLAUDE.md` 权限段落同步「始终允许跨回合持久化（会话级 ASK→ALLOW 置换 + 配置 allowlist 两层）」。
