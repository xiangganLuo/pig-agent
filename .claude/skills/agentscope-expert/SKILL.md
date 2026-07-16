---
name: agentscope-expert
description: >-
  AgentScope Java 2.0 (io.agentscope) API / architecture / migration authority.
  Invoke for ANY design, API, or migration question about AgentScope 2.0 —
  HarnessAgent vs ReActAgent, the builder API (.model/.stateStore/.memory/.compaction/.subagent/.workspace/.filesystem/.enablePlanMode/.enableTaskList/.skillRepository/.maxRetries/.fallbackModel/.permissionContext/.middlewares/.toolkit + fromAgent),
  streamEvents→Flux<AgentEvent>, RuntimeContext/AgentState, subagents (agent_spawn/agent_send/expose_to_user),
  the permission engine + HITL, middleware (5 stages), memory & compaction, sandbox & filesystem, tools & MCP,
  channels/Gateway + native adapters (DingTalk/Feishu/GitHub/GitLab/WeCom), model providers,
  the v1→v2 breaking-change migration, and how pig-agent maps onto native 2.0 (REPLACE/KEEP/ADAPT/GAP).
  Use this instead of re-reading the raw docs. Says "verify via javap" where a fact needs bytecode confirmation.
license: MIT
compatibility: AgentScope Java 2.0 (io.agentscope, groupId io.agentscope). Distilled from the official docs.
metadata:
  author: pig-agent
  version: "1.0"
  source: "D:\\Users\\admin\\Documents\\en\\ (official AgentScope 2.0 docs)"
---

# AgentScope Java 2.0 Expert

This is a **navigable index**. High-signal facts + exact identifiers live below; deep per-topic
cheatsheets (exact class/method/package + doc-quoted snippets) live in the sibling reference files —
load one only when you need that depth (progressive loading). Chinese prose, but **all API identifiers
are verbatim**. Where a signature can't be confirmed from doc text it is marked `（需 javap 验证）`.

> 权威来源 = 本地官方文档 `D:\Users\admin\Documents\en\`。每个支撑文件顶部都回引具体 doc 路径。

---

## 1. HarnessAgent vs ReActAgent — 选哪个
> 详见 `agent-and-harness.md`；源 `docs/building-blocks/agent.md` + `docs/harness/architecture.md`

| | `ReActAgent` | `HarnessAgent` |
|---|---|---|
| 包 | `io.agentscope.core.ReActAgent` | `io.agentscope.harness.agent.HarnessAgent` |
| artifact | `agentscope-core` | `agentscope-harness`（传递依赖 core） |
| 定位 | 裸 reason-act 循环：model+tools+permission+HITL+state | 薄封装 ReActAgent，叠加 workspace/记忆/压缩/子agent/沙盒/Plan Mode/skill |
| 何时 | 单请求→推理→工具→回复 | 长期运行、跨请求恢复、记忆沉淀、多租户、沙盒 |

`HarnessAgent.builder()` 继承 `ReActAgent.builder()` 全部字段，再加 harness 专有方法。可用 `HarnessAgent.builder().fromAgent(reActAgent)` 从既有 ReActAgent 派生（`（需 javap 验证）` 精确签名）。

**Harness 能力面（一览）**：workspace persona（`AGENTS.md`/`MEMORY.md`/`tools.json`）· 状态持久化（`AgentStateStore`，keyed by `(userId,sessionId)`）· 双层长期记忆（`MemoryConfig`）· 对话压缩（`CompactionConfig`）· 大工具结果驱逐（`toolResultEviction`）· 子 agent 编排 · 可插拔文件系统/沙盒 · Plan Mode · Skill 多源仓库。

---

## 2. Builder API 速查
> 详见 `agent-and-harness.md`

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("assistant").sysPrompt("...")            // required
    .model("dashscope:qwen-plus")                  // 或 .model(Model 实例)
    .toolkit(new Toolkit())                          // 默认 new Toolkit()
    .middlewares(List.of(new MyMiddleware()))        // 或 .middleware(one)
    .stateStore(new JsonFileAgentStateStore(path))   // 状态持久化
    .permissionContext(PermissionContextState.DEFAULT)
    .maxIters(10) .maxRetries(3) .fallbackModel("dashscope:qwen-max")
    .enableMetaTool(true) .enableTaskList()          // 工具组元工具 / 任务列表
    // ---- Harness 专有 ----
    .workspace(Paths.get(".agentscope/workspace"))
    .memory(MemoryConfig.builder()...build())
    .compaction(CompactionConfig.builder().triggerMessages(30).keepMessages(10).build())
    .toolResultEviction(ToolResultEvictionConfig.defaults())
    .subagent(SubagentDeclaration.builder()...build())
    .filesystem(new DockerFilesystemSpec().image("ubuntu:24.04"))  // 或 LocalFilesystemSpec/RemoteFilesystemSpec
    .distributedStore(RedisDistributedStore.fromJedis(jedis))       // 一行=stateStore+baseStore+snapshot+guard
    .enablePlanMode()                                // 只读计划阶段
    .skillRepository(new GitSkillRepository(url))     // 可多次调用（叠加）
    .build();
```

