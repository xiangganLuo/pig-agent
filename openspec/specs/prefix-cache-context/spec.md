# prefix-cache-context Specification

## Purpose
TBD - created by archiving change prefix-cache-context. Update Purpose after archive.
## Requirements
### Requirement: 记忆经 user 侧 ephemeral 注入

当记忆启用且检索到内容时，系统 SHALL 把 `CompositeLongTermMemory` 合并后的记忆作为 ephemeral 文本注入**当前回合模型调用的 user 侧**，而非 system prompt。注入 MUST 只发生在 API 调用时；持久化会话历史中的原始 user 消息 MUST NOT 被篡改，注入内容 MUST NOT 写入会话存储，且 MUST NOT 随回合累积进历史。

#### Scenario: 记忆注入 user 侧
- **WHEN** 记忆启用且某回合检索到相关记忆
- **THEN** 该回合传给模型的 user 侧消息包含记忆文本，而 system prompt 不含该记忆

#### Scenario: 原始历史不被篡改且注入不落库
- **WHEN** 一个含记忆注入的回合完成后查看会话历史
- **THEN** 历史中的原始 user 消息不含注入的记忆文本，且历史中不存在任何承载注入记忆的消息（注入是 ephemeral 的，不写会话存储）

#### Scenario: 跨回合不累积
- **WHEN** 同一会话连续多个回合、每回合记忆内容不同
- **THEN** 持久化历史只随真实对话增长，不因记忆注入而新增消息（不逐轮堆积记忆快照）

### Requirement: system prompt 跨回合恒定

在同一会话、同一模型下，系统 SHALL 保持 system prompt 跨回合字节级恒定（不因记忆变动而变动），以命中上游前缀缓存。

#### Scenario: 多回合 system prompt 稳定
- **WHEN** 同一会话连续多个回合、记忆内容在回合间发生变化
- **THEN** 各回合的 system prompt 保持一致（不随记忆变化），记忆变化体现在 user 侧

### Requirement: 记忆开关语义不变且 record 保留

`/memory off` 关闭记忆时，系统 SHALL NOT 注入任何记忆；两层记忆（全局 + 会话）的合并与来源标注、`record` 只写会话层等既有行为不变，仅注入位置与是否持久化改变。记忆启用时，系统 SHALL 在回合结束后照常 `record` 到会话层。

#### Scenario: 关闭记忆不注入且 record no-op
- **WHEN** `/memory off` 且发起一个回合
- **THEN** 该回合 user 侧不含记忆注入、system prompt 亦不含记忆，且不发生 record 写入

#### Scenario: 启用记忆时 record 仍写会话层
- **WHEN** 记忆启用且一个回合完成
- **THEN** 该回合的对话内容经 `record` 写入会话层记忆（全局层不被写入）

