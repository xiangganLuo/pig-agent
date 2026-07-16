## Context

需求源：北极星「24h 常驻超级助手」的**主动外呼 + 通知**刚需。基线 = `main`（已含 `pig-agent-channel` 的 `Channel`/`ChannelType` 枚举注册表/`ChannelStrategy`+`StrategyHttpChannel`/飞书钉钉；`pig-agent-task` 的 `TaskScheduler.schedule(id,TaskSchedule,Runnable)` 真 cron；`pig-agent-core` 数字员工 `AgentRunner`+`AgentRunner.ReportWriter`+`FileReportWriter`→`workspace/reports/`；`pig-agent-tools` 的 SPI 自动注册 + `ToolRiskClassifier` + `ToolAvailability` 门 + `{"error"}` 契约）。

已读源确认的关键事实：
- `Channel`：`start(Consumer<String>)`/`sendMessage(String)`/`stop`/`isRunning`/`channelId`/`displayName`。`sendMessage` 是**对当前会话/webhook 的回帖**，非「给指定接收人主动发」。
- `StrategyHttpChannel implements Channel`：`sendMessage(msg)` 委托 `strategy.send(msg)`（钉钉/飞书经自定义机器人 webhook 出站，`ChannelStrategy.send` 返回 void、内部永不抛、失败自记 warn）。
- `TaskScheduler.schedule(String id, TaskSchedule, Runnable)`：DELAYED/CRON（真 5 段 cron，自重排），ONCE 忽略。
- `AgentRunner`：构造入 `ReportWriter`（函数式 `write(spec, report)`）；`FileReportWriter` 写 `{reportsDir}/{date}/{id}.md`，容错不抛。`AgentReport`（record：outcome/body/pending/note）。
- `ToolContext`（`pig-agent-tools`）：不可变 holder，字段可空，`ToolProvider.create(ctx)` 返回 null 即不注册。SPI 列在 `META-INF/services/io.pigagent.tool.spi.ToolProvider`。
- `ToolRiskClassifier.DEFAULTS`：字符串键 → 风险；`fetchUrl`=NETWORK。
- `ToolAvailability`：`availabilityToolNames()` + `checkAvailability()`；不可用工具被 `ToolAvailabilityGate` 移出 schema。
- `AgentBootstrap.build`：装配 toolkit（先 SPI 自动注册 built-in，再插件，再 MCP）→ `ToolContext` 在 built-in 注册**之前**构造；`Services` holder 交给 CLI。渠道在 `PigAgentCli.main` 里 `AgentBootstrap.build` **之后**启动。

约束：AgentScope 1.0.12；不可变领域类型（record + `withXxx`）；SLF4J（不 `System.out`）；JUnit5+Mockito+AssertJ AAA；文件 <800 行、函数 <50 行；离线单测（mock 渠道出站）；无新第三方依赖；凭据/接收人不外泄；**框架中立、可迁 v2**。

## Goals / Non-Goals

**Goals:**
- 助手能在触发点**主动**经渠道给用户发一条通知（简报/提醒/待决/告警）。
- **框架中立、可迁 2.0**：填补 2.0 原生渠道内核（Gateway）「主动推送缺口」；外呼逻辑（通知路由/去重/护栏/触发器）为 pig 自有「房子」，传输 `send` 是**窄 seam**——v2 由原生 Gateway/`ChatUiChannel`/原生适配器接管，逻辑不变。
- 用设计模式分层：Capability 接口（`OutboundChannel`）、值对象 + 服务 seam（`Notification`/`NotificationService`）、纯值对象 + 门（`OutreachPolicy`/`OutreachGate`）、Strategy 触发器（定时 + 晨报推送 + agent 发起）、Composite（报告 writer）、调度 seam（`OutreachScheduler`）。
- 防打扰护栏：限流 + 去重 + 免打扰时段，紧急绕行。纯、可单测。
- **默认关闭**，不配即零行为变化；`notifyUser` 工具用可用性门隐藏（禁用即不进 schema）。
- 接收人/凭据不外泄。

