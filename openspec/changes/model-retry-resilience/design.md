## Context

模型调用现状：交互路径 `AgentRepl.streamToAgent` 直接 `agentHolder.get().stream(msg)` 消费 `Flux<Event>`；渠道路径 `ChannelAgentBridge` 走 agent 调用。任一处遇上游瞬时故障（502/超时/网络）即失败，无自愈。`/model test` 用独立的 `probe.call()` 探测，本意快速失败。

澄清已定：每次尝试 10s 超时、最多 10 次、指数退避+封顶、仅瞬时错误、范围=交互+渠道（不含 test）。

## Goals / Non-Goals

**Goals:**
- 瞬时错误（5xx/超时/网络）自动重试，参数：每次 10s 超时、≤10 次、指数退避+封顶。
- 永久性错误（4xx/认证）不重试，立即抛出。
- 重试对用户可见、可经 `application.yaml` 配置、可关闭。
- 覆盖交互对话流 + 渠道 agent；`/model test` 不套重试。

**Non-Goals:**
- 不改模型协议 SPI / `models.json` / onboarding。
- 不做跨模型 fallback（失败切换到另一个模型）——另议。
- 不彻底解决阻塞 `call()` 的硬中断（与 `digital-employee` 超时 spike 同源，本 spec 只对 `stream()` 路径保证硬超时）。

## Decisions

- **D1：重试实现为可复用的 reactive 重试算子，应用在 `PigAgent` 的流式/调用边界。** 交互与渠道都经 `PigAgent` 发起，故在此单点包裹即同时覆盖两者；`/model test` 的 `probe` 走不套重试的构造/路径。备选：在每个调用点各自包裹 —— 重复且易漏，否决。
- **D2：仅重试瞬时错误。** 分类器 `TransientErrorClassifier`：HTTP 5xx（含 502/503）、超时（`TimeoutException`）、网络/IO（`IOException`/连接类）判为可重试；4xx（尤其 401/403/400）与其余判为不可重试。分类基于异常消息/类型（AgentScope 抛的是带状态码的流错误，按消息含 `502`/`503`/`5xx` + 异常类型识别）。
- **D3：每次尝试 10s 超时用 `Flux.timeout(Duration.ofSeconds(10))`。** 在 `stream()` 路径干净落地：超时抛 `TimeoutException` → 归入可重试。`call()`（渠道若走阻塞）路径的硬超时受既有 `.block()` 难中断限制，首版尽力而为（`Mono.timeout` 能触发重试信号，但底层请求未必真中断），此限制写入 Risks 并与 spike 关联。
- **D4：指数退避 + 封顶。** `retryWhen(Retry.backoff(maxRetries, firstBackoff).maxBackoff(cap))`；默认 `firstBackoff=0.5s`、`cap=8s`、`maxRetries=10`。仅对 D2 判定可重试的错误退避重试；不可重试错误经 `filter` 直接透传。
- **D5：防重复输出——pre-emission 才重试。** 一旦 `stream` 已发出任何 `AGENT_RESULT` 内容（用户已看到部分响应），再失败**不重试**、原样抛出；只有在“尚未产出内容前”失败才重订阅重试。实现：重试算子只包裹到“首个内容事件”之前，或用标志位在已发内容后关闭重试。避免重订阅导致模型从头再刷一遍。
- **D6：配置驱动 + 可关。** `application.yaml` 新增 `model.retry`：`enabled`(默认 true)/`max-retries`(10)/`per-attempt-timeout-seconds`(10)/`first-backoff-ms`(500)/`max-backoff-ms`(8000)。缺省即默认值，向后兼容。`enabled:false` 完全旁路，等于旧行为。
- **D7：重试可见。** 每次重试前经回调渲染一行提示（交互终端：`[retry k/N] <cause>, backing off <d>…`）。回调注入，core 不依赖 CLI 渲染。

## Risks / Trade-offs

- [重订阅导致重复输出] → D5 的 pre-emission 守卫：已流出内容后不重试。单测覆盖“中途失败不重试、早期失败才重试”。
- [阻塞 `call()` 路径超时难真中断] → D3 承认限制，`stream()` 路径保证硬超时；阻塞路径超时尽力而为，彻底解决留超时 spike（复用 `digital-employee` 的 task 0）。
- [瞬时/永久错误分类误判] → D2 基于状态码+异常类型；单测覆盖 502→重试、401→不重试、超时→重试、400→不重试。误判偏保守（拿不准当不可重试，快速失败）。
- [重试放大上游负载] → 指数退避+封顶+上限 10 次，最坏总等待有界（约 0.5+1+2+4+8×… 封顶后线性，数十秒级）；且仅瞬时错误触发。
- [超时/重试拖慢“本该快速失败”的场景] → `/model test` 明确不套重试；`enabled:false` 可全局关闭。

## Migration Plan

- 纯新增横切能力 + 新配置块，默认值即启用，无数据格式变更。不满意可 `model.retry.enabled: false` 回旧行为。
- 回滚：改动集中在 `pig-agent-core/retry` + 调用边界 + 配置；回退分支即可。

## Open Questions

- 退避默认值（0.5s 起、8s 封顶）是否合适？可在 code 阶段依实测微调，不影响 spec 契约。
- 渠道路径当前实现走 `call()` 还是 `stream()`？影响 D3 超时在渠道的落地程度——code 阶段核对 `ChannelAgentBridge` 后定；不改变“渠道也享重试”的结论。
