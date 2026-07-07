# compression-lineage Specification

## Purpose
TBD - created by archiving change compression-lineage. Update Purpose after archive.
## Requirements
### Requirement: 压缩派生并持久化会话谱系

当一次上下文压缩发生时，系统 SHALL 记录压缩前后会话的 parent/child 谱系关系，并持久化到会话元数据。谱系 SHALL 可查询（能从压缩后的会话追溯其来源）。旧的、无谱系字段的会话 SHALL 被容错读取（视为无 parent）。

#### Scenario: 压缩记录 parent/child
- **WHEN** 某会话触发一次压缩
- **THEN** 压缩后的会话元数据记录其来源谱系（parent 指向压缩前状态），可查询

#### Scenario: 旧会话无谱系容错
- **WHEN** 读取一个在本能力之前创建、无谱系字段的会话
- **THEN** 系统正常读取并视其为无 parent，不报错

### Requirement: 压缩语义不变

记录 lineage MUST NOT 改变压缩的既有行为：压缩仍只作用于内存对话，MUST NOT 触碰持久化的对话历史与记忆内容；保留最近回合、成对保留 tool 消息等现有行为不变。

#### Scenario: 压缩不触碰持久化历史
- **WHEN** 发生一次带 lineage 记录的压缩
- **THEN** 持久化的对话历史与记忆内容不被修改，仅会话元数据新增谱系

