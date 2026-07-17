# Sandbox & Filesystem

> 源: D:\Users\admin\Documents\en\docs\harness\sandbox.md
> 源: D:\Users\admin\Documents\en\docs\harness\filesystem.md

---

## 整体抽象

`HarnessAgent` 通过统一 `AbstractFilesystem` 接口抽象工作空间，所有文件工具
（`read_file` / `write_file` / `edit_file` / `grep_files` / `glob_files` / `list_files`）
及可选 shell（`execute`）均通过此接口路由。

挂载方式（二选一，互斥）：

```java
HarnessAgent.builder()
    .filesystem(spec)          // 三种预置模式之一
    // 或
    .abstractFilesystem(myFs)  // 完全自管，极少用
```

---

## 三种部署模式

| # | 类 | Shell | 典型场景 |
|---|----|-------|---------|
| 1 | `RemoteFilesystemSpec` | 无 | 多副本共享 KV（MEMORY.md / 对话日志 / 任务）|
| 2 | `DockerFilesystemSpec` / `KubernetesFilesystemSpec` / `E2bFilesystemSpec` / `DaytonaFilesystemSpec` / `AgentRunFilesystemSpec` | 沙箱内 | 隔离执行、跨调用恢复 |
| 3 | `LocalFilesystemSpec`（默认，可省略） | 宿主机 `sh -c` | 单进程本地可信环境 |

---

## Mode 1：共享存储（RemoteFilesystemSpec）

```java
import io.agentscope.harness.filesystem.RemoteFilesystemSpec;
import io.agentscope.extensions.redis.RedisDistributedStore;

DistributedStore store = RedisDistributedStore.fromJedis(jedis);

HarnessAgent agent = HarnessAgent.builder()
        .name("store-agent")
        .model(model)
        .workspace(workspace)
        .distributedStore(store)                     // 一行注入 stateStore + baseStore
        .filesystem(new RemoteFilesystemSpec()
                .isolationScope(IsolationScope.USER))
        .build();
```

内置路由规则（框架自动处理）：

| 路径 | KV namespace 段 |
|------|----------------|
| `AGENTS.md`、`MEMORY.md`、`tools.json` | `root` |
| `memory/` | `memory` |
| `skills/` | `skills` |
| `subagents/` | `subagents` |
| `knowledge/` | `knowledge` |
| `agents/<agentId>/sessions/` | `sessions` |
| `agents/<agentId>/tasks/` | `tasks` |

表外路径 fallback 到本地 `LocalFilesystem`（无 shell）。
**此模式无 shell**，需 shell 请用模式 2 或 3。

`BaseStore` 实现：`RedisStore`（`agentscope-extensions-redis`）、`JdbcStore`（`agentscope-extensions-mysql`）、`InMemoryStore`（测试用）。

---

## Mode 2：沙箱（SandboxFilesystemSpec 体系）

### Docker（最常用）

```java
import io.agentscope.harness.filesystem.DockerFilesystemSpec;
import io.agentscope.harness.filesystem.LocalSnapshotSpec;

HarnessAgent agent = HarnessAgent.builder()
        .name("code-agent")
        .model(model)
        .workspace(workspace)
        .filesystem(new DockerFilesystemSpec()
                .image("ubuntu:24.04")
                .isolationScope(IsolationScope.USER)
                .memorySizeBytes(512 * 1024 * 1024L)
                .cpuCount(2L)
                .network("host")
                .exposedPorts(8080, 3000)
                .environment(Map.of("NODE_ENV", "development"))
                .snapshotSpec(new LocalSnapshotSpec("/data/snapshots")))
        .build();
```

`DockerFilesystemSpec` 关键参数：

| 方法 | 默认 | 说明 |
|------|------|-----|
| `image(String)` | 必填 | Docker 镜像 |
| `isolationScope(IsolationScope)` | `SESSION` | 隔离维度 |
| `memorySizeBytes(Long)` | Docker 默认 | 内存限制 |
| `cpuCount(Long)` | Docker 默认 | CPU 限制 |
| `snapshotSpec(SandboxSnapshotSpec)` | `NoopSnapshotSpec` | 快照策略 |
| `executionGuard(SandboxExecutionGuard)` | none | AGENT/GLOBAL 并发锁 |
| `workspaceRoot(String)` | `/workspace` | 容器内挂载点 |
| `workspaceProjectionEnabled(boolean)` | `true` | 宿主静态资源注入沙箱 |
| `workspaceProjectionRoots(List)` | `AGENTS.md`, `skills`, `subagents`, `knowledge`, `.skills-cache` | 注入根路径 |

