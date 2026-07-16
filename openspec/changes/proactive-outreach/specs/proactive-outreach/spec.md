## ADDED Requirements

### Requirement: 出站渠道能力 OutboundChannel
系统 SHALL 提供 `OutboundChannel` 能力接口（`boolean send(String recipient, Notification notification)`），作为渠道的**可选**能力，用于**主动**给指定接收人发一条未被请求的通知（区别于 `Channel.sendMessage` 的会话内回帖）。`OutboundChannel.send` MUST NOT 抛异常（内部捕获，失败返回 `false`）。渠道 MAY 实现该能力——只入站的渠道 MUST NOT 被强制实现（不实现即天然不参与外呼，优雅降级）。`StrategyHttpChannel`（钉钉/飞书）MUST 实现 `OutboundChannel`：把 `Notification` 渲染为文本后经其 `ChannelStrategy` 的自定义机器人 webhook 出站（尽力而为）。该 seam MUST 保持窄——MUST NOT 在其中实现会话管理/多 agent 路由（留给上层/未来原生渠道内核）。接收人 token MUST NOT 出现在日志中。

#### Scenario: 出站发送经渲染委托 Strategy
- **WHEN** 对实现了 `OutboundChannel` 的 `StrategyHttpChannel` 调 `send(recipient, notification)`
- **THEN** 通知被渲染为文本并经 `ChannelStrategy.send` 出站，返回 `true`（尽力而为），且不抛异常

#### Scenario: 只入站渠道不实现出站
- **WHEN** 一个渠道未实现 `OutboundChannel`
- **THEN** 它不参与主动外呼路由，且不因此报错（`NotificationService` 视之为「无出站渠道」）

### Requirement: Notification 值对象与 NotificationService seam
系统 SHALL 提供不可变值对象 `Notification`，承载 `type`（`NotificationType`）、`severity`（`Severity`）、`title`、`body`、可选 `action`（`OutreachAction`，如 yes/no 请求）、`targetChannel`（空则取默认）、`recipient`（空则取默认）、`dedupKey`（空则由 `title`+`body` 派生）。`Notification` MUST 归一 null 字段为安全缺省，MUST 提供 `urgent()`（`severity` 为 URGENT）、`effectiveDedupKey()` 与 `withXxx` 拷贝方法（不可变）。系统 SHALL 提供框架中立的 `NotificationService` seam（`NotificationResult notify(Notification)`），把「真正发送」藏在渠道 seam 之后（可 mock、可单测）。`NotificationResult` MUST 携带 `Outcome`（`DELIVERED`/`DISABLED`/`QUIET_HOURS`/`RATE_LIMITED`/`DUPLICATE`/`NO_CHANNEL`/`FAILED`）与 detail，并提供 `delivered()`/`suppressed()`。这些类型 MUST 位于 `pig-agent-core`（框架中立，不依赖任何渠道/调度实现），以便迁移到未来原生渠道内核时逻辑不变。

#### Scenario: 不可变构造与派生去重键
- **WHEN** 以 `Notification.of(type, severity, title, body)` 构造并 `withTarget`/`withDedupKey`
- **THEN** 返回新的不可变实例（原实例不变）；未显式设 `dedupKey` 时 `effectiveDedupKey()` 由 `title`+`body` 派生，显式设则用显式值

#### Scenario: 结果语义
- **WHEN** 检查 `NotificationResult`
- **THEN** `DELIVERED` 时 `delivered()` 为真；`DISABLED`/`QUIET_HOURS`/`RATE_LIMITED`/`DUPLICATE` 时 `suppressed()` 为真

### Requirement: 通知路由 ChannelNotificationService
系统 SHALL 提供 `ChannelNotificationService implements NotificationService`（`pig-agent-channel`），按目标渠道 id 经查找函数路由到对应 `OutboundChannel`。`notify(n)` MUST：① 先过 `OutreachGate`，裁决非 `ALLOW` 时**早退**并返回对应结果（`DISABLED`/`QUIET_HOURS`/`RATE_LIMITED`/`DUPLICATE`），MUST NOT 查找或发送（禁用即 no-op）；② 目标渠道/接收人为空时取配置默认；③ 目标渠道 id 为空或查不到对应 `OutboundChannel` 时返回 `NO_CHANNEL` 且 detail 给出**有帮助**的说明（含渠道 id，MUST NOT 含接收人）；④ `OutboundChannel.send` 返回 `true`→`DELIVERED`、`false`→`FAILED`、抛异常→`FAILED`（捕获，detail 经凭据脱敏，MUST NOT 外抛）。日志 MUST 只含渠道 id/类型/严重度/裁决，MUST NOT 含接收人。

