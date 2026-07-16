# Events & Streaming

> 源: `D:\Users\admin\Documents\en\docs\building-blocks\message-and-event.md`

## 1. Msg 消息模型

`Msg` (`io.agentscope.core.message`) 代表一轮对话——用户输入、Agent 回复或系统指令。内容用有序的 `ContentBlock` 列表建模。

**一次 `call` 周期（含多轮 reasoning+acting）恰好凝缩成一个 `AssistantMessage`**，保证 Msg 状态可从事件流完整重建。

### 核心字段（getter）

| 方法 | 类型 | 说明 |
|------|------|------|
| `getId()` | `String` | 消息唯一 ID |
| `getName()` | `String` | 发送方名称（可为 null） |
| `getRole()` | `MsgRole` | `USER` / `ASSISTANT` / `SYSTEM` / `TOOL` |
| `getContent()` | `List<ContentBlock>` | 有序内容块（不可变） |
| `getMetadata()` | `Map<String, Object>` | 任意 key/value 元数据 |
| `getTimestamp()` | `String` | `yyyy-MM-dd HH:mm:ss.SSS` |
| `getUsage()` | `ChatUsage` | token 用量（仅 ASSISTANT 消息） |
| `getGenerateReason()` | `GenerateReason` | 终止原因（见下） |

`GenerateReason` 枚举值（完整）：`MODEL_STOP` / `TOOL_SUSPENDED` / `REASONING_STOP_REQUESTED` / `ACTING_STOP_REQUESTED` / `ALL_TOOLS_DENIED` / `INTERRUPTED` / `MAX_ITERATIONS`

### ContentBlock 类型（`io.agentscope.core.message`）

| 类 | 说明 | 允许 Role |
|----|------|-----------|
| `TextBlock` | 纯文本 | USER, ASSISTANT, SYSTEM |
| `DataBlock` | 二进制（图片/音频/视频），base64 或 URL；取代旧版 ImageBlock 等 | USER, ASSISTANT |
| `ImageBlock` / `AudioBlock` / `VideoBlock` | 旧版具体媒体块（仍支持，新代码优先用 `DataBlock`） | USER |
| `ThinkingBlock` | 模型推理/思维链 | ASSISTANT |
| `ToolUseBlock` | 工具调用：`id` / `name` / `input` / `state`(`ToolCallState`) | ASSISTANT |
| `ToolResultBlock` | 工具结果，含 `state`(`ToolResultState`) | ASSISTANT |
| `HintBlock` | 注入循环的用户上下文指令 | ASSISTANT |

Role 约束在构造时强制：`USER` 只允许 text/data/image/audio/video；`SYSTEM` 只允许 `TextBlock`。

### 角色固定子类与构建

```java
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.Base64Source;

// 纯文本用户消息——字符串自动包成 TextBlock
UserMessage userText = new UserMessage("user", "What's in this image?");

// 多模态用户消息
UserMessage userMulti =
        new UserMessage(
                "user",
                TextBlock.builder().text("Describe this image:").build(),
                DataBlock.builder()
                        .source(Base64Source.builder()
                                .data("...")
                                .mediaType("image/png")
                                .build())
                        .build());

// 带 builder 的完整构造
UserMessage msg =
        UserMessage.builder()
                .name("user")
                .textContent("Hello")
                .build();
```

### 内容访问辅助方法

```java
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.ToolResultBlock;

String text = msg.getTextContent();                          // 所有 TextBlock 拼接（\n）
List<ToolUseBlock> toolCalls = msg.getContentBlocks(ToolUseBlock.class);
if (msg.hasContentBlocks(ToolResultBlock.class)) { ... }
Object first = msg.getFirstContentBlock(SomeBlock.class);   // 首个匹配块或 null
```

---

## 2. 事件体系

事件（`io.agentscope.core.event`）是消息的流式对应物——增量进度更新，驱动实时 UI 和 HITL 流程。

`AgentEvent` 公共字段：

| 方法 | 类型 | 说明 |
|------|------|------|
| `getId()` | `String` | 事件唯一 ID |
| `getCreatedAt()` | `String` | ISO 8601 时间戳 |
| `getType()` | `AgentEventType` | 事件类型枚举 |
| `getSource()` | `String` | 来源路径；顶级为 null；子 agent 为斜线分隔路径如 `"main/researcher"` |

同一 reply 内所有事件共享 `getReplyId()`；`getBlockId()` 关联文本/思维/数据块；`getToolCallId()` 关联工具调用与结果。

### 完整事件类型列表（24 个，按分组）

