## MODIFIED Requirements

### Requirement: 声明式 Agent 定义

系统 SHALL 用不可变的 `AgentSpec` 描述一个 agent，至少包含 `id`、`name`、`sysPrompt`、`toolNames`（工具名白名单）、`permissionMode`、`modelId`（可为空）与 `maxIters`；并支持**自主运行字段**：`mandate`（自主运行时作为 user message 的岗位说明，可空）、`schedule`（`TaskSchedule`：ONCE/CRON/DELAYED，可空）、`commandAllowlist`（EXEC 首-token 白名单，可空）、`timeoutSeconds`（尽力而为墙钟超时，0=不设）、`lastRunAt`（上次自主运行时间，可空）。`schedule` 为空表示交互型、非空表示自主型。`AgentSpec` MUST 通过 `withXxx` 拷贝方式修改，不得原地可变。

#### Scenario: 用 withXxx 生成新实例而不改原对象
- **WHEN** 对一个 `AgentSpec` 调用 `withModelId(newId)`
- **THEN** 返回一个新的 `AgentSpec`，其 `modelId` 为 `newId`，而原对象的字段保持不变

#### Scenario: 缺省字段有合理默认
- **WHEN** 创建一个仅指定 `id` 与 `name` 的 `AgentSpec`
- **THEN** `toolNames` 默认为空表示全量工具、`permissionMode` 取全局默认、`modelId` 为空表示用默认模型、`schedule`/`mandate` 为空表示交互型

#### Scenario: schedule 区分交互型与自主型
- **WHEN** 一个 `AgentSpec` 的 `schedule` 非空
- **THEN** 它被视为自主型 agent，纳入自主运行调度；`schedule` 为空则为交互型，不被调度

#### Scenario: 自主字段完整持久化往返
- **WHEN** 保存一个含 `mandate`、`schedule`、`commandAllowlist`、`timeoutSeconds` 的 `AgentSpec` 后重新加载
- **THEN** 这些自主字段与保存前等值（不丢字段）
