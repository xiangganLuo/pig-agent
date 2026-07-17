## 1. CompressionService：会话作用域 + 持久化（HIGH）

- [x] 1.1 内存供给改为会话作用域：字段 `Supplier<Memory>` → `Function<String,Memory> memoryFn`；`currentMemory()` → `currentMemory(String sessionId)`；`maybeCompress/compressNow/status` 三处调用点传入 sessionId。生产构造子映射为 `sessionId -> agent.getMemory(sessionId)`。
- [x] 1.2 压缩改写后立即持久化：新增 `Consumer<String> persister`（生产映射为 `sessionId -> agent.saveTo(sessionId)`），`compress(...)` 在 `sessionId != null` 时于改写后调用 `persist(sessionId)`（容错：记 warn、不中断、不回退返回值）。
- [x] 1.3 更新 package-private seam 构造子签名（`Function<String,Memory>` + `Consumer<String>`），更新类级 javadoc。
- [x] 1.4 单测：`compressPersistsRewrittenSessionSlot`（改写 + persist seam 触发）、`compressAndStatusOperateOnTheGivenSessionSlot`（会话作用域，仅目标槽被改写/持久化）、`statusReportsNonZeroForNonEmptySession`、`compressWithNullSessionIdDoesNotPersist`；更新既有 lineage 测试到新签名。
- [x] 1.5 端到端单测 `CompressionPersistenceTest`：真实 `PigAgent` + `JsonFileAgentStateStore`，压缩后新建同 store 的 agent 读回**压缩后**的槽（证明回写生效）。
- [x] 1.6 更新 `ModelSummarizerToolAwareTest`：seed `(pig,sess-1)` 槽（此前依赖默认槽/会话槽混淆的 BUG 行为）。

## 2. 删除会话清除原生状态槽（MEDIUM）

- [x] 2.1 `PigAgent` **仅新增** `deleteConversation(String sessionId)`：调用 `stateStore.delete("pig", sessionId)`；null/blank 为 no-op；失败记 warn 后继续（不触碰其他既有方法）。
- [x] 2.2 `SessionManager.delete(ids)` 对每个 id 调用 `agentHolder.get().deleteConversation(id)`（与 sidecar 删除并列）。
- [x] 2.3 单测 `delete_removesNativeConversationStateSlot`：seed 并持久化 `(pig,target)` 槽 → 删除 → `loadIfExists(target)` 为 false 且 sidecar 消失。

## 3. 清空会话重置压缩快照（LOW）

- [x] 3.1 `SessionManager` 新增可选 `Consumer<String> snapshotResetHook`（新 7 参构造子，6 参委托传 null；镜像 `memoryToggleHook`），`clearConversation` 中对 currentSessionId 调用。
- [x] 3.2 单测 `clearConversation_invokesSnapshotResetHook`（seam 触发；`CompressionService.resetSnapshot` 已存在，AgentBootstrap 接线由该文件属主后续补齐）。

## 4. 误导性持久化注释（LOW/doc）

- [x] 4.1 `FileSystemSessionRepository` 类 javadoc：更正“对话状态存放于 `sessions/{id}/`”为“对话状态在原生 `workspace/state/pig/{id}/`；本类仅元数据 sidecar”。
- [x] 4.2 `SessionManager.saveCurrent` 注释：更正为“原生已自动持久化主 agent；本 `saveTo` 是 belt-and-suspenders 即时 flush（冗余但不破坏）”。（`PigAgent` 的 `disableSessionPersistence` 注释由并行 fixer 负责，本变更不动。）

## 5. 验证

- [x] 5.1 `mvn -pl pig-agent-core -am test` GREEN（272/0/0）。
- [x] 5.2 `mvn -pl pig-agent-session -am test` GREEN（session 46/0/0）。
- [x] 5.3 `mvn -pl pig-agent-cli -am compile` BUILD SUCCESS（下游接线未破坏）。
