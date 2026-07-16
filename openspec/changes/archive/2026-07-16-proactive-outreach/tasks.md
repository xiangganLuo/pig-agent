## 1. Notification 值对象 + 服务 seam（core，framework-neutral）

- [x] 1.1 `NotificationTest`/`NotificationResultTest`：`Notification.of` + `withTarget`/`withAction`/`withDedupKey` 不可变拷贝；null 归一（title/body→""、severity→NORMAL、type→MESSAGE）；`urgent()`；`effectiveDedupKey()`（显式键 vs 派生 title+body）；`NotificationResult` outcome + `delivered()`/`suppressed()`
- [x] 1.2 `pig-agent-core` 新增 `outreach/{NotificationType, Severity, OutreachAction, Notification, NotificationResult, NotificationService}`（record/enum/接口，纯 JDK）
- [x] 1.3 测试全绿

## 2. 防打扰护栏 OutreachPolicy + OutreachGate（core，纯）

- [x] 2.1 `OutreachPolicyTest`：缺省/`disabled()`；`QuietHours.isQuiet` 同日窗口 + 跨午夜窗口 + 边界；容错归一（负窗口/条数）
- [x] 2.2 `OutreachGateTest`（注入固定 `Clock`）：`enabled=false`→`DISABLED`；正常→`ALLOW`；同键窗口内二次→`DUPLICATE`（含 urgent）；非紧急免打扰内→`QUIET_HOURS`、紧急同时段→`ALLOW`（绕行）；非紧急超限→`RATE_LIMITED`、紧急超限→`ALLOW`（绕行）；窗口滑出后恢复；线程安全（记录后计数）
- [x] 2.3 `outreach/{OutreachPolicy, OutreachDecision, OutreachGate}`（`OutreachGate` 注入 `Supplier<OutreachPolicy>` + `Clock`，`synchronized evaluate`）
- [x] 2.4 测试全绿

## 3. 出站渠道能力 OutboundChannel + StrategyHttpChannel（channel）

- [x] 3.1 `NotificationRendererTest`：`render(Notification)` 出「标题 + 正文（+ action 提示）」纯文本，容错空字段
- [x] 3.2 `StrategyHttpChannelOutboundTest`（记录型 `FakeStrategy`/`RecordingSender`）：`StrategyHttpChannel implements OutboundChannel`；`send(recipient,n)` → `strategy.send(渲染文本)` 被调、返回 true；不抛
- [x] 3.3 `pig-agent-channel` 新增 `outreach/{OutboundChannel, NotificationRenderer}`；`StrategyHttpChannel implements Channel, OutboundChannel`（`send` 渲染→`strategy.send`，尽力而为 true，永不抛）
- [x] 3.4 测试全绿

## 4. 路由服务 ChannelNotificationService（channel）

- [x] 4.1 `ChannelNotificationServiceTest`（mock `OutboundChannel` + 假 lookup + 注入 gate）：目标 `feishu` 有出站→`send` 被调 + `DELIVERED`；目标无出站渠道→`NO_CHANNEL`（detail 含渠道 id、不含接收人）；`send` 返回 false→`FAILED`；`send` 抛→`FAILED`（不外抛）；禁用（gate `DISABLED`）→不查不发 `DISABLED`（no-op）；`QUIET_HOURS`/`RATE_LIMITED`/`DUPLICATE` 映射；空目标→取默认渠道/接收人；日志不含接收人
- [x] 4.2 `outreach/ChannelNotificationService implements NotificationService`（lookup + gate + 默认渠道/接收人 supplier；早退禁用/抑制；无出站→NO_CHANNEL）
- [x] 4.3 测试全绿

## 5. 定时触发 OutreachScheduler + ScheduledOutreach（channel）

- [x] 5.1 `ScheduledOutreachTest`（假 `OutreachScheduler` 捕获 runnable + mock `NotificationService`）：`arm` 以正确 id/cron 注册；跑捕获的 runnable → `notify(supplier.get())` 被调
- [x] 5.2 `outreach/{OutreachScheduler(@FunctionalInterface), ScheduledOutreach}`
- [x] 5.3 测试全绿

## 6. 晨报推送 NotificationReportWriter + CompositeReportWriter（channel + core）

