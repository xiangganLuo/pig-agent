# digital-employee Specification

## Purpose
TBD - created by archiving change digital-employee. Update Purpose after archive.
## Requirements
### Requirement: 自主/定时运行

带 `schedule` 的 agent，系统 SHALL 在计划时间点自动运行其 `mandate`，用一个**与交互 active 实例隔离的一次性受限 agent**（独立记忆），不得污染或占用用户的交互会话。

#### Scenario: 到点自主运行
- **WHEN** 一个带 CRON `schedule` 的 agent 到达计划时间
- **THEN** 系统起一个隔离的一次性 agent 跑其 `mandate`，交互会话不受影响

#### Scenario: 交互 agent 不被自主运行触发
- **WHEN** 一个 `schedule` 为空的 agent
- **THEN** 系统不对其做自主运行

### Requirement: 无人值守安全

自主运行**无人确认**，系统 SHALL 采用 fail-closed 权限：危险动作（EXEC/删改等，`commandAllowlist` 放行者除外）MUST NOT 擅自执行，而是被拦截并记入晨报「等你决定」。

#### Scenario: 危险动作被拦并进等你决定
- **WHEN** 自主运行中 agent 试图执行未在白名单的危险命令
- **THEN** 该动作不执行，被记入晨报的「等你决定」区

#### Scenario: 白名单命令放行
- **WHEN** agent 的 `commandAllowlist` 含 `mvn`，自主运行中执行 `mvn test`
- **THEN** 该命令放行执行，其结果计入晨报「我做了」

### Requirement: 结构化晨报

每次自主运行结束系统 SHALL 产出一份晨报 `workspace/reports/{date}/{id}.md`，含三段：「我做了」「我发现」「等你决定」。无论运行成功、失败还是超时，MUST 都产出一份报告（失败/超时报告说明原因）。

#### Scenario: 成功运行产报告
- **WHEN** 自主运行正常完成
- **THEN** 生成含「我做了/我发现/等你决定」的晨报文件

#### Scenario: 失败或超时也产报告
- **WHEN** 自主运行因错误或超时结束
- **THEN** 仍生成一份晨报，说明失败/超时原因，不静默丢弃

### Requirement: 运行上限与尽力而为超时

系统 SHALL 对自主运行施加 `maxIters` 迭代软上限；`timeoutSeconds` 为尽力而为的墙钟超时（因 `ReActAgent` 不可中断，超时会标记该轮为超时并产报告，但不保证立即停止底层调用）。系统 MUST NOT 用会重入单飞 agent 的重订阅式超时。

#### Scenario: 超过迭代上限即停
- **WHEN** 自主运行达到 `maxIters` 上限仍未结束
- **THEN** 系统停止该轮并产出报告

#### Scenario: 超时标记不重入
- **WHEN** 自主运行超过 `timeoutSeconds`
- **THEN** 该轮被标记为超时并产报告，且系统不通过重订阅重入该 agent

### Requirement: 防重入与状态回写

系统 SHALL 防止同一 agent 的自主运行重入：上一轮未结束时，到点的新一轮 MUST 跳过并记录。每轮结束后系统 SHALL 回写该 agent 的 `lastRunAt` 并持久化。

#### Scenario: 未完成则跳过本轮
- **WHEN** 某 agent 上一轮自主运行仍在进行，其 schedule 又到点
- **THEN** 本轮被跳过并记一行日志，不并发重入

#### Scenario: 回写 lastRunAt
- **WHEN** 一轮自主运行结束
- **THEN** 该 agent 的 `lastRunAt` 被更新并持久化

### Requirement: 手动触发与查看

系统 SHALL 提供手动触发一次自主运行、以及查看晨报的命令（如 `/agent run <id>` 与 `/agent report`）。

#### Scenario: 手动触发一次运行
- **WHEN** 用户执行 `/agent run <id>`
- **THEN** 系统立即对该 agent 跑一次 mandate 并产报告

#### Scenario: 查看晨报
- **WHEN** 用户执行 `/agent report`
- **THEN** 系统列出/展示最近的晨报

### Requirement: 定点调度

系统 SHALL 支持定点 CRON 调度（如「每晚 2:00」），调度底座通用化以同时服务任务与数字员工。

#### Scenario: 每晚定点运行
- **WHEN** agent 的 schedule 配置为「每晚 2:00」
- **THEN** 系统在每天 2:00 触发其自主运行

### Requirement: 从 REPL 创建自主 agent（`/agent new` 自主标志）

系统 SHALL 允许用户直接从 REPL 用 `/agent new` 创建一个自主「数字员工」agent，无需手改 `workspace/agents/<id>.md`。`/agent new <id> <name> [modelId]` MUST 接受可选标志 `--mandate "<text>"`、`--schedule "<cron>"`、`--allow "<cmd1,cmd2>"`，据此经既有 `AgentSpec.withMandate`/`withSchedule`/`withCommandAllowlist` 构建 spec，并经 `/agent new` 已用的 kernel 持久化（不额外触碰调度）。`--schedule` 的表达式 MUST 用既有 cron 解析器校验；非法表达式 MUST 以一行友好信息拒绝且 MUST NOT 创建 agent。`--schedule` MUST 需要同时提供 `--mandate`（否则调度一个无事可做的空跑）。**无任何自主标志**的 `/agent new <id> <name> [modelId]` MUST 与既有行为逐字节一致（创建一个交互式、非自主 agent）。

#### Scenario: 带 mandate/schedule/allow 创建数字员工
- **WHEN** 用户执行 `/agent new emp "Night Watch" --mandate "check CI nightly" --schedule "0 2 * * *" --allow "mvn test,git status"`
- **THEN** 经 kernel 持久化一个 `AgentSpec`，其 `mandate` = "check CI nightly"、`schedule` = "0 2 * * *"、`commandAllowlist` = ["mvn test","git status"]，且 `isAutonomous()` 为真

#### Scenario: 非法 cron 被拒绝
- **WHEN** 用户执行 `/agent new bad Bad --mandate "do" --schedule "not-a-cron"`
- **THEN** 输出一行友好错误（指明期望 5 段 cron），且不创建任何 agent

#### Scenario: schedule 缺 mandate 被拒绝
- **WHEN** 用户执行 `/agent new x X --schedule "0 2 * * *"`（无 `--mandate`）
- **THEN** 输出一行提示需要 `--mandate`，且不创建任何 agent

#### Scenario: 无标志形态不变
- **WHEN** 用户执行 `/agent new a Assistant`（无任何自主标志）
- **THEN** 创建一个交互式 `AgentSpec`，`isAutonomous()` 为假、`mandate` 为空、`commandAllowlist` 为空