**Non-Goals:**
- **不在传输层反造会话管理 / 每会话并发 / 多 agent 路由**——那是 2.0 Gateway 的活，v1 send 越薄越好。
- Telegram/Discord/Slack/Webhook/Stdin 升级为真实出站——仍入站/桩，本次只让 `StrategyHttpChannel`（钉钉/飞书）真实出站（尽力而为）。
- 富文本/卡片/按钮交互式回执——`OutreachAction` 仅承载文案（yes/no 提示），实际回执走既有渠道入站，不做双向绑定。
- 出站真实投递确认——`ChannelStrategy.send` 为 fire-and-forget（返回 void、内部记失败），故 `StrategyHttpChannel.send` 返回**尽力而为** true；真实投递状态 + 真渠道往返留**真渠道 IT**。
- 持久化外呼历史/审计存储——去重/限流状态为进程内内存（重启清空）；持久化留后续。

## Decisions

- **D1 出站能力接口 `OutboundChannel`（Capability 接口，窄 seam，复用 ChannelType/Strategy）。** `boolean send(String recipient, Notification n)`——渠道的**可选**能力，MUST NOT 抛（内部捕获→false）。渠道 MAY 实现（只入站的不实现，天然降级）。`StrategyHttpChannel implements Channel, OutboundChannel`：`send` = `strategy.send(NotificationRenderer.render(n))` 后返回 `true`（尽力而为——`ChannelStrategy.send` 是 void fire-and-forget，出站 webhook 失败已在 `Jdk WebhookSender` 内记 warn）。**不改** `ChannelStrategy` 契约（保持 channel-expansion 归档 spec 稳定；真实投递确认留 IT）。**取舍**：send 刻意薄且不碰会话/路由，v2 被原生适配器取代时代价最小。

- **D2 `Notification` 值对象 + `NotificationService` seam（框架中立，`pig-agent-core.outreach`）。** 
  - `NotificationType`（enum）：`BRIEFING`/`REMINDER`/`REPORT`/`ALERT`/`ACTION_REQUEST`/`MESSAGE`。
  - `Severity`（enum）：`LOW`/`NORMAL`/`HIGH`/`URGENT`，`isUrgent()`=`==URGENT`。
  - `OutreachAction`（record，可空）：`prompt` + `options`（如 `["yes","no"]`），静态 `yesNo(prompt)`。
  - `Notification`（record，不可变）：`type`/`severity`/`title`/`body`/`action`(可空)/`targetChannel`(空→默认)/`recipient`(空→默认)/`dedupKey`(空→由 title+body 派生)。紧凑构造归一（null→安全缺省）；`urgent()`=`severity.isUrgent()`；`effectiveDedupKey()` 派生；`withTarget`/`withAction`/`withDedupKey` 拷贝方法；静态 `of(type,severity,title,body)`。
  - `NotificationResult`（record）：`Outcome`（`DELIVERED`/`DISABLED`/`QUIET_HOURS`/`RATE_LIMITED`/`DUPLICATE`/`NO_CHANNEL`/`FAILED`）+ `detail`；`delivered()`/`suppressed()` 便捷。
  - `NotificationService`（接口 seam）：`NotificationResult notify(Notification)`。纯、可 mock。**取舍**：值对象 + seam 放 core（框架中立），实现放 channel（有向依赖 channel→core 已存在，无环）。

- **D3 路由实现 `ChannelNotificationService`（`pig-agent-channel.outreach`）。** 依赖：`Function<String,Optional<OutboundChannel>> lookup`（按渠道 id 查出站渠道）、`OutreachGate gate`、`Supplier<String> defaultChannel`、`Supplier<String> defaultRecipient`（config 活取）。`notify(n)`：① `gate.evaluate(n)` 非 ALLOW → 映射为 `DISABLED`/`QUIET_HOURS`/`RATE_LIMITED`/`DUPLICATE` 结果，**早退不查不发**（禁用→no-op）；② 解析目标渠道/接收人（空→默认）；③ 渠道 id 空 → `NO_CHANNEL`；④ `lookup` 空 → `NO_CHANNEL`（**有帮助错误**，含渠道 id——非机密）；⑤ `channel.send(recipient,n)` true→`DELIVERED`/false→`FAILED`，异常→`FAILED`（sanitize）。日志只打渠道 id/类型/严重度/裁决——**不打接收人**。

