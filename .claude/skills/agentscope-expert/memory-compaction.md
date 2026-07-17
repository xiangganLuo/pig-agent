# Memory & Compaction

> AgentScope 2.0 提供**两套记忆体系**，适用场景不同：
> - `HarnessAgent` 内置双层长期记忆 + 对话压缩（`MemoryConfig` / `CompactionConfig`）
> - `ReActAgent` 通过 `LongTermMemory` SPI 接入外部记忆服务（Mem0 / ReMe / Bailian）
>
> 两套体系互不冲突，迁移到 2.0 后可单独选用或组合。

---

## 1. HarnessAgent 记忆模型

> 源: `D:\Users\admin\Documents\en\docs\harness\memory.md`

### 1.1 双层结构

`HarnessAgent` 将记忆拆分为**两层**：

| 层 | 文件 | 行为 |
|---|---|---|
| 层 1 · 日志 | `memory/YYYY-MM-DD.md` | 每次调用后追加，不去重 |
| 层 2 · 长期 | `MEMORY.md` | 定期由 LLM 合并去重；每步推理注入 system prompt |

### 1.2 三条 LLM 调用路径

| # | 操作 | 写入目标 | 默认 Prompt 常量 | 配置入口 |
|---|---|---|---|---|
| 1 | **Flush** — 从对话窗口提取事实 | `memory/YYYY-MM-DD.md` | `MemoryFlushManager.DEFAULT_FLUSH_PROMPT` | `MemoryConfig.builder().flushPrompt(...)` |
| 2 | **Consolidation** — 合并日志到 `MEMORY.md` | `MEMORY.md`（全量重写）| `MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT` | `MemoryConfig.builder().consolidationPrompt(...)` |
| 3 | **Compaction summary** — 将对话前缀蒸馏为摘要 | 注入当前上下文 | `CompactionConfig.DEFAULT_SUMMARY_PROMPT` | `CompactionConfig.builder().summaryPrompt(...)` |

Flush 与 Consolidation 属于"长期记忆沉淀"，配置在 `MemoryConfig`；Compaction summary 属于"上下文压缩"，配置在 `CompactionConfig`。**三条路径默认共享 Agent 主模型，但 `MemoryConfig` 和 `CompactionConfig` 各自支持 `.model(...)` 覆盖**（通过 `ModelRegistry.resolve()` 解析字符串或直接传 `Model` 实例）。

Flush 与 Session JSONL offload 均为**异步**（`doOnComplete` fire-and-forget），不阻塞当前 `call()` 返回。

### 1.3 `MemoryConfig` 配置

通过 `HarnessAgent.builder().memory(MemoryConfig.builder()...build())` 接入。

```java
HarnessAgent.builder()
    .model("openai:o3")                         // 主推理模型
    .memory(MemoryConfig.builder()
        .model("openai:gpt-4.1-mini")           // flush/consolidation 专用轻量模型
        .flushTrigger(MemoryConfig.FlushTrigger.throttled(Duration.ofMinutes(10)))
        .consolidationMinGap(Duration.ofHours(2))
        .dailyFileRetentionDays(30)
        .sessionRetentionDays(60)
        .consolidationMaxTokens(8_000)
        .build())
    .build();
```

**`MemoryConfig` 字段速查**：

| 字段 | 默认值 | 说明 |
|---|---|---|
| `model` | `null`（用主模型）| flush/consolidation 专用模型 |
| `flushPrompt` | `null`（用 `DEFAULT_FLUSH_PROMPT`）| 路径 1 的 SYSTEM prompt |
| `consolidationPrompt` | `null`（用 `DEFAULT_CONSOLIDATION_PROMPT`）| 路径 2 模板（**必须含两个 `%d`**，否则构造期抛异常）|
| `consolidationMaxTokens` | `4_000` | `MEMORY.md` token 上限 |
| `consolidationMinGap` | `30 min` | 后台合并最小间隔 |
| `dailyFileRetentionDays` | `90` | 日志归档天数 |
| `sessionRetentionDays` | `180` | Session JSONL 清理天数 |
| `flushTrigger` | `FlushTrigger.always()` | `ALWAYS` / `NEVER` / `THROTTLED(Duration)` |

