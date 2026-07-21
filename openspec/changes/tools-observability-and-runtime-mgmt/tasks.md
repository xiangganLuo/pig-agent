## 1. Spike —— 轻量可行性确认（前置，非阻塞门；走主路径无回退）

- [ ] 1.1 SP1：确认一个纯观察的度量 `MiddlewareBase.onActing`（`next.apply(input)` + `doFinally`/`doOnError` 旁路记时/记错）能与既有 `ToolCallLoggingMiddleware` **并存**——各自独立 `onActing`、list 位置定序，不改 acting input / tool result / 事件流；既有 DEBUG 日志契约逐字保持。产出结论 + 一个"度量观察不改流"最小用例。
- [ ] 1.2 SP2：确认 `ToolAvailabilityGate.evaluate/applyTo` 可在运行时二次调用，且触发 `McpManager.toolsChangedCallback` 同款"重建交互/渠道 agent"路径后，重评新现工具进入新 toolkit 并获逐工具权限规则（与 `/mcp add` 后重建同构）。产出结论 + seam/mock 断言重建入口被调用的最小用例。
- [ ] 1.3 记录结论：两条均走主路径，无需回退方案；把承重点与验收动作落入下述组 2/4。

## 2. per-tool 度量聚合（`pig-agent-core`，纯观察 additive）

- [ ] 2.1 新增线程安全 `ToolMetricsRegistry`（`ConcurrentHashMap<toolName, counters>`：调用数 / 累计延迟 / 错误数；派生平均延迟、错误率）；键**仅工具名**，不存任何入参/返回/凭据值。
- [ ] 2.2 新增 `middleware/ToolMetricsMiddleware`（`MiddlewareBase.onActing`）：`next.apply(input)` 记开始，`doFinally` 记延迟 + 调用数、`doOnError`/错误信号记错误数；按 `ToolUseBlock.getName()` 归集到登记表。纯观察——不改 input/result/事件流。
- [ ] 2.3 不动 `ToolCallLoggingMiddleware`（单一职责）：度量与日志两个独立 middleware 并存（SP1 已确认）。
- [ ] 2.4（可选）`pig-agent-config` `tools.metrics.enabled`（默认 true、纯观察；false 则不接入 metrics middleware，零开销），optional/default-safe。
- [ ] 2.5 单测：成功调用累加调用数+延迟、失败调用累加错误数、度量键仅工具名不含凭据/入参、既有 `ToolCallLoggingMiddleware` 行为与既有 core middleware 单测保持绿。
- [ ] 2.6 `mvn -pl pig-agent-core -am compile` 绿。

## 3. `/tools` 运维命令面（`pig-agent-cli`，镜像 `/mcp`）

- [ ] 3.1 新增 `repl/command/ToolsCommand`（picocli `@Command name="/tools"`，子命令 `list|info|enable|disable|groups`，缺省 `list`），构造注入 `ReplContext`。
- [ ] 3.2 `list`：逐行「名称 + 风险（`ToolRiskClassifier.classify`，只读）+ 可用性 + 延迟状态」；`info <tool>`：描述/风险/可用性(含缺前置原因)/所属组/度量摘要。全部经 `CredentialSanitizer`。
- [ ] 3.3 `enable|disable <tool>`：`enable` 揭示被延迟工具（复用 `DeferredToolGate` 揭示 seam）、`disable` 移入延迟；对可用性缺前置隐藏的工具，`enable` 给"缺 <前置>、补齐后 `/tools refresh`"提示（不凭空开、不含凭据）。
- [ ] 3.4 `groups`：列 tool group（组名 / active|inactive / 成员），衔接 T2 延迟分组。
- [ ] 3.5 挂进 `ReplCommands` 命令树 + 斜杠补全（`/tools` 进补全菜单）；`usage()` 帮助。
- [ ] 3.6 单测：各子命令渲染 + 脱敏（凭据/入参不入输出）、`enable` 两条路径（延迟→揭示 / 可用性隐藏→提示）、`list`/`info` 读风险与可用性正确。
- [ ] 3.7 `mvn -pl pig-agent-cli -am compile` 绿。

## 4. 运行时可用性热重评入口（复用 `toolsChangedCallback` 重建）

- [ ] 4.1 `AgentBootstrap` 暴露 `refreshAvailability()`（或等价 seam）：重新 `ToolAvailabilityGate.evaluate(builtins)` → 与上次报告 diff → 可见集合变化时走 `McpManager.toolsChangedCallback` 同款重建交互/渠道 agent 路径。
- [ ] 4.2 `/tools`（或显式 `/tools refresh`）触发 4.1；集合无变化则不重建（diff 门，避免无谓 cache-miss）。
- [ ] 4.3 触发式：MUST NOT 引入自动轮询/文件监听（Non-Goal）。
- [ ] 4.4 单测：前置从"缺"变"备"后重评 → 报告中该工具 hidden→available 且重建入口被调用（seam/mock）；前置仍缺 → 保持隐藏且原因不含凭据；重评后新现工具进入新 toolkit 有逐工具权限规则。

## 5. 内核 façade 暴露工具清单（`pig-agent-core` `AgentKernel`）

- [ ] 5.1 新增不可变 `ToolInfo`（名 / 风险 `ToolRisk` / 可用性 available+reason / 延迟状态 deferred|revealed|none / 度量摘要 calls+avgLatency+errorRate）。
- [ ] 5.2 `AgentKernel.listTools()`（或 `toolInventory()`）：编排 当前活跃 agent 的 toolkit 工具名 + `ToolRiskClassifier` + 最近一次可用性报告 + `DeferredToolRegistry` + `ToolMetricsRegistry` → `List<ToolInfo>`；条目不含凭据值（reason 只名前置）。
- [ ] 5.3 `/tools`（组 3）改为经该 façade 方法取数据（frontend 只依赖 façade，不碰内部各表）。
- [ ] 5.4 单测：façade 返回条目含 名/风险/可用性/延迟/度量；清单不含凭据/入参值；反映当前活跃 agent 的真实工具集。

## 6. 默认安全回归 + 验收 + 文档（离线，硬约束=全绿）

- [ ] 6.1 默认无感回归：不使用 `/tools`、不触发热重评时，工具 schema / 调用行为 / 既有日志与引入本能力前逐字节一致；`tools.metrics.enabled=false` 时不接入 metrics middleware。
- [ ] 6.2 现有工具层单测保持绿：availability / deferred / permission / contract / sandbox + core middleware 全绿。
- [ ] 6.3 `security-reviewer` 过一遍度量/`/tools`/façade 输出的凭据脱敏路径。
- [ ] 6.4 `mvn -pl pig-agent-core -am test` 与 `mvn -pl pig-agent-cli -am test` 绿（全量 `mvn verify` 按 coordinator 指示在 itest/合并阶段统一做）。
- [ ] 6.5 `CLAUDE.md` 增补「工具可观测与运行时管理（`/tools`）」段落 + 模块表补 `ToolsCommand` / `ToolMetricsMiddleware`+`ToolMetricsRegistry` / `AgentKernel.listTools`+`ToolInfo`；标注度量重启清零、重评触发式、复用 `toolsChangedCallback`。
