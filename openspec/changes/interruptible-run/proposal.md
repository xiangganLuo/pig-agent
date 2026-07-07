## Why

当前 `ReActAgent` 一旦发起模型调用便不可中断：用户无法中途停止一次回合，超时取消后底层 agent 仍在运行、重订阅即撞「Agent is still running」。正因如此，model-retry 的 `per-attempt-timeout-seconds` 被迫默认 0（禁用），数字员工的超时只能 best-effort。本变更引入**可中断的模型调用**：模型调用跑在后台线程，主线程同时等待 {响应就绪 / 中断信号 / 超时}，中断即放弃该线程且不把半截响应写入历史。它同时是 TUI 停止键的实现前提，并让 model-retry 的真 per-attempt 硬超时可安全启用。依据路线图 `docs/planning/tui-and-core-roadmap.md`（Spec B①）。

## What Changes

- **新增可中断的模型调用**：在 `Model` 装饰层（与现有 `RetryingModel` 同层、协作）把 `model.stream` 的订阅跑在后台线程，主线程等待 {首个/后续信号、中断信号、可选超时}；收到中断/超时即取消订阅、放弃该线程，**不把半截内容写入对话历史**。
- **新增 `AgentKernel.interruptCurrent()`**：请求中断「当前活动回合」，返回是否已发起中断；供 TUI/前端停止键调用。签名一次定稿。
- **启用 model-retry 真 per-attempt 硬超时**：`per-attempt-timeout-seconds > 0` 时，超时**中断当前 attempt**（借新的可中断能力）并按既有退避策略重试；`= 0` 仍为关闭（向后兼容）。慢但健康的响应在超时未到时不受影响。
- **与既有约束协同**：中断/超时后不重订阅 `reactAgent.stream()`（`ReActAgent` 单飞）；沿用「已产出内容后不重试」的防重复输出约束——中途中断即抛出/结束，不从头重生。

无 **BREAKING**：`interruptCurrent()` 为新增；`per-attempt-timeout-seconds` 默认仍 0，缺省行为不变。

## Capabilities

### New Capabilities
- `interruptible-run`: 一次模型回合可被中途取消——模型调用在后台线程执行，收到中断信号或（可选）超时即取消该调用且不污染对话历史；对上经 `AgentKernel.interruptCurrent()` 暴露，供前端停止键调用。

### Modified Capabilities
- `model-retry`: 「每次尝试超时」需求变更——基于新的可中断能力，`per-attempt-timeout-seconds > 0` 可安全启用（超时中断当前 attempt 并重试），不再因不可中断而被迫默认关闭；慢但健康响应仍不被误伤。

## Impact

- **代码**：`pig-agent-core`（`agent`/`retry`：新增可中断 `Model` 装饰或增强 `RetryingModel`；`kernel`：`AgentKernel.interruptCurrent()` + 当前回合的中断句柄追踪）。
- **配置**：`model.retry.per-attempt-timeout-seconds` 语义从「不安全故禁用」改为「可安全启用」；默认值不变（0）。
- **协作/不改**：`RetryingModel` 的瞬时/永久错误分类、退避、防重复输出、作用范围豁免（`/model test` 不重试）等既有需求不变。
- **消费方**：TUI（`tui-frontend` 已接桩 `interruptCurrent()`，本变更提供真实现）；digital-employee 的超时可由 best-effort 升级为真中断（后续按需接入，不在本 spec 强制）。
- **测试**：可中断装饰层单测（中断即取消、历史无半截、超时触发中断+重试、慢响应不误伤）；`AgentKernel.interruptCurrent()` 单测。
- **文档**：`CLAUDE.md` 的 model-retry 段落更新「per-attempt 超时现可安全启用」。
