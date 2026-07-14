## 1. maxIters 接入全部 agent（agent-run-limit）

- [ ] 1.1 测试先行：`PigAgentTest`（或 `AgentFactoryTest`）断言 `PigAgent.Builder.maxIters(n>0)` 使底层 `ReActAgent` 以该上限构建、`maxIters<=0` 时不设（用可观察手段：反射/行为，或最简单地断言 builder 传参路径不抛且构建成功）
- [ ] 1.2 `PigAgent.Builder` 增 `int maxIters`（默认 0）+ `maxIters(int)` 方法；`build()` 中 `if (maxIters > 0) reactBuilder.maxIters(maxIters)`
- [ ] 1.3 `AgentFactory` 增带 `int maxIters` 的构造重载；旧 3 个重载委托为 `maxIters=0`（向后兼容）；`create(model)` 传入 `.maxIters(maxIters)`
- [ ] 1.4 `AgentInstanceFactory.create` 传 `.maxIters(spec.maxIters())`；补测断言从 spec 取值
- [ ] 1.5 wiring：`AgentBootstrap`/`ModelManager` 建 `AgentFactory` 时传 `config.getAgent().getMaxIters()`；`AgentRunner` 的 AgentBuilder（wiring 层）建自主 agent 时经 `PigAgent.builder().maxIters(spec.maxIters())`
- [ ] 1.6 默认值（用户拍板）：`PigAgentConfig.AgentConfig.maxIters` 默认由 10 **改为 40**（交互/渠道够用）；`AgentSpec.DEFAULT_MAX_ITERS` **保持 10**（自主保守）。补测断言两默认值
- [ ] 1.7 `mvn -pl pig-agent-core,pig-agent-config -am test` 绿；勾选本组

## 2. 任务仓库容错（task-persistence）

- [ ] 2.1 测试先行：`FileSystemTaskRepositoryTest` —— 缺 Status / 非法状态枚举 / 截断内容的 `.md`，`findAll` 跳过 + 不抛、返回其余有效任务；`findById` 对坏文件返回 empty（AAA、@TempDir）
- [ ] 2.2 `FileSystemTaskRepository.parseMarkdown` 捕获所有异常（含 RuntimeException）→ warn + 返回 null/Optional.empty（引入 SLF4J logger，遵循 logging-framework）
- [ ] 2.3 `findAll` `map(parseMarkdown).filter(Objects::nonNull)`；`findById` 同样容错；移除 `Task.create("Error",...)` 占位塞入
- [ ] 2.4 `mvn -pl pig-agent-task -am test` 绿；勾选本组

## 3. 任务持久化无损（task-persistence）

- [ ] 3.1 测试先行：`FileSystemTaskRepositoryTest` —— save→findById 往返：CRON(cron 表达式一致)、DELAYED(秒数一致)、ONCE；description 与 createdAt/updatedAt 一致；旧格式（Schedule 仅类型）读为 ONCE 不崩
- [ ] 3.2 `toMarkdown` 写全 Schedule 行：`ONCE` / `CRON:<expr>` / `DELAYED:<seconds>`（单行可逆）
- [ ] 3.3 `parseMarkdown` 解析 Schedule 行重建 `TaskSchedule`（cron/delayed/once），解析 `Created`/`Updated`（`Instant.parse`，失败回退 now）与 description（metadata 块后正文）；旧格式容错降级 ONCE
- [ ] 3.4 验证 `TaskScheduler.scheduleAll` 对回读的 CRON/DELAYED 任务正确重排（可在 `TaskSchedulerTest` 或仓库测试层面断言 schedule().type() != ONCE）
- [ ] 3.5 `mvn -pl pig-agent-task -am test` 绿；勾选本组

## 4. 配置健壮性（config-resilience）

- [ ] 4.1 测试先行：`ConfigurationManagerTest` 或 `PigAgentConfigTest` —— 含未知顶层字段 + 有效 permissions/compression 的 yaml 加载后：未知字段被忽略、既有设置正确绑定（不回退默认）
- [ ] 4.2 `PigAgentConfig` 顶层加 `@JsonIgnoreProperties(ignoreUnknown = true)`（嵌套配置类按需一并加）
- [ ] 4.3 `mvn -pl pig-agent-config -am test` 绿；勾选本组

## 5. 集成与回归

- [ ] 5.1 `mvn -q test` 全量绿（单线程；不回归既有单测。注：`AgentKernelInterruptTest` 在 `-T 1C` 并行下偶发时序 flaky，与本变更无关）
- [ ] 5.2 `mvn -pl pig-agent-cli -am compile` 编译通过
- [ ] 5.3 复查无回归：maxIters 仅在 >0 施加；任务旧文件容错；config 仅忽略未知字段

## 6. 文档

- [ ] 6.1 README + `CLAUDE.md`：补"maxIters 现约束全部 agent（默认 10，可调 `agent.max-iters`）"、任务持久化无损/容错、配置忽略未知字段
- [ ] 6.2 （可选）真模型 IT 冒烟：自主 agent 到 maxIters 上限即停并产报告（归 `/ls:itest`）
