## 1. Spike（承重、卡点 —— 已执行，全绿）

- [x] 1.1 **S1**：真实 2.0 jar + 离线 harness 复核——发现 mode-`DEFAULT` 引擎返回 ASK 但 **agent 对纯模式默认 ASK 不弹 HITL、直接执行工具**（只有显式 ASK 规则才暂停）→ 原 P1「删 ASK 依模式默认」不可行（安全回归）。`DONT_ASK` 仍 DENY（fail-closed）成立。（`PermissionAlwaysAllowSpikeTest`）
- [x] 1.2 **S2**：javap 确认 `AgentState.getPermissionContext()/setPermissionContext(...)`、`ReActAgent.getAgentState/saveAgentState/setPermissionMode`、`ConfirmResult` 3 参 +`getRules()`、`PermissionRule` ctor、`PermissionContextState.builder/withMode/get*Rules`；两回合 harness 实证会话槽 ASK→ALLOW 置换 → 下回合放行。
- [x] 1.3 **S2 时序 / 缓存**：`ReActAgent.permissionEngineCache` 为 per-slot `computeIfAbsent`；`setPermissionMode(sameMode)` 以 `.put(...)` 强制刷新 + save。裸 `setPermissionContext+save` 亦跨回合生效（引擎每回合重建），产品取 `setPermissionMode` 刷新作 determinism 保险；写回时点定为 `renderTurn` 完成后（无竞态）。
- [x] 1.4 **结论**：write-back 可行、无需会话级重建兜底；原 P1 改为**会话级逐工具 ASK→ALLOW 置换**（`PermissionContextFactory` 不动）。结论已写回 `design.md`；delta/proposal 已同步；LEAD 安全门已重新签字。

## 2. `a` 分支挂 ALLOW 规则（fix #1，仅改 `AgentRepl.confirm()`）

- [x] 2.1 `AgentRepl.confirm()` 的 `a` 分支：改用 3 参 `ConfirmResult(true, call, List.of(new PermissionRule(call.getName(), null, PermissionBehavior.ALLOW, "user:always")))`；保留 `rememberTool(call.getName())`（跨重启）。**只动了 `confirm()` 的 `a` 分支**（+ 2 行 permission import），未碰 `renderStream`/`onEvent`/dispatch。
- [x] 2.2 单测 `AgentReplConfirmAlwaysTest`（3）：`a` 分支产出的 `ConfirmResult.getRules()` 含该工具 ALLOW 规则 + 调门面 `allowToolForSession(activeId, sid, tool)`；`y`/`N` 分支不带规则、不调门面、语义不变。

## 3. 会话级 ASK→ALLOW 置换 + 跨回合持久化（D3，仅 ADD `PigAgent.allowToolForSession`）

- [x] 3.1 `PigAgent` **新增** `allowToolForSession(String sessionId, String toolName)` + 私有 `withToolAlwaysAllowed(...)`：`getAgentState(USER_ID, sessionId)` → 读上下文 → builder 重建（拷 mode + working dirs + allow + deny + ask**跳过该工具 ask**；丢弃该工具旧 `user:always` ALLOW 以幂等）+ `addAllowRule(tool, PermissionRule(tool,null,ALLOW,"user:always"))` → `setPermissionContext` → `setPermissionMode(sameMode)` 刷新缓存 + 保存。null 上下文兜底 `DEFAULT`；失败记 warn 不崩。**只 ADD，未改 `PigAgent` 其它方法。**
- [x] 3.2 `AgentKernel` 门面**新增** `allowToolForSession(String agentId, String sessionId, String toolName)` → `registry.get(agentId).orElse(active())` → `instance.agent().allowToolForSession(...)`；unknown → no-op。
- [x] 3.3 `AgentRepl.confirm()`：`a` 分支从字段读 `agentKernel.activeId()` + `sessionManager.getCurrentSessionId()` 调门面（confirm 在两次 stream 之间、turn 暂停时执行 → 无竞态）；未改 `renderTurn` 签名/其它路径。
- [x] 3.4 单测 `PigAgentAllowToolForSessionTest`（9）：置换后该工具无 ASK、含一条 ALLOW；另一工具 ASK 规则保留；幂等（多次仅一条 ALLOW、不残留 ASK）；deny 规则保留；blank 工具名 no-op。

## 4. 真实两回合离线回归 + 安全不变量（验收）

- [x] 4.1 两回合场景回归（`PigAgentAllowToolForSessionTest.turn2AutoAllowsSameTool`，真实 `allowToolForSession` 路径）：回合 1 调 T→ask→approve→`allowToolForSession(T)`→回合 2 同 T→**无 HITL、执行**。
- [x] 4.2 安全不变量守卫：`otherToolStillAsksAfterAllow`（另一工具仍问）、`firstEverCallStillAsks`（首问）、`nonInteractiveDontAskStaysFailClosed`（`DONT_ASK` 仍拒）、`allowIsPerSession`（另一会话仍问）。
- [x] 4.3 affected 模块 `mvn -pl pig-agent-cli -am test` 全绿（core + cli，含新增 12 测试 + spike 7）；`mvn -pl pig-agent-cli -am compile` 绿。

## 5. 集成测试（真模型 `*IT`，外环 —— `/ls:itest`）

- [ ] 5.1 权限 `*IT`（扩 `PermissionEnforcementIT`/`FullLinkAgentIT`，真模型）：真实多回合确认链路——同会话对某工具选 `a` 后，后续回合该工具免确认；首问与非交互 fail-closed 仍成立（延后 `/ls:itest`）。

## 6. 验收 + 文档

- [ ] 6.1 `openspec validate permission-always-allow-persist --strict` 通过。
- [ ] 6.2 code-review + **security-reviewer 签字**：确认 `PermissionContextFactory` 未改、无工具丢失确认、fail-closed/plan/deny/bypass 不变量保全。
- [ ] 6.3 `CLAUDE.md` 权限段落同步：「始终允许」跨回合持久化（会话级 ASK→ALLOW 置换 + 配置 allowlist 两层）。
- [ ] 6.4 归档（`/ls:archive`）：同步 delta 进 `openspec/specs/tool-permissions/spec.md`，变更移 `openspec/changes/archive/`。
