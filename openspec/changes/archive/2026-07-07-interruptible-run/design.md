## Context

`RetryingModel`（`io.pigagent.core.retry`）是包住底层 `Model` 的装饰层，`AgentFactory.create` 在有重试策略时套用它，覆盖交互 + 渠道。它在 `Flux.defer` 里重订阅 `model.stream` 做重试，对单飞的 `ReActAgent` 透明。当前无任何机制取消一次进行中的 `model.stream`：`per-attempt-timeout-seconds` 因此默认 0（见 model-retry spec），TUI/前端也无停止入口。`AgentKernel` 是前端唯一门面，持有活动 agent 视图。

## Goals / Non-Goals

**Goals:**
- 一次模型调用可被中途取消，取消后不污染对话历史、agent 立即可用。
- `AgentKernel.interruptCurrent()` 作为前端稳定停止入口。
- 让 model-retry 的 `per-attempt-timeout-seconds > 0` 可安全启用（超时→中断→重试）。

**Non-Goals:**
- 不改 `ReActAgent` 内部单飞语义（不重订阅 `reactAgent.stream()`）。
- 不改瞬时/永久错误分类、退避、防重复输出、豁免（`/model test`）等既有 model-retry 需求。
- 不强制把 digital-employee 的 best-effort 超时改为真中断（可后续接入）。
- 不改 `per-attempt-timeout-seconds` 默认值（仍 0）。

## Decisions

- **D1 — 可中断在 Model 装饰层实现**：新增可中断包装（或增强 `RetryingModel`），把 `model.stream` 的订阅置于可取消的后台执行；主线程/协调侧持有取消句柄。与 Reactor 亲和：用 `Flux` 的取消（`Disposable`/`takeUntil`(中断信号)/`timeout` 语义）而非裸线程，尽量复用 Reactor 的订阅取消传播到底层 HTTP。理由：与现有 `RetryingModel` 同层、对 `ReActAgent` 透明，符合 model-retry「重试在 Model 层、不重入 agent」既定约束。
- **D2 — 中断信号来源**：一个每回合的「中断信号」（如 `Sinks`/`AtomicBoolean` + `Disposable`），由 `AgentKernel.interruptCurrent()` 触发。装饰层订阅到该信号即 `dispose`/短路当前流。
- **D3 — `AgentKernel.interruptCurrent()`**：kernel 追踪「当前活动回合」的中断句柄（在 `chat`/`runNow` 启动回合时登记、结束时清除）。`interruptCurrent()` 命中句柄则触发中断并返回 true，否则 false。签名一次定稿（`tui-frontend` 已按此接桩）。
- **D4 — 不污染历史**：取消发生在 `model.stream` 层、`ReActAgent` 之下；因取消是「订阅未完成即 dispose」，不产生 AGENT_RESULT，历史不落半截。与「已产出内容后不重试」约束一致：中途取消即结束/抛出，不从头重生。
- **D5 — per-attempt 超时接入**：`per-attempt-timeout-seconds > 0` 时，在装饰层对每次 attempt 套 `timeout`；超时等价一次「中断当前 attempt」，随后按 `RetryPolicy` 退避重试（超时算可重试信号，但仍受「已产出内容后不重试」保护——已出内容则不因超时重试）。
- **D6 — 与 `RetryingModel` 的组合顺序**：可中断能力与重试在同一装饰链；超时/中断在「单次 attempt」粒度，重试在其外层。实现上可合并进 `RetryingModel` 或作为其内层装饰，保证「超时→取消当前 attempt→外层重试」的层次正确。

## Risks / Trade-offs

- **R1 — 底层 HTTP 是否真被取消**：Reactor 订阅 `dispose` 未必立刻中止底层 HTTP 连接（取决于 provider SDK 对取消的响应）。若 SDK 不响应取消，后台调用可能继续跑到自然结束——但只要**不把其结果写入历史**、且主线程已放行，用户体验上已「中断」。→ Spike 验证各 provider SDK 的取消行为，至少保证「逻辑中断 + 历史干净」。列为 **Spike 卡点**。
- **R2 — 超时误伤**：`per-attempt-timeout-seconds` 设太小会误伤慢大模型。默认保持 0（关闭）；文档指导按模型调大。scenario「慢但健康不误重试」守住语义。
- **R3 — 中断句柄的并发**：`interruptCurrent()` 与回合登记/清除存在竞态。用原子引用 + 回合 id 保护，中断只作用于当前回合，回合已结束则 no-op。
- **R4 — 与防重复输出的交互**：超时/中断发生在「已产出内容后」时，MUST 走「不重试、原样结束」路径，避免重复刷屏。测试需覆盖「中途已出内容 + 超时」不重试。
