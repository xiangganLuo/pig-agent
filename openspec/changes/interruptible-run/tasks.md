## 1. Spike：模型调用取消行为验证（卡点，不过不进后续）

- [x] 1.1 验证「Reactor 订阅 `dispose` → 底层 `model.stream` 取消」在至少一个真实 provider（如 openai 协议）上的行为：能否真正中止上游调用，或至少「逻辑中断 + 不落历史」成立。（离线：用可控 `Sinks` 源验证兜底语义；真 provider 上游 HTTP 取消行为待联网人工验证）
- [x] 1.2 最小闭环：一个可中断 `Model` 装饰，收到中断信号即 `dispose` 当前流，验证订阅侧不再收到后续信号、无 AGENT_RESULT 落历史。
- [x] 1.3 结论落档：若某 provider SDK 完全不响应取消，明确「逻辑中断 + 历史干净」为兜底语义并记录；不可行则停下升级人工。

## 2. 可中断的 Model 装饰层

- [x] 2.1 新增可中断包装（或增强 `RetryingModel`）：`model.stream` 订阅可被每回合的「中断信号」取消（`Disposable`/`takeUntil`）。
- [x] 2.2 中断即取消订阅、不写半截历史；未中断正常产出（行为等价现状）。
- [x] 2.3 与 `RetryingModel` 组合顺序：超时/中断在单次 attempt 粒度，重试在外层。（`AgentFactory.decorate` = `RetryingModel(InterruptibleModel(model))`）
- [x] 2.4 单测：中断即取消 + 历史无半截；未中断正常完成。

## 3. AgentKernel.interruptCurrent()

- [x] 3.1 kernel 在 `chat`/`runNow` 启动回合时登记「当前回合中断句柄」（原子引用 + 回合 id），结束时清除。（chat 用 doOnSubscribe/doFinally；runNow try/finally。注：autonomous/channel 模型不接可中断装饰，属独立轨，避免与交互回合串扰——digital-employee 中断本 spec 不强制）
- [x] 3.2 `interruptCurrent()`：命中活动回合则触发中断返回 true，否则 no-op 返回 false（替换 `tui-frontend` 的桩实现）。
- [x] 3.3 单测：有/无进行中回合两分支；并发登记/清除竞态安全。

## 4. 启用 model-retry per-attempt 硬超时

- [x] 4.1 `per-attempt-timeout-seconds > 0` 时对每次 attempt 套 `timeout`，超时=中断当前 attempt 并按退避重试；`= 0` 保持关闭。（`RetryPolicy.apply` 既有机制，本 spec 以可中断能力兜底其安全性）
- [x] 4.2 守「已产出内容后不因超时重试」：中途已出内容 + 超时 → 原样结束不重试。（pre-emission guard `!emitted.get()`）
- [x] 4.3 单测：超时触发中断+重试；慢但健康（超时内产出）不误伤；已出内容+超时不重试；默认 0 行为不变。

## 5. 文档与验收

- [x] 5.1 `CLAUDE.md` model-retry 段落：`per-attempt-timeout-seconds` 现可安全启用（超时→中断→重试）。
- [x] 5.2 `mvn -pl pig-agent-core -am test` 绿；回归 model-retry 既有单测。
