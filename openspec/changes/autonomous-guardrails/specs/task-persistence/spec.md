## ADDED Requirements

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
