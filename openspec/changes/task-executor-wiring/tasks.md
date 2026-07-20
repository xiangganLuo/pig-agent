## 1. Task 结果字段 + 仓库往返（task-execution）

- [ ] 1.1 测试先行：`TaskTest` 断言 `withResult`/`withLastRunAt` 拷贝且不改原对象、`withStatus` 保留 result/lastRunAt；`FileSystemTaskRepositoryTest` 断言 result（多行）+ lastRunAt 往返一致、旧文件（无 Result/LastRun 行）读为 null 且 schedule/描述往返不受影响
- [ ] 1.2 `Task` record 增 `String result` + `Instant lastRunAt`（规范 10 参构造）；保留 8 参兼容构造（result/lastRunAt=null）；`withStatus`/`withSchedule` 保留新字段；增 `withResult`/`withLastRunAt`
- [ ] 1.3 `FileSystemTaskRepository`：`toMarkdown` 在 Created 与 Updated 之间写 `LastRun`/`Result` 行（Result 单行转义、按 MAX_RESULT_CHARS 截断）；`parseMarkdown` 读回（`extractField` + 反转义 + `parseNullableInstant`）；`extractDescription` 不改
- [ ] 1.4 `TaskManager.recordRun(id, result)` 容错落 result+lastRunAt=now（任务不存在则 no-op）；补测
- [ ] 1.5 `mvn -pl pig-agent-task -am test` 绿；勾选本组

## 2. AgentRunner ad-hoc mandate 入口（task-execution）

- [ ] 2.1 测试先行：`AgentRunnerTest` —— `runMandate` 用 FakeModel(SUCCESS) 返回 SUCCESS+body、不触发 reportWriter/specUpdater；FakeModel(FAILURE) 返回 FAILURE+note 不抛
- [ ] 2.2 `AgentRunner.runMandate(String id, String mandate)` + `runMandate(id, mandate, timeoutSeconds)`：合成瞬态非自主 spec、复用 running 重入保护 + builder.build + execute；`DEFAULT_MANDATE_TIMEOUT_SECONDS` 常量
- [ ] 2.3 `mvn -pl pig-agent-core -am test` 绿；勾选本组

## 3. 配置门 tasks.execute（task-execution）

- [ ] 3.1 测试先行：`PigAgentConfigTest`/`ConfigurationManagerTest` 断言 `tasks.execute` 默认 false、可被 yaml 覆盖为 true
- [ ] 3.2 `PigAgentConfig` 增 `TasksConfig`（`execute` 默认 false）+ `tasks` 字段 + getter/setter
- [ ] 3.3 `mvn -pl pig-agent-config -am test` 绿；勾选本组

## 4. AgentBootstrap 接线（task-execution + session-snapshot-reset）

- [ ] 4.1 测试先行：`AgentBootstrapTaskExecutorTest`（pig-agent-cli）—— `buildTaskExecutor(realAgentRunner(fakeBuilder), realTaskManager)` 得到的 `Consumer<Task>` 跑一个已存任务后，仓库中该任务 result 非空且含 outcome 摘要
- [ ] 4.2 `AgentBootstrap.buildTaskExecutor(AgentRunner, TaskManager)` 静态助手 + `taskOutcomeSummary(AgentReport)`；`build()` 中 `if (config.getTasks().isExecute()) taskScheduler.setTaskExecutor(buildTaskExecutor(agentRunner, taskManager))`
- [ ] 4.3 `SessionManager` 改 7 参构造，传 `compressionRef` 转发的 snapshot 重置钩子；`CompressionService` 建好后 `compressionRef.set(...)`
- [ ] 4.4 `/tasks`（`ReplCommands.TasksCommand`）：任务有 result 时列表追加一行摘要（截断）
- [ ] 4.5 `mvn -pl pig-agent-cli -am test` 绿（前台，报数）；勾选本组

## 5. 校验与归档

- [ ] 5.1 `openspec validate task-executor-wiring --strict` 通过
- [ ] 5.2 复查无回归：默认 `tasks.execute=false` → 仅提醒；8 参 Task 构造保留；SessionManager 6 参测试不变
