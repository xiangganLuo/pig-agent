# channel-expansion Specification

## Purpose
TBD - created by archiving change channel-expansion. Update Purpose after archive.
## Requirements
### Requirement: Channel 适配器契约与生命周期
`Channel` SPI MUST 定义一致的渠道适配器契约，并文档化其生命周期：`start(Consumer<String> handler)` 装配入站——渠道收到外部消息时调用 `handler`；入站消息经 `ChannelAgentBridge` 路由到 agent，agent 回复经渠道的 `sendMessage(String)` 出站；`stop()` 释放资源；`isRunning()` MUST 反映当前运行状态。契约签名 MUST 保持向后兼容（现有 `TelegramChannel`/`DiscordChannel`/`ChatChannel` 零回归）。新增适配器 MUST 只需实现 `Channel` 并经 `ChannelFactory`/`ChannelRegistry` 接入，无需改动 bridge 或既有渠道。

#### Scenario: 新适配器实现契约即可接入
- **WHEN** 新增一个类实现 `Channel`（`channelId`/`displayName`/`start`/`sendMessage`/`stop`/`isRunning`）并在 `ChannelFactory` 注册其 id
- **THEN** 该渠道可经 `ChannelAgentBridge` 路由消息到 agent，无需改动 bridge 或其它渠道

#### Scenario: 生命周期状态一致
- **WHEN** 调用 `start(handler)` 后再 `stop()`
- **THEN** `isRunning()` 在 `start` 后为 `true`、`stop` 后为 `false`

### Requirement: 通用 Webhook/HTTP 渠道
系统 SHALL 提供 `WebhookChannel`——基于 JDK 内置 HTTP 服务（无新第三方依赖）监听入站 HTTP `POST`，把请求体映射为用户消息，经 bridge 跑 agent，并把 agent 回复写回同一次 HTTP 响应体。渠道 MUST 拒绝非 `POST` 方法（返回 405）与空消息（返回 400）。当配置了 `token` 时，渠道 MUST 校验入站请求的 `X-Auth-Token` 或 `Authorization: Bearer` 头，不匹配返回 401；未配置 `token` 时不施加鉴权。请求处理与消息映射 MUST 为纯逻辑（不依赖真实 socket 即可单测）；凭据 `token` MUST NOT 出现在日志、响应体或错误文本中。

#### Scenario: POST 消息经 agent 回复
- **WHEN** 向渠道 POST 一个含消息文本的请求体（JSON `{"message":"..."}` 或纯文本）
- **THEN** 消息经 bridge 跑 agent，agent 回复以 HTTP 200 写回响应体

#### Scenario: 非 POST 方法被拒
- **WHEN** 向渠道发起 `GET` 请求
- **THEN** 返回 405，不触发 agent

#### Scenario: 空消息被拒
- **WHEN** POST 一个空/空白消息体
- **THEN** 返回 400，不触发 agent

#### Scenario: 鉴权门控
- **WHEN** 配置了 `token` 且入站请求缺失或携带错误的鉴权头
- **THEN** 返回 401；携带正确 token 时正常处理

#### Scenario: 未配 token 免鉴权
- **WHEN** 未配置 `token`
- **THEN** 任意 POST 消息均被处理（不施加鉴权门控）

### Requirement: Webhook 消息编解码
系统 SHALL 提供纯函数式的 `WebhookCodec`：入站从请求体抽取消息文本——JSON 体优先取 `message` 键、其次 `text` 键，非 JSON 或无该键则回退为整段纯文本；空/空白体抽取为空。出站把 agent 回复格式化为 JSON（`{"reply":...}`，正确转义）。编解码 MUST 无副作用、可离线单测。

#### Scenario: JSON 消息键抽取
- **WHEN** 请求体为 `{"message":"hi"}` 或 `{"text":"hi"}`
- **THEN** 抽取出 `hi`

#### Scenario: 纯文本回退
- **WHEN** 请求体为非 JSON 的纯文本
- **THEN** 整段文本作为消息

#### Scenario: 回复格式化
- **WHEN** 把回复文本格式化为出站体
- **THEN** 得到含该文本的 JSON（`reply` 字段，特殊字符正确转义）

### Requirement: CLI/stdin 管道渠道
系统 SHALL 提供 `StdinPipeChannel`——从输入流（缺省 `System.in`）按行读取，每一非空行作为一条入站消息经 bridge 跑 agent，agent 回复写出输出流（缺省 `System.out`）。空白行 MUST 跳过。读取循环 MUST 可同步单测（喂有限输入流至 EOF 即确定性结束），`start` 用后台守护线程运行之，`stop` 终止读取。

