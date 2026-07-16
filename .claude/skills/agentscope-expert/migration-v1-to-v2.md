# v1 → v2 Migration

> 源: D:\Users\admin\Documents\en\docs\change-log.md（主迁移指南）、others/going-to-production.md、others/faq.md、others/release-notes.md

---

## Part A — 必须迁移（编译或运行时报错）

### A.1 `ReActAgent.Builder` 已删除方法

| v1 方法 | v2 替换 |
|---------|---------|
| `.memory(Memory)` | `.stateStore(AgentStateStore)` — 对话历史存于 `AgentState.getContext()`，每次 `call()` 按 `(userId, sessionId)` 自动保存/加载 |
| `.statePersistence(StatePersistence)` | 同上，`AgentStateStore` 包含持久化语义 |
| `.structuredOutputReminder(StructuredOutputReminder)` | 已移除，结构化输出由模型层原生处理（`Model.supportsNativeStructuredOutput()`），框架自动选 native JSON schema 或 tool-choice 降级 |

### A.2 已删除包与类

| v1 标识符 | v2 处理 |
|-----------|---------|
| `io.agentscope.core.session.SessionManager` | 在 builder 上配置 `.stateStore(AgentStateStore)`，框架按 `(userId, sessionId)` 自动持久化 |
| `io.agentscope.core.pipeline.*`（`Pipeline`, `Pipelines`, `SequentialPipeline`, `FanoutPipeline`, `MsgHub`） | 用 Middleware + 子 Agent + 事件流做多 Agent 编排；见 Subagent 指南 |
| `io.agentscope.core.model.tts.*`（14 个文件，DashScope TTS / Realtime TTS / `AudioPlayer` 等） | core 不再内置 TTS；直接接入上游 provider SDK |
| `io.agentscope.core.model.StructuredOutputReminder` | 已删除，功能内联到模型层 |
| `io.agentscope.core.agent.StructuredOutputCapableAgent` | 已删除，能力内联到 `ReActAgent` |
| `io.agentscope.core.hook.PendingToolRecoveryHook` | 改用 `Builder.enablePendingToolRecovery(boolean)` |
| `io.agentscope.core.hook.TTSHook` | 随 TTS 模块一并删除 |

### A.3 模型 provider 移出 core（**最高频踩坑**）

所有 provider 实现从 `agentscope-core` 移入独立 extension 模块；同时 Spring Boot 应使用对应 starter。

| v1 导入 / 依赖 | v2 依赖 + 新导入包 |
|----------------|-------------------|
| `io.agentscope.core.model.OpenAIChatModel` | 加 `agentscope-extensions-model-openai`；`io.agentscope.extensions.model.openai.OpenAIChatModel` |
| `io.agentscope.core.model.GeminiChatModel` | 加 `agentscope-extensions-model-gemini`；`io.agentscope.extensions.model.gemini.GeminiChatModel` |
| `io.agentscope.core.model.AnthropicChatModel` | 加 `agentscope-extensions-model-anthropic`；`io.agentscope.extensions.model.anthropic.AnthropicChatModel` |
| `io.agentscope.core.model.DashScopeChatModel` | 加 `agentscope-extensions-model-dashscope`；`io.agentscope.extensions.model.dashscope.DashScopeChatModel` |
| `io.agentscope.core.model.OllamaChatModel` | 加 `agentscope-extensions-model-ollama`；`io.agentscope.extensions.model.ollama.OllamaChatModel` |
| `io.agentscope.core.formatter.<provider>.*` | `io.agentscope.extensions.model.<provider>.formatter.*` |
| `io.agentscope.core.credential.<Provider>Credential` | `io.agentscope.extensions.model.<provider>.credential.<Provider>Credential` |

`ModelRegistry` 字符串 id（`"dashscope:qwen-plus"`）在对应 extension 模块上 classpath 时仍可用：

```java
// v2 ——字符串 id 仍有效，只要 extension 在 classpath
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("dashscope:qwen-plus")
    .build();
```

Spring Boot starters（代替旧的 core 模型路径）：

| Provider | Spring Boot Starter |
|----------|---------------------|
| OpenAI | `agentscope-openai-spring-boot-starter` |
| DashScope | `agentscope-dashscope-spring-boot-starter` |
| Gemini | `agentscope-gemini-spring-boot-starter` |
| Anthropic | `agentscope-anthropic-spring-boot-starter` |
| Ollama | `agentscope-ollama-spring-boot-starter` |

