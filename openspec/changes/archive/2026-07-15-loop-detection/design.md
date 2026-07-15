## Context

pig-agent 的 ReAct 智能体没有任何「重复动作」防线。三道既有安全网（`tool-permissions` 的 veto、`tool-availability` 的可见性门控、`tool-json-contract` 的异常兜底）都不覆盖「同一动作被高频重复」这一失败模式——模型陷进「读同一文件 / 跑同一失败命令 / 撞同一堵墙再重试」的死循环时，每轮都烧一次推理 + 一次工具调用，永不收敛。deerflow 的 `LoopDetectionMiddleware` 用滑动窗口 + 签名计数解决了这一问题。

本 spec 把它移植进来，但**遵循 pig 既有的设计骨架**：纯逻辑（检测器 + 签名策略）落 `pig-agent-core` 可离线单测，薄适配层（hook）接入 AgentScope 事件模型并**复用**既有的 veto-to-sentinel（`tool-permissions`）与 ephemeral 注入（`prefix-cache-context`）两套机制，不重造。

## Goals / Non-Goals

**Goals:**
- 纯、可离线单测的循环检测策略：滑动窗口（默认 20）+ 签名计数，`WARN@≥3` / `STOP@≥5`。
- `readFile` 按 200 行分桶（近似重复仍算重复）；`writeFile`/编辑哈希全量参数；其余通用哈希。
- hook 排在权限 veto 之后，`WARN` 注入提醒、`STOP` 复用哨兵改写强制收敛，两者都不污染持久化历史。
- 全部可配、默认安全、向后兼容；`enabled:false` 完全旁路。
- 按回合重置，不把计数泄漏到不相关的下一回合。

**Non-Goals:**
- 不做基于语义/embedding 的「近似循环」识别（只做签名精确 + `readFile` 桶级近似）。
- 不改任何工具的对外语义，不改权限/可用性/契约的行为契约。
- 不做跨进程/持久化的循环统计（窗口是进程内、按 agent 实例、按回合重置的）。
- 不为自主（数字员工）一次性 agent 单独接线（其每次 run 新建 agent = 天然新窗口；本次聚焦交互式 + 渠道，见 D7）。

## Decisions

### D1 — Strategy：签名计算独立成可替换策略 `ToolCallSignatureStrategy`
「如何把一次工具调用折叠成一个用于比较的签名」是本能力最易变、最需特化的点（`readFile` 分桶就是一种特化）。故抽成 Strategy 接口 `ToolCallSignatureStrategy.signature(toolName, input)`，默认实现 `DefaultToolCallSignatureStrategy` 承载分桶/哈希规则。`LoopDetector` 通过组合持有一个策略（默认注入 `DefaultToolCallSignatureStrategy`），策略可整体替换而不动窗口/阈值逻辑。这让「签名规则」与「窗口计数」两个关注点解耦，各自单测。

### D2 — `readFile` 按 200 行分桶；`writeFile`/编辑哈希全量参数；其余通用哈希
- **`readFile`**：签名 = `readFile|<path>|#<bucket>`，其中 `bucket = max(0, startLine) / 200`（整除）。`startLine` 从一组候选行参数名里取第一个数值（`offset`/`start_line`/`startLine`/`start`/`line`/`from`/`begin`），缺失即 0。**理由**：模型「读同一文件、每次错开几十行」是典型的伪进展循环；按 200 行分桶让「同一文件、同一 200 行段」的近似重复归为同一签名，仍被计成重复。当前内置 `readFile` 无行参数 → `startLine` 恒为 0 → 同一路径的反复读天然归一桶（正是想要的）。
- **`writeFile` / 编辑类**：**哈希全量参数**（含 `content`），**不分桶**——写同一文件但内容不同不是循环；只有「同名 + 同内容」重复才是。这落在通用分支（下条），无需为 `writeFile` 单列代码，但在设计上明确「写入不分桶」这一决策。
- **通用**：签名 = `toolName|sha256(canonical(input))`。`canonical` 递归按 key 排序序列化 map（参数顺序无关）、拼 list、标量取 `String.valueOf`，再取 SHA-256 hex。哈希让大参数（长 `content`）签名定长、窗口省内存。

