## 1. Spike（承重、卡点 —— 不过不进编码）

- [ ] 1.1 **S1 / P1 兜底断言**：以真实 2.0 jar + 离线 harness 复核——移除显式 per-tool ASK 规则后，非平凡上下文里 mode-`DEFAULT`（交互）可变工具（无 allow/ask/deny 规则）**仍抬起 HITL `RequireUserConfirmEvent`**（首问保留）；`DONT_ASK`（非交互）基线下同类工具**仍 DENY**（fail-closed 保留）。产出可复用为回归的最小 harness。
- [ ] 1.2 **S2 / P2 架构未知**：javap 确认 `AgentState.getPermissionContext()`（getter 名/可空性，`（需 javap 验证）`）与 `setPermissionContext(PermissionContextState)`（复核签名）；两回合 harness 实证「写回 ALLOW 到 `(userId,sessionId)` 槽 + save → 下一回合引擎放行」（下回合确从会话槽 `permissionContext` 重建）。
- [ ] 1.3 **S2 时序**：确认 `getAgentState(userId,sessionId)` 是否返回与运行中调用同一缓存对象、本回合末尾 `saveCurrent()` 是否覆盖写回 → 定写回时点（默认「invocation 结束后写回+save」的无竞态时序）。
- [ ] 1.4 **结论与择路**：若无干净就地写回 API / 下回合不读槽上下文 → 采「pig 侧 per-session allow-store + 会话级 agent 重建（共享 state store 保守会话）」兜底。产出 javap 确认清单（已确认 vs 未知）并据此定 D3 最终 seam。**卡点：S1/S2 结论未定不进第 2 组。**

## 2. 移除 per-tool ASK 遮蔽（P1，安全敏感）

- [ ] 2.1 `PermissionContextFactory.addRule`：把 M-1 对 `executeCommand` 的「decision==ASK → 不发规则」豁免**推广到所有工具**（任何 `decision==ASK` 一律 `return` 不发 per-tool 规则）；`DENY`（plan）/`ALLOW`（bypass/allowlisted）分支不变。更新类/方法 javadoc（记录推广后依模式默认兜底的语义）。
- [ ] 2.2 单测 `PermissionContextFactoryTest`：`ask` 下 WRITE/NETWORK 工具**不再**产生 ask 规则；`plan` 仍发 DENY 规则；`bypass`/allowlisted 仍发 ALLOW；非交互（interactive=false）不再发「ASK→DENY」显式规则（依 `DONT_ASK` 兜底）。
- [ ] 2.3 **安全回归**（复用 1.1 harness）：交互 ASK 工具移除规则后仍弹 HITL；非交互仍 fail-closed DENY；`plan` 只读不受影响；deny 规则/危险路径仍不可绕过（含 `bypass`）。
- [ ] 2.4 `PermissionPolicyTest`：确认判定矩阵未改（本组只改「是否发规则」不改判定表），既有断言全绿。

## 3. `a` 分支挂 ALLOW 规则（fix #1）

- [ ] 3.1 `AgentRepl.confirm()` 的 `a` 分支：改用 3 参 `ConfirmResult(true, call, List.of(new PermissionRule(call.getName(), null, PermissionBehavior.ALLOW, "user:always")))`；保留 `rememberTool(call.getName())`（跨重启）。
- [ ] 3.2 单测：`a` 分支产出的 `ConfirmResult.getRules()` 含该工具的 ALLOW 规则（`source="user:always"`、`ruleContent=null`）；`y`/`N` 分支不带规则、语义不变。

## 4. 跨回合持久化到会话权限上下文（P2 / fix #3）

- [ ] 4.1 `PigAgent` 新增会话级方法（如 `allowToolForSession(toolName, sessionId)`）：`getAgentState(USER_ID, sessionId)` → 读现有 `PermissionContextState` → 用 `PermissionContextState.builder()` 重建副本（拷 mode + allow/deny/ask + working dirs，**幂等**跳过已有等效 ALLOW）+ `addAllowRule` → `setPermissionContext` → `saveAgentState(USER_ID, sessionId)`。复用 `SubagentPermissions.deriveChildContext` 的读→重建模式；容错（null 上下文兜底、失败记 warn 不崩）。
- [ ] 4.2 `AgentKernel` 门面加对应方法（如 `allowToolForSession(agentId, toolName, sessionId)`）路由到活动 `PigAgent`；前端只依赖门面。
- [ ] 4.3 `AgentRepl`：选 `a` 时（按 spike S2 定的时序，默认 invocation 结束后）对本回合选过 `a` 的工具集调门面持久化到 `sessionManager.getCurrentSessionId()` 槽。
- [ ] 4.4 单测：`PigAgent` 写回后 `getAgentState(sessionId)` 的 `permissionContext` 含该工具 ALLOW 规则；幂等（多次不堆叠）；deny 规则/其它工具把关不被削弱；null/缺失上下文容错。

## 5. 真实两回合离线回归（验收）

- [ ] 5.1 两回合 harness 回归（复用 1.1/1.2）：回合 1 调工具 T→模拟用户选 `a`（挂 ALLOW 规则 + 写回会话槽 + save）→回合 2 同工具 T→**引擎放行、不再 HITL**（既有调查证天真修法在此失败，本 spec 应通过）。
- [ ] 5.2 反例守卫：**首次**调用（选 `a` 前）仍抬 HITL；**非交互**回合触发同工具仍 fail-closed DENY；**另一会话**槽无该 ALLOW（作用域=本会话）。
- [ ] 5.3 whole reactor `mvn test` 全绿（报告新增测试数）；`mvn -pl pig-agent-cli -am compile` 绿。

## 6. 集成测试（真模型 `*IT`，外环 —— `/ls:itest`）

- [ ] 6.1 权限 `*IT`（扩 `PermissionEnforcementIT`/`FullLinkAgentIT`，真模型）：真实多回合确认链路——同会话对某工具选 `a` 后，后续回合该工具免确认；首问与非交互 fail-closed 仍成立（延后 `/ls:itest`）。

## 7. 验收 + 文档

- [ ] 7.1 `openspec validate permission-always-allow-persist --strict` 通过。
- [ ] 7.2 code-review + **security-reviewer 签字**（P1 移除 ASK 遮蔽为安全敏感项）：确认无工具丢失确认、fail-closed/plan/deny/bypass 不变量保全。
- [ ] 7.3 `CLAUDE.md` 权限段落同步：「始终允许」跨回合持久化（回合级会话槽写回 + 重启级配置 allowlist 两层）、`PermissionContextFactory` ASK 改依模式默认（推广 M-1）。
- [ ] 7.4 归档（`/ls:archive`）：同步 delta 进 `openspec/specs/tool-permissions/spec.md`，变更移 `openspec/changes/archive/`。
