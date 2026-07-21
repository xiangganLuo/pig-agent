## Why

工具层已经堆了多重守卫（可用性门 `ToolAvailabilityGate`、权限 `PermissionEngine`、返回契约 `ToolContractGuard`、执行沙箱 `CommandGuard`、延迟工具 `deferred-tools`）+ MCP 动态 CRUD（`McpManager` 带 `toolsChangedCallback` 重建 agent），但运维对**内置工具**几乎不可见、不可控：MCP 有 `/mcp` 全套运维面，内置工具却没有对等的 `/tools`——运维看不到某个工具的风险分级、当前是否可用、是否被延迟隐藏，也无从运行时开关。更糟的是**可用性只在 bootstrap 评估一次**：把 `BRAVE_API_KEY` 设上后，`webSearch` 仍要**重启**才现身（`ToolAvailabilityGate` 在 `AgentBootstrap` 只跑一次，无运行时重评入口）。同时 `ToolCallLoggingMiddleware` 只在 DEBUG 打日志、**无任何度量聚合**——运维答不出"哪个工具被调最多/最慢/最常报错"。这是内核路线图 Wave-2 **Tool-OS 的 T3（工具可观测 + 运行时管理）**要补的缺口：给运维一张工具「进程表」。

## What Changes

- **新增 `/tools` 运维命令（工具进程表，镜像 `/mcp` 范式）**：`list|info|enable|disable|groups`。`list` 一屏列出每个工具的**名称 + 风险分级 + 可用性 + 是否延迟组**；`info <tool>` 展示单工具详情（描述、风险、可用性原因、所属延迟/能力组、度量摘要）；`enable|disable <tool>` 运行时开关一个工具（走延迟工具 group 揭示/延迟机制，或可用性）；`groups` 列出能力包（tool group，与 T2 延迟工具分组衔接）。全部输出经 `CredentialSanitizer` 脱敏，可用性原因**只名缺失前提、绝不含凭据值**（对齐 `ToolAvailabilityReport` 约定）。
- **新增 per-tool 度量聚合**：**扩展/补充** `ToolCallLoggingMiddleware`（或新增一个纯观察的 metrics middleware）按工具名聚合**调用数 / 延迟 / 错误率**，`/tools list|info` 可查。度量**键仅工具名**，凭据/入参值**绝不入度量**；聚合是**纯观察**——MUST NOT 改动 acting input / tool result / 既有 DEBUG 日志行为。
- **新增运行时可用性热重评入口**：提供一个显式入口重新评估 `ToolAvailability`（设了 key 后**免重启**现出 `webSearch`），**复用 MCP 已有 `toolsChangedCallback` 重建 agent 的机制**，使重评后新现/复现的工具拿到正确的原生权限上下文（`PermissionContextState` build 时按 toolkit 工具名快照，不重建则无逐工具规则）。
- **内核 façade 暴露工具清单**：`AgentKernel` 新增只读工具清单方法（每项含 名称/风险/可用性/延迟状态/度量摘要），供 `/tools` 与未来 frontend 消费——frontend **只依赖 façade**，不碰内部 `Toolkit`/`ToolAvailabilityGate`/`DeferredToolRegistry`/度量登记表。
- **默认安全**：`/tools` 是**新命令**（纯增量）；度量聚合 **additive**（不改任何既有行为，仅观察）；可用性热重评**触发式**（仅显式调用才跑，无自动轮询/热 schema 交换）。不触发任何入口时，行为与引入本能力前**逐字节一致**。

无 **BREAKING**：新命令 + 纯观察度量 + 触发式重评，全部叠加在既有守卫之上；`ToolRiskClassifier`（中央表）**只读不改**；`ToolCallLoggingMiddleware` 的既有日志契约、`ToolAvailabilityGate` 的 bootstrap 首评、`McpManager` 的默认路径全部保持。硬约束：现有工具层单测（availability / deferred / permission / contract / sandbox）保持绿。

## Capabilities

### New Capabilities
- `tools-observability`: 面向运维的工具可观测与运行时管理能力——`/tools` 命令面（list/info/enable/disable/groups）、per-tool 度量聚合（调用数/延迟/错误率，凭据不入）、运行时可用性热重评（复用 `toolsChangedCallback` 重建）、内核 façade 暴露工具清单；默认安全（新命令、度量 additive、重评触发式）。