关键默认：`maxIters`=10 · `stateStore`=null（无持久化）· `toolkit`=`new Toolkit()` · `permissionContext`=`DEFAULT`。自定义 `.middleware(...)` 在所有 Harness 内置 middleware **之前**执行。

---

## 3. 调用与事件流
> 详见 `agent-and-harness.md` + `events-streaming.md`；源 `docs/building-blocks/{agent,message-and-event}.md`

```java
// 阻塞：Mono<Msg>
Msg r = agent.call(List.of(new UserMessage("Hello")),
    RuntimeContext.builder().userId("alice").sessionId("s1").build()).block();
// 结构化输出：call(List<Msg>, Class<T>, RuntimeContext) → r.getStructuredData(T.class)
// 流式：Flux<AgentEvent>
agent.streamEvents(new UserMessage("...")).doOnNext(e -> { ... }).blockLast();
agent.interrupt("alice","s1");                     // 取消进行中的 call（per-session）
AgentState st = agent.getAgentState("alice","s1"); // st.toJson()
```

- **`RuntimeContext`** = 每次调用的元数据袋（`userId`/`sessionId` + `put(key/Class, val)` 两层属性）；同 `(userId,sessionId)` 串行、不同 session 并行。生产头号陷阱：不传 ctx → 全部落到 `defaultSessionId` 串话。
- **`Msg`**（`io.agentscope.core.message`）内容是有序 `ContentBlock`：`TextBlock`/`DataBlock`(取代旧 Image/Audio/Video)/`ThinkingBlock`/`ToolUseBlock`/`ToolResultBlock`/`HintBlock`；角色 `UserMessage`/`AssistantMessage`/`SystemMessage`。`getGenerateReason()`→`MODEL_STOP/TOOL_SUSPENDED/ALL_TOOLS_DENIED/INTERRUPTED/MAX_ITERATIONS/...`
- **`AgentEvent`**（`io.agentscope.core.event`）24 类，按 `getType():AgentEventType` + `getSource()`（子agent 为 `"main/child"`）分派。核心：`AgentStartEvent`/`AgentEndEvent`/`ExceedMaxItersEvent`/`RequestStopEvent`；`TextBlock{Start,Delta,End}Event`（`getDelta()`）；`ThinkingBlock*`/`DataBlock*`；`ToolCall{Start,Delta,End}Event`；`ToolResult{Start,TextDelta,DataDelta,End}Event`（`getState():ToolResultState = SUCCESS/ERROR/INTERRUPTED/DENIED/RUNNING`）；`ModelCall{Start,End}Event`；HITL：`RequireUserConfirmEvent`/`UserConfirmResultEvent`/`RequireExternalExecutionEvent`/`ExternalExecutionResultEvent`/`AllToolsDeniedEvent`；`SubagentExposedEvent`。`（枚举常量名需 javap 验证）`

---

## 4. Subagent 编排
> 详见 `subagent.md`；源 `docs/harness/subagent.md`

