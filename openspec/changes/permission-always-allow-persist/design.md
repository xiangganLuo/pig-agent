# permission「始终允许」跨回合持久化 设计（安全敏感 + 架构性 bug 修复）

> 阶段：`/ls:spec`。分支 `bug/20260717-permission-always-allow`（隔离 worktree，off `main`；提交信息用 conventional-commit `fix:`）。
> 修的是既有 `tool-permissions` 能力**已承诺但未兑现**的行为（主 spec `始终允许后免确认` = `之后相同调用不再确认`）。
> 承重假设经既有调查以**真实 2.0 jar + 离线两回合 PigAgent harness** 证实；本设计在其上构建，`（需 javap 验证）` 标注仍未确认的签名。

## ⚠️ Spike 结论（已执行，改变设计 —— 需 LEAD 安全门重新签字）

`/ls:code` 第 1 组 spike 已用**真实 2.0 jar javap + 离线 PigAgent 两回合 harness**（`PermissionAlwaysAllowSpikeTest`，7 测试全绿）执行完毕，**推翻了原批准的 P1 机制**：

- **javap 全部确认**（见下 D1/D3）：`AgentState.getPermissionContext()/setPermissionContext(...)`、`ReActAgent.getAgentState/saveAgentState/setPermissionMode(RuntimeContext|String,String,mode)`、`ConfirmResult(boolean,ToolUseBlock,List<PermissionRule>)`+`getRules()`、`PermissionRule` record ctor、`PermissionContextState.builder()/withMode/getMode/getAllowRules/getDenyRules/getAskRules/getWorkingDirectories`——**均存在、签名确认**。`ReActAgent.permissionEngineCache` 是 `ConcurrentHashMap` + `computeIfAbsent`（per-slot 引擎缓存），`setPermissionMode` 里 `permissionEngineCache.put(...)` 强制刷新。
- **承重反证（S1 被推翻）**：原批准的 P1「移除 per-tool ASK 规则、改依 native 模式默认兜底」**不可行**。原因：`PermissionEngine.checkPermission` 对 `DEFAULT` 模式无规则的可变工具**确实返回 ASK**（引擎层 S1 成立），**但 agent 的 ReAct 循环只在命中显式 ASK 规则（或内建 ASK 检查）时才暂停弹 HITL**；对纯模式默认 ASK，**agent 不暂停、直接执行工具**（`modeDefaultAsk_agentExecutes_noHitl` 证：toolInvoked=1、无 `RequireUserConfirmEvent`）。故**删除 ASK 规则 = 可变工具无确认直接执行 = 安全回归**，正是 LEAD 批准时要求避免的。对照 `explicitAskRule_agentSurfacesHitl`：有显式 ASK 规则才弹 HITL、工具不执行。
- **修正机制（已证，替代 P1）**：**保留全局 per-tool ASK 规则**（所有工具首问不变），选 `a` 时改为在**会话槽**做**逐工具 ASK→ALLOW 置换**——删除**该工具**的 ASK 规则 + 加一条 ALLOW 规则，再（determinism 保险）经 `setPermissionMode(userId,sessionId,sameMode)` 刷新引擎缓存并保存。`swapAskForAllow_withCacheRefresh_autoAllowsNextTurn` 证：回合 2 自动放行；`addAllowButKeepAskRule_stillAsks` 证：若不删 ASK 规则、`deny>ask>allow` 遮蔽 → 回合 2 仍问（**删 ASK 是必要的**）；`swap_isPerSession` 证：作用域=本会话；`swapAlsoTakesEffectWithoutExplicitRefresh` 证：裸 `setPermissionContext+saveAgentState` 也能跨回合生效（每回合从持久上下文重建引擎），刷新仅作 determinism 保险。

**设计变更影响**：D2/P1 由「全局移除 ASK 规则」→「**保留 ASK 规则 + 会话级逐工具 ASK→ALLOW 置换**」（更保守、更安全，全局首问不受损）；delta spec 的 ADDED「移除 per-tool ASK 遮蔽，改依模式默认兜底」需**改写**为「会话级逐工具 ASK→ALLOW 置换」，`PermissionContextFactory` **不再改动**（保留 ASK 规则）。**此为安全敏感的设计变更，已在 spike 门停下，待 LEAD 重新签字后再实现产品代码。** 下文 D2、安全段、落实追踪表按修正机制标注；spike 测试 `PermissionAlwaysAllowSpikeTest` 已提交为证据。

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

### D2（fix #2 / P1）—— ~~移除 per-tool ASK 遮蔽，改依模式默认~~ 【被 spike 推翻】→ 会话级逐工具 ASK→ALLOW 置换
> **原方案（已废弃）**：把 `PermissionContextFactory.addRule` 的 M-1「decision==ASK → 不发规则」豁免推广到所有工具，靠 native 模式默认兜底。**spike 证其不可行**：agent 对纯模式默认 ASK **不弹 HITL、直接执行工具**（只有显式 ASK 规则或内建 ASK 检查才触发暂停），删规则 = 可变工具无确认执行 = 安全回归。

