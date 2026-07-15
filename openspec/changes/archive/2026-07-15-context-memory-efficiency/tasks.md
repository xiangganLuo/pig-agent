## 1. 内容序列化单点（Strategy/DRY 基座）

- [x] 1.1 新增 `compression/MsgContentRenderer`：`render(Msg, maxCharsPerBlock)` 渲染一条消息的全部 content block（Text / ToolUse 入参 / ToolResult 结果载荷，递归子块），`maxCharsPerBlock<=0` 不截断、`>0` 每块截断；`renderConversation(List<Msg>, maxCharsPerBlock)` 供摘要 prompt。
- [x] 1.2 单测：`render` 含 tool 调用入参与 tool 结果文本；未知块不计入；截断按块生效。（`MsgContentRendererTest` 7 例）

## 2. tool-aware token 估算（Strategy）

- [x] 2.1 新增 `compression/TokenEstimator` 接口 + `compression/CharBudgetTokenEstimator`（用 `MsgContentRenderer` 不截断渲染 + `字符数/4`）。
- [x] 2.2 `CompressionService` 依赖 `TokenEstimator`（字段默认注入字符预算实现），`maybeCompress`/`status` 改走它，移除内联 `estimateTokens`。
- [x] 2.3 单测：含大 tool 结果的消息列表估算 **远高于** 纯文本列表。（`CharBudgetTokenEstimatorTest` 3 例）

## 3. tool-aware 摘要

- [x] 3.1 `CompressionService.ModelSummarizer.summarize` 改用 `MsgContentRenderer.renderConversation(older, MAX_PER_BLOCK)`，纳入 tool 调用 + 结果内容。
- [x] 3.2 单测：喂给摘要模型的渲染文本包含 tool 结果载荷文本（渲染器断言 + `ModelSummarizerToolAwareTest` 端到端捕获模型输入）。

## 4. 回合内记忆检索缓存（Decorator）

- [x] 4.1 新增 `memory/CachingLongTermMemory`（实现 `LongTermMemory`）：按 query 文本单槽缓存 `.cache()` 后的 `retrieve`；`record` 直通底层并失效缓存；线程安全。
- [x] 4.2 `EphemeralMemoryContextHook` 构造时用 `CachingLongTermMemory` 包裹注入的 memory（hook 逻辑不变）。
- [x] 4.3 单测：同 query 两次只读盘一次；新 query 重读；`record` 委托底层；`record` 失效缓存；禁用（disabled composite）经装饰器仍 no-op。（`CachingLongTermMemoryTest` 5 例）

## 5. 验收

- [x] 5.1 `mvn -q test` 单线程绿；既有 `CompressionServiceTest` / `MemoryUserSideInjectionTest` 不回归。
- [x] 5.2 `mvn -q -pl pig-agent-cli -am compile` 绿。