> 完全关闭记忆：`HarnessAgent.builder().disableMemoryHooks().disableMemoryTools()`  
> 仅停止逐次 flush：`.flushTrigger(MemoryConfig.FlushTrigger.never())`（后台 consolidation 仍运行）  
> `THROTTLED` 只影响路径 1（逐次 flush）；压缩内嵌 flush（路径 2）和 overflow flush（路径 3）不受影响。

Agent 内置两个记忆工具供模型自调：`memory_search query="..."` 和 `memory_get path="..." startLine=N endLine=M`。

---

## 2. 对话压缩（Compaction）

> 源: `D:\Users\admin\Documents\en\docs\harness\compaction.md`  
>      `D:\Users\admin\Documents\en\docs\harness\memory.md`

### 2.1 四种策略（全部默认关闭，正交可组合）

| 策略 | 解决问题 | 中间件 |
|---|---|---|
| 对话摘要 | 消息数/Token 总量过多 | `CompactionMiddleware` |
| 大工具结果驱逐 | 单个工具返回过大 | `ToolResultEvictionMiddleware` |
| Overflow 安全网 | 模型真实报 `context_length_exceeded` | `HarnessAgent.recoverFromOverflow` |
| 摘要前参数截断 | 工具调用参数（如 `write_file` body）过大 | `CompactionConfig.TruncateArgsConfig` |

### 2.2 `CompactionConfig` 配置

```java
HarnessAgent.builder()
    .compaction(CompactionConfig.builder()
        .triggerMessages(30)            // 30 条消息触发
        .keepMessages(10)               // 保留最近 10 条原文
        .flushBeforeCompact(true)       // 压缩前先 flush 到 Layer 1（默认 true）
        .offloadBeforeCompact(true)     // 压缩前写入 *.log.jsonl（默认 true）
        .model("openai:gpt-4.1-mini")  // 压缩摘要专用独立模型
        .truncateArgs(CompactionConfig.TruncateArgsConfig.builder()
            .maxArgLength(2000)
            .truncationText("... [truncated] ...")
            .build())
        .build())
    .build();
```

**`CompactionConfig` 字段速查**：

| 字段 | 默认值 | 说明 |
|---|---|---|
| `triggerMessages` | `50` | 消息条数触发阈值（`0`=关闭）|
| `triggerTokens` | `80_000` | 估算 Token 触发阈值（`0`=关闭）|
| `keepMessages` | `20` | 保留末尾原文消息条数 |
| `keepTokens` | `0` | 非零时按 Token 预算回溯，覆盖 `keepMessages` |
| `flushBeforeCompact` | `true` | 压缩前提取事实到 Layer 1（路径 2）|
| `offloadBeforeCompact` | `true` | 压缩前写 JSONL 原始记录 |
| `summaryPrompt` | `DEFAULT_SUMMARY_PROMPT` | 路径 3 摘要 prompt（**必须含 `{messages}`**）|
| `model` | `null`（用主模型）| **压缩摘要专用独立模型**，接受 `Model` 或 `"provider:model"` 字符串 |

> 压缩专用独立模型的配置标识符：`CompactionConfig.builder().model("openai:gpt-4.1-mini")`  
> MemoryConfig 的独立模型配置标识符：`MemoryConfig.builder().model("openai:gpt-4.1-mini")`

**Overflow 安全网**：只要配置了 `.compaction(...)`，模型报 `context_length_exceeded` / `maximum context` / `token limit` 时自动强制极端压缩（`triggerMessages=1`）并**重试一次**，无需额外配置。

**参数截断**：`TruncateArgsConfig` 是非 LLM 的低成本前置过滤，在许多工作负载中可显著延迟摘要触发点。

**压缩不触及的状态**：`ConversationCompactor` 仅操作 `AgentState.contextMutable()` 消息列表；Plan Mode 状态（`AgentState.getPlanModeContext()`）、subagent 后台任务、`todo_write` 任务列表、权限规则均独立存储，压缩透明。

