## ADDED Requirements

### Requirement: 清空会话时重置压缩快照
生产装配 MUST 把 `SessionManager` 的 `snapshotResetHook` 接到 `CompressionService::resetSnapshot`（经 7 参构造），使 `/session clear`（`clearConversation`）在清空某会话对话后**真正重置该会话的压缩快照**（`lastCompressedAt`/`lastCompressedSize`）。因此清空后的 `maybeCompress`/`status` MUST NOT 再按清空前的消息数推理。当未接钩子（测试/无 wiring）时 MUST 保持无操作（不崩），既有 6 参构造语义不变。

#### Scenario: 清空会话触发快照重置钩子
- **WHEN** 生产装配以 7 参构造接上 `compressionService::resetSnapshot` 后，对当前会话执行 `/session clear`
- **THEN** 该会话的压缩快照被重置，后续压缩判定不再受清空前消息数影响

#### Scenario: 未接钩子时无操作
- **WHEN** 以 6 参构造（`snapshotResetHook=null`）执行 `clearConversation`
- **THEN** 清空正常完成、不抛异常，不进行任何快照重置
