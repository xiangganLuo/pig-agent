# task-execution Specification

## Purpose
TBD - created by archiving change task-executor-wiring. Update Purpose after archive.
## Requirements
### Requirement: 计划任务经隔离一次性 agent 执行其意图
当为 `TaskScheduler` 接上任务执行器（生产由配置门 `tasks.execute` 决定，默认关）后，一个触发的计划任务（CRON/DELAYED）MUST 把其意图（title，及非空时的 description）作为 mandate，经**隔离一次性、非交互、fail-closed 权限**的 agent 运行一次并捕获结果。执行器 MUST 复用既有 `AgentRunner` 的机器（隔离构建 / 重入保护 / 尽力而为超时 / 事后 DENIED 扫描），MUST NOT 使用交互 HITL confirmer，MUST NOT 复用当前活跃 agent 实例。当**未**接执行器（默认，或测试）时，计划任务 MUST 保持"仅提醒"翻状态（TODO→IN_PROGRESS→COMPLETED），与既有行为字节等价。

#### Scenario: 执行器接上时任务意图被运行并捕获结果
- **WHEN** 接上执行器后，一个 DELAYED(0) 任务触发
- **THEN** 该任务的意图经一次性隔离 agent 运行，运行结果（成功摘要或失败/超时说明）被写回该任务并可读取

#### Scenario: 未接执行器时保持仅提醒
- **WHEN** 未 `setTaskExecutor`（默认）时一个计划任务触发
- **THEN** 任务不运行任何 agent、不消耗 token，仅按既有语义翻状态 TODO→IN_PROGRESS→COMPLETED

#### Scenario: 执行器自身异常回退 TODO
- **WHEN** 执行器闭包在处理触发任务时抛异常
- **THEN** 该任务被回退为 TODO（沿用 seam 既有语义），不标记 COMPLETED

### Requirement: AgentRunner 提供 ad-hoc mandate 运行入口
`AgentRunner` MUST 提供 `runMandate(String id, String mandate)`（及带超时的重载），复用与 `run(AgentSpec)` 相同的隔离一次性构建、重入保护、尽力而为超时与事后 DENIED 扫描机器；它 MUST NOT 写晨报、MUST NOT 更新任何持久 `AgentSpec`（ad-hoc 运行无持久 spec）。该方法 MUST 返回运行的 `AgentReport` 且 MUST NOT 抛异常——运行失败/超时以对应 outcome 的 report 返回。合成 agent 的权限上下文 MUST 由注入的 builder 决定（生产为 fail-closed 非交互）。

#### Scenario: runMandate 返回成功报告且无晨报副作用
- **WHEN** 以一个成功完成的（桩）模型调用 `runMandate(id, mandate)`
- **THEN** 返回 outcome=SUCCESS 且 body 含模型输出的 `AgentReport`，且未触发任何 reportWriter/specUpdater 副作用

#### Scenario: runMandate 运行失败以报告返回而非抛出
- **WHEN** 底层（桩）模型运行出错
- **THEN** `runMandate` 返回 outcome=FAILURE 且 note 非空的报告，不向调用方抛异常

### Requirement: 任务运行结果持久化无损
`Task` MUST 携带不可变的 `result`（本次运行摘要，可空）与 `lastRunAt`（运行时刻 Instant，可空）字段，经 `withXxx` 拷贝方法维护；其余 `withXxx`（如 `withStatus`）MUST 保留这两字段。文件任务仓库 MUST 无损往返这两字段：`result` 单行转义存储（含换行的多行结果读回一致）、`lastRunAt` 以 ISO-8601 存储。**旧格式**任务 `.md`（无 Result/LastRun 行）MUST 容错解析为 `result=null`/`lastRunAt=null`，不崩、不影响既有 schedule/时间戳/描述往返。有结果的任务 MUST 在 `/tasks` 列表以一行摘要展示。

#### Scenario: result 与 lastRunAt 往返一致
- **WHEN** 保存一个带多行 `result` 与特定 `lastRunAt` 的任务再回读
- **THEN** 回读任务的 `result`（含换行）与 `lastRunAt` 与保存前一致

#### Scenario: withStatus 保留运行结果
- **WHEN** 一个已带 `result`/`lastRunAt` 的任务经 `withStatus` 改状态并保存回读
- **THEN** 新状态生效且 `result`/`lastRunAt` 不丢失

#### Scenario: 旧文件无结果字段仍正常解析
- **WHEN** 读取一个不含 Result/LastRun 行的旧任务 `.md`
- **THEN** 解析成功，`result`/`lastRunAt` 为 null，既有 schedule/描述/时间戳往返不受影响