#### Scenario: 按行入站，空行跳过
- **WHEN** 输入流含 `"a\n\nb\n"`
- **THEN** handler 依次收到 `a`、`b`（空行被跳过），到 EOF 后读取循环结束

#### Scenario: 回复写出输出流
- **WHEN** agent 对一行输入产生回复
- **THEN** 回复被写入渠道的输出流

### Requirement: Slack 事件渠道骨架
系统 SHALL 提供 `SlackChannel`——经 Slack Events API 接入入站：`url_verification` 请求 MUST 回显其 `challenge`；`event_callback` 中类型为 `message` 的事件 MUST 抽取 `event.text` 并经 bridge 跑 agent，带 `bot_id` 或 `subtype` 的消息（bot 回声）MUST 忽略以防自触发。当配置了 `signing-secret` 时，渠道 MUST 用 Slack `v0` 方案（`v0:{timestamp}:{body}` 的 HMAC-SHA256，常量时间比对）校验请求签名，无效则返回 401。入站事件抽取与签名校验 MUST 为纯逻辑、可离线单测。出站回帖为文档化桩（回复记入日志，不真正投递），`signing-secret` MUST NOT 出现在日志/响应/错误文本中。

#### Scenario: URL 校验握手
- **WHEN** 收到 `{"type":"url_verification","challenge":"C"}`
- **THEN** 返回 200 并回显 `C`

#### Scenario: 消息事件抽取
- **WHEN** 收到 `event_callback` 且 `event.type=="message"` 且无 `bot_id`/`subtype`
- **THEN** 抽取 `event.text` 触发 agent

#### Scenario: 忽略 bot 回声
- **WHEN** 收到带 `bot_id` 或 `subtype` 的 message 事件
- **THEN** 不触发 agent（防自触发回环）

#### Scenario: 签名校验
- **WHEN** 配置了 `signing-secret` 且请求签名头无效
- **THEN** 返回 401；签名有效时正常处理

### Requirement: 渠道构造与启用门控
系统 SHALL 提供 `ChannelFactory`，把每个 `channels.<id>` 配置映射为渠道实例并集中启用门控：配置 `enabled=false` 或缺失 MUST 不构造（返回空）；启用但为未知 id MUST 记 `warn` 并跳过（返回空）；启用且为已知 id MUST 构造对应渠道。渠道种类 MUST 由 `ChannelType` **枚举注册表**统一登记（见「ChannelType 枚举注册表」），`ChannelFactory` MUST 由该枚举驱动（`ChannelType.fromId(id)` → `type.create(cfg)`），MUST NOT 使用 ad-hoc 的字符串 `switch`——新增一个渠道 MUST 只需新增一个枚举常量，无需改动 `ChannelFactory` 或 `PigAgentCli`。已知 id 至少覆盖 `telegram`/`discord`/`webhook`/`slack`/`stdin`/`dingtalk`/`feishu`。渠道缺省 `enabled=false`，故不配置时不启动任何新渠道（向后兼容）。`PigAgentCli` MUST 经该工厂构造渠道。

#### Scenario: 禁用或未知不构造
- **WHEN** 某渠道 `enabled=false`，或启用了一个未知 id
- **THEN** 工厂不构造该渠道（未知 id 记 warn），返回空

#### Scenario: 已知渠道按 id 经枚举构造
- **WHEN** `channels.<id>.enabled=true`（`webhook`/`slack`/`stdin`/`telegram`/`discord`/`dingtalk`/`feishu` 任一）
- **THEN** 工厂经 `ChannelType.fromId(id).create(cfg)` 构造出对应类型的渠道实例（`dingtalk`/`feishu` 为通用 `StrategyHttpChannel`）

#### Scenario: 加渠道不改工厂
- **WHEN** 需新增一个渠道种类
- **THEN** 只新增一个 `ChannelType` 枚举常量即可被 `ChannelFactory` 构造，`ChannelFactory`/`PigAgentCli` 无需改动

### Requirement: 每渠道配置最小扩展
`PigAgentConfig.ChannelConfig` SHALL 在既有 `enabled`/`token`/`port`/`path`/`signing-secret` 之外最小扩展三个可选字段：`webhook-url`（出站机器人 webhook URL，缺省 null）、`sign-secret`（钉钉/飞书出站+入站签名密钥，缺省 null）、`verification-token`（飞书事件订阅入站验签 token，缺省 null）。所有新字段 MUST 可选且有安全缺省，旧 `application.yaml`（仅含 `enabled`/`token` 等既有字段）MUST 照常解析（向后兼容）。凭据字段（`sign-secret`/`verification-token`/`token`/`signing-secret`）MUST NOT 出现在日志、HTTP 响应或错误文本中。

