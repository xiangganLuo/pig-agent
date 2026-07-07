## Why

`CompositeLongTermMemory` 检索到的记忆当前进入 system prompt 一侧参与每回合调用。system prompt 随记忆变动而变动，破坏 Anthropic/OpenRouter 的 **prefix cache**——多轮对话每回合都是缓存未命中，白白多花输入 token。借鉴 Hermes：把记忆作为 **ephemeral 内容注入当前回合的 user 消息**，让 system prompt 跨回合恒定，命中前缀缓存（多轮可省 75%+ 输入 token）。依据路线图 `docs/planning/tui-and-core-roadmap.md`（Spec B③）。本 spec 范围**仅记忆**；其它易变块（时间戳/画像等）后续按需再优化。

## What Changes

- **记忆注入 user 侧而非 system prompt**：每回合调用前，把 `CompositeLongTermMemory.retrieve` 的合并记忆作为 ephemeral 文本**追加到当前回合的 user 消息**；system prompt 不再随记忆变动。
- **ephemeral、不落库、不改历史**：注入只在 API 调用时发生，持久化的会话历史中的原始 user 消息 MUST NOT 被篡改，注入内容 MUST NOT 写入会话存储。
- **记忆开关语义不变**：`/memory on|off` 关闭时不注入；两层记忆（全局 + 会话）合并与来源标注沿用现状，只改「注入到哪一侧」。

无 **BREAKING**：记忆仍进入模型输入，仅位置从 system 侧改为 user 侧；关闭记忆行为不变。

## Capabilities

### New Capabilities
- `prefix-cache-context`: 检索到的长期记忆作为 ephemeral 内容注入当前回合的 user 消息（不落库、不改历史），使 system prompt 跨回合恒定以命中前缀缓存。

### Modified Capabilities
<!-- 无既有 capability spec：记忆/压缩当前无 openspec 主 spec；本变更为新增能力，不改 model-protocol/model-retry 契约。 -->

## Impact

- **代码**：`pig-agent-core`（记忆检索→消息组装的注入点；确保写在 agent 调用前的 user 消息构造处，而非 system prompt 组装处）。
- **不改**：`CompositeLongTermMemory` 的两层合并/来源标注/`record` 只写会话层等既有行为；`/memory` 开关语义；`CompressionService`（与之互补，压缩仍只作用于内存对话）。
- **测试**：注入后 system prompt 跨回合字节稳定；记忆内容确进入模型输入（user 侧）；持久化历史的原始 user 消息不被篡改；`/memory off` 不注入。
- **文档**：`CLAUDE.md` 两层记忆段落补「记忆经 user 侧 ephemeral 注入以保 prefix cache」。
