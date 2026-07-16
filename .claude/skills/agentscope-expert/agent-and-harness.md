# Agent & Harness

## ReActAgent vs HarnessAgent

> 源: D:\Users\admin\Documents\en\docs\building-blocks\agent.md  
> 源: D:\Users\admin\Documents\en\docs\harness\architecture.md

| | `ReActAgent` | `HarnessAgent` |
|---|---|---|
| **包名** | `io.agentscope.core.ReActAgent` | `io.agentscope.harness.agent.HarnessAgent` |
| **Maven 模块** | `agentscope-core`（仅框架） | `agentscope-harness`（传递依赖 `agentscope-core`） |
| **定位** | 裸推理-行动循环引擎：model + tools + permission + HITL + state | `ReActAgent` 的薄封装；加了 workspace persona、长期记忆、compaction、subagent、sandbox、Plan Mode、skill 组合 |
| **何时选** | 只需一次请求→推理→工具→回复；无会话持久化需求 | 长期运行 agent：跨请求恢复、记忆沉淀、多用户隔离、沙盒 |

```xml
<!-- 推荐：直接用 HarnessAgent -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-harness</artifactId>
    <version>${agentscope.version}</version>
</dependency>

<!-- 只需裸 ReActAgent -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-core</artifactId>
    <version>${agentscope.version}</version>
</dependency>

<!-- 模型 provider 单独引入（按需） -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-dashscope</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

---

## ReActAgent Builder 完整 API

> 源: D:\Users\admin\Documents\en\docs\building-blocks\agent.md

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;

ReActAgent agent = ReActAgent.builder()
        .name("my_agent")                          // String — required；用于日志和消息标识
        .sysPrompt("You are a helpful assistant.") // String — required
        .model("dashscope:qwen-plus")              // String 形式（ModelRegistry 解析）
        // 或 .model(DashScopeChatModel.builder()...build()) — 显式 Model 实例
        .toolkit(new Toolkit())                    // Toolkit — default new Toolkit()
        .stateStore(new JsonFileAgentStateStore(   // AgentStateStore — default null（无持久化）
                Paths.get(System.getProperty("user.home"), ".agentscope/sessions")))
        .defaultSessionId("default")              // String — fallback sessionId，default = agent name
        .permissionContext(PermissionContextState.DEFAULT) // PermissionContextState
        .modelConfig(ModelConfig.builder()...build())      // ModelConfig（重试 + fallback，见下）
        .reactConfig(ReactConfig.builder()...build())      // ReactConfig（maxIters + reject handling）
        .maxIters(10)                              // int — ReAct 循环最大迭代次数，default 10
        .maxRetries(3)                             // int — 模型调用失败自动重试
        .fallbackModel("dashscope:qwen-max")       // String — 连续失败后切换的备用模型
        .skillRepository(new MysqlSkillRepository(dataSource)) // SkillRepository
        .enableMetaTool(true)                      // 注册 list_tools / activate_group 元工具
        .enableTaskList()                          // 注册任务分解追踪工具
        // .middlewares(List.of(new MyMiddleware())) // List<? extends MiddlewareBase>
        .build();
```

### Builder 字段速查表

| 方法 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `name(String)` | `String` | required | Agent 标识符 |
| `sysPrompt(String)` | `String` | required | 基础系统提示 |
| `model(String)` | `String` | required | `<provider>:<model>` 格式，ModelRegistry 解析 |
| `model(Model)` | `Model` | required | 显式 Model 实例（精细控制 timeout/endpoint） |
| `toolkit(Toolkit)` | `Toolkit` | `new Toolkit()` | 管理工具、MCP 客户端、技能、工具组 |
| `middlewares(List<? extends MiddlewareBase>)` | `List` | `List.of()` | agent/reasoning/acting/model-call/sysPrompt 钩子 |
| `stateStore(AgentStateStore)` | `AgentStateStore` | `null` | 设置后每次 `call` 自动加载/保存 `AgentState` |
| `defaultSessionId(String)` | `String` | agent `name` | `RuntimeContext` 无 sessionId 时的 fallback |
| `permissionContext(PermissionContextState)` | `PermissionContextState` | `DEFAULT` | 工具执行权限规则 |
| `modelConfig(ModelConfig)` | `ModelConfig` | default | 模型重试 + fallback 模型 |
| `reactConfig(ReactConfig)` | `ReactConfig` | default | 最大迭代 + reject 处理 |
| `maxIters(int)` | `int` | `10` | ReAct 主循环上限（`reactConfig` 的快捷方式） |
| `maxRetries(int)` | `int` | — | 模型调用失败自动重试次数 |
| `fallbackModel(String)` | `String` | — | 连续失败后切换备用模型 |
| `skillRepository(SkillRepository)` | `SkillRepository` | — | 热加载 Markdown prompt 模块 |
| `enableMetaTool(boolean)` | `boolean` | false | 注册 `list_tools` / `activate_group` |
| `enableTaskList()` | — | — | 注册任务分解追踪工具 |

