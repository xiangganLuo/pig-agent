# permission「始终允许」跨回合持久化 设计（安全敏感 + 架构性 bug 修复）

> 阶段：`/ls:spec`。分支 `bug/20260717-permission-always-allow`（隔离 worktree，off `main`；提交信息用 conventional-commit `fix:`）。
> 修的是既有 `tool-permissions` 能力**已承诺但未兑现**的行为（主 spec `始终允许后免确认` = `之后相同调用不再确认`）。
> 承重假设经既有调查以**真实 2.0 jar + 离线两回合 PigAgent harness** 证实；本设计在其上构建，`（需 javap 验证）` 标注仍未确认的签名。

## Context

**Bug**：同一 REPL 会话内用户对某工具选 `a`（始终允许），**该工具在后续回合的第二次调用又被要求确认**。复现：回合 1「我叫陈志雄」→ agent 调 `updateProfile`、用户选 `a`；回合 2「我家住在翻斗花园」→ agent 再调 `updateProfile` → **再问**（bug）。

**现状代码**（本仓库确认）：
- `AgentRepl.confirm()` 的 `a` 分支：`rememberTool(name)` + `results.add(new ConfirmResult(true, call))`（**2 参、不带规则**）。`rememberTool` 只把工具名写进 `permissions.allowlist.tools` 配置——而 `interactivePermCtx` 供应器只在 **agent build** 时读配置，故配置写入**下次 build 才生效**（当前会话不重建 → 本回合/下回合不生效）。
- `PermissionContextFactory.addRule`：`ask` 模式下 WRITE 工具判定 ASK → `builder.addAskRule(name, rule)`（发一条显式 per-tool ASK 规则）。`AgentBootstrap` 以 `interactive=true` 在 `toolkit.getToolNames()`（含 `updateProfile`）上构建交互上下文。
- `PigAgent.setPermissionMode(mode, sessionId)` javadoc（本仓库）：「the base PermissionMode of the `(userId,sessionId)` slot is flipped in place ... **A full re-derivation of per-tool rules happens when the agent is rebuilt (model switch) or on a fresh session**」——即**既有会话槽的 per-tool 规则不在运行时重新派生**，且会话槽的权限上下文是可就地变更并跨回合持久的（`setPermissionMode` 已证这条通路存在）。
- `PigAgent`：`reactAgent.getAgentState(USER_ID, sessionId)` / `reactAgent.saveAgentState(USER_ID, sessionId)` 已在 `clearConversation`/`copyConversation` 使用——会话槽读写的通路是现成的。
- `SubagentPermissions.deriveChildContext`：已用「读现有 `PermissionContextState`（`getMode`/`getAllowRules`/`getDenyRules`/`getAskRules`/`getWorkingDirectories`）→ `PermissionContextState.builder()` 重建带修改规则的副本」模式——**正是回合级持久化写回要复用的模式**。

**两个相互独立、均经实证的拦路石**（天真的「只挂一条 ALLOW 规则」修法失效）：
- **P1 — ASK 遮蔽**（`deny>ask>allow`）。`PermissionContextFactory` 给 `ask` 模式 WRITE 工具发的显式 per-tool ASK 规则，会在工具 `checkPermissions` 之前短路、压过任何后加的 ALLOW → 仍 ASK。`PermissionContextFactory` 自身的 M-1 注释已对 `executeCommand` 记录过同一遮蔽。
- **P2 — 跨回合不持久**。即便无遮蔽，`applyConfirmResults`（native `ReActAgent$CallExecution`）把规则经 `PermissionEngine.addRule` 加进**本次调用**引擎的 `allowRules`，但**从不写回** `AgentState.setPermissionContext(...)`；pig 每个用户回合是独立 `stream()`，下回合从**未变的持久上下文**重建全新引擎 → 再问。

## Goals / Non-Goals

