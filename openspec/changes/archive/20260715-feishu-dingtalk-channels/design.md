## Context

需求源：在 `channel-expansion`（批次 B，已归档）之上，用**设计模式**重构渠道层并新增**飞书/钉钉**两个渠道。基线 = `main`（已含 `pig-agent-channel` 的 WebhookChannel/StdinPipeChannel/SlackChannel + `ChannelFactory` + `HttpChannelServer` 粘合）。

当前实现事实（已读源确认）：
- `Channel`（`start(Consumer<String>)`/`sendMessage`/`stop`/`isRunning` + `channelId`/`displayName`）——生命周期已文档化，签名足够，不改。
- `ChannelFactory.create(id,cfg): Optional<Channel>`——**硬编码 `switch(id){telegram/discord/webhook/slack/stdin/default→warn}`**；`createEnabled(Map)` 批量。`PigAgentCli.startChannels` 委托它。
- `http/`：`HttpChannelServer`（JDK `HttpServer` 薄粘合，`start(port,path,RequestHandler)`/`boundPort`/`stop`，headers 键小写，守护线程池）；`InboundHttp(method,headers,body)` + `header(name)` 大小写不敏感；`OutboundHttp(status,contentType,body)` + `json`/`text`；`RequestHandler`（纯 `InboundHttp→OutboundHttp`）。
- `WebhookChannel`/`SlackChannel`：各自持 `HttpChannelServer` + `process(InboundHttp)` 纯逻辑；Slack 用 `SlackEventCodec`（`v0` HMAC-SHA256 hex 验签、challenge、事件抽取）。出站 Webhook 同步（`ThreadLocal` 捕获），Slack 出站为日志桩。
- `ChannelAgentBridge`：`handleMessage` 同步跑 `agentHolder.get().stream(msg).…blockLast()`，累积 `AGENT_RESULT` 文本后**一次性** `channel.sendMessage(fullText)`；持可空 `AgentKernel`（`noteChannelChat`）。
- `PigAgentConfig.ChannelConfig`：`enabled`(默认 false)/`token`/`port`(0)/`path`/`signing-secret`。config 仅依赖 jackson+slf4j。

约束：AgentScope 1.0.12；不可变领域类型；SLF4J（不 `System.out`）；JUnit5+Mockito+AssertJ AAA；文件 <800 行、函数 <50 行；离线单测；无新第三方依赖；凭据不外泄。

## Goals / Non-Goals

**Goals:**
- 用**枚举注册表**（`ChannelType`）做渠道种类的单一事实源，`ChannelFactory` 由枚举驱动，**移除 `switch`**；加渠道 = 加常量。
- 用**策略模式**（`ChannelStrategy`）抽出 HTTP 机器人的三处平台差异（入站解析 / 签名验证 / 出站发送），通用 `StrategyHttpChannel` 复用入站(`HttpChannelServer`)+出站(`WebhookSender`)传输。
- 落地**钉钉自定义机器人**（出站签名 URL POST、入站 `sign` 头验签、`text.content` 解析）与**飞书/Lark 机器人+事件订阅**（`url_verification` challenge、`im.message.receive_v1` 抽取、`X-Lark-Signature` 验签、出站签名体 POST），全部离线可测。
- `ChannelConfig` 最小扩展 `webhook-url`/`sign-secret`/`verification-token`，默认安全、向后兼容；凭据不入日志。
- Telegram/Discord/Webhook/Stdin/Slack 零回归（仅接入枚举，行为不变）。

**Non-Goals:**
- 把 Webhook/Slack 重写为策略实现——它们已测、可用，仅接入枚举注册表；避免重写无法离线验证的行为（YAGNI）。
- 飞书事件的**加密（encrypt）解密**——本次只处理明文事件体 + 对 `encrypt` 字段（若存在）参与签名；AES 解密留后续。
- 「先 ack 后台跑」的异步化——飞书/钉钉入站同步跑 agent 再发出站（同 Slack 骨架约束），文档标注生产应异步 ack。
- Telegram/Discord 升级为真实实现（仍是桩）。
- 富文本/卡片消息（仅 text 消息类型）。

