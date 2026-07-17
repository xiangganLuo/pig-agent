<div align="center">

<img src="assets/logo.svg" alt="Pig Agent Logo" width="150"/>

# Pig Agent

**一个基于 AgentScope Java 2.0 构建的、24 小时常驻的终端个人助理框架**

*记得住你 · 越用越懂你 · 会自己沉淀经验 · 底层 harness 外包给 2.0 原生*

</div>

## 项目背景

Pig Agent 的北极星是：**站在 Claude Code 之上的「超级助手」——有自己的大脑（多协议模型层），编排专业 agent 去干活，通过多种渠道随时触达人，自己专注于「解决人的事情」。** 它不是「按需唤起的会话工具」，而是持续在线、无人值守替人办事的数字员工。

为此，Pig Agent 把复杂的、别人已做到极致的基础设施**交出去**，只把力气花在自己的差异化上：

- **底层 harness**（权限 / 记忆 / 压缩 / 中断 / 沙箱 / 事件 / session / 子 agent / Plan Mode）→ **AgentScope Java 2.0 原生**。
- **模型能力** → Pig Agent **自己的大脑**：5 套协议标准的多协议模型层，供应商无关、运行时可切换。
- **专注差异化** → 编排 + 人机交互（多渠道触达）+ 事务自动化 + **个人助理记忆/画像/自主技能**。

每个 agent 都封装 AgentScope 的 **`HarnessAgent`** 载体（内部委派给 `ReActAgent`），并通过 picocli + JLine3 的 CC 风格 REPL 暴露给用户。

### 技术栈

| 组件         | 技术选型                                                      |
| ------------ | ------------------------------------------------------------- |
| Agent 框架   | AgentScope Java 2.0.0（`agentscope-core` + `agentscope-harness` + `agentscope-extensions-model-*`） |
| Agent 载体   | `HarnessAgent`（委派 `ReActAgent`）                           |
| 终端 REPL    | JLine3 3.28.0 + picocli 4.7.6 + Jansi                        |
| 配置管理     | Jackson YAML 2.18.3                                           |
| MCP 协议     | 动态增删改查（stdio / SSE / streamable-http）                |
| 日志         | SLF4J + Logback                                              |
| 构建工具     | Maven（Java 17）                                             |

> **版本说明**：仓库已全量迁移到 AgentScope 2.0——不再依赖 1.x 的 `io.agentscope:agentscope` 单包，也不再有已废弃的 `io.agentscope.core.hook.*`。旧的 1.0.12（v1）线归档在分支 **`v1-stable-20260716`** + tag **`v1-final-20260716`**（另有 `v1.0.12-final`），需要 2.0 之前的构建可从那里取。

## 核心架构

```
┌──────────────────────────────────────────────────────────────┐
│              前端适配器（Add a frontend = add an adapter）      │
│   ┌─────────────────────┐        ┌──────────────────────┐    │
│   │  CC 风格 REPL (CLI)  │        │  Web 控制台 (可选)    │    │
│   └─────────────────────┘        └──────────────────────┘    │
├──────────────────────────────────────────────────────────────┤
│                   AgentKernel 门面 (facade)                    │
│   listAgents / useAgent / chat / interruptCurrent / events    │
├──────────────────────────────────────────────────────────────┤
│              PigAgent  →  HarnessAgent  →  ReActAgent          │
│   ┌────────────┐  ┌────────────┐  ┌────────────────────────┐ │
│   │ Middleware │  │ 原生权限   │  │  原生两层记忆 + 用户画像 │ │
│   │ (中间件链) │  │PermissionE.│  │  MEMORY.md / USER.md    │ │
│   └────────────┘  └────────────┘  └────────────────────────┘ │
│   原生：状态/session · 重试/中断 · 子 agent · Plan Mode · 淘汰 │
├──────────────────────────────────────────────────────────────┤
│                          Toolkit                              │
│  文件 · 命令 · 任务 · 清单 · 技能 · 记忆 · 画像 · 外呼 · MCP  │
│  按需工具 (tool_search) · 计算/网络插件 (plugin-builtin)      │
├──────────────────────────────────────────────────────────────┤
│   多协议大脑 · Session · Task 调度 · MCP · Channel · Workspace │
└──────────────────────────────────────────────────────────────┘
```

**请求处理流程**（一次对话回合）：

```
用户输入 → JLine3 REPL
    ↓ 构建 Msg(USER)
AgentKernel.chat(activeId, msg, sessionId)   ← 注册可中断 TurnHandle
    ↓
PigAgent → HarnessAgent → ReActAgent 推理循环
    ├── Middleware 链：onSystemPrompt / onReasoning / onActing / onModelCall
    ├── LLM 调用（原生重试 429/5xx）
    ├── 原生 PermissionEngine 逐工具放行 / DENY / HITL 确认
    ├── 工具执行（@Tool 方法，多道正交护栏）
    └── 大结果淘汰 (tool-result eviction)
    ↓ Flux<AgentEvent> 流式富渲染（TextBlockDelta / ToolResult* / RequireUserConfirm）
终端增量出字 + markdown 高亮；Ctrl-C 真中断
```

