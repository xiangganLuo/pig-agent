# Channel & Gateway

> 源: D:\Users\admin\Documents\en\docs\harness\channel.md
> 源: D:\Users\admin\Documents\en\integration\channel\{index,dingtalk,feishu,github,gitlab,wecom}.md

## 核心抽象

**Gateway** — agent 与应用代码之间的中间层，负责：
- **Session 管理**：将每个用户会话映射到稳定的 session id，agent 跨轮次看到一致的记忆
- **单 session 并发排队**：同一 session 的并发消息公平排队，agent 不会自竞争
- **Agent 路由**：多 agent 场景下将消息路由到正确的 agent

**Channel** — 适配一个消息平台（HTTP、WebSocket、DingTalk 等）到 Gateway 路由模型，解析消息发送者、目标 agent、回复地址。

核心接口 / 类：

| 类型 | 关键方法 |
|------|---------|
| `Channel`（接口）| `channelId()`, `config()`, `init(Gateway)`, `start()`, `stop()`, `dispatch(InboundMessage) → Mono<Msg>`, `dispatchStream(InboundMessage) → Flux<AgentEvent>` |
| `Gateway`（接口）| `run(context, messages, outboundAddress)`, `runStream(...)` |
| `GatewayBootstrap` | 多 agent / 多 channel 组装入口 |
| `ChatUiChannel` | 内置通用 channel，适用 HTTP/API 场景 |
| `SendOptions` | 指定 `userId` / `sessionId` / `agentId` |
| `InboundMessage` | 归一化的入站消息 |
| `RouteResult` | `context()` + `outboundAddress()` |
| `ChannelConfig` | channel 元数据（channelId + mainAgentId）|

## 快速起步

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("assistant")
    .sysPrompt("You are a helpful assistant.")
    .model("dashscope:qwen-plus")
    .build();

// agent.channel(...) 惰性创建内部 gateway，注册 agent，注入 gateway 到 channel
ChatUiChannel chat = agent.channel(ChatUiChannel.create());

// 每个 userId 自动对应独立 session
Msg reply = chat.send(SendOptions.userId("user-1"), "Hello!").block();
// 同一用户 + 同一 session：对话继续
Msg followUp = chat.send(SendOptions.userId("user-1"), "Tell me more.").block();
// 不同用户：独立 session
Msg other = chat.send(SendOptions.userId("user-2"), "Hi there").block();
```

`SendOptions` 工厂：

| 工厂方法 | 行为 |
|----------|------|
| `SendOptions.userId("user-1")` | 每用户一个 session（最常用）|
| `SendOptions.of("user-1", "session-a")` | 显式 session（同用户多会话）|
| `SendOptions.userId("u").withAgentId("support")` | 路由到指定 agent |

## 流式事件 / SSE

```java
chat.sendStream(SendOptions.userId("user-1"), "What is the weather in Beijing?")
    .doOnNext(event -> {
        if (event instanceof TextBlockDeltaEvent delta) {
            System.out.print(delta.getDelta());
        } else if (event instanceof ToolCallStartEvent tc) {
            System.out.println("\n[tool] " + tc.getToolCallName());
        }
    })
    .blockLast();
```

Spring Boot SSE controller 模板：

```java
@GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> chat(@RequestParam String userId,
                                          @RequestParam String message,
                                          @RequestParam(required = false) String sessionId) {
    SendOptions options = sessionId != null
            ? SendOptions.of(userId, sessionId)
            : SendOptions.userId(userId);
    return chat.sendStream(options, message)
            .map(event -> ServerSentEvent.<String>builder()
                    .data(objectMapper.writeValueAsString(toPayload(event)))
                    .build());
}
```

## 多 Agent 路由

```java
GatewayBootstrap gw = GatewayBootstrap.builder()
    .agent("sales", salesAgent)
    .agent("support", supportAgent)
    .mainAgent("sales")     // 未指定 agentId 时的默认路由
    .build();

ChatUiChannel chat = gw.chatUiChannel();

// 路由到默认 agent（sales）
chat.send(SendOptions.userId("u1"), "What products?").block();
// 显式路由到 support
chat.send(SendOptions.userId("u1").withAgentId("support"), "Billing issue").block();
```

## 暴露 Subagent 给用户

当 agent 通过 `expose_to_user=true` 派生 subagent 时，gateway 发出 `SubagentExposedEvent`，携带 `subagentId`：

```java
chat.sendStream(SendOptions.userId("user-1"), "Spawn a researcher to investigate AI trends")
    .doOnNext(event -> {
        if (event instanceof SubagentExposedEvent se) {
            System.out.printf("subagentId=%s agentId=%s label=%s%n",
                se.getSubagentId(), se.getAgentId(), se.getLabel());
        }
    }).blockLast();

// 之后直接向 subagent 发消息（绕过父 agent）
Msg reply = chat.sendToSubagent(subagentId, "Focus on LLM agents").block();
chat.sendToSubagentStream(subagentId, "Focus on LLM agents").doOnNext(...).blockLast();
```

## 自定义 Channel

```java
public class MySlackChannel implements Channel {
    @Override public String channelId() { return "slack"; }
    @Override public ChannelConfig config() { return myConfig; }
    @Override public void init(Gateway gateway) { this.gateway = gateway; }
    @Override public void start() { /* 连接 Slack */ }
    @Override public void stop() { /* 断开 */ }