- [x] 6.1 `CompositeReportWriterTest`（core）：`write` 扇出多个 `ReportWriter`；一个抛不影响其余（容错）
- [x] 6.2 `NotificationReportWriterTest`（channel，mock `NotificationService`）：`write(spec,report)` → 构造 `REPORT` 通知（severity 随 outcome）→ `notify` 被调；service 抛不外抛（容错）
- [x] 6.3 `pig-agent-core` 新增 `agent/runner/CompositeReportWriter implements AgentRunner.ReportWriter`；`pig-agent-channel` 新增 `outreach/NotificationReportWriter implements AgentRunner.ReportWriter`
- [x] 6.4 测试全绿

## 7. agent 工具 notifyUser（tools）

- [x] 7.1 `NotifyUserToolTest`（mock `NotificationService`）：`DELIVERED`→成功文案；抑制→普通状态串（非 error）；`NO_CHANNEL`/`FAILED`→`{"error"}`（`ToolErrors`，含渠道 id、**不含接收人**）；`ToolAvailability`：enabled→AVAILABLE、disabled→unavailable(原因)；构造/参数容错（urgent 宽松解析）
- [x] 7.2 `NotifyUserToolProviderTest`：`ctx.notificationService()==null`→null（不注册）；非空→返回工具实例
- [x] 7.3 `ToolRiskClassifierTest` 增：`notifyUser`→`NETWORK`
- [x] 7.4 `tool/notify/NotifyUserTool`（`@Tool` + `implements ToolAvailability`）；`spi/providers/NotifyUserToolProvider` + services 行；`ToolContext` 增 `notificationService`(可空)+`outreachEnabled`(BooleanSupplier)；`ToolRiskClassifier.DEFAULTS` 增 `notifyUser`→NETWORK
- [x] 7.5 测试全绿

## 8. /notify 运维命令（cli）

- [x] 8.1 `NotifyCommandTest`（mock `NotificationService`）：`test [msg]`→`notify` 被调 + 显示结果；`status`→显示开关/默认渠道/免打扰/限流/去重，**不显示 recipient 值**
- [x] 8.2 `cli/repl/command/NotifyCommand`；`ReplContext` 增 `notificationService`；`ReplCommands` 注册 + `/help`；`AgentRepl` 构造传入
- [x] 8.3 测试全绿

## 9. 配置 outreach 块（config）

- [x] 9.1 `OutreachConfigTest`：缺省（`enabled=false`、子块安全缺省）；setter 往返；YAML 往返；旧配置（无 outreach 块）向后兼容
- [x] 9.2 `PigAgentConfig` 增 `OutreachConfig`（+ `QuietHoursConfig`/`RateLimitConfig`/`BriefingConfig`/`ReportPushConfig`）+ getter/setter
- [x] 9.3 测试全绿

## 10. 装配 wiring（cli）

- [x] 10.1 `AgentBootstrap`：由 `OutreachConfig` 建 `OutreachPolicy`（`Supplier` 活取）→ `OutreachGate` → 空 `ChannelRegistry outreachRegistry` → `ChannelNotificationService`（惰性 lookup）→ 注入 `ToolContext`(`notificationService`+`outreachEnabled`) + `Services`；`report-push.enabled` 时用 `CompositeReportWriter` 包 `FileReportWriter`+`NotificationReportWriter`；`briefing.enabled` 时 `ScheduledOutreach.arm(taskScheduler 适配为 OutreachScheduler)`
- [x] 10.2 `PigAgentCli.main`：`startChannels` 后把每个渠道注册进 `s.outreachRegistry`；`AgentRepl` 传 `s.notificationService`
- [x] 10.3 `mvn -q -pl pig-agent-cli -am compile` 绿

## 11. 集成 + 回归 + 安全复查

- [x] 11.1 全量 `mvn -q test` **单线程** BUILD SUCCESS，0 失败 0 错误（记净增测试数）
- [x] 11.2 `mvn -q -pl pig-agent-cli -am compile` BUILD SUCCESS
- [x] 11.3 安全复查：接收人/凭据不入日志/工具输出/命令回显；`notifyUser`=NETWORK 受权限管控；护栏防刷屏生效；禁用即零行为变化