- **D4 护栏 `OutreachPolicy`（纯值对象）+ `OutreachGate`（门，注入 `Clock`，线程安全）。**
  - `OutreachPolicy`（record）：`enabled` + `QuietHours quietHours`（record：`enabled`/`start`/`end`(LocalTime)，`isQuiet(LocalTime)` 处理跨午夜）+ `int maxPerWindow`（≤0=不限）+ `Duration rateWindow` + `Duration dedupWindow`（≤0=不去重）。紧凑构造容错归一；静态 `disabled()`。
  - `OutreachDecision`（enum）：`ALLOW`/`DISABLED`/`QUIET_HOURS`/`RATE_LIMITED`/`DUPLICATE`，`allowed()`。
  - `OutreachGate`：`Supplier<OutreachPolicy>` + `Clock`（可注入固定时钟测跨午夜/窗口）；状态 `Deque<Instant> recentAllowed`（限流窗口）+ `Map<String,Instant> lastByKey`（去重）。`synchronized evaluate(n)`：
    1. `!enabled` → `DISABLED`。
    2. `dedupWindow>0` 且 `lastByKey[key]` 在窗口内 → `DUPLICATE`（**所有消息**，紧急亦然）。
    3. `!urgent`：免打扰（`quietHours.enabled && isQuiet(now)`）→ `QUIET_HOURS`；限流（剪枝窗口外后 `size>=maxPerWindow`）→ `RATE_LIMITED`。
    4. 记录：`lastByKey.put(key,now)` + `recentAllowed.add(now)`（**紧急也记**，计入窗口防洪）→ `ALLOW`。
  - **紧急旁路语义（显式决策）**：URGENT **绕行免打扰 + 限流**（24h 助手必须能发紧急告警）；**去重始终生效**（同一条不在窗口内重复；新紧急告警去重键不同故不受影响）。

- **D5 触发器（Strategy + 调度 seam）。**
  - **定时**：`OutreachScheduler`（`@FunctionalInterface schedule(String id,String cron,Runnable)`）——框架中立调度 seam。`ScheduledOutreach`（record/class）：`id`+`cron`+`NotificationService`+`Supplier<Notification>`；`arm(scheduler)`=`scheduler.schedule(id,cron,this::fire)`；`fire()`=`service.notify(supplier.get())`。CLI 适配 `(id,cron,a)->taskScheduler.schedule(id,TaskSchedule.cron(cron),a)`。测试用假 scheduler 捕获 runnable 跑之，验 `notify` 被调。
  - **事件/晨报推送**：`NotificationReportWriter implements AgentRunner.ReportWriter`（`pig-agent-channel`——依赖 core 的 `AgentReport`/`ReportWriter` + `NotificationService`，无环）：把 `AgentReport` 映射为 `Notification`（type=`REPORT`，severity：FAILURE/TIMEOUT→`HIGH` 否则 `NORMAL`，title=「晨报 · {name}」，body=摘要 + 待决数，dedupKey=agentId+日期），`service.notify`；容错不抛。`CompositeReportWriter`（`pig-agent-core.agent.runner`，Composite）：`write` 扇出多个 `ReportWriter`，逐个容错。`AgentBootstrap` 在 `outreach.enabled && report-push.enabled` 时 `new CompositeReportWriter(fileWriter, notifyWriter)`。
  - **agent 发起**：`notifyUser` 工具（见 D6）。

