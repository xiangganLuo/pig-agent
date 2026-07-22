# cc-repl Specification

## Purpose
TBD - created by archiving change tui-frontend. Update Purpose after archive.
## Requirements
### Requirement: 增强既有 CLI REPL 为唯一终端前端

系统 SHALL 保留 `pig-agent-cli` 的 JLine3 + picocli 行式 REPL（`AgentRepl` / `ReplCommands` / `ReplContext`）作为唯一终端前端，并在其上增强为 Claude Code 风格的行式交互。系统 MUST NOT 引入全屏 TUI，MUST NOT 删除既有 REPL 或任何既有斜杠命令。增强 MUST 复用 CLI 现有的同一批 manager 实例（`AgentKernel` + `ModelManager` / `SessionManager` / `CompressionService` / `McpManager` / `TaskManager` / `ProtocolRegistry` / `ConfigurationManager`），保持单一真相源、零业务逻辑重复。一次对话回合 MUST 保持既有钩子顺序：`noteUserMessage` → `maybeCompress` → `kernel.chat` → `saveCurrent`。

#### Scenario: 启动进入增强 REPL
- **WHEN** 运行 `PigAgentCli.main`
- **THEN** 进入行式 REPL，显示状态行与提示符，既有全部斜杠命令可用，不进入任何全屏界面

#### Scenario: 回合钩子不回归
- **WHEN** 用户发送一条非斜杠消息
- **THEN** 依次记录用户消息 → 按需压缩 → 经 `kernel.chat` 流式返回 → 保存当前会话

### Requirement: 流式富渲染

REPL SHALL 对助手回答做基础 markdown→ANSI 渲染（粗体、行内代码、代码块、列表、标题）并**增量**出字（不整体缓冲到回合结束）；SHALL 将工具调用渲染为缩进块（`⏺ 工具名` + `└ 结果摘要`）——在 `ToolCallStartEvent`/`ToolResultStartEvent` 到达时 SHALL **立即**打印 `⏺ 工具名` 头（每个 tool-call id 只打一次），执行期保留活动指示，结果到达时补 `└ 结果摘要` 体；一次**失败**（`ToolResultState.ERROR`）的工具结果 SHALL 以视觉上区分成功的错误标记（红色 `✗`）渲染。渲染工具块**前** SHALL 先冲出（flush）任何缓冲中的部分答案行（并在 `TextBlockEndEvent` 时冲出），使缓冲文本不会打印在工具块之后而与后续相位粘连乱码。

REPL SHALL 在推理阶段显示进度指示（spinner）；当推理 spinner 运行超过约 6 秒仍无答案文本时，其标签 SHALL 翻为「模型繁忙，重试中…」以提示原生重试在进行（工具执行相位的指示 MUST NOT 翻为重试文案）。

当一次回合的模型流**出错**时，REPL SHALL 渲染一条**本地化、友好**的一行错误（按 HTTP 状态/异常类型映射：限流/上游不可用/鉴权/拒绝/请求无效/模型不存在/超时/网络），且**恰好一次**；该错误行 MUST 经凭据脱敏（`sk-`、`Bearer`、`key=` 赋值，及裸 `AIza…`/`gh[pousr]_…`/`xox…`/JWT 令牌）并有长度上限，MUST NOT 向终端输出原始异常堆栈或泄漏凭据。斜杠命令执行失败 SHALL 打印一行错误、完整堆栈仅入日志文件，MUST NOT 向终端喷堆栈。

凭据（apiKey、MCP 的 env/headers、URL 中的 `?key=…`）MUST NOT 出现在任何渲染输出或日志中。用户提供的会话名 / 模型标签中的控制字符 MUST 在渲染前剥离；行内选择器的每行 SHALL 截断到终端宽度（ANSI 感知、不切断代理对），避免宽标签换行破坏重绘。一次不产生任何答案/工具/子 agent 输出的回合 SHALL 显示一条暗色「[无输出]」标记。回合内 chrome（中断/达上限/全拒）SHALL 本地化。