**Goals：**
- 让 `ask` 模式下选 `a` 的工具在**同一会话的后续回合**立即免确认（兑现主 spec 承诺），同时**首次调用仍弹确认**。
- 修正**两个独立拦路石**：移除 ASK 遮蔽（P1）+ 把「始终允许」持久化到会话权限上下文（P2）。
- 保全全部既有安全不变量：非交互/自主 **fail-closed**、`plan`/Plan-Mode 只读、deny 规则与危险路径**不可绕过**、`bypass` 语义、命令粒度 allowlist（M-1）、风险分级不变。

**Non-Goals：**
- 不削弱任何 deny 规则、危险路径拦截、`bypass`/`plan` 语义。
- 不改风险分级矩阵（`ToolRiskClassifier`/`PermissionPolicy` 判定表）。
- 不改命令粒度 allowlist（M-1）机制本身（本变更是把它的「不发 ASK 规则」豁免推广到全部工具）。
- 不新做跨会话的运行时「始终允许」共享（跨会话/跨重启仍由 `rememberTool` 写配置 allowlist、下次 build 生效承担）——运行时作用域刻意限定为**本会话**。
- 不改 native `applyConfirmResults`（不改框架源码；pig 在自己一侧补写回）。

## Decisions

### D1（fix #1）—— `a` 分支挂 ALLOW 规则（必要非充分）
`AgentRepl.confirm()` 的 `a` 分支改用 3 参 `ConfirmResult(true, call, List.of(new PermissionRule(call.getName(), null, PermissionBehavior.ALLOW, "user:always")))`。作用：**本次调用内**后续相同调用即时免确认（native `applyConfirmResults` 消费 `getRules()` → `PermissionEngine.addRule`）。保留 `rememberTool(name)`（写配置 allowlist）以支撑**跨重启**（下次 build 由 `PermissionPolicy`：allowlisted→ALLOW 派生真 ALLOW 规则）。
- **javap 已确认**（既有调查）：`ConfirmResult(boolean, ToolUseBlock, List<PermissionRule>)` 3 参构造 + `getRules()`；`PermissionRule(String, String, PermissionBehavior, String)`；`PermissionBehavior.ALLOW`；`applyConfirmResults` 消费 `getRules()`→`addRule`（进 `allowRules`）但**不写回** `AgentState.setPermissionContext`。

### D2（fix #2 / P1）—— 移除 per-tool ASK 遮蔽，改依模式默认
把 `PermissionContextFactory.addRule` 现有的 M-1「`executeCommand` 且 decision==ASK → 不发规则」豁免**推广到所有工具**：`decision == ASK` 时一律 `return`（不发 per-tool 规则），交给 native 模式默认兜底。派生表其余分支不变（`DENY`（plan）照发、`ALLOW`（bypass/allowlisted）照发）。
- **为何成立（native 决策流，见本地文档 `permission-system.md` 流程图）**：
  - 交互 `DEFAULT`（ask）：无 ask 规则 → 工具 `checkPermissions`（pig 工具默认 PASSTHROUGH）→ 无 allow 规则 → 非 ACCEPT_EDITS 安全文件 → 非只读 bash → 非 BYPASS → 非 DONT_ASK → **ASK3**（弹 HITL）。**首问保留**。
  - 交互 `ACCEPT_EDITS`（auto）：EXEC@auto 判定 ASK；无 ask 规则 → …→ 非安全文件 → **ASK3**。auto 对 EXEC 的把关保留。
  - 非交互 `DONT_ASK`（渠道/自主）：无 ask 规则 → …→ **DONT_ASK 模式默认 = DENY**。**fail-closed 保留**（原「ASK→显式 DENY 规则」被「DONT_ASK 默认 DENY」等价取代）。
  - `plan`（EXPLORE）：可变工具判定为 DENY（非 ASK）→ 仍发 DENY 规则；**只读语义不受影响**。
  - `bypass`：判定 ALLOW → 照发；allowlist 命中：判定 ALLOW → 照发。
