# State, Session & Workspace

> 本文档提炼自 AgentScope Java 2.0 官方文档，涵盖状态模型、工作空间、会话持久化与分布式存储。

---

## 1. 无状态引擎 + AgentState 模型

> 源: D:\Users\admin\Documents\en\docs\building-blocks\context.md

`ReActAgent`（以及包装它的 `HarnessAgent`）是**无状态引擎**：实例本身只持有不可变配置（系统提示、模型、工具链、中间件链），所有可变的会话数据存放在 `AgentState` 中，以 `(userId, sessionId)` 为索引。同一个实例可并发服务多用户/多会话。

### AgentState 字段

`AgentState`（`io.agentscope.core.state.AgentState`）是每次 `call()` 结束后写入 store 的完整快照：

| 字段 | 说明 |
|---|---|
| `getSessionId()` | 所属 session |
| `getUserId()` | 用户 id（匿名可为 null）|
| `getContext()` / `contextMutable()` | 对话历史（user/assistant/tool call/tool result）|
| `getSummary()` | 压缩摘要（开启 compaction 后使用）|
| `getPermissionContext()` | 工具权限规则 |
| `getPlanModeContext()` | Plan Mode 状态 + 当前 plan 文件路径 |
| `getTasksContext()` | `todo_write` 任务列表 |
| `getToolContext()` | 激活的工具组 (`activatedGroups`) |

每次 `call()` 结束写入 store，下次相同 `(userId, sessionId)` 的 `call()` 自动加载。**`InterruptControl` 是运行时信号，标注 `@JsonIgnore transient`，不序列化到 store。**

```java
// 序列化 / 反序列化
String json = state.toJson();
AgentState restored = AgentState.fromJsonString(json);

// 直接读取（绕过 agent 循环，适合管理/审计）
AgentState state = agent.getAgentState("alice", "session-001");
```

### AgentStateStore 实现清单

`io.agentscope.core.state.AgentStateStore` 是持久化接口，内置四种实现：

| 实现类 | artifact | 场景 |
|---|---|---|
| `InMemoryAgentStateStore` | `agentscope-core` | 单元测试，进程退出即丢失 |
| `JsonFileAgentStateStore` | `agentscope-core` | **HarnessAgent 默认**，本地文件，默认路径 `~/.agentscope/state/<agentId>/`，单机 |
| `RedisAgentStateStore` | `agentscope-extensions-redis` | 多副本生产首选 |
| `MysqlAgentStateStore` | `agentscope-extensions-mysql` | 已有关系库 / 需 SQL 审计 |
| `OssAgentStateStore` | `agentscope-extensions-oss` | 阿里云 OSS 生态 |

Builder 接入一行：

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("MyAgent")
    .model(model)
    .workspace(workspace)
    .stateStore(new RedisAgentStateStore(...))   // 切换 store
    .build();
```

> 注意：配置了 `filesystem(SandboxFilesystemSpec)` 或 `filesystem(RemoteFilesystemSpec)` 却不换分布式 store，`build()` 会抛 `IllegalStateException`。

### RuntimeContext — 每次 call 的元数据载体

`RuntimeContext`（`io.agentscope.core.agent.RuntimeContext`）是传入 `agent.call(msgs, ctx)` 的轻量载体：

```java
RuntimeContext ctx = RuntimeContext.builder()
    .userId("alice")
    .sessionId("s-001")
    .put("request_id", "req-abc")
    .build();