#### Scenario: 答案富渲染并增量出字
- **WHEN** 助手回答包含 markdown 标记
- **THEN** 终端按 ANSI 渲染粗体/行内代码/代码块/列表/标题，且内容随事件增量显示

#### Scenario: 工具调用块渲染且脱敏
- **WHEN** 一次回合触发工具调用
- **THEN** 以 `⏺ 工具名` + `└ 结果摘要` 缩进显示，且不展示 apiKey / env / headers / 裸令牌等敏感字段

#### Scenario: 工具头即时出现且失败可辨
- **WHEN** 一次工具调用开始（`ToolCallStartEvent`）随后失败（结果 `ERROR`）
- **THEN** `⏺ 工具名` 头在开始时即打印、执行期有活动指示，失败结果以红色 `✗` 体渲染，与成功体视觉区分

#### Scenario: 工具块前先冲出缓冲答案
- **WHEN** 一段无换行结尾的部分答案文本后紧跟一个工具事件
- **THEN** 该缓冲答案先被打印，随后才渲染工具块（不粘连、不乱序）

#### Scenario: 推理超时翻重试文案
- **WHEN** 推理 spinner 运行超过约 6 秒仍无答案文本（原生重试进行中）
- **THEN** 指示标签翻为「模型繁忙，重试中…」；工具执行相位的指示不翻

#### Scenario: 模型出错渲染友好一行且脱敏
- **WHEN** 一次回合的模型流以 HTTP 5xx / 429 / 鉴权 / 网络 / 超时等错误结束
- **THEN** 终端恰好一次显示对应的本地化友好一行，不含原始堆栈，凭据被脱敏（含 URL 中的 `?key=AIza…`）

#### Scenario: 空回合与本地化 chrome
- **WHEN** 一次回合被中断 / 达最大推理轮次 / 全部工具被拒 / 未产生任何输出
- **THEN** 分别显示本地化的「[已中断]」/「[已达最大推理轮次]」/「[所有工具调用被权限策略拒绝]」/「[无输出]」

### Requirement: 斜杠命令补全菜单

REPL SHALL 在用户输入 `/` 时提供补全菜单，覆盖全部斜杠命令（`/model` `/session` `/mcp` `/permission` `/memory` `/compress` `/status` `/agent` `/tasks` `/skills` `/config` `/protocols` `/channels` `/help` `/clear` `/quit`）。补全由 JLine `Completer` + picocli 命令元数据驱动。

类型即听（type-ahead）补全的按键重绑 MUST NOT 干扰普通（非斜杠）输入的即时回显：当当前缓冲**不是**斜杠命令缓冲（`SlashCommands.isCommandBuffer` 为假）时，每个可打印字符 SHALL 在**首次**按键即回显到输入行（不得出现"按两下才出字"）。自定义的 self-insert / 补全触发 SHALL **仅**在斜杠命令缓冲态介入（自动列出候选并随键收窄）；离开斜杠缓冲态后普通聊天输入 SHALL 走原生逐字回显。真 TTY 下方向键在斜杠缓冲态导航候选、否则回落历史的既有行为 MUST 保持。

#### Scenario: 触发斜杠补全
- **WHEN** 用户在提示符输入 `/` 并触发补全
- **THEN** 显示涵盖全部斜杠命令的候选菜单

#### Scenario: 普通输入首键即出字
- **WHEN** 用户在提示符输入一个非斜杠开头的普通字符（如英文字母/数字/标点）
- **THEN** 该字符在首次按键即回显到输入行，无需按第二下；补全菜单不弹出

#### Scenario: 斜杠补全不影响普通输入
- **WHEN** 用户先输入并删除一个斜杠命令后继续输入普通聊天文本
- **THEN** 后续普通字符仍逐字即时回显，补全链路不再介入

### Requirement: 状态行

REPL SHALL 显示一行状态，包含当前 model、session 与权限 mode，并随切换即时反映。凭据 MUST NOT 出现在状态行。

