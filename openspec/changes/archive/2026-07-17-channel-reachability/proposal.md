## Why

一次评审发现：**用户今天根本连不上任何渠道**。渠道子系统能力存在（钉钉/飞书/Webhook/Stdin 有真实传输），但**不可达、不诚实、不安全**：

1. **无 in-app 管理**——只有只读的 `/channels` 状态命令；要加渠道唯一的办法是手改 `application.yaml` 再重启。
2. **种子配置只文档化了 `telegram` + `discord`**，而这俩是**纯桩**（`start()`/`sendMessage()` 只打日志）；真正能用的渠道（钉钉/飞书/Webhook/Stdin）根本没进种子。
3. **凭据明文无 0600**——`application.yaml` 的 `token`/`signSecret` 明文写盘且不像 `models.json`/`mcp.json` 那样收敛权限。
4. **`/notify test` 假成功**——钉钉/飞书 `send` fire-and-forget，机器人以 HTTP 200 + 非零 `errcode` 拒绝时仍报「Sent」。

这不是新增能力，而是**把已有渠道能力做成真正可达、诚实、安全**。

## What Changes

- **`/channel list|add|remove|enable|disable|test` 运维命令（`pig-agent-cli`，镜像 `/mcp`）**：对 `ConfigurationManager` 的 `channels` 配置做 CRUD + 从已启动 bridges 读实时状态；秘密经 JLine 掩码 `readLine(prompt,'*')` 录入；`list` 只打**安全标签**（传输 + 非秘密提示，如「webhook-url set」，绝不打原始 token/URL/secret）；`test` 尝试连接/投递并给出友好结果。注册进 `ReplCommands.build()` + `/help`。
- **诚实标注桩（`ChannelType.isFunctional()`）**：Telegram/Discord（及无法回帖的 Slack 出站）标为非功能桩。种子配置里明确标 `未实现/占位 (stub — not functional yet)`，并把能用的渠道（钉钉/飞书/Webhook/Stdin）作为注释示例列出；`/channel enable|test` 对桩返回「该渠道尚未实现（占位）」而非假装成功；`PigAgentCli.startChannels` 跳过启动被启用的桩并打清晰 WARN（不再对死桩打「Channel started」）。**不实现真实网络客户端**（超范围）。
- **网关前置（决策）**：启动时**只要 enabled + functional 的渠道就启动，与 opt-in 的原生 `channel-gateway` 内核开关无关**（默认路径本就如此——本次显式文档化 + 跳桩加固）；`/channel enable` 持久化开关并明确告知「下次启动连接」（不在会话内强行拉起传输，也不强制打开原生 Gateway 内核）。
- **0600 渠道秘密（安全）**：`WorkspaceManager` 写种子 `application.yaml` 后、`ConfigurationManager.saveConfig` 每次写后，在 POSIX 上把文件收敛为属主可读写（`restrictToOwner`，非 POSIX 空操作），镜像 `JsonModelStore`/`JsonMcpStore`。
- **`/notify test` 真实投递**：`ChannelStrategy.send` 改为返回 `boolean`；`JdkWebhookSender.post` 读响应体、机器人级 `errcode`/`code`/`StatusCode` 非零→`false`（只记录码值，绝不记录正文/凭据），`StrategyHttpChannel.send(recipient,notification)` 回传真实结果，故 `/notify test` 只在真正 2xx+errcode==0 时报「Sent」。`/notify status|test`：配置的 `outreach.channel` 是只入站渠道（无 `OutboundChannel`）时明确提示「该渠道无出站能力」。

## Capabilities

### Added Capabilities
- `channel-reachability`: 让已有渠道能力**真正可达、诚实、安全**——`/channel` 运维 CRUD（掩码录入 + 安全标签 + 桩拒绝）、`ChannelType.isFunctional()` 桩标注（种子配置 + 启动跳桩）、网关无关的启动、`application.yaml` 0600、`/notify test` 机器人 errcode 真实投递校验 + 只入站渠道提示。**不新增网络客户端**（桩只标注）。

## Impact

- **代码**：
  - `pig-agent-channel`——`ChannelType` 增 `functional` 标志 + `isFunctional()`；`ChannelStrategy.send` 返回 `boolean`（`DingTalkStrategy`/`FeishuStrategy` 回传 `sender.post`）；`StrategyHttpChannel.send` 回传真实结果；`WebhookSender`/`JdkWebhookSender` 读体查 errcode；两处测试 fake 的 `send` 改返回 `boolean`。
  - `pig-agent-cli`——新增 `repl/command/ChannelCommand`；`ReplCommands` 注册 `/channel` + `/help`；`NotifyCommand` errcode 表述 + 只入站提示；`PigAgentCli.startChannels` 跳桩 + 网关无关文档。
  - `pig-agent-config`——`PigAgentConfig` 增 `setChannels(...)`（供 CRUD 安全替换整张 map）；`ConfigurationManager.saveConfig` 收敛 0600。
  - `pig-agent-workspace`——`WorkspaceManager` 种子 `channels` 块重写（桩标注 + 能用渠道注释示例）+ `application.yaml` 0600。
- **依赖**：无新第三方依赖（errcode 解析复用已在 `pig-agent-channel` 的 Jackson）。
- **安全**：秘密录入掩码、`list` 只打安全标签、写盘 0600；`/notify` 不因机器人拒绝而假报成功。
- **向后兼容**：`/channels`（只读状态）保留；默认路径的渠道启动行为不变（只多了「跳桩 + 文档」）；无渠道配置 → 零行为变化。
- **测试**：离线单测——`ChannelCommand`（list 安全标签/桩标注、add 掩码录入 + 存配置、enable 桩拒绝/功能置位、disable、remove、test 桩拒绝/无 webhook-url）、`ChannelType.isFunctional`、`JdkWebhookSender` errcode（非零→false/零→true/各平台字段）、`/notify` 只入站提示、种子配置内容 + `application.yaml` 0600（POSIX 条件断言）、`ConfigurationManager` 0600。真实渠道往返由后续真渠道 `*IT` 覆盖。