#### Scenario: 旧配置向后兼容
- **WHEN** 载入只含 `channels.telegram.{enabled,token}` 的旧配置
- **THEN** 正常解析，新字段取安全缺省（`webhook-url`/`sign-secret`/`verification-token` 为 null）

#### Scenario: 扩展字段可配
- **WHEN** 配置 `channels.dingtalk.{enabled,webhook-url,sign-secret}` 或 `channels.feishu.{enabled,webhook-url,sign-secret,verification-token}`
- **THEN** 对应字段被工厂读取并传入渠道策略生效

### Requirement: ChannelType 枚举注册表
系统 SHALL 提供 `ChannelType` 枚举作为渠道种类的**唯一注册表**。每个枚举常量 MUST 携带其 `id`（`channels.<id>` 配置键）、`displayName`（人读名）与一个从 `ChannelConfig` 构造 `Channel` 的构造器（HTTP 机器人类的构造器链接到其 `ChannelStrategy`）。枚举 MUST 提供 `create(ChannelConfig): Channel` 与静态 `fromId(String): Optional<ChannelType>`（未知 id 返回空）。所有渠道种类（含既有 `telegram`/`discord`/`webhook`/`slack`/`stdin` 与新增 `dingtalk`/`feishu`）MUST 各为一个常量，且 id MUST 全局唯一。

#### Scenario: id 查找
- **WHEN** 以已知 id 调 `ChannelType.fromId("dingtalk")`
- **THEN** 返回对应枚举常量；未知 id 返回 `Optional.empty()`

#### Scenario: 常量携带信息并构造
- **WHEN** 对某常量调 `create(cfg)`
- **THEN** 返回该种类对应的 `Channel` 实例，且常量的 `id`/`displayName` 与该渠道一致

### Requirement: ChannelStrategy 策略与通用 HTTP 策略渠道
系统 SHALL 提供 `ChannelStrategy` 策略接口，抽出 HTTP 机器人渠道**因平台而异**的行为：① 入站请求 → 消息解析（含 `url_verification`/事件识别）、② 签名/验证、③ 出站回复发送。系统 SHALL 提供通用 `StrategyHttpChannel implements Channel`，复用 `HttpChannelServer` 做入站传输、复用 `WebhookSender` 做出站传输，把上述平台差异**全部委托**给注入的 `ChannelStrategy`——新增一个 HTTP 机器人渠道 MUST 只需实现一个 `ChannelStrategy`（+ 其纯 codec），无需再写传输粘合。`ChannelStrategy.inbound(InboundHttp)` MUST 返回不可变的 `Inbound`——要么一个直接回写的 `OutboundHttp`（challenge / 401 / ack），要么一段待路由的用户消息文本。`StrategyHttpChannel.process` MUST 按「`inbound` → 直接响应则回写 / 有消息则交 handler 跑 agent（回复经 `sendMessage`→`strategy.send` 出站）→ 回 `ack`」编排；`channelId`/`displayName`/生效端口路径 MUST 取自策略。

#### Scenario: 直接响应透传
- **WHEN** 策略的 `inbound` 返回一个直接响应（如 challenge 回显或 401）
- **THEN** `StrategyHttpChannel` 原样回写该 `OutboundHttp`，不触发 agent

#### Scenario: 消息路由并出站回复
- **WHEN** 策略的 `inbound` 返回一段待路由消息
- **THEN** `StrategyHttpChannel` 把消息交入站 handler（经 bridge 跑 agent），agent 回复经 `sendMessage` 委托 `strategy.send` 出站，HTTP 响应回策略的 `ack`

#### Scenario: 加 HTTP 机器人只写策略
- **WHEN** 需新增一个 HTTP 机器人渠道
- **THEN** 只需实现一个 `ChannelStrategy`（复用 `StrategyHttpChannel` + `HttpChannelServer` + `WebhookSender`），无需重写传输粘合

### Requirement: 出站 Webhook 发送 seam
系统 SHALL 提供 `WebhookSender` 出站传输 seam（`post(url, jsonBody): boolean`），把「向机器人 webhook POST JSON」这一唯一需真实网络的动作抽成可注入接口，MUST 永不抛异常（内部捕获 → 记 `warn` 返回 `false`）。默认实现 `JdkWebhookSender` MUST 基于 JDK `java.net.http.HttpClient`（`Content-Type: application/json`，带连接超时），不引入第三方 HTTP 客户端。策略 MUST 通过该 seam 出站，以便离线单测注入记录型假实现断言「签名 URL + 载荷」而不触网。发送失败日志 MUST NOT 包含请求体或凭据。