**修正方案（spike 已证，`PermissionAlwaysAllowSpikeTest` 全绿）**：`PermissionContextFactory` **不改**（**保留**所有 per-tool ASK 规则 → 全局首问不变）。「移除遮蔽」下沉到 **D3 的会话级写回**：选 `a` 时，在**当前会话槽**做**逐工具 ASK→ALLOW 置换**——从该槽上下文中**删除该工具的 ASK 规则** + **加一条 ALLOW 规则**。这样：
  - 首次调用：ASK 规则在 → 弹 HITL 确认（不变，对**所有**工具）。`explicitAskRule_agentSurfacesHitl` 证。
  - 选 `a` 后同会话后续回合：该工具无 ASK 规则 + 有 ALLOW 规则 → 引擎 ALLOW → agent 自动执行。`swapAskForAllow_withCacheRefresh_autoAllowsNextTurn` 证。
  - 其它工具：ASK 规则仍在 → 仍逐个确认（遮蔽只对被 `a` 的那个工具、且只在本会话解除）。
- **为何必须删 ASK 而非仅加 ALLOW**：`deny>ask>allow`——若保留 ASK 规则只加 ALLOW，ASK 恒遮蔽 ALLOW → 回合 2 仍问。`addAllowButKeepAskRule_stillAsks` 证（删 ASK 是必要的）。
- **安全评审重点**：此为安全敏感项（见「安全」段），须 security-reviewer 签字。**关键：本修正方案严格更安全——全局 ASK 规则全部保留，仅对用户显式选 `a` 的工具、仅在其所在会话槽解除遮蔽；不存在原方案「全局删 ASK」的首问丢失面。**

### D3（fix #3 / P2）—— 回合级持久化「始终允许」到会话权限上下文（含 ASK→ALLOW 置换）
选 `a` 时，在**当前会话槽 `(userId,sessionId)` 的持久 `AgentState.permissionContext`** 里做逐工具 ASK→ALLOW 置换，下一回合从该槽重建的引擎即放行。**spike 已全部 javap 确认 + 两回合 harness 实证。**
- **seam（复用 `SubagentPermissions.deriveChildContext` 模式）**：pig 新增会话级方法（落 `PigAgent`，经 `AgentKernel` 门面暴露，供 `AgentRepl` 调用——前端只依赖门面）：
  1. `AgentState state = reactAgent.getAgentState(USER_ID, sessionId)`（现成，javap 确认）。
  2. `PermissionContextState cur = state.getPermissionContext()`（**javap 确认 getter 存在**；null → 以最小 `DEFAULT`/`DONT_ASK` 兜底）。
  3. 用 `PermissionContextState.builder()` 重建 `cur` 的副本：拷 mode + working dirs + allow 规则 + deny 规则 + **ask 规则（但跳过该工具的 ask 规则）**，再 `addAllowRule(tool, new PermissionRule(tool, null, ALLOW, "user:always"))`。**幂等**：该工具已有等效 ALLOW 则整体为 no-op。
  4. `state.setPermissionContext(updated)`（**javap 确认 setter**）+ `reactAgent.setPermissionMode(USER_ID, sessionId, updated.getMode())`（刷新 per-slot 引擎缓存 `permissionEngineCache.put(...)` + `saveAgentState`；`withMode(sameMode)` 保留规则）。
- **为何下一回合生效（spike 实证）**：`ReActAgent.permissionEngineCache` 是 per-slot `computeIfAbsent` 引擎缓存，每回合从 `AgentState.getPermissionContext()` 派生引擎；`swapAskForAllow_withCacheRefresh_autoAllowsNextTurn` 证回合 2 自动放行。`swapAlsoTakesEffectWithoutExplicitRefresh` 另证：裸 `setPermissionContext+saveAgentState` 也已跨回合生效（引擎每回合重建），故 `setPermissionMode` 刷新为 **determinism 保险**（保证本回合内/边界缓存一致），非硬需求——产品取用刷新路径以确定性。
- **时序（无竞态）**：写回在整个 `renderTurn`（含 HITL resume）**完成之后**、对本回合选过 `a` 的工具集执行（此时本次 invocation 已结束，读槽→置换→存槽不与运行中的引擎竞争）。`getAgentState`/`saveAgentState` 与本回合末尾 `saveCurrent()` 操作同一 slot，幂等无覆盖。
- **作用域 = 本会话**：写回按 `(userId,sessionId)` 落槽，天然按会话隔离（`swap_isPerSession` 证：另一会话槽无此置换 → 仍按其模式/风险判定弹确认）。跨会话/跨重启由 `rememberTool` 写的配置 allowlist 在下次 build 生效承担（D1 保留它的原因）。

