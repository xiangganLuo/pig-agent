## ADDED Requirements

### Requirement: 纯策略循环检测器按滑动窗口计数并按阈值判决

系统 MUST 提供一个纯的、可离线单测的循环检测器 `LoopDetector`，维护一个固定容量的滑动窗口（默认 20）记录近期工具调用签名。每次观察一个工具调用（`observe(toolName, input)`）时，检测器 SHALL：把该调用折叠成一个签名并入窗、窗满时逐出最旧签名、统计当前签名在窗口内的出现次数，并返回一个判决 `LoopDecision`：出现次数 `≥ stop-threshold`（默认 5）→ `STOP`；`≥ warn-threshold`（默认 3）→ `WARN`；否则 `OK`。检测器 MUST 提供 `reset()` 清空窗口。构造参数（窗口大小、warn/stop 阈值）MUST 默认安全：非法/越界值（`≤0`、或 `stop < warn`）SHALL 被容错钳制而非抛错。检测器 MUST NOT 依赖 AgentScope 运行时类型（纯逻辑）。

#### Scenario: 无重复调用不触发
- **WHEN** 依次观察一串**签名各不相同**的工具调用
- **THEN** 每次判决均为 `OK`，不注入提醒、不否决

#### Scenario: 同一调用达到 warn 阈值
- **WHEN** 同一签名的调用被连续观察到第 3 次（默认 `warn-threshold=3`）
- **THEN** 该次判决为 `WARN`

#### Scenario: 同一调用达到 stop 阈值
- **WHEN** 同一签名的调用被连续观察到第 5 次（默认 `stop-threshold=5`）
- **THEN** 该次判决为 `STOP`

#### Scenario: 窗口逐出后计数回落
- **WHEN** 窗口容量为 N，且在某签名的两次出现之间插入 ≥N 个其它签名，使前一次出现被逐出窗口
- **THEN** 当前签名在窗口内的计数据实回落，判决据回落后的计数给出（不把已逐出的历史计入）

#### Scenario: 重置清空计数
- **WHEN** 调用 `reset()` 后再次观察此前已接近阈值的同一签名
- **THEN** 计数从头累计，判决为 `OK`

### Requirement: 签名策略特判 readFile 分桶并对写入哈希全量参数

系统 MUST 通过一个可替换的签名策略 `ToolCallSignatureStrategy` 计算工具调用签名，默认实现 MUST：
- 对 `readFile` 按 200 行一个桶分段——签名由 `toolName` + 文件路径 + `max(0, 起始行) / 200`（整除得到的桶号）构成，使**同一文件、行区间只是略有不同（落在同一 200 行桶内）的重复读取归为同一签名**（仍被计成重复）；起始行从一组候选行参数名中取，缺失时视为 0；
- 对 `writeFile`/编辑类及其余工具，签名由 `toolName` + **全量参数的规范化哈希**构成（不分桶），使写同一文件但内容不同 NOT 归为同一签名；
- 规范化 MUST 与参数 map 的 key 顺序无关（顺序不同、内容相同的参数得到相同签名）。

#### Scenario: readFile 近似行区间归为重复
- **WHEN** 对同一路径的 `readFile` 以落在同一 200 行桶内的不同起始行（如 0、50、100）重复调用
- **THEN** 三次得到相同签名，被检测器计成同一签名的重复

#### Scenario: readFile 跨桶不算同一签名
- **WHEN** 对同一路径的 `readFile` 以相差 ≥200 行、落在不同桶的起始行调用
- **THEN** 得到不同签名

#### Scenario: writeFile 内容不同不算重复
- **WHEN** 对同一路径的 `writeFile` 以不同 `content` 调用
- **THEN** 得到不同签名（写入哈希全量参数、不分桶）

#### Scenario: 参数顺序不影响签名
- **WHEN** 同一工具、同一组参数但 map 中 key 顺序不同
- **THEN** 得到相同签名

