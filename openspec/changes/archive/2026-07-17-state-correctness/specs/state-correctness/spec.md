## ADDED Requirements

### Requirement: 压缩作用于活跃会话槽

上下文压缩 SHALL 读写与被压缩会话相同的原生对话槽 `(userId="pig", sessionId)`（`agent.getMemory(sessionId)`），而非无参默认槽。`maybeCompress`/`compressNow`/`status` 三条路径 MUST 使用调用方传入的 `sessionId` 定位对话；`sessionId` 为 null 时回退默认槽（保持既有行为）。

#### Scenario: 压缩只改写目标会话的对话

- **WHEN** 对会话 A 触发 `compressNow("A")`，而会话 B 有独立对话
- **THEN** 只有 A 的对话被改写，B 的对话保持不变

#### Scenario: status 报告目标会话的真实规模

- **WHEN** 对一个非空会话调用 `status(sessionId)`
- **THEN** 返回的消息数与估算 token 数均为该会话槽的非零真实值

### Requirement: 压缩后立即持久化会话槽

一次成功的压缩改写内存后，系统 MUST 立即将该 `(pig, sessionId)` 槽持久化（回写 `AgentStateStore`），使原生在下一回合开始时的重载观察到压缩后的对话，而非丢弃内存改写。持久化失败 MUST 记日志并被吞掉，绝不中断或回退一次已成功的压缩。`sessionId` 为 null 时不持久化。

#### Scenario: 压缩改写跨重载保留

- **WHEN** 对会话执行 `compressNow(sessionId)` 使其对话被改写
- **THEN** 该槽被回写持久化，之后新建的、共享同一 store 的 agent 读回的是**压缩后**的对话

#### Scenario: 持久化失败不破坏压缩

- **WHEN** 压缩改写内存成功，但回写持久化抛出异常
- **THEN** 异常被记日志并吞掉，压缩仍报告成功，内存改写在当前进程内仍然有效

### Requirement: 删除会话清除原生对话状态槽

删除会话时，系统 MUST 同时删除元数据 sidecar 与原生对话状态槽 `(userId="pig", sessionId)`（经 `PigAgent.deleteConversation` → `AgentStateStore.delete`），不得在磁盘上遗留孤儿对话。每个槽删除 MUST 容错（记日志并继续），一个失败不得中断整批删除；null/blank 会话 id 为 no-op。

#### Scenario: 删除后原生槽消失

- **WHEN** 某会话已有持久化的对话槽，用户删除该会话
- **THEN** 该会话的原生状态槽与元数据 sidecar 均被移除，`loadIfExists` 返回 false

### Requirement: 清空会话重置压缩快照

清空一个会话的对话时，系统 SHALL 重置该会话的压缩快照（`lastCompressedAt`/`lastCompressedSize`），使后续的 `maybeCompress`/`status` 不再基于清空前的消息计数推理。该重置经可选钩子完成，未接线时为 no-op（保持既有行为）。

#### Scenario: 清空后快照被重置

- **WHEN** 对当前会话执行 clear 且已接线快照重置钩子
- **THEN** 该会话 id 被传给重置钩子，其压缩快照被清除
