## Why

现有 `pig-agent-channel` 只有两个**桩**适配器（`TelegramChannel`/`DiscordChannel`，`start()` 仅打日志、`sendMessage` 不真正发送）与一个终端内部 `ChatChannel`。`Channel` SPI 契约本身可用但**未文档化生命周期**，且渠道构造 + 启用门控硬编码在 `PigAgentCli.startChannels` 的 `switch` 里——加一个新渠道要改 CLI、无法单测门控。要往「Hermes 式多平台网关」方向走（一个 agent、多渠道接入），第一步是**把渠道适配器契约收敛**（文档化生命周期、把构造/门控抽成可测的工厂）并**落地可离线验证的真实适配器**，证明「实现 `Channel` + 注册」这条扩展路径真的顺滑。

## What Changes

- **契约收敛（不改签名，向后兼容）**：为 `Channel` SPI 补齐生命周期 Javadoc（`start(handler)` 装配入站 → 入站消息经 `ChannelAgentBridge` → agent → `sendMessage` 出站回复 → `stop()` 释放；`isRunning` 反映状态）。新增 `ChannelFactory`，把 `channels.<id>` → `Channel` 的构造与**启用门控**从 `PigAgentCli` 抽出成可单测的一处；`PigAgentCli.startChannels` 改为委托它。既有 Telegram/Discord/Chat 行为不变。
- **通用 Webhook/HTTP 渠道（完整实现）**：`WebhookChannel` 基于 JDK 内置 `com.sun.net.httpserver.HttpServer`（无新依赖）监听 `POST`，请求体（JSON `message`/`text` 键或纯文本）映射为用户消息，同步经 bridge 跑 agent，回复写回 HTTP 200 响应体。`GET`→405、空消息→400、可选 `token` 鉴权（`X-Auth-Token`/`Authorization: Bearer`）不匹配→401。HTTP↔记录的粘合抽到极薄的 `HttpChannelServer`；请求处理与消息映射为**纯逻辑**（`process(InboundHttp)` + `WebhookCodec`），离线全覆盖。
- **CLI/stdin 管道渠道（完整实现）**：`StdinPipeChannel` 从输入流按行读取（默认 `System.in`），每非空行 → agent，回复写出输出流（默认 `System.out`）。读取循环 `pump()` 为包私有、同步可测（喂有限流到 EOF 即确定性结束），`start()` 用守护线程跑之。
- **Slack 事件渠道（薄骨架）**：`SlackChannel` 复用 `HttpChannelServer` 接 Slack Events API 入站——`url_verification` 回显 `challenge`、`event_callback` 抽取 `event.text`（忽略 bot 回声/带 subtype 的消息）、可选 `signing-secret` 做 `v0` HMAC-SHA256 签名校验（常量时间比对）。映射与校验落在纯 `SlackEventCodec`（离线全覆盖）。**出站回帖（`chat.postMessage`/`response_url`）为文档化桩**（回复仅日志、不真正投递，待引入 Slack SDK/HTTP 客户端）——即需求所述「thin, tested skeleton」。
- **每渠道配置**：复用 `PigAgentConfig.ChannelConfig`（`enabled`/`token`），**最小扩展**三个可选字段：`port`、`path`（HTTP 类渠道用）、`signing-secret`（Slack 用）。全部有安全缺省，缺省 `enabled=false`——旧 `application.yaml` 照常解析，向后兼容。凭据（token/signing-secret）不入日志、不回显。

## Capabilities

### New Capabilities
- `channel-expansion`: 渠道适配器契约的收敛与扩展——文档化的 `Channel` 生命周期、可单测的 `ChannelFactory` 构造+启用门控、通用 Webhook/HTTP 渠道、CLI/stdin 管道渠道、Slack 事件渠道薄骨架；每渠道 `channels.<id>` 配置（含最小扩展字段），缺省禁用、凭据不外泄。

### Modified Capabilities
<!-- 无：本变更为渠道层的正交新增 + 契约文档化，不改其它 capability 的既有 requirement -->

## Impact

- **代码**：`pig-agent-channel`（`Channel` Javadoc、新增 `ChannelFactory`、`http/HttpChannelServer`+记录、`webhook/{WebhookChannel,WebhookCodec}`、`cli/StdinPipeChannel`、`slack/{SlackChannel,SlackEventCodec}`）、`pig-agent-config`（`ChannelConfig` 增 `port`/`path`/`signing-secret`）、`pig-agent-cli`（`PigAgentCli.startChannels` 委托 `ChannelFactory`）。
- **依赖**：`pig-agent-channel` 新增对 `pig-agent-config` 的依赖（读 `ChannelConfig`；config 仅依赖 jackson+slf4j，无环）；JSON 解析复用已传递可用的 `jackson-databind`（channel→core→jackson）；HTTP 服务用 JDK `com.sun.net.httpserver`，**无新第三方依赖**。
- **配置**：`channels.<id>` 增量字段全部可选、默认安全（`port` 缺省由渠道取内置默认、`path`/`signing-secret` 缺省空），缺省 `enabled=false`，向后兼容。
- **安全**：新渠道均为入站边界——鉴权（webhook token / slack 签名）为可选加固；凭据不落日志；出站错误只回 `Error: ...` 文本，不含 token。不改既有权限 veto / 渠道 `channel-mode` 分轨（channel bridge 仍走 no-confirmer channel agent）。
- **测试**：离线单测——`WebhookCodec`/`SlackEventCodec` 纯映射与签名、`WebhookChannel.process`/`StdinPipeChannel.pump` 请求/行处理、`HttpChannelServer` 回环 round-trip 覆盖粘合、`ChannelFactory` 构造+门控、`ChannelAgentBridge` 路由（mock agent/kernel）、`ChannelConfig` 扩展字段缺省。