#### Scenario: 状态行反映切换
- **WHEN** 用户切换模型或会话
- **THEN** 状态行显示新的 model / session（及当前权限 mode）

### Requirement: Ctrl-C 打断当前回合

一次对话回合进行中 WHEN 用户按 `Ctrl-C`，REPL SHALL 调用 `AgentKernel.interruptCurrent()` 取消在飞的模型调用、取消当前流式订阅、回到可接受输入的提示符，且 MUST NOT 退出程序。为使中断真实生效，流式 MUST 经 `AgentKernel.chat(activeId, msg)` 订阅（注册可中断回合），而非绕过内核的裸 agent 流。回合执行期的 Ctrl-C MUST NOT 向终端打印 `UserInterruptException` 或任何异常堆栈，仅显示本地化 `[已中断]`。空闲态的 `Ctrl-C` 与 `Ctrl-D` 行为 MUST 保持（丢弃当前行 / 退出）。

#### Scenario: 回合内打断
- **WHEN** 对话流进行中用户按 `Ctrl-C`
- **THEN** 当前模型调用被取消，界面回到提示符，程序继续运行，且不打印异常堆栈

#### Scenario: 空闲态按键不受影响
- **WHEN** 提示符空闲时用户按 `Ctrl-C`（或 `Ctrl-D`）
- **THEN** 丢弃当前行、REPL 存活（或按 `Ctrl-D` 退出）

### Requirement: 轻量行内交互

REPL SHALL 为 `/model`（并可选 `/session`）无参调用提供行内选择器（方向键选择，全程行式、不接管全屏）；破坏性操作 SHALL 沿用 y/N 行内确认。行内选择器在不可交互时 MUST 降级到既有的数字输入选择，不得中断使用。

#### Scenario: 行内选择模型
- **WHEN** 用户执行无参 `/model`
- **THEN** 行内列出模型供方向键选择，选定后经 `ModelManager` + 会话层生效（后续对话使用新模型）

#### Scenario: 破坏性操作确认
- **WHEN** 用户执行删除类操作（如删除模型 / 会话）
- **THEN** 以 y/N 行内确认后才执行，否则取消

### Requirement: 会话进入回放与后台子 agent 可见性

当用户进入一个**已有历史**的会话（REPL 启动恢复当前会话、或 `/session switch` 切到目标会话）时，REPL SHALL 回放该会话槽（`(pig, sessionId)`，经 `getMemory(sessionId).getMessages()` 读取）的**最近 K 条**消息（默认约 8 条，即数轮对话），冠以一行暗色标题（如 `⟳ 已恢复会话「<名称>」· 最近 <n> 条`）。回放 SHALL 为**纯渲染、无副作用**：USER 消息渲染为暗色 `› 文本`、ASSISTANT 文本经 markdown→ANSI 渲染、工具调用/结果经 `⏺ 工具名` / `└ 结果摘要` 渲染；MUST NOT 修改会话内容。回放 MUST 有界（仅最近 K 条，绝不整段转储长历史），文本 MUST 经凭据脱敏且截断（不切断代理对）。空会话 SHALL 不渲染任何内容。回放 SHALL 为**尽力而为**：任何失败被吞并降级，MUST NOT 破坏启动或会话切换本身。

当模型派发一个**后台**子 agent（`agent_spawn` 且 `timeout_seconds=0`，返回 `task_id`、状态常为 `timeout_promoted`）时，REPL SHALL 渲染一行清晰的运行中提示（如 `已派发后台子agent（task <id前8位>）· 运行中，完成后会回报`，暗色、凭据安全），而非静默或转储原始 JSON；后台任务完成时经 `<system-reminder>` 回灌为父 agent 的答案文本 MUST NOT 被抑制。同步子 agent 的嵌套暗色渲染 SHALL 保持不变。凭据 MUST NOT 出现在回放或提示的任何输出中。

#### Scenario: 启动恢复会话回放最近历史

