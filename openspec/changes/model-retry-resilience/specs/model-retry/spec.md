## ADDED Requirements

### Requirement: 瞬时错误自动重试

模型调用遇**瞬时错误**时，系统 SHALL 自动重试，最多 `max-retries`（默认 10）次，每两次之间按**指数退避 + 封顶**等待。瞬时错误包括：HTTP 5xx（如 502/503）、请求超时、网络/IO 中断。

#### Scenario: 上游 502 后重试并成功
- **WHEN** 模型调用第一次返回 502（瞬时），重试时上游已恢复
- **THEN** 系统自动重试并返回成功响应，用户无需手动重发

#### Scenario: 持续瞬时失败到达上限后放弃
- **WHEN** 模型调用连续 `max-retries` 次均返回瞬时错误
- **THEN** 系统在用尽重试后抛出最后一次错误，且总重试次数不超过 `max-retries`

### Requirement: 永久性错误不重试

对**永久性错误**（HTTP 4xx，尤其 401/403 认证、400 请求错误），系统 SHALL NOT 重试，MUST 立即抛出，不浪费退避时间。

#### Scenario: 认证失败立即失败
- **WHEN** 模型调用返回 401（认证失败）
- **THEN** 系统不重试，立即向用户报告错误

#### Scenario: 请求错误立即失败
- **WHEN** 模型调用返回 400（请求非法）
- **THEN** 系统不重试，立即报告错误

### Requirement: 每次尝试超时

系统 SHALL 对每一次尝试施加 `per-attempt-timeout-seconds`（默认 10s）超时；在流式路径上，一次尝试超过该时限 MUST 判为超时（归入可重试的瞬时错误）。

#### Scenario: 单次尝试超时触发重试
- **WHEN** 流式调用一次尝试超过 10s 未产出
- **THEN** 该次判为超时失败，触发下一次重试（若未达上限）

### Requirement: 防止重试导致重复输出

当一次流式响应**已经产出部分内容**后再失败，系统 SHALL NOT 重试该响应（避免重订阅使模型从头重新生成、重复刷屏）；只有在**尚未产出任何内容前**失败才允许重试。

#### Scenario: 早期失败可重试
- **WHEN** 流在产出任何内容前失败（如连接阶段 502）
- **THEN** 允许重试

#### Scenario: 中途失败不重试
- **WHEN** 流已输出部分响应内容后失败
- **THEN** 不重试，原样抛出错误，不重复已输出内容

### Requirement: 作用范围与豁免

重试 SHALL 覆盖交互对话流与渠道 agent 的模型调用；连通性探测 `/model test` SHALL NOT 套用重试（保持快速失败语义）。

#### Scenario: 交互对话享重试
- **WHEN** 交互 REPL 中的对话遇瞬时错误
- **THEN** 自动重试

#### Scenario: 连通性测试快速失败
- **WHEN** 执行 `/model test` 且目标不可用
- **THEN** 立即返回失败，不进行重试等待

### Requirement: 可配置与可关闭

重试行为 SHALL 由 `application.yaml` 的 `model.retry` 块配置：`enabled`（默认 true）、`max-retries`（10）、`per-attempt-timeout-seconds`（10）、退避基数与上限。缺省即默认值（向后兼容）；`enabled: false` MUST 完全旁路重试，等同旧行为。

#### Scenario: 缺省配置用默认值
- **WHEN** `application.yaml` 无 `model.retry` 块
- **THEN** 使用默认（启用、10 次、10s、指数退避封顶）

#### Scenario: 关闭重试回到旧行为
- **WHEN** 配置 `model.retry.enabled: false`
- **THEN** 模型调用不重试，首次失败即抛出

### Requirement: 重试对用户可见

每次重试前系统 SHALL 输出一条可见提示，至少包含当前重试序号/上限与原因，使用户知晓正在等待而非卡死。

#### Scenario: 重试提示
- **WHEN** 发生第 k 次重试（共 N 次上限）
- **THEN** 终端显示形如 `[retry k/N] <原因>, backing off <时长>` 的提示
