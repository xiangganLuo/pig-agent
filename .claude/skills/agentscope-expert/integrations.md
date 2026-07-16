# Protocol Integrations

> 源: D:\Users\admin\Documents\en\integration\protocol\{overview,a2a,agent-protocol,agui}.md

## 三个协议概览

| 扩展模块 | 协议 | 解决的问题 |
|----------|------|-----------|
| A2A | [Agent-to-Agent](https://a2aproject.github.io/A2A/) | Agent 互相调用 / 组合多 agent 工作流 |
| Agent Protocol | [agentprotocol.ai](https://agentprotocol.ai/) | 外部系统通过标准 HTTP REST 提交"任务" |
| AG-UI | [AG-UI Protocol](https://github.com/ag-ui-protocol/ag-ui) | 前端 UI 的标准化事件流（含 ThinkingBlock）|

选择依据：
- **向前端 UI 流式推送事件（含推理）** → AG-UI
- **让后端系统通过 REST 调度 agent** → Agent Protocol
- **多 agent 互相调用（含第三方）** → A2A

---

## A2A（Agent-to-Agent）

两个独立子模块，可单独使用。

### 客户端：调用远端 A2A Agent

**Artifact**: `agentscope-extensions-a2a-client`  
**核心类**: `io.agentscope.core.a2a.agent.A2aAgent`, `io.agentscope.core.a2a.agent.card.WellKnownAgentCardResolver`

```java
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.agent.card.WellKnownAgentCardResolver;
import io.a2a.spec.AgentCard;

// 方式一：直接提供 AgentCard
AgentCard card = AgentCard.builder()
    .name("remote-translator")
    .url("http://other-service:8080")
    .build();
A2aAgent remote = A2aAgent.builder()
    .name("remote-translator")
    .agentCard(card)
    .build();

// 方式二：通过 well-known 端点自动发现
A2aAgent remote = A2aAgent.builder()
    .name("remote")
    .agentCardResolver(new WellKnownAgentCardResolver(
        "http://127.0.0.1:8080",
        "/.well-known/agent-card.json",
        Map.of()
    ))
    .build();

Msg result = remote.call(new UserMessage("Translate to English: 你好")).block();
```

`A2aAgent` 是 `AgentBase` 的子类，可与 Pipeline、MsgHub、Subagent 等自然组合。

### 服务端：将本地 ReActAgent 暴露为 A2A Server

**Artifact**: `agentscope-extensions-a2a-server`  
**核心类**: `io.agentscope.core.a2a.server.AgentScopeA2aServer`, `io.agentscope.core.a2a.server.transport.jsonrpc.JsonRpcTransportProperties`

```java
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.transport.jsonrpc.JsonRpcTransportProperties;

AgentScopeA2aServer server = AgentScopeA2aServer.builder()
    .agentBuilder(ReActAgent.builder().name("backend-agent").model(model))
    .transportProperties(new JsonRpcTransportProperties())
    // .agentCard(customCard)
    // .agentRegistry(myRegistry)   // 注册到 Nacos 等外部注册中心
    .build();

// 不自行绑端口——由 Spring/Quarkus/Vert.x 等框架转发请求
TransportWrapper wrapper = server.getTransportWrapper("JSONRPC");
// ... 框架 controller 转发到 wrapper.handle(...)
server.postEndpointReady();   // web server 开始监听后调用，触发注册等后置操作
```

可选组件（生产环境替换默认内存实现）：
- `TaskStore` / `QueueManager` — 任务和事件队列持久化
- `PushNotificationConfigStore` / `PushNotificationSender` — 出站推送通知
- `AgentRegistry` — 向外部注册中心（如 Nacos）注册 `AgentCard`

Spring Boot 快捷方式：`agentscope-spring-boot-starter-a2a-server`（自动配置 server + controller，无需手动接线）。

---

## Agent Protocol

**Artifact**: `agentscope-extensions-agent-protocol`  
**交付形式**: Spring Boot auto-configuration

以标准 [Agent Protocol](https://agentprotocol.ai/) HTTP API 暴露 `HarnessAgent`，让外部系统（CI、其他 agent 平台、自动化作业）通过统一合约提交"任务"。

```yaml
# application.yml
agentscope:
  agent-protocol:
    enabled: true   # 默认 false；改为 true 后自动注册 /tasks REST endpoints
```

```java
@Bean
public HarnessAgent harnessAgent() {
    return HarnessAgent.builder()
            .name("protocol-agent")
            .model("dashscope:qwen-plus")
            .build();
}
// 同时提供一个 WorkspaceManager bean 即可
```

关键特性：
- agent 在调用之间无状态，单例处理多个并发任务
- 每个任务通过 `RuntimeContext` 携带独立的 `(userId, sessionId)`，状态完全隔离
- 同一 session 的并发请求自动序列化；不同 session 并行执行
- 任务完成后，workspace 中的文件和日志通过标准 Agent Protocol endpoints 对外暴露（外部客户端可拉取产物）
- `enabled: false`（默认）时依赖完全惰性——不暴露任何 REST 端点，可安全打包发布

---

## AG-UI

**Artifact**: `agentscope-extensions-agui`  
**核心类**: `io.agentscope.core.agui.adapter.AguiAgentAdapter`, `io.agentscope.core.agui.adapter.AguiAdapterConfig`, `io.agentscope.core.agui.event.AguiEvent`, `io.agentscope.core.agui.model.RunAgentInput`

将 AgentScope 事件流转换为 [AG-UI Protocol](https://github.com/ag-ui-protocol/ag-ui) 事件，供前端 UI（Vercel AG-UI、自定义聊天 UI）渲染 agent 运行时（文本、工具调用、推理 ThinkingBlock）。

```java
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;

AguiAdapterConfig config = AguiAdapterConfig.builder()
    .enableReasoning(true)          // 将 ThinkingBlock 转为 REASONING_* 事件
    .runTimeout(Duration.ofMinutes(5))
    .build();

AguiAgentAdapter adapter = new AguiAgentAdapter(agent, config);

// 前端提供 runAgentInput（含 threadId, runId, messages 等）
Flux<AguiEvent> events = adapter.run(runAgentInput);
// 在 Spring @PostMapping("/ag-ui") 中作为 SSE 返回
```

事件映射：

| AgentScope 事件 / 块 | AG-UI 事件 |
|----------------------|-----------|
| `EventType.REASONING/SUMMARY` with `TextBlock` | `TEXT_MESSAGE_*` |
| `EventType.REASONING/SUMMARY` with `ThinkingBlock` | `REASONING_*`（需 `enableReasoning=true`）|
| `ToolUseBlock` | `TOOL_CALL_START` |
| `EventType.TOOL_RESULT` | `TOOL_CALL_END` |

常用配置项：

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `toolMergeMode` | `MERGE_FRONTEND_PRIORITY` | 前端工具与 agent 侧工具的合并策略 |
| `emitStateEvents` | `true` | 是否 emit `STATE_*` 事件（如 thread state）|
| `emitToolCallArgs` | `true` | 是否流式传递工具调用参数 |
| `enableReasoning` | `false` | 是否将 ThinkingBlock emit 为 `REASONING_*` 事件 |
| `runTimeout` | `10m` | 每次运行超时 |
| `defaultAgentId` | `null` | 未提供 agentId 时的默认值 |

Spring Boot 集成：使用 `agentscope-spring-boot-starter-agui` 可自动注册 controller，无需手写 `@PostMapping`。