声明三途（互斥源）：内置 `general-purpose` · workspace `workspace/subagents/<id>.md`（YAML 前置元数据）· 代码 `.subagent(SubagentDeclaration.builder().name().description().workspace()/.url()/.inlineAgentsBody()...)`。
父 agent 内置工具：`agent_spawn` / `agent_send` / `agent_list` / `task_output` / `task_cancel` / `task_list`。
**同步 vs 后台** = `agent_spawn` 的 `timeout_seconds`：`>0`（默认30，最大600）阻塞返回；`=0` 后台返回 `task_id`，完成后框架**自动 push-back**（下一步推理前注入 `<system-reminder>`，**无需轮询**）。
`persistSession(true)` 跨 spawn 复用实例（保留历史）。`expose_to_user=true` → 发 `SubagentExposedEvent`，客户端 `chat.sendToSubagent(subagentId, ...)` 绕过父 agent 直连（需 `agent.channel(...)` 绑定 Channel）。安全：DENY 规则 + Plan Mode 自动继承；子 agent 不能再 spawn（硬上限 3 层）；`userId` 传播。同步本地子 agent 事件实时转发到父 `streamEvents()`（后台/远程不转发）。

---

## 5. Permission + HITL / Middleware / State
> 详见 `permission-hitl.md`、`middleware.md`、`state-session.md`

**Permission**（`io.agentscope.core.permission`）：`PermissionMode`=`DEFAULT/ACCEPT_EDITS/EXPLORE/BYPASS/DONT_ASK`；`PermissionBehavior`=`ALLOW/DENY/ASK/PASSTHROUGH`；决策工厂 `PermissionDecision.allow/deny/ask/passthrough(msg)`。DENY 规则 + 危险路径 (`ToolDangerousPathConstants`) **即使 BYPASS 也执行**。运行时切换 `agent.setPermissionMode(ctx, mode)`。**Plan Mode**（仅 HarnessAgent）：`.enablePlanMode()`/`planFileDirectory()`/`allowShellInPlanMode()`；工具 `plan_enter/plan_write/plan_exit`；API `agent.enterPlanMode(ctx)/exitPlanMode(ctx)/isPlanModeActive(ctx)`。

**Middleware**（`io.agentscope.core.middleware.MiddlewareBase`）5 阶段：`onAgent` / `onReasoning` / `onActing` / `onModelCall`（4 个 Onion 洋葱包裹，列表首个最外层）+ `onSystemPrompt`（Transformer 管道，左→右）。输入 Record：`AgentInput`/`ReasoningInput`/`ActingInput`/`ModelCallInput`/`String`。内置 `OtelTracingMiddleware`、`TaskReminderMiddleware`。

**State**：`AgentStateStore`（`io.agentscope.core.state`）实现 `InMemoryAgentStateStore`/`JsonFileAgentStateStore`（core）· `RedisAgentStateStore`(`agentscope-extensions-redis`)/`MysqlAgentStateStore`(`-mysql`)/`OssAgentStateStore`(`-oss`)；每次 `call()` 结束一次性写入。`AgentState.toJson()`。分布式一行入口 `RedisDistributedStore`/`MysqlDistributedStore`/`OssDistributedStore` → `DistributedStore`。

---

## 6. Memory & Compaction
> 详见 `memory-compaction.md`；源 `docs/harness/{memory,compaction}.md` + `integration/memory/*`

两套体系并存：① HarnessAgent 内置双层文件记忆 `MemoryConfig`（`memory/YYYY-MM-DD.md` 日志 → LLM 合并到 `MEMORY.md` → 每步注入 system prompt；工具 `memory_search`/`memory_get`）；② ReActAgent 外部 `LongTermMemory` SPI（Mem0/ReMe/Bailian，`.longTermMemory(...)`+`.longTermMemoryMode(BOTH)`）。
`CompactionConfig`：`triggerMessages`(50)/`triggerTokens`(80_000)/`keepMessages`(20)/`keepTokens`/`flushBeforeCompact`/`offloadBeforeCompact`/`summaryPrompt`(须含 `{messages}`)/`truncateArgs`。
**省 token 关键杠杆**：`MemoryConfig.builder().model("openai:gpt-4.1-mini")` 和 `CompactionConfig.builder().model(...)` 各自独立指向便宜模型（默认回落主模型）。溢出安全网：配了 `.compaction(...)` 后模型报 `context_length_exceeded` 自动极端压缩+重试一次。集成类：`Mem0LongTermMemory`(`-mem0`) / `ReMeLongTermMemory`(`-reme`) / `BailianLongTermMemory`(`-memory-bailian`)。