## 个人助理核心（差异化 headline）

Pig Agent 的灵魂是「记得住你、越用越懂你、会自己沉淀经验」。这一层建在 2.0 原生记忆地基之上，叠加 Pig 的差异化能力。

### 跨会话原生两层记忆（`pa-memory-native`）

采用 AgentScope 2.0 的**原生两层记忆**，取代此前自建、有跨会话缺陷的记忆栈：

- **两层落盘（工作区级，天然跨会话）**：日志层 `memory/YYYY-MM-DD.md`（每回合 flush，LLM 抽取长期事实，**无字数过滤**）→ 固化层 `MEMORY.md`（后台节流 consolidation 合并去重、整文件重写）。
- **注入 system prompt**：Pig 自有的 `NativeMemoryContextMiddleware`（`onSystemPrompt`）把 `MEMORY.md` 注入系统提示词——会话内稳定、前缀缓存友好。
- **记忆工具**：`memory_search` / `memory_get` / `memory_save` / `session_search`（+ `session_list` / `session_history`），已在 `ToolRiskClassifier` 分级（检索只读、`memory_save` 写）。
- **廉价模型**：flush / consolidation 可跑在轻量模型（`memory.model-id`，如 Doubao lite），省成本。
- **开关**：`/memory on|off` 翻转 `memory-enabled` 并重建 agent；一次性迁移旧 `context/memory.md` 到 `MEMORY.md`（幂等、容错）。

> 这修好了一个真 bug：会话 A 说「我叫罗湘赣」→ `/session new` → 会话 B 记不住。因为固化层是工作区级、跨会话，durable 事实不再随会话丢失。

### 用户画像 `USER.md`（`user-profile`）

一份**结构化的用户画像**（工作区根 `USER.md`）——身份（怎么称呼你）、长期偏好（语言 / 输出风格 / 技术取向）、工作方式——与 `MEMORY.md`（通用事实台账）分离：

- 由 `UserProfileContextMiddleware` 注入 system prompt（在 `MEMORY.md` 之前），有界、凭据脱敏、字节稳定。
- 两条维护路径：`updateProfile` `@Tool`（确定性 set/merge 一个字段，agent 立即落用户明确表达的偏好）；可选后台 consolidation（默认关，用廉价模型从 `MEMORY.md` 蒸馏画像）。
- 工作区级 → 会话 A 设的字段在会话 B 也生效。

### 自主沉淀技能（`autonomous-skills`，默认关）

让 Pig「越用越会」：把解过的任务/工作流蒸馏成可复用 `SKILL.md`——但**永不直接安装**：

- 两个 `@Tool`（`proposeSkill` / `skillManage`，分类 WRITE）把草稿写入**暂存区** `workspace/skills/.pending/<name>/`（对读路径不可见）。
- **默认人工门**（OpenClaw 模型）：`/skill review|approve|reject` 人工审后才提升到 `workspace/skills/<name>/`，提升即被 `WorkspaceSkillSource` 实时发现（无需重启）。
- 提升前跑内容安全扫描（`SkillSecurity` 路径 + `SkillLimits` 大小 + `CredentialSanitizer` 凭据拒绝 + 结构校验）+ 去重。
- **fail-closed**：agent 工具本身不持有 promoter，渠道/自主 agent 只能提案、不能安装。

### 混合记忆检索 BM25 + 向量（`hybrid-memory-search`，默认关，opt-in）

在记忆库（`MEMORY.md` + `memory/*.md` + `USER.md`）上叠加 OpenClaw 式**混合检索**：纯 Java `Bm25Index`（Okapi BM25，CJK 分词）+ 可插拔 `VectorStore`（默认内存 cosine，无原生依赖），`HybridRanker` 归一化后按 `0.7·向量 + 0.3·BM25` 混合。无 embedder 时优雅降级为 BM25-only。启用后 `HybridMemorySearchTool`（`@Tool name="memory_search"`）取代原生关键词检索。

## Harness / 原生能力

迁移到 2.0 后，大量此前自建的基础设施改为框架原生，Pig 只保留门面/接线：

