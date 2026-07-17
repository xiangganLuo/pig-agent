## 1. `/agent new` 自主标志（`pig-agent-cli`）

- [x] 1.1 `AgentCommand` 新增 `@Option --mandate/--schedule/--allow`（仅作用于 `new`）。
- [x] 1.2 `create()`：坏 cron（`TaskScheduler.isValidCron`）友好拒绝；`--schedule` 缺 `--mandate` 拒绝；经 `withMandate`/`withSchedule`/`withCommandAllowlist`（逗号分隔、trim、去空）构建 `AgentSpec`，经 `ctx.agentKernel().createAgent(spec)` 持久化；数字员工成功提示 + 「下次启动生效 / `/agent run` 立即」提示。
- [x] 1.3 更新 `usage()` 帮助文本（新增三个标志的说明行）。
- [x] 1.4 `AgentCommandTest`（5）：带三标志构建自主 spec、坏 cron 拒绝且不 create、schedule 缺 mandate 拒绝且不 create、无标志形态不变（interactive）、既存 id 拒绝。

## 2. 任务执行器 seam + 诚实（`pig-agent-task`）

- [x] 2.1 `TaskScheduler` 加可选 `Consumer<Task>` 执行器字段 + `setTaskExecutor(Consumer<Task>)`（nullable、线程安全 volatile、可清除）。
- [x] 2.2 `executeTask`：执行器已设 → IN_PROGRESS → `accept(task)` → COMPLETED（异常回退 TODO）；未设 → 提醒-标记路径（日志如实、IN_PROGRESS→COMPLETED，行为中立）。
- [x] 2.3 `isValidCron(String)`（public static）：UNIX 5 段 cron 校验，供 REPL 校验 `--schedule`；遗留 `*/N`/`@macro` 不算合法 cron。
- [x] 2.4 `TaskSchedulerExecutorTest`（4）：执行器设置后被调用 + 未设默认状态翻转保留 + 执行器抛异常回退 TODO + `isValidCron` 接受 5 段拒绝其余。

## 3. 验收

- [x] 3.1 `mvn -pl pig-agent-task -am test` 绿；`mvn -pl pig-agent-cli -am test` 绿（报告数目）。
- [x] 3.2 只动 owned 文件（`AgentCommand.java` / `TaskScheduler.java` + 测试）；`AgentBootstrap`/`PigAgentCli`/`AgentRunner`/`Task` 未触碰。
- [x] 3.3 `openspec validate automation-reachability --strict` 通过。

## 4. 集成（lead 后续，非本 seam 交付）

- [ ] 4.1 lead 在 `AgentBootstrap` 把真实 `TaskExecutor`（agent run）接到 `TaskScheduler.setTaskExecutor(...)`。