---

## 7. Sandbox & Filesystem / Tools & MCP / Skills
> 详见 `sandbox-filesystem.md`、`tools-mcp.md`、`skills.md`

**Filesystem**（`.filesystem(spec)`，统一 `AbstractFilesystem`）三模式：`LocalFilesystemSpec`（默认，宿主 shell；`LocalFsMode`=`ROOTED/SANDBOXED/UNRESTRICTED`）· `RemoteFilesystemSpec`（共享 KV，无 shell）· 沙盒 `DockerFilesystemSpec`/`KubernetesFilesystemSpec`/`E2bFilesystemSpec`/`DaytonaFilesystemSpec`/`AgentRunFilesystemSpec`。`IsolationScope`=`USER/SESSION/AGENT/GLOBAL`（USER 缺 userId 自动降级 SESSION）。快照 `*SnapshotSpec`，并发锁 `SandboxExecutionGuard`（Redis/Jdbc）。

**Tools**：`Toolkit.registerTool(Object)`（扫 `@Tool`/`@ToolParam` 方法进 `"basic"` 组）；或继承 `ToolBase`（`callAsync(ToolCallParam)→Mono<ToolResultBlock>`、`checkPermissions(...)→Mono<PermissionDecision>`）。`ToolGroup`+`enableMetaTool` 运行时激活/停用工具组。外部执行工具 `.externalTool(true)` → `RequireExternalExecutionEvent`。
**MCP**（`io.agentscope.core.tool.mcp`）：`McpClientBuilder.stdio()/.streamableHttp()/.sse()` → `McpClientWrapper` → `toolkit.registerMcpClient(w).block()`（异步，必须 `.block()`）；命名空间 `mcp__{server}__{tool}`。

**Skills**：`AgentSkillRepository` 接口（`getAllSkills/getSkill/save/delete`），`.skillRepository(repo)` 可多次叠加。实现 `GitSkillRepository`(`-skill-git-repository`)/`MysqlSkillRepository`(`-skill-mysql-repository`)/`PostgresSkillRepository`(`-skill-postgresql-repository`)/`NacosSkillRepository`(`-nacos-skill`)/`ClasspathSkillRepository`。skill = 目录 `SKILL.md`(YAML `name`+`description` + 正文) + `references/`+`scripts/`；agent 用 `load_skill_through_path(skillId, path)` 按需读取。优先级 project-global < marketplace < workspace < per-user。

---

## 8. Model Providers + Maven / 包结构
> 详见 `model.md` + `integrations.md`；源 `docs/building-blocks/model.md` + `integration/*`

`agentscope-core` 只留 `Model`/`ChatModelBase`/`Formatter`/`ModelRegistry`/`ModelProvider` SPI；provider 全部移到 `io.agentscope.extensions.model.<provider>.*`（**这是 v2 头号 breaking + 首个 NoClassDefFoundError 来源**）。

| Provider | 类 | artifactId | env |
|---|---|---|---|
| OpenAI（含所有兼容端点，`baseUrl(...)`）| `OpenAIChatModel` | `agentscope-extensions-model-openai` | `OPENAI_API_KEY` |
| Anthropic | `AnthropicChatModel` | `agentscope-extensions-model-anthropic` | `ANTHROPIC_API_KEY` |
| DashScope | `DashScopeChatModel` | `agentscope-extensions-model-dashscope` | `DASHSCOPE_API_KEY` |
| Gemini | `GeminiChatModel` | `agentscope-extensions-model-gemini` | `GEMINI_API_KEY` |
| Ollama | `OllamaChatModel` | `agentscope-extensions-model-ollama` | `OLLAMA_BASE_URL`(可选) |

