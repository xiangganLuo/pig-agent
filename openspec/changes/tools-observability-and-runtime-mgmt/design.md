## Context

内核路线图 Wave-2 的 **Tool-OS** 目标是把工具从"一堆散装 `@Tool`"升级成一个可编排、可观测、可运行时管理的子系统。T2（延迟工具 + 智能默认，`deferred-tools`）已合并——工具能按规模移出/揭示初始 schema。**T3 补的是"可观测 + 运行时管理"**：把运维盲区（内置工具不可见/不可控、可用性 bootstrap-once、调用无度量）补齐，给运维一张工具「进程表」。

现状拼图：

- **风险分级**：`ToolRiskClassifier`（`pig-agent-tools` `io.pigagent.tool.permission`）是"工具名 → `ToolRisk`（READ_ONLY/WRITE/EXEC/NETWORK/MCP_ADMIN）"的**中央只读目录**（字符串键、跨模块）。`/tools list` 读它显示分级。
- **可用性**：`ToolAvailabilityGate.evaluate(tools) → ToolAvailabilityReport`（纯求值，不碰 toolkit）与 `applyTo(toolkit, tools)`（求值 + `removeTool` 隐藏）在 `AgentBootstrap` **只跑一次**。`ToolAvailabilityReport.Hidden(toolName, reason)` 的 `reason` **只名缺失前提、绝无凭据值**。
- **延迟**：`DeferredToolRegistry`（仍延迟集 + 已揭示集 + 分组名）+ `DeferredToolGate`（inactive group park / reveal 激活）+ AgentScope 原生 tool group。`/tools groups`/`enable|disable` 消费它。
- **调用日志**：`ToolCallLoggingMiddleware`（`MiddlewareBase.onActing`）**纯观察**、仅 `isDebugEnabled()` 时打工具名——**无度量聚合**。
- **MCP 运维**：`McpManager` 有 `/mcp` 全套 + `toolsChangedCallback`（运行时工具集变化后由 CLI 重建交互/渠道 agent 的原生权限上下文——因为 `PermissionContextState` 在 build 时按 toolkit 工具名快照）。
- **façade**：`AgentKernel` 是 frontend 唯一入口（frontend 只依赖 façade，不碰内部 registry/factory）。目前**不暴露工具清单**。
- **凭据硬化**：`CredentialSanitizer`（`pig-agent-tools` `io.pigagent.tool.contract`）——"never echo a key/token"，度量/`/tools` 输出经它。

T3 的四根支柱都是**叠加**在这套现状之上，不推翻任何既有守卫。

## Goals / Non-Goals

**Goals**
- 给运维一个 `/tools list|info|enable|disable|groups` 命令面（镜像 `/mcp`），一屏看清每个工具的名/风险/可用性/延迟状态，并能运行时开关。
- per-tool 度量（调用数/延迟/错误率）聚合，`/tools` 可查；**凭据/入参绝不入度量**；度量是**纯观察 additive**（不改既有日志/执行行为）。
- 运行时可用性**热重评**入口：设了前置（如 `BRAVE_API_KEY`）后**免重启**现出工具；**复用** `toolsChangedCallback` 的重建机制让重评后的工具拿到正确权限上下文。
- `AgentKernel` 暴露只读工具清单（façade 方法），`/tools` 与未来 frontend 同源消费。
- **默认安全**：新命令 + 纯观察度量 + 触发式重评；不触发时逐字节无感。

**Non-Goals**
- 不改 `ToolRiskClassifier` 中央表（**只读**它显示分级；如需重分级走 `tool-overrides`，非本 spec）。
- 不改 `ToolAvailabilityGate` 的 bootstrap 首评语义、不改 `ToolAvailabilityReport` 契约、不改 `deferred-tools` 的延迟/揭示机制、不改 `McpManager.toolsChangedCallback` 契约（仅**复用**）。
- 不做持久化的度量落盘/时序库/导出（度量为**进程内、内存态**，重启清零——够运维即时排障；持久化/Prometheus 导出按 YAGNI 留后续）。
- 不做**自动**可用性轮询 / 热 schema 交换 / 文件监听触发（触发式即可，避免 bootstrap-once 之外引入常驻扫描）。
- 不做 Web/TUI 面板可视化（本 spec 只到 façade + REPL 命令；Web adapter 复用同一 façade 方法，另 spec）。
- 不引入工具级细粒度权限编辑 UI（`/permission` 已管权限；`/tools enable|disable` 只管**可见性**：延迟/揭示或可用性，不管 may-run）。