- **WHEN** REPL 启动且当前会话槽已有历史消息
- **THEN** 在提示符前显示暗色标题 + 最近 K 条消息（用户/助手/工具分别渲染），凭据脱敏、有界；若会话为空则不渲染任何内容

#### Scenario: /session switch 回放目标会话

- **WHEN** 用户 `/session switch <id|index>` 切到一个已有历史的会话
- **THEN** 打印 "Switched to…" 后回放目标会话槽的最近 K 条消息；回放失败绝不影响切换本身

#### Scenario: 后台子 agent 派发即可见

- **WHEN** 一次 `agent_spawn` 结果表明是后台任务（含 `task_id` / `timeout_promoted`）
- **THEN** 渲染一行暗色运行中提示（含 task 短 id），不转储原始 JSON，且不抑制后续经 system-reminder 回灌的父答案文本

### Requirement: ESC 键行为

REPL SHALL 支持 `ESC` 键，其语义随状态而定，并与 Ctrl-C 复用同一中断路径以避免逻辑重复：

- **任务执行期**：一次对话回合进行中 WHEN 用户按 `ESC`，REPL SHALL 调用 `AgentKernel.interruptCurrent()` 取消在飞模型调用、取消当前流式订阅、回到提示符并显示本地化 `[已中断]`，效果等效于执行期 Ctrl-C，且 MUST NOT 退出程序、MUST NOT 打印异常堆栈。
- **提示符空闲态**：无回合进行、光标在输入行 WHEN 用户按 `ESC`，REPL SHALL 清空当前输入行（不退出、不产生回合）；空行时为无操作。
- **斜杠补全菜单打开时**：候选菜单展示中 WHEN 用户按 `ESC`，REPL SHALL 清空当前斜杠输入行，从而关闭候选菜单（放弃当前命令、不执行）。

ESC 处理 MUST 仅在真 TTY 安装；非交互/哑终端 SHALL 降级为不处理 ESC（不得因缺失能力抛错或破坏输入）。执行期的 ESC 监听通过字符输入模式（关 `ICANON`/`ISIG`/`ECHO`、保留输出标志）读取按键实现；空闲/菜单态的 ESC 通过 LineReader 键位绑定实现，JLine 的歧义键超时使方向键转义序列不受影响。

#### Scenario: 执行期 ESC 中断回合
- **WHEN** 对话流进行中用户按 `ESC`
- **THEN** 当前模型调用被取消、界面回到提示符、显示 `[已中断]`，程序继续运行且不打印异常堆栈

#### Scenario: 空闲态 ESC 清空输入行
- **WHEN** 提示符空闲且输入行已有文本时用户按 `ESC`
- **THEN** 当前输入行被清空，REPL 存活，不产生对话回合

#### Scenario: 菜单态 ESC 关闭补全
- **WHEN** 斜杠补全候选菜单展示中用户按 `ESC`
- **THEN** 当前斜杠输入行被清空，候选菜单随之关闭，不执行命令

### Requirement: 回合执行期中断不喷异常堆栈

一次对话回合执行期间的中断（Ctrl-C 或 ESC）MUST NOT 向终端输出 `org.jline.reader.UserInterruptException` 或任何异常堆栈；REPL SHALL 仅显示本地化 `[已中断]`。若中断路径产生任何异常，其堆栈 SHALL 仅写入日志文件（`warn`/`debug` 级），MUST NOT 到达终端。空闲态的 Ctrl-C（丢弃当前行）与 Ctrl-D（退出）行为 MUST 保持不变。

#### Scenario: 执行期 Ctrl-C 不打印堆栈
- **WHEN** 一次对话回合执行中用户按 `Ctrl-C`
- **THEN** 终端仅显示 `[已中断]`，绝不出现 `UserInterruptException` 或任何 Java 异常堆栈；堆栈（若有）仅入日志文件

#### Scenario: 空闲态中断键行为不回归
- **WHEN** 提示符空闲时用户按 `Ctrl-C` 或 `Ctrl-D`
- **THEN** 分别丢弃当前行（REPL 存活）/ 退出程序，且不打印异常堆栈