### Requirement: 循环检测 hook 排在权限 veto 之后，warn 注入提醒、stop 否决收敛

系统 MUST 提供一个 `Hook` 适配器 `LoopDetectionHook`，把纯检测器接入 AgentScope 事件模型，其 `priority()` MUST 使其在权限 veto hook（`priority()=0`）**之后**执行以免干扰。该 hook MUST：
- 在 `PreActingEvent` 上观察待执行工具调用；被禁用（配置 `enabled=false`）或工具名属于哨兵/权限拒绝名等「忽略集」时 SHALL 直通、不计数；
- 判决为 `WARN` 时，SHALL 在随后的 `PreReasoningEvent` 上注入一条 user 侧的临时提醒消息（复用既有 ephemeral 注入路径，MUST NOT 写入持久化历史），告知模型正在重复、应换方法或直接作答，随后放行工具；
- 判决为 `STOP` 时，SHALL 复用既有 veto-to-sentinel 机制——把待执行的 `ToolUseBlock` 改写为一个只读哨兵工具（保留原调用 id），使真实工具不执行、模型收到「陷入循环、请停止并作答」的哨兵结果而被迫收敛。

该 hook MUST 按回合重置检测器：在 `PreReasoningEvent` 上依据输入消息里 user 消息条数的变化识别回合切换并调用 `reset()`，避免把计数泄漏到不相关的下一回合。哨兵工具 MUST 经既有工具注册路径（SPI + 手动兜底）登记，其工具名 MUST 与 hook 用于改写的常量一致（单一事实源，不漂移）。

#### Scenario: warn 时向模型注入一条临时提醒
- **WHEN** 某工具调用被判 `WARN`，且随后触发一个 `PreReasoningEvent`
- **THEN** 该 reasoning 输入被追加一条 user 角色的循环提醒消息（仅本次推理可见、不落持久化历史），提示模型换方法或直接作答

#### Scenario: stop 时改写为哨兵、真实工具不执行
- **WHEN** 某工具调用被判 `STOP`
- **THEN** hook 以哨兵工具名改写该 `PreActingEvent` 的 `ToolUseBlock`（保留原 id），真实工具不被执行，模型收到哨兵返回的收敛提示

#### Scenario: 忽略哨兵与权限拒绝调用、禁用时完全旁路
- **WHEN** 待执行工具名属于忽略集（本哨兵名 / 权限拒绝哨兵名），或配置 `enabled=false`
- **THEN** hook 直通不计数、不注入、不改写（`enabled=false` 等同旧行为，完全旁路）

#### Scenario: 回合切换后计数重置
- **WHEN** `PreReasoningEvent` 输入中的 user 消息条数相较上一次发生变化（新用户回合到来 / 切换会话）
- **THEN** hook 调用检测器 `reset()`，此前逼近阈值的计数被清零，不泄漏到新回合

### Requirement: 循环检测默认启用、默认安全且向后兼容

循环检测 MUST 经配置块 `loop-detection` 控制：`enabled`（默认 true）、`window-size`（默认 20）、`warn-threshold`（默认 3）、`stop-threshold`（默认 5），全部可选。缺失整个 `loop-detection` 块或任一字段时 SHALL 回退到上述安全默认值，旧配置文件 MUST 照常解析（向后兼容）。检测器 MUST NOT 改变任何工具的对外语义，`WARN` 注入与 `STOP` 改写 MUST NOT 污染或增长持久化对话历史。循环检测 MUST 至少接入交互式 agent 的 hook 链。

#### Scenario: 缺配置回退安全默认
- **WHEN** `application.yaml` 中不含 `loop-detection` 块
- **THEN** 循环检测以默认值启用（窗口 20、warn 3、stop 5），旧配置正常加载

#### Scenario: 显式关闭则旁路
- **WHEN** 配置 `loop-detection.enabled=false`
- **THEN** hook 直通、不做任何检测/注入/改写，行为等同引入本能力之前
