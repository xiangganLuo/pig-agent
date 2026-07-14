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
系统 SHALL 提供 `ChannelFactory`，把每个 `channels.<id>` 配置映射为渠道实例并集中启用门控：配置 `enabled=false` 或缺失 MUST 不构造（返回空）；启用但为未知 id MUST 记 `warn` 并跳过（返回空）；启用且为已知 id（`telegram`/`discord`/`webhook`/`slack`/`stdin`）MUST 构造对应渠道。渠道缺省 `enabled=false`，故不配置时不启动任何新渠道（向后兼容）。`PigAgentCli` MUST 经该工厂构造渠道（而非内联硬编码 switch）。

#### Scenario: 禁用或未知不构造
- **WHEN** 某渠道 `enabled=false`，或启用了一个未知 id
- **THEN** 工厂不构造该渠道（未知 id 记 warn），返回空

#### Scenario: 已知渠道按 id 构造
- **WHEN** `channels.webhook.enabled=true`（或 `slack`/`stdin`/`telegram`/`discord`）
- **THEN** 工厂构造出对应类型的渠道实例

### Requirement: 每渠道配置最小扩展
`PigAgentConfig.ChannelConfig` SHALL 在既有 `enabled`/`token` 之外最小扩展三个可选字段：`port`（HTTP 类渠道监听端口，缺省 0 → 渠道取内置默认）、`path`（HTTP 路径，缺省空 → 渠道取内置默认）、`signing-secret`（Slack 签名密钥，缺省空）。所有新字段 MUST 可选且有安全缺省，旧 `application.yaml`（仅含 `enabled`/`token`）MUST 照常解析（向后兼容）。

#### Scenario: 旧配置向后兼容
- **WHEN** 载入只含 `channels.telegram.{enabled,token}` 的旧配置
- **THEN** 正常解析，新字段取缺省（`port=0`、`path`/`signing-secret` 为空）

#### Scenario: 扩展字段可配
- **WHEN** 配置 `channels.webhook.{enabled,port,path}` 或 `channels.slack.{enabled,signing-secret}`
- **THEN** 对应字段被渠道读取生效