- **复用既有实证**：`executeCommand` 早已走这条「不发 ASK 规则、依模式默认 + `CommandPermissionTool` 自检」的路子并在生产验证（`FullLinkAgentIT` 10/10）。本决策是把同一模式推广到全部工具——非新机制。
- **安全评审重点**：此为安全敏感项（见「安全」段的威胁模型），须 security-reviewer 签字。

### D3（fix #3 / P2）—— 回合级持久化「始终允许」到会话权限上下文
选 `a` 时，把该 ALLOW 规则写进**当前会话槽 `(userId,sessionId)` 的持久 `AgentState.permissionContext`**，下一回合从该槽重建的引擎即放行。
- **seam（复用 `SubagentPermissions.deriveChildContext` 模式）**：pig 新增会话级方法（落 `PigAgent`，经 `AgentKernel` 门面暴露，供 `AgentRepl` 调用——前端只依赖门面）：
  1. `AgentState state = reactAgent.getAgentState(USER_ID, sessionId)`（现成）。
  2. `PermissionContextState cur = state.getPermissionContext()` `（需 javap 验证 getter 名/是否可为 null——若为 null 则以 build 期默认或最小 DONT_ASK 兜底）`。
  3. 用 `PermissionContextState.builder()` 重建 `cur` 的副本（拷 mode + 现有 allow/deny/ask 规则 + working dirs，**幂等**：若同工具已有等效 ALLOW 则跳过）并 `addAllowRule(tool, new PermissionRule(tool, null, ALLOW, "user:always"))`。
  4. `state.setPermissionContext(updated)` `（javap 确认 setter 存在）` + `reactAgent.saveAgentState(USER_ID, sessionId)`（现成）。
- **为何下一回合生效**：`setPermissionMode` javadoc 证会话槽的权限上下文可就地变更并跨回合持久，且既有会话槽的规则**不在运行时重新派生**（只在 build 重新派生）——因此我们写进槽的 ALLOW 规则不会被下回合的重新派生覆盖，会被引擎读到。**此为 P2 的承重假设，spike 须以两回合 harness 实证。**
- **时序（避免与本回合末尾保存竞态）**：`getAgentState(userId,sessionId)` 与本次运行中的调用是否同一缓存对象、以及本回合末尾 `saveCurrent()` 是否会覆盖写回 —— 定为 spike 项。**默认设计取无竞态时序**：在整个 `renderTurn`（含 HITL resume）**完成之后**再对本回合选过 `a` 的工具集执行写回 + save（此时本次 invocation 已结束，读槽→加规则→存槽不与运行中的引擎竞争），或确保写回在 `saveCurrent()` 之后/合并进同一次 save。
- **作用域 = 本会话**：写回按 `(userId,sessionId)` 落槽，天然按会话隔离（另一会话槽无此规则 → 仍按其模式/风险判定）。跨会话/跨重启由 `rememberTool` 写的配置 allowlist 在下次 build 生效承担（D1 保留它的原因）。

### D4 —— 两层持久化职责划分（回合级 vs 重启级）
| 持久层 | 载体 | 生效时机 | 作用域 | 谁写 |
|---|---|---|---|---|
| 回合级（本 spec 新增，D3） | 会话槽 `AgentState.permissionContext` 的 ALLOW 规则 | **本会话下一回合**（引擎从槽重建） | 本会话 | 选 `a` 时的会话级写回 |
| 重启级（既有，D1 保留） | 配置 `permissions.allowlist.tools`（`rememberTool`） | **下次 agent build/重启**（`PermissionPolicy` 派生 ALLOW，配 P1 移除遮蔽后不再被压过） | 全局 | `rememberTool` |

二者互补：单靠配置 allowlist 不重建当前会话 → 本回合/下回合不生效（即当前 bug）；单靠回合级写回 → 不跨重启。两者都要。

