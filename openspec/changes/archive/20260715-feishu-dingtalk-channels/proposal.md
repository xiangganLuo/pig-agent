## Why

`channel-expansion` 已把渠道适配器契约收敛（文档化 `Channel` 生命周期 + 可单测的 `ChannelFactory`）并落地了 Webhook/Stdin/Slack 三个可离线验证的适配器，但两处仍不够「面向扩展」：

1. **构造仍是硬编码 `switch`**——`ChannelFactory.create` 用 `switch(id)` 把 id 映射到渠道，加渠道要改这个 `switch`，且渠道的「id / 显示名 / 如何构造」散落各处，没有单一注册表。
2. **HTTP 类渠道各写各的**——`WebhookChannel`/`SlackChannel` 各自持有 `HttpChannelServer`、各自实现「验签 + 解析入站 + 出站回复」的粘合，平台差异与传输粘合缠在一起，加一个 HTTP 机器人要重抄一遍骨架。

要继续往「一个 agent、多平台网关」走并新增**飞书(Feishu/Lark)**与**钉钉(DingTalk)**两个中文办公场景高频渠道，最干净的做法是先用**设计模式**把渠道层重构到位：用**枚举做单一注册表**（`ChannelType`，每个常量携带 id/显示名并链接到自己的构造/策略）、用**策略模式**（`ChannelStrategy`）抽出「入站解析 / 签名校验 / 出站发送」这三处平台差异，让所有 HTTP 机器人共享 `HttpChannelServer` 粘合而只换策略。这样「加一个渠道 = 加一个枚举常量 + 一个策略」，不再改工厂、不再重抄骨架。飞书/钉钉均基于**自定义机器人 + 事件订阅（webhook）**，无需引入任何厂商 SDK，可完全离线单测。

## What Changes

- **枚举注册表 `ChannelType`（设计模式：enum-registry + Factory）**：新增 `ChannelType` 枚举作为渠道种类的**唯一注册表**，每个常量携带 `id`/`displayName` 并链接到一个 `Function<ChannelConfig, Channel>` 构造器（HTTP 机器人链接到其策略）。`ChannelFactory` 改为**由枚举驱动**（`ChannelType.fromId(id)` → `type.create(cfg)`），**移除 ad-hoc `switch`**。加渠道 = 加枚举常量，工厂零改动。既有 `telegram`/`discord`/`webhook`/`slack`/`stdin` 全部迁到枚举常量，行为不变（零回归）。
- **策略模式 `ChannelStrategy`（设计模式：Strategy）**：新增 `ChannelStrategy` 接口，抽出 HTTP 机器人渠道**因平台而异**的三处行为——① 入站请求 → 消息解析（含 `challenge`/事件识别）、② 签名/验证、③ 出站回复发送。新增**通用** `StrategyHttpChannel implements Channel`：复用 `HttpChannelServer` 做入站传输、复用出站 `WebhookSender` 做出站传输，把平台差异全部委托给注入的 `ChannelStrategy`。新增出站传输 seam `WebhookSender`（`JdkWebhookSender` 基于 JDK `HttpClient`，可注入假实现离线测）。既有 Webhook/Slack/Stdin/Telegram/Discord **保持原类不变**（已测、可用），仅接入枚举注册表；不重写无法离线验证的行为。
- **钉钉自定义机器人渠道（完整实现，离线可测）**：新增 `DingTalkStrategy` + 纯 `DingTalkCodec`。出站 = 用 JDK `HttpClient` POST 到机器人 webhook，URL 追加 `&timestamp=..&sign=..`，`sign = base64(HMAC-SHA256(secret, timestamp + "\n" + secret))`；入站（outgoing 机器人）= `HttpChannelServer` 收 POST，用 `timestamp`+`sign` 头**同法**验签，解析 `text.content` 为用户消息。出站消息体 `{"msgtype":"text","text":{"content":...}}`。
- **飞书/Lark 机器人渠道 + 事件订阅（完整实现，离线可测）**：新增 `FeishuStrategy` + 纯 `FeishuCodec`。入站事件订阅 = `HttpChannelServer` 收 POST——处理 `type:url_verification` 回显 `challenge`、处理 `im.message.receive_v1` 消息事件（解析 `event.message.content` 内嵌 JSON 的 `text`）；配置了 verification token 时用 `X-Lark-Signature`（`SHA-256(timestamp+nonce+encrypt+token)` 十六进制、常量时间比对）验签。出站 = 自定义机器人 webhook，`sign = base64(HMAC-SHA256(key=timestamp + "\n" + secret, data=空))`，签名随请求体 `{"timestamp":..,"sign":..,"msg_type":"text","content":{"text":..}}` 发送。
- **配置最小扩展**：`PigAgentConfig.ChannelConfig` 在既有字段外**最小新增**三个可选字段：`webhook-url`（出站机器人 webhook）、`sign-secret`（钉钉/飞书出站+入站签名密钥）、`verification-token`（飞书事件订阅入站验签 token）。全部可选、默认安全（缺省 null），缺省 `enabled=false`，旧 `application.yaml` 照常解析（向后兼容）。凭据（`sign-secret`/`verification-token`）MUST NOT 入日志/回显——仅用于比对与签名。