### A.4 `state` 包重构（编译错误）

| v1 | v2 |
|----|-----|
| `AgentMetaState` | `AgentState` |
| `StateModule` | **已删除**，不再作为 `Memory`/`Toolkit` 等的超类 |
| `StatePersistence` | **已删除**，由 `AgentStateStore` 抽象替代 |
| `ToolkitState`（`io.agentscope.core.state`） | 移至 `io.agentscope.core.state.legacy.ToolkitState`，仅兼容保留，新代码禁止引用 |
| （新增） | `Task`, `TaskContextState`, `ToolContextState`, `PlanModeContextState`, `ReadCacheEntry` |

### A.5 `PlanNotebook` 完全移除

整个 `io.agentscope.core.plan` 包（`PlanNotebook`, `Plan`, `SubTask`, `PlanStorage`, `PlanToHint`）**无桥接**地删除。

| v1 `PlanNotebook` | v2 Plan Mode |
|-------------------|--------------|
| `ReActAgent.builder().planNotebook(PlanNotebook.builder().build())` | `HarnessAgent.builder().enablePlanMode()` |
| 结构化 `Plan` + `SubTask` 状态机（8 个 tool） | 普通 Markdown 文件 `plans/PLAN.md`（3 个 tool：`plan_enter` / `plan_write` / `plan_exit`） |
| 计划与执行交织，无只读限制 | Plan Mode 只读；`plan_exit` 触发 HITL 门 |
| `PlanToHint` 每步注入提示 | `PlanModeMiddleware` 阻断写工具 |

v1 子任务状态跟踪 → v2 改用 `.enableTaskList(true)`（注册 `TodoTools` + `TaskReminderMiddleware`）。

### A.6 `Msg` 内容校验更严格（运行时异常）

`Msg` 在构造时按 role 校验 content：
- `USER` — 只允许 `TextBlock` / `DataBlock` / `ImageBlock` / `AudioBlock` / `VideoBlock`
- `SYSTEM` — 只允许 `TextBlock`
- `ASSISTANT` — 无限制

v1 允许的非法组合（如 `USER` 携带 `ToolUseBlock`）现在构造即抛异常。推荐使用角色固定子类：`UserMessage` / `AssistantMessage` / `SystemMessage` / `ToolResultMessage`。

### A.7 Agent 完全无状态（架构变更）

`ReActAgent` 实例不再持有任何可变的"当前会话"状态；所有 per-call 可变状态封装在内部 `CallExecution`，通过 Reactor Context 传播。

| v1 已删除 | v2 替换 |
|----------|---------|
| `ReActAgent.getCurrentSessionId()` | 通过 `call()` 时传入的 `RuntimeContext.getSessionId()` |
| `ReActAgent.getCurrentUserId()` | 通过 `RuntimeContext.getUserId()` |
| `AgentBase(name, desc, checkRunning, hooks)` 构造器 | `AgentBase(name, desc, hooks)` — `checkRunning` 不再需要，per-session 串行化保证并发安全 |
| `ReActAgent.getState()` | `ReActAgent.getAgentState()` 或 `getAgentState(userId, sessionId)` |

`isCheckRunning()` / `Builder.checkRunning(boolean)` 仍可调用（`@Deprecated`），前者返回 `false`，后者被忽略。

---

## Part B — 推荐迁移（`@Deprecated(forRemoval = true)`，当前仍可编译运行）

### B.1 `SkillBox` → skill repositories

- `SkillBox` 类和 `Builder.skillBox(SkillBox)` 均标 `@Deprecated(forRemoval = true, since = "2.0.0")`
- 替换：`Builder.skillRepository(AgentSkillRepository)` / `.skillRepositories(...)`，内置：`ClasspathSkillRepository`、`FileSystemSkillRepository`；注册后 `DynamicSkillMiddleware` 自动安装并在每次 `call()` 前重建 skill prompt
- 细粒度过滤：`Builder.skillFilter(SkillFilter)`

### B.2 Hook → Middleware

`io.agentscope.core.hook` 整包（`Hook`、`HookEvent`、`HookEventType`、所有 `*Event`）标 `@Deprecated(forRemoval = true, since = "2.0.0")`；`Builder.hook(...)` / `.hooks(...)` 通过 `LegacyHookDispatcher` 保持可调。

