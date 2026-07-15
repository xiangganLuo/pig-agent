## Why

内核上下文/记忆热路径有三处「按功能硬写」的低效点，在这个 tool 密集的编码 agent 上被放大：

1. **token 估算偏低** —— `CompressionService.estimateTokens` 只数 `Msg.getTextContent()`，完全忽略 tool 调用入参与 tool 返回结果（shell/file/web 工具动辄返回 KB 级文本）。→ 估算严重偏低 → 压缩触发太晚 → 真实上下文早已冲破预算。
2. **摘要丢 tool 上下文** —— `ModelSummarizer.summarize` 只把 `getTextContent()` 喂给摘要模型，tool 结果被丢弃 → 恰恰丢掉「那条命令 / 那个文件返回了什么」这类最该保留的上下文（摘要 prompt 明明写着「保留重要 tool 结果」，却没真把它们交给模型）。
3. **记忆检索逐步重复读盘** —— `EphemeralMemoryContextHook.injectMemory` 在**每个** `PreReasoningEvent`（每步 ReAct 推理）都 `retrieve(...)`，每次都 `Files.readString` 全局 + 会话 `memory.md`。一个回合内 query（末条 user 消息）不变，检索结果恒等，却被逐步重复读盘 N 次。

## What Changes

- **tool-aware token 估算**：估算覆盖每条消息的**全部** content block（文本 + tool 调用入参 + tool 结果载荷），仍保留 `~字符数/4` 启发式，但作用于完整序列化内容，使触发反映真实上下文规模。
- **tool-aware 摘要**：喂给摘要模型的文本纳入 tool 调用 + tool 结果内容（紧凑、按块截断），使摘要能保留重要 tool 输出。
- **回合内记忆检索缓存（Decorator）**：新增 `CachingLongTermMemory` 装饰器包裹注入的 `LongTermMemory`，按 query 内容缓存 `retrieve`；query 变化（新回合）→ 缓存未命中 → 重新检索；`record` 直通底层并使缓存失效。回合内 N 步推理只读盘一次。
- **设计模式落地（用户强制要求，避免面向功能硬写）**：`MsgContentRenderer`（Msg 全内容序列化，DRY 单点）被估算 + 摘要复用；`TokenEstimator`（Strategy）+ `CharBudgetTokenEstimator` 默认实现；`CachingLongTermMemory`（Decorator）。

无 **BREAKING**：估算/摘要更准但对外契约不变；缓存对调用方透明（仍是一个 `LongTermMemory`）；`/memory` 开关、`record` 只写会话层、两层合并与来源标注、压缩触发阈值/保留策略/lineage 均不变。

## Capabilities

### New Capabilities
- `context-memory-efficiency`：内核上下文/记忆热路径的效率优化——tool-aware 的 token 估算与摘要，及回合内记忆检索缓存装饰器。

### Modified Capabilities
<!-- 压缩估算/摘要、记忆注入当前无独立主 spec 契约。本能力为新增，不改 prefix-cache-context（注入位置/ephemeral/record 语义）与 compression-lineage（压缩不碰持久化历史/记忆）的既有契约。 -->

## Impact

- **代码**：`pig-agent-core` —— 新增 `compression/MsgContentRenderer`、`compression/TokenEstimator`、`compression/CharBudgetTokenEstimator`、`memory/CachingLongTermMemory`；改 `compression/CompressionService`（估算/摘要走新组件）、`memory/EphemeralMemoryContextHook`（构造时包裹缓存装饰器）。
- **不改**：`CompositeLongTermMemory` 两层合并/来源标注/`record` 只写会话层；`/memory` 开关；`FileSystemLongTermMemory` 读写；压缩触发阈值/保留策略/lineage 记录。
- **测试**：估算计入 tool 内容（大 tool 结果远高于纯文本）；摘要输入含 tool 结果文本；缓存同 query 只读盘一次、新 query 重读、`record` 直通并失效、禁用路径 no-op；不回归既有 `CompressionServiceTest` / `MemoryUserSideInjectionTest`。
- **文档**：`CLAUDE.md` 压缩 + 两层记忆段落可补一句说明（后续）。