## Capabilities

### Modified Capabilities
- `channel-expansion`: 在既有渠道扩展能力上——① 把渠道构造/门控从 `switch` 重构为 `ChannelType` **枚举注册表**驱动（新增 dingtalk/feishu 两类）；② 新增 `ChannelStrategy` **策略**抽象 + 通用 `StrategyHttpChannel` + 出站 `WebhookSender`，HTTP 机器人共享传输粘合、仅换策略；③ 落地**钉钉自定义机器人**与**飞书/Lark 机器人 + 事件订阅**两个完整、离线可测的适配器（入站解析/验签 + 出站签名 POST）；④ `ChannelConfig` 最小扩展 `webhook-url`/`sign-secret`/`verification-token`。

## Impact

- **代码**：`pig-agent-channel`——新增 `ChannelType`（枚举）、`strategy/{ChannelStrategy, StrategyHttpChannel, Inbound}`、`http/{WebhookSender, JdkWebhookSender}`、`dingtalk/{DingTalkStrategy, DingTalkCodec}`、`feishu/{FeishuStrategy, FeishuCodec}`；重构 `ChannelFactory`（枚举驱动，移除 switch）；既有 Webhook/Slack/Stdin/Telegram/Discord 类不变。`pig-agent-config`——`ChannelConfig` 增 3 个可选字段。`pig-agent-cli`——`startChannels` 无需改动（仍委托 `ChannelFactory`）。
- **依赖**：**无新第三方依赖**——入站复用 JDK `com.sun.net.httpserver`（`HttpChannelServer`），出站用 JDK `java.net.http.HttpClient`，JSON 复用已传递可用的 `jackson-databind`，HMAC/SHA-256 用 JDK `javax.crypto`/`java.security`。不接飞书/钉钉厂商 SDK。
- **配置**：`channels.dingtalk.{enabled,webhook-url,sign-secret,port,path}`、`channels.feishu.{enabled,webhook-url,sign-secret,verification-token,port,path}` 全部可选、默认安全，缺省 `enabled=false`——不配即不启动，向后兼容。
- **安全**：新渠道均为入站边界——钉钉 `sign` 头 / 飞书 `X-Lark-Signature` 验签为可选加固；`sign-secret`/`verification-token` 仅用于比对/签名，不入任何日志/HTTP 响应/错误文本（沿用「凭据不外泄」约定）。渠道走既有 `channel-mode` no-confirmer 分轨，不改权限 veto。
- **测试**：离线单测——`DingTalkCodec`/`FeishuCodec` 纯签名与解析、`DingTalkStrategy`/`FeishuStrategy` 入站门控 + 出站发送（注入假 `WebhookSender`）、`StrategyHttpChannel` 入站路由 + 生命周期、`JdkWebhookSender` 回环 round-trip（loopback `HttpChannelServer`）、`ChannelType` 枚举映射、`ChannelFactory` 门控（新增 dingtalk/feishu）、`ChannelConfig` 新字段缺省与往返。
