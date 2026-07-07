## 1. Spike（卡点，先做）：ReActAgent 可中断性

- [x] 1.1 结论承接 `model-retry` 已证：`ReActAgent` 单飞、不可中断，客户端超时无法真停底层调用。
- [x] 1.2 依结论定超时策略：`maxIters` 软上限 + 后台线程 `Future.get`/`cancel` 尽力而为墙钟超时（超时标记+产报告，绝不重订阅）。落在 `AgentRunner`（AgentRunnerTest 的 timeout 用例守住）。

## 2. 调度通用化 + cron 库（pig-agent-task）

- [x] 2.1 单测 `TaskSchedulerGenericTest`：通用 `schedule(id,TaskSchedule,Runnable)` DELAYED 触发；真 cron（cron-utils）解析定点；legacy `*/N`/`@macro` 回退。
- [x] 2.2 `TaskScheduler` 抽通用调度方法 + cron-utils 自重排；`schedule(Task)` 委托保留 Task 路径。引入 cron-utils 9.2.1。
- [x] 2.3 `mvn -pl pig-agent-task -am test` 绿，`TaskTest` 无回归。

## 3. AgentSpec 自主字段扩展（pig-agent-core，MODIFIED agent-management）

- [x] 3.1 单测 `AgentSpecAutonomousTest`：mandate/schedule/commandAllowlist/timeoutSeconds/lastRunAtEpochMs 的 withXxx + 默认值 + isAutonomous；持久化往返不丢新字段。
- [x] 3.2 扩展 `AgentSpec`（12 字段 + 7 参兼容构造器）+ `AgentSpecRepository` front-matter 新字段。schedule 用 String（避免 core→task 依赖）。
- [x] 3.3 `mvn -pl pig-agent-core -am test` 绿。

## 4. AgentRunner + 无人值守安全 + 晨报（pig-agent-core / pig-agent-tools）

- [x] 4.1 单测 `AgentRunnerTest`（假模型）：隔离一次性 agent；防重入；成功/失败/超时都产报告；回写 lastRunAt；denied→等你决定。
- [x] 4.2 实现 `AgentRunner`（隔离一次性 + maxIters/尽力而为超时 D4）+ `AgentReport` 三段式 + `FileReportWriter`。
- [x] 4.3 `DeniedActionRecorder` + `ToolPermissionHook` 加 `DenialListener` 回调（否决→recorder）。
- [x] 4.4 `WorkspaceManager` 加 `reports/` 目录；FileReportWriter 落 reports/{date}/{id}.md（写失败不崩）。

## 5. 调度接入 + /agent run|report 命令（pig-agent-cli）

- [x] 5.1 单测 `DigitalEmployeeScheduledRunTest`：调度器→AgentRunner→晨报落盘（离线确定性）。
- [x] 5.2 实现 `/agent run|report` 命令（`AgentCommand`）+ `ReplContext` 暴露 `AgentRunner`/reportsDir + `/help` 增补。
- [x] 5.3 `PigAgentCli` 装配：自主 `AgentBuilder`（RetryingModel + 受限 toolkit + 无人值守权限档 merge commandAllowlist + denial→recorder）+ `AgentRunner` + 把带 schedule 的 agent 注册进调度器。

## 6. 样例 + 集成测试 + 文档

- [x] 6.1 样例 `docs/examples/nightwatch.md`（守夜人：commandAllowlist [mvn,git]，schedule 每晚2点）。
- [x] 6.2 端到端离线确定性测试 `DigitalEmployeeScheduledRunTest` 覆盖判据（到点自主跑→晨报）。
- [x] 6.3 全模块 `mvn test` BUILD SUCCESS，无回归。
- [x] 6.4 文档：`CLAUDE.md` 数字员工段（自主运行/无人值守/晨报/超时限制/命令）。
