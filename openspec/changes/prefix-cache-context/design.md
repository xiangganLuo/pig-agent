## Context

`CompositeLongTermMemory`（`LongTermMemory`）合并全局（`workspace/context/memory.md`）+ 会话（`sessions/{id}/temp-memory.md`）两层，`retrieve` 带来源标注，`record` 只写会话层，受 `/memory on|off` 全局开关控制。当前记忆参与调用的方式使其进入 system prompt 一侧，导致 system prompt 随记忆变动、破坏上游 prefix cache。`CompressionService` 只作用于内存对话，与记忆解耦。

## Goals / Non-Goals

**Goals:**
- 记忆作为 ephemeral 内容注入当前回合 user 消息；system prompt 跨回合恒定以命中 prefix cache。
- 不落库、不改历史；`/memory` 开关与两层合并语义不变。

**Non-Goals:**
- 不移动其它易变块（时间戳/画像等）出 system prompt（后续按需）。
- 不改 `CompositeLongTermMemory` 的合并/来源/record 行为。
- 不改压缩逻辑。

## Decisions

- **D1 — 注入点在 user 消息构造处**：在 agent 发起回合、构造本回合 user 消息的位置追加记忆文本；确保不经过 system prompt 组装路径。理由：system prompt 恒定是命中 prefix cache 的关键，注入必须在 user 侧且 ephemeral。
- **D2 — ephemeral 语义**：注入的是「传给模型的这一份 user 消息副本」，不回写会话历史/存储。会话保存的仍是用户原始输入。实现上：检索记忆 → 生成「原始 user 文本 + 记忆块」的调用用消息，仅用于本次 API 调用。
- **D3 — 与开关/两层的关系**：`/memory off` → 跳过检索与注入；启用时沿用 `retrieve` 的合并 + 来源标注结果，仅改注入位置。
- **D4 — 与压缩协同**：压缩作用于内存对话历史（不含 ephemeral 注入，因注入不入历史），二者天然不冲突。
- **D5 — 展示/标注**：注入块可带轻量标注（如「[memory]」）便于模型区分，但不含敏感来源路径以外的内容；不影响 system prompt。

## Risks / Trade-offs

- **R1 — 每回合都注入增大 user token**：记忆进 user 侧意味着每回合都带记忆文本（不被缓存）。权衡：system prompt（通常更大、含 skills/工具指引）稳定被缓存，净收益为正；必要时配合压缩/裁剪记忆长度。
- **R2 — 注入位置回归**：若某路径仍把记忆混入 system prompt，则前缀缓存失效。→ 测试断言 system prompt 跨回合字节稳定，守住该不变量。
- **R3 — 历史一致性**：必须保证注入不写回历史，否则重放/压缩会把记忆当成用户输入。→ 测试断言持久化历史的原始 user 消息不含注入内容。