## Spike（前置 Task 组 1 —— 轻量确认，非阻塞门）

> 无重承重（plan-tools 评估无 spike）。仅需在 `/ls:code` 起手做两条**轻量可行性确认**，走主路径、无回退方案：

- **SP1 — 度量 middleware 接入不破坏 `ToolCallLoggingMiddleware` 既有行为**。确认：一个纯观察 `MiddlewareBase.onActing`（`next.apply(input).doOnEach/doFinally` 记时/记错）能与既有 logging middleware **并存**（各自独立 `onActing`，list 位置决定顺序），不改 acting input、不改 tool result、不吞事件流；既有 `ToolCallLoggingMiddleware` 的 DEBUG 日志契约逐字保持。验收动作：加一个度量 middleware 后跑既有 core middleware 单测保持绿 + 新增"度量观察不改流"的用例。
- **SP2 — 可用性热重评复用 `toolsChangedCallback` 重建 agent 可行**。确认：`ToolAvailabilityGate.evaluate/applyTo` 可在运行时再次调用（对当前工具实例），且触发 `McpManager` 同款"重建交互/渠道 agent"路径后，重评后**新现/复现**的工具进入新 agent 的 toolkit 并获得逐工具权限规则（与 `/mcp add` 后的重建同构）。验收动作：离线用例——前置从"缺"变"备"后重评 → 报告中该工具从 hidden 变 available；断言重建入口被调用（mock/seam）。

## Decisions

- **D1 — `/tools` 命令面（镜像 `/mcp`）**。子命令：
  - `list`（缺省）：逐行 `名称  [风险]  可用性  [延迟?]`——风险读 `ToolRiskClassifier.classify(name, overrides)`；可用性读**当前**重评结果（available / hidden+原因）；延迟状态读 `DeferredToolRegistry`（仍延迟/已揭示）。
  - `info <tool>`：单工具详情——描述、风险、可用性（含缺失前置**原因**、绝无凭据值）、所属延迟/能力组、度量摘要（调用数/平均延迟/错误率）。
  - `enable <tool>` / `disable <tool>`：运行时**可见性**开关。`enable` = 若工具在延迟组则**揭示**（激活组，复用 `DeferredToolGate` 揭示 seam）；`disable` = 把工具**延迟**（移入 inactive 组）。对**因可用性缺前置而隐藏**的工具，`enable` 给出提示"缺 <前置>，请补齐后 `/tools refresh`"（可见性由热重评恢复，不能凭空 enable）。
  - `groups`：列出能力包（tool group）——组名 + active/inactive + 成员工具名，与 T2 延迟分组（`deferred__<tool>`、`mcp:<server>`）衔接。
  - 触发热重评：`/tools`（或显式 `/tools refresh`）调用热重评入口。**全部输出经 `CredentialSanitizer`**，可用性/度量输出**只名缺失前提、绝不含凭据值**。
  - *备选*：把工具管理塞进 `/status` 或 `/mcp`——否决，职责不清；`/tools` 独立命令与 `/mcp` 对称，运维心智一致。
