## Why

源码核对 AgentScope 2.0 后发现一批**对话状态 / 压缩正确性**缺陷：

- **[HIGH] 压缩打错了 state 槽 → 整个压缩差异化能力实际失效。** 原生 `ReActAgent` 在每次 `call`/`streamEvents` 开始时都会从共享 `JsonFileAgentStateStore` **重新加载** `(userId,sessionId)` 槽（`activateSlotForContext`），并在回合结束时保存。`CompressionService` 通过 `agentHolder.get().getMemory()`（无参 → **默认槽** `(null, agentName)`）读写内存，而 pig 的真实对话在 `(pig, <sessionId>)` 槽；即便改对了槽，压缩后没有 `saveTo(sessionId)` 回写，下一回合的重载会**丢弃**内存改写。结果：`/compress now` 与自动压缩被静默回退。
- **[MEDIUM] 删除会话遗留对话状态在磁盘上（隐私/磁盘泄漏）。** `SessionManager.delete` 只删元数据 sidecar（`sessions/{id}/`），原生状态槽 `workspace/state/pig/<id>/` 从不删除。
- **[LOW] 清空会话未重置压缩快照。** `clearConversation` 后 `lastCompressedSize/At` 仍是清空前的值。
- **[LOW/doc] 持久化注释误导。** `FileSystemSessionRepository`/`SessionManager` 的注释宣称 `sessions/{id}/` 存放对话状态、且 pig 的 `saveCurrent` 是唯一持久化机制——与 2.0 现实（原生自动持久化主 agent；pig 的 `saveTo` 只是即时 flush 的冗余）不符。

## What Changes

- `CompressionService` 的内存供给改为**会话作用域**：`currentMemory(sessionId)` → `agent.getMemory(sessionId)`（`(pig,sessionId)` 槽），`maybeCompress/compressNow/status` 三处调用点全部按 sessionId 取槽。
- 压缩改写内存后**立即持久化**该槽（新增 persist seam → `agentHolder.get().saveTo(sessionId)`），使原生每回合重载看到压缩后的状态；持久化失败容错（记日志、不中断）。
- 新增 `PigAgent.deleteConversation(sessionId)`（调用注入的 `AgentStateStore.delete("pig", sessionId)`，容错）；`SessionManager.delete` 对每个被删 id 调用它，连同 sidecar 一并清除。
- `SessionManager.clearConversation` 通过可选 `snapshotResetHook`（镜像 `memoryToggleHook`）重置该会话的压缩快照。
- 修正 `FileSystemSessionRepository`/`SessionManager` 的误导性持久化注释。

## Capabilities

### New Capabilities
- `state-correctness`: 对话状态与上下文压缩的正确性——压缩作用于活跃会话槽并回写持久化、删除会话清除原生状态槽、清空会话重置压缩快照。

### Modified Capabilities
<!-- openspec/specs 目前为空，无既有能力的需求变更 -->

## Impact

- **代码**：`pig-agent-core`（`compression/CompressionService`、`agent/PigAgent` 仅新增 `deleteConversation`）、`pig-agent-session`（`SessionManager`、`FileSystemSessionRepository`）。
- **数据/隐私**：删除会话现在也清除 `workspace/state/pig/<id>/`，不再遗留孤儿对话。
- **行为**：`/compress now` 与自动压缩现在真正生效并跨回合保留；无破坏性 API 变更（`CompressionService`/`SessionManager` 公开构造子不变，`PigAgent` 仅新增方法）。
- **范围外**：会话进入时的历史回放渲染（F2）由并行 fixer/lead 负责；本变更只保证 DATA + 压缩正确。