## Spike（tasks 第 1 组，承重、卡点 —— 不过不进编码）

> 目标：以真实 2.0 jar + 离线 harness 证实两条承重假设；产出 javap 确认清单。既有调查已证多数事实，spike 仅**收口 P2 的架构未知**与 P1 的兜底断言。

- **S1（P1 兜底断言，承重）**：移除显式 per-tool ASK 规则后，一个**非平凡上下文**里 mode-`DEFAULT` 的可变工具（无 allow/ask/deny 规则）是否**仍抬起 HITL `RequireUserConfirmEvent`**（首问保留）；且 `DONT_ASK` 基线下是否**仍 DENY**（fail-closed 保留）。（既有离线检查已初证，spike 复核并纳入回归。）
- **S2（P2 架构未知，承重）**：确认把一条规则持久进 `(userId,sessionId)` 的 `AgentState.permissionContext` 使**下一回合**引擎放行的确切机制与 API——
  - `AgentState.getPermissionContext()` getter 是否存在、返回可空性 `（需 javap 验证）`；`setPermissionContext(PermissionContextState)` setter（既有调查已确认存在，spike 复核签名）。
  - 下一回合的引擎是否确从会话槽的 `permissionContext` 重建（`setPermissionMode` 强隐含为真；spike 以两回合 harness 实证：回合 1 ASK→写回 ALLOW+save→回合 2 同工具→ALLOW）。
  - 时序：`getAgentState(userId,sessionId)` 是否返回与运行中调用同一缓存对象；本回合末尾 save 是否覆盖写回 → 定写回时点（D3 时序）。
  - **若无干净的写回 API / 下一回合不读槽上下文**：改走「pig 侧 per-session allow-store + 选 `a` 时轻量重建当前会话 agent（共享 state store 保守会话）」的替代——但会话级重建比就地写回重，属兜底。spike 须给出可行结论择一。
- **产出**：javap 确认清单（已确认 vs 仍未知），并据结论定 D3 的最终 seam（就地写回 优先；重建 兜底）。

## 安全（security-reviewer 须签字）

- **移除 per-tool ASK 规则的威胁模型（P1 核心）**：是否有工具因此**丢失确认**？——否。移除后依 native 模式默认：交互（`DEFAULT`/`ACCEPT_EDITS`）无匹配规则的可变工具落 **ASK3**（仍确认）；非交互（`DONT_ASK`）落 **DENY**（仍 fail-closed）。唯一「行为等价替换」：非交互原来的「ASK→显式 DENY 规则」被「`DONT_ASK` 模式默认 DENY」取代——同为 DENY，净行为不变（spike S1 复核）。`executeCommand` 早已这样跑并生产验证。
- **fail-closed 不变量（渠道/自主）**：`effectiveNativeMode`（非交互非 bypass → `DONT_ASK`）**不改**；移除 ASK 规则后无匹配规则的工具仍被 `DONT_ASK` 默认拒。渠道独立 agent + 无 confirmer 姿态不变。
- **plan / Plan-Mode 只读**：`plan` 判定为 DENY（非 ASK），DENY 规则照发；Plan-Mode 读只读由中间件按 `isReadOnly()` 独立强制——均不受本变更影响。
- **deny 规则 / 危险路径不可绕过**：D2 只删「ASK 判定」分支的发规则，不碰 deny 规则；D3 写回只**新增** ALLOW，重建副本时 deny/ask 规则原样保留、且 `deny>ask>allow` 使新增 ALLOW 永不越过 deny/危险路径（native 内建检查不可绕过，含 `bypass`）。
- **「始终允许」作用域 = 本会话**：D3 按 `(userId,sessionId)` 落槽，不隐式泛化到其它会话；跨会话/跨重启须用户经 `rememberTool`（选 `a` 自动写配置 allowlist）或 `/permission allow` 显式持久。避免「一处放行、全局松绑」。
- **凭据**：ALLOW 规则 `ruleContent=null`（匹配该工具全部调用），不含命令/参数，无凭据外泄面；`source="user:always"` 无敏感值。
- **幂等**：重复选 `a` 至多一条等效 ALLOW，杜绝规则堆叠导致的意外放大。

