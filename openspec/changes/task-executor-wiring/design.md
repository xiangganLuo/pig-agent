## Context

需求源：方案 1（把计划任务真正执行 + 接上快照重置钩子）。已读源确认（worktree = shared checkout 同哈希）：

- `TaskScheduler.setTaskExecutor(Consumer<Task>)` 已存在（`pig-agent-task`）；`executeTask` 在 `taskExecutor==null` 时走"仅提醒"翻状态（TODO→IN_PROGRESS→COMPLETED），非 null 时 `IN_PROGRESS → exec.accept(task) → COMPLETED`，**exec 抛异常 → 回退 TODO**。生产从未 `setTaskExecutor`。
- `AgentRunner`（`pig-agent-core`，`io.pigagent.core.agent.runner`）：`run(AgentSpec)` 经 `AgentBuilder.build(spec, recorder)` 建**隔离一次性 agent**，重入保护（`running` set），`execute` 尽力而为超时（后台线程 + `Future.get(timeout)`/`cancel`），事后 `recordDenials` 扫描 DENIED tool-result；`run` 还写晨报 + `markRan`。`AgentBootstrap` 的 `autonomousBuilder` 已经把 fail-closed 非交互权限上下文（`interactive=false` → ASK 关闭、DONT_ASK 基线；`mergedPermissionConfig` 折入 commandAllowlist）注入该 agent。
- `AgentBootstrap.build`：`TaskScheduler` 建于 L286；`AgentRunner agentRunner` 建于 L677；`SessionManager` 建于 L787（**6 参**，无 snapshot hook）；`CompressionService` 建于 L800（在 SessionManager **之后**）。`taskManager` 全程在作用域内。
- `SessionManager` 已有 7 参构造（`snapshotResetHook`），`clearConversation` 在非 null 时 `snapshotResetHook.accept(currentSessionId)`；`SessionManagerTest.clearConversation_invokesSnapshotResetHook` 已绿（证明 hook 语义正确，只差生产接线）。
- `CompressionService.resetSnapshot(String)` 已存在（清 `lastCompressedAt`/`lastCompressedSize`）。
- `Task` record 8 字段（`id,title,description,status,schedule,createdAt,updatedAt,dueDate`），所有 `new Task(...)` 调用点均 8 参。`FileSystemTaskRepository.extractDescription` 键在 `**Updated**:` 行、取其后正文。

约束：不可变（record + `withXxx`）；文件 <800 行、函数 <50 行；SLF4J；仓库容错（坏文件跳过不崩）；离线单测为主。

## Goals / Non-Goals

**Goals:**
- 计划任务触发（执行器接上时）经 fail-closed 一次性隔离 agent 跑其意图并捕获结果；未接（默认）保持仅提醒。
- `AgentRunner` 提供 ad-hoc mandate 入口，复用既有 fail-closed 机器，不产生晨报/spec 副作用。
- `Task` 增 `result`/`lastRunAt` 往返无损；旧文件向后兼容。
- `/session clear` 真正重置压缩快照。
- 每条行为有离线单测（agent 运行以 fake model 桩定，确定性、离线）。

**Non-Goals:**
- 真·可中断超时（沿用 `AgentRunner` 尽力而为语义）。
- 任务失败自动重试策略（agent 运行失败以 FAILURE 结果记录、状态照常 COMPLETED；仅执行器**抛异常**才回退 TODO——沿用 seam 既有语义）。
- 任务 `.md` 格式版本/迁移器（新字段容错缺省即可）。
- 为渠道/交互 agent 触发任务执行（仅调度器 fire 路径）。

## Decisions

- **D1 复用 `AgentRunner` = 新增 `runMandate(String id, String mandate)` 入口（首选方案）。** 内部合成一个**瞬态非自主** `AgentSpec`（`AgentSpec.create(id,id).withMandate(mandate).withTimeoutSeconds(t)`），走与 `run` 相同的 `running` 重入保护 + `builder.build(spec, recorder)` + `execute(spec, agent, recorder)`，返回 `AgentReport`；**不**调 `reportWriter`/`specUpdater`（任务无持久 spec，晨报是数字员工概念）。带超时重载 `runMandate(id, mandate, timeoutSeconds)`，2 参默认 `DEFAULT_MANDATE_TIMEOUT_SECONDS`（尽力而为上限，防调度线程被 hang 住）。fail-closed 由 `AgentBootstrap` 传入的 `autonomousBuilder` 保证（`interactive=false`），`runMandate` 本身不放松权限。
  - 备选"合成 spec 直接调 `run`"被否：`run` 会写晨报到 `workspace/reports/` 并 `markRan` 一个不存在的 spec，副作用不合适。