| 能力 | 实现 | 运维/触达 |
| ---- | ---- | -------- |
| **原生权限引擎** | `PermissionEngine` + `PermissionContextFactory`（模式 `plan/ask/auto/bypass`）；`GuardedAgentTool extends ToolBase` 让引擎真正生效 | `/permission` |
| **人在环确认（HITL）** | ASK → 原生 `RequireUserConfirmEvent`，REPL 逐工具 y/a/N 后携 `ConfirmResult` 恢复 | REPL 内联 |
| **原生重试 + 中断** | `ReActAgent` `maxRetries` / `fallbackModel`（429/5xx 自愈）；`interrupt(RuntimeContext)` 协作式中断 | 回合中 Ctrl-C |
| **Hook → 中间件** | `MiddlewareBase`（5 阶段：`onAgent/onReasoning/onActing/onModelCall/onSystemPrompt`）；1.x Hook 事件模型已移除 | — |
| **原生事件流** | `streamEvents` → `Flux<AgentEvent>`（`TextBlockDeltaEvent`/`ToolResult*Event`/…），按子类型分发 | — |
| **原生状态 / session** | `JsonFileAgentStateStore`（`workspace/state/`），按 `(userId,sessionId)` 自动存取 | `/session` |
| **原生子 agent 委派** | `agent_spawn`/`agent_send`/`agent_list` + `task_*`；父权限 fail-closed 继承（Pig 注入 `subagentFactory` 补 2.0.0 缺口） | 自动（默认开）|
| **Plan Mode** | 原生「只读思考 → 写 `PLAN.md` → HITL 批准 → 执行」（`plan_enter/write/exit`） | `/plan` |
| **大结果淘汰** | `ToolResultEviction`（默认开）：超阈值工具结果落盘 + 占位回读，防上下文膨胀 | 自动 |
| **按需工具** | `tool_search`（默认关）：大/低频工具留在非激活组，模型按需揭示，省 prompt token | 自动 |
| **命令执行沙箱** | `SandboxPolicy` + `CommandGuard`：输出封顶 / 超时 / 灾难命令三级 denylist / 环境凭据擦除 | 自动 |
| **循环检测** | `LoopDetectionMiddleware`：同一动作重复 → WARN 提示 / STOP 改写为哨兵工具 | 自动 |
| **主动外呼** | `notifyUser` `@Tool` + `OutreachGate` 防打扰（限流/去重/免打扰时段，URGENT 例外） | `/notify` |
| **Gateway 渠道内核** | 可选：原生 `Gateway`/`ChatUiChannel`（公平队列 + 子 agent 桥 `expose_to_user`） | `channel-gateway` |
| **动态 MCP** | `McpManager` + `mcp.json` 运行时增删改查、热注册工具 | `/mcp` |

> **peer agent vs 子 agent**：`/agent use` 切换的是长期存在的 **peer**（多 agent，每个有自己的 `AgentSpec`/模型/工具子集/权限模式，落盘 `workspace/agents/{id}.md`）；子 agent 是 peer 委派子任务的**临时子进程**（经 `agent_spawn`）。二者不混淆。

## 系统模块

Maven 多模块项目（`io.pigagent`，`0.1.0-SNAPSHOT`），共 **16 个模块**；`pig-agent-cli` 是主入口，依赖其余全部。

