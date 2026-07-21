## 1. Spike —— 轻量可行性确认（前置，非阻塞门；走主路径无回退）

- [x] 1.1 SP1：纯观察度量 `MiddlewareBase.onActing`（`next.apply(input)` + `doFinally`/`doOnError` 旁路记时/记错）与既有 `ToolCallLoggingMiddleware` **并存**、不改 acting input/结果/事件流。→ `ToolMetricsMiddlewareTest.coexistsWithLoggingMiddleware_withoutChangingItsPassThrough`（innermost 收到原始 input）+ `doesNotRewriteInputNorAlterStream` 绿。
- [x] 1.2 SP2：`ToolAvailabilityGate.evaluate/applyTo` 运行时二次调用可行；重评集合变化触发 `toolsChangedCallback` 同款重建 seam。→ `ToolAvailabilityRefresherTest`（前置满足→重现+rebuild 计数=1）绿；`AgentBootstrap` 复用共享 `rebuildAgents` Runnable。
- [x] 1.3 结论：两条走主路径，无回退；承重点落入组 2/4。

## 2. per-tool 度量聚合（`pig-agent-core`，纯观察 additive）

- [x] 2.1 `io.pigagent.core.metrics.ToolMetricsRegistry`（`ConcurrentHashMap<name, LongAdder×3>`：调用数/累计延迟/错误数；派生 avg/errorRate）；键仅工具名，blank 忽略、负延迟 clamp。
- [x] 2.2 `middleware/ToolMetricsMiddleware`（`onActing`）：`doFinally` 记调用+延迟、`doOnError` 记错误；按 `ToolUseBlock.getName()` 归集；纯观察不改流。
- [x] 2.3 未动 `ToolCallLoggingMiddleware`（两个独立 middleware 并存，SP1 确认）。
- [x] 2.4 `pig-agent-config` `tools.metrics.enabled`（默认 true、纯观察；false → 不接入 metrics middleware）。→ `ToolMetricsConfigTest`（3）绿。
- [x] 2.5 单测：`ToolMetricsRegistryTest`（7，含成功计数+延迟、失败计错、blank 忽略、快照排序、reset）+ `ToolMetricsMiddlewareTest`（5，含空调用透传、错误双计、不改流、SP1）绿。
- [x] 2.6 `mvn -pl pig-agent-core -am compile` 绿。

## 3. `/tools` 运维命令面（`pig-agent-cli`，镜像 `/mcp`）

- [x] 3.1 `repl/command/ToolsCommand`（`@Command name="/tools"`，子命令 `list|info|enable|disable|groups|refresh`，缺省 `list`），注入 `ReplContext`。
- [x] 3.2 `list`/`info` 经内核 faç和 `AgentKernel.listTools()` 取「名+风险+可用性+延迟+度量」；管理经 `AgentKernel.toolAdmin()`。逻辑在 `io.pigagent.cli.tools.ToolsConsole`（脱敏 `CredentialSanitizer`）。
- [x] 3.3 `enable|disable`：`enable` 揭示被延迟工具（`DeferredToolGate.reveal(revealTargets,…)`）、`disable` 经 `DeferredToolGate.applyTo` 移入 `deferred__<tool>` 组 + rebuild；可用性隐藏工具 `enable` 返回"缺前置、`/tools refresh`"提示（不凭空开）。MCP 工具 `disable` 拒绝（指向 `/mcp disable`，护 `mcpClientName`）。
- [x] 3.4 `groups`：列延迟组 + MCP 组（`mcpManager.managedToolGroups`）+ 运行时组，含 active 态 + 成员。
- [x] 3.5 挂进 `ReplCommands` 命令树（`/tools`）+ `/help` 条目 + `usage()`。
- [x] 3.6 单测：`ToolsConsoleTest`（5：list 风险/可用性/延迟/度量、disable→隐藏+rebuild、enable→揭示、可用性隐藏 enable→提示、groups 列延迟组）绿。
- [x] 3.7 `mvn -pl pig-agent-cli -am compile` 绿。

## 4. 运行时可用性热重评入口（复用 `toolsChangedCallback` 重建）

- [x] 4.1 `io.pigagent.cli.tools.ToolAvailabilityRefresher.refresh()`：重 `ToolAvailabilityGate.evaluate(gatedTools)` → 与当前 toolkit diff → 现的重注册+`ToolContractGuard.install` 重包、消失的 `removeTool` → 变化则跑共享 `rebuildAgents`（= `toolsChangedCallback` 同一 Runnable）。
- [x] 4.2 `/tools refresh` 触发；集合无变化 → 不 rebuild（`RefreshOutcome.changed()` 门）。
- [x] 4.3 触发式：无自动轮询/文件监听（仅显式 `refresh()`）。
- [x] 4.4 单测：`ToolAvailabilityRefresherTest`（3：前置满足→重现+rebuild、前置消失→隐藏、无变化→不 rebuild + 原因不含凭据）绿。

## 5. 内核 façade 暴露工具清单（`pig-agent-core` `AgentKernel`）

- [x] 5.1 `io.pigagent.core.agent.kernel.ToolInventoryEntry`（名/风险(String)/available+reason/deferralStatus/calls/avgLatency/errorRate）——String 风险避免 core→tools 依赖。
- [x] 5.2 `AgentKernel.setToolInventoryProvider(...)` + `listTools()`（provider 由 `AgentBootstrap` 注入 = `ToolsConsole::list`，编排 toolkit+分类器+可用性报告+延迟登记表+度量登记表）；未接线 → 空表。另加 `ToolAdmin` seam（groups/enable/disable/refresh）+ `setToolAdmin`/`toolAdmin()`。
- [x] 5.3 `ToolsCommand` list/info 经 `ctx.agentKernel().listTools()`、管理经 `ctx.agentKernel().toolAdmin()`（frontend 只依赖 faç和，不碰内部各表）。
- [x] 5.4 单测：`AgentKernelToolInventoryTest`（3：provider 未接线空表、返回条目、provider 返 null 降级空）绿；条目由 `ToolsConsole` 构造、经脱敏、无凭据。

## 6. 默认安全回归 + 验收 + 文档（离线，硬约束=全绿）

- [x] 6.1 默认无感回归：`/tools` 为新命令；度量 additive（`AgentBootstrapMetricsWiringTest`：metrics=null → 无 `ToolMetricsMiddleware`；非 null → 恰 1 个）；重评触发式。`tools.metrics.enabled=false` → 不接入 middleware。
- [ ] 6.2 现有工具层单测保持绿：availability / deferred / permission / contract / sandbox + core middleware 全绿。
- [x] 6.3 凭据脱敏自审：度量键仅工具名（不存值）；`ToolsConsole`/`ToolInventoryEntry`/refresh 输出全经 `CredentialSanitizer`，可用性 reason 只名缺失前置（对齐 `ToolAvailabilityReport` 约定）；测试断言 `doesNotContain("sk-")`。
- [ ] 6.4 `mvn -pl pig-agent-core -am test` 与 `mvn -pl pig-agent-cli -am test` 绿。
- [ ] 6.5 `CLAUDE.md` 增补「工具可观测与运行时管理（`/tools`）」段落（归档阶段随 `/ls:archive` 同步）。
