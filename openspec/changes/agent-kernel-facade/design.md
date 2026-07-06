## Context

`multi-agent-kernel`（已归档）后，CLI（`AgentRepl`/`ReplCommands`）与渠道仍**直接**依赖内核类（`AgentRegistry`/`AgentHolder`/`ModelManager`/`AgentInstanceFactory`）。要让未来入口（Web、更多渠道）共用同一内核而非各自接线，需要一层**门面**：内核只经 `AgentKernel` API + `KernelEvent` 事件流对外，入口都是它的 adapter。本阶段是承重墙工作的收尾，**到「门面 + CLI adapter」为止**（Web 属独立 spec）。

## Goals / Non-Goals

**Goals:**
- `AgentKernel` 门面：单一内核 API（列/建/改/删/切 agent、`chat→Flux<Event>`、`runNow`、调度开关、`subscribeEvents→Flux<KernelEvent>`）。
- `KernelEvent` 事件流（会话/运行/报告），reactor `Flux`。
- `AgentRepl`/`ReplCommands` 改为经门面驱动，**交互行为不变**。
- 明确 channel（`channelHolder`/channel agent）在门面下的归属。

**Non-Goals:**
- Web server / 前端（独立 `web-visualization` spec，以本门面为数据源）。
- 改内核内部结构（Registry/Instance/Factory 不动）。

## Decisions

- **D1：`AgentKernel` 为唯一对外面。** 前端 MUST 只依赖 `AgentKernel`，不再直连内核类。门面内部持有并编排 `AgentRegistry`/`AgentInstanceFactory`/`ModelManager`/`AgentSpecRepository`/`AgentRunner`。
- **D2：`KernelEvent` 用 `Flux` 暴露。** 会话事件、运行事件、报告事件统一为 `KernelEvent`，`subscribeEvents()` 返回热 `Flux`（多订阅者：CLI 可忽略、Web 消费）。契合 AgentScope reactive 栈。
- **D3：`AgentRepl` 改造成 adapter，行为不变。** REPL 命令改为调门面方法；交互流 `chat` 走门面。**回归护栏**：现有 `ReplCommandsTest` / `CommandDispatchTest` / 交互行为保持绿。
- **D4：channel 归属明确。** `channelHolder`（渠道专用 agent）在门面下建模为「门面管理的一个渠道 agent」或明确其为门面外的独立轨道；本阶段给出定论并让 `ChannelAgentBridge` 经门面（或明确豁免）。
- **D5：不改内核内部。** 门面是**新增的外层**；Registry/Instance/Factory/持久化不动，降低回归面。

## Risks / Trade-offs

- [门面改造触碰 REPL 全部命令 → 回归面大] → D3：一次一个命令迁到门面，全程 `ReplCommandsTest`/`CommandDispatchTest` 守绿；门面方法与现有内核调用一一对应，行为等价。
- [热 `Flux` 事件流的背压/多订阅] → 用 `Sinks.many().multicast()` 或等价；CLI 不订阅时零成本；Web spec 再压测。
- [channel 归属定错] → D4 本阶段先给最小定论（channel 仍走独立 holder，但经门面注册/可见），Web spec 不依赖其细节。

## Migration Plan

- 纯新增外层门面 + 把 CLI/渠道的直连改为经门面；内核内部不变。交互行为等价，无数据变更。
- 回滚：门面是新增层，回退分支即可；内核不受影响。

## Open Questions

- 门面方法粒度是否要一步到位覆盖 runNow/调度开关（数字员工相关）？→ 覆盖，签名预留；数字员工归档后其实现自然接入。
- `KernelEvent` 的事件类型枚举先覆盖哪些？→ 会话开始/结束、运行开始/结束、报告产出；够 Web MVP 用，后续可加。
