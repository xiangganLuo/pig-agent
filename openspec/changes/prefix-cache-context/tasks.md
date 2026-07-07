## 1. 记忆 user 侧 ephemeral 注入

- [x] 1.1 实测定位现路径：`STATIC_CONTROL` 的 `StaticLongTermMemoryHook` 在 `PreCallEvent` 注入，经 `doCall.addToMemory` 被持久化累积（反编译 + RED 测试确认）。
- [x] 1.2 新增 `EphemeralMemoryContextHook`：在 `PreReasoningEvent`（来自 `prepareMessages()`、不回写 Memory）追加 `retrieve` 结果为末尾 USER 消息 → 真正 ephemeral（不落会话历史/存储、不累积）。
- [x] 1.3 `PigAgent` 去掉 `.longTermMemory()`/`STATIC_CONTROL` 接线，改把该 hook 加入 hooks；`record` 在 `PostCallEvent` 委托回 `CompositeLongTermMemory.record`。
- [x] 1.4 `/memory off` 时 `retrieve` 空→不注入、`record` no-op；启用时沿用两层合并 + 来源标注。

## 2. 单测（离线回归）

- [x] 2.1 记忆启用时 user 侧含记忆、system prompt 不含。
- [x] 2.2 system prompt 跨回合（记忆变化）字节级稳定。
- [x] 2.3 持久化历史不含注入内容（强化版：修复前 RED、修复后 GREEN），且跨多轮不累积。
- [x] 2.4 `/memory off` 不注入且 record no-op。
- [x] 2.5 记忆启用时 `record` 仍写会话层。

## 3. 验收

- [x] 3.1 `mvn -pl pig-agent-core -am test` 绿（`MemoryUserSideInjectionTest` 6 绿 + 全模块 66 绿，记忆/会话既有单测回归通过）。
- [x] 3.2 `CLAUDE.md` 两层记忆段落补「记忆经自有 hook 在 `PreReasoningEvent` 做 user 侧 ephemeral 注入、`PostCallEvent` record，以保 prefix cache 且不污染历史」。
