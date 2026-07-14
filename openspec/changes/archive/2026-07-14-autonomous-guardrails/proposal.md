## Why

生产就绪度审查（`docs/review/production-readiness-2026-07-14.md`）P1 阻断项：自主运行缺三道安全网。① `maxIters` 声明/解析/持久化/`/status` 显示齐全，却从未接进 `ReActAgent`（`PigAgent.builder` 不调 `.maxIters`）——叠加 `timeoutSeconds` 默认 0，agent 陷入 tool-call 乒乓时**无强制上限、失控成本风险**（digital-employee spec 的「SHALL 施加 maxIters」形同未实现）。② `FileSystemTaskRepository.parseMarkdown` 遇坏/缺字段抛 `RuntimeException` 且只 catch `IOException`，沿 `findAll → AgentBootstrap.scheduleAll` 传到**启动路径 → 单个坏 `.md` 让进程启动崩溃**，违反"仓库容错、绝不崩"承诺。③ 回读时一律重建为 `TaskSchedule.once()`、且 `toMarkdown` 只写 `schedule().type()`（丢 cron 表达式/delay）——**CRON/DELAYED 任务重启后降级为 ONCE、`scheduleAll` 永不重排、循环任务静默消失**。

此外，本轮切分支时暴露一处配置脆弱：`PigAgentConfig` 未忽略未知字段，一个不认识的配置项会让**整份配置回退默认**（丢掉用户的 permissions/session/compression 等设置）。作为无人值守稳健性的一部分一并收口。

## What Changes

- **maxIters 接入全部 agent**（用户拍板："所有 agent 都需要接入运行上限"）：`PigAgent.Builder` 增 `maxIters` 字段，`build()` 在 `> 0` 时调 `ReActAgent.builder().maxIters(n)`（`io.agentscope.core.ReActAgent$Builder.maxIters(int)` 已 javap 确认）。经共享构建路径覆盖每类 agent：`AgentFactory`（交互 + 渠道，取 `config.agent.max-iters`）、`AgentInstanceFactory`（每 agent 取 `AgentSpec.maxIters`）、`AgentRunner`（自主取 `AgentSpec.maxIters`）。
- **任务仓库容错**：`FileSystemTaskRepository.parseMarkdown` 单文件解析失败（坏/缺字段/坏枚举）时**跳过 + warn、返回可跳过信号**，`findAll` 用容错聚合不因单文件中断（对齐 `JsonModelStore`/`JsonMcpStore` 的 skip/backup 范式）；启动 `scheduleAll` 不再被单个坏文件打断。
- **任务持久化无损**：`toMarkdown` 写全 schedule（type + cronExpression + delaySeconds）与 description；`parseMarkdown` 正确回读 Schedule/Created/Updated/description，重建等价 `Task`。使 CRON/DELAYED 任务重启后被 `scheduleAll` 正确重排。
- **配置健壮性**：`PigAgentConfig`（及嵌套配置类）加 `@JsonIgnoreProperties(ignoreUnknown = true)`，未知字段被忽略而非让整份配置加载失败回退默认——schema 漂移不再静默丢用户设置。

## Capabilities

### New Capabilities
- `agent-run-limit`: 所有 agent（交互 / 渠道 / 自主）经统一构建路径施加 `maxIters` 迭代上限，防止推理-工具循环无界运行；值来源为各自 `AgentSpec.maxIters` 或 `config.agent.max-iters`。
- `task-persistence`: 文件任务仓库的容错读取（坏文件跳过不崩）与无损往返（schedule 全字段 + 时间戳 + 描述），使 CRON/DELAYED 任务跨重启存活并被正确重排。
- `config-resilience`: 配置加载对未知字段容错（忽略而非整份回退默认），schema 前后兼容、不静默丢失既有设置。

### Modified Capabilities
<!-- 无需改写：digital-employee 的「运行上限与尽力而为超时」requirement 早已声明 maxIters SHALL；本变更是把它真正实现并推广到全部 agent（新 agent-run-limit 能力承载通用语义），不改其既有 spec 文本。 -->

## Impact

- **代码**：`pig-agent-core`（`PigAgent.Builder` 增 maxIters；`AgentFactory` 增 maxIters 字段；`AgentInstanceFactory.create` 传 `spec.maxIters`；`AgentRunner`/其 AgentBuilder 传 `spec.maxIters`）、`pig-agent-task`（`FileSystemTaskRepository` 容错 + 无损）、`pig-agent-config`（`PigAgentConfig` 忽略未知字段；`AgentFactory` 构造处 wiring 在 `AgentBootstrap`/`ModelManager`）。
- **配置**：复用既有 `agent.max-iters`（默认 10）与 `AgentSpec.maxIters`（默认 `DEFAULT_MAX_ITERS=10`），无新增配置键。**行为变更**：交互/渠道 agent 现在也受 maxIters 约束（此前不受限）——默认 10 对复杂交互任务可能偏紧，见 design Risks。
- **数据**：任务 `.md` 的 Schedule 行格式增强（写全 cron/delay）；旧格式（仅 type）读取时容错降级为 ONCE、不崩。
- **不回归**：维持既有 300+ 单测；容错遵循既有"坏文件跳过不崩"约定；maxIters 仅在配置值 > 0 时施加（AgentSpec 保证 ≥ DEFAULT）。
- **测试**：离线单测覆盖每条（maxIters 传入各构建路径、坏任务文件跳过不抛、schedule/时间戳/描述往返无损、CRON 重启重排、未知配置字段被忽略且既有设置保留）。
