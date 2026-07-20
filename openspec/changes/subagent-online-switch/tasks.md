## 1. Spike（先做，卡点 — load-bearing gate）

- [x] 1.1 javap/sources-jar 确认 2.0.0 原生签名：`ChatUiChannel.sendToSubagent`/`sendToSubagentStream`（→ `HarnessGateway.runSubagent(Stream)(subagentId, List<Msg>)`）；`SubagentExposedEvent` getters（`getSubagentId/getAgentId/getSessionId/getLabel`）；`HarnessAgent.channel(T)`/`gateway()`（同一 lazy `ensureGateway()`）；`agent_spawn` 的 `expose_to_user` 参数 + `withSubagentExposedEvent` 发事件。
- [x] 1.2 确认枚举方式：网关**无**列出 exposed 子agent 的 API → 从流上的 `SubagentExposedEvent` 追踪。
- [x] 1.3 **关键风险**：写离线 PoC 证明绑定网关不回归交互流路径——`SubagentExposeSpikeTest` gate 1（subagents 开启即 eager `gateway()`，普通 `stream("hi")` 仍产出模型答案）。结论：**PROCEED**。
- [x] 1.4 PoC gate 2：脚本化 fake model 驱动完整 expose→switch（父 spawn `expose_to_user=true` → 捕获 `SubagentExposedEvent` 的 subagentId → `streamSubagent(id,msg)` 子agent应答 "CHILD_REPLY"）。

## 2. pig-agent-core：PigAgent + AgentKernel 门面

- [x] 2.1 `PigAgent.build()`：当 `subagentsEnabled` 时 eager `harness.gateway()`（wire expose bridge；try/catch 容错，绝不阻断构建）。
- [x] 2.2 `PigAgent.streamSubagent(subagentId, msg)`：经 `harness.gateway().runSubagentStream(...)` 路由；null/blank id → error `Flux`（不抛异常）。
- [x] 2.3 新增 `ExposedSubagent` 值类型（id/agentId/label + `display()`）。
- [x] 2.4 `AgentKernel`：`noteSubagentExposed`/`listSubagents`/`subagentOutput`/`chatWithSubagent`（interrupt 包裹，同 `chat`）；`useAgent`/active-`updateAgent` 时清空已追踪的 exposed 子agent。
- [x] 2.5 单测：`SubagentExposeSpikeTest`（3）、`AgentKernelSubagentTest`（tracking + clear + 真网关路由，3）。回归 `AgentKernelTest`/`SubagentDelegationTest`/`PigAgentTest` 绿。

## 3. pig-agent-cli：/agent sub 命令 + REPL switch 状态机

- [x] 3.1 新增 `SubagentSwitchState`（纯状态机：switchTo/back/current/isActive）。
- [x] 3.2 `ReplContext` 加 `subagentSwitch` 字段 + 向后兼容 16 参构造（旧调用点/测试不动）。
- [x] 3.3 `AgentCommand` 加 `sub list|view <id>|switch <id>|back`（switch 用 `kernel.subagentOutput(id)` 校验）；更新 `@Command`/usage。
- [x] 3.4 `AgentRepl`：拥有并共享 `SubagentSwitchState`；`onEvent` 的 `SubagentExposedEvent` → `kernel.noteSubagentExposed(...)` + switch 提示；`runTurn` 在 switch 态路由到 `chatWithSubagent`（不记父会话/不压缩）；prompt 显示 `[sub <id>]`；陈旧 id 回退父 agent。
- [x] 3.5 单测：`SubagentSwitchStateTest`（5）、`AgentCommandSubTest`（6）、`AgentReplSubagentSwitchTest`（3）。回归 `AgentReplTurnTest`/`AgentCommandTest`/`ReplCommandsTest`/`CommandDispatchTest` 绿。

## 4. 验收

- [x] 4.1 `mvn -pl pig-agent-cli -am test`（本 change 相关类）GREEN。
- [ ] 4.2 集成测试（`*IT`）：真模型 spawn `expose_to_user=true` + 真 switch（外环 `/ls:itest`）。
- [x] 4.3 openspec change `subagent-online-switch`（proposal/design/tasks + delta spec，`validate --strict`）。
