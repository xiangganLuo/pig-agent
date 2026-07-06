## MODIFIED Requirements

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
