# Subagent Orchestration

> 源: D:\Users\admin\Documents\en\docs\harness\subagent.md

## 核心角色

父 agent 把"独立、上下文重、可并行"的任务委托给 subagent，避免自身 loop 膨胀。每个 subagent 是瞬态实例（本地 `HarnessAgent` 或远程 stub），拥有独立 session，通过 tool result 返回结果。

---

## 三种声明方式

三种来源在 build 时合并：

| 方式 | 用途 | 方法 |
|------|------|------|
| 内置 `general-purpose` | 通用回退（镜像父 agent 能力） | 永远存在，无需配置 |
| Workspace spec 文件 | 项目级、可版控 | `workspace/subagents/<id>.md`，文件名即 `agent_id` |
| 代码声明 | 运行时决定（远程、动态参数） | `builder.subagent(SubagentDeclaration.builder()...)` |

### Workspace spec 文件示例

```markdown
---
description: Code review specialist     # 必填；模型据此决定是否委托
workspace:
  mode: isolated              # 默认 isolated；shared = 使用父 workspace
  path: ./defs/reviewer       # 可选；省略则框架自动创建子目录
model: openai:gpt-4o-mini     # 可选；省略则继承父 agent
steps: 8                      # 可选；每次 spawn 的最大迭代数
temperature: 0.2              # 可选；覆盖父 GenerateOptions
top_p: 0.95                   # 可选
hidden: false                 # true = 不列给模型，但可编程调用
mode: subagent                # primary / subagent / all（默认 all）；primary 不可被 spawn
expose_to_user: true          # 可选三态；强制/禁止用户暴露（省略=无意见）
tools: [read_file, grep_files] # 可选；继承工具的白名单
---
```

### 代码声明（`SubagentDeclaration.builder()`）

```java
HarnessAgent.builder()
    .name("orchestrator")
    .model(model)
    .workspace(workspace)
    .subagent(SubagentDeclaration.builder()
        .name("reviewer")
        .description("Code review specialist")
        .workspace(Path.of("./defs/reviewer"))
        .workspaceMode(WorkspaceMode.ISOLATED)
        .model("qwen3-max")
        .steps(8)
        .tools(List.of("read_file", "grep_files"))
        .build())
    .subagent(SubagentDeclaration.builder()
        .name("remote-researcher")
        .description("Remote research subagent")
        .url("http://agent-task-server:8080")     // 远程 subagent
        .headers(Map.of("Authorization", "Bearer xxx"))
        .build())
    .build();
```

三种来源互斥：`workspace(...)` / `inlineAgentsBody(...)` / `url(...)`，选其一。

---

## 编排工具（父 agent 内置）

| 工具 | 职责 |
|------|------|
| `agent_spawn` | 创建 subagent 并（可选）执行任务（同步或后台） |
| `agent_send` | 向已有 subagent 实例发送后续消息 |
| `agent_list` | 列出当前活跃的 subagent 实例 |
| `task_output` | 按 `task_id` 获取后台任务结果（阻塞或非阻塞） |
| `task_cancel` | 取消正在运行的后台任务 |
| `task_list` | 列出所有后台任务及其当前状态 |

`agent_spawn` / `agent_send` 管理 subagent **实例**；`task_output` / `task_cancel` / `task_list` 管理后台**任务结果**。两者的桥梁是 `task_id`（`timeout_seconds=0` 时由 `agent_spawn` / `agent_send` 返回）。

---

## 同步 vs 后台（`timeout_seconds`）

核心开关是 `agent_spawn` 的 `timeout_seconds` 参数：

- `timeout_seconds > 0`（默认 30，最大 600）— **同步**：父 agent 阻塞等待，结果作为 tool result 返回。
- `timeout_seconds = 0` — **后台**：立即返回 `task_id`，subagent 在后台运行。

### 后台任务自动推送（auto push-back）

后台任务完成后，父 agent **无需轮询**——在父 agent 下一个推理步骤前，框架自动将结果注入为 system reminder：

```
<system-reminder>
Background tasks delivered:
- task_id=xxx, agent=research-analyst, status=COMPLETED
  result summary: ...
</system-reminder>
```

提示词中**不要**写"记得轮询 task_output"——那是旧写法。

---

## 持久 Session（`persistSession`）

默认每次 `agent_spawn` 创建新 session（无历史记忆）。设置 `persistSession(true)` 可跨多次 spawn 复用同一实例：

```java
.subagent(SubagentDeclaration.builder()
    .name("note-taker")
    .description("Accumulates notes across the conversation")
    .persistSession(true)
    .build())
```

框架以 `(parentSessionId, agentId, label)` 派生确定性 key；相同组合再次 spawn 时复用已有实例，保留对话历史与状态。

---

## 向用户暴露 Subagent（`expose_to_user`）

正常情况下 subagent 对用户不可见。`expose_to_user=true` 让 subagent 可被用户直接寻址：

```
agent_spawn agent_id="researcher" task="investigate AI trends" expose_to_user=true
```

效果：
1. 在 Gateway 中将 subagent 注册为用户可寻址入口点
2. 向流式事件流发出 `SubagentExposedEvent`，携带 `subagentId` 句柄