#### Scenario: 路由到正确出站渠道
- **WHEN** 通知目标渠道为 `feishu` 且存在对应 `OutboundChannel`，且护栏放行
- **THEN** 调用该渠道的 `send(recipient, n)`，返回 `DELIVERED`

#### Scenario: 无出站渠道给有帮助的错误
- **WHEN** 目标渠道无对应 `OutboundChannel`
- **THEN** 返回 `NO_CHANNEL`，detail 说明缺少该渠道 id 的出站渠道（不含接收人），且不抛异常

#### Scenario: 禁用即 no-op
- **WHEN** `outreach.enabled=false`（护栏裁决 `DISABLED`）
- **THEN** `notify` 不查找、不发送，返回 `DISABLED`

#### Scenario: 发送失败不外抛
- **WHEN** `OutboundChannel.send` 返回 `false` 或抛异常
- **THEN** 返回 `FAILED`，不外抛，detail 已脱敏

### Requirement: 防打扰护栏 OutreachPolicy + OutreachGate
系统 SHALL 提供不可变 `OutreachPolicy`（`enabled` + 免打扰时段 `QuietHours`（`enabled`/`start`/`end`，`isQuiet` MUST 处理跨午夜窗口）+ 限流（窗口内最大条数 `maxPerWindow`≤0 为不限 + `rateWindow`）+ 去重窗口 `dedupWindow`≤0 为不去重）与纯逻辑门 `OutreachGate`（注入 `Clock`、线程安全）。`OutreachGate.evaluate(n)` MUST 依次判定并返回 `OutreachDecision`：`enabled=false`→`DISABLED`；去重键在窗口内重复→`DUPLICATE`（**对所有消息生效，紧急亦然**）；非紧急且处于免打扰时段→`QUIET_HOURS`；非紧急且超限→`RATE_LIMITED`；否则记录本次发送并返回 `ALLOW`。**紧急（URGENT）MUST 绕行免打扰时段与限流**（24h 助手必须能发紧急告警）；被放行的紧急发送 MUST 计入限流窗口（防洪）。护栏 MUST 为纯、可单测（固定时钟）。

#### Scenario: 正常放行并记录
- **WHEN** 启用、非重复、非免打扰、未超限的通知
- **THEN** 返回 `ALLOW` 并记录本次发送（计入去重键与限流窗口）

#### Scenario: 去重拦截重复（含紧急）
- **WHEN** 同一去重键在去重窗口内二次到达（无论是否紧急）
- **THEN** 返回 `DUPLICATE`，不重复发送

#### Scenario: 免打扰拦截非紧急、紧急绕行
- **WHEN** 当前处于免打扰时段
- **THEN** 非紧急通知返回 `QUIET_HOURS`；紧急通知返回 `ALLOW`（绕行）

#### Scenario: 限流拦截非紧急、紧急绕行
- **WHEN** 限流窗口内已达 `maxPerWindow`
- **THEN** 非紧急通知返回 `RATE_LIMITED`；紧急通知返回 `ALLOW`（绕行）；窗口滑出后非紧急恢复放行

#### Scenario: 跨午夜免打扰
- **WHEN** 免打扰配置为跨午夜窗口（如 22:00–08:00）
- **THEN** `isQuiet` 对午夜两侧的时刻均正确判定为免打扰

### Requirement: 定时外呼触发器
系统 SHALL 提供框架中立的调度 seam `OutreachScheduler`（`schedule(String id, String cron, Runnable action)`）与 `ScheduledOutreach` 触发策略（给定 id、cron、`NotificationService`、`Supplier<Notification>`）。`ScheduledOutreach.arm(scheduler)` MUST 以其 id/cron 注册一个「到点调用 `NotificationService.notify(supplier.get())`」的 runnable。CLI MUST 把 `OutreachScheduler` 适配到既有 `TaskScheduler.schedule(id, TaskSchedule.cron(expr), action)`（复用现有调度，不新造）。该 seam MUST 可注入假实现离线单测。

#### Scenario: 到点触发 notify
- **WHEN** `ScheduledOutreach.arm` 注册后其调度动作被触发
- **THEN** 以供给的 `Notification` 调用 `NotificationService.notify`

#### Scenario: 以正确 id/cron 注册
- **WHEN** 调 `arm(scheduler)`
- **THEN** `scheduler.schedule` 收到该触发器的 id 与 cron 表达式