### D4 —— 两层持久化职责划分（回合级 vs 重启级）
| 持久层 | 载体 | 生效时机 | 作用域 | 谁写 |
|---|---|---|---|---|
| 回合级（本 spec 新增，D3） | 会话槽 `AgentState.permissionContext` 的 ASK→ALLOW 置换（删该工具 ASK + 加 ALLOW） | **本会话下一回合**（引擎从槽重建） | 本会话 | 选 `a` 时的会话级写回 |
| 重启级（既有，D1 保留） | 配置 `permissions.allowlist.tools`（`rememberTool`） | **下次 agent build/重启**（`PermissionPolicy`：allowlisted→ALLOW 决策 → 发 ALLOW 规则**且不发 ASK 规则**，故无遮蔽，`PermissionContextFactory` 无需改动） | 全局 | `rememberTool` |

二者互补：单靠配置 allowlist 不重建当前会话 → 本回合/下回合不生效（即当前 bug）；单靠回合级写回 → 不跨重启。两者都要。

## Spike（tasks 第 1 组，承重、卡点 —— 已执行，见顶部「⚠️ Spike 结论」）

**已执行完毕**（`pig-agent-core` 的 `PermissionAlwaysAllowSpikeTest`，7 测试全绿；javap over `agentscope-core-2.0.0.jar`）。结论摘要：

- **S1 结果（推翻原假设）**：引擎层 `PermissionEngine.checkPermission`（`DEFAULT` 无规则可变工具）返回 **ASK**，但 **agent 层对纯模式默认 ASK 不弹 HITL、直接执行**——只有**显式 ASK 规则**（或内建 ASK 检查）才触发 agent 暂停。故「移除 ASK 规则依模式默认」**不可行**（安全回归）。`DONT_ASK` 仍 DENY（fail-closed）成立。→ **改为保留 ASK 规则 + 会话级逐工具 ASK→ALLOW 置换**（D2 修正、D3）。
- **S2 结果（write-back 可行，API 全确认）**：`AgentState.getPermissionContext()/setPermissionContext(...)`、`ReActAgent.getAgentState/saveAgentState/setPermissionMode`、`ConfirmResult(bool,ToolUseBlock,List<PermissionRule>)+getRules()`、`PermissionRule` ctor、`PermissionContextState.builder/withMode/get*Rules` **全部 javap 确认**。两回合 harness 实证：会话槽置换 ASK→ALLOW → 下回合自动放行（有/无 `setPermissionMode` 刷新均生效，产品取刷新路径作 determinism 保险）。**R2 兜底（会话级重建）无需采用**——就地写回可行。
- **净结论**：write-back（P2）可行；原 P1（删 ASK 依模式默认）不可行，已由更安全的会话级置换替代。**此设计变更已在 spike 门停下，报 LEAD 重新签字。**

## 安全（security-reviewer 须签字）

- **威胁模型（修正机制）**：是否有工具**丢失首次确认**？——**否，且比原方案更安全**。`PermissionContextFactory` **不改**：所有 per-tool ASK 规则全部保留 → 任何工具首次调用仍弹 HITL（`explicitAskRule_agentSurfacesHitl` 证）。仅当用户对某工具**显式选 `a`**，才在**其所在会话槽**删除**该工具**的 ASK 规则并加 ALLOW（其它工具、其它会话不受影响）。不存在原 P1「全局删 ASK → 可变工具无确认执行」的安全回归面（该回归已被 `modeDefaultAsk_agentExecutes_noHitl` spike 捕获并规避）。
- **fail-closed 不变量（渠道/自主）**：`effectiveNativeMode`（非交互非 bypass → `DONT_ASK`）**不改**；`PermissionContextFactory` 不改，非交互 ASK→DENY 规则照旧。渠道/自主无 confirmer、不做会话级置换（`a` 只在交互 REPL 产生）→ 姿态不变、fail-closed 不变。
- **plan / Plan-Mode 只读**：`plan` 判定为 DENY（非 ASK），DENY 规则照发；Plan-Mode 读只读由中间件按 `isReadOnly()` 独立强制——均不受本变更影响。
- **deny 规则 / 危险路径不可绕过**：会话级置换只**删被 `a` 工具的 ASK 规则 + 加其 ALLOW**，**不碰 deny 规则**；重建副本时 deny 规则原样保留，`deny>ask>allow` 使新增 ALLOW 永不越过 deny/危险路径（native 内建检查不可绕过，含 `bypass`）。
- **「始终允许」作用域 = 本会话**：D3 按 `(userId,sessionId)` 落槽，不隐式泛化到其它会话（`swap_isPerSession` 证）；跨会话/跨重启须用户经 `rememberTool`（选 `a` 自动写配置 allowlist）或 `/permission allow` 显式持久。避免「一处放行、全局松绑」。
- **凭据**：ALLOW 规则 `ruleContent=null`（匹配该工具全部调用），不含命令/参数，无凭据外泄面；`source="user:always"` 无敏感值。
- **幂等**：重复选 `a` 至多一条等效 ALLOW，杜绝规则堆叠导致的意外放大。

