## 1. Spike：模型调用取消行为验证（卡点，不过不进后续）

- [ ] 1.1 验证「Reactor 订阅 `dispose` → 底层 `model.stream` 取消」在至少一个真实 provider（如 openai 协议）上的行为：能否真正中止上游调用，或至少「逻辑中断 + 不落历史」成立。
- [ ] 1.2 最小闭环：一个可中断 `Model` 装饰，收到中断信号即 `dispose` 当前流，验证订阅侧不再收到后续信号、无 AGENT_RESULT 落历史。
- [ ] 1.3 结论落档：若某 provider SDK 完全不响应取消，明确「逻辑中断 + 历史干净」为兜底语义并记录；不可行则停下升级人工。

## 2. 可中断的 Model 装饰层

- [ ] 2.1 新增可中断包装（或增强 `RetryingModel`）：`model.stream` 订阅可被每回合的「中断信号」取消（`Disposable`/`takeUntil`）。
- [ ] 2.2 中断即取消订阅、不写半截历史；未中断正常产出（行为等价现状）。
- [ ] 2.3 与 `RetryingModel` 组合顺序：超时/中断在单次 attempt 粒度，重试在外层。
- [ ] 2.4 单测：中断即取消 + 历史无半截；未中断正常完成。

## 3. AgentKernel.interruptCurrent()

- [ ] 3.1 kernel 在 `chat`/`runNow` 启动回合时登记「当前回合中断句柄」（原子引用 + 回合 id），结束时清除。
- [ ] 3.2 `interruptCurrent()`：命中活动回合则触发中断返回 true，否则 no-op 返回 false（替换 `tui-frontend` 的桩实现）。
- [ ] 3.3 单测：有/无进行中回合两分支；并发登记/清除竞态安全。

## 4. 启用 model-retry per-attempt 硬超时

- [ ] 4.1 `per-attempt-timeout-seconds > 0` 时对每次 attempt 套 `timeout`，超时=中断当前 attempt 并按退避重试；`= 0` 保持关闭。
- [ ] 4.2 守「已产出内容后不因超时重试」：中途已出内容 + 超时 → 原样结束不重试。
- [ ] 4.3 单测：超时触发中断+重试；慢但健康（超时内产出）不误伤；已出内容+超时不重试；默认 0 行为不变。

## 5. 文档与验收

- [ ] 5.1 `CLAUDE.md` model-retry 段落：`per-attempt-timeout-seconds` 现可安全启用（超时→中断→重试）。
- [ ] 5.2 `mvn -pl pig-agent-core -am test` 绿；回归 model-retry 既有单测。