三种建法：字符串 `"provider:model"`（`ModelRegistry.resolve`，SPI 自动发现 + 读 env）· 显式 `XxxChatModel.builder().apiKey().modelName().baseUrl().stream().defaultOptions(GenerateOptions...)` · Spring Boot `agentscope-<provider>-spring-boot-starter`。核心流式 API：`Model.stream(List<Msg>, List<ToolSchema>, GenerateOptions) → Flux<ChatResponse>`（自定义 provider 重写 `ChatModelBase.doStream`）。OpenAI 兼容 Credential（`DeepSeekCredential`/`KimiCredential`/`XAICredential`）在 core。
其他扩展：`DistributedStore`（`-redis`/`-mysql`/`-oss`）· 沙盒（`-sandbox-{kubernetes,agentrun,daytona,e2b}`，Docker 内置）· channel（见 §9）· 协议 A2A/AG-UI/Agent Protocol（`integrations.md`）· 大多数扩展有 `agentscope-spring-boot-starter-*`。

---

## 9. Channel & Gateway + 原生适配器
> 详见 `channel-gateway.md`；源 `docs/harness/channel.md` + `integration/channel/*`

`Gateway`（session 管理 + 单 session 并发排队 + 多 agent 路由）与 `Channel`（适配平台）。最简：`ChatUiChannel chat = agent.channel(ChatUiChannel.create()); chat.send(SendOptions.userId("u1"),"...").block();`（`sendStream(...)`→`Flux<AgentEvent>`）。多 agent：`GatewayBootstrap.builder().agent(id,a).mainAgent(id).build()`。自定义 channel 实现 `Channel`（`dispatch/dispatchStream` 调 `gateway.run/runStream`）。
原生适配器（均 `XxxChannel.fromProperties(id, ChannelConfig.of(id,agentId), props)`；共享 `agentscope-extensions-channel-common` 的 `IdempotencyStore`+`BotLoopGuard`）：`DingTalkChannel`(`-channel-dingtalk`, WebSocket Stream) · `FeishuChannel`(`-channel-feishu`, HTTP 回调, 需 Spring) · `GitHubChannel`(`-channel-github`, webhook) · `GitLabChannel`(`-channel-gitlab`) · `WeComChannel`(`-channel-wecom`, 加密回调, 需 Spring)。

---

## 10. v1 → v2 迁移速查
> 详见 `migration-v1-to-v2.md`；源 `docs/change-log.md` + `docs/others/{going-to-production,faq}.md`

必改（否则编译/运行失败）：
- `.memory(Memory)` / `.statePersistence(...)` → **`.stateStore(AgentStateStore)`**；`io.agentscope.core.session.SessionManager`/`Session` **已删除** → `AgentStateStore` 自动 per-`(userId,sessionId)` 持久化。
- 模型类 `io.agentscope.core.model.<Provider>ChatModel` → `io.agentscope.extensions.model.<provider>.*`（+ 手动加 extension 依赖）。
- `io.agentscope.core.hook.*` → `io.agentscope.core.middleware.*`（`MiddlewareBase`）；1.x `Hook` 仍可跑但 `@Deprecated(forRemoval)`，经 `LegacyHookDispatcher` 桥接（`PreReasoningEvent` 等仍在）。
- `io.agentscope.core.plan.*` → `HarnessAgent.builder().enablePlanMode()`；`io.agentscope.core.pipeline.*` → middleware + 子 agent + 事件流。
- `AgentMetaState`→`AgentState`；`StateModule`/`StatePersistence` 删除；`ToolkitState`→`...state.legacy.ToolkitState`。
- `ReActAgent.getCurrentSessionId()/getCurrentUserId()` → `RuntimeContext.getSessionId()/getUserId()`；`getState()`→`getAgentState()`；`stream(...)`（`Flux<Event>`）`@Deprecated(forRemoval)` → `streamEvents(...)`（`Flux<AgentEvent>`）。
- artifact：`agentscope-extensions-session-redis` → `agentscope-extensions-redis`。
生产：`RuntimeContext` 必带 `userId+sessionId`；`DistributedStore` 一行取代 7 项分散配置；`.maxRetries`+`.fallbackModel` 原生重试。

---

## 11. pig-agent ↔ 原生 2.0 裁决（浓缩）
> 详见 `pig-agent-mapping.md`；源 `docs/planning/harness-capability-map-2026-07-16.md` + `av2/20260716-foundation-main:docs/planning/agentscope-v2-migration.md`