| 模块 | 职责 | 关键类型 |
| ---- | ---- | -------- |
| `pig-agent-core` | Agent 载体（`HarnessAgent` 封装）+ 可重建 agent、多 agent 注册表、原生中间件、原生两层记忆注入/迁移、用户画像、压缩、中断、协议 SPI | `PigAgent`、`AgentHolder`、`AgentFactory`、`AgentSpec`、`AgentRegistry`、`AgentInstanceFactory`、`middleware/*`、`memory/*`、`memory/search/*`、`profile/*`、`compression/CompressionService`、`interrupt/*`、`protocol/ModelProtocol`、`outreach/*`、`agent/runner/*` |
| `pig-agent-providers` | 5 套模型协议实现 + 注册表 | `OpenAiProtocol`、`AnthropicProtocol`、`GeminiProtocol`、`OllamaProtocol`、`DashScopeProtocol`、`ProtocolRegistry` |
| `pig-agent-model` | 多模型配置、连通测试、运行时切换 | `StoredModel`、`ModelStore`、`JsonModelStore`、`ModelManager` |
| `pig-agent-session` | 会话元数据/血缘 sidecar（叠在原生 `AgentStateStore` 上） | `Session`、`SessionManager`、`FileSystemSessionRepository`、`SessionLineageWriter` |
| `pig-agent-tools` | **核心** `@Tool` 工具 + 工具框架（权限/契约/可用性/沙箱/SPI），SPI 自动注册；`SkillsTool` 组合多个 `SkillSource` | `ShellTools`、`FileSystemTools`、`TaskTool`、`SkillsTool`、`McpTool`、`notify/NotifyUserTool`、`profile/UserProfileTool`、`memory/HybridMemorySearchTool`、`skills/*`、`permission/*`、`contract/*`、`availability/*`、`sandbox/*`、`deferred/*`、`spi/*` |
| `pig-agent-task` | 任务模型、调度、文件持久化 | `Task`、`TaskManager`、`TaskScheduler`、`FileSystemTaskRepository` |
| `pig-agent-mcp` | 动态 MCP 服务器管理 + JSON store | `McpManager`、`McpServerSpec`、`McpStore`、`JsonMcpStore` |
| `pig-agent-workspace` | `~/.pig-agent/workspace/` 布局 | `WorkspaceManager` |
| `pig-agent-config` | YAML 配置 + 变更监听 | `PigAgentConfig`、`ConfigurationManager`、`ConfigurationChangedEvent` |
| `pig-agent-channel` | 通道抽象 + agent 桥接 + 适配器工厂 + 原生 Gateway 内核 + 主动外呼 | `Channel`、`ChannelFactory`、`ChannelAgentBridge`、`TelegramChannel`/`DiscordChannel`/`SlackChannel`/`WebhookChannel`/`StdinPipeChannel`、`gateway/*`、`outreach/*`、`ChannelRegistry` |
| `pig-agent-onboarding` | 首次运行交互式模型配置 | `OnboardingWizard` |
| `pig-agent-plugin` | 插件 SPI（`register(ctx)`）+ 发现源 | `Plugin`、`PluginContext`、`ServiceLoaderPluginSource`、`DirectoryPluginSource`、`PluginRegistry` |
| `pig-agent-plugin-builtin` | 内置插件参考实现：6 个离线计算插件 + 从核心拆出的 web 搜索/抓取/清单 | `AbstractToolPlugin`、`PluginCatalog`、`Time/Uuid/Base64/Hash/Json/Random` 插件、`WebSearchPlugin`/`WebFetchPlugin`/`ChecklistPlugin` |
| `pig-agent-skills-builtin` | 内置系统技能（classpath `SKILL.md` 资源，`SkillProvider` SPI 发现） | `SkillCatalog`、`BuiltinSkillProvider`、7 个 `SKILL.md`（code-review / systematic-debugging / tdd / refactoring / git-commit / security-review / planning） |
| `pig-agent-web` | 嵌入式本地 Web 控制台——第二个 `AgentKernel` 适配器（HTTP + SSE），`web.enabled` 可选启用 | `WebConsole`、`WebLauncher`、`ChatHandler`、`EventStreamHandler`、`StaticHandler` |
| `pig-agent-cli` | picocli + JLine3 + Jansi 的 CC 风格 REPL，接线（`AgentBootstrap`）、优雅关闭 | `PigAgentCli`、`AgentBootstrap`、`repl/*`、`repl/command/*`、`repl/render/*`、`repl/select/*` |

## 内置工具

| 工具 | `@Tool` 方法 | 功能 |
| ---- | ------------ | ---- |
| `FileSystemTools` | `readFile`、`writeFile`、`listDirectory` | 文件读写与目录列表（凭据文件黑名单护栏） |
| `ShellTools` | `executeCommand` | 执行 shell 命令（命令执行沙箱：输出封顶/超时/denylist/环境擦除） |
| `TaskTool` | `createTask`、`listTasks`、`updateTaskStatus` | 任务管理 |
| `CheckListTool`（plugin-builtin） | `createChecklist`、`completeItem`、`showChecklist` | 清单管理 |
| `SkillsTool` | `listSkills`、`loadSkill` | 加载技能（内置 classpath + 工作区两源，渐进加载） |
| 原生记忆工具 | `memory_search`、`memory_get`、`memory_save`、`session_search` | 两层记忆检索/写入 |
| `UserProfileTool` | `updateProfile` | 确定性写入用户画像 `USER.md` |
| `NotifyUserTool` | `notifyUser` | 主动经渠道给用户发通知 |
| 自主技能（默认关） | `proposeSkill`、`skillManage` | 起草/管理暂存技能（人工门后才安装） |
| 按需工具（默认关） | `tool_search` | 揭示被延迟的工具 |
| `McpTool` | `listMcpServers`、`testMcpServer`、`addMcpServer`、`removeMcpServer` | Agent 自助 MCP（受 D-SEC 门控） |
| `WebSearchPlugin`（plugin-builtin） | `webSearch` | Brave Search 网页搜索（`BRAVE_API_KEY` 可用性门控） |
| `WebFetchPlugin`（plugin-builtin） | `fetchUrl` | 抓取网页（`SsrfGuard` 防私网访问） |
| 计算插件（plugin-builtin） | `currentDateTime`/`convertTimezone`、`generateUuid`、`base64Encode/Decode`、`md5Hash`/`sha256Hash`、`jsonPrettyPrint`/`jsonValidate`、`randomNumber`/`randomString` 等 | 纯计算，`READ_ONLY` |

> 所有内置工具遵循统一返回契约：成功返回正常输出，失败返回规范化的 `{"error":"<reason>"}`（凭据脱敏），从不抛异常中断回合。

## 代码结构

