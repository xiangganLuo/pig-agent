# subagent-online-switch Specification

## Purpose
TBD - created by archiving change subagent-online-switch. Update Purpose after archive.
## Requirements
### Requirement: 通过 AgentKernel 门面暴露/追踪已 expose 的子agent
系统 SHALL 通过 `AgentKernel` 门面（而非内部 registry/gateway）向前端提供已 expose 子agent 的追踪与查询：`noteSubagentExposed(subagentId, agentId, label)` 记录、`listSubagents()` 按 expose 顺序返回、`subagentOutput(id)` 单条查询。由于原生网关不提供枚举 API，已 expose 集合 MUST 从父流上的 `SubagentExposedEvent` 追踪；前端 MUST 只依赖门面，不得直接访问网关。当活动 agent 切换或重建（模型切换）时，系统 MUST 清空已追踪的 expose 子agent（它们属于上一个 agent 的网关）。

#### Scenario: 记录并列出已 expose 的子agent
- **WHEN** 前端在流上看到 `SubagentExposedEvent` 并调用 `noteSubagentExposed`
- **THEN** `listSubagents()` 按顺序包含该子agent（id/type/label），`subagentOutput(id)` 可查到它

#### Scenario: 忽略无 id 的 spawn
- **WHEN** 一个未 expose 的 spawn（subagentId 为空）
- **THEN** 它不进入 `listSubagents()`

#### Scenario: 切换活动 agent 清空追踪
- **WHEN** 已追踪若干 expose 子agent 后 `useAgent(其它)`
- **THEN** `listSubagents()` 为空（陈旧 handle 不再可寻址）

### Requirement: 直连已 expose 的子agent（switch）
系统 SHALL 允许把一次对话直接路由到当前活动 agent 已 expose 的子agent，绕过父会话——门面 `chatWithSubagent(subagentId, msg): Flux<AgentEvent>` 经该 agent 自身的原生网关 `runSubagentStream` 路由，并像普通对话一样注册为可中断单元（mid-turn Ctrl-C 终止该流并交回控制）。仅 `expose_to_user=true` 的子agent 可被 switch；未 expose 的后台 spawn 保持只读（经其 `task_output`）。绑定网关 MUST NOT 回归普通交互对话流路径。

#### Scenario: 绑定网关不回归交互流
- **WHEN** 一个开启 subagents 的 agent 在构建时 eager 绑定网关，随后进行普通对话
- **THEN** 普通 `stream(...)` 仍逐字产出模型答案（不经网关的公平队列/路由）

#### Scenario: expose 后可被直连
- **WHEN** 父 agent 以 `expose_to_user=true` spawn 子agent，父流发出携带 subagentId 的 `SubagentExposedEvent`
- **THEN** `chatWithSubagent(subagentId, msg)` 把消息送达该子agent，返回其应答事件流

#### Scenario: 未知/失效 id 不抛异常
- **WHEN** 对未知的 subagentId 调用直连
- **THEN** 返回 error `Flux`（前端呈现一行错误），不抛异常、不崩溃

### Requirement: REPL 的 /agent sub 命令与 switch 状态机
系统 SHALL 提供 `/agent sub list|view <id>|switch <id>|back`：`list` 展示已 expose 子agent（标注当前 switch），`view <id>` 展示单条元数据，`switch <id>`（须经门面校验 id 存在）进入该子agent、`back` 返回父 agent。REPL MUST 维护"当前 switch 进入的子agent"状态：处于该状态时，普通输入 MUST 路由到 `chatWithSubagent`（不记入父会话、不触发父压缩/保存），提示符 MUST 显示所进入的子agent；若该子agent 已失效（切换/重建后），MUST 回退到父 agent 并提示。expose 事件到达时，REPL MUST 在门面登记该 id 并提示如何 switch。

#### Scenario: switch 已知子agent
- **WHEN** `/agent sub switch <已知id>`
- **THEN** 进入该子agent，后续普通输入送达它；`back` 后返回父 agent

#### Scenario: switch 未知子agent被拒
- **WHEN** `/agent sub switch <未知id>`
- **THEN** 拒绝并提示，switch 状态不变（仍在父 agent）

#### Scenario: switch 态下的对话不落父会话
- **WHEN** 处于 switch 态并输入一条消息
- **THEN** 经 `chatWithSubagent` 路由，且不调用父会话的记录/压缩/保存

#### Scenario: 子agent失效回退父
- **WHEN** switch 进入的子agent 已被清空（活动 agent 切换/模型重建），再输入一条消息
- **THEN** 清除 switch、提示不可用，并把该输入交由父 agent 处理

