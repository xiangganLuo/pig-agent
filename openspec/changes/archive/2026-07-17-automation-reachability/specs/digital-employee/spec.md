## ADDED Requirements

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