### Kubernetes / E2B / Daytona / AgentRun

```java
// Kubernetes
.filesystem(new KubernetesFilesystemSpec()
        .image("node:20-slim").namespace("agents")
        .serviceAccount("agent-runner").isolationScope(IsolationScope.USER))

// E2B
.filesystem(new E2bFilesystemSpec()
        .apiKey("${E2B_API_KEY}").templateId("my-template")
        .sandboxTimeoutSeconds(300).isolationScope(IsolationScope.SESSION))

// Daytona
.filesystem(new DaytonaFilesystemSpec()
        .apiKey("${DAYTONA_API_KEY}")
        .controlPlaneBaseUrl("https://api.daytona.io")
        .image("python:3.12-slim").cpu(2).memory(4).disk(10)
        .isolationScope(IsolationScope.USER))

// AgentRun（阿里云 FC 3.0）
.filesystem(new AgentRunFilesystemSpec()
        .apiKey("${AGENTRUN_API_KEY}").accountId("your-account-id")
        .region("cn-hangzhou").templateName("python3.12")
        .isolationScope(IsolationScope.USER))
```

### 快照策略（SandboxSnapshotSpec 实现类）

| 类 | 描述 |
|----|-----|
| `NoopSnapshotSpec` | 无持久化（默认） |
| `LocalSnapshotSpec(Path)` | 宿主本地磁盘 |
| `RedisSnapshotSpec` | Redis |
| `OssSnapshotSpec` | 阿里云 OSS / S3 兼容 |
| `JdbcSnapshotSpec` | MySQL / JDBC BLOB |
| `RemoteSnapshotSpec` | BaseStore 适配 |

跨调用恢复优先级：容器仍活着 → 直接继续（最快）→ 容器已消失 → 从快照重建 → 无快照 → 冷启动。

### 并发控制（多副本 USER/AGENT/GLOBAL scope）

```java
import io.agentscope.extensions.redis.RedisDistributedStore;

// 推荐：distributedStore 一行自动注入 stateStore + snapshotSpec + executionGuard
DistributedStore store = RedisDistributedStore.fromJedis(jedis);
HarnessAgent.builder()
        .distributedStore(store)
        .filesystem(new DockerFilesystemSpec()
                .image("ubuntu:24.04")
                .isolationScope(IsolationScope.USER))
        .build();

// 手动指定锁参数
.filesystem(new DockerFilesystemSpec()
        .isolationScope(IsolationScope.USER)
        .executionGuard(RedisSandboxExecutionGuard.builder(jedis)
                .leaseTtl(Duration.ofMinutes(30)).build()))
```

内置 `SandboxExecutionGuard` 实现：`RedisSandboxExecutionGuard`（Redis `SET NX PX`）、`JdbcSandboxExecutionGuard`（MySQL `GET_LOCK()`）。

### 自管沙箱（高级）

```java
import io.agentscope.harness.sandbox.SandboxContext;

Sandbox mySandbox = dockerClient.create(workspaceSpec, snapshotSpec, options);
mySandbox.start();

SandboxContext callCtx = SandboxContext.builder()
        .client(dockerClient)
        .externalSandbox(mySandbox)  // 框架只调 stop()，不调 shutdown()
        .build();

agent.call(msgs, RuntimeContext.builder()
        .sessionId("my-session")
        .put(SandboxContext.class, callCtx)
        .build()).block();

mySandbox.shutdown();  // 自行管理生命周期
```

---

## Mode 3：本地 + Shell（LocalFilesystemSpec，默认）

```java
import io.agentscope.harness.filesystem.LocalFilesystemSpec;
import io.agentscope.harness.filesystem.LocalFsMode;

.filesystem(new LocalFilesystemSpec()
        .project(Paths.get("/my/project"))      // 项目根（shell cwd + overlay 下层）
        .addRoot(Paths.get("/extra/dir"))        // 追加可访问目录
        .mode(LocalFsMode.ROOTED)               // 路径策略
        .executeTimeoutSeconds(120)
        .maxOutputBytes(100_000)
        .env("MY_VAR", "value")
        .inheritEnv(true)
        .projectWritable(true))                 // 代码写入项目目录而非 workspace
```

`LocalFsMode` 枚举：

| 值 | 行为 |
|----|------|
| `ROOTED`（默认） | 仅允许 workspace + project + additionalRoots 下绝对路径；拒绝 `..` 遍历 |
| `SANDBOXED` | 所有路径锚定 workspace 根；拒绝绝对路径和 `..` |
| `UNRESTRICTED` | 绝对路径直通（仅测试或完全可信环境） |

