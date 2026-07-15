## Context

`CompressionService` 在 token 预算超限时摘要较早回合、保留最近回合，只作用于内存对话。`estimateTokens`（触发判据）与 `ModelSummarizer.summarize`（喂给摘要模型的文本）当前都只取 `Msg.getTextContent()`。AgentScope 的 `Msg.getContent()` 返回 `List<ContentBlock>`，其中 `ToolUseBlock.getInput()` 是调用入参、`ToolResultBlock.getOutput()` 是结果载荷（`List<ContentBlock>`，通常为 `TextBlock`）——tool 内容不在 `getTextContent()` 里，故被两处一并漏掉。

记忆注入侧，`EphemeralMemoryContextHook` 持有一个 `LongTermMemory`（实际是 `CompositeLongTermMemory`），在每个 `PreReasoningEvent` 调 `retrieve(lastUserMsg)`，底层 `FileSystemLongTermMemory.retrieve` 每次 `Files.readString`。一个回合内末条 user 消息不变 → query 恒等 → 结果恒等，却被逐步重复读盘。

## Goals / Non-Goals

**Goals:**
- token 估算与摘要都覆盖 tool 内容（入参 + 结果），且「如何渲染一条 Msg 的完整内容」逻辑单点收敛（DRY）。
- 回合内记忆检索按 query 缓存，query 变化即失效，读盘从 N 次降到 1 次。
- 用设计模式表达（Decorator / Strategy），不做面向功能的过程式重复。

**Non-Goals:**
- 不引入精确 tokenizer（仍是 `~字符数/4` 近似，仅让「计入什么」更全）。
- 不改压缩触发阈值、保留回合数、lineage、`/memory` 开关、两层记忆合并语义。
- 不做跨回合/跨进程的持久缓存（缓存只是回合内热路径去重）。

## Decisions

- **D1 — Strategy：`TokenEstimator` 接口 + `CharBudgetTokenEstimator` 实现。** 把「如何由消息列表得到 token 估算」抽成策略接口，`CompressionService` 依赖接口（字段类型 `TokenEstimator`，默认注入字符预算实现），估算算法可替换、可独立单测。理由：估算是一个明确的、会演进（未来可换真 tokenizer）的算法点，Strategy 让它与压缩编排解耦；避免把估算逻辑硬写进 service。
- **D2 — DRY 单点：`MsgContentRenderer` 内容序列化助手。** 「把一条 Msg 的全部 content block（Text / ToolUse / ToolResult）渲染成字符串」这段逻辑只写一处，被 `CharBudgetTokenEstimator`（估算）与 `ModelSummarizer`（摘要）复用。理由：估算与摘要要看的是**同一份**完整内容，只是估算不截断、摘要按块截断；单点渲染消除了两处各写一遍 `getContent()` 解析的过程式重复（这正是需求点名要避免的）。
  - `render(Msg, maxCharsPerBlock)`：`maxCharsPerBlock <= 0` 表示不截断（估算用）；`> 0` 时每块截断（摘要用，防单条巨型 tool 结果撑爆摘要器自身上下文）。
  - `renderConversation(List<Msg>, maxCharsPerBlock)`：按 `role: content` 逐行拼接，供摘要 prompt。
  - `ToolResultBlock` 的 `getOutput()` 递归渲染其子块；未知块（Thinking/Image 等）不计入（保持聚焦 tool-awareness，避免 `toString` 不稳定）。
