## Why

长期记忆当前经 AgentScope 的 `STATIC_CONTROL`（`ReActAgent` + `LongTermMemory`）参与调用：其内置 `StaticLongTermMemoryHook` 在 **`PreCallEvent`** 用 `retrieve` 结果拼一条独立的 `long_term_memory` USER 消息，追加到本回合输入。**问题（实测反编译 + 回归测试确认）**：`PreCallEvent` 的消息列表会流经 `AgentBase.notifyPreCall → doCall → addToMemory` **被写入会话 Memory 并随 JsonSession 存盘**——即注入内容并非 ephemeral，而是**逐轮累积**进会话历史，每回合都新增一份记忆快照、重复回传模型、且污染持久化历史。虽然（因是 append-only）prefix cache 仍成立，但这是一个真 bug：记忆被当成对话内容永久落盘并线性膨胀。

借鉴 Hermes 的思路，把记忆作为 **真正 ephemeral 的 user 侧上下文**注入：只在本次模型调用时出现，不写会话历史/存储，从而 system prompt 跨回合恒定（命中前缀缓存、多轮省输入 token），且历史不被记忆污染、不累积。依据路线图 `docs/planning/tui-and-core-roadmap.md`（Spec B③）。本 spec 范围**仅记忆**；其它易变块（时间戳/画像等）后续按需再优化。

## What Changes

- **改用自有 hook，不再用 `STATIC_CONTROL` 接线**：`PigAgent` 不再把 `LongTermMemory` 挂到 `ReActAgent`（那会启用会持久化注入的 `StaticLongTermMemoryHook`），改为新增 `EphemeralMemoryContextHook`。
- **在 `PreReasoningEvent` 侧 ephemeral 注入**：该事件的输入列表由 `prepareMessages()`（`[system] + memory.getMessages()`）每次推理新建、**不回写 Memory**，因此把 `retrieve` 结果作为一条末尾 USER 消息追加到这里天然 ephemeral——只进本次模型调用，不落会话历史/存储、不逐轮累积。
- **`record` 保留不变**：在 `PostCallEvent` 委托回 `CompositeLongTermMemory.record(...)`（会话层，与原 `StaticLongTermMemoryHook` 的 record 半部等价），记忆仍照常落盘。
- **记忆开关语义不变**：`/memory off` 关闭时 `retrieve` 返回空（不注入）、`record` 为 no-op；两层记忆（全局 + 会话）合并与来源标注沿用现状，只改「注入位置 + 是否持久化」。

无 **BREAKING**：记忆仍进入模型输入且仍在 user 侧、record 行为不变、关闭记忆行为不变；仅修复「注入被持久化并逐轮累积」这一 bug，使注入真正 ephemeral。

## Capabilities

### New Capabilities
- `prefix-cache-context`: 检索到的长期记忆作为**真正 ephemeral** 的 user 侧内容注入本回合模型调用（不落库、不改历史、不逐轮累积），使 system prompt 跨回合恒定以命中前缀缓存。

### Modified Capabilities
<!-- 无既有 capability spec：记忆/压缩当前无 openspec 主 spec；本变更为新增能力，不改 model-protocol/model-retry 契约。 -->

## Impact

- **代码**：`pig-agent-core` —— 新增 `memory/EphemeralMemoryContextHook`；`agent/PigAgent` 去掉 `STATIC_CONTROL`/`longTermMemory` 接线，改挂该 hook。
- **不改**：`CompositeLongTermMemory` 的两层合并/来源标注/`record` 只写会话层等既有行为；`/memory` 开关语义；`CompressionService`（与之互补，压缩仍只作用于内存对话）。
- **测试**：注入后 system prompt 跨回合字节稳定；记忆确进入模型输入（user 侧）；持久化历史**不含**注入内容且跨多轮不累积；`record` 仍写会话层；`/memory off` 不注入且 record no-op。
- **文档**：`CLAUDE.md` 两层记忆段落补「记忆经自有 hook 在 `PreReasoningEvent` 做 user 侧 ephemeral 注入、`PostCallEvent` record，以保 prefix cache 且不污染历史」。