## Decisions

- **D1 `ChannelType` 枚举做单一注册表（enum-registry + Factory 模式）。** 每个常量携带 `id`（`channels.<id>` 键）、`displayName`、`Function<ChannelConfig, Channel> builder`。`create(cfg)` 调 builder；静态 `fromId(String): Optional<ChannelType>` 做 id→常量查找。既有 5 个渠道迁为常量（builder 直接 `new XxxChannel(...)`），dingtalk/feishu 常量的 builder = `new StrategyHttpChannel(new XxxStrategy(cfg, WebhookSender.jdk()), cfg.getPort(), cfg.getPath())`。`ChannelFactory.create` 改为 `fromId(id).map(t → t.create(cfg))`，未知 id → `log.warn`+空——**移除 `switch`**。加渠道只加常量，工厂/CLI 零改。**取舍**：把「id/显示名/构造」收拢到枚举，代价是枚举 import 了各渠道类（可接受，本就同模块）。

- **D2 `ChannelStrategy` 抽出三处平台差异（Strategy 模式）。** 接口方法映射任务点名的三处「因平台而异」行为：
  - `Inbound inbound(InboundHttp req)` —— **签名验证 + 入站解析**合一：先验签（失败返回 `Inbound.respond(401)`），再解析（`url_verification`→`respond(challenge)`、消息事件→`route(text)`、其它→`ignore()`）。`Inbound` 是不可变记录（`response` 直接回的 `OutboundHttp` 或 `message` 待路由文本，二者其一），静态工厂 `respond/route/ignore` + `isDirect()`/`hasMessage()`。
  - `OutboundHttp ack()` —— 路由消息后（或空入站）写回的固定 ack（钉钉/飞书均 `200 {}`）。
  - `void send(String reply)` —— **出站发送**：经平台签名规则 POST 到自定义机器人 webhook（未配 webhook-url 时 `log.debug` 跳过）。
  - `defaultPort()`/`defaultPath()`/`channelId()`/`displayName()` 供通用渠道解析身份与端口。
  通用 `StrategyHttpChannel implements Channel`：`start`=`bind(handler)`+`server.start(effectivePort, effectivePath, this::process)`；`process(req)` = `inbound(req)` → `isDirect` 直接回 `response` / 有消息则 `handler.accept(msg)`（bridge 同步跑 agent，回复经 `sendMessage`→`strategy.send`）/ 最后回 `ack()`；`sendMessage(reply)`=`strategy.send(reply)`（出站走 webhook，不占用 HTTP 响应体）；`channelId/displayName` 委托 strategy；`stop`/`isRunning`/`boundPort` 同既有 HTTP 渠道。**取舍**：飞书/钉钉出站均走 webhook（不在 HTTP 响应体里带回复），因任务明确「outbound = POST to webhook」；故 `StrategyHttpChannel` 无需 `ThreadLocal` 捕获——比 WebhookChannel 更简单。

- **D3 出站传输 seam `WebhookSender`（可注入，离线可测）。** `@FunctionalInterface WebhookSender { boolean post(String url, String jsonBody); }`——纯传输，永不抛（内部捕获异常 → `log.warn` + `false`）。默认实现 `JdkWebhookSender`（JDK `HttpClient`，`Content-Type: application/json`，连接超时）；`WebhookSender.jdk()` 静态工厂返回之。策略持 `WebhookSender`，测试注入记录型假实现断言「签名 URL + 载荷」而不触网。`JdkWebhookSender` 用 loopback `HttpChannelServer` round-trip 覆盖（离线确定性，同 `HttpChannelServerTest` 手法）。**取舍**：出站 POST 是唯一需真实网络的部分，抽成 seam 让策略 100% 离线可测。

