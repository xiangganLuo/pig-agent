# agent-management

## Purpose

让内核从「单 agent」变为「进程内管理多个 agent」：每个 agent 由声明式、不可变的 `AgentSpec`（各自模型、工具子集、权限档、人格）定义并持久化；用户可列出/新建/切换 agent，交互「当前 agent」为注册表中的 active 实例。单 agent 为其退化特例，行为不变。设计见 `docs/design/agent-management-design.md`。
## Requirements
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

### Requirement: Agent 定义持久化

系统 SHALL 把每个 `AgentSpec` 持久化为 `workspace/agents/{id}.md`（YAML front-matter 承载字段 + Markdown 正文承载 sysPrompt），且写入后再读出 MUST 得到字段等值的 `AgentSpec`（完整往返，不丢字段）。仓库 SHALL 对损坏文件容错：跳过或备份坏文件，不影响其余 agent 的列举。

#### Scenario: 往返读写字段等值
- **WHEN** 保存一个含 `toolNames`、`permissionMode`、`modelId` 的 `AgentSpec` 后重新加载
- **THEN** 加载出的 `AgentSpec` 各字段与保存前等值

#### Scenario: 坏文件不影响列举
- **WHEN** `workspace/agents/` 下有一个内容损坏的 `.md` 与若干正常文件
- **THEN** 列举返回全部正常的 agent，坏文件被跳过或备份，不抛异常中断

### Requirement: 多 Agent 注册与当前 Agent

系统 SHALL 用 `AgentRegistry` 持有多个 `AgentInstance`（每个 = agentId + 源 AgentSpec + 内部 PigAgent），并维护唯一的「当前（active）agent」。对外访问 SHALL 经 `AgentKernel` 门面（列/建/改/删/切、chat、事件流）；内核内部仍以 `AgentRegistry` 为准，`AgentHolder` 继续作为 active 实例的视图，既有只通过 `AgentHolder.get()` 读当前 agent 的组件 MUST 继续读到 active 实例，行为不变。

#### Scenario: 切换当前 agent
- **WHEN** 注册表中存在 agent A（active）与 agent B，经门面 `useAgent("B")`
- **THEN** active 实例变为 B，随后 `AgentHolder.get()` 返回 B 的 PigAgent

#### Scenario: AgentHolder 作为 active 视图零变化
- **WHEN** 通过门面/注册表切换 active 实例
- **THEN** REPL、SessionManager、CompressionService 等读 `agentHolder.get()` 的组件立即看到新的 active agent，无需改动其代码

#### Scenario: 对外访问经门面
- **WHEN** 一个入口需要操作 agent 或消费事件
- **THEN** 它经 `AgentKernel` 门面完成，而非直连 `AgentRegistry` 内部类

### Requirement: 每 Agent 独立选择模型

系统 SHALL 依据 `AgentSpec.modelId` 经 `ModelStore` + `ProtocolRegistry` 为该 agent 构建其自己的 `Model`。当 `modelId` 为空或指向已不存在的 `StoredModel` 时，系统 SHALL 容错回落到默认模型，不得抛出未处理异常。

#### Scenario: 两个 agent 各用各的模型
- **WHEN** agent A 的 `modelId` 指向模型 M1、agent B 的指向 M2，分别与二者对话
- **THEN** A 的回复由 M1 产生、B 的由 M2 产生

#### Scenario: 悬空 modelId 回落默认
- **WHEN** 某 agent 的 `modelId` 指向一个已被删除的 `StoredModel`
- **THEN** 该 agent 用默认模型构建并可正常对话，不崩溃

### Requirement: 每 Agent 隔离的工具、权限与记忆

构建 `AgentInstance` 时系统 SHALL 为该 agent 单独装配：按 `toolNames` 新建的受限 `Toolkit`（仅注册白名单工具；未知工具名忽略并记录）、读取 `spec.permissionMode` 的独立权限 hook、以及独立的对话记忆。不同 agent 之间 MUST NOT 共享工具白名单之外的能力或彼此的对话记忆。

#### Scenario: 工具白名单生效
- **WHEN** agent 的 `toolNames` 只含 `readFile`，向其请求执行 shell 命令
- **THEN** 该 agent 的 toolkit 中不存在 shell 工具，无法执行

#### Scenario: per-agent 权限档生效
- **WHEN** agent A 的 `permissionMode` 为 `plan`、agent B 的为 `auto`
- **THEN** A 的可变工具调用被否决（只读产计划）、B 的写/网络类调用自动放行

### Requirement: 单 Agent 向后兼容

系统 SHALL 保证在没有任何已保存 agent 定义时自动以现有默认配置（默认 sysPrompt + 默认模型 + 全量工具 + 全局权限）建立一个 active 实例，使升级后的单 agent 使用体验与本变更前一致。

#### Scenario: 空 agents 目录启动
- **WHEN** `workspace/agents/` 为空时启动
- **THEN** 系统自动建立一个默认 active agent，用户可直接对话，行为与变更前相同

#### Scenario: 现有交互链路不回归
- **WHEN** 在默认 agent 上执行发送消息、切换模型、切换会话、上下文压缩
- **THEN** 各功能表现与本变更前一致

