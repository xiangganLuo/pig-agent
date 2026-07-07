# agent-kernel-facade Specification

## Purpose
TBD - created by archiving change agent-kernel-facade. Update Purpose after archive.
## Requirements
### Requirement: 单一内核门面

系统 SHALL 提供 `AgentKernel` 作为内核对外的唯一 API，覆盖：列出/获取/新建/修改/删除 agent、切换当前 agent、`chat(agentId, msg)` 返回 `Flux<Event>`、`runNow(agentId)`、启用/停用调度、`subscribeEvents()`。前端 MUST 只依赖 `AgentKernel`，不直接依赖内核内部类。

#### Scenario: 前端只经门面操作 agent
- **WHEN** 一个入口需要列出并切换 agent
- **THEN** 它通过 `AgentKernel.listAgents()` / `useAgent(id)` 完成，不引用 `AgentRegistry` 等内部类

#### Scenario: 交互对话经门面
- **WHEN** 入口发起一次对话
- **THEN** 经 `AgentKernel.chat(agentId, msg)` 得到 `Flux<Event>` 流式消费

### Requirement: 内核事件流

系统 SHALL 经 `AgentKernel.subscribeEvents()` 暴露 `Flux<KernelEvent>`，至少覆盖会话、运行、报告类事件，支持多订阅者（无订阅者时零成本）。

#### Scenario: 订阅内核事件
- **WHEN** 一个入口订阅 `subscribeEvents()`
- **THEN** 它收到会话/运行/报告事件，用于展示或可视化

#### Scenario: 无订阅者不影响运行
- **WHEN** 没有任何入口订阅事件流
- **THEN** 内核正常运行，不因事件流阻塞或报错

### Requirement: 入口即 adapter

新增入口（CLI/Web/渠道/未来）SHALL 作为 `AgentKernel` 的 adapter 接入，新增入口 MUST NOT 需要修改内核内部结构。

#### Scenario: 加入口不改内核
- **WHEN** 新增一个入口消费门面 API + 事件流
- **THEN** 无需改动 `AgentRegistry`/`AgentInstanceFactory` 等内核内部代码

### Requirement: 渠道为门面可见的独立轨道

渠道（Telegram/Discord 等）SHALL 运行在**自己的 agent holder + channel-mode 权限轨道**上，MUST NOT 成为门面可切换（`useAgent`）的内核 agent —— 以免无 confirmer 的渠道 agent 经 `/agent use` 泄漏到交互会话。渠道回合 SHALL 经 `AgentKernel` 暴露为**可见**事件（供 Web/状态观测），但其路由与模型切换轨道保持独立。

#### Scenario: 渠道活动经门面可见但不可切换
- **WHEN** 一条渠道消息到达
- **THEN** `AgentKernel.subscribeEvents()` 收到一个标记为该渠道的会话事件（如 `channel:<id>`），而该渠道 agent 不出现在 `listAgents()`/`useAgent` 的可切换集合中

#### Scenario: 无门面时渠道仍工作
- **WHEN** 渠道桥未注入 `AgentKernel`
- **THEN** 渠道照常路由消息到其专用 holder，仅不产生门面可见事件

### Requirement: CLI 经门面且行为不变

`AgentRepl`/`ReplCommands` SHALL 改为经 `AgentKernel` 驱动；改造后交互命令与对话流行为 MUST 与改造前一致。

#### Scenario: 斜杠命令行为不变
- **WHEN** 用户执行 `/agent list|use|new|model`、`/help` 等
- **THEN** 表现与门面改造前一致，回归测试保持绿