---

## HarnessAgent Builder 附加 API

> 源: D:\Users\admin\Documents\en\docs\harness\architecture.md  
> 源: D:\Users\admin\Documents\en\docs\quickstart.md

`HarnessAgent.builder()` 继承 `ReActAgent.builder()` 全部字段，并额外提供：

```java
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;

HarnessAgent agent = HarnessAgent.builder()
        .name("note-taker")
        .sysPrompt("You are a note-taking assistant.")
        .model("dashscope:qwen-plus")
        // --- Harness 特有方法 ---
        .workspace(Paths.get(".agentscope/workspace"))   // Path — workspace 根目录
        .compaction(CompactionConfig.builder()            // CompactionConfig — 对话压缩
                .triggerMessages(30)
                .keepMessages(10)
                .build())
        .memory(/* MemoryConfig — 自定义记忆提示/触发策略 */)           // （需 javap 验证确认参数类型）
        .stateStore(/* AgentStateStore 覆盖默认 */)
        .subagent(/* SubagentSpec — 声明子 agent */)                    // （需 javap 验证确认参数类型）
        .filesystem(/* FilesystemSpec，如 new DockerFilesystemSpec() */) // （需 javap 验证确认参数类型）
        .enablePlanMode()                                // 开启只读计划阶段 + HITL 退出
        .skillRepository(/* SkillRepository */)
        .toolResultEviction(/* ToolResultEvictionConfig */)             // （需 javap 验证确认参数类型）
        .middleware(/* MiddlewareBase — 在所有 Harness 内置 middleware 之前执行 */) // （需 javap 验证确认参数类型）
        .build();
```

### Harness 能力矩阵

| 能力 | Builder 钩子 | 说明 |
|------|-------------|------|
| Workspace persona | `.workspace(Path)` | `AGENTS.md`/`MEMORY.md`/`skills/`/`tools.json` 文件驱动 |
| 状态持久化 | 默认开启；`.stateStore(...)` 覆盖 | `(userId, sessionId)` 寻址；跨请求/进程/副本恢复 |
| 两层长期记忆 | 默认开启；`.memory(...)` 自定义 | 每步推理注入 `MEMORY.md` |
| 对话压缩 | `.compaction(CompactionConfig)` | 超阈值截断 + 强制重试 |
| 大 tool-result 卸载 | `.toolResultEviction(...)` | >80K 字符结果移磁盘+占位符 |
| Subagent 编排 | `.subagent(...)` 或 workspace 目录 | 同步/后台委托，自动推回 |
| 可插拔文件系统 | `.filesystem(...)` | 本地/共享存储/沙盒无需改代码 |
| 沙盒隔离 | `.filesystem(new DockerFilesystemSpec())` | 跨调用恢复，多副本 |
| Plan Mode | `.enablePlanMode()` | 只读思考阶段 + HITL 退出 |
| Skill 组合 | `.skillRepository(...)` | Git/Nacos/MySQL/classpath/workspace |
| 自定义 middleware | `.middleware(...)` | 运行顺序：用户 → Harness 内置 |

---

## 调用 API

> 源: D:\Users\admin\Documents\en\docs\building-blocks\agent.md

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;

// call — 阻塞到最终消息，返回 Mono<Msg>
Msg result = agent.call(List.of(new UserMessage("Hello")),
        RuntimeContext.builder().userId("alice").sessionId("s1").build()).block();