- **D4 钉钉：纯 `DingTalkCodec` 承重 + `DingTalkStrategy` 编排。** `DingTalkCodec`（纯，无 I/O）：
  - `sign(timestamp, secret)` = `base64(HMAC-SHA256(key=secret, data=timestamp + "\n" + secret))`——出站与入站**同法**（任务要求）。
  - `verify(timestamp, providedSign, secret)` = 常量时间比对 `sign(timestamp,secret)`（任一 null→false）。
  - `signedUrl(webhookUrl, secret, timestamp)` = `url + (含?则&否则?) + "timestamp="+ts+"&sign="+urlEncode(sign)`。
  - `buildTextPayload(content)` = `{"msgtype":"text","text":{"content":...}}`（jackson 正确转义）。
  - `extractMessage(body)` = 解析 `text.content`（无则空）。
  `DingTalkStrategy`：`inbound` 非 POST→405；`sign-secret` 非空时读 `timestamp`/`sign` 头验签失败→401；`extractMessage` 空→`ignore`，否则 `route`。`ack`=`200 {}`。`send(reply)`：无 `webhook-url`→debug 跳过；否则 `ts=now(ms)`，有 secret 则 `sender.post(signedUrl(url,secret,ts), buildTextPayload(reply))`，无 secret 则 `sender.post(url, buildTextPayload(reply))`。默认端口 8688、路径 `/dingtalk`。

- **D5 飞书：纯 `FeishuCodec` 承重 + `FeishuStrategy` 编排。** `FeishuCodec`（纯）：
  - `challenge(body)` = `type=="url_verification"` 时取 `challenge`（Optional）。
  - `extractMessage(body)` = `header.event_type=="im.message.receive_v1"` 且 `event.message.message_type=="text"` 时，解析 `event.message.content`（内嵌 JSON 字符串）的 `text`（Optional）。
  - `encryptField(body)` = 取 body 顶层 `encrypt` 字段（无则空串）。
  - `signature(timestamp, nonce, encrypt, token)` = `hex(SHA-256(timestamp + nonce + encrypt + token))`（飞书事件订阅方案）。
  - `verifySignature(token, timestamp, nonce, encrypt, providedSig)` = 常量时间比对（任一 null→false）。
  - 出站：`sign(timestamp, secret)` = `base64(HMAC-SHA256(key=(timestamp + "\n" + secret), data=空))`（飞书自定义机器人方案，与钉钉 key/data 互换——正是策略要抽的平台差异）；`buildTextPayload(content)` = `{"msg_type":"text","content":{"text":...}}`；`buildSignedTextPayload(content, ts, sign)` = 前者加 `timestamp`/`sign` 字段。
  `FeishuStrategy`：`inbound` 非 POST→405；`verification-token` 非空时用 `X-Lark-Request-Timestamp`/`X-Lark-Request-Nonce`/`encrypt`/`X-Lark-Signature` 验签失败→401；`challenge` 存在→`respond(200 {"challenge":c})`；`extractMessage` 有→`route`，否则 `ignore`。`ack`=`200 {}`。`send(reply)`：无 `webhook-url`→debug 跳过；有 secret 则签名体 POST，无 secret 则纯体 POST。默认端口 8689、路径 `/feishu`。

- **D6 配置最小扩展，安全缺省。** `ChannelConfig` 增 `@JsonProperty("webhook-url") String`、`@JsonProperty("sign-secret") String`、`@JsonProperty("verification-token") String` + getter/setter（缺省 null）。旧 YAML 照常解析。工厂读 `cfg.getWebhookUrl()/getSignSecret()/getVerificationToken()` 传给策略。

- **D7 凭据不外泄。** `sign-secret`/`verification-token`/`token` 仅用于比对与签名，不进任何日志/HTTP 响应/错误文本。渠道 `start`/`stop`/`send` 日志只打 `channelId`/`displayName`/端口/路径/字符数。`WebhookSender.post` 失败日志只打状态码/URL 主机级信息，不打 body/凭据。

## Risks / Trade-offs