```
pig-agent/
├── pom.xml                          # 父 POM（16 modules，AgentScope 2.0.0）
├── pig-agent-core/                  # Agent 核心
│   └── src/main/java/io/pigagent/core/
│       ├── agent/PigAgent.java              # 封装 HarnessAgent（委派 ReActAgent）
│       ├── agent/kernel/AgentKernel.java    # 前端门面
│       ├── middleware/{LoggingMiddleware,ToolCallLoggingMiddleware}.java
│       ├── loop/LoopDetectionMiddleware.java
│       ├── memory/{ConversationMemory,NativeMemoryContextMiddleware,MemoryMigration}.java
│       ├── memory/search/{MemorySearchIndex,Bm25Index,HybridRanker,...}.java
│       ├── profile/{UserProfileStore,UserProfileContextMiddleware,...}.java
│       ├── compression/CompressionService.java
│       ├── interrupt/{InterruptController,TurnHandle}.java
│       ├── outreach/{Notification,NotificationService,OutreachGate}.java
│       ├── agent/runner/AgentRunner.java    # 数字员工（自主 agent）
│       └── protocol/{ModelProtocol,ModelSpec}.java
├── pig-agent-providers/             # 5 套模型协议
│   └── .../provider/{openai,anthropic,gemini,ollama,dashscope}/*Protocol.java
├── pig-agent-tools/                 # 内置工具 + 工具框架
│   └── src/main/java/io/pigagent/tool/
│       ├── shell/ShellTools.java  filesystem/FileSystemTools.java  task/TaskTool.java
│       ├── skills/{SkillsTool,SkillRegistry,WorkspaceSkillSource,...}
│       ├── permission/{PermissionContextFactory,ToolRiskClassifier,...}
│       ├── contract/{ToolContractGuard,GuardedAgentTool,ToolErrors}
│       ├── sandbox/{SandboxPolicy,CommandGuard}
│       ├── deferred/{DeferredToolRegistry,ToolSearchTool}
│       └── spi/{ToolProvider,ToolContext,ToolRegistrar}
├── pig-agent-model/                 # StoredModel / ModelManager / JsonModelStore
├── pig-agent-session/               # SessionManager（原生 state 之上的 sidecar）
├── pig-agent-task/                  # Task / TaskScheduler / FileSystemTaskRepository
├── pig-agent-mcp/                   # McpManager / JsonMcpStore
├── pig-agent-workspace/             # WorkspaceManager
├── pig-agent-config/                # PigAgentConfig / ConfigurationManager
├── pig-agent-channel/               # Channel / ChannelAgentBridge / gateway / outreach
├── pig-agent-onboarding/            # OnboardingWizard
├── pig-agent-plugin/                # Plugin SPI + 发现源
├── pig-agent-plugin-builtin/        # 内置插件（计算 + web + 清单）
├── pig-agent-skills-builtin/        # 7 个内置技能 SKILL.md
├── pig-agent-web/                   # 可选 Web 控制台适配器
└── pig-agent-cli/                   # CLI 入口
    └── src/main/java/io/pigagent/cli/
        ├── PigAgentCli.java  AgentBootstrap.java
        └── repl/{AgentRepl,ReplCommands,StatusLine,...}  repl/command/*  repl/render/*
```

## 快速开始

### 前置条件

- Java 17+
- Maven 3.9+
- 至少一个可用的 LLM 模型（首次运行交互式配置，或用环境变量兜底）

### 编译与运行

```bash
mvn compile                       # 编译全部模块
mvn -pl pig-agent-cli -am compile # 只编译 CLI 及其依赖
mvn exec:java -pl pig-agent-cli   # 启动 CLI REPL（主类 io.pigagent.cli.PigAgentCli）
mvn test                          # 全量单测（离线）
mvn verify                        # 单测 + jacoco 每模块覆盖率下限（ratchet）；*IT 默认跳过
mvn verify -Pit                   # 额外跑真模型 *IT（需配置好 models.json）
```

模型**在首次运行时交互式配置**：引导向导写入 `~/.pig-agent/workspace/models.json`，此后以该文件为准。API key 不再只从环境读取，但环境变量仍作兜底：`ANTHROPIC_API_KEY`、`OPENAI_API_KEY`、`DASHSCOPE_API_KEY`、`GEMINI_API_KEY`、`MIMO_API_KEY`（Ollama 无需 key）。没有任何可用模型时，向导会运行且无法跳过。

首次运行会自动创建 `~/.pig-agent/workspace/`，生成 `AGENT.md`（可编辑的系统提示词）、`INFO.md`（环境信息）、`application.yaml`。

> **终端要求**：JLine 需要真控制台。请用 **Windows Terminal / PowerShell / cmd**（或 Linux/macOS 原生终端）。**git-bash / mintty 下 stdin 不是真 TTY，交互会异常**——请用 `winpty mvn ... exec:java`，或改用 Windows Terminal。

### 工作区结构

