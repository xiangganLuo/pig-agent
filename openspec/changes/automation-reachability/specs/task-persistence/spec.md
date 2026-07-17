## ADDED Requirements

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