内部实现为 `OverlayFilesystem`：上层（可读写）= `LocalFilesystemWithShell`（workspace 根），下层（只读）= `LocalFilesystem`（project 根）。读优先 workspace，fallback 到 project（COW 语义）。

`projectWritable(true)` 路由规则：workspace 元数据（`MEMORY.md`、`memory/`、`agents/`、`skills/`、`plans/` 等）仍写 workspace；其余写项目目录（`src/`、`pom.xml` 等）。

---

## IsolationScope 枚举

`io.agentscope.harness.filesystem.IsolationScope`（模式 1、2 共用）

| 值 | 共享维度 | namespace key |
|----|---------|--------------|
| `USER`（默认） | 同 userId 共享 | `agents/<agentId>/users/<userId>/...` |
| `SESSION` | 每个 sessionId 独立 | `agents/<agentId>/sessions/<sessionId>/...` |
| `AGENT` | 该 agent 所有用户/会话共享 | `agents/<agentId>/shared/...` |
| `GLOBAL` | 全局单槽 | `global/...` |

**降级规则**：`USER` scope 且 `RuntimeContext.userId` 缺失 → 自动降级为 `SESSION`（用 sessionId），沙箱不崩溃。

---

## RuntimeContext 寻址

```java
import io.agentscope.core.RuntimeContext;

RuntimeContext rc = RuntimeContext.builder()
        .userId("alice")
        .sessionId("conv-1")
        .build();

agent.call(msg, rc).block();
```

同 `userId` 跨调用 → 自动复用同一沙箱（或从快照恢复）。不同 `userId` → 独立沙箱。

---

## 工作空间投影（Workspace Projection）

沙箱启动时，框架将宿主 workspace 静态资源 tar 打包注入容器 `/workspace`，内容哈希门控（SHA-256）避免重复传输：

- `AGENTS.md`、`skills/`、`subagents/`、`knowledge/`、`.skills-cache/`

可通过 `workspaceProjectionRoots(List)` 定制，或 `workspaceProjectionEnabled(false)` 完全关闭。

沙箱内文件**不自动同步回宿主**，需通过 `read_file` 工具取回产物。

---

## 多用户隔离：静态资源 vs 运行时数据

| 类型 | 隔离方式 |
|------|---------|
| 运行时数据（对话日志、memory、tasks） | 自动按 IsolationScope / userId 隔离 |
| 静态资源（`AGENTS.md`、`tools.json`、`knowledge/`） | **不**自动分区；通过 per-user 覆盖目录实现差异化（`workspace/alice/skills/...` 覆盖 `workspace/skills/...`） |

---

## 关键类速查

| 类/接口 | 说明 |
|---------|-----|
| `AbstractFilesystem` | 文件系统统一接口 |
| `OverlayFilesystem` | 本地模式内部实现（上层 workspace + 下层 project） |
| `CompositeFilesystem` | 共享存储模式内部实现（KV overlay + 本地模板） |
| `LocalFilesystemSpec` | 本地 + Shell 配置对象 |
| `RemoteFilesystemSpec` | 共享 KV 存储配置对象 |
| `SandboxFilesystemSpec` | 沙箱配置抽象父类 |
| `DockerFilesystemSpec` | Docker 沙箱配置 |
| `KubernetesFilesystemSpec` | K8s 沙箱配置 |
| `E2bFilesystemSpec` | E2B 托管沙箱配置 |
| `DaytonaFilesystemSpec` | Daytona 托管沙箱配置 |
| `AgentRunFilesystemSpec` | 阿里云 FC 沙箱配置 |
| `IsolationScope` | `USER/SESSION/AGENT/GLOBAL` 枚举 |
| `LocalFsMode` | `ROOTED/SANDBOXED/UNRESTRICTED` 枚举 |
| `SandboxExecutionGuard` | 分布式并发锁接口 |
| `RedisSandboxExecutionGuard` | Redis `SET NX PX` 实现 |
| `JdbcSandboxExecutionGuard` | MySQL `GET_LOCK()` 实现 |
| `SandboxContext` | 自管沙箱注入载体 |
| `DistributedStore` / `RedisDistributedStore` | 分布式一站式配置入口 |
| `NoopSnapshotSpec` / `LocalSnapshotSpec` / `OssSnapshotSpec` / `RedisSnapshotSpec` / `JdbcSnapshotSpec` | 快照策略实现类 |