### D3 — 滑动窗口 + 阈值：`LoopDetector` 封装状态，返回不可变 `LoopDecision`
`LoopDetector.observe(toolName, input)`：算签名 → `addLast` 入窗 → 窗满则 `removeFirst` 逐出最旧 → 统计当前签名在窗口内出现次数 `count` → `count>=stop → STOP`；`count>=warn → WARN`；否则 `OK`。窗口用 `ArrayDeque<String>`，`observe`/`reset` 都 `synchronized`（hook 可能在 reactive 线程被调）。构造参数**容错钳制**（默认安全）：`windowSize=max(1,ws)`、`warn=max(1,warn)`、`stop=max(warn,stop)`——非法配置永不抛、永不比 warn 还小的 stop。`reset()` 清空窗口。检测器是纯的、确定性的、无 AgentScope 依赖，`LoopDetectorTest` 全覆盖。

### D4 — Adapter：`LoopDetectionHook` 薄适配，复用两套既有机制
hook 只做「事件 ↔ 纯检测器」的翻译，不含策略逻辑（对标 `tool-permissions` 的「hook 是薄适配」）：
- **`PreActingEvent`（检测点）**：取 `getToolUse()`；若 hook 被禁用、或工具名在「忽略集」（本哨兵 `loopDetected` + 权限哨兵 `permissionDenied`）内 → 直通不计数。否则 `observe(name, input)`：
  - `STOP` → **复用 veto-to-sentinel**（`tool-permissions` 已验证的机制）：`setToolUse(...)` 把待执行 `ToolUseBlock` 改写为只读哨兵 `LoopDetectedTool`（保留原 `id`，`input=空`），真实工具不跑，模型收到哨兵返回的「你陷入循环、请停止并作答」。
  - `WARN` → 记下一个「待发提醒」标志（含工具名 + 次数），放行工具。
  - `OK` → 无动作。
- **`PreReasoningEvent`（提醒注入 + 回合重置）**：先按 D6 判回合边界（变化则 `reset()` 并清待发提醒）；否则若有「待发提醒」→ **复用 ephemeral 注入**（`prefix-cache-context` 的 `EphemeralMemoryContextHook` 同款做法）：把一条 user 角色的提醒 `Msg` 追加进 `getInputMessages()` 并 `setInputMessages(...)`，随后清标志。该注入落在 PreReasoning 的输入列表（每步由 `[system]+memory` 重建、从不写回 memory）→ **ephemeral，不污染持久化历史**。

**为何 WARN 走 PreReasoning 而非 PreActing**：`PreActingEvent` 事件 API 只暴露 `setToolUse`（无法在此追加一条独立消息）；把提醒放到「工具跑完后的下一个推理步」注入，恰好赶在模型做下一个决策前送达，且复用了 spec 明确点名的「reasoning 输入」注入路径。WARN 是尽力而为的软提醒（偶发漏发不致命），`STOP@≥5` 是硬保底。

### D5 — hook 次序：`priority()=10`（在权限 veto 之后、日志之前）
权限 hook `priority()=0` 最先跑。本 hook 取 `10`（> 0，spec 要求「在权限 veto 之后，以免干扰」；< 日志 hook 的 50/60）。**后果与应对**：一次被权限 veto 的调用在本 hook 看到时已被改写成 `permissionDenied`——故本 hook 把 `permissionDenied` 放进「忽略集」不计数（否则会把「被拒的重复」误当循环、且看到的是改写后的名而非原工具）。循环检测因此聚焦「真正会执行、真正烧 token」的读/网络/命令重复；权限拒绝的循环由权限系统自己的消息处理。

### D6 — 按回合重置：`PreReasoningEvent` 上用 user 消息计数变化识别新回合
「一个回合」= 一次用户消息触发的一次 agent 调用（内含多步 reasoning/acting）。为「不把计数泄漏到不相关的下一回合」，需在回合边界 `reset()`。**选定信号**：`PreReasoningEvent.getInputMessages()` 里 `MsgRole.USER` 消息的条数——回合内该数恒定（回合内只追加 assistant/tool 消息），仅当新用户回合到来（或切会话/压缩改写记忆）才变化。hook 记住 `lastUserMsgCount`，**变化即视为回合切换 → `reset()`**（增大=新回合、减小=切会话/压缩，都应重置）。**优点**：零额外接线（不动 `AgentKernel`/`SessionManager`），完全在 hook 内闭环；本 hook `priority()=10` 先于记忆 hook（50），看到的是注入记忆前的干净列表，计数不受 ephemeral 记忆消息干扰。

