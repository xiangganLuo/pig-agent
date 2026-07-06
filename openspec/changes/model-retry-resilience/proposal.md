## Why

上游模型服务出现**瞬时故障**（如网关返回 `502 upstream_error`、超时、网络抖动）时，当前一次对话就直接失败、把错误抛给用户，得手动重发。日志实证：`Failed to stream Anthropic API: 502: Upstream service temporarily unavailable`——认证与请求都正常，纯粹是上游临时不可用。为这类可自愈的瞬时故障加**自动重试**，能让个人使用体验在上游打嗝时不中断。

## What Changes

- **新增可复用的重试策略**：对模型调用的**瞬时错误**自动重试——**最多 10 次**重试、**指数退避 + 封顶**；重试由真实错误信号驱动。（客户端每次尝试超时**默认关闭**——见下方风险：与不可中断 agent 不兼容，留待超时 spike。）
- **仅重试瞬时错误**：5xx（如 502/503）、超时、网络/IO 中断可重试；**4xx（401/403 认证、400 请求错误）不重试**（重试永久性错误无意义，只会拖慢报错）。
- **每次尝试超时默认关闭**（`per-attempt-timeout-seconds: 0`）：itest 实测客户端超时与不可中断的 `ReActAgent` 不兼容（误伤慢模型 + 超时取消后重订阅撞「Agent is still running」），改由错误信号驱动重试。
- **作用范围**：交互对话流（`PigAgent.stream`）+ 渠道 agent；**连通性探测 `/model test` 不套重试**（它本意是快速失败）。
- **防重复输出**：仅在**尚未产出任何响应内容前**失败才重试（重订阅=模型从头生成）；已流式输出部分内容后再失败则原样抛出，不重试，避免重复刷屏。
- **可配置**：`application.yaml` 新增 `model.retry` 块（`max-retries` 默认 10、`per-attempt-timeout-seconds` 默认 10、退避基数/上限），可调可关。
- **重试可见**：每次重试在终端给一行提示（如 `[retry 2/10] upstream 502, backing off 2s…`），用户知道在等什么而非卡死。

## Capabilities

### New Capabilities
- `model-retry`: 模型调用遇瞬时错误（5xx/超时/网络）时按「每次 10s 超时 + 最多 10 次 + 指数退避封顶」自动重试；永久性错误（4xx/认证）不重试；重试对用户可见、可配置、可关闭。

### Modified Capabilities
<!-- 无既有 spec 的 REQUIREMENT 变更：重试是新增的横切能力，不改 model-protocol 的协议契约。 -->

## Impact

- **代码**：`pig-agent-core`（新增 `retry/`：`TransientErrorClassifier` + `RetryPolicy` + `RetryingModel` 装饰器；`AgentFactory` 在有 policy 时包 `RetryingModel`——重试在**一次 agent 调用内部**的底层 model.stream 上，不重入单飞 agent）；`pig-agent-config`（`model.retry` 配置块）；`pig-agent-cli`（构建 policy + 重试提示渲染）；交互 + 渠道经 `AgentFactory` 单点覆盖。
- **配置**：`application.yaml` 新增 `model.retry`；缺省即上述默认值，向后兼容（无配置=用默认）。
- **风险/限制**：每次尝试超时在 `stream()`（Flux）路径用 `Flux.timeout` 干净落地；`call()` 阻塞路径的硬超时受既有「同步 `.block()` 难中断」限制（与 `digital-employee` 的超时 spike 同源），首版对阻塞路径超时为**尽力而为**，重试本身照常。
- **不改动**：模型协议 SPI、`models.json`、`/model test` 的快速失败语义。