```
~/.pig-agent/workspace/
├── AGENT.md            # 系统提示词（可编辑，仅新工作区 seed 默认值）
├── INFO.md             # 每机环境信息
├── USER.md             # 用户画像（身份/偏好/工作方式）
├── MEMORY.md           # 记忆固化层（跨会话，注入 system prompt）
├── memory/             # 记忆日志层：YYYY-MM-DD.md（每回合 flush）
├── models.json         # 模型配置（0600，凭据源）
├── mcp.json            # MCP 服务器配置（0600）
├── application.yaml    # 其余配置
├── state/              # 原生对话状态（(userId,sessionId) 存储槽）
├── sessions/           # 会话元数据 sidecar（meta.json + temp-memory）
├── agents/             # 声明式 AgentSpec（{id}.md）
├── skills/             # 工作区技能（含 .pending 暂存区）
├── tasks/              # 任务（按日期 + recurring/）
├── reports/            # 数字员工晨报
├── plugins/            # 外部插件 jar
└── logs/               # 滚动日志 pig-agent.log
```

### 日志

日志经 **SLF4J + Logback**：控制台 + 滚动文件（`~/.pig-agent/workspace/logs/pig-agent.log`）。默认级别 INFO；要调级别/输出目标，编辑 `pig-agent-cli/src/main/resources/logback.xml`。首次运行向导与 REPL 的彩色/流式输出属交互界面，不走日志。

### 配置示例

编辑 `~/.pig-agent/workspace/application.yaml`（以下均可选、缺省即安全默认，仅列常用块）：

```yaml
# —— agent 迭代上限 ——
agent:
  name: PigAgent
  max-iters: 40                 # 交互/渠道默认 40；自主/数字员工用 AgentSpec.maxIters（默认 10）

# —— 模型重试（原生，429/5xx 自愈）——
model:
  retry:
    enabled: true
    max-retries: 10

# —— 记忆（原生两层）——
memory-enabled: true            # 总开关（= /memory on|off）
memory:
  flush: throttled              # always | never | throttled
  flush-throttle-minutes: 5
  consolidation-min-gap-minutes: 30
  model-id: ""                  # 廉价模型跑 flush/consolidation；空=用主模型
  search:
    hybrid-enabled: false       # 开启 BM25+向量混合检索（默认关=原生关键词）
    bm25-weight: 0.3
    vector-weight: 0.7

# —— 用户画像 ——
user-profile:
  enabled: true
  path: USER.md
  consolidation:
    enabled: false              # 后台从 MEMORY.md 蒸馏画像（默认关）

# —— 权限（原生 PermissionEngine）——
permissions:
  mode: ask                     # plan | ask（默认）| auto | bypass
  channel-mode: auto            # 渠道 agent 无 confirmer，fail-closed
  allowlist:
    tools: []
    commands: []                # executeCommand 按命令首 token 放行

# —— 命令执行沙箱 ——
sandbox:
  exec:
    timeout-seconds: 30
    max-output-bytes: 200000
    scrub-env: true
    denylist: []                # 只能扩展内置灾难命令下限
    warnlist: []

# —— 工具：web / 按需 / 大结果淘汰 ——
tools:
  web:
    allowed-hosts: []           # 非空时 fetchUrl 仅放行白名单
  deferred:
    enabled: false              # 按需工具（tool_search）
    auto-defer-mcp: true
    threshold: 25
  result-eviction:
    enabled: true               # 大工具结果落盘（默认开）
    threshold: 80000

# —— 子 agent / Plan Mode / 自主技能 / 外呼 ——
subagents:
  enabled: true                 # 交互轨道原生子 agent 委派
plan-mode:
  enabled: false                # 原生 Plan Mode（/plan）
skills:
  autonomous:
    enabled: false              # 自主沉淀技能（proposeSkill + 人工门）
    auto-promote: false
outreach:
  enabled: false                # 主动外呼（notifyUser / 晨报推送）
  channel: ""
  quiet-hours:
    enabled: false
    start: "22:00"
    end: "08:00"

# —— 循环检测 ——
loop-detection:
  enabled: true
  window-size: 20
  warn-threshold: 3
  stop-threshold: 5

# —— 渠道 ——
channels:
  telegram: { enabled: false, token: "" }
  discord:  { enabled: false, token: "" }
channel-gateway:
  enabled: false                # 原生 Gateway 内核（公平队列 + expose_to_user）

# —— MCP（首启从此导入到 mcp.json，之后以 mcp.json 为准）——
mcp:
  servers: {}
  agent-management:
    allow-add: false
    allow-remove: false
    allowed-hosts: []
```

## REPL 命令