v2 扩展接口为 `io.agentscope.core.middleware.MiddlewareBase`，五个阶段：
- **onion 型**：`onAgent` / `onReasoning` / `onActing` / `onModelCall`
- **pipeline 型**：`onSystemPrompt`

Builder 方法：`.middleware(MiddlewareBase)` / `.middlewares(List<? extends MiddlewareBase>)`。

### B.3 `Memory` → `AgentStateStore` + `AgentState`

- `io.agentscope.core.memory.Memory` 接口及所有实现（`InMemoryMemory`、`LongTermMemory` 等）标 `@Deprecated(forRemoval = true, since = "2.0.0")`
- `Memory` 不再继承 `StateModule`；增加 `saveTo(AgentStateStore, userId, sessionId)` / `loadFrom(AgentStateStore, userId, sessionId)` 桥接方法
- v2 推荐模型：
  - 对话历史 → `AgentState.getContext()`
  - 持久化 → `AgentStateStore`（内置：`InMemoryAgentStateStore`、`JsonFileAgentStateStore`）
  - Builder 链：`.stateStore(AgentStateStore)`

### B.4 `stream()` → `streamEvents()`

`StreamableAgent.stream(...)`（11 个重载）、`AgentBase.stream(...)`（3 个）、`ReActAgent.stream(..., RuntimeContext)`（4 个）、`HarnessAgent.stream(...)`（9 个）均标 `@Deprecated(forRemoval = true)`。

```java
// v1 旧写法（已废弃）
agent.stream(new Msg(Role.USER, "Hello")).doOnNext(e -> { ... }).blockLast();

// v2 新写法
agent.streamEvents(new UserMessage("Hello"))
        .doOnNext(event -> {
            if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
                System.out.print(((TextBlockDeltaEvent) event).getDelta());
            }
        })
        .blockLast();
```

`io.agentscope.core.agent.Event`、`EventType`、`EventSource` 软废弃（暂无 `forRemoval`）；等待下游模块（`SubAgentTool`、AG-UI、A2A）迁移到 `AgentEvent` 后再翻。

**当前 gap**：`HarnessAgent.streamEvents(...)` 尚不转发子 Agent 事件——`AgentEvent` 体系还没有对应的 `EventSource` 通道；需要子 Agent 流的调用方须暂留在废弃的 `stream(...)` 路径。

### B.5 RAG 模块（进行中）

`Knowledge`、`KnowledgeRetrievalTools`、`RAGMode`、`GenericRAGHook` 及对应 Builder 方法（`.knowledge(...)`/`.knowledges(...)`/`.ragMode(...)`/`.retrieveConfig(...)`）全部标 `@Deprecated(forRemoval = true, since = "2.0.0")`。v2 重写进行中，新代码禁止依赖当前 API。

### B.6 长期记忆模块（进行中）

`LongTermMemory`、`LongTermMemoryMode`、`LongTermMemoryTools` 及 Builder 方法（`.longTermMemory(...)`/`.longTermMemoryMode(...)`/`.longTermMemoryAsyncRecord(...)`）全部标 `@Deprecated(forRemoval = true, since = "2.0.0")`。

### B.7 核心 shell / 文件工具不再废弃

`io.agentscope.core.tool.coding.*`（`ShellCommandTool`、`CommandValidator`、`UnixCommandValidator`、`WindowsCommandValidator`）和 `io.agentscope.core.tool.file.*`（`ReadFileTool`、`WriteFileTool`、`FileToolUtils`）**自 RC1 起撤销废弃标注**，是 `ReActAgent` 场景下的推荐 shell/文件访问方式。`HarnessAgent` 场景推荐使用 harness 内置工具（`read_file`、`write_file`、`execute` 等）。

---

## Maven artifact 坐标变更

> 源: D:\Users\admin\Documents\en\docs\others\release-notes.md（RC2 Breaking Changes）

| v1 旧坐标 | v2 新坐标 |
|-----------|-----------|
| `agentscope-extensions-session-redis` | `agentscope-extensions-redis`（捆绑 `RedisAgentStateStore`、`RedisStore`、`RedisSnapshotSpec` 等） |
| `agentscope-core`（含 provider 类） | `agentscope-core` + 各 `agentscope-extensions-model-*` |
| `agentscope-harness`（含 sandbox 实现） | `agentscope-harness`（纯接口）+ `agentscope-extensions-sandbox-docker` 等独立模块 |

