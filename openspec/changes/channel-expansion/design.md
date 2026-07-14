## Context

需求源：批次 B「渠道扩展」——firm up 渠道适配器契约 + 落地新适配器，往 Hermes 式多平台网关方向走。基线 = 批次 A（`feat/tool-sandbox-and-secrets`）。

当前实现事实（已读源确认）：
- `Channel`（`pig-agent-channel/.../Channel.java`）：`channelId()`/`displayName()`/`start(Consumer<String>)`/`sendMessage(String)`/`stop()`/`isRunning()`——契约清晰但无生命周期 Javadoc。
- `ChannelAgentBridge`：`start()` 调 `channel.start(this::handleMessage)`；`handleMessage` **同步**跑 `agentHolder.get().stream(msg).…blockLast()`，累积 `AGENT_RESULT` 文本后 `channel.sendMessage(text)`；持可空 `AgentKernel`，入站时 `kernel.noteChannelChat(id)` 使渠道回合在 façade 事件流可见（分轨、不被 `/agent use` 拥有）。
- `ChannelRegistry`：`register`/`findById`/`getAll`（`LinkedHashMap`）。
- `TelegramChannel`/`DiscordChannel`：桩（`start` 打日志、`sendMessage` 打日志、构造入 `token`）。`ChatChannel`：终端内部渠道（`onUserInput` 手动喂入）。
- 渠道构造 + 门控硬编码在 `PigAgentCli.startChannels`：遍历 `config.getChannels()`，`if(!cfg.isEnabled()) continue`，`switch(id){telegram/discord/default→warn}`，包 `ChannelAgentBridge` 后 `start()`。
- `PigAgentConfig.ChannelConfig`：`enabled`(默认 false) + `token`。`pig-agent-config` 仅依赖 jackson+slf4j。`pig-agent-channel` 现仅依赖 `pig-agent-core`（core 已传递 `jackson-databind`）。
- AgentScope 事件 API：`io.agentscope.core.agent.Event`，`event.getType()==EventType.AGENT_RESULT`，`event.getMessage().getTextContent()`（`AgentRepl`/`ChannelAgentBridge` 均用）。

约束：AgentScope 1.0.12；不可变领域类型；SLF4J（不 `System.out`）；JUnit5+Mockito+AssertJ AAA；文件 <800 行；离线单测；凭据不外泄（沿用 `CredentialSanitizer`/redact 约定）。

## Goals / Non-Goals

**Goals:**
- `Channel` SPI 生命周期文档化，且**签名不变**——Telegram/Discord/Chat 零回归。
- 渠道构造 + 启用门控收敛到**可单测**的 `ChannelFactory`（`channels.<id>`→`Optional<Channel>`：禁用/未知→空，已知启用→构造）。
- 通用 **Webhook/HTTP** 渠道：入站 POST→agent→回复写 HTTP 响应，鉴权/方法/空消息门控齐全，映射逻辑纯函数离线可测。
- **CLI/stdin** 渠道：按行入站→agent→回复写出，读取循环同步可测（喂有限流到 EOF）。
- **Slack** 事件渠道薄骨架：入站 challenge/事件抽取/签名校验（纯 codec 全测），出站为文档化桩。
- 每渠道配置最小扩展（`port`/`path`/`signing-secret`），默认安全 + 缺省禁用，向后兼容；凭据不入日志。

**Non-Goals:**
- Telegram/Discord 从桩升级为真实实现（本次不接其 SDK，保持桩；契约收敛后可后续单独上线）。
- Slack 出站真实投递（`chat.postMessage`/`response_url`）——本次桩（日志），待引入 HTTP/SDK。
- 渠道级鉴权/限流的完整体系、渠道多实例（同一 id 多路）、入站异步 ack + 后台跑（Slack 3s 超时的生产级处理）——骨架同步处理 + 文档标注。
- 改动权限 veto / `channel-mode` 分轨 / bridge 的既有路由语义。

## Decisions