Msg result = agent.call(List.of(new UserMessage("Hi")), ctx).block();
```

- `(userId, sessionId)` 决定从 store 中加载/写入哪个 `AgentState` 槽位。
- call 入口时框架将 `AgentState` 注入：`rc.setAgentState(state)`，中间件和工具通过 `ctx.getAgentState()` 获取**本次 call 的** state，而非全局 `agent.getAgentState()`（并发安全关键点）。
- 静态辅助方法 `RuntimeContext.resolveAgentState(ctx, agent)` 优先取 `ctx` 内注入值，在中间件/工具中应使用此方法。
- 非 `(userId, sessionId)` 字段（如 `request_id`）**不序列化到 store**，仅作用于本次 call。

---

## 2. Workspace 工作空间

> 源: D:\Users\admin\Documents\en\docs\harness\workspace.md

Workspace 是 `HarnessAgent` 的**代理定义与长期演化的 Single Source of Truth**，存储为一棵 Markdown/JSON 目录树。`AgentState` 不在 workspace 内，它存放在独立的 `AgentStateStore`（默认 `~/.agentscope/state/<agentId>/`，在 workspace 树之外）。

### 目录布局（逻辑布局，与后端无关）

```
.agentscope/workspace/
├── AGENTS.md               ← 静态：人设 + 行为规则（唯一必须手写的文件）
├── MEMORY.md               ← 长期：精炼后的长期记忆，每轮注入 system prompt
├── tools.json              ← 静态：工具 allow/deny + MCP 服务声明
├── memory/YYYY-MM-DD.md    ← 长期：追加式每日事实日志
├── knowledge/KNOWLEDGE.md  ← 静态：领域知识入口，全文注入 prompt
├── skills/<name>/SKILL.md  ← 静态：可复用能力包
├── subagents/<agent-id>.md ← 静态：子 Agent 声明
├── plans/PLAN.md           ← 运行时：Plan Mode 计划文件
└── agents/<agentId>/
    ├── sessions/            ← 运行时：会话索引 + 永不压缩的原始 JSONL 日志
    └── tasks/               ← 运行时：子 Agent 后台任务记录
```

### Builder 配置

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("MyAgent")
    .model(model)
    .workspace(Paths.get(".agentscope/workspace"))  // 省略则默认 ${user.dir}/.agentscope/workspace
    .additionalContextFile("SOUL.md")               // workspace 相对路径，全文注入 system prompt
    .maxContextTokens(8000)                         // MEMORY.md 注入的 token 预算
    .build();
```

### IsolationScope（多租户隔离）

| `IsolationScope` | 共享粒度 | 典型场景 |
|---|---|---|
| `SESSION` | 每个 sessionId 完全隔离 | 一次性沙箱 |
| `USER`（默认）| 同一 userId 的所有 session 共享 | 用户长期记忆/技能共享 |
| `AGENT` | 所有用户/session 共享 | 共享知识库 Agent |
| `GLOBAL` | 整个 store 共享 | 谨慎使用 |

Workspace 支持三种文件系统后端，布局不变：本地磁盘（默认）、远端 KV store（`RemoteFilesystemSpec`）、沙箱容器（`SandboxFilesystemSpec` / `DockerFilesystemSpec`）。切换后端不改 Agent 代码。

---

## 3. Session 持久化：各 Store 实现

### 3.1 Redis（`agentscope-extensions-redis`）

> 源: D:\Users\admin\Documents\en\integration\session\redis.md  
> 源: D:\Users\admin\Documents\en\integration\distributed\redis.md

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-redis</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

支持 Jedis / Lettuce / Redisson，覆盖 Standalone / Cluster / Sentinel：

```java
// Jedis
AgentStateStore store = RedisAgentStateStore.builder()
    .jedisClient(new JedisPooled("redis://localhost:6379"))
    .keyPrefix("myapp:session:")
    .build();

// Lettuce Cluster
AgentStateStore store = RedisAgentStateStore.builder()
    .lettuceClusterClient(RedisClusterClient.create(RedisURI.create("localhost", 7000)))
    .build();
```

Redis key 格式：`{prefix}{userSegment}/{sessionId}:{stateKey}`，匿名用户 `userSegment` 为 `__anon__`。

### 3.2 MySQL（`agentscope-extensions-mysql`）

> 源: D:\Users\admin\Documents\en\integration\session\mysql.md  
> 源: D:\Users\admin\Documents\en\integration\distributed\mysql.md

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-mysql</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