    @Override
    public Mono<Msg> dispatch(InboundMessage message) {
        RouteResult route = router.resolveRoute(config(), message);
        return gateway.run(route.context(), message.messages(), route.outboundAddress());
    }

    @Override
    public Flux<AgentEvent> dispatchStream(InboundMessage message) {
        RouteResult route = router.resolveRoute(config(), message);
        return gateway.runStream(route.context(), message.messages(), route.outboundAddress());
    }
}
```

```java
GatewayBootstrap gw = GatewayBootstrap.builder()
    .agent("main", agent)
    .channel(new MySlackChannel())
    .build();
gw.start();   // 调用所有 channel 的 init() + start()
gw.stop();    // 调用所有 channel 的 stop()
```

## 内置 Channel 适配器

所有适配器共享 `agentscope-extensions-channel-common`（传递依赖）中的公共工具：
- `IdempotencyStore` — 按消息 id 去重重试投递
- `BotLoopGuard` — per-peer 速率限制，防止 bot 互循环

### DingTalk（钉钉）

- **Artifact**: `agentscope-extensions-channel-dingtalk`
- **传输**: Stream 协议（持久 WebSocket，无需公网 webhook 端点）
- **类名**: `DingTalkChannel`

```java
DingTalkChannel channel = DingTalkChannel.fromProperties(
    "my-dingtalk",
    ChannelConfig.of("my-dingtalk", "main"),
    Map.of(
        "appKey",    "your-app-key",
        "appSecret", "your-app-secret",
        "robotCode", "your-robot-code"
    ));
```

关键配置项：`appKey`（必填）、`appSecret`（必填）、`robotCode`（必填）、`apiBase`（默认 `https://api.dingtalk.com`）。

断线自动重连，指数退避（1s → 60s 上限）。出站通过 OpenAPI `batchSend`（DM）和 `groupMessages/send`（群聊）；文本 / Markdown 格式自动识别。

### Feishu / Lark（飞书）

- **Artifact**: `agentscope-extensions-channel-feishu`
- **传输**: Event Subscription v2 回调（HTTP）；需要 Spring Boot
- **类名**: `FeishuChannel`

```java
FeishuChannel channel = FeishuChannel.fromProperties(
    "my-feishu",
    ChannelConfig.of("my-feishu", "main"),
    Map.of(
        "appId",     "cli_xxxxx",
        "appSecret", "your-app-secret"
        // 可选: "encryptKey", "verificationToken"
    ));
```

`FeishuCallbackController`（Spring `@RestController`）自动注册于 `/api/channels/feishu/{channelId}/callback`；自动处理 URL 验证握手。配置 `encryptKey` 后自动 AES-256-CBC 解密 + `X-Lark-Signature` 验证。访问 token 由 `FeishuAccessTokenProvider` 缓存，在 TTL 80% 处主动刷新。

### GitHub

- **Artifact**: `agentscope-extensions-channel-github`
- **传输**: Webhook（HTTP），HMAC-SHA256 签名验证
- **类名**: `GitHubChannel`

```java
GitHubChannel channel = GitHubChannel.fromProperties(
    "my-github",
    ChannelConfig.of("my-github", "main"),
    Map.of(
        "token",         "ghp_xxxxxxxxxxxx",
        "webhookSecret", "your-webhook-secret"
    ));
```

Webhook 路径：`/api/channels/github/{channelId}/webhook`。启动时通过 `GET /user` 解析 bot GitHub user id，自动丢弃 bot 自身评论。每个 issue/PR 线程建模为 `THREAD` peer（id = `owner/repo#number`）——Gateway 为每条线程创建独立 session，agent 在同一线程内看到完整对话历史。

### GitLab

- **Artifact**: `agentscope-extensions-channel-gitlab`
- **传输**: Note hook（HTTP）
- **类名**: `GitLabChannel`

```java
GitLabChannel channel = GitLabChannel.fromProperties(
    "my-gitlab",
    ChannelConfig.of("my-gitlab", "main"),
    Map.of(
        "token", "glpat-xxxxxxxxxxxx"
        // 可选: "apiBase" 用于自托管实例
    ));
```

Webhook 路径：`/api/channels/gitlab/{channelId}/webhook`。支持 GitLab SaaS 和自托管实例（设 `apiBase`）。启动时调用 `GET /api/v4/user` 解析 bot id 防自循环。出站通过 GitLab Notes API 发布评论。

### WeCom（企业微信）

- **Artifact**: `agentscope-extensions-channel-wecom`
- **传输**: 加密回调（HTTP）；需要 Spring Boot
- **类名**: `WeComChannel`

```java
WeComChannel channel = WeComChannel.fromProperties(
    "my-wecom",
    ChannelConfig.of("my-wecom", "main"),
    Map.of(
        "corpId",         "your-corp-id",
        "agentId",        "1000002",
        "secret",         "your-secret",
        "token",          "your-callback-token",
        "encodingAesKey", "your-encoding-aes-key"
    ));
```

`WeComCallbackController` 自动注册于 `/api/channels/wecom/{channelId}/callback`；`WeComCrypto` 实现 WeCom 加密回调规范（自动解密 + 签名验证）。访问 token 由 `WeComAccessTokenProvider` 管理；出站通过 `/cgi-bin/message/send`（DM）和 `/cgi-bin/appchat/send`（群聊）。