- **D1 契约收敛靠「文档 + 工厂」，不改 `Channel` 签名。** 现签名足够（`start(handler)` 装配入站、`sendMessage` 出站、`stop`/`isRunning` 生命周期），加字段/方法会破坏 Telegram/Discord/Chat。改为：① 给 `Channel` 补生命周期 Javadoc（契约即文档）；② 新增 `ChannelFactory.create(String id, ChannelConfig cfg): Optional<Channel>`——`cfg==null||!enabled`→空（静默），已知 id→构造对应渠道，启用但未知 id→`log.warn`+空。`createEnabled(Map)` 便捷批量。`PigAgentCli.startChannels` 改为委托工厂并包 bridge。门控 + 构造从此可单测。
- **D2 Webhook 请求处理拆「薄粘合 + 纯逻辑」。** JDK `com.sun.net.httpserver`（无新依赖）。`http/HttpChannelServer`（薄）：`start(port,path,RequestHandler)` 把 `HttpExchange` ↔ `InboundHttp(method,headers,body)`/`OutboundHttp(status,contentType,body)` 记录互转（唯一需 socket 的部分，回环 round-trip 单测覆盖）；headers 键统一小写。`WebhookChannel.process(InboundHttp): OutboundHttp`（纯）：非 POST→405、`token` 非空且 `X-Auth-Token`/`Bearer` 不匹配→401、`WebhookCodec.extractMessage` 空→400、否则 `runAndCapture(text)`→200+`WebhookCodec.formatReply`。`WebhookCodec`（纯）：入站 JSON 取 `message`/`text` 键否则纯文本；出站 `{"reply":...}`。**回复捕获**：`ThreadLocal<StringBuilder>`——`runAndCapture` set 缓冲、同步调 `handler.accept(text)`（bridge 同步 `blockLast` 后 `sendMessage(reply)` 追加缓冲）、返回缓冲；`sendMessage` 有缓冲则追加、否则 `log.debug`。同步成立因 bridge 阻塞到回合完成才返回。
- **D3 stdin 渠道读取循环同步可测。** `StdinPipeChannel(InputStream,OutputStream)`（缺省 `System.in`/`System.out`）。`bind(handler)` 置 handler+`running=true`（不起线程）；`pump()`（包私有）`while(running)` 读行、非空 `handler.accept(line)`、EOF/`null` 退出——喂 `ByteArrayInputStream("a\nb\n")` 直接 `pump()` 确定性可测。`start(handler)`=`bind`+守护线程跑 `pump`；`sendMessage`=`out.println`；`stop`=`running=false`+中断。
- **D4 Slack 薄骨架，纯 codec 承重。** `SlackEventCodec`（纯）：`challenge(body)`→`url_verification` 时取 `challenge`；`extractText(body)`→`event_callback` 且 `event.type=="message"` 且无 `bot_id`/`subtype` 时取 `event.text`（滤 bot 回声防自触发）；`verifySignature(secret,timestamp,body,sig)`→`v0:{ts}:{body}` 的 HMAC-SHA256 十六进制、`v0=` 前缀、常量时间比对（`MessageDigest.isEqual`）。`SlackChannel` 复用 `HttpChannelServer`：`signing-secret` 非空则先验签（头 `x-slack-signature`/`x-slack-request-timestamp`）失败→401；`url_verification`→200 回 challenge；`event_callback`→抽文本→`runAndCapture`（触发 agent + 出站桩）→200 ack（空体）。**出站桩**：`sendMessage` 仅 `log.info("Slack would post: …")`（Slack 事件 ack 不带回复体；真实回帖需 API）——文档标注。
- **D5 配置最小扩展，安全缺省。** `ChannelConfig` 增 `@JsonProperty("port") int port=0`、`path String(null)`、`signing-secret String(null)` + getter/setter。`WebhookChannel` port≤0→`DEFAULT_PORT=8686`、path 空→`/webhook`；`SlackChannel` port≤0→`8687`、path 空→`/slack/events`。旧 YAML（仅 enabled/token）照常解析，缺省禁用，向后兼容。
- **D6 channel 模块新增对 config 的依赖。** `ChannelFactory` 需 `ChannelConfig`。`pig-agent-config` 仅依赖 jackson+slf4j，不依赖 channel/core → 无环。`pig-agent-channel` pom 增 `pig-agent-config`。JSON 解析用已传递可用的 `jackson-databind`（无需显式加）。
- **D7 凭据不外泄。** token/signing-secret 仅用于比对，不进任何日志/HTTP 响应/错误文本。bridge 出站错误维持 `Error: <message>`（agent 异常信息，不含渠道凭据）。渠道 `start`/`stop` 日志只打 `channelId`/`displayName`。

