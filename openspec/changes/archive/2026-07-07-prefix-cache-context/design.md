## Context

`CompositeLongTermMemory`（`LongTermMemory`）合并全局（`workspace/context/memory.md`）+ 会话（`sessions/{id}/temp-memory.md`）两层，`retrieve` 带来源标注，`record` 只写会话层，受 `/memory on|off` 全局开关控制。

`PigAgent` 当前把它挂到 `ReActAgent` 并用 `LongTermMemoryMode.STATIC_CONTROL`。反编译 AgentScope 1.0.12 + 离线回归测试确认其真实行为：内置 `StaticLongTermMemoryHook` 在 **`PreCallEvent`** 用 `retrieve` 拼一条 `long_term_memory` USER 消息追加到输入——但 `PreCallEvent` 的列表经 `AgentBase.notifyPreCall → doCall → addToMemory` **被写入会话 Memory 并随 JsonSession 存盘**。所以注入并非 ephemeral，而是**逐轮累积**进会话历史（每回合一份记忆快照）：污染持久化历史、重复回传模型、线性膨胀。system prompt 本身固定（不含记忆），prefix cache 因 append-only 仍成立——真正的 bug 是「注入被持久化并累积」。`CompressionService` 只作用于内存对话，与记忆解耦。

## Goals / Non-Goals

**Goals:**
- 记忆作为**真正 ephemeral** 的 user 侧内容注入本回合模型调用；system prompt 跨回合恒定以命中 prefix cache。
- 不落库、不改历史、**跨轮不累积**；`record` 与 `/memory` 开关及两层合并语义不变。

**Non-Goals:**
- 不移动其它易变块（时间戳/画像等）出 system prompt（后续按需）。
- 不改 `CompositeLongTermMemory` 的合并/来源/record 行为。
- 不改压缩逻辑。

## Decisions

- **D1 — 注入点在 `PreReasoningEvent`，不在 `PreCallEvent`**：注入必须落在「不回写 Memory」的路径上才是 ephemeral。`PreReasoningEvent` 的输入列表来自 `prepareMessages()`（`[system] + memory.getMessages()`），每次推理新建、只喂本次 `model.stream`、不写回 Memory；而 `PreCallEvent`（`StaticLongTermMemoryHook` 用的点）会经 `addToMemory` 持久化。故自建 `EphemeralMemoryContextHook` 在 `PreReasoningEvent` 追加记忆。
- **D2 — ephemeral 语义（实现方式修订）**：不再走 AgentScope 的 `STATIC_CONTROL`（其注入会持久化累积）。改为把 `retrieve` 结果作为一条末尾 `long_term_memory` USER 消息追加到 `PreReasoningEvent` 的输入副本（`setInputMessages`），仅用于本次 API 调用；会话历史里只有用户原始输入，无任何注入痕迹。（原设计「自建注入 hook」在此坐实为 `PreReasoningEvent` 侧注入，明确**不新增到 `PreCallEvent`/不复用会持久化的 `StaticLongTermMemoryHook`**。）
- **D3 — record 保留**：`record` 在 `PostCallEvent` 委托回 `CompositeLongTermMemory.record(event.getMemory().getMessages())`——与原 `StaticLongTermMemoryHook` 的 record 半部等价（会话层、`/memory off` 时 no-op），不引入 record 回归。注入既不入 Memory，也就不会被 record 误当成用户输入回写。
- **D4 — 与开关/两层的关系**：`/memory off` → `retrieve` 返回空 → 注入被 `filter` 跳过；`record` 走 `CompositeLongTermMemory` 的 no-op。启用时沿用两层合并 + 来源标注，仅改注入位置与是否持久化。
- **D5 — 与压缩协同**：压缩作用于内存对话历史（现在确实不含注入，因注入只在 `PreReasoningEvent` 副本里），二者天然不冲突。
- **D6 — 切断 `STATIC_CONTROL` 的安全性**：`ReActAgent.Builder.build()` 仅在 `longTermMemory != null` 时 `configureLongTermMemory`（注册 `StaticLongTermMemoryHook`/`LongTermMemoryTools`）。`PigAgent` 不再调用 `.longTermMemory(...)`，等价于既有「无长期记忆」路径（channel/无记忆用例一直走这条），故切断该 mode 不触发任何 null 分支，也无其它地方依赖它。

## Risks / Trade-offs

- **R1 — 每回合都注入增大 user token**：记忆进 user 侧意味着每回合都带记忆文本（不被缓存）。权衡：system prompt（通常更大、含 skills/工具指引）稳定被缓存，净收益为正；必要时配合压缩/裁剪记忆长度。相较修复前，避免了历史里累积多份旧记忆快照，反而更省。
- **R2 — 注入位置回归**：若某路径仍把记忆混入 system prompt 或改回 `PreCallEvent`，则前缀缓存失效或注入再被持久化。→ 测试断言 system prompt 跨回合字节稳定 + 历史不含注入且跨轮不累积，守住不变量。
- **R3 — 历史一致性**：注入不得写回历史，否则重放/压缩会把记忆当成用户输入。→ 测试断言持久化历史不含注入内容（强化版，修复前为 RED、修复后 GREEN）。
- **R4 — record 回归**：切掉 `STATIC_CONTROL` 时若一并删掉 record，会导致记忆永不落盘。→ 保留 `PostCallEvent` record 并加测试断言 record 后会话层确有内容。

## 落实追踪表（评审/发现项 → 落点 + 状态）

| 发现项 | 落点 | 状态 |
|--------|------|------|
| 原前提「记忆进 system prompt」不成立 | Why/Context 更正为「`STATIC_CONTROL` 在 `PreCallEvent` 注入」 | 已更正 |
| 实测：注入经 `doCall.addToMemory` 被持久化并逐轮累积（真 bug） | Why/Context + D1/D2；测试 `injection_isEphemeral_*`、`injection_doesNotAccumulateAcrossTurns` | 已实现 |
| 注入需真正 ephemeral | D1/D2：`EphemeralMemoryContextHook` 在 `PreReasoningEvent` 注入 | 已实现 |
| record 不得回归 | D3/R4：`PostCallEvent` 委托 `CompositeLongTermMemory.record`；测试 `record_writesConversationToSessionTierAfterTurn` | 已实现 |
| `/memory off` 语义不变 | D4；测试 `memoryDisabled_nothingInjectedAndRecordIsNoOp` | 已实现 |
| system prompt 跨回合恒定 | R2；测试 `systemPrompt_byteStableAcrossTurns_whenMemoryChanges` | 已实现 |
| 切断 `STATIC_CONTROL` 的安全性 | D6：build() 仅在 LTM!=null 时配置，切断等价无记忆路径 | 已实现 |
