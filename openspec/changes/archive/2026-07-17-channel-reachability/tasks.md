## 1. `/channel` 运维命令（可达性）

- [x] 1.1 `ChannelType` 增 `functional` 构造参数 + `isFunctional()`：钉钉/飞书/Webhook/Stdin=true，Telegram/Discord/Slack=false（桩/无法回帖）。
- [x] 1.2 `PigAgentConfig` 增 `setChannels(Map)`（供 `/channel` 安全替换整张 map；null→`Map.of()`）。
- [x] 1.3 新增 `repl/command/ChannelCommand`：`list|add|remove|enable|disable|test`（+ `help`），走 `ConfigurationManager` CRUD + 从 `bridges` 读实时状态。
- [x] 1.4 `list` 用安全标签（传输 + 非秘密提示，绝不打 token/URL/secret）+ 桩标注 + 工作渠道页脚。
- [x] 1.5 `add` 经 JLine 掩码 `readLine(prompt,'*')` 录入秘密；只提供 functional 类型；存配置 + enabled=true。
- [x] 1.6 `enable`/`test` 对桩返回「该渠道尚未实现（占位）」；`enable` 持久化开关 + 提示下次启动连接。
- [x] 1.7 `test`：机器人（有 webhook-url）→ 真实出站投递；入站 HTTP → 端口 bind 校验；stdin → 随 CLI 运行说明；已运行 → 短路。
- [x] 1.8 `ReplCommands.build()` 注册 `/channel` + `/help` 增条目。

## 2. 诚实（去广告桩）

- [x] 2.1 `WorkspaceManager` 种子 `channels` 块重写：telegram/discord 标 `未实现/占位 (stub)`；钉钉/飞书/Webhook/Stdin 作注释示例；文档化 `channel-gateway` 非必需。
- [x] 2.2 `PigAgentCli.startChannels` 跳过启动被启用的桩（清晰 WARN，不再对死桩打「started」），两条路径一致。

## 3. 网关前置（决策）

- [x] 3.1 默认路径显式文档化：enabled + functional 渠道启动**与 opt-in `channel-gateway` 内核开关无关**；`/channel enable` 持久化 + 明确「下次启动连接」（不在会话内强行拉起传输）。

## 4. 0600 渠道秘密（安全）

- [x] 4.1 `WorkspaceManager` 写种子 `application.yaml` 后 `restrictToOwner`（POSIX 0600，非 POSIX 空操作）。
- [x] 4.2 `ConfigurationManager.saveConfig` 每次写后 `restrictToOwner`。

## 5. `/notify test` 真实投递

- [x] 5.1 `ChannelStrategy.send` 改返回 `boolean`；`DingTalkStrategy`/`FeishuStrategy` 回传 `sender.post`（无 webhook-url→false）。
- [x] 5.2 `JdkWebhookSender.post` 读响应体，机器人级 `errcode`/`code`/`StatusCode` 非零→false（只记码值，不记正文/凭据）；`WebhookSender` 契约文档更新。
- [x] 5.3 `StrategyHttpChannel.send(recipient,notification)` 回传真实结果；`sendMessage` 忽略。
- [x] 5.4 `NotifyCommand`：`status`/`test` 检测配置渠道为只入站（无 `OutboundChannel`）→「该渠道无出站能力」；`test` 遇此拒绝、不触达服务。

## 6. 测试

- [x] 6.1 `ChannelCommandTest`：list 安全标签/桩标注/空态、add 掩码录入 + 存配置、enable 桩拒绝/功能置位、disable、remove、test 桩拒绝/无 webhook-url。
- [x] 6.2 `ChannelTypeTest`：`isFunctional` 工作/桩分类。
- [x] 6.3 `JdkWebhookSenderTest`：errcode 非零→false、零→true、各平台字段解析、非 JSON→0。
- [x] 6.4 `NotifyCommandTest`：只入站渠道 status 提示 + test 拒绝不触达服务。
- [x] 6.5 `WorkspaceManagerTest`：种子含工作渠道 + 标桩、`application.yaml` 0600（POSIX 条件）。
- [x] 6.6 `ConfigurationManagerTest`：saveConfig 0600（POSIX 条件）。
- [x] 6.7 更新既有 fake（`StrategyHttpChannelTest`/`StrategyHttpChannelOutboundTest` 的 `send` 返回 boolean），不破坏既有渠道/CLI 测试。
- [x] 6.8 `mvn -pl pig-agent-cli -am test` 全绿。