- **D2 — 度量聚合位置：独立纯观察 middleware + 线程安全登记表，不改 `ToolCallLoggingMiddleware`**。新增 `ToolMetricsMiddleware`（`MiddlewareBase.onActing`）：`next.apply(input)` 记开始时刻，`doFinally`/`doOnError` 记结束——按 `ToolUseBlock.getName()` 累加**调用数**、累加**延迟**、错误信号累加**错误数**（错误率 = 错误数/调用数）。度量落进一个线程安全 `ToolMetricsRegistry`（`ConcurrentHashMap<toolName, counters>`）。**键仅工具名**——不记入参、不记 tool result 内容、不记任何值 → 凭据天然不入。*为何不扩 `ToolCallLoggingMiddleware`*：单一职责（日志 vs 度量）、避免动它的既有 DEBUG 契约、两个 middleware 各自 `onActing` 独立不耦合（SP1 已确认可并存）。*备选*：在 `GuardedAgentTool`/`ToolContractGuard` 里记度量——否决，那层已够重且不覆盖非 guarded 路径；middleware 层是所有工具调用的统一必经点。
- **D3 — 度量为进程内内存态，重启清零**。`ToolMetricsRegistry` 只在内存；无落盘/时序/导出（Non-Goal）。够运维即时排障；持久化/Prometheus 按 YAGNI 留后续。可提供 `/tools`（或 metrics reset）清零（可选）。
- **D4 — 热重评入口复用 `toolsChangedCallback` 重建机制**。`AgentBootstrap` 暴露一个 `refreshAvailability()`（或等价 seam）：重新 `ToolAvailabilityGate.evaluate(builtins)` → 与上次报告 diff → **若可见集合变化**（有工具从 hidden→available 或反之），走 **`McpManager.toolsChangedCallback` 同款**"重建交互/渠道 agent"路径（新 toolkit 应用新可用性 + 新的原生权限上下文快照）。**判据**：设了 `BRAVE_API_KEY` 后 `/tools refresh` → `webSearch` 现身且可调用（有逐工具权限规则）。**触发式**：仅显式调用才跑，无自动轮询（Non-Goal）。*为何复用*：`toolsChangedCallback` 正是为"运行时工具集变化后重建权限上下文"而生（M-2），可用性重评是同类事件；自造第二套重建 = 重复 + 漂移。*备选*：只 `removeTool`/重加不重建——否决，新工具无逐工具权限规则（`PermissionContextState` build 时快照），会静默失去 ASK/DENY 规则（安全回归）。
- **D5 — `AgentKernel` 暴露只读工具清单 façade**。新增 `listTools()`（或 `toolInventory()`）返回不可变 `ToolInfo` 列表：`名称 / 风险(ToolRisk) / 可用性(available+reason) / 延迟状态(deferred/revealed/none) / 度量摘要(calls/avgLatency/errorRate)`。façade **编排**内部（toolkit 工具名 + `ToolRiskClassifier` + 最近一次可用性报告 + `DeferredToolRegistry` + `ToolMetricsRegistry`），frontend **只依赖 façade**。**清单条目不含任何凭据值**（`reason` 只名前置）。与 `agent-kernel-facade` 的"frontend 只依赖 façade"原则一致——这是它的延伸，不推翻。*备选*：`/tools` 直接读内部各表——否决，破坏 façade 边界（Web adapter 就得重写一套）。
- **D6 — 凭据不入度量/输出（贯穿）**。度量键=工具名（D2 天然不含值）；`/tools`/façade 清单的一切文本经 `CredentialSanitizer`；可用性 `reason` 沿用 `ToolAvailabilityReport` 约定（只名缺失前提）。对齐 model/mcp "never echo a key/token" 全局约定。
- **D7 — 默认安全 = 三条叠加、不触发即无感**。`/tools` 新命令（不打不影响）；`ToolMetricsMiddleware` 纯观察（`onActing` 只 `next.apply` + 旁路记数，不改 input/result/事件流——SP1 保证）；热重评触发式（不调不跑）。度量若给 config 开关，`tools.metrics.enabled` 默认 true 但纯观察（无行为变化）；置 false 则不挂 metrics middleware（零开销）。判据：不打 `/tools`、不触发 refresh 时，工具 schema / 调用行为 / 日志与引入本能力前逐字节一致。

## 交叉依赖（写进 design，供后续引用）

| 依赖对象 | 关系 |
|---|---|
| **T2 `deferred-tools`（已合并）** | `/tools groups` 与其 tool group 概念衔接（组名/active/成员）；`/tools enable|disable` 复用其揭示（激活组）/延迟（移入 inactive 组）机制与 `DeferredToolRegistry`。**不改**其 REQUIREMENT。 |
| **`tool-availability`** | 可用性热重评是其 **bootstrap-once 限制的运行时补丁**——复用 `ToolAvailabilityGate.evaluate/applyTo` 与 `ToolAvailabilityReport`，在其首评之上补"运行时再评 + 重建"。**不改**其"评估一次"的既有语义（那是实现说明），运行时重评作为**新能力**的 REQUIREMENT 承载。 |
| **`ToolRiskClassifier` 中央表** | `/tools list`/清单 façade 读它显示风险分级，**只读不改**（重分级走 `tool-overrides`，非本 spec）。 |
| **`credential-hardening`（`CredentialSanitizer`）** | 度量/`/tools`/façade 输出经它脱敏；reasons 只名缺失前提不含凭据值（对齐 `ToolAvailabilityReport` 约定）。 |
| **`mcp-management`（`toolsChangedCallback`）** | 热重评**复用**其"运行时工具集变化 → 重建交互/渠道 agent 权限上下文"的重建机制（M-2 同构事件），**不改**其契约。 |
| **`agent-kernel-facade`** | 新增只读清单方法是其"frontend 只依赖 façade"原则的延伸；Web adapter 复用同一方法。**不推翻**其既有条款。 |

