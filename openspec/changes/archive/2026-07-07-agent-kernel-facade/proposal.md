## Why

`multi-agent-kernel`（阶段1）后，CLI（`AgentRepl` / `ReplCommands`）仍**直接**依赖 `AgentRegistry` / `AgentHolder` / `ModelManager` 等内核类。要让未来的入口（Web、更多渠道）共用同一内核而不是各自接线，需要一层**门面**：内核只暴露一个 `AgentKernel` API + 事件流，所有入口都是它的 adapter——「加入口 = 加 adapter，不动内核」。本变更（阶段3）落地设计文档的前端适配层，是承重墙工作的收尾。

> **依赖**：本变更依赖 `multi-agent-kernel` 已归档。在其归档前，本 proposal 仅锁定范围与依赖，`tasks.md` 待内核归档后再细化。范围**到「门面 + CLI adapter」为止**；Web 实现属独立的 `web-visualization` spec。

## What Changes

- **新增 `AgentKernel` 门面**：单一内核 API —— `listAgents / getAgent / createAgent / updateAgent / deleteAgent / useAgent / chat(agentId,msg)→Flux<Event> / runNow / enable|disableSchedule / subscribeEvents()→Flux<KernelEvent>`。前端只依赖它。
- **新增 `KernelEvent` 事件流**：会话/运行/报告事件以 reactor `Flux` 暴露（契合现有 AgentScope reactive 栈），供后续 Web 可视化消费。
- **`AgentRepl` 改造成 CLI adapter**：REPL 通过 `AgentKernel` 驱动而非直连内核类，**行为不变**（现有斜杠命令与交互流保持一致）。
- **Channel 归属明确化**（对应内核 spec Open Question）：`channelHolder` / channel agent 在门面下的归属定清楚。

## Capabilities

### New Capabilities
- `agent-kernel-facade`: 内核对外只经 `AgentKernel` 门面 + `KernelEvent` 事件流暴露能力；任意入口（CLI/Web/渠道/未来）作为 adapter 接入，新增入口无需改内核。

### Modified Capabilities
- `agent-management`: 内核对入口的暴露方式从「直连内核类」收敛为「经 `AgentKernel` 门面」；CLI 成为门面的第一个 adapter，交互行为不变。

## Impact

- **代码**：`pig-agent-core`（`AgentKernel` 门面 + `KernelEvent`）；`pig-agent-cli`（`AgentRepl`/`ReplCommands` 改经门面调用，行为不变）；`pig-agent-channel`（channel 走门面，归属明确）。
- **不改动**：内核内部结构（Registry/Instance/Factory）；单 agent 与交互行为。
- **前置**：`multi-agent-kernel` 归档。
- **解锁**：`web-visualization` spec 以本门面 API + 事件流为数据源。
