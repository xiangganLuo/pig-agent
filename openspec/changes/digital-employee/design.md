## Context

`multi-agent-kernel`（已归档）让内核能管理多个 agent（`AgentSpec` / `AgentRegistry` / `AgentInstanceFactory`）。本阶段给 agent 加**自主运行**：带 `schedule` 的 agent 到点自主醒来、在受限权限下跑一份自然语言 `mandate`、产出晨报。守个人电脑红线：进程内、文件持久化、无 DB/服务端。

关键前置结论（来自已归档的 `model-retry`）：**当前 `ReActAgent` 不可中断**——客户端超时只取消下游订阅、底层调用仍在跑。这直接约束本阶段的"硬超时"能做到什么程度。

## Goals / Non-Goals

**Goals:**
- `AgentSpec` 扩展自主运行字段（`mandate`/`schedule`/`commandAllowlist`/`timeoutSeconds`/`lastRunAt`）。
- `AgentRunner`：到点起**隔离的一次性受限 agent** 跑 mandate → 产晨报；防重入；成功/失败/超时都产报告；回写 `lastRunAt`。
- 无人值守安全：危险动作不擅自执行，攒进晨报「等你决定」。
- `TaskScheduler` 通用化 + 真 cron 库（支持「每晚定点」）。
- `/agent run|report` 手动触发 / 看晨报。

**Non-Goals:**
- 晨报推送到通道（首版只落文件）。
- agent 间委派/编排（可选阶段4）。
- 彻底的硬中断（受 `ReActAgent` 不可中断限制，见 D4）。

## Decisions

- **D1：`AgentRunner` 用隔离的一次性 agent，不用 active 实例。** 自主运行**绝不能**复用交互 active 实例（会污染用户会话）。用 `AgentInstanceFactory`/`PigAgent.builder` 范式（同 `CompressionService.summarize`）按该 agent 的 spec 新建一次性受限 agent（独立记忆），跑完即弃。
- **D2：调度通用化 + 换 cron 库。** `TaskScheduler` 现硬绑 `Task` 生命周期（`updateStatus`/`getTasksByStatus`），且 `parseCronInterval` 只认 `*/N`。解耦出通用 `schedule(id, TaskSchedule, Runnable)`；**引入 cron-utils** 支持「每晚 2 点」等定点。`Task` 调度沿用同一底座。
- **D3：无人值守安全 = fail-closed + commandAllowlist + defer。** 自主运行无 confirmer → `ask` 天然 fail-closed（复用现有权限 sentinel）。危险动作被 `PermissionDeniedTool` 拦 → `DeniedActionRecorder` 收集 → 进晨报「等你决定」。`commandAllowlist`（复用 `permissions.allowlist.commands` 的首-token 语义）放行少数安全命令（如 `mvn test`），让守夜人能真干活。
- **D4：硬超时受限，用 `maxIters` 软上限 + 尽力而为超时（task 0 spike 先验证）。** `model-retry` 已证客户端超时对不可中断 `ReActAgent` 不安全。task 0 spike 验证：能否给一次自主运行加**真**中断？若不能（大概率），自主运行用 `maxIters` 软上限 + 后台线程 `Future.get(timeout)` 尽力而为超时（超时则标记该轮为超时、产"超时"报告，但不保证底层真停）。**绝不**用会重入 agent 的重订阅式超时。
- **D5：防重入 + 总产报告。** 内存 `Set<runningId>`：同一 agent 上轮未完则跳过本轮并记日志。无论成功/失败/超时都产一份报告；跑完回写 `spec.lastRunAt` 并持久化。
- **D6：晨报三段式，只落文件。** `workspace/reports/{date}/{id}.md`：`## ✅ 我做了` / `## 🔍 我发现` / `## ⏳ 等你决定`。首版只落文件，通道推送留后。
- **D7：交互型与自主型统一于 `AgentSpec`。** `schedule` 为空 = 交互型（现状）；非空 = 自主型。同一 spec、同一持久化，`AgentRunner` 只处理有 schedule 的。

## Risks / Trade-offs

- [硬中断做不到 → 卡死的自主运行占资源] → D4：maxIters 软上限必设；后台线程隔离 + 尽力而为超时；spike 先探边界。回归：一个"永不结束"的假模型自主运行在 maxIters/超时后被判失败并产报告。
- [commandAllowlist 首-token 较粗（放行 `mvn` = 任意 mvn 子命令）] → 个人工具可接受；文档标注；更细粒度留后续。
- [调度通用化触碰现有 `TaskScheduler`（Task 在用）] → 抽通用方法 + 保留 `Task` 路径；`TaskTest`/现有调度回归守住。
- [自主运行污染交互会话] → D1 隔离一次性 agent + 独立记忆，从构造上杜绝。

## Migration Plan

- 纯扩展：`AgentSpec` 加字段（默认值向后兼容，老 agent 无 schedule = 交互型不变）；新增 `reports/` 目录、`AgentRunner`、cron 依赖。无数据破坏。
- 回滚：改动集中在 core（AgentRunner）+ task（调度通用化）+ cli（命令/装配）；回退分支即可。

## Open Questions

- 命令用 `/agent run|report` 还是独立 `/employee`？→ 倾向扩展 `/agent`（少一个命令面），实现时定。
- 晨报保留策略（清理旧报告）？→ 首版不清理，留后续。