**生命周期（Lifecycle）**
- `AgentStartEvent` — agent 开始新回复；携带 `replyId` / `sessionId` / `name` / `role`
- `AgentEndEvent` — agent 完成回复；携带 `replyId`
- `ExceedMaxItersEvent` — 达到最大 reasoning-acting 迭代次数上限；携带 `replyId`
- `RequestStopEvent` — 中间件或工具发起的提前终止请求

**文本流（Text streaming）**
- `TextBlockStartEvent` — 新文本块开始；携带 `replyId` / `blockId`
- `TextBlockDeltaEvent` — 增量文本；`getDelta()` 返回文本片段
- `TextBlockEndEvent` — 文本块完成

**思维链流（Thinking streaming）**
- `ThinkingBlockStartEvent` — 模型思维链开始；与文本流同形
- `ThinkingBlockDeltaEvent` — 增量思维内容；`getDelta()`
- `ThinkingBlockEndEvent` — 思维链完成

**数据流（Data streaming）**
- `DataBlockStartEvent` — `getMediaType()` 返回 MIME 类型（如 `"image/png"`）
- `DataBlockDeltaEvent` — `getData()` 返回增量 base64 编码数据
- `DataBlockEndEvent`

**工具调用流（Tool-call streaming）**
- `ToolCallStartEvent` — `getToolCallId()` / `getToolCallName()`
- `ToolCallDeltaEvent` — `getDelta()` 返回 JSON 片段（参数增量）
- `ToolCallEndEvent`

**工具结果流（Tool-result streaming）**
- `ToolResultStartEvent` — 工具开始执行；携带 `toolCallId` / `toolCallName`
- `ToolResultTextDeltaEvent` — 增量文本输出；`getDelta()`
- `ToolResultDataDeltaEvent` — 增量二进制输出；含 `mediaType` / `data` / `url`
- `ToolResultEndEvent` — 完成；`getState()` 返回 `ToolResultState`：`SUCCESS / ERROR / INTERRUPTED / DENIED / RUNNING`

**模型调用（Model-call）**
- `ModelCallStartEvent` — 携带 `modelName`
- `ModelCallEndEvent` — 携带 `inputTokens` / `outputTokens`

**人机交互（HITL）**
- `RequireUserConfirmEvent` — 暂停等待确认；`getToolCalls()` 返回 `List<ToolUseBlock>`
- `RequireExternalExecutionEvent` — 暂停等待外部执行
- `UserConfirmResultEvent` — 用户提供确认结果（输入事件）；携带 `List<ConfirmResult>`
- `ExternalExecutionResultEvent` — 外部系统返回结果（输入事件）；携带 `List<ToolResultBlock>`
- `AllToolsDeniedEvent` — 用户拒绝了本轮 reasoning 所有工具调用；`getDeniedToolCalls()` 返回 `List<ToolUseBlock>`；中间件可监听并发出 `RequestStopEvent` 来终止 agent

**子 Agent（Subagent）**
- `SubagentExposedEvent` — 通过 `agent_spawn(expose_to_user=true)` 暴露的子 agent；携带 `subagentId` / `agentId` / `sessionId` / `label`

> 注：实际 `AgentEventType` 枚举值名称未在文档中逐一列出——以上名称来自事件类名推断。**需 javap 验证**枚举常量与类名的完整对应关系。

---

## 3. streamEvents 与 Reactor 消费模式

`agent.streamEvents(userMsg)` 返回 `Flux<AgentEvent>`（Project Reactor）。典型消费：

```java
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;

StringBuilder accumulated = new StringBuilder();

agent.streamEvents(userMsg)
        .doOnNext(event -> {
            if (event instanceof AgentStartEvent start) {
                System.out.println("[start replyId=" + start.getReplyId() + "]");
            } else if (event instanceof TextBlockDeltaEvent delta) {
                accumulated.append(delta.getDelta());
            } else if (event instanceof ToolCallStartEvent tc) {
                System.out.println("[tool] " + tc.getToolCallName());
            } else if (event instanceof ToolResultEndEvent end) {
                System.out.println("[tool result state=" + end.getState() + "]");
            } else if (event instanceof AgentEndEvent end) {
                System.out.println("\n[end] full text:\n" + accumulated);
            }
        })
        .blockLast();
```

事件流与消息是同一数据的两个视图——按 `replyId` / `blockId` / `toolCallId` 聚合可重建完整 `AssistantMessage`。后端推 SSE 事件流，前端客户端侧重建；连接断开后从任意检查点重放即可恢复状态。

参考实现：`agentscope-core/agent/StreamingHook.java`、`agentscope-examples/.../streaming/AgentEventStreamExample.java`。
