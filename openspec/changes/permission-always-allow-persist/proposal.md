## Why

`ask` 模式下用户对某工具选择 `a`（始终允许）后，**同一 REPL 会话内、后续回合再次调用该工具仍会重新弹确认**——这与 `tool-permissions` 主 spec 已承诺的「始终允许后免确认」（`之后相同调用不再确认`）不符，是一个**安全敏感 + 架构性**的 bug。复现：回合 1「我叫陈志雄」→ agent 调 `updateProfile`、用户选 `a`；回合 2「我家住在翻斗花园」→ agent 再调 `updateProfile` → **又问一次**（bug）。

根因经既有调查以真实 2.0 jar + 离线两回合 harness 证实：`a` 分支现只做 2 参 `ConfirmResult(true, call)`（不带规则）+ `rememberTool()`（只写配置 allowlist、下次 build 才生效），而**两个相互独立的拦路石**使「只挂一条 ALLOW 规则」的天真修法失效——① **ASK 遮蔽**（`PermissionContextFactory` 给 `ask` 模式的 WRITE 工具显式发一条 per-tool ASK 规则，原生优先级 `deny>ask>allow`，ASK 恒压过后加的 ALLOW）；② **跨回合不持久**（`applyConfirmResults` 把 ALLOW 加进本次调用的引擎 `allowRules`，但**从不写回** `AgentState.permissionContext`；pig 每个用户回合是一次独立 `stream()`，下回合从未变的持久上下文重建全新引擎 → 再问）。

## What Changes

- **`a`（始终允许）分支挂 ALLOW 规则**（`AgentRepl.confirm()`）：为 `a` 选择的工具挂一条 `PermissionRule(name, null, ALLOW, "user:always")` 到 `ConfirmResult`（3 参 `ConfirmResult(boolean, ToolUseBlock, List<PermissionRule>)`），使**本次调用内**后续相同调用即时免确认；保留 `rememberTool()`（写配置 allowlist）以支撑**跨重启**持久。此为必要非充分。
- **移除 ASK 遮蔽（安全敏感）**：把 `PermissionContextFactory` 现有对 `executeCommand` 的 M-1「不发 ASK 规则」豁免**推广到所有 ASK 判定的工具**——`ask`/`auto` 下不再发 per-tool ASK 规则，改依 native **模式默认**兜底（交互 `DEFAULT`/`ACCEPT_EDITS` 无匹配规则 → 仍走 ASK3 弹 HITL；非交互 `DONT_ASK` 无匹配规则 → 默认 DENY，fail-closed 不变）。移除遮蔽后，回合级持久化写入的 ALLOW 才不会被 ASK 压过。**MUST 保持**：首次调用仍弹确认、非交互/自主 fail-closed、plan 只读（DENY 规则不受影响）、风险分级不变。
- **跨回合持久化「始终允许」（架构性、新增 pig 能力）**：选 `a` 时把该 ALLOW 规则写进**当前会话槽 `(userId,sessionId)` 的持久 `AgentState.permissionContext`**，使下一回合从该槽重建的引擎仍放行。复用 `SubagentPermissions.deriveChildContext` 已证的「读现有 `PermissionContextState` → 重建带新规则的副本」模式，落在 `AgentState.setPermissionContext(...)`（javap 确认存在）+ `saveAgentState(userId,sessionId)`——机制与 `setPermissionMode`（已能就地翻转会话槽的 base mode 并跨回合生效）同源。
- **验收**：真实两回合场景离线回归——回合 2 自动放行，且**首次调用仍弹确认、非交互仍 fail-closed**（既有 harness 已证天真修法在回合 2 失败，本 spec 的验收是三合一修法让回合 2 免确认而不放松首问/fail-closed）。

**非破坏**：`bypass`（全放行）、`plan`（EXPLORE 只读，DENY 规则）、`auto` 对 EXEC 的把关、命令粒度 allowlist（M-1）语义不变；「始终允许」的**运行时**作用域为**本会话**（跨会话/跨重启仍靠 `rememberTool` 写入的配置 allowlist 于下次 build 生效）。

## Capabilities

### New Capabilities
<!-- 无新增能力：本变更修的是既有 tool-permissions 能力已承诺但未兑现的行为。 -->

### Modified Capabilities
- `tool-permissions`: 收紧「ask 模式人工确认 / 始终允许」的语义——明确 `a` 的免确认 MUST **跨同一会话的后续回合**立即生效（非仅本次调用、非仅跨重启）；新增「移除 per-tool ASK 遮蔽、改依模式默认」与「回合级持久化 ALLOW 到会话权限上下文」两条要求，并显式保全首问、非交互 fail-closed、plan 只读、deny/危险路径不可绕过等安全不变量。

## Impact

- **代码**：`pig-agent-tools`（`PermissionContextFactory.addRule`：ASK 判定一律不发 per-tool 规则——把 M-1 的 `executeCommand` 豁免推广到全部；`PermissionPolicyTest`/`PermissionContextFactoryTest` 同步）；`pig-agent-core`（`PigAgent` 新增会话级「持久化一条 ALLOW 规则到 `(userId,sessionId)` 槽」的方法，复用 `getAgentState`/`setPermissionContext`/`saveAgentState`；可选薄 helper 复用 `SubagentPermissions` 的规则拷贝模式）；`pig-agent-core.agent.kernel`（`AgentKernel` 加一个「为会话始终允许某工具」的门面方法，前端只依赖门面）；`pig-agent-cli`（`AgentRepl.confirm()` 的 `a` 分支：3 参 `ConfirmResult` 带 ALLOW 规则 + 调门面持久化 + 保留 `rememberTool`）。
- **不改**：风险分级表（`ToolRiskClassifier`/`PermissionPolicy` 判定矩阵）；`GuardedAgentTool extends ToolBase` 的 P0 门控；命令粒度 allowlist（M-1）；渠道/自主 fail-closed 姿态；plan/Plan-Mode 只读；deny 规则与危险路径不可绕过；`bypass` 语义。
- **测试**：离线单测——`PermissionContextFactory` 移除 ASK 规则后交互仍达 ASK、非交互仍 DENY（模式默认兜底）；`a` 分支挂 ALLOW 规则；`PigAgent` 会话级持久化写回 + 重建引擎放行；**真实两回合离线回归**（回合 1 弹→选 a→回合 2 免确认；首次仍问；非交互 fail-closed）。真实链路（真模型多回合确认）→ 权限 `*IT`（`/ls:itest`）。
- **文档**：`CLAUDE.md` 权限段落同步「始终允许跨回合持久化」与「ASK 改依模式默认（推广 M-1）」。
