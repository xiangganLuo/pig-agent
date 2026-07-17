## Why

两处「自动化能力已接线但触不到 / 名不副实」的落差，损害「24h 常驻超级助手」的可达性与诚实性：

1. **数字员工无法从 REPL 创建。** 整条自主/cron 后端已接线（非空 `schedule` 的 `AgentSpec` → `AgentBootstrap` 调度 `agent:<id>` → `AgentRunner.run` → 晨报；`/agent run`、`/agent report` 可用），但 `/agent new`（`AgentCommand.create`）只设 id/name/model，**从不设** `mandate`/`schedule`/`commandAllowlist`。因此创建一个数字员工的唯一途径是手改 `workspace/agents/<id>.md`——对用户不可达。`AgentSpec` 早已有 `withMandate`/`withSchedule`/`withCommandAllowlist`。
2. **定时任务执行是空操作。** `TaskScheduler.executeTask` 只把状态 TODO→IN_PROGRESS→COMPLETED 翻一遍——任务「跑了」却什么都没做。这与真正调用 agent 的数字员工路径形成误导性对比。

## What Changes

- **`/agent new` 自主标志。** 扩展 `AgentCommand.create` 接受可选 `--mandate "<text>"`、`--schedule "<cron>"`、`--allow "<cmd1,cmd2>"`，经既有 `withMandate`/`withSchedule`/`withCommandAllowlist` 构建 `AgentSpec`，并经 `/agent new` 已用的 kernel/registry **正确持久化**（不触碰 `AgentBootstrap`/`PigAgentCli` 调度——既有 cron→AgentRunner 路径在下次启动时拾取）。用既有 cron 解析器（`TaskScheduler.isValidCron`，UNIX 5 段 cron）校验，坏表达式以友好行拒绝；`--schedule` 需配 `--mandate`（否则调度一个无事可做的空跑）。**无标志的 `/agent new <id> <name>` 行为完全不变。**
- **任务执行诚实 + seam。** 不建整套跨模块的 agent-任务派发（越界）。而是：(a) 给 `TaskScheduler` 加一个**可选**执行器 seam——一个 `Consumer<Task>` 字段（`setTaskExecutor`），设置后 `executeTask` 委托它做真实工作；未设置（默认，接线是 lead 在 `AgentBootstrap` 的活）时保留当前状态翻转行为。(b) 让状态翻转路径**诚实**：无执行器的任务只是一个「提醒/标记」而非自主运行，用清晰日志反映（`/tasks` 由他人后续如实展示）。seam 未接线时行为中立（既有任务测试保持绿）。

## Capabilities

### Modified Capabilities
- `digital-employee`: 新增「从 REPL 创建自主 agent」——`/agent new` 支持 `--mandate`/`--schedule`/`--allow` 标志构建数字员工 `AgentSpec` 并持久化，cron 经既有解析器校验；无标志形态不变。
- `task-persistence`: 新增「可选任务执行器 seam + 诚实的提醒-标记语义」——`TaskScheduler.setTaskExecutor(Consumer<Task>)`；未接线时定时任务是提醒-标记（不做工作、日志如实），行为中立。

## Impact

- **代码**：`pig-agent-cli`（`repl/command/AgentCommand.java`——新增创建标志 + 校验 + 用法文本）；`pig-agent-task`（`TaskScheduler.java`——执行器 seam + `isValidCron` + 诚实 `executeTask`）。
- **不改**：`AgentBootstrap`/`PigAgentCli` 调度；`AgentRunner`；`Task`/`TaskStatus` 记录；权限/沙箱/渠道语义。
- **测试**：离线单测——`/agent new` 带标志构建自主 `AgentSpec`（`isAutonomous()==true`、mandate/allowlist set）、坏 cron 拒绝、无标志形态不变、schedule 缺 mandate 拒绝；`TaskScheduler` 执行器设置后被调用 + 未设置时默认状态翻转保留 + 执行器抛异常回退 TODO + `isValidCron`。
- **文档**：归档时（`/ls:archive`）由 lead 决定是否同步 `CLAUDE.md`。
