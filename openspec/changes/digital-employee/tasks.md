## 1. Spike（卡点，先做）：ReActAgent 可中断性

- [ ] 1.1 spike：验证能否给一次自主 `reactAgent` 运行加**真中断**（后台线程 + `Future.cancel`/`Mono.timeout` 是否真停底层调用）。产出结论。
- [ ] 1.2 依结论定超时策略：若不可真中断 → `maxIters` 软上限 + 后台线程尽力而为墙钟超时（超时标记+产报告，不重入）。写进 design D4 已覆盖，spike 核实。

## 2. 调度通用化 + cron 库（pig-agent-task）

- [ ] 2.1 单测：通用 `schedule(id, TaskSchedule, Runnable)` 按 ONCE/DELAYED/CRON 触发；真 cron（cron-utils）解析「每晚定点」。
- [ ] 2.2 把 `TaskScheduler` 从 `Task` 生命周期解耦出通用调度方法；引入 cron-utils；保留现有 `Task` 调度路径（回归 `TaskTest`）。
- [ ] 2.3 `mvn -pl pig-agent-task -am test` 绿，无回归。

## 3. AgentSpec 自主字段扩展（pig-agent-core，MODIFIED agent-management）

- [ ] 3.1 单测：`AgentSpec` 新增 `mandate`/`schedule`/`commandAllowlist`/`timeoutSeconds`/`lastRunAt` 的 `withXxx` + 默认值；持久化往返不丢新字段。
- [ ] 3.2 扩展 `AgentSpec` record + `AgentSpecRepository`（front-matter 加新字段）令 3.1 绿。
- [ ] 3.3 `mvn -pl pig-agent-core -am test` 绿。

## 4. AgentRunner + 无人值守安全 + 晨报（pig-agent-core / pig-agent-tools）

- [ ] 4.1 单测 `AgentRunnerTest`（假模型）：隔离一次性 agent 跑 mandate；防重入（上轮未完跳过）；成功/失败/超时都产报告；回写 `lastRunAt`。
- [ ] 4.2 实现 `AgentRunner`（隔离一次性受限 agent，同 `CompressionService.summarize` 范式）+ `maxIters`/尽力而为超时（依 task 1 结论）令 4.1 绿。
- [ ] 4.3 单测：`DeniedActionRecorder` 收集被拦动作；`commandAllowlist`（复用 allowlist 首-token）放行安全命令。
- [ ] 4.4 无人值守权限档（无 confirmer fail-closed）+ `DeniedActionRecorder` + 晨报三段式（`workspace/reports/{date}/{id}.md`）令 4.3 绿；`WorkspaceManager` 加 `reports/`。

## 5. 调度接入 + /agent run|report 命令（pig-agent-cli）

- [ ] 5.1 单测：`/agent run <id>` 手动触发一次；`/agent report` 列出晨报。
- [ ] 5.2 实现 `/agent run|report` 命令 + `/help` 增补令 5.1 绿。
- [ ] 5.3 `PigAgentCli` 装配：启动把带 schedule 的 agent 注册进通用调度器触发 `AgentRunner`。

## 6. 样例 + 集成测试 + 文档

- [ ] 6.1 样例 `agents/nightwatch.md`（守夜人：`commandAllowlist: [mvn, git]`）。
- [ ] 6.2 端到端**离线确定性**测试：假模型 + DELAYED 触发 → 晨报落盘，断言「我做了」「等你决定」两段；危险动作被 defer。
- [ ] 6.3 全模块 `mvn test` BUILD SUCCESS，无回归。
- [ ] 6.4 文档：`CLAUDE.md`（数字员工/自主运行/晨报）+ `README`（数字员工配置示例）。