- **D6 `notifyUser` 工具（`pig-agent-tools.notify`）。** `@Tool notifyUser(@ToolParam title, @ToolParam(name="message") body, @ToolParam(name="urgent") String urgent)`：构造 `Notification`（type=`urgent?ALERT:MESSAGE`，severity=`urgent?URGENT:NORMAL`，目标空→服务默认），`service.notify`：`DELIVERED`→返回「已通知用户」；抑制（`DISABLED`/`QUIET_HOURS`/`RATE_LIMITED`/`DUPLICATE`）→返回**普通**状态串（非 error，避免模型重试循环）如「未发送：quiet-hours（护栏）」；`NO_CHANNEL`/`FAILED`→`ToolErrors.message(detail)`（`{"error"}`，detail 已 sanitize、只含渠道 id，**绝不含接收人**）。`implements ToolAvailability`：`availabilityToolNames()={"notifyUser"}`，`checkAvailability()`=`enabled?AVAILABLE:unavailable("outreach disabled (set outreach.enabled)")`——禁用即不进 schema。`ToolRiskClassifier` 增 `notifyUser`→`NETWORK`。SPI：`NotifyUserToolProvider`（`ctx.notificationService()==null`→返回 null 不注册）+ services 行。`ToolContext` 增 `notificationService`(可空) + `outreachEnabled`(BooleanSupplier)。

- **D7 `/notify` 运维命令（`pig-agent-cli`）。** `status`：显示 `enabled`/默认渠道/免打扰时段/限流/去重窗口（**不显示 recipient 值**，只显示是否已配）。`test [message]`：`service.notify(测试 Notification)`，显示 `NotificationResult`（outcome + detail）。`ReplContext`/`AgentRepl` 传 `NotificationService`。

- **D8 配置最小扩展，默认关闭（`pig-agent-config`）。** `PigAgentConfig` 增 `@JsonProperty("outreach") OutreachConfig`：`enabled`(false)/`channel`(默认渠道 id)/`recipient`(默认接收人)/`quiet-hours`(`enabled`/`start`/`end`)/`rate-limit`(`max-per-window`/`window-minutes`)/`dedup-window-minutes`/`briefing`(`enabled`/`cron`/`title`/`body`)/`report-push`(`enabled`)。全部可选、安全缺省；缺块=默认关闭；沿用 `@JsonIgnoreProperties(ignoreUnknown=true)`（`ConfigurationManager` mapper 级）。

- **D9 惰性渠道查找解决装配顺序（`AgentBootstrap` 早于渠道启动）。** `ToolContext`/`NotificationService` 在 `AgentBootstrap.build` 构造，但渠道在 `PigAgentCli.main` 之后才启动。故 `AgentBootstrap` 建**空** `ChannelRegistry outreachRegistry`，`ChannelNotificationService` 的 `lookup = id -> outreachRegistry.findById(id).filter(OutboundChannel).map(cast)`（惰性——发通知时才解析）；`outreachRegistry` 挂 `Services`，`PigAgentCli.main` 启动每个渠道后 `outreachRegistry.register(channel)`。定时简报的 runnable 到点才 `notify`，那时渠道已注册。**取舍**：复用既有 `ChannelRegistry`（可变 registry）而非新造，惰性查找避免装配环。

- **D10 凭据/接收人卫生。** 接收人 token 绝不入日志/工具输出/命令回显：`ChannelNotificationService` 日志不含接收人；`notifyUser` 输出不回显接收人；`/notify status` 只显示「已配/未配」不显示值；错误经 `CredentialSanitizer`/`ToolErrors`。

## Risks / Trade-offs

- **[框架演进] v2 原生渠道内核可能接管出站** → 已按协调方向把 `send` 做成**窄 seam**（`OutboundChannel.send(recipient,Notification)`），不碰会话/多 agent 路由；外呼逻辑（`NotificationService`/`OutreachGate`/触发器）框架无关。迁 v2：只需一个把 seam 对接 `Gateway`/原生适配器的实现，逻辑零改。design 已显式记录此路径。
- **[出站真实性] 仅钉钉/飞书真实出站，且为尽力而为** → `ChannelStrategy.send` 是 void fire-and-forget，`StrategyHttpChannel.send` 返回尽力而为 true；Telegram/Discord/Slack/Webhook/Stdin 不实现 `OutboundChannel`（诚实标注）。真实投递确认 + 真渠道往返留**真渠道 IT**（本次离线 mock 出站，`ChannelNotificationService` 的 `FAILED` 路径由 mock 返回 false 覆盖）。
- **[护栏状态] 进程内内存，重启清空** → 去重/限流窗口为内存态；重启后可能重发一条。可接受（外呼非幂等关键路径）；持久化留后续。
- **[免打扰误伤] 紧急旁路** → URGENT 绕行免打扰 + 限流，可能在深夜发；这是**刻意**（紧急告警必须触达）。非紧急严格受限。去重对紧急仍生效防重复轰炸。
- **[装配顺序] 惰性查找** → 若在渠道启动前调 `notify`（如 REPL `/notify test` 但渠道未配）→ `NO_CHANNEL` 有帮助错误，不崩。可接受。
- **[配置误用] 默认渠道未实现出站** → 如默认 `channel=telegram`（无 `OutboundChannel`）→ `NO_CHANNEL`。`/notify status` + 结果 detail 提示，文档标注默认应指向钉钉/飞书。

