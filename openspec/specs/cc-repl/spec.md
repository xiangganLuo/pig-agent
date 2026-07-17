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

#### Scenario: 触发斜杠补全
- **WHEN** 用户在提示符输入 `/` 并触发补全
- **THEN** 显示涵盖全部斜杠命令的候选菜单

### Requirement: 状态行

REPL SHALL 显示一行状态，包含当前 model、session 与权限 mode，并随切换即时反映。凭据 MUST NOT 出现在状态行。

#### Scenario: 状态行反映切换
- **WHEN** 用户切换模型或会话
- **THEN** 状态行显示新的 model / session（及当前权限 mode）

### Requirement: Ctrl-C 打断当前回合

一次对话回合进行中 WHEN 用户按 `Ctrl-C`，REPL SHALL 调用 `AgentKernel.interruptCurrent()` 取消在飞的模型调用、取消当前流式订阅、回到可接受输入的提示符，且 MUST NOT 退出程序。为使中断真实生效，流式 MUST 经 `AgentKernel.chat(activeId, msg)` 订阅（注册可中断回合），而非绕过内核的裸 agent 流。空闲态的 `Ctrl-C` 与 `Ctrl-D` 行为 MUST 保持（丢弃当前行 / 退出）。

#### Scenario: 回合内打断
- **WHEN** 对话流进行中用户按 `Ctrl-C`
- **THEN** 当前模型调用被取消，界面回到提示符，程序继续运行

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