```java
// 第二个参数 createIfNotExist=true 自动建库建表
AgentStateStore store = new MysqlAgentStateStore(dataSource, true);

// 自定义库名/表名
AgentStateStore store = new MysqlAgentStateStore(
    dataSource, "agentscope_prod", "session_state", true);
```

默认表名 `agentscope_sessions`，`session_id` 列存储 `{userId}:{sessionId}`（匿名为 `__anon__:{sessionId}`）。

### 3.3 Alibaba Cloud OSS（`agentscope-extensions-oss`）

> 源: D:\Users\admin\Documents\en\integration\session\oss.md  
> 源: D:\Users\admin\Documents\en\integration\distributed\oss.md

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-oss</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

```java
OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
AgentStateStore store = OssAgentStateStore.builder()
    .ossClient(ossClient)
    .bucketName("my-agentscope-bucket")
    .keyPrefix("agentscope/state/")
    .build();
```

OSS object 路径：`{keyPrefix}{userId}/{sessionId}/{stateKey}.json`，匿名用户 `userId` 为 `__anon__`。

---

## 4. 分布式 Store（DistributedStore）

> 源: D:\Users\admin\Documents\en\integration\distributed\index.md

`DistributedStore` 接口**一行配置**同时覆盖四个分布式能力组件：

| 组件 | 接口 | Redis | MySQL | OSS |
|---|---|:---:|:---:|:---:|
| Agent 状态持久化 | `AgentStateStore` | `RedisAgentStateStore` | `MysqlAgentStateStore` | `OssAgentStateStore` |
| Workspace 文件系统 KV | `BaseStore` | `RedisStore` | `JdbcStore` | `OssBaseStore` |
| 沙箱快照 | `SandboxSnapshotSpec` | `RedisSnapshotSpec` | `JdbcSnapshotSpec` | `OssSnapshotSpec` |
| 沙箱并发锁 | `SandboxExecutionGuard` | `RedisSandboxExecutionGuard` | `JdbcSandboxExecutionGuard` | — （OSS 不支持）|

一行设置（Redis 示例）：

```java
DistributedStore store = RedisDistributedStore.fromJedis(
    new JedisPooled("redis://localhost:6379"));

HarnessAgent agent = HarnessAgent.builder()
    .name("my-agent")
    .model(model)
    .distributedStore(store)
    .filesystem(new RemoteFilesystemSpec()
            .isolationScope(IsolationScope.USER))
    .build();
```

混合 store（MySQL 存状态/文件，Redis 处理沙箱锁）：

```java
DistributedStore mysql = MysqlDistributedStore.create(dataSource);
DistributedStore redis = RedisDistributedStore.fromJedis(jedis);

DistributedStore mixed = DistributedStore.builder()
    .agentStateStore(mysql.agentStateStore())
    .baseStore(mysql.baseStore())
    .sandboxSnapshotSpec(redis.sandboxSnapshotSpec())
    .sandboxExecutionGuard(redis.sandboxExecutionGuard())
    .build();
```

优先级：显式 `.stateStore(...)` > `distributedStore(...)` 自动注入 > 本地默认（`JsonFileAgentStateStore` 等）。

---

## 关键要点速查

- **AgentState 写入时机**：每次 `call()` 结束一次性写入（不是每条消息），吞吐压力低。
- **跨节点自动恢复**：使用分布式 store 后，不同进程/机器对同一 `(userId, sessionId)` 的首次 `call()` 即可继续中断的会话。
- **Workspace ≠ AgentState**：会话日志（`agents/<agentId>/sessions/*.jsonl`）在 workspace；`AgentState` 在独立的 `AgentStateStore`，默认路径 `~/.agentscope/state/<agentId>/`。
- **1.x `Memory` 接口**（`InMemoryMemory` / `LongTermMemory` 等）已在 2.0 标记 `@Deprecated(forRemoval = true)`；新代码用 `AgentState.getContext()` + `AgentStateStore`。