**REPLACE**（原生取代手搓 harness）：`PigAgent`→`HarnessAgent` · `SessionManager`/`JsonSession`→`AgentStateStore` · `CompositeLongTermMemory`→`MemoryConfig`（port `/memory` UX）· `CompressionService`→`CompactionConfig`（port `/compress`+lineage）· `RetryingModel`/`InterruptibleModel`→`.maxRetries`/`ReActAgent.interrupt()` · `ToolPermissionHook`+`PermissionDeniedTool`→`PermissionEngine`+`ToolResultState.DENIED`（Risk-2 PoC 已证等价）· `EphemeralMemoryContextHook`→`EphemeralMemoryMiddleware.onReasoning`（PoC 绿）· `AgentRegistry`/`AgentInstanceFactory`/`AgentSpec`→原生子 agent。
**KEEP**（pig 差异化，原生无）：CC 风格 REPL 渲染层 · `ModelManager`/`ProtocolRegistry` 多协议自有大脑 · 主动外呼渠道 · 工具安全正交层（`ToolContractGuard`/`SsrfGuard`/凭据黑名单）· 数字员工三段晨报（`AgentRunner`/`DeniedActionRecorder`）· `/ls:*` 流水线。
**ADAPT**：`McpManager`→声明式 `workspace/tools.json`（`registerMcpClient/removeMcpClient` 保留）· `SkillSource`/`SkillRegistry`→`AgentSkillRepository`。
**GAP→ADOPT**：exec-sandbox→`DockerFilesystemSpec` 真隔离(P3) · 大工具结果→`.toolResultEviction`。
分支：`main`=v1 稳定线（1015 测试绿）；`av2/20260716-foundation-main`=v2 迁移线（Phase 0-redux + Phase 1 叶模块绿）；唯一阻塞点 `pig-agent-session`（Phase 3，`Session` 已删）上游卡 `model`/`onboarding`；捷径=把 `AgentModelSwitcher` 接口移到 `pig-agent-core`。`ConversationMemory` 仅存在于迁移线，非 `main`。

---

## 12. Where to read more（主题 → 支撑文件 → 本地 doc）

| 主题 | 支撑文件 | 本地 doc（`D:\Users\admin\Documents\en\`） |
|---|---|---|
| Agent/Harness + builder + 调用 | `agent-and-harness.md` | `docs\building-blocks\agent.md` · `docs\harness\architecture.md` · `docs\quickstart.md` |
| 消息/事件/streamEvents | `events-streaming.md` | `docs\building-blocks\message-and-event.md` |
| Middleware 5 阶段 | `middleware.md` | `docs\building-blocks\middleware.md` |
| Subagent 编排 | `subagent.md` | `docs\harness\subagent.md` |
| Permission + Plan Mode + HITL | `permission-hitl.md` | `docs\building-blocks\permission-system.md` · `docs\harness\plan-mode.md` |
| State/Session/Workspace | `state-session.md` | `docs\building-blocks\context.md` · `docs\harness\workspace.md` · `integration\session\*` · `integration\distributed\*` |
| Memory + Compaction | `memory-compaction.md` | `docs\harness\memory.md` · `docs\harness\compaction.md` · `integration\memory\*` |
| Sandbox + Filesystem | `sandbox-filesystem.md` | `docs\harness\sandbox.md` · `docs\harness\filesystem.md` |
| Tools + MCP | `tools-mcp.md` | `docs\building-blocks\tool.md` |
| Skills + skill repos | `skills.md` | `docs\harness\skill.md` · `integration\skill\*` |
| Model providers | `model.md` | `docs\building-blocks\model.md` · `integration\model\*` |
| Channel/Gateway + 适配器 | `channel-gateway.md` | `docs\harness\channel.md` · `integration\channel\*` |
| 协议 A2A/AG-UI/Agent Protocol | `integrations.md` | `integration\protocol\*` |
| v1→v2 迁移 | `migration-v1-to-v2.md` | `docs\change-log.md` · `docs\others\{going-to-production,faq}.md` |
| pig-agent ↔ 原生映射 | `pig-agent-mapping.md` | `docs\planning\harness-capability-map-2026-07-16.md` |

> 遇到需 bytecode 确认的签名（本索引与支撑文件中标 `（需 javap 验证）`）：对已迁移的 pig-agent 类用 `javap -p` 核对；对 AgentScope jar 用 `javap -classpath <agentscope-*.jar> io.agentscope....`。
