## ADDED Requirements

### Requirement: 全部 agent 施加迭代上限
系统 MUST 对**每一类** agent（交互、渠道、自主）施加 `maxIters` 迭代上限，经统一构建路径接入底层 `ReActAgent`。上限值来源：自主与每-agent 实例用其 `AgentSpec.maxIters`；交互/渠道共享 agent 用 `config.agent.max-iters`。仅当上限值 `> 0` 时施加；值 `<= 0` 表示不设（沿用底层默认）。模型运行时切换后重建的 agent MUST 沿用同一上限（不因换模型而丢失约束）。

#### Scenario: 自主 agent 受其 spec 上限约束
- **WHEN** 一个自主 agent 以 `AgentSpec.maxIters = N`（N>0）运行 mandate
- **THEN** 其底层 `ReActAgent` 以 `maxIters=N` 构建，推理-工具循环达到 N 即结束本轮

#### Scenario: 交互 agent 也受上限约束
- **WHEN** 交互回合的共享 agent 构建
- **THEN** 以 `config.agent.max-iters` 作为 `maxIters` 构建（此前交互 agent 不受显式上限约束）

#### Scenario: 模型切换后仍受限
- **WHEN** 用户运行时切换模型、agent 被重建
- **THEN** 重建的 agent 仍以相同 `maxIters` 构建，约束不丢失

#### Scenario: 非正值不施加
- **WHEN** 上限值 `<= 0`
- **THEN** 不调用底层 `maxIters`，保持 AgentScope 默认行为
