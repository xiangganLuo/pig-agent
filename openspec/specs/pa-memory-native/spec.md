# pa-memory-native Specification

## Purpose
TBD - created by archiving change pa-memory-native. Update Purpose after archive.
## Requirements
### Requirement: 跨会话记忆持久

个人助理 SHALL 让用户陈述的 durable 事实**跨会话持久**：当某会话记录了一条用户事实后，即使经 `/session new` 切换到新会话，系统 SHALL 仍能召回该事实。durable 事实 MUST 落在工作区级、不随会话切换而丢失的记忆层（而非仅会话级临时层）。

#### Scenario: 名字跨 /session new 被召回
- **WHEN** 会话 A 中用户陈述「我叫罗湘赣」，随后 `/session new` 切到会话 B，用户在 B 问「我叫什么」
- **THEN** 系统能召回「罗湘赣」（该事实在会话 A 被写入工作区级记忆，会话 B 仍可见）

#### Scenario: 事实写入工作区级而非仅会话级
- **WHEN** 一条 durable 用户事实被记录
- **THEN** 它进入工作区级记忆（跨会话可见），MUST NOT 仅落在会话切换即失效的会话临时层

### Requirement: 两层记忆（日志层 → 固化层，工作区级）

系统 SHALL 采用两层记忆模型：**日志层**（`memory/YYYY-MM-DD.md`，按日 append、原始、不去重）与**固化层**（`MEMORY.md`，经 LLM 合并去重、整文件重写）。两层 MUST 均为**工作区级**（不按会话隔离）。固化层 MUST 为注入模型上下文的长期记忆来源；日志层等待被合并进固化层，两层 MUST NOT 相互覆盖。

#### Scenario: 事实先入日志层
- **WHEN** 一次对话产生新的长期事实
- **THEN** 该事实被 append 到当日日志层文件（`memory/YYYY-MM-DD.md`）

#### Scenario: 固化层为注入来源
- **WHEN** 模型进行推理需要长期记忆
- **THEN** 注入的长期记忆来自固化层 `MEMORY.md`（而非逐条原始日志），且 `MEMORY.md` 为工作区单一来源

### Requirement: 自动 flush 抽取（无字数过滤）

系统 SHALL 在回合结束时自动 flush——由 LLM 从对话窗口抽取长期事实写入日志层——且抽取 MUST NOT 施加固定字数下限（如旧 `MIN_TEXT_LENGTH=20`）：短事实（如一个名字）MUST NOT 因长度被丢弃，是否记录由抽取模型按 flush prompt 判定。flush MUST NOT 阻塞回合返回（异步）。

#### Scenario: 短事实不被字数丢弃
- **WHEN** 用户陈述一条短于旧 20 字阈值的事实（如「我叫 X」）
- **THEN** 该事实经 flush 可被抽取记录，不因长度被静默丢弃

#### Scenario: flush 不阻塞回合
- **WHEN** 一个回合完成触发 flush
- **THEN** 回合响应先返回给用户，flush 抽取在其后（异步）执行，不阻塞回合

### Requirement: 后台 consolidation 去重固化

系统 SHALL 以节流的后台任务把日志层合并进固化层 `MEMORY.md`：合并 MUST 去重、MUST 受一个 token 上限约束（整文件重写到上限内）。consolidation 的节流 MUST 使其**不是每回合都跑**（后台最小间隔），从而固化层在一次会话内通常稳定。

#### Scenario: 日志合并去重进固化层
- **WHEN** 后台 consolidation 触发
- **THEN** 当日/近日日志层被合并进 `MEMORY.md`，重复事实被去重，输出不超过配置的 token 上限

#### Scenario: consolidation 节流
- **WHEN** 同一会话内连续多个回合、间隔短于最小 consolidation 间隔
- **THEN** consolidation 不在每回合都执行（受最小间隔节流），固化层在该会话内通常保持稳定

### Requirement: 廉价模型跑 flush/consolidation

系统 SHALL 允许 flush 与 consolidation 使用与主推理模型**不同的、更廉价的模型**（如 Doubao lite），而主推理模型不变。辅助模型的接入 MUST 复用 pig 自有的模型管理（注入一个 `Model` 实例），MUST NOT 要求主推理模型承担 flush/consolidation。

#### Scenario: 辅助操作走廉价模型
- **WHEN** 配置了记忆辅助模型且 flush/consolidation 触发
- **THEN** flush/consolidation 调用廉价模型，主推理仍走主模型

### Requirement: 记忆检索工具

启用记忆时，系统 SHALL 向 agent 暴露记忆检索工具：一个**关键词检索**工具（扫固化层 + 日志层，返回有上限的命中）、一个按路径/行区间读取的工具，以及一个搜索历史会话转录的工具。这些工具 MUST 为只读检索，MUST NOT 修改记忆内容。

#### Scenario: 关键词检索命中记忆
- **WHEN** agent 以关键词检索记忆且存在相关内容
- **THEN** 返回固化层/日志层中的相关命中（数量受上限约束），且不改动记忆文件

#### Scenario: 搜索历史转录
- **WHEN** agent 搜索历史会话转录
- **THEN** 返回匹配的历史片段（只读）

### Requirement: 记忆开关语义保持

`/memory off` 关闭记忆时，系统 SHALL NOT 注入任何长期记忆、SHALL NOT 执行 flush 写入；`/memory on` 恢复。对用户可见的语义（记忆是否参与、是否关闭）MUST 与本能力引入前一致——仅底层存储布局与注入位置改变。

#### Scenario: 关闭记忆不注入不 flush
- **WHEN** `/memory off` 且发起一个回合
- **THEN** 该回合不注入长期记忆、不发生 flush 写入

#### Scenario: 重新开启恢复
- **WHEN** `/memory on` 后发起一个回合
- **THEN** 长期记忆恢复注入、flush 恢复

### Requirement: 退役自建记忆并容错迁移旧数据

采用原生记忆后，系统 SHALL 退役 pig 自建的两层记忆（`CompositeLongTermMemory`/`FileSystemLongTermMemory`）与 A4 抽取（`memory-extraction`）；会话级隔离层退役。旧的全局记忆文件（`workspace/context/memory.md`）SHALL 被一次性、幂等地迁入固化层 `MEMORY.md`，迁移 MUST 容错——失败时降级为「新库从空开始、旧文件只读保留」，MUST NOT 因迁移失败而崩溃启动或丢失原文件（保留备份）。

#### Scenario: 旧全局记忆迁入固化层
- **WHEN** 存在旧 `workspace/context/memory.md` 且首次以新记忆栈启动
- **THEN** 其内容被幂等迁入 `MEMORY.md`（保留原文件备份），重复启动不重复迁移

#### Scenario: 迁移失败安全降级
- **WHEN** 旧记忆文件损坏或迁移出错
- **THEN** 启动不崩溃，新记忆库从空开始，旧文件被只读保留（记 warn 日志）

### Requirement: 上下文压缩保持正交

采用原生长期记忆 MUST NOT 改变 pig 的 A5 上下文工程压缩（`CompressionService`/`ContextEngineer`）：系统 SHALL 继续禁用原生 compaction 而使用 pig 自有压缩，压缩仍只作用于内存对话历史，与长期记忆落盘互不干扰。

#### Scenario: 压缩仍走 pig A5
- **WHEN** 内存对话超过压缩预算触发压缩
- **THEN** 由 pig A5（非原生 compaction）执行，且不改动长期记忆的日志层/固化层
</content>

