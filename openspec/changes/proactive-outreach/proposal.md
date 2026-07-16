## Why

今天助手是**被动**的——只有被人 @ 到（渠道入站、REPL 输入、定时数字员工写晨报到 `workspace/reports/`）才会动。北极星要的是一个 **24h 常驻超级助手**：它得能**主动找人**——在某个触发点，**主动**经渠道给用户推一条消息（每日简报、任务提醒、需要你拍板的事、异常告警）。当前渠道层只有「入站→回复」（`Channel.sendMessage` 是对当前会话的回帖），没有「无人问也能给指定接收人发一条」的**出站**能力；也没有「谁来触发主动外呼」和「怎么不打扰人」的护栏。

AgentScope 2.0 有**原生渠道内核 Gateway**（会话管理 + 每会话并发 + agentId 路由 + 原生飞书/钉钉/企业微信等适配器），但官方**以回复为主、未记载主动推送/push**——**主动外呼正是 2.0 原生渠道内核的缺口**，也正是本能力要盖的「房子」。要把它做成能长期演进、且**能平滑迁到 2.0** 的能力，最干净的做法是用**设计模式**分层，且**框架中立**（渠道/调度/通知都是 pig 自有抽象，不耦合 AgentScope agent 内核，不在传输层反造会话/多 agent 路由——那是 Gateway 的活）：

1. **出站能力**用**能力接口 + 策略**（`OutboundChannel`，按 `ChannelType` 由已有 Strategy 实现，可选——只入站的渠道不实现即可），且**越薄越好**（被 v2 原生适配器取代时代价最小）。
2. **通知**用**不可变值对象 + 服务 seam**（`Notification` + `NotificationService`，纯、可单测，真正发送藏在渠道 seam 后，可 mock）。
3. **触发器**用**策略**（定时 via 既有 `TaskScheduler`；事件/agent 发起：数字员工晨报可选推渠道 + agent 经工具请求外呼）。
4. **防打扰护栏**（一个 24h 助手绝不能变成骚扰源）用**纯值对象 + 门**（`OutreachPolicy` + `OutreachGate`：限流 + 去重 + 免打扰时段，紧急绕行）。

全部**默认关闭**（`outreach.enabled=false`）——不配置即零行为变化。

## What Changes

- **出站渠道能力（设计模式：Capability 接口 + 复用 ChannelType 枚举注册表 + Strategy）**：新增 `OutboundChannel` 能力接口（`boolean send(String recipient, Notification)`，MUST NOT 抛），作为渠道的**可选、窄** seam——渠道 MAY 实现（只入站的渠道不实现，天然优雅降级）。已有 `StrategyHttpChannel`（钉钉/飞书）实现之：把 `Notification` 渲染成文本经其 `ChannelStrategy` 的自定义机器人 webhook 出站（**真实出站，尽力而为**）。Telegram/Discord/Slack/Webhook/Stdin 仍为入站/桩，不实现出站（诚实标注）。send 实现刻意薄，v2 迁移时由原生 Gateway/适配器接管此 seam。
- **`Notification` 值对象 + `NotificationService` seam（框架中立，落在 `pig-agent-core`）**：不可变 `Notification`（`NotificationType` 类型 + `Severity` 严重度 + 标题 + 正文 + 可选 `OutreachAction`（如 yes/no 请求）+ 目标渠道 + 接收人 + 去重键）。`NotificationService.notify(Notification): NotificationResult` 是纯 seam——真正发送藏在渠道 seam 后。实现 `ChannelNotificationService`（`pig-agent-channel`）按目标渠道 id 经查找函数路由到对应 `OutboundChannel`；**无出站渠道 → 返回有帮助的错误结果**（`NO_CHANNEL`，不抛）；先过 `OutreachGate` 护栏。
- **防打扰护栏（纯值对象 + 门，落在 `pig-agent-core`）**：`OutreachPolicy`（不可变：`enabled` + 免打扰时段（含跨午夜）+ 限流（窗口内最大条数）+ 去重窗口）+ `OutreachGate`（有状态、注入 `Clock`、线程安全）。裁决 `OutreachDecision`：`ALLOW`/`DISABLED`/`QUIET_HOURS`/`RATE_LIMITED`/`DUPLICATE`。**紧急（URGENT）绕行免打扰时段 + 限流**（24h 助手必须能发紧急告警），**去重对所有消息始终生效**（同一条不在窗口内重复发，紧急亦然——真正的新紧急告警去重键不同）。纯逻辑、可单测。
- **触发器（设计模式：Strategy + 调度 seam）**：
  - **定时**：新增 `OutreachScheduler` 函数式 seam（`schedule(id, cron, Runnable)`）——由 CLI 适配到既有 `TaskScheduler.schedule(id, TaskSchedule.cron(expr), action)`（框架中立，可注入假实现单测）。`ScheduledOutreach`（Strategy）：给定 cron + `Supplier<Notification>` + `NotificationService`，`arm(scheduler)` 注册一个「到点 `notify(supplier.get())`」的 runnable（如每日 09:00 推简报）。
  - **事件/agent 发起**：新增 `NotificationReportWriter implements AgentRunner.ReportWriter`（`pig-agent-channel`）——数字员工晨报除写文件外，可选**推**到渠道（经 `CompositeReportWriter` 组合 `FileReportWriter` + 推送 writer，Composite 模式）；不改 `AgentRunner` 契约。agent 经 `notifyUser` 工具主动 ping 用户。