## Risks / Trade-offs

- **R1 — 度量 middleware 引入性能/行为回归**。风险：包在每次工具调用上的记时/记错影响延迟或改变事件流。→ D2 纯观察（只 `next.apply` + 旁路计数，`ConcurrentHashMap` O(1) 累加）；SP1 确认与既有 logging middleware 并存不改流；tasks 加"度量不改 acting input/result、既有 DEBUG 日志逐字保持"护栏 + 可 config 关闭（默认 true 但零行为变化）。
- **R2 — 热重评重建 agent 的副作用（会话态/prefix-cache）**。风险：重建交互 agent 可能扰动会话或使 prefix-cache 失效。→ D4 复用 `toolsChangedCallback` **同款**路径（`/mcp add` 已在用、已验证），可见集合**无变化时不重建**（diff 门）；重建只在运行时工具集**真变了**时发生（与 `/mcp` 改动同性质，一次 cache-miss，可接受）；触发式（运维显式发起，非后台自动）。
- **R3 — 凭据经度量/清单泄漏**。风险：工具名以外的值意外进入度量/输出。→ D6 度量键=工具名（不记值）；一切输出经 `CredentialSanitizer`；可用性 `reason` 沿用"只名缺失前提"约定；tasks 加"度量/清单不含凭据/入参值"用例 + `security-reviewer` 过一遍。
- **R4 — `/tools enable` 对"可用性隐藏"工具的语义歧义**。风险：用户以为 `enable` 能凭空开出缺前置的工具。→ D1 明确：`enable` 只对**延迟**工具生效（揭示）；对**可用性缺前置**隐藏的工具给出"缺 <前置>、补齐后 `/tools refresh`"提示，可见性由热重评恢复而非 enable。spec 场景覆盖两条路径。
- **R5 — 度量重启清零，非持久**。→ D3 明确进程内内存态是**刻意取舍**（即时排障够用，持久化 YAGNI）；文档写清"重启清零"，不误导为审计日志。
- **R6 — façade 清单与 `Toolkit.copy()` 派生实例（peer/子agent）的一致性**。风险：清单反映的是哪个 toolkit？→ 本 spec 清单以**当前活跃 agent 运行的 toolkit** 为准（faç和 `activeId` 一致）；peer/子agent 的独立可见性属 T2 已记的 copy 语义，本 spec 不扩展跨实例聚合（Non-Goal），仅约束"清单反映活跃 agent 的真实工具集"。

## 落实追踪表（评审/发现项 → 落点 + 状态）

| 发现项 | 落点 | 状态 |
|---|---|---|
| 内置工具无运维面（对比 `/mcp`） | D1；proposal What Changes | 落 tasks 组 3（`/tools` 命令） |
| per-tool 度量缺失（日志无聚合） | D2/D3；R1 | 落 tasks 组 2（度量 middleware + 登记表） |
| 度量接入不破坏 `ToolCallLoggingMiddleware`（承重轻确认） | Spike SP1 | 落 tasks 组 1（轻量确认） |
| 可用性 bootstrap-once，设 key 要重启 | D4；R2 | 落 tasks 组 4（热重评入口） |
| 热重评复用 `toolsChangedCallback` 重建（承重轻确认） | Spike SP2；D4；交叉依赖 | 落 tasks 组 1 + 组 4 |
| 重建复用避免新工具丢权限规则 | D4；R2 | 落 tasks 组 4（重建后有逐工具规则用例） |
| kernel 暴露工具清单供 `/tools`+未来 frontend | D5 | 落 tasks 组 5（façade `listTools`） |
| 凭据绝不入度量/输出 | D6；R3；交叉依赖（`CredentialSanitizer`） | 落 tasks 组 2/3/5（脱敏 + 用例） |
| `ToolRiskClassifier` 只读显示分级 | D1/D5；Non-Goals；交叉依赖 | 落 delta spec（清单读分级，不改表） |
| `/tools groups`/`enable|disable` 衔接 T2 分组 | D1；交叉依赖 | 落 tasks 组 3（groups + enable/disable） |
| `enable` 对可用性隐藏工具的语义 | D1；R4 | 落 delta spec（enable 两条路径场景） |
| 度量进程内内存态、重启清零 | D3；R5 | 记为已接受取舍（design + 文档说明） |
| 默认安全（新命令/度量 additive/重评触发式） | D7；R1 | 落 tasks 组 6（默认无感回归） |
| 现有工具层单测全绿（硬约束） | Goals | 落 tasks 组 6（验收全量测试） |