客户端处理：

```java
chat.sendStream(SendOptions.userId("user-1"), "Spawn a researcher to investigate AI trends")
    .doOnNext(event -> {
        if (event instanceof SubagentExposedEvent se) {
            // se.getSubagentId() → 直接与 subagent 对话的句柄
            // se.getAgentId()    → subagent 类型（如 "researcher"）
            // se.getLabel()      → 可选的人类可读名称
        }
    })
    .blockLast();

// 直接向已暴露的 subagent 发消息（绕过父 agent）
chat.sendToSubagent(subagentId, "Focus on LLM agents specifically").block();
```

### 暴露决策优先级（高→低）

1. `RuntimeContext` 的 per-call 覆盖（`AgentSpawnTool.CTX_EXPOSE_TO_USER` key）
2. `SubagentDeclaration.exposeToUser(...)` 静态策略（三态：`true`/`false`/`null`）
3. LLM 的 `expose_to_user` 工具参数
4. 默认 `false`

### 启用条件

需用 `agent.channel(...)` 绑定 Channel，网关桥接自动装配；无 Channel 时 `expose_to_user=true` 被静默忽略：

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("orchestrator")
    .model("dashscope:qwen-plus")
    .build();

ChatUiChannel chat = agent.channel(ChatUiChannel.create());
```

---

## 权限继承与安全边界

- **DENY 规则自动继承**：父 agent 被拒绝的工具，子 agent 同样被拒绝；委托不能绕过安全边界。
- 如需退出继承：`SubagentDeclaration.builder().inheritParentPermissions(false)`（需 javap 验证）。
- **Plan Mode 继承**：父 agent 处于 Plan Mode 时，spawn 的 subagent 自动继承只读限制。
- **递归安全**：subagent 不能再 spawn subagent（强制标记为叶节点）；硬上限 3 层。
- **userId 传播**：父 agent 的 `RuntimeContext.userId` 自动转发给子 agent，多租户隔离链完整。

---

## 远程 Subagent

设置 `url` + 可选 `headers`，通过远程 HTTP 服务（Agent Protocol）运行：

```java
.subagent(SubagentDeclaration.builder()
    .name("remote-researcher")
    .description("Remote research subagent")
    .url("http://agent-task-server:8080")
    .headers(Map.of("Authorization", "Bearer xxx"))
    .build())
```

同步/后台语义同本地 subagent（`timeout_seconds` 控制）。

---

## 流式事件转发（`streamEvents()`）

> 新代码应使用 `streamEvents()`（返回 `Flux<AgentEvent>`）。旧版 `stream()` 系列（`Flux<Event>`）自 2.0.0 起 `@Deprecated(forRemoval = true)`。

同步 subagent 的中间事件**实时转发**到父 agent 的 `streamEvents()` 流。每个子事件携带 `source` 字段（`/` 分隔路径，如 `"main/researcher"`）：

```java
parent.streamEvents(new UserMessage(message), ctx)
    .doOnNext(event -> {
        String src = event.getSource();
        String prefix = (src != null) ? "[" + src + "] " : "";

        if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
            System.out.print(prefix + ((TextBlockDeltaEvent) event).getDelta());
        } else if (event.getType() == AgentEventType.AGENT_START) {
            if (src != null) System.out.println("── child started: " + src);
        }
    })
    .blockLast();

// 仅父 agent 事件
events.filter(e -> e.getSource() == null).subscribe(…);
// 仅子 agent 事件
events.filter(e -> e.getSource() != null).subscribe(…);
```

### 流式转发边界

| 场景 | 实时转发 |
|------|----------|
| `streamEvents()` + 同步本地子 agent（`timeout_seconds > 0`） | ✔ |
| `call()` 模式（非流式） | ✗ |
| `timeout_seconds = 0` 后台任务 | ✗（结果通过 push-back 推送） |
| 远程 subagent（Agent Protocol） | ✗ |

### 错误处理

子 agent 内部异常由框架捕获写回 `TOOL_RESULT`，**不**向父 agent 传播 `onError`——子 agent 失败不会中断父 agent。

---

## ISOLATED vs SHARED Workspace

- **ISOLATED**（默认）：subagent 有独立 workspace；运行时状态按 `(parentSessionId × userId)` 分桶，不同会话互不污染。
- **SHARED**：直接使用父 agent 的 workspace，适合子 agent 输出需被父 agent 立即读取的场景（如 `general-purpose`）。

---

## 后台任务存储路径

后台任务状态默认写入 `workspace/agents/<parentAgentId>/tasks/<sessionId>.json`。多副本场景下任意节点可读取结果；`task_cancel` 可从任意节点发起。

---

## 关键事实汇总

1. **`agent_spawn` 的 `timeout_seconds`** 是同步/后台的唯一开关，后台完成后框架自动 push-back，无需在 prompt 中要求轮询。
2. **`SubagentExposedEvent`** + **`chat.sendToSubagent(subagentId, ...)`** 实现"分支会话"：用户可绕过父 agent 直接与专家 subagent 对话；前提是父 agent 绑定了 `Channel`。
3. **权限不可绕过**：父 agent 的 DENY 规则自动继承，委托链不能提升权限。