result.getTextContent();

// 结构化输出 overload：call(List<Msg>, Class<T>, RuntimeContext) → Mono<Msg>
Msg r2 = agent.call(List.of(new UserMessage("...")), WeatherResponse.class).block();
WeatherResponse data = r2.getStructuredData(WeatherResponse.class);
// 也可 call(List<Msg>, JsonNode) 传原始 JSON Schema

// streamEvents — 增量 AgentEvent 流，返回 Flux<AgentEvent>
agent.streamEvents(new UserMessage("Summarize."))
        .doOnNext(event -> {
            if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA)
                System.out.print(((TextBlockDeltaEvent) event).getDelta());
            else if (event.getType() == AgentEventType.TOOL_CALL_START)
                System.out.println("[tool] " + ((ToolCallStartEvent) event).getToolCallName());
        })
        .blockLast();

// observe — 注入消息不触发推理，返回 Mono<Void>
agent.observe(otherAgentMsg).block();

// interrupt — 取消进行中的 call（per-session）
agent.interrupt(RuntimeContext.builder().userId("alice").sessionId("s1").build());
agent.interrupt("alice", "s1");
agent.interrupt("alice", "s1", new UserMessage("User cancelled"));

// getAgentState — 检查会话状态
AgentState state = agent.getAgentState("alice", "session-001");
state.getContext().size();
String json = state.toJson();
```

---

## RuntimeContext

> 源: D:\Users\admin\Documents\en\docs\building-blocks\agent.md

**每次调用**的元数据袋，非持久化（持久化是 `AgentState` 的职责）。

```java
RuntimeContext ctx = RuntimeContext.builder()
        .userId("alice")                                    // 可选；null = 匿名
        .sessionId("session-001")                           // 选定状态槽
        .put("request_id", "req-abc-123")                   // String 属性层（key→Object）
        .put(UserContext.class, new UserContext("alice", "en")) // 类型属性层（Class<T>→T）
        .build();
```

- 同一 `(userId, sessionId)` 的调用**串行化**；不同 session 并行。
- `@Tool` 方法参数中声明对应类型，框架自动注入 typed attribute。
- `RuntimeContext.empty()` = null session fields + 空属性 map，fallback 到 builder 的 `defaultSessionId`。

---

## AgentState 持久化

> 源: D:\Users\admin\Documents\en\docs\building-blocks\agent.md

| 实现 | 模块 | 适用场景 |
|------|------|----------|
| `InMemoryAgentStateStore` | `agentscope-core` | 单元测试/单进程 demo |
| `JsonFileAgentStateStore` | `agentscope-core` | 单机开发；JSON 文件按 `(userId, sessionId)` 目录存储 |
| `RedisAgentStateStore` | `agentscope-extensions-redis` | 多副本生产；跨进程/节点共享 |
| `MysqlAgentStateStore` | `agentscope-extensions-mysql` | 需关系型存储（审计/报告） |

`AgentState` 默认存放于 `~/.agentscope/state/<agentId>/`（`HarnessAgent` 默认行为）；**在 workspace 之外**，因为恢复 workspace 本身需要先有 state。

---

## Harness 状态三层流

> 源: D:\Users\admin\Documents\en\docs\harness\architecture.md

- **调用内**：`AgentState`（对话上下文、权限规则、Plan Mode 状态、tool 状态）+ `RuntimeContext`
- **跨调用**：每次 `call()` 结束自动保存，下次同 `(userId, sessionId)` 自动加载；完整对话日志 `sessions/<sessionId>.log.jsonl` 永不压缩
- **长期记忆**：`memory/YYYY-MM-DD.md`（append-only）→ 后台 merge 到 `MEMORY.md` → 每次推理步骤注入系统提示

关键不变式：
- 系统提示每步推理**重建**，编辑 `AGENTS.md`/`MEMORY.md` 立即生效，无需重启。
- `AgentState` 由 `ReActAgent` + `AgentStateStore` 持久化；Harness 不再额外添加持久化钩子。
- 自定义 `.middleware(...)` 在所有 Harness 内置 middleware **之前**运行。