- **[范围] Webhook/Slack 未迁为策略** → 保持原类不变（已测、行为无法在不重写的前提下等价迁移），仅接入枚举。风险：两套 HTTP 渠道写法并存。缓解：新平台一律走策略，旧类稳定不动；文档标注「策略是新 HTTP 机器人的推荐路径」。
- **[生产度] 入站同步跑 agent 可能超时** → 飞书/钉钉服务端对 webhook 响应有超时/重试；骨架同步跑 agent 再 ack 可能超时。**明确为 skeleton**：文档标注生产应「先 ack 200 + 后台跑 + 出站 webhook 回帖」。本次证入站解析/验签 + 出站签名 POST 契约。
- **[加密] 飞书 encrypt 事件** → 本次只处理明文事件体（`encrypt` 字段参与签名但不解密）；启用了「加密推送」的租户需后续加 AES 解密。文档标注。
- **[安全] 未配签名即免验** → 钉钉 `sign-secret` / 飞书 `verification-token` 为可选加固；未配则任意 POST 可触发 agent（同既有渠道信任模型 + `channel-mode` no-confirmer 分轨）。文档提示生产应配签名并置于反代后。
- **[出站网络] `JdkWebhookSender` 触网** → 抽成 `WebhookSender` seam：策略测试注入假实现零触网；真实实现仅由 loopback round-trip 覆盖（临时端口 + 短超时，离线确定性）。

## 落实追踪表（评审/取向发现项 → 落点 + 状态）

| 发现项 / 取向 | 落点（决策/任务） | 状态 |
|---|---|---|
| 枚举管理渠道信息（id/显示名/构造单一注册表） | D1（ChannelType）/ T2 | 已实现 |
| 工厂由枚举驱动、移除 ad-hoc switch | D1（ChannelFactory 重构）/ T7 | 已实现 |
| 策略模式抽出入站解析/验签/出站三处差异 | D2（ChannelStrategy + StrategyHttpChannel + Inbound）/ T3 | 已实现 |
| HTTP 机器人共享 HttpChannelServer 粘合、仅换策略 | D2（通用 StrategyHttpChannel）/ T3 | 已实现 |
| 出站可离线测（不触网） | D3（WebhookSender seam + JdkWebhookSender）/ T4 | 已实现 |
| 钉钉：出站签名 URL POST + 入站 sign 验签 + 消息解析 | D4（DingTalkCodec/Strategy）/ T5 | 已实现 |
| 飞书：challenge + im.message.receive_v1 抽取 + 验签 + 出站签名体 | D5（FeishuCodec/Strategy）/ T6 | 已实现 |
| 配置最小扩展 webhook-url/sign-secret/verification-token，向后兼容 | D6（ChannelConfig）/ T1 | 已实现 |
| 凭据不落日志/回显 | D7 / 各策略 + 复查 T9 | 已实现 |
| Telegram/Discord/Webhook/Stdin/Slack 零回归 | D1（仅接入枚举）/ T2、T7 | 已实现 |
| 飞书 encrypt 事件 AES 解密 | Non-Goals（后续 spec） | 延后 |
| 「先 ack 后台跑」异步化 | Non-Goals（同 Slack 骨架约束） | 延后 |
| Webhook/Slack 迁为策略实现 | Non-Goals（已测旧类稳定不动，避免不可测重写） | 延后 |

## Migration Plan

- 纯增量、默认安全：不配 `channels.dingtalk/feishu` → 无新渠道启动；旧渠道（含 webhook/slack）行为不变。新字段 `webhook-url`/`sign-secret`/`verification-token` 全可选，旧 YAML 无需改。
- `ChannelFactory` 从 `switch` 改为枚举驱动——外部行为等价（同样按 enabled 门控、未知 warn、构造相同类型），仅把「id→构造」收拢到 `ChannelType`。`PigAgentCli.startChannels` 无需改。
- 回滚：新渠道默认禁用即等于「未引入」；`ChannelType`/`ChannelStrategy`/新策略均为新增类，删除即回到原状（但保留更优）。

## Open Questions

- 飞书出站是否需支持卡片/富文本消息（`interactive`/`post`）——本次仅 `text`，留后续。
- 钉钉/飞书是否需「先 ack 后台跑 + 出站回帖」的异步生产级处理——本次同步骨架，留后续 spec。
- 多渠道同端口复用（一个 `HttpServer` 挂多 context）——本次每 HTTP 渠道独立 server/端口。