REPL 是 Claude-Code 风格的**行式对话**（非全屏 TUI）。输入 `/` 会**自动弹出补全菜单**并随输入实时过滤（如 `/mo` → `/model`），`↑/↓` 移动、Enter 补全、Esc 退出——仅当缓冲以 `/` 开头，普通对话不打扰。回合进行中 Ctrl-C **真中断**（取消当前模型调用、回到提示符，进程不退出）；空闲态 Ctrl-C 丢弃当前行、Ctrl-D 退出。

```
/help                 帮助                    /model      管理模型（list|add|switch|edit|delete）
/agent                管理 agent（list|use|new|model|run|report）
/session              会话（new|list|switch|fork|…）      /memory  切换/查看原生长期记忆
/compress             上下文压缩（now|status|off|on）      /mcp     MCP 服务器（list|add|remove|edit|enable|disable|test）
/permission           权限（status|mode|channel-mode|allow|revoke|reset|list）
/plan                 原生 Plan Mode（enter|exit|status）  /notify  主动外呼（status|test）
/skills               列出可用技能（只读）                /skill   自主技能人工门（review|approve|reject）
/tasks                列出任务                            /status  agent 状态摘要
/protocols            列出模型协议类型                    /channels 已连接渠道及状态
/config               查看当前配置                        /clear   清屏      /quit（/exit） 退出
```

## 核心扩展（SPI 优先，不改核心）

### 1. 自定义工具（`@Tool` + SPI 自动注册）

写一个带 `@Tool`/`@ToolParam` 方法的类，再加一个 `ToolProvider` SPI 实现并登记到 `META-INF/services/io.pigagent.tool.spi.ToolProvider`，`ToolRegistrar` 会在 `AgentBootstrap` 自动发现，无需改接线。

```java
public final class MyCustomTool {
    @Tool(description = "描述工具的功能")
    public String myMethod(@ToolParam(name = "param1", description = "参数说明") String param1) {
        // 成功返回正常输出；失败返回 ToolErrors.message(reason)（规范 {"error"}，凭据脱敏）
        return "结果";
    }
}
```

### 2. 自定义中间件（`MiddlewareBase`）

实现 `io.agentscope.core.middleware.MiddlewareBase`（2.0 的 5 阶段）；每个阶段包裹一个 `next`，你用「可能是新的」输入 record 调用它来注入/观测，而不改传入列表。1.x 的 `Hook` 事件模型（`Pre/PostReasoningEvent`、`priority()`）已移除。

```java
public final class MyMiddleware extends MiddlewareBase {
    @Override
    public Flux<AgentEvent> onReasoning(ReasoningInput input, ReasoningNext next) {
        // 观测/改写后交给 next（顺序 = builder 中的列表位置）
        return next.next(input);
    }
}
```

### 3. 自定义模型协议

实现 `ModelProtocol`（`io.pigagent.core.protocol`：`protocolId()` + `createModel(ModelSpec)` + `supportsBaseUrl()`/`requiresApiKey()`），在 `PigAgentCli` 注册到 `ProtocolRegistry`。

> 接入**已有协议**的新厂商（例如又一个 OpenAI 兼容端点）**无需写代码**——用户在向导 / `/model add` 里选协议并填 baseUrl 即可（小米 mimo / DeepSeek / Kimi / 通义-compat 都走 `openai` 协议）。

| 协议 | 底层 Model | 默认模型 | 说明 |
| ---- | ---------- | -------- | ---- |
| openai | `OpenAIChatModel` | gpt-4o | OpenAI 兼容协议，吃掉 mimo/DeepSeek/Kimi 等 |
| anthropic | `AnthropicChatModel` | claude-sonnet-4-6 | Anthropic messages 协议 |
| gemini | `GeminiChatModel` | gemini-2.0-flash | Google Gemini 协议 |
| ollama | `OllamaChatModel` | llama3.2 | 本地自托管，无需 API key |
| dashscope | `DashScopeChatModel` | qwen-max | 阿里云 DashScope 原生协议（通义千问） |

（底层 Model 类为 `io.agentscope.extensions.model.<protocol>.*ChatModel`。）

### 4. 自定义通道

实现 `Channel` 接口并注册到 `ChannelRegistry`；`ChannelAgentBridge` 把渠道消息路由到 agent 并回传。可选实现 `OutboundChannel.send(...)` 支持主动外呼。原生平台适配器（钉钉/飞书/GitHub/…）可在 `channels.<id>.native: true` 时反射加载。

### 5. 自定义插件

实现 `io.pigagent.plugin.Plugin`（`register(PluginContext ctx)`），用 `ctx.addTool(...)` / `ctx.addMiddleware(...)` 贡献能力；两种发现源：classpath `ServiceLoader<Plugin>`，或丢进 `workspace/plugins/` 的外部 jar（`URLClassLoader`）。`pig-agent-plugin-builtin` 是参考实现（计算 + web + 清单）。

> 外部 jar 是无沙箱的任意代码——只放可信 jar。

### 6. 自主沉淀技能