## Risks / Trade-offs

- **R0（spike 推翻原 P1，设计变更 — 已停门升级）**：原批准的「删 ASK 依模式默认」经 spike 证不可行（安全回归）；已替换为更安全的会话级 ASK→ALLOW 置换。**待 LEAD 安全门重新签字后再实现产品代码 + 改写 delta/proposal。**
- **R1（写回时序）**：写回在 `renderTurn` 完成后执行（无竞态，见 D3）；spike 证有/无 `setPermissionMode` 刷新均跨回合生效，产品取刷新路径作 determinism 保险。
- **R2（就地写回 API — 已消解）**：spike 确认 `AgentState.get/setPermissionContext` + `setPermissionMode` 可行，**无需**采用会话级重建兜底。
- **R3（native `applyConfirmResults` 不写回是框架行为）**：本变更不改框架、在 pig 侧补写回——若未来 2.0 版本自带写回，pig 的回合级写回将幂等冗余（无害），届时可精简。
- **R4（真实多回合确认质量）**：离线 harness 证结构正确性；真模型多回合确认链路属 live-model → 权限 `*IT`（`/ls:itest`，扩 `PermissionEnforcementIT`/`FullLinkAgentIT`）。

## 落实追踪表（发现项/决策 → 落点 + 状态）

| 发现项 / 决策 | 落点 | 状态 |
|---|---|---|
| 主 spec 承诺「始终允许后免确认」未兑现（同会话下回合再问） | delta：MODIFIED `ask 模式人工确认`（跨回合免确认 + 首问保留） | 本 spec 修复 |
| P1 ASK 遮蔽（`deny>ask>allow`） | ~~D2 原案「删 ASK 依模式默认」~~ **spike 推翻**（安全回归）→ 会话级逐工具 ASK→ALLOW 置换（D2 修正 + D3）；`PermissionContextFactory` 不改 | **spike 已证；待 LEAD 重签 + 改写 delta** |
| P2 跨回合不持久（`applyConfirmResults` 不写回 `AgentState`） | D3；delta ADDED「跨回合持久化到会话权限上下文」；`PigAgent` 会话级写回 seam | spike 已证（API 全确认 + 两回合 harness） |
| **spike 发现：mode-default ASK 不弹 agent HITL（只有显式 ASK 规则弹）** | 顶部「⚠️ Spike 结论」+ D2 修正 + `PermissionAlwaysAllowSpikeTest` | 已证（7 测试全绿） |
| fix #1：`a` 分支挂 ALLOW 规则（3 参 `ConfirmResult`）+ 保留 `rememberTool` | D1；`AgentRepl.confirm()` | 已设计 |
| 复用 `SubagentPermissions` 读→重建副本模式（不重造） | D3 seam | 已设计 |
| 复用 `getAgentState`/`saveAgentState` 通路（`clearConversation`/`copyConversation` 已用） | D3 seam | 已设计 |
| 前端只依赖 `AgentKernel` 门面（不直连 registry/内部） | D3；新增门面方法 | 已设计 |
| 两层持久化（回合级 vs 重启级）职责划分 | D4 表 | 已设计 |
| fail-closed / plan 只读 / deny 不可绕过 / bypass 语义保全 | 安全段 | 已设计（security-reviewer 签字） |
| 「始终允许」作用域 = 本会话 | D3 + delta「按会话隔离」场景 | spike 已证（`swap_isPerSession`） |
| 写回时序 | R1 + D3 时序（renderTurn 后写回） | 已设计（无竞态） |
| 就地写回 API 可行性 | R2 + spike | 已证可行（无需会话级重建兜底） |
| 真实多回合确认链路 | R4 | 延后 `/ls:itest`（权限 `*IT`） |

## openspec 落点

- 变更目录：`openspec/changes/permission-always-allow-persist/`（`proposal.md` + 本 `design.md` + `tasks.md` + `specs/tool-permissions/spec.md` delta）。
- **修改能力** `tool-permissions`（MODIFIED `ask 模式人工确认` + 2 条 ADDED）；不新增能力。
- 归档（`/ls:archive`）：把 delta 同步进 `openspec/specs/tool-permissions/spec.md`，变更移到 `openspec/changes/archive/`。
- `openspec validate permission-always-allow-persist --strict` 须通过。