### Modified Capabilities
<!-- 无 spec 级 REQUIREMENT 变更需要 delta：
     - tool-availability：本 spec 只在其 bootstrap 首评之上"补一条运行时重评入口"，复用其 ToolAvailabilityGate.evaluate/applyTo，不改也不推翻其既有 REQUIREMENT（"评估一次"是 CLAUDE.md 的实现说明，非 spec 条款）。运行时重评作为新能力的 REQUIREMENT 承载，交叉依赖记入 design.md。
     - deferred-tools：/tools groups / enable|disable 消费其 tool group 揭示/延迟机制，不改其 REQUIREMENT。
     - agent-kernel-facade：新增只读清单方法与其"frontend 只依赖 façade"原则一致，作为新能力 REQUIREMENT 承载，不推翻其既有条款。
     故本 spec 只引入一个新 capability。 -->

## Impact

- **代码（全部叠加，默认不改行为）**：
  - `pig-agent-cli` `repl/command/ToolsCommand`（新）：`/tools list|info|enable|disable|groups`，镜像 `McpCommand` 的运维风格；输出经 `CredentialSanitizer`。挂进 `ReplCommands` 命令树 + 斜杠补全。
  - `pig-agent-core` 度量：扩展 `middleware/ToolCallLoggingMiddleware`（或新增 `middleware/ToolMetricsMiddleware`）+ 一个线程安全的 per-tool 度量登记表（调用数/延迟/错误计数，键=工具名）。仅观察 `onActing` 的开始/完成/异常。
  - `pig-agent-core` `agent/kernel/AgentKernel`：新增只读 `listTools()`（或等价）返回工具清单条目（名/风险/可用性/延迟/度量摘要）——façade 编排内部 toolkit + `ToolRiskClassifier` + `ToolAvailabilityGate.evaluate` + 度量登记表 + 延迟登记表。
  - `pig-agent-core`/`pig-agent-cli` 热重评入口：`AgentBootstrap` 暴露一个"重评可用性 → 若集合变化则复用 `McpManager.toolsChangedCallback` 同款重建 agent"的可调用点，`/tools`（或 `/tools refresh`）触发。
  - `pig-agent-config`（可选）：`tools.metrics`（`enabled` 默认 true，纯观察）等 optional/default-safe 配置块。
- **不改**：`ToolRiskClassifier` 中央表（`/tools list` **只读**它显示分级）；`ToolAvailabilityGate` 的 bootstrap 首评语义 + `ToolAvailabilityReport` 的"原因只名缺失前提"约定；`deferred-tools` 的延迟/揭示机制与 MCP attach-时分组；`McpManager` 的 `toolsChangedCallback` 契约（仅复用，不改）；`ToolContractGuard`/`PermissionEngine`/`CommandGuard` 的既有语义。
- **依赖**：消费 T2 `deferred-tools`（已合并，`/tools groups`/`enable|disable` 衔接其 tool group）；消费 `tool-availability`（`ToolAvailabilityGate.evaluate`）、`credential-hardening`（`CredentialSanitizer`）、`agent-kernel-facade`（façade 范式）；复用 `mcp-management` 的 `toolsChangedCallback` 重建机制。
- **测试（离线，硬约束=全绿）**：度量聚合（成功计数+延迟、失败计错、键仅工具名不含凭据/入参、既有日志行为不变）；工具清单条目正确（名/风险/可用性/延迟状态；凭据不入）；热重评（前提满足后工具现身、前提缺失仍隐藏且原因不含凭据、重评复用重建使新工具拿到权限规则）；`/tools` 各子命令渲染 + 脱敏；默认安全（不触发时逐字节无感）。现有工具层单测保持绿。
- **文档**：`CLAUDE.md` 增补「工具可观测与运行时管理（`/tools`）」段落——`/tools` 命令面、度量 additive、可用性热重评复用 `toolsChangedCallback`、kernel 暴露清单、凭据不入度量/输出；模块表补 `ToolsCommand` / 度量 middleware+登记表 / `AgentKernel.listTools`。