- **D3 — Decorator：`CachingLongTermMemory` 包裹注入的 `LongTermMemory`。** 缓存不写进 hook 的临时字段，而是做成一个同样实现 `LongTermMemory` 的装饰器，在 `EphemeralMemoryContextHook` 构造时包裹传入的 memory。hook 代码零改动地透明获益。理由：需求明确「用装饰器而非 hook 里的 ad-hoc 缓存字段」；装饰器保持 `LongTermMemory` 契约，可组合、可单测、对上下游透明。
  - **键：query 文本内容**（`Msg.getTextContent()`），单槽缓存（只记最近一个 query 及其结果）。回合内末条 user 消息恒定 → 命中；新回合 query 变 → 未命中 → 重新检索。选内容而非对象身份：保证「同一 query 必命中」可测、不依赖 AgentScope 是否复用 Msg 实例。
  - **缓存的是 `.cache()` 后的 `Mono<String>`**：底层冷 `Mono`（`Files.readString` 在订阅时执行）只被订阅一次，后续同 query 复用同一 Mono → 重放缓存值、不再读盘。
  - **`record` 直通并失效缓存**：`record` 委托底层（写会话层语义不变），同时清空单槽。因为 `record` 只在回合末（`PostCallEvent`）触发一次，故「每回合末失效」保证下一回合即便 query 文本恰好相同也重新检索，杜绝跨回合陈旧读（与「query 变化即失效」互补，覆盖同文本相邻回合的边角）。
  - **线程安全**：单槽用 `synchronized` 保护的一小段临界区即可（agent 单飞，冲突几乎为零，`Mono.cache()` 自身线程安全）。
- **D4 — 包裹点在 hook 构造函数。** 唯一构造 `EphemeralMemoryContextHook` 的地方是 `PigAgent.Builder.build()`；在 hook 构造时 `new CachingLongTermMemory(memory)`，则交互 / 频道 / 自治 agent 全部路径透明获益，无需改 `AgentFactory` / `AgentInstanceFactory`。
- **D5 — 缓存错误也被重放（可接受）。** `.cache()` 会缓存首次的错误信号并重放。回合内一次读盘失败通常会持续失败，重放错误可接受；hook 对检索异常本就 `onErrorResume` 优雅降级（不注入），且下一回合 `record` 会失效缓存。

## Risks / Trade-offs

- **R1 — token 仍是近似。** 本变更只让「计入什么」更全（纳入 tool 内容），不追求精确计数；`~字符数/4` 对中英文/代码/JSON 都是粗略近似。→ 目标是纠正「系统性漏计 tool 内容导致的严重偏低」，把触发拉回接近真实规模，而非精确预算。
- **R2 — 缓存陈旧。** 单槽内容键理论上存在「相邻两回合 query 文本完全相同、其间记忆已变」的陈旧风险。→ 由 `record` 每回合末失效兜底（`record` 必在回合末触发一次），实际不会陈旧；且缓存本就是回合内热路径去重，作用域小。
- **R3 — 摘要按块截断可能丢长 tool 结果尾部。** 为防单条巨型 tool 结果撑爆摘要器，按块截断（保留头部）。→ 权衡：保留「返回了什么」的关键前缀 > 完整但撑爆上下文；截断阈值取足够大（数千字符/块）。
- **R4 — 不回归既有语义。** `MemoryUserSideInjectionTest`（注入 ephemeral / system prompt 恒定 / record 只写会话层 / 禁用 no-op）与 `CompressionServiceTest`（lineage / 压缩不碰持久化）必须全绿——装饰器与渲染器都不改这些语义，且既有测试会顺带覆盖「装饰器透传禁用路径」。

## 落实追踪表（评审/需求发现项 → 落点 + 状态）

| 发现项 / 需求 | 落点 | 状态 |
|---|---|---|
| tool 内容漏计导致估算偏低 | D1 `TokenEstimator` + D2 `MsgContentRenderer`；spec Req A | 已实现 |
| 摘要丢弃 tool 结果 | D2 `MsgContentRenderer.renderConversation` + `ModelSummarizer` 改用之；spec Req B | 已实现 |
| 逐步重复读盘 | D3/D4 `CachingLongTermMemory` 装饰器 + hook 包裹；spec Req C | 已实现 |
| 「用设计模式，别面向功能编程」 | Decorator（缓存）+ Strategy（估算）+ DRY 单点渲染器（D1–D3） | 已实现 |
| 单条巨型 tool 结果撑爆摘要器 | D2 按块截断 `maxCharsPerBlock`；R3 | 已实现 |
| 跨回合陈旧读 | D3 `record` 失效缓存 + query 键；R2 | 已实现 |
| 精确 tokenizer | 非目标；R1 记录，后续能力可换实现（Strategy 已留口） | 延后 |