### Requirement: 晨报推送触发器
系统 SHALL 提供 `NotificationReportWriter implements AgentRunner.ReportWriter`，把数字员工的晨报（`AgentReport`）映射为 `REPORT` 类型 `Notification`（severity 随 outcome：FAILURE/TIMEOUT→HIGH，否则 NORMAL）并经 `NotificationService.notify` **推**到渠道；`write` MUST 容错（服务异常记录但不外抛，不破坏 run）。系统 SHALL 提供 `CompositeReportWriter implements AgentRunner.ReportWriter`（Composite），把 `write` 扇出给多个 `ReportWriter`（如 `FileReportWriter` + `NotificationReportWriter`），单个失败 MUST NOT 影响其余。该推送 MUST 仅在 `outreach.enabled && report-push.enabled` 时接入，不改 `AgentRunner` 契约（晨报仍始终写文件）。

#### Scenario: 晨报被推送
- **WHEN** `report-push` 启用且数字员工产生晨报
- **THEN** `NotificationReportWriter` 以 `REPORT` 通知调用 `NotificationService.notify`；文件晨报仍照常写出

#### Scenario: 组合扇出容错
- **WHEN** `CompositeReportWriter` 的某个子 writer 抛异常
- **THEN** 其余子 writer 仍被调用（互不影响）

### Requirement: agent 面 notifyUser 工具
系统 SHALL 提供 `@Tool` `notifyUser(title, message, urgent)`，让 agent **主动**通知用户。该工具 MUST 在 `ToolRiskClassifier` 中分级为 `NETWORK`（受权限体系管控）。该工具 MUST 实现 `ToolAvailability`：`outreach.enabled=false` 时判定为不可用（不进模型 schema，`/status` 显示原因），故禁用即零行为变化。成功（`DELIVERED`）MUST 返回正常成功文案；护栏抑制（`DISABLED`/`QUIET_HOURS`/`RATE_LIMITED`/`DUPLICATE`）MUST 返回**普通**状态文案（非 `{"error"}`，避免模型重试循环）；`NO_CHANNEL`/`FAILED` MUST 返回规范 `{"error":"<reason>"}`（`ToolErrors`，reason 已脱敏、只含渠道 id）。工具输出 MUST NOT 回显接收人。该工具 MUST 经 SPI（`NotifyUserToolProvider` + services 文件）自动注册，且当 `ToolContext.notificationService()` 为空时 provider MUST 返回 null（不注册）。

#### Scenario: 成功通知
- **WHEN** 护栏放行且发送成功
- **THEN** 工具返回成功文案，不回显接收人

#### Scenario: 抑制返回普通状态
- **WHEN** 护栏抑制（如 quiet-hours / rate-limited / duplicate）
- **THEN** 工具返回说明未发送原因的普通文案（非 `{"error"}`）

#### Scenario: 失败返回规范错误
- **WHEN** 无出站渠道或发送失败
- **THEN** 工具返回 `{"error":"<脱敏 reason>"}`，reason 不含接收人

#### Scenario: 禁用则隐藏
- **WHEN** `outreach.enabled=false`
- **THEN** `notifyUser` 的可用性判定为不可用，不进入模型工具 schema

### Requirement: /notify 运维命令
系统 SHALL 提供 `/notify` 运维命令：`test [message]` 经 `NotificationService` 发一条测试通知并显示 `NotificationResult`（outcome + detail）；`status` 显示 outreach 配置（开关、默认渠道、免打扰时段、限流、去重窗口）。`/notify status` MUST NOT 显示接收人值（只显示是否已配）。

#### Scenario: 测试发送
- **WHEN** 执行 `/notify test hello`
- **THEN** 经 `NotificationService.notify` 发送并显示结果 outcome/detail

#### Scenario: 状态不泄露接收人
- **WHEN** 执行 `/notify status`
- **THEN** 显示开关/默认渠道/免打扰/限流/去重，但不显示接收人值

### Requirement: outreach 配置块（默认关闭、向后兼容）
`PigAgentConfig` SHALL 提供 `outreach` 配置块，全部可选、默认安全：`enabled`（默认 `false`）、`channel`/`recipient`（默认目标）、`quiet-hours`（`enabled`/`start`/`end`）、`rate-limit`（`max-per-window`/`window-minutes`）、`dedup-window-minutes`、`briefing`（`enabled`/`cron`/`title`/`body`）、`report-push`（`enabled`）。缺 `outreach` 块 MUST 等价于 `enabled=false`（不外呼、`notifyUser` 隐藏、无定时简报、无晨报推送），旧 `application.yaml` MUST 照常解析（向后兼容）。接收人凭据 MUST NOT 入日志。

#### Scenario: 缺省关闭
- **WHEN** 配置无 `outreach` 块
- **THEN** `enabled=false`，无任何外呼行为（向后兼容）

#### Scenario: 配置往返
- **WHEN** 设置 `outreach.enabled=true` 及子字段并写回读取
- **THEN** 各字段被正确解析与保留
