## 1. AgentKernel 门面 + KernelEvent（pig-agent-core）

- [x] 1.1 单测 `KernelEventTest`：事件类型（会话/运行/报告）构造；`subscribeEvents` 热 Flux 多订阅、无订阅零成本。
- [x] 1.2 定义 `KernelEvent`（sealed/枚举 + 载荷）+ 事件流实现（`Sinks.many().multicast()`）令 1.1 绿。
- [x] 1.3 单测 `AgentKernelTest`：list/get/create/update/delete/use 委托 `AgentRegistry`/`AgentSpecRepository`；`chat` 返回 active 的 `Flux<Event>`；`useAgent` 未知 id 处理。
- [x] 1.4 实现 `AgentKernel` 门面（内部编排 Registry/InstanceFactory/ModelManager/Repository），方法签名预留 `runNow`/调度开关（数字员工归档后接实现）令 1.3 绿。
- [x] 1.5 `mvn -pl pig-agent-core -am test` 绿。

## 2. AgentRepl / ReplCommands 改为 adapter（pig-agent-cli）

- [x] 2.1 `ReplContext` 暴露 `AgentKernel`（替代直连 Registry/Repository/InstanceFactory）。
- [x] 2.2 `ReplCommands`（`/agent` 等）与 `AgentRepl.streamToAgent` 改为经 `AgentKernel` 调用。
- [x] 2.3 **回归护栏**：`ReplCommandsTest` / `CommandDispatchTest` / 交互行为保持绿（行为等价）。
- [x] 2.4 `PigAgentCli` 装配：构建 `AgentKernel` 并注入 REPL；内核内部装配不变。

## 3. Channel 归属（pig-agent-channel）

- [x] 3.1 定论 **D4：渠道 = 门面可见的独立轨道**——渠道 agent 保留自己的 holder + channel-mode 权限轨道，不进入 `useAgent` 可切换集合；`ChannelAgentBridge` 经 `AgentKernel.noteChannelChat` 让渠道回合对门面事件流可见（可选注入，未注入则纯独立轨道）。
- [x] 3.2 单测 `AgentKernelTest#noteChannelChat_isFacadeVisible_asChannelTaggedEvent` + 渠道桥双构造器回归绿。

## 4. 全量校验 + 文档

- [x] 4.1 全模块 `mvn test` BUILD SUCCESS，单 agent + 交互回归无变化。
- [x] 4.2 文档：`CLAUDE.md` 架构章节补 `AgentKernel` 门面 + adapter 模式 + `KernelEvent` + 渠道独立轨道（D4）。