Sandbox 模块独立拆出（RC2）：

| Sandbox | 新 artifactId |
|---------|---------------|
| Docker | `agentscope-extensions-sandbox-docker` |
| Kubernetes | `agentscope-extensions-sandbox-kubernetes` |
| E2B | `agentscope-extensions-sandbox-e2b` |
| Daytona | `agentscope-extensions-sandbox-daytona` |
| AgentRun | `agentscope-extensions-sandbox-agentrun` |

---

## 生产部署要点

> 源: D:\Users\admin\Documents\en\docs\others\going-to-production.md

### 最快上线路径

```java
// 一行配置全部分布式组件
DistributedStore store = RedisDistributedStore.fromJedis(jedis);
// 或 MysqlDistributedStore.create(dataSource)
// 或 OssDistributedStore.create(ossClient, bucket, prefix)

HarnessAgent.builder()
    .distributedStore(store)   // 自动注入 stateStore + snapshotSpec + executionGuard
    .filesystem(new DockerFilesystemSpec()
            .image("python:3.12-slim")
            .isolationScope(IsolationScope.USER))
    .build();
```

### 会话隔离黄金规则

每次 `call()` **必须**传 `RuntimeContext`，否则所有请求共享 `defaultSessionId` 状态导致串话：

```java
agent.call(msg, RuntimeContext.builder()
        .userId(tenantId + ":" + userId)
        .sessionId(agentId + ":" + sessionId)
        .build()).block();
```

### 关键组件选型

| 关注点 | 推荐方案 |
|--------|---------|
| `AgentStateStore` | `RedisAgentStateStore`（多副本生产默认） |
| 文件共享 | `RemoteFilesystemSpec` + `BaseStore`（Redis/MySQL） |
| 大对象 / sandbox 快照 | `OssSnapshotSpec`（OSS 不适合做 `BaseStore`——延迟高、费用高） |
| 技能治理 | `MysqlSkillRepository(writeable=false)` 或 `NacosSkillRepository`（`AutoCloseable`，注意关闭） |
| 可观测性 | `OtelTracingMiddleware` + OpenTelemetry SDK + OTLP exporter |
| 优雅关闭 | `GracefulShutdownManager`（自动注册 JVM hook）；处理 SIGTERM |

### 常见陷阱

- 用 `java.nio.Files` 写文件：sandbox / Remote 模式下写到错误位置；务必通过 `agent.getWorkspaceManager()`
- `tools.json` 的 `allow` 过滤内置工具：白名单时保留 `read_file` / `memory_search` / `agent_spawn` 等
- `IsolationScope` 上线后不得修改：改动等价于切换命名空间，已有数据不迁移
- `LocalFilesystemSpec` + 分布式 filesystem = `build()` 直接 `IllegalStateException`（故意设计）
- `filesystem(RemoteFilesystemSpec)` 未配 `stateStore` 或 `distributedStore` → `build()` 抛 `IllegalStateException`
- `filesystem(SandboxFilesystemSpec)` + 本地 `AgentStateStore` → `build()` 打 **warning**，生产必须补分布式 store

---

## FAQ 迁移相关

> 源: D:\Users\admin\Documents\en\docs\others\faq.md

- **2.0 是否兼容 1.0？** 目标尽量兼容，但存在 API 级 breaking change（agent 抽象重设计 + 事件系统 + 权限系统 + Middleware 栈）；见上文 Part A。
- **RAG / 长期记忆何时到位？** `io.agentscope.core.rag` 和 `LongTermMemory` 代码已存在但未完成，后续小版本迭代补齐；现阶段 v1 实现可运行，但不应在新代码中依赖。
- **前端 UI？** `agentscope-admin` 模块开箱即用，对接 `AgentEvent` 体系和权限系统 HITL 流。

---

## 需 javap 验证的签名

- `AgentBase(name, desc, hooks)` 构造器的精确参数类型（`List<Hook>` 还是 `List<MiddlewareBase>`）（需 javap 验证）
- `HarnessAgent.streamEvents(Msg/List<Msg>[, RuntimeContext])` 四个重载的确切方法签名（需 javap 验证）
- `MemoryConfig.builder().model(String)` / `CompactionConfig.builder().model(String)` 的精确 builder 链（文档给出示例，签名需 javap 验证）