## Risks / Trade-offs

- **[粘合] `HttpChannelServer` 需真实 socket** → 仅回环 round-trip 单测覆盖（`port=0` 临时端口 + JDK `HttpClient` 打 localhost，离线确定性）；请求处理/映射的承重逻辑在纯 `process`/`WebhookCodec`/`SlackEventCodec`，无 socket 可全测。缓解 socket 测试潜在环境敏感：round-trip 单测短超时 + 临时端口，失败不影响纯逻辑绿。
- **[并发] Webhook `ThreadLocal` 回复捕获** → `HttpChannelServer` 用守护线程池，每请求独立线程；`runAndCapture` 的 `finally` 清 `ThreadLocal` 防串场。bridge 同步 `blockLast` 保证 `sendMessage` 在 `accept` 返回前触发。缺省单请求同步无竞争。
- **[生产度] Slack 同步处理超 3s** → 骨架同步跑 agent 再 ack，可能超 Slack 3s 事件超时；出站又是桩。**明确为 skeleton**：文档标注真实实现需「先 ack 200 + 后台跑 + `response_url`/`chat.postMessage` 回帖」。本次只证入站/校验/映射契约。
- **[范围] Telegram/Discord 仍是桩** → 本次不接 SDK（避免引第三方依赖 + 令牌真实网络）；契约收敛后它们可复用 `ChannelFactory` 路径后续单独上线。缓解：桩行为零回归，工厂已为其保留分支。
- **[安全] DNS-rebinding / 入站伪造** → webhook token / slack 签名为可选加固；未配则任何 POST 可触发 agent（同现有渠道信任模型 + `channel-mode` no-confirmer 分轨）。文档提示生产应配 token/signing-secret 并置于反代之后。

## 落实追踪表（评审/取向发现项 → 落点 + 状态）

| 发现项 / 取向 | 落点（决策/任务） | 状态 |
|---|---|---|
| 契约文档化、不破坏既有渠道 | D1（Channel Javadoc + 签名不变） / T2 | 已实现 |
| 构造+门控可单测 | D1（ChannelFactory） / T3 | 已实现 |
| 通用 Webhook/HTTP 渠道（离线可测） | D2（HttpChannelServer 薄 + process/Codec 纯） / T4、T5 | 已实现 |
| 第二个自包含适配器（CLI/stdin） | D3（StdinPipeChannel，pump 同步可测） / T6 | 已实现 |
| 真实平台适配器（Slack）为 thin tested skeleton | D4（SlackEventCodec 纯全测 + SlackChannel 出站桩） / T7 | 已实现（出站桩） |
| 每渠道 `channels.<id>` 配置、最小扩展、向后兼容 | D5（ChannelConfig +port/path/signing-secret，缺省禁用） / T1、T8 | 已实现 |
| 凭据不落日志/回显 | D7 / 各渠道 + 复查 T9 | 已实现 |
| bridge 路由（mock agent/kernel）覆盖 | D2/既有 bridge / T8 | 已实现 |
| Telegram/Discord 升级真实实现 | Non-Goals（契约收敛后后续 spec） | 延后 |
| Slack 出站真实投递（response_url/postMessage）| Non-Goals + D4 桩 | 延后 |

## Migration Plan

- 纯增量、默认安全：不配 `channels.webhook/slack/stdin` → 无新渠道启动；旧 `channels.telegram/discord`（enabled/token）行为不变。新字段 `port`/`path`/`signing-secret` 全可选，旧 YAML 无需改。
- `PigAgentCli.startChannels` 委托 `ChannelFactory`——外部行为等价（同样按 enabled 门控、未知 warn），仅把逻辑挪到可测处。
- 回滚：新渠道默认禁用即等于「未引入」；`ChannelFactory` 是纯新增类，删除即回到内联 switch（但保留更优）。

## Open Questions

- Slack 出站真实投递（`response_url` vs `chat.postMessage` + bot token）与「先 ack 后台跑」的异步化——本次记录不做，留后续 spec。
- Webhook 是否需要异步回调模式（inbound ack + 回复 POST 到 callback URL）而非同步响应体——本次同步响应体（配 bridge 的同步 `blockLast` 最简），异步留后续。
- 多渠道同端口复用（一个 `HttpServer` 挂多个 context）——本次每 HTTP 渠道独立 server/端口，网关合并留后续。
