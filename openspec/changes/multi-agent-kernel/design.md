## Context

内核现状：全系统只有一个 `AgentHolder`（单 `volatile PigAgent`）。运行时切模型 = `AgentFactory.create(model)` 重建 agent 后 `holder.set(...)`；所有消费方（REPL、session、channel、compression）通过 `agentHolder.get()` 读当前 agent。这套「单槽 + 重建」无法承载「多个各有模型/工具/权限的 agent 并存并切换」。

两轮对抗评审（`docs/design/agent-management-design.md` 的「待优化点」表）已核实关键事实：`AgentHolder` 的**唯一写点是 `ModelManager`**，且它持**双 holder**（主 `holder` + `channelHolder`）；`AgentFactory`/`Toolkit`/`Hook`/`Memory` 均为**全局单例**；`Toolkit` 无"取子集"API；`AgentSpec.modelId` 的解析链（`ModelStore`→`ProtocolRegistry`→`Model`）真实可用。

## Goals / Non-Goals

**Goals:**
- 用 `AgentRegistry`（`Map<agentId, AgentInstance>`）替换单 `AgentHolder`，支持进程内多 agent。
- `AgentSpec`（声明式、可持久化、含 `modelId`）定义 agent；每 agent 各用各的模型/工具子集/权限档/人格。
- `/agent list|use|new|model` 管理 agent。
- **单 agent 老路径行为零变化**（回归基线守住）。

**Non-Goals:**
- 自主/定时运行、晨报、`commandAllowlist`、超时/中断 —— 属阶段 2（数字员工）。
- `AgentKernel` 门面、Web —— 属阶段 3 / 独立 spec。
- agent 间委派/编排 —— 可选阶段 4。
- 多 agent **并发运行**去 `.block()` —— 本阶段交互仍单活，不引入并发。

## Decisions

- **D1：`AgentHolder` 退化为「active 实例视图」，而非删除。** 只读 `agentHolder.get()` 的调用方（REPL/session/channel/compression/ReplCommands）保持不动 → 零变化风险最低。备选：全量把消费方改成读 `AgentRegistry` —— 爆炸半径大、回归风险高，否决。
- **D2：`ModelManager` 是真正要改的写点。** 它持双 holder 且是唯一 `set` 方；重定义为「切 **active 实例** 的模型」（主 holder 视图随 active 实例走；`channelHolder` 归属保持现状，channel agent 的多 agent 化留后续）。备选：让 registry 自己管模型切换、ModelManager 只建 `Model` —— 更干净但改动面更大，本阶段先走最小职责重定义。
- **D3：不复用 `AgentFactory`，改为 per-agent 构建。** 因其 toolkit/hooks/memory 是构造时固定的全局单例，无法满足 per-agent 差异。建实例时：按 `spec.toolNames` **新建 `Toolkit`** 逐个 `registration().tool().apply()` 注册白名单工具；**新建 `ToolPermissionHook`** 读 `spec.permissionMode`；**新建 `InMemoryMemory`**。备选：给 AgentFactory 加参数 —— 但它本质是"共享配置只换 model"，语义冲突，另立 per-agent 构建路径更清晰。
- **D4：模型解析容错。** `spec.modelId` → `ModelStore.findById`；`null` 或 miss → 回落默认模型（`ModelStore` 的 default）。仿现有 fault-tolerant 范式，不因坏引用崩溃。
- **D5：持久化用 YAML front-matter + 正文。** 复用已有 Jackson YAML 依赖；只借 `FileSystemTaskRepository` 的**容错骨架**（坏文件跳过/备份），**重写 parse** 使字段完整往返（现有实现 parseMarkdown 丢 description/schedule，是缺陷，不抄）。
- **D6：启动兜底建默认 agent。** 若 `workspace/agents/` 为空，用现有默认配置（AGENT.md sysPrompt + 默认模型 + 全量工具 + 全局权限）建一个 active 实例 = 今天的单 agent 等价物 → 保证零变化。

## Risks / Trade-offs

- [回归：单 agent 行为被破坏] → 先写单 agent 行为**回归基线**测试（切模型、发消息、session 切换、compression 仍工作），再动 AgentHolder；D1 的"视图"策略把改动面压到最小。
- [ModelManager 双 holder 语义打架：切"当前 agent 模型" vs 切"active 实例"] → D2 明确只改主 holder 随 active 实例走，channelHolder 保持现状；写单测覆盖"切 active 实例后主 holder 指向对"。
- [per-agent 新建 Toolkit 的工具实例状态] → 内置工具多为无状态（`@Tool` 方法），新建实例安全；MCP 工具的多实例注册本阶段不涉及（agent 用 toolNames 白名单，MCP 归属留后续）。
- [`toolNames` 引用了不存在的工具名] → 建实例时忽略未知名并记一行日志，不崩。
- [持久化字段往返丢失（重蹈 parseMarkdown 覆辙）] → 用结构化 YAML 序列化而非手写 parse；单测断言 write→read 往返等值。

## Migration Plan

- 纯新增 + 内部重构，无数据格式破坏。升级后 `workspace/agents/` 为空 → D6 兜底建默认 agent，用户无感。
- 回滚：本变更集中在 `pig-agent-core/agent` + `ModelManager` + `/agent` 命令；回退分支即可，无持久化迁移。

## Open Questions

- `channelHolder`（channel 专用 agent）在多 agent 下归属哪个实例？→ 本阶段保持现状，留阶段 3 门面 spec 细化。
- 非 active 实例的会话何时持久化？→ 本阶段交互单活，仅 active 实例走现有 session 流；registry 里其余实例无独立会话（切过去时才建/载）。留阶段 2/3 深化（对应设计文档 Open Q2）。
