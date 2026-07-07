## ADDED Requirements

### Requirement: 可中断的模型调用

模型调用 SHALL 在后台线程执行其 `model.stream` 订阅，主线程同时等待 {响应信号 / 中断信号 / 可选超时}。收到中断或超时时，系统 SHALL 取消该订阅并放弃后台线程，且 MUST NOT 把任何半截内容写入对话历史。该能力 SHALL 在 `Model` 装饰层实现，与现有 `RetryingModel` 同层协作，MUST NOT 通过重订阅 `reactAgent.stream()` 达成（`ReActAgent` 单飞）。

#### Scenario: 中断即取消且不污染历史
- **WHEN** 一次模型调用进行中收到中断信号
- **THEN** 底层调用被取消、后台线程被放弃，对话历史不含该回合的半截输出，agent 立即可接受新输入

#### Scenario: 未中断的调用正常完成
- **WHEN** 一次模型调用未收到中断信号也未超时
- **THEN** 调用正常产出并返回，行为与未引入本能力时一致

### Requirement: 经门面请求中断当前回合

`AgentKernel` SHALL 提供 `interruptCurrent()`，请求中断「当前活动回合」并返回是否已发起中断（无进行中回合时返回否）。前端（TUI 停止键等）SHALL 经此接口请求中断，MUST NOT 直接操作内部 agent/线程。

#### Scenario: 有进行中回合时请求中断
- **WHEN** 某回合流进行中调用 `interruptCurrent()`
- **THEN** 返回「已发起中断」，该回合的模型调用被取消

#### Scenario: 无进行中回合时请求中断
- **WHEN** 无活动回合时调用 `interruptCurrent()`
- **THEN** 返回「未发起」（no-op），不影响后续回合