- **agent 面工具 `notifyUser`（`pig-agent-tools`）**：`@Tool` `notifyUser(title, message, urgent)`，让 agent 主动通知用户。在 `ToolRiskClassifier` 分级为 **NETWORK**（受权限体系管控）。实现 `ToolAvailability`——`outreach.enabled=false` 时**不进模型 schema**（隐藏，`/status` 显示原因），故禁用即零行为变化。失败走 `{"error"}` 契约（`ToolErrors`），**接收人绝不回显**。经 SPI（`NotifyUserToolProvider` + services 文件）自动注册；`ToolContext` 增可空 `notificationService` + `outreachEnabled`。
- **`/notify` 运维命令（`pig-agent-cli`）**：`status`（显示 outreach 配置：开关、默认渠道、免打扰时段、限流、去重——**不显示接收人值**）、`test [message]`（发一条测试通知并显示裁决/结果）。
- **配置 `outreach` 块（`pig-agent-config`，全部可选、默认安全、默认关闭）**：`enabled`（默认 false）、`channel`/`recipient`（默认目标）、`quiet-hours`、`rate-limit`、`dedup-window-minutes`、`briefing`（定时简报）、`report-push`（晨报推渠道）。缺 `outreach` 块 → 一切照旧（向后兼容）。
- **凭据/接收人卫生**：接收人 token 绝不入日志/工具输出/命令回显（复用 `CredentialSanitizer`；服务日志只打渠道 id/类型/严重度，不打接收人）。

## Capabilities

### Added Capabilities
- `proactive-outreach`: 助手**主动外呼 + 通知**能力——出站渠道能力（`OutboundChannel` + `StrategyHttpChannel` 实现）、`Notification` 值对象 + `NotificationService` 路由 seam + `ChannelNotificationService`、防打扰护栏（`OutreachPolicy` + `OutreachGate`：限流/去重/免打扰，紧急绕行）、触发器（定时 `ScheduledOutreach` via `OutreachScheduler`/`TaskScheduler` + 晨报推送 `NotificationReportWriter`/`CompositeReportWriter`）、`notifyUser` 工具（NETWORK + 可用性门 + `{"error"}` 契约）、`/notify` 运维命令、`outreach` 配置块（默认关闭）。框架中立（seam 均为 pig 自有接口，填补 2.0 原生渠道内核的主动推送缺口，可迁 v2）。

## Impact

- **代码**：
  - `pig-agent-core`——新增 `outreach/{Notification, NotificationType, Severity, OutreachAction, NotificationResult, NotificationService, OutreachPolicy, OutreachGate, OutreachDecision}`；`agent/runner/CompositeReportWriter`。
  - `pig-agent-channel`——新增 `outreach/{OutboundChannel, NotificationRenderer, ChannelNotificationService, OutreachScheduler, ScheduledOutreach, NotificationReportWriter}`；`StrategyHttpChannel implements OutboundChannel`。调度不新增对 `pig-agent-task` 的依赖——经 `OutreachScheduler` seam（在 CLI 适配）。
  - `pig-agent-config`——`PigAgentConfig` 增 `OutreachConfig`（+ `QuietHoursConfig`/`RateLimitConfig`/`BriefingConfig`/`ReportPushConfig`），`@JsonIgnoreProperties` 沿用。
  - `pig-agent-tools`——新增 `notify/NotifyUserTool` + `spi/providers/NotifyUserToolProvider` + services 行；`ToolContext` 增 `notificationService`/`outreachEnabled`；`ToolRiskClassifier` 增 `notifyUser`→NETWORK。
  - `pig-agent-cli`——`AgentBootstrap` 装配 `OutreachPolicy`/`OutreachGate`/`ChannelNotificationService`（惰性渠道查找经空 `ChannelRegistry`）→ 注入 `ToolContext` + `Services`；组合报告 writer；armed 定时简报；`PigAgentCli.main` 启动渠道后把渠道注册进 outreach registry；`ReplContext`/`AgentRepl` 传 `notificationService`；新增 `/notify` 命令 + `/help`。
- **依赖**：**无新第三方依赖**——渲染纯字符串，出站复用既有渠道 webhook，调度复用既有 `TaskScheduler`，值对象纯 JDK（`java.time`）。
- **配置**：`outreach.*` 全部可选、默认安全，`enabled` 默认 false——不配即不外呼，向后兼容。
- **安全**：接收人 token 不入日志/工具输出/命令回显；`notifyUser` 分级 NETWORK 受权限 veto 管控；护栏防刷屏（限流/去重/免打扰）。
- **框架中立 / v2 可迁**：`NotificationService`/`OutboundChannel`/`OutreachScheduler` 均为 pig 自有 seam，不依赖 AgentScope agent 内核；本能力填补 2.0 原生渠道内核的「主动推送缺口」，迁 v2 时 send seam 对接 `Gateway`/`ChatUiChannel`/原生适配器，外呼逻辑（路由/去重/护栏/触发器）原样保留。
- **测试**：离线单测——`OutreachGate`（限流/去重/免打扰/紧急绕行/禁用）、`OutreachPolicy`+`QuietHours` 跨午夜、`ChannelNotificationService` 路由（选对渠道 / 无出站 → 有帮助错误 / 禁用→no-op / 各裁决）、`ScheduledOutreach`（假 scheduler 到点 notify）、`NotificationReportWriter` + `CompositeReportWriter`（seam 被调用、组合扇出）、`StrategyHttpChannel` 出站（假渲染→strategy.send）、`NotifyUserTool`（成功/抑制/失败 `{"error"}`/接收人不回显 + 可用性门）、`NotifyUserToolProvider`（禁用→null）、`OutreachConfig` 缺省 + 往返、`ToolRiskClassifier` notifyUser=NETWORK、`/notify` 命令。渠道真实出站由后续**真渠道 IT** 覆盖（本次 mock 出站，离线）。