### 2.3 大工具结果驱逐

```java
HarnessAgent.builder()
    .toolResultEviction(ToolResultEvictionConfig.defaults())
    .build();
```

默认：单工具结果超 80K 字符时写盘，上下文替换为 head+tail preview（各约 2K 字符）+ `read_file` 指针。`execute` 命令输出**故意不在默认排除列表**（命令输出可任意大）。可通过 `ToolResultEvictionConfig.builder()` 定制阈值和目录。

---

## 3. ReActAgent LongTermMemory 集成

> 源: `D:\Users\admin\Documents\en\integration\memory\overview.md`  
>      `D:\Users\admin\Documents\en\integration\memory\mem0.md`  
>      `D:\Users\admin\Documents\en\integration\memory\reme.md`  
>      `D:\Users\admin\Documents\en\integration\memory\bailian.md`

三个扩展均实现 `io.agentscope.core.memory.LongTermMemory` 接口，接入方式完全一致：

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .longTermMemory(memory)                        // 任意一种实现
    .longTermMemoryMode(LongTermMemoryMode.BOTH)   // 记录 + 检索
    .build();
```

### 3.1 Mem0

**artifact**：`io.agentscope:agentscope-extensions-mem0`  
**实现类**：`io.agentscope.core.memory.mem0.Mem0LongTermMemory`

```java
Mem0LongTermMemory memory = Mem0LongTermMemory.builder()
    .agentName("travel-bot")
    .userId("alice")
    .apiBaseUrl("http://localhost:8000")
    .apiType(Mem0ApiType.SELF_HOSTED)    // 或 PLATFORM（默认，Mem0 SaaS）
    .metadata(Map.of("category", "travel"))
    .build();
```

三层命名空间隔离：`agentName / userId / runName`，**至少设其一**，否则 `build()` 抛 `IllegalArgumentException`。`metadata` 同时作用于写入和检索过滤。

### 3.2 ReMe

**artifact**：`io.agentscope:agentscope-extensions-reme`  
**实现类**：`io.agentscope.core.memory.reme.ReMeLongTermMemory`

```java
ReMeLongTermMemory memory = ReMeLongTermMemory.builder()
    .userId("task_workspace")             // 映射为 ReMe 的 workspace_id
    .apiBaseUrl("http://localhost:8002")
    .build();
```

基于**对话轨迹（trajectory）**提取记忆；服务端 LLM 处理；隔离粒度为 `userId`（workspace 级）；暂不支持 tag 过滤（需要过滤可编码进 `userId`，如 `tenant-a:project-1`）。

### 3.3 Bailian

**artifact**：`io.agentscope:agentscope-extensions-memory-bailian`  
**实现类**：`io.agentscope.core.memory.bailian.BailianLongTermMemory`

```java
try (BailianLongTermMemory memory = BailianLongTermMemory.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .userId("user_001")
        .memoryLibraryId("lib_xxxxx")
        .enableRerank(true)
        .enableJudge(true)
        .enableRewrite(true)
        .build()) {
    // 使用 memory ...
}
```

三级隔离（`userId + memoryLibraryId + projectId`）；支持 `enableRerank / enableJudge / enableRewrite` 检索质量增强（默认均关闭，开启增加延迟和成本）；实现 `AutoCloseable`，推荐 try-with-resources 释放 HTTP 连接。

---

## 4. 选型速查

| 场景 | 推荐 |
|---|---|
| 使用 `HarnessAgent`，需跨 session 事实沉淀 | `MemoryConfig` 双层记忆（内置，无需外部服务）|
| 使用 `ReActAgent`，需语义检索 + 多租户隔离 | `Mem0LongTermMemory` |
| 使用 `ReActAgent`，轻量自托管 + 轨迹摘要 | `ReMeLongTermMemory` |
| 已在阿里云 Bailian，需检索质量增强 | `BailianLongTermMemory` |
| 节省 flush/compaction token 成本 | `MemoryConfig.builder().model(lighter)` + `CompactionConfig.builder().model(lighter)` |