### D7 — 装配：交互式 + 渠道各自一个检测器实例；自主 agent 天然隔离
每个 agent 实例的 hook 列表里挂一个**独享** `LoopDetector`（与既有 per-agent hook 模式一致，互不串扰）。本次接入**交互式** hook 链（主战场）与**渠道** hook 链（渠道回合同样会循环烧钱）。**自主（数字员工）agent 不单独接线**：其每次 `run` 由 `AgentRunner` 新建一次性 agent（新 hook、新窗口），本就天然「一次一重置」，且其 `DeniedActionRecorder`/超时是另一套保底——留待后续按需接入，避免本次扩面。`enabled` 经 `BooleanSupplier` 实时读配置，`enabled:false` 时 hook 直通（完全旁路）。

### D8 — 哨兵工具落 `pig-agent-tools`，工具名常量落 `pig-agent-core`
`LoopDetectedTool`（`@Tool loopDetected()` 返回收敛提示）住 `pig-agent-tools`（对标 `PermissionDeniedTool`），经既有 SPI provider + `META-INF/services` 自动注册，并进 `AgentBootstrap` 手动兜底清单（两条注册路径都覆盖，与 `PermissionDeniedTool` 一致）。**工具名常量 `LoopDetectionHook.SENTINEL_TOOL_NAME = "loopDetected"` 定义在 core 的 hook 上**，`LoopDetectedTool.TOOL_NAME` 引用它（`pig-agent-tools` 已依赖 `pig-agent-core`）——单一事实源，不漂移。hook 改写目标名由 `AgentBootstrap` 用该常量注入（core 不反向依赖 tools）。风险分级：不登记进 `ToolRiskClassifier.DEFAULTS`（沿用 `permissionDenied` 的现状——未登记 → EXEC fail-safe，但哨兵只由 hook 内部改写产生、模型不会主动调，且本 hook 把它放进忽略集，无副作用）。

## Risks / Trade-offs

- **R1 — 误报（正常任务被判循环）**：阈值宽松（连续 5 次**完全相同**签名才硬停；`readFile` 才分桶，写入按全量参数）。→ 正常任务几乎不可能连发 5 次同签名；真要放宽/收紧可配。默认安全。
- **R2 — 与权限 veto 的次序耦合**：权限先改写，本 hook 看到 `permissionDenied`。→ D5 把它放忽略集；循环检测聚焦「会执行的」重复，语义清晰。
- **R3 — WARN 注入偶发漏发**：若模型在被 WARN 的 acting 之后直接收尾、没有下一个 PreReasoning，提醒不送达。→ WARN 本就是软提醒；`STOP@≥5` 是硬保底，不依赖 WARN 成功。
- **R4 — 回合计数信号的健壮性**：依赖 `PreReasoningEvent` 输入里 user 消息计数。→ 回合内恒定、跨回合必变；压缩/切会话导致计数变化也只是「多重置一次」，无害。本 hook 先于记忆注入 hook 跑，不受 ephemeral 记忆消息影响。
- **R5 — 哨兵 `loopDetected` 进模型 schema**：模型理论上可主动调它。→ 与 `permissionDenied` 完全同构、现状可接受；调用只返回一句无害提示。

## 落实追踪表

| 评审/发现项 | 落点 | 状态 |
|---|---|---|
| 签名策略可替换（Strategy） | D1；spec R1；task 2.x | 已实现 |
| `readFile` 200 行分桶 / 写入全量哈希 / 通用哈希 | D2；spec R1；task 2.x | 已实现 |
| 滑动窗口 + warn@3/stop@5 + 容错钳制 | D3；spec R2；task 3.x | 已实现 |
| hook 复用 veto-to-sentinel（STOP） | D4/D8；spec R3；task 4.x/5.x | 已实现 |
| hook 复用 ephemeral 注入（WARN） | D4；spec R4；task 4.x | 已实现 |
| hook 次序 priority=10（权限之后） | D5；spec R3；task 4.x | 已实现 |
| 忽略哨兵 + 权限拒绝名（不计数） | D5；spec R3；task 4.x | 已实现 |
| 按回合重置（user 计数变化） | D6；spec R5；task 4.x | 已实现 |
| 配置块 default-safe + 向后兼容 | proposal；spec R2；task 1.x | 已实现 |
| 交互式 + 渠道接线；自主天然隔离 | D7；spec R3；task 5.x | 已实现 |
| 哨兵工具 + 常量单一事实源 | D8；spec R3；task 5.x | 已实现 |
| 文档同步（CLAUDE.md） | proposal Impact；task 6.x | 已实现 |
