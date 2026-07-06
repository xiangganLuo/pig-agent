## 1. 承重墙勘察 + 回归基线（卡点，先做）

- [x] 1.1 摸清 `AgentHolder` 全部读/写点，产出清单：只读方（`AgentRepl`/`ReplContext`/`SessionManager`/`ChannelAgentBridge`/`CompressionService`/`ReplCommands`）与唯一写方（`ModelManager` 双 holder：主 `holder` + `channelHolder`）。写进本变更 design 的 Decisions 已覆盖，核对无遗漏。
- [ ] 1.2 写**单 agent 行为回归基线**单测（动 AgentHolder 前必须先绿）：默认 agent 能发消息、切模型后 `agentHolder.get()` 指向新 agent、切会话、上下文压缩仍工作。
- [x] 1.3 `mvn -pl pig-agent-core -am test` 基线绿（卡点：不绿不进第 2 组）。

## 2. AgentSpec + 持久化（pig-agent-core / pig-agent-workspace）

- [x] 2.1 单测 `AgentSpecTest`：`withXxx` 不改原对象；缺省字段默认值（空 toolNames=全量、空 modelId=默认模型）。
- [x] 2.2 实现 `io.pigagent.core.agent.AgentSpec`（record，字段 id/name/sysPrompt/toolNames/permissionMode/modelId/maxIters + `withXxx`）令 2.1 绿。
- [x] 2.3 单测 `AgentSpecRepositoryTest`：write→read 字段等值往返；坏文件跳过/备份不崩列举。
- [x] 2.4 实现 `AgentSpecRepository`（`workspace/agents/{id}.md` = YAML front-matter + 正文；Jackson YAML 序列化；容错骨架仿 `FileSystemTaskRepository` 但**重写 parse 不丢字段**）令 2.3 绿；`WorkspaceManager` 加 `agents/` 目录。
- [x] 2.5 `mvn -pl pig-agent-core -am test` 绿。

## 3. AgentInstance + AgentRegistry + per-agent 构建（pig-agent-core）

- [x] 3.1 单测 `AgentRegistryTest`：注册/切换 active；`AgentHolder` 视图随 active 走；未知 agentId 处理。
- [x] 3.2 实现 `AgentInstance`（agentId + AgentSpec + PigAgent + 生命周期）与 `AgentRegistry`（`Map<agentId, AgentInstance>` + active）令 3.1 绿。
- [x] 3.3 单测 `AgentInstanceFactoryTest`：函数式 provider 注入；per-agent 模型经 `getModel()` 验证。（具体「toolNames 子集/未知名忽略」「modelId 悬空回落」的实现级测试随 G4/G5 的具体 provider 落地。）
- [x] 3.4 实现 per-agent 构建路径 `AgentInstanceFactory`（不复用 `AgentFactory`；每 agent 新建 Toolkit/hook/memory；模型/工具/hook 经函数式 provider 注入，具体 resolver 在 G4/G5）令 3.3 绿。
- [x] 3.5 `AgentHolder` 保持不变，由 `AgentRegistry` 切 active 时 `holder.set(active.agent())` 实现「active 视图」（design D1）；`mvn -pl pig-agent-core -am test` 绿。

## 4. ModelManager 职责重定义（pig-agent-model）

- [x] 4.1 单测 `ModelManagerResolveTest`：`modelId` present→本身、null→默认、悬空→回落默认、无默认→空。（「切 active/切某 agent 模型」的开关语义随 G5 `/agent model` 命令一并测。）
- [x] 4.2 `ModelManager` 加 `resolveStoredModel`/`modelFor`（per-agent 模型解析 + 容错回落，作为 CLI 里 `AgentInstanceFactory.ModelResolver` 的实现源）；`ensureModel` 维持会话流不变；「切 active 实例模型」由 G5 `/agent model` 重建该实例落地（design D2）。
- [x] 4.3 `mvn -pl pig-agent-model -am test` 绿，无回归（全依赖树 5 模块通过）。

## 5. /agent 命令 + 装配（pig-agent-cli）

- [ ] 5.1 单测 `AgentCommandTest`（或 ReplCommands 层）：`/agent list` 列出、`/agent use <id>` 切换、`/agent new` 建、`/agent model <id> <modelId>` 改模型。
- [ ] 5.2 实现 `/agent list|use|new|model` 命令令 5.1 绿；`ReplContext` 暴露 `AgentRegistry`；`/help` 增补。
- [ ] 5.3 `PigAgentCli` 装配：建 `AgentRegistry`，启动从 `workspace/agents/` 载入；**空目录兜底建默认 active agent**（等价今天单 agent）；`ToolPermissionHook` 支持从 spec 读 permissionMode。
- [ ] 5.4 `ToolPermissionHook` 支持 per-agent 权限档（构造时接受 mode 来源，默认仍读全局 config 保持兼容）；相关单测。

## 6. 全量校验 + 文档

- [ ] 6.1 全模块 `mvn test` BUILD SUCCESS，全部单测通过、单 agent 回归基线绿、无回归。
- [ ] 6.2 端到端 `*IT`（外环）：定义两个不同模型的 agent、CLI 间切换、各用各的模型对话（判据用例）。
- [ ] 6.3 文档：`CLAUDE.md` 架构章节 `AgentHolder`→`AgentRegistry` 表述；`agent-management-design.md` 勾掉阶段 1 相关待优化点。
