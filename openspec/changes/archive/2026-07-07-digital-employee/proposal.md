## Why

`multi-agent-kernel`（阶段1）让内核能管理多个 agent，但每个 agent 仍是**被动交互型**——必须有人在 REPL 前驱动。本变更（阶段2）落地设计文档 `docs/design/agent-management-design.md` 的第一个多 agent 应用「数字员工」：一个**带 `schedule` 的 agent**，到点自主醒来、在受限权限下跑一份自然语言 mandate、产出「我做了 / 我发现 / 等你决定」晨报。仍守个人电脑红线：进程内、文件持久化、无 DB/服务端。

> **依赖**：本变更依赖 `multi-agent-kernel` 已归档（复用 `AgentSpec` / `AgentInstanceFactory` / `AgentRegistry`）。在其归档前，本 proposal 仅锁定范围与依赖，`tasks.md` 待内核归档后再细化。

## What Changes

- **扩展 `AgentSpec`**：新增自主运行字段 `mandate`（岗位说明，自主运行时当 user message）、`schedule`（复用 `TaskSchedule`：ONCE/CRON/DELAYED）、`commandAllowlist`（EXEC 首 token 白名单，让守夜人能跑 `mvn test`）、`timeoutSeconds`、`lastRunAt`。
- **新增 `AgentRunner`（自主运行）**：`schedule` 到点起一个**隔离的一次性受限 agent**（同 `CompressionService.summarize` 范式），跑 mandate → 产报告；防重入、成功/失败/超时都产报告、跑完回写 `lastRunAt`。
- **无人值守安全**：无 confirmer → `ask` fail-closed（复用现有权限 sentinel）；危险动作被 `PermissionDeniedTool` 拦 → `DeniedActionRecorder` 收集 → 进晨报「等你决定」区；`commandAllowlist` 放行少数安全命令。
- **调度通用化**（对应内核 spec 待优化 #6）：把 `TaskScheduler` 从 `Task` 生命周期解耦出通用 `schedule(id, TaskSchedule, Runnable)`；**换真 cron 库（cron-utils）支持「每晚定点」**。
- **超时/中断 spike**（对应内核 spec 待优化 #7）：`tasks.md` 第 1 组为 Spike 卡点——验证能否给一次 `reactAgent` 运行加真超时/中断。
- **晨报持久化**：`workspace/reports/{date}/{id}.md`（三段式），首版只落文件（通道投递留后）。
- **命令**：`/agent` 扩展 `run|report`（手动触发 / 看晨报），或 `/employee` 子集（实现时定）。

## Capabilities

### New Capabilities
- `digital-employee`: 带 schedule 的 agent 在受限权限下自主/定时运行一份 mandate 并产出结构化晨报（做了/发现/等你决定），危险动作不擅自执行而 defer 到人工确认。

### Modified Capabilities
- `agent-management`: `AgentSpec` 增加自主运行字段（mandate/schedule/commandAllowlist/timeoutSeconds/lastRunAt），交互型（无 schedule）与自主型（有 schedule）统一于同一 spec。

## Impact

- **代码**：`pig-agent-core`（`AgentSpec` 扩字段 + `AgentRunner` + `DeniedActionRecorder`）；`pig-agent-task`（`TaskScheduler` 通用化 + cron 库）；`pig-agent-tools`（`commandAllowlist` 接入权限解析）；`pig-agent-cli`（调度注册 + 报告命令）；`pig-agent-workspace`（`reports/` 目录）。
- **依赖**：新增 cron 解析库（如 cron-utils）。
- **不改动**：阶段1 的交互路径与单 agent 兼容性。
- **前置**：`multi-agent-kernel` 归档。
