## ADDED Requirements

### Requirement: 渠道运维命令 `/channel`
系统 MUST 提供一个操作者面的 `/channel` 命令，支持 `list|add|remove|enable|disable|test`，对 `channels.<id>` 配置做 CRUD（经 `ConfigurationManager` 持久化到 `application.yaml`）并从已启动的渠道读实时状态。秘密（token / signing secret / webhook-url）录入 MUST 经掩码输入，`list` 输出 MUST 只显示安全标签（传输类型 + id + 非秘密提示），MUST NOT 回显任何原始 token / URL / 秘密值。

#### Scenario: 列出渠道只显示安全标签
- **WHEN** 用户执行 `/channel list`，且某渠道配置了 `webhook-url` / `sign-secret` / `token`
- **THEN** 输出显示该渠道 id、传输名与「webhook-url set」「secret set」之类的布尔提示，且不含任何原始 URL / token / 秘密值

#### Scenario: 新增渠道时掩码录入秘密
- **WHEN** 用户执行 `/channel add` 并输入一个秘密字段
- **THEN** 该秘密经掩码 `readLine(prompt,'*')` 读取（不回显到滚动缓冲），配置被持久化且该渠道被启用

### Requirement: 诚实标注非功能桩渠道
每个渠道 MUST 声明它是否为可用传输（`ChannelType.isFunctional()`）。Telegram / Discord（及无法回帖的 Slack）MUST 标为非功能桩。种子 `application.yaml` MUST 把桩明确标注为「未实现/占位 (stub — not functional yet)」，并把可用渠道（钉钉/飞书/Webhook/Stdin）作为示例呈现。`/channel enable` 与 `/channel test` 对桩 MUST 返回「该渠道尚未实现（占位）」而非假装成功；启动时被启用的桩 MUST 被跳过并记录清晰告警（不得对死桩打印「Channel started」）。

#### Scenario: 拒绝启用桩渠道
- **WHEN** 用户执行 `/channel enable telegram`
- **THEN** 命令拒绝并提示「该渠道尚未实现（占位）」，且不把该渠道置为启用

#### Scenario: 种子配置标桩并呈现可用渠道
- **WHEN** 新工作区被初始化
- **THEN** 种子 `application.yaml` 把 telegram/discord 标为占位/stub，并以注释示例呈现 dingtalk/feishu/webhook/stdin

### Requirement: 启用的渠道启动与 Gateway 内核开关无关
被启用且功能可用的渠道 MUST 在启动时被拉起，且**不依赖** opt-in 的原生 `channel-gateway` 内核开关（该开关只选择原生 Gateway 路由引擎 + 原生平台适配器）。`/channel enable` MUST 持久化启用开关并明确告知渠道在下次启动时连接，且 MUST NOT 强制打开原生 Gateway 内核。

#### Scenario: 无网关内核也能启动渠道
- **WHEN** 一个功能渠道 enabled 且 `channel-gateway.enabled` 为默认的 false
- **THEN** 该渠道在启动时被拉起（作为 pig 自有适配器），不被网关开关阻塞

### Requirement: 渠道秘密静态 0600
写入 `application.yaml` 时（种子写入与每次 `updateConfig` 保存）系统 MUST 在 POSIX 文件系统上把该文件权限收敛为仅属主可读写（0600），非 POSIX 上为空操作——镜像 `models.json` / `mcp.json` 的做法。

#### Scenario: 保存后收敛为属主可读写
- **WHEN** 在 POSIX 文件系统上，`application.yaml` 被种子写入或经 `updateConfig` 保存
- **THEN** 该文件的权限为 `rw-------`（0600）

### Requirement: 通知投递如实反馈
经渠道机器人 webhook 出站的通知 MUST 校验真实投递结果——一个以 HTTP 200 携带非零 `errcode`/`code`/`StatusCode` 拒绝消息的机器人 MUST 被判为失败。`/notify test` MUST 仅在真正 2xx 且机器人错误码为 0 时报告成功；否则报告失败。当配置的 `outreach.channel` 是只入站渠道（无 `OutboundChannel` 能力）时，`/notify status` 与 `/notify test` MUST 明确提示「该渠道无出站能力」，且 `test` 不触达通知服务。校验/日志 MUST NOT 回显响应正文或凭据。

#### Scenario: 机器人以 200 + 非零 errcode 拒绝
- **WHEN** 机器人 webhook 返回 HTTP 200 但正文 `errcode` 非零
- **THEN** 发送被判为失败，`/notify test` 报告「Not sent」而非「Sent」，且日志只记录数字错误码、不含正文/凭据

#### Scenario: 只入站渠道被明确提示
- **WHEN** 配置的 `outreach.channel` 是一个已启动但只入站的渠道（如 webhook）
- **THEN** `/notify status` 标注「该渠道无出站能力」，`/notify test` 拒绝并不触达通知服务
