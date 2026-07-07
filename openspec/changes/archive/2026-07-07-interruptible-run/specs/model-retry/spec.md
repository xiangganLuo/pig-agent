## MODIFIED Requirements

### Requirement: 每次尝试超时（可选，默认关闭）

重试 SHALL 由**真实瞬时错误信号**（5xx/网络/IO）驱动。客户端每次尝试超时 SHALL 为**可选**（`per-attempt-timeout-seconds`，默认 0=关闭，向后兼容）。基于可中断的模型调用（能力 `interruptible-run`），当 `per-attempt-timeout-seconds > 0` 时，超时 SHALL 安全地**中断当前 attempt**（取消其后台调用、不污染历史）并按既有退避策略重试；MUST NOT 误伤慢但健康、超时未到即正常产出的响应。当 `= 0` 时保持关闭，行为不变。

#### Scenario: 慢但健康的响应不被误重试
- **WHEN** 一次流式响应因大上下文/工具而首个信号迟到，但在超时（或超时关闭时无限）之内最终正常产出
- **THEN** 系统 MUST NOT 因超时而重试或中断它，正常返回该响应

#### Scenario: 关闭超时不影响错误重试
- **WHEN** `per-attempt-timeout-seconds` 为 0（默认）且模型返回 502（瞬时）
- **THEN** 系统仍按瞬时错误重试

#### Scenario: 启用超时时超时中断并重试
- **WHEN** `per-attempt-timeout-seconds > 0` 且某次 attempt 在该时限内无任何信号
- **THEN** 系统中断该 attempt（取消其后台调用、历史无半截内容）并按退避策略发起下一次重试
