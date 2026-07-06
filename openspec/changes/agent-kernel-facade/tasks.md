## 1. AgentKernel 门面 + KernelEvent（pig-agent-core）

- [ ] 1.1 单测 `KernelEventTest`：事件类型（会话/运行/报告）构造；`subscribeEvents` 热 Flux 多订阅、无订阅零成本。
- [ ] 1.2 定义 `KernelEvent`（sealed/枚举 + 载荷）+ 事件流实现（`Sinks.many().multicast()`）令 1.1 绿。
- [ ] 1.3 单测 `AgentKernelTest`：list/get/create/update/delete/use 委托 `AgentRegistry`/`AgentSpecRepository`；`chat` 返回 active 的 `Flux<Event>`；`useAgent` 未知 id 处理。
- [ ] 1.4 实现 `AgentKernel` 门面（内部编排 Registry/InstanceFactory/ModelManager/Repository），方法签名预留 `runNow`/调度开关（数字员工归档后接实现）令 1.3 绿。
- [ ] 1.5 `mvn -pl pig-agent-core -am test` 绿。

## 2. AgentRepl / ReplCommands 改为 adapter（pig-agent-cli）

- [ ] 2.1 `ReplContext` 暴露 `AgentKernel`（替代直连 Registry/Repository/InstanceFactory）。
- [ ] 2.2 `ReplCommands`（`/agent` 等）与 `AgentRepl.streamToAgent` 改为经 `AgentKernel` 调用。
- [ ] 2.3 **回归护栏**：`ReplCommandsTest` / `CommandDispatchTest` / 交互行为保持绿（行为等价）。
- [ ] 2.4 `PigAgentCli` 装配：构建 `AgentKernel` 并注入 REPL；内核内部装配不变。

## 3. Channel 归属（pig-agent-channel）

- [ ] 3.1 明确 `channelHolder`/channel agent 在门面下的归属（门面注册/可见，或明确独立轨道）；`ChannelAgentBridge` 相应接入。
- [ ] 3.2 相关单测/回归绿。

## 4. 全量校验 + 文档

- [ ] 4.1 全模块 `mvn test` BUILD SUCCESS，单 agent + 交互回归无变化。
- [ ] 4.2 文档：`CLAUDE.md` 架构章节补 `AgentKernel` 门面 + adapter 模式 + `KernelEvent`。
