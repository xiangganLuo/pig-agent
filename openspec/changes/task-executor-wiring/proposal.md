## Why

`/tasks` 的计划任务（CRON/DELAYED）触发时**只翻状态**（TODO→IN_PROGRESS→COMPLETED），并不做任何真实工作——`TaskScheduler` 已预留 `setTaskExecutor(Consumer<Task>)` 真活儿座（seam，nullable；未接=诚实的"仅提醒"翻状态，即既有行为），但**生产从未接线**。真正的无人值守执行机器 `AgentRunner`（隔离一次性、非交互、fail-closed 权限、尽力而为超时、结果捕获）已存在，只差把两者接上。

此外，Fix-State 为 `SessionManager` 加了可选的 `snapshotResetHook`（`Consumer<String>`，7 参构造；6 参委托 null）用于 `/session clear` 时重置压缩快照（`CompressionService.resetSnapshot(sessionId)`），但**生产仍走 6 参构造（hook=null → no-op）**，快照未被重置——清空会话后 `maybeCompress`/`status` 仍可能按清空前的消息数推理。

## What Changes

- **任务执行接线（默认关）**：新增配置门 `tasks.execute`（默认 `false`）。开启后 `AgentBootstrap` 为 `TaskScheduler` 接上执行器：一个触发的任务把其意图（title + description）作为 mandate，经**复用 `AgentRunner` 的 fail-closed 一次性隔离 agent**跑一遍并捕获结果。未开启（默认）→ 执行器不接 → 仅提醒翻状态（与今天字节等价，向后兼容）。
- **`AgentRunner` 新增 ad-hoc mandate 入口**：`runMandate(String id, String mandate)`（及带超时的重载），复用与 `run(AgentSpec)` 相同的隔离构建 / 重入保护 / 尽力而为超时 / 事后 DENIED 扫描机器，但**不写晨报、不改 spec**（任务无持久 spec）；返回运行 `AgentReport`，永不抛（失败/超时以 report 形式回来）。
- **任务结果落盘无损**：`Task` record 增两不可变字段 `result`（本次运行摘要）+ `lastRunAt`（Instant），经 `withXxx` 拷贝；`FileSystemTaskRepository` 往返持久化（Result 单行转义、LastRun ISO-8601），旧文件（无这两字段）照常解析（result/lastRunAt=null）；`/tasks` 列表在有结果时展示一行摘要。
- **快照重置钩子接线**：`AgentBootstrap` 改用 `SessionManager` 的 **7 参构造**，传入 `compressionService::resetSnapshot`，使 `/session clear` 真正重置该会话的压缩快照。

## Capabilities

### New Capabilities
- `task-execution`: 计划任务触发时经 fail-closed 一次性隔离 agent 跑其意图并捕获结果（`result`/`lastRunAt` 落盘、`/tasks` 展示）；由 `tasks.execute` 配置门控（默认关 → 仅提醒，向后兼容）。
- `session-snapshot-reset`: `/session clear` 经已存在的 `snapshotResetHook` 真正重置该会话的压缩快照（生产接上 7 参构造）。

### Modified Capabilities
<!-- 无：本变更是接线 + 新增字段/入口，不改写既有能力的 spec 文本（TaskScheduler seam、SessionManager hook、AgentRunner 机器均已存在）。 -->

## Impact

- **代码**：`pig-agent-task`（`Task` 增 `result`/`lastRunAt` + `withResult`/`withLastRunAt`，8 参兼容构造保留；`TaskManager.recordRun`；`FileSystemTaskRepository` 往返）、`pig-agent-core`（`AgentRunner.runMandate`）、`pig-agent-config`（`tasks.execute` 门）、`pig-agent-cli`（`AgentBootstrap` 接执行器 + 7 参 `SessionManager`；`/tasks` 展示 result）。
- **配置**：新增 `tasks.execute`（默认 `false`），可选、默认安全；不开则零行为变更。
- **数据**：任务 `.md` 增 `LastRun`/`Result` 两元数据行（放在 `Updated` 之前，保证 `extractDescription` 键在 `Updated` 上的既有解析不变）；旧文件无这两行 → 解析为 null，向后兼容。
- **安全/成本**：执行器跑真 agent → **消耗模型 token**；复用 `AgentRunner` 的**非交互 fail-closed 权限上下文**（DONT_ASK 基线 + 每工具 ASK→DENY + spec 的 commandAllowlist），无 HITL confirmer，危险动作被原生 `PermissionEngine` 拒绝。默认关，是显式 opt-in。
- **不回归**：`TaskScheduler`/`Task`/`FileSystemTaskRepository`/`SessionManager` 既有单测不变（seam 语义、8 参 Task 构造、往返、7 参 hook 测试均保留）。
