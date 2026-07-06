## Why

Pig Agent 的内核目前是**单 agent**：全系统只有一个 `AgentHolder`（单 `volatile PigAgent` 引用），换模型靠"重建后塞回 holder"。要支持「养一批各有模型/工具/权限/人格的 agent，并在它们之间切换」，第一步必须把这唯一的 agent 槽换成一个**注册表**。本变更拆承重墙的第一块，为后续「数字员工（自主定时运行）」与「前端适配层 + Web」打地基。依据设计 `docs/design/agent-management-design.md`（Status: APPROVED，经两轮对抗评审）。

## What Changes

- **新增 `AgentSpec`（不可变 record）**：声明式 agent 定义——`id / name / sysPrompt / toolNames / permissionMode / modelId / maxIters`（本阶段不含自主运行相关的 `mandate / schedule / commandAllowlist / timeoutSeconds`，留阶段 2）。可持久化、可 `withXxx` 拷贝。
- **新增 `workspace/agents/{id}.md` 持久化**：YAML front-matter（Jackson YAML 序列化字段）+ Markdown 正文（= sysPrompt）。仓库**容错**（坏文件跳过/备份、不崩列表），但**不复刻** `FileSystemTaskRepository.parseMarkdown` 丢字段的缺陷——字段必须完整往返。
- **新增 `AgentInstance`（运行时）**：`agentId + 源 AgentSpec + 内部 PigAgent + 生命周期状态`，泛化今天的 `AgentHolder.get()`。
- **新增 `AgentRegistry`（管理层）替换单 `AgentHolder`**：持有 `Map<agentId, AgentInstance>`，交互「当前 agent」= active 实例。`AgentHolder` **退化为「active 实例视图」**，现有只读 `agentHolder.get()` 的调用方（`AgentRepl` / `ReplContext` / `SessionManager` / `ChannelAgentBridge` / `CompressionService` / `ReplCommands`）**行为零变化**。
- **每 agent 各选模型**：`AgentSpec.modelId` 指向 `StoredModel.id`，经现有 `ModelStore` + `ProtocolRegistry` 建 `Model`（与 onboarding / `/model` 同一条解析链）；`null` 或指向已删除的 `StoredModel` 时**容错回落默认模型**。
- **per-agent 构建**：不直接复用现有 `AgentFactory`（其 toolkit/hooks/memory 是构造时固定的全局单例）——建实例时**每 agent 新建**受限 `Toolkit`（按 `toolNames` 重新注册工具实例）、独立 hooks（`ToolPermissionHook` 从 `spec.permissionMode` 读而非全局 config）、独立 `InMemoryMemory`。
- **重定义 `ModelManager` 职责**：它是唯一**写** `AgentHolder`（主 + `channelHolder` 双 holder）的地方；改为「切 **active 实例**的模型」，模型构建职责保留。
- **新增 `/agent list | use | new | model` 命令**：列出/切换/新建 agent、改某 agent 的模型。

无 **BREAKING**：单 agent 老路径行为不变；启动时若无 agent 定义，自动以现有默认配置建一个 active 实例（等价今天的单 agent）。

## Capabilities

### New Capabilities
- `agent-management`: 内核以进程内注册表管理**多个** agent 实例，每个由声明式 `AgentSpec`（含各自模型、工具子集、权限档、人格）定义并持久化；用户可列出/新建/切换 agent，交互「当前 agent」为其中的 active 实例。单 agent 为其退化特例。

### Modified Capabilities
<!-- 无既有 spec 的 REQUIREMENT 变更：本变更是内核新增能力，不改 model-protocol / mcp-management / tool-permissions 的既定行为（tool-permissions 的 per-agent 读取属实现细节，不改其 spec 级契约）。 -->

## Impact

- **代码**：`pig-agent-core`（新增 `agent/AgentSpec`、`agent/AgentInstance`、`agent/AgentRegistry`；`AgentHolder` 改为 active 视图；per-agent 构建逻辑）；`pig-agent-model`（`ModelManager` 职责重定义，双 holder→active 实例）；`pig-agent-session`（`SessionManager` 经 registry 读 active，保持单活语义）；`pig-agent-cli`（`/agent` 命令、`PigAgentCli` 装配 registry + 启动建默认 agent）；`pig-agent-workspace`（`agents/` 目录）；`pig-agent-tools`（`ToolPermissionHook` 支持从 spec 读 permissionMode）。
- **配置/数据**：新增 `workspace/agents/` 目录；无既有数据格式变更。
- **不改动**：`ModelStore` / `ProtocolRegistry` / `StoredModel`（复用其解析链）；`Channel` 双 holder 的存在（仅明确其归属，channel agent 归属留后续门面 spec 细化）；`AgentScope` 依赖版本。
- **测试**：新增 `AgentSpec` / `AgentRegistry` / agents 持久化单测；单 agent 行为**回归基线**（守住"零变化"）；`ModelManager` 切模型单测更新。
- **文档**：`CLAUDE.md` 架构章节（AgentHolder→AgentRegistry）；`README` 后续同步。
