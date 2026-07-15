## Why

pig-agent 当前**完全没有工具调用循环检测**。ReAct 智能体在真实任务里会陷入死循环：反复 `readFile` 同一个文件、反复 `executeCommand` 同一条失败命令、或在被权限/错误挡住后不断重试同一动作——每一轮都要跑一次模型推理 + 一次工具调用，**持续烧 token、占满上下文、永不收敛**。现有的三道安全网都不覆盖这种「同一动作的高频重复」：权限 veto 管「能不能跑」、可用性门控管「可不可见」、返回契约管「异常不外泄」，没有一层管「你已经把同一件事做了 N 遍」。

deerflow 的 `LoopDetectionMiddleware` 用一个滑动窗口 + 签名计数解决了同类问题。本次把这一模式移植进 pig-agent，作为**独立、正交、默认安全**的第四道防线，落在既有 hook 链里、排在权限 veto 之后，不与任何现有门控争抢。

## What Changes

本次是**面向设计模式的能力新增**（Strategy + Adapter，非零散功能拼装）：

- **纯策略核心 `LoopDetector`（`pig-agent-core`，可离线单测）**：维护一个滑动窗口（默认 20）记录近期工具调用签名，统计当前签名在窗口内的重复次数，返回一个 `LoopDecision`（`OK` / `WARN@≥3` / `STOP@≥5`）。窗口满则逐出最旧签名。构造参数（窗口大小、warn/stop 阈值）全部可配、默认安全（越界值容错钳制）。
- **签名策略 `ToolCallSignatureStrategy`（Strategy 接口）+ 默认实现**：把一次工具调用折叠成签名 = `toolName` + 规范化参数的哈希；**特判 `readFile`**：按 200 行为一个桶分段（同一文件、略有不同的行区间仍归为同一签名 → 仍算重复）；`writeFile`/编辑类工具**哈希全量参数**（不分桶）；其余工具走通用「toolName + 全量参数哈希」。策略可替换。
- **适配 hook `LoopDetectionHook`（`Hook` on `PreActingEvent`）**：把纯检测器接入 AgentScope 事件模型，排在权限 veto **之后**（`priority()` > 0）以免干扰。`WARN` → 在下一个 `PreReasoningEvent` 注入一条 user 侧的临时提醒消息（复用 `EphemeralMemoryContextHook` 的 ephemeral 注入路径，不落历史），告诉模型它在重复、应换方法或直接给最终答复；`STOP` → 复用既有 veto-to-sentinel 机制，把待执行的 `ToolUseBlock` 改写为只读哨兵 `LoopDetectedTool`（对标 `PermissionDeniedTool`），真实工具不执行，模型收到「你陷入循环，请停止并作答」后被迫收敛。
- **哨兵工具 `LoopDetectedTool`（`pig-agent-tools`）**：一个 `@Tool` 只读占位，经既有 SPI 自动注册 + 手动兜底清单登记；工具名常量在 `pig-agent-core` 的 hook 上定义，二者不漂移。
- **配置 `loop-detection` 块（`PigAgentConfig`）**：`enabled`(默认 true) / `window-size`(20) / `warn-threshold`(3) / `stop-threshold`(5)，全部可选、缺省即安全默认、向后兼容（旧配置照常解析）。
- **装配（`AgentBootstrap`）**：把 `LoopDetectionHook` 接入交互式 hook 链（并同样接入渠道 hook 链）；每个 agent 实例持有自己的 `LoopDetector`（与既有 per-agent hook 列表模式一致）；检测器按「回合」重置——hook 在 `PreReasoningEvent` 依据输入里的 user 消息计数变化识别新回合并 `reset()`，避免把计数泄漏到不相关的下一回合。

无 **BREAKING**：默认启用但阈值宽松（连续 5 次完全相同的调用才硬停），正常任务不受影响；`enabled: false` 完全旁路，等同旧行为。检测器只读取工具调用、不改变任何工具语义；`STOP` 复用既有 veto 机制、`WARN` 复用既有 ephemeral 注入机制，均不污染持久化历史。

## Capabilities

### New Capabilities
- `loop-detection`: 工具调用循环检测——纯策略检测器（滑动窗口 + 签名计数 + `readFile` 分桶）+ 适配 hook（`WARN` 注入提醒 / `STOP` veto-to-sentinel），排在权限 veto 之后，默认启用且默认安全，按回合重置，防止模型陷入重复工具调用死循环烧 token。

<!-- 不改：tool-permissions（veto 机制被复用，行为不变）/ prefix-cache-context（ephemeral 注入路径被复用，行为不变）/ tool-availability / tool-json-contract 的行为契约。 -->

## Impact

- **代码（`pig-agent-core`）**：新增 `loop/` 包——`LoopDecision`、`ToolCallSignatureStrategy`、`DefaultToolCallSignatureStrategy`、`LoopDetector`、`LoopDetectionHook`、`LoopMessages`（提醒/哨兵文案）。
- **代码（`pig-agent-tools`）**：新增 `loop/LoopDetectedTool`（哨兵）+ `spi/providers/LoopDetectedToolProvider`；`META-INF/services/io.pigagent.tool.spi.ToolProvider` 增一行。
- **代码（`pig-agent-config`）**：`PigAgentConfig` 新增 `LoopDetectionConfig` 嵌套类 + `loop-detection` 字段 + getter。
- **装配（`pig-agent-cli`）**：`AgentBootstrap` 构造交互式/渠道 `LoopDetector` + `LoopDetectionHook` 并接入对应 hook 链；哨兵工具进手动兜底清单。
- **协作/不改**：权限 veto（priority 0，先于本 hook）、`EphemeralMemoryContextHook`（priority 50，其 PreReasoning 注入与本 hook 的注入叠加不冲突）、可用性门控、返回契约 + 分发守卫，全部不变。
- **测试**：`LoopDetectorTest`（无重复→OK；3 次相同→WARN；5 次→STOP；`readFile` 行区间分桶命中近似重复；不同工具/参数不触发；窗口逐出后计数回落）；`DefaultToolCallSignatureStrategyTest`（`readFile` 分桶、`writeFile` 全量哈希、通用哈希、参数顺序无关）；`LoopDetectionHookTest`（Mockito mock 事件：WARN 注入 / STOP 改写为哨兵 / 跳过哨兵与权限拒绝名 / 回合切换 reset）。
- **文档**：`CLAUDE.md` 增一段「工具调用循环检测（loop-detection）」。