#### Scenario: 成功 POST 返回 true
- **WHEN** `JdkWebhookSender.post(url, body)` 打到一个返回 2xx 的端点
- **THEN** 返回 `true`，且服务端收到该 JSON 体

#### Scenario: 失败不抛
- **WHEN** 目标不可达或返回非 2xx
- **THEN** 返回 `false`，不抛异常，日志不含请求体/凭据

### Requirement: 钉钉自定义机器人渠道
系统 SHALL 提供钉钉（DingTalk）自定义机器人渠道（`ChannelType.DINGTALK`，id `dingtalk`），经 `DingTalkStrategy` + 纯 `DingTalkCodec` 实现，复用 `StrategyHttpChannel`。**出站** MUST 用 JDK `HttpClient`（经 `WebhookSender`）POST 到机器人 webhook，消息体 `{"msgtype":"text","text":{"content":...}}`；当配置了 `sign-secret` 时 URL MUST 追加 `&timestamp=..&sign=..`，其中 `sign = base64(HMAC-SHA256(key=secret, data=timestamp + "\n" + secret))`。**入站**（outgoing 机器人）MUST 经 `HttpChannelServer` 收 `POST`，当配置了 `sign-secret` 时用请求头 `timestamp`+`sign` **同法**验签（常量时间比对），无效返回 401；从请求体解析 `text.content` 为用户消息经 bridge 跑 agent。非 `POST` 返回 405。签名/解析 MUST 为纯逻辑、可离线单测；`sign-secret` MUST NOT 出现在日志/响应/错误文本。

#### Scenario: 出站签名 POST
- **WHEN** 配置了 `webhook-url` 与 `sign-secret`，agent 产生回复
- **THEN** 经 `WebhookSender` POST 到 `webhook-url?...&timestamp=..&sign=..`，体为 `{"msgtype":"text","text":{"content":<回复>}}`

#### Scenario: 入站验签门控
- **WHEN** 配置了 `sign-secret` 且入站请求 `timestamp`/`sign` 头无效
- **THEN** 返回 401，不触发 agent；有效时解析 `text.content` 触发 agent

#### Scenario: 未配 secret 免验
- **WHEN** 未配置 `sign-secret`
- **THEN** 任意 POST 的 `text.content` 均被解析并触发 agent（不施加验签）

### Requirement: 飞书/Lark 机器人渠道与事件订阅
系统 SHALL 提供飞书/Lark（Feishu）机器人渠道（`ChannelType.FEISHU`，id `feishu`），经 `FeishuStrategy` + 纯 `FeishuCodec` 实现，复用 `StrategyHttpChannel`。**入站事件订阅** MUST 经 `HttpChannelServer` 收 `POST`：`type:url_verification` 请求 MUST 回显其 `challenge`；`im.message.receive_v1` 消息事件 MUST 从 `event.message.content`（内嵌 JSON）解析 `text` 经 bridge 跑 agent；当配置了 `verification-token` 时 MUST 用 `X-Lark-Signature`（`SHA-256(timestamp + nonce + encrypt + token)` 十六进制、常量时间比对，取 `X-Lark-Request-Timestamp`/`X-Lark-Request-Nonce` 头与体内 `encrypt` 字段）验签，无效返回 401。非 `POST` 返回 405。**出站** MUST 经 `WebhookSender` POST 到自定义机器人 webhook，消息体 `{"msg_type":"text","content":{"text":...}}`；当配置了 `sign-secret` 时体内 MUST 带 `timestamp`/`sign`，其中 `sign = base64(HMAC-SHA256(key=timestamp + "\n" + secret, data=空))`。签名/解析 MUST 为纯逻辑、可离线单测；`verification-token`/`sign-secret` MUST NOT 出现在日志/响应/错误文本。

#### Scenario: URL 校验握手
- **WHEN** 收到 `{"type":"url_verification","challenge":"C"}`（且验签通过或未配 token）
- **THEN** 返回 200 并回显 `{"challenge":"C"}`

#### Scenario: 消息事件抽取
- **WHEN** 收到 `im.message.receive_v1` 事件且 `event.message.message_type=="text"`
- **THEN** 解析 `event.message.content` 内嵌 JSON 的 `text` 触发 agent

#### Scenario: 事件验签门控
- **WHEN** 配置了 `verification-token` 且入站 `X-Lark-Signature` 无效
- **THEN** 返回 401，不触发 agent；有效时正常处理

#### Scenario: 出站签名体 POST
- **WHEN** 配置了 `webhook-url` 与 `sign-secret`，agent 产生回复
- **THEN** 经 `WebhookSender` POST 到 `webhook-url`，体为 `{"timestamp":..,"sign":..,"msg_type":"text","content":{"text":<回复>}}`