## Risks / Trade-offs

- **R1（P2 写回时序竞态）**：若写回与本回合末尾 save 竞争，可能丢写或读到旧槽。→ D3 取「invocation 结束后再写回 + save」的无竞态时序；spike S2 定案。
- **R2（就地写回 API 若不可用）**：退化到「per-session allow-store + 会话级 agent 重建」（较重，但共享 state store 保守会话）。spike S2 择一，proposal/design 已声明兜底。
- **R3（native `applyConfirmResults` 不写回是框架行为）**：本变更不改框架、在 pig 侧补写回——若未来 2.0 版本自带写回，pig 的回合级写回将幂等冗余（无害），届时可精简。
- **R4（真实多回合确认质量）**：离线 harness 证结构正确性；真模型多回合确认链路属 live-model → 权限 `*IT`（`/ls:itest`，扩 `PermissionEnforcementIT`/`FullLinkAgentIT`）。

## 落实追踪表（发现项/决策 → 落点 + 状态）

| 发现项 / 决策 | 落点 | 状态 |
|---|---|---|
| 主 spec 承诺「始终允许后免确认」未兑现（同会话下回合再问） | delta：MODIFIED `ask 模式人工确认`（跨回合免确认 + 首问保留） | 本 spec 修复 |
| P1 ASK 遮蔽（`deny>ask>allow`） | D2；delta ADDED「移除 per-tool ASK 遮蔽，改依模式默认」；`PermissionContextFactory.addRule` 推广 M-1 豁免 | 已设计（spike S1 复核兜底断言） |
| P2 跨回合不持久（`applyConfirmResults` 不写回 `AgentState`） | D3；delta ADDED「跨回合持久化到会话权限上下文」；`PigAgent` 会话级写回 seam | 已设计（spike S2 收口 API/时序） |
| fix #1：`a` 分支挂 ALLOW 规则（3 参 `ConfirmResult`）+ 保留 `rememberTool` | D1；`AgentRepl.confirm()` | 已设计 |
| 复用 `SubagentPermissions` 读→重建副本模式（不重造） | D3 seam | 已设计 |
| 复用 `getAgentState`/`saveAgentState` 通路（`clearConversation`/`copyConversation` 已用） | D3 seam | 已设计 |
| 前端只依赖 `AgentKernel` 门面（不直连 registry/内部） | D3；新增门面方法 | 已设计 |
| 两层持久化（回合级 vs 重启级）职责划分 | D4 表 | 已设计 |
| fail-closed / plan 只读 / deny 不可绕过 / bypass 语义保全 | 安全段 | 已设计（security-reviewer 签字） |
| 「始终允许」作用域 = 本会话 | D3 + delta「按会话隔离」场景 | 已设计 |
| 写回时序竞态 | R1 + D3 时序 | 定 spike S2 |
| 就地写回 API 若不可用 → 会话级重建兜底 | R2 + spike S2 | 兜底已声明 |
| 真实多回合确认链路 | R4 | 延后 `/ls:itest`（权限 `*IT`） |

## openspec 落点

- 变更目录：`openspec/changes/permission-always-allow-persist/`（`proposal.md` + 本 `design.md` + `tasks.md` + `specs/tool-permissions/spec.md` delta）。
- **修改能力** `tool-permissions`（MODIFIED `ask 模式人工确认` + 2 条 ADDED）；不新增能力。
- 归档（`/ls:archive`）：把 delta 同步进 `openspec/specs/tool-permissions/spec.md`，变更移到 `openspec/changes/archive/`。
- `openspec validate permission-always-allow-persist --strict` 须通过。