- **D2 `Task` 增 `result`+`lastRunAt` 两不可变字段，保留 8 参兼容构造。** 规范构造为 10 参；新增 8 参委托构造（`result=null,lastRunAt=null`），使既有全部 `new Task(8 args)` 调用点/测试**不改**即编译。`withStatus`/`withSchedule` 拷贝时保留新字段；新增 `withResult(String)`/`withLastRunAt(Instant)`。
- **D3 结果落盘：Result/LastRun 作为元数据行，插在 `Created` 与 `Updated` 之间。** `extractDescription` 键在 `**Updated**:` 上取其后正文——把新字段放在 `Updated` **之前**，`extractDescription` **零改动**、正文往返不受影响（最小、最稳、完全向后兼容）。`Result` 可能多行 → 单行转义（`\`→`\\`、`CRLF/CR/LF`→字面 `\n`），读回反转义；写入按 `MAX_RESULT_CHARS` 截断（防超大文件）。`LastRun` 为 ISO-8601 Instant（缺省空）。旧文件无这两行 → `extractField` 返 null → result/lastRunAt=null。
- **D4 `TaskManager.recordRun(id, result)` 容错落结果。** 读现任务 → `withResult(result).withLastRunAt(now)` → save；任务已删则 no-op（不抛）。执行器闭包在 `AgentBootstrap`（持 `taskManager`）中调它。翻状态顺序保证结果存活：`IN_PROGRESS`（result 旧）→ exec 内 `recordRun`（写 result，status 仍 IN_PROGRESS）→ `COMPLETED`（`withStatus` 拷贝时保留 result）。
- **D5 执行器构建抽成可测静态助手 `AgentBootstrap.buildTaskExecutor(AgentRunner, TaskManager)`。** 返回 `Consumer<Task>`：`mandate = title (+ "\n\n" + description)` → `runner.runMandate("task:"+id, mandate)` → `taskManager.recordRun(id, summary(report))`。`taskOutcomeSummary(report)` = `"[OUTCOME] body|note"`（SUCCESS 用 body，其余用 note）。`build()` 中仅当 `config.getTasks().isExecute()` 时 `taskScheduler.setTaskExecutor(buildTaskExecutor(...))`。抽静态使其可用真 `AgentRunner`(fake builder) + 真 `TaskManager`/repo 离线单测。
- **D6 快照钩子接线，避免重排块（additive）。** `SessionManager` 建于 `CompressionService` 之前，故用 `AtomicReference<CompressionService> compressionRef`：`SessionManager` 传 7 参、hook = `sessionId -> { CompressionService cs = compressionRef.get(); if (cs != null) cs.resetSnapshot(sessionId); }`；`CompressionService` 建好后 `compressionRef.set(...)`。纯增量（加 1 个 ref + 改 ctor 参数），不移动既有代码块——降低与并行 `AgentBootstrap` 改动的冲突面。

## Risks / Trade-offs

- **[成本] 任务执行消耗 token** → 默认 `tasks.execute=false`（seam 未接=仅提醒）；显式 opt-in 才跑真 agent。已在 proposal/README 注明成本。
- **[安全] 无人值守跑 agent** → 复用 `AgentRunner` fail-closed 上下文（非交互、DONT_ASK、ASK→DENY、commandAllowlist），危险动作被原生 `PermissionEngine` 拒绝；执行器不持 confirmer。
- **[语义] agent 运行失败 vs 执行器异常** → agent FAILURE/TIMEOUT 记为结果、状态 COMPLETED（诚实记录本次跑的结果）；只有执行器**自身**抛（如 recordRun IO 失败）才走 seam 的回退 TODO。文档化。
- **[并发] `AgentBootstrap` 并行改动** → 本变更编辑局部且增量（新增执行器接线块 + 改 SessionManager 一处 ctor + 加一个 ref）。

## 落实追踪表

| 评审/发现项 | 落点 | 状态 |
|---|---|---|
| 计划任务不做真事 | D1（runMandate）+ D5（buildTaskExecutor 接线）+ `task-execution` spec | 已实现 |
| 结果不可见 | D2/D3/D4（Task 字段 + 往返 + recordRun）+ `/tasks` 展示 | 已实现 |
| 无成本/安全护栏 | D1 fail-closed 复用 + `tasks.execute` 默认关 | 已实现 |
| `/session clear` 未重置快照 | D6（7 参 SessionManager 接线）+ `session-snapshot-reset` spec | 已实现 |

## Migration Plan

纯增量：`tasks.execute` 默认关 → 未接执行器 = 今天行为；`Task` 8 参构造保留 → 既有调用点不改；任务 `.md` 新字段旧文件容错缺省；快照 hook 接线只补一处生产 wiring（测试已证语义）。回滚：`tasks.execute` 设回 false / 移除执行器接线即恢复。

## Open Questions

- 任务执行的默认超时 `DEFAULT_MANDATE_TIMEOUT_SECONDS`（本次取一个保守上限；真模型 IT 时可校准）。
- 是否需要在 `/tasks` 详情/单任务视图展示完整 result（本次仅列表一行摘要，足够；完整视图延后）。