## 落实追踪表（评审/取向发现项 → 落点 + 状态）

| 发现项 / 取向 | 落点（决策/任务） | 状态 |
|---|---|---|
| 出站能力抽象（可选、按渠道实现、复用 Strategy） | D1（OutboundChannel + StrategyHttpChannel）/ T3 | 已实现 |
| Notification 值对象 + NotificationService seam（纯、可 mock） | D2（core.outreach）/ T1 | 已实现 |
| 路由到对应出站渠道；无出站→有帮助错误 | D3（ChannelNotificationService）/ T4 | 已实现 |
| 防打扰护栏：限流 + 去重 + 免打扰，紧急绕行 | D4（OutreachPolicy + OutreachGate）/ T2 | 已实现 |
| 定时触发（cron via 既有 TaskScheduler） | D5（OutreachScheduler + ScheduledOutreach）/ T5 | 已实现 |
| 晨报可选推渠道（事件触发，不改 AgentRunner） | D5（NotificationReportWriter + CompositeReportWriter）/ T6 | 已实现 |
| agent 发起外呼工具 notifyUser（NETWORK） | D6（NotifyUserTool + 分级 + 可用性门 + {"error"}）/ T7 | 已实现 |
| /notify 运维命令（test + status） | D7 / T8 | 已实现 |
| 配置 outreach 块，默认关闭、向后兼容 | D8（OutreachConfig）/ T9 | 已实现 |
| 装配顺序（bootstrap 早于渠道启动）经惰性查找解决 | D9（空 ChannelRegistry + 惰性 lookup）/ T10 | 已实现 |
| 接收人/凭据不外泄 | D10 / 各类 + 复查 T11 | 已实现 |
| 框架中立 / 填补 2.0 推送缺口 / send seam 可被 Gateway 接管 | D1+D2+D5（seam）+ 本节 Risks | 已实现（文档 + 结构） |
| 真实投递确认 + 真渠道往返 | Non-Goals（真渠道 IT） | 延后 |
| 外呼历史/审计持久化 | Non-Goals（内存态护栏） | 延后 |
| Telegram/Discord/Slack 真实出站 | Non-Goals（仅钉钉/飞书） | 延后 |
| 富文本/卡片/交互式回执 | Non-Goals | 延后 |

## Migration Plan

- 纯增量、默认安全：不配 `outreach` → `enabled=false` → `notifyUser` 隐藏、无定时简报、无晨报推送、`/notify` 仅显示 disabled；渠道/数字员工/工具行为不变。
- 开启：`outreach.enabled=true` + `channel`/`recipient`（指向钉钉/飞书）→ `notifyUser` 进 schema、`/notify test` 可发；`briefing.enabled` 起定时简报；`report-push.enabled` 起晨报推送。
- v2 迁移：`NotificationService`/`OutreachGate`/触发器保留；仅把 `OutboundChannel.send` 的实现从 pig 渠道换成对接 2.0 `Gateway`/`ChatUiChannel`/原生适配器的薄适配。
- 回滚：删除 `outreach` 配置即等于未引入；新增类均为新增，删除即回原状。

## Open Questions

- 默认渠道/接收人是否该支持多接收人/广播——本次单默认渠道 + 单默认接收人（`Notification` 可携带具体 target 覆盖），多播留后续。
- 交互式回执（yes/no 按钮回调绑定到 pending 决策）——本次 `OutreachAction` 仅文案，回执走既有入站，不做闭环。
- 护栏状态持久化（跨重启去重/限流）——本次内存态，留后续。
