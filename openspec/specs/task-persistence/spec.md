# task-persistence Specification

## Purpose
文件任务仓库的容错读取（坏文件跳过不崩）与无损往返（schedule 全字段 + 时间戳 + 描述），使 CRON/DELAYED 任务跨重启存活并被 `scheduleAll` 正确重排。
## Requirements
### Requirement: 任务仓库容错读取
文件任务仓库在读取任务时，单个任务 `.md` 解析失败（文件损坏、缺字段、非法状态枚举等）MUST 被跳过并记 warn，MUST NOT 让 `findAll`/`findByStatus`/`findById` 抛异常或中断遍历。启动路径的任务调度（`scheduleAll`）MUST NOT 因单个坏任务文件而崩溃。容错遵循既有 `JsonModelStore`/`JsonMcpStore` 的"坏条目跳过、其余照常"范式，MUST NOT 用占位错误任务污染任务列表。

#### Scenario: 坏任务文件被跳过而非崩溃
- **WHEN** 任务目录含一个缺 `Status` 字段或状态值非法的 `.md`
- **THEN** `findAll` 跳过该文件并记 warn，返回其余有效任务，不抛异常

#### Scenario: 启动调度不被坏文件打断
- **WHEN** 启动时 `scheduleAll` 遍历任务、其中一个 `.md` 损坏
- **THEN** 进程正常启动，坏文件被跳过，其余任务正常调度

### Requirement: 任务持久化无损往返
任务写入与回读 MUST 无损保留调度信息、时间戳与描述：`schedule` 的完整内容（`ONCE` / `CRON` 的 cron 表达式 / `DELAYED` 的秒数）、`createdAt`/`updatedAt`、`description`。回读重建的 `Task` MUST 与写入前等价（就上述字段而言）。因此 CRON/DELAYED 任务在进程重启后 MUST 仍被 `scheduleAll` 识别为非 ONCE 并正确重排。旧格式（仅记录 `type`、无 cron/delay 值）MUST 容错降级为 `ONCE` 而非崩溃。

#### Scenario: CRON 任务重启后仍被重排
- **WHEN** 一个 `CRON` 任务（如 `0 2 * * *`）被保存后进程重启
- **THEN** 回读得到的任务其 schedule 仍为 `CRON` 且 cron 表达式一致，`scheduleAll` 重新为其排程（不降级为 ONCE）

#### Scenario: DELAYED 与时间戳/描述往返
- **WHEN** 保存一个带 `DELAYED` 调度、非空描述与特定 `createdAt/updatedAt` 的任务再回读
- **THEN** 回读任务的 schedule 秒数、描述、时间戳与保存前一致

#### Scenario: 旧格式容错降级
- **WHEN** 读取一个旧格式 `.md`（Schedule 行仅有类型、无 cron/delay 值）
- **THEN** 该任务被读为 `ONCE`、不抛异常

### Requirement: 可选任务执行器 seam + 诚实的提醒-标记语义

`TaskScheduler` SHALL 暴露一个**可选**的任务执行器 seam：`setTaskExecutor(Consumer<Task>)`。设置后，一个定时任务触发时 `executeTask` MUST 把真实工作委托给该执行器（执行器抛异常时任务 MUST 被标记回 `TODO` 以便重试，否则标记 `COMPLETED`）。**未设置执行器（默认）**时，一个定时任务 MUST 被当作**提醒/标记**——不执行任何工作，并以日志如实反映其为提醒-标记（非自主运行），同时保留既有的 `TODO→IN_PROGRESS→COMPLETED` 状态翻转（行为中立）。seam 未接线时，调度器行为 MUST 与本能力引入前一致（既有任务测试保持绿）。

#### Scenario: 执行器已设置则被调用
- **WHEN** 已 `setTaskExecutor(...)`，一个定时任务触发
- **THEN** 执行器被以该任务调用（真实工作），任务随后被标记 `COMPLETED`

#### Scenario: 执行器抛异常则回退 TODO
- **WHEN** 已设置的执行器在处理任务时抛异常
- **THEN** 任务被标记回 `TODO`（可重试），MUST NOT 被标记 `COMPLETED`

#### Scenario: 未设置执行器则为提醒-标记
- **WHEN** 未设置执行器，一个定时任务触发
- **THEN** 不执行任何工作，任务仍走 `IN_PROGRESS`→`COMPLETED` 状态翻转（行为中立），并有一条如实的「提醒-标记、无执行器」日志

### Requirement: 5 段 cron 校验器（供 REPL 校验调度）

`TaskScheduler` SHALL 提供一个 public static 的 cron 校验器 `isValidCron(String)`，当且仅当入参是合法的标准 UNIX 5 段 cron 表达式时返回真。遗留的每-N-秒（`*/N`）与 `@macro` 简写 MUST NOT 被视为合法 cron（它们映射到粗粒度固定间隔，不应作为真实 cron 调度提供）。null/空白 MUST 返回假。

#### Scenario: 接受 5 段 cron
- **WHEN** 传入 `"0 2 * * *"`
- **THEN** 返回真

#### Scenario: 拒绝非 cron 与遗留简写
- **WHEN** 传入 `"garbage"`、`"*/30"`、`null` 或空白
- **THEN** 均返回假