`proposeSkill` 起草 → 暂存区 → `/skill approve` 人工门 → 提升到 `workspace/skills/`，`WorkspaceSkillSource` 实时发现。安全扫描 + 去重内建，渠道/自主 agent fail-closed（只提案不安装）。

## 工具安全护栏（多道正交）

工具执行叠了多道相互独立的护栏，各管一件事：

1. **可用性门控**（`availability`）——决定工具是否对模型**可见**（如 `webSearch` 依赖 `BRAVE_API_KEY`，缺失则从 schema 移除）。
2. **原生权限引擎**（`PermissionEngine`）——决定可见工具是否**可运行**。四种模式 `plan`（只读产计划）/ `ask`（逐次确认，默认）/ `auto`（自动放行写文件/网络，仍确认执行）/ `bypass`（全放行）；工具按风险分级（只读/写/执行/网络/MCP 管理，未知按最严）。ASK 走原生 HITL 确认；`a`（始终允许）持久化到 allowlist。运维：`/permission`。
3. **返回契约**（`contract`）——成功返回正常输出，失败返回规范 `{"error"}`（凭据脱敏）；`ToolContractGuard` 把每个工具包成 `GuardedAgentTool extends ToolBase`（既兜住异常，又让权限引擎真正生效）。
4. **出口边界**（`sandbox`）——文件出口黑名单（`readFile`/`writeFile` 拒触凭据文件 `models.json`/`mcp.json`）；网络出口 SSRF 防护（`fetchUrl` 解析目标全部 IP，命中回环/私网/link-local/云 metadata 即拒）。
5. **命令执行沙箱**——`executeCommand` 专属：输出封顶（默认 200KB）、可配超时（默认 30s）、灾难命令三级 denylist（`block`>`warn`>`pass`，两遍最严胜）、环境凭据擦除。
6. **循环检测**——同一工具签名重复 → WARN 提示 / STOP 改写为哨兵，防推理-工具死循环。

> 凭据卫生：`/model`、`/mcp`、向导录入敏感值掩码不回显；`models.json`/`mcp.json` 落盘 POSIX `0600`。凭据仍**明文存储**（依赖 `0600` + 目录权限）——**请勿在共享主机使用**。

## 24 小时不间断运行

Pig Agent 可作为后台服务常驻，持续接收渠道消息、执行定时任务、无人值守替人办事。

```bash
# 前台
mvn exec:java -pl pig-agent-cli
# 后台
nohup mvn exec:java -pl pig-agent-cli > pig-agent.log 2>&1 &
# 生产推荐 systemd（Restart=always 故障自愈）
```

**数字员工（自主 agent）**：一个带 `schedule`（cron 表达式）的 `AgentSpec` 即自主 agent——`TaskScheduler` 到点触发 `AgentRunner`，构建一次性隔离 agent 无人值守执行 `mandate`，写三段式晨报（`我做了 / 我发现 / 等你决定`）到 `workspace/reports/{date}/{id}.md`。安全：无 confirmer → 原生 `DONT_ASK` + ASK→DENY（fail-closed），只有 `commandAllowlist` 里的安全命令放行，其余被 `PermissionEngine` 拒并汇总到「等你决定」。运维：`/agent run <id>`、`/agent report`。样例：`docs/examples/nightwatch.md`。

| 能力 | 说明 |
| ---- | ---- |
| 渠道常驻 | Telegram/Discord/Slack/Webhook 等持续监听，收到即响应 |
| 主动外呼 | `notifyUser` + 定时晨报/提醒，`OutreachGate` 防打扰（URGENT 例外） |
| 定时任务 | `TaskScheduler` 后台执行 CRON/DELAYED，`.md` 无损往返、重启重排 |
| MCP 长连接 | 连接保持、工具随时可用 |
| 运行上限 | `maxIters` 约束全部 agent（交互/渠道 40，自主 10） |
| 配置容错 | 忽略未知字段，schema 漂移不再让整份配置回退默认 |
| 优雅关闭 | SIGTERM 时依次关闭渠道、任务调度器、MCP 连接 |

## 设计原则

- **不可变数据** — `Task`、`Session`、`StoredModel`、`ProviderCredentials` 等均为 record，通过 `withXxx()` 生成新实例。
- **设计模式优先** — Strategy / Factory / 枚举登记 / Template Method 等灵活运用，不面向功能硬编码。
- **容错持久化** — 文件仓库遇单个坏文件跳过或备份，从不让整份列表崩溃。
- **门面 + 适配器** — 前端只依赖 `AgentKernel` 门面；「加一个前端 = 加一个适配器」（REPL + Web 两个活例）。
- **SPI 扩展** — 工具 / 中间件 / 协议 / 通道 / 插件 / 技能都靠 SPI 组合，不改核心。
- **配置驱动** — YAML + 变更监听器，运行时动态调整。

## License

Apache License 2.0
